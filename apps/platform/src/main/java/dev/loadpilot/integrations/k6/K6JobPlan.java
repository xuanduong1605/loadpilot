package dev.loadpilot.integrations.k6;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.experiments.*;
import io.kubernetes.client.custom.IntOrString;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.*;
import jakarta.validation.Validator;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Pure object construction. No Kubernetes client, DNS lookup, script execution or submit capability. */
public final class K6JobPlan {
    public record Policy(String namespace, String imageDigest, String serviceAccount, String runtimeClass, int temporaryStorageMiB) {}
    /** Must come from future execution-specific allowlist/rebinding preflight; construction is not approval. */
    public record PinnedTarget(String host, String ip, int port) {}
    public record Plan(V1Job job, V1ConfigMap files, V1NetworkPolicy network, String optionsJson, boolean submissionReady) {}
    private final ObjectMapper json;
    private final Validator validator;
    public K6JobPlan(ObjectMapper json, Validator validator) { this.json=json.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);this.validator=validator; }
    public Plan construct(ExperimentSnapshots.Snapshot snapshot, RunDispatch.Ticket ticket, Policy policy, PinnedTarget target, Instant now) {
        var input=snapshot.inputs();
        require("experiment-input-v5".equals(input.schemaVersion()) && "SINGLE_RUN".equals(input.mode()),"A current single-run snapshot is required.");
        require(snapshot.id().equals(ticket.snapshotId()) && ticket.workerName().equals("lp-"+ticket.attemptId()),"Ticket does not match the reviewed snapshot and worker identity.");
        require(ticket.revision()>0 && ticket.runId()!=null && ticket.shardId()!=null && ticket.submitOperationId()!=null,"Incomplete dispatch identity.");
        require(input.settings()!=null && input.executionBounds()!=null && input.loadProfile()!=null && input.sloPolicy()!=null,"Complete effective inputs are required.");
        require(validator.validate(input.settings()).isEmpty() && validator.validate(input.executionBounds()).isEmpty() && validator.validate(input.loadProfile()).isEmpty() && validator.validate(input.sloPolicy()).isEmpty(),"Invalid effective inputs.");
        require(new ExperimentSettingsApi().validateEffective(new ExperimentSettingsApi.EffectiveInput(input.settings(),input.executionBounds(),input.loadProfile(),input.sloPolicy())).valid(),"Effective settings are inconsistent.");
        var bounds=input.executionBounds();var profile=input.loadProfile();
        require(profile.mode()==LoadProfile.Mode.SINGLE_RUN && ticket.maximumSeconds()==bounds.maximumAttemptSeconds(input.settings()),"Ticket limits do not match reviewed inputs.");
        require(!ticket.deadlineAt().toInstant().isBefore(now.plusSeconds(ticket.maximumSeconds())),"Insufficient remaining run budget.");
        validatePolicy(policy);
        var uri=URI.create(input.target().baseUrl());
        int port=uri.getPort()>0?uri.getPort():"https".equals(uri.getScheme())?443:80;
        require(("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost()!=null && uri.getPort()!=0 && uri.getPort()<=65535 && uri.getUserInfo()==null && uri.getRawQuery()==null && uri.getRawFragment()==null,"Invalid reviewed target URL.");
        require(uri.getHost().equalsIgnoreCase(target.host()) && port==target.port(),"Pinned destination differs from the reviewed target origin.");
        var address=literal(target.ip());
        require(!address.isAnyLocalAddress() && !address.isLoopbackAddress() && !address.isLinkLocalAddress() && !address.isMulticastAddress(),"Unsafe pinned destination address.");
        var script=input.scenario().script();
        require(script.approved() && script.source().getBytes(StandardCharsets.UTF_8).length<=65536 && hash(script.source()).equals(script.sha256()),"Approved exact-source integrity failed.");
        var labels=Map.of("app.kubernetes.io/name","loadpilot-k6","loadpilot.dev/run",ticket.runId().toString(),"loadpilot.dev/attempt",ticket.attemptId().toString(),"loadpilot.dev/shard",ticket.shardId().toString());
        var annotations=Map.of("loadpilot.dev/snapshot",snapshot.id().toString(),"loadpilot.dev/snapshot-sha256",snapshot.sha256(),"loadpilot.dev/script-sha256",script.sha256(),"loadpilot.dev/submit-operation",ticket.submitOperationId().toString());
        var scenario=new LinkedHashMap<String,Object>();
        scenario.put("executor",profile.executor());scenario.put("timeUnit","1s");scenario.put("preAllocatedVUs",bounds.preAllocatedVUs());scenario.put("maxVUs",bounds.maximumVUs());scenario.put("gracefulStop",bounds.gracefulStopSeconds()+"s");
        if(profile.shape()==LoadProfile.Shape.STEADY) { scenario.put("rate",profile.startRate());scenario.put("duration",input.settings().durationSeconds()+"s"); }
        else { scenario.put("startRate",profile.startRate());scenario.put("stages",profile.stages().stream().map(stage->Map.of("duration",stage.durationSeconds()+"s","target",stage.targetRate())).toList()); }
        String options=encode(Map.of("scenarios",Map.of("trial",scenario),"discardResponseBodies",true,"maxRedirects",0,"insecureSkipTLSVerify",false,"systemTags",List.of("status","error_code","scenario"),"summaryTrendStats",List.of("min","max","avg","med","p(90)","p(95)","p(99)")));
        String entry=K6RuntimeGuard.entry(ticket,bounds)+K6TerminalSummary.summaryFunction(json,ticket,snapshot.sha256(),script.sha256());
        var files=new V1ConfigMap().apiVersion("v1").kind("ConfigMap").immutable(true).metadata(meta(ticket.workerName(),policy.namespace(),labels,annotations))
                .data(Map.of("scenario.js",script.source(),"entry.js",entry,"options.json",options));
        var quantities=Map.of("cpu",new Quantity(bounds.generatorCpuMillicores()+"m"),"memory",new Quantity(bounds.generatorMemoryMiB()+"Mi"),"ephemeral-storage",new Quantity(policy.temporaryStorageMiB()+"Mi"));
        var security=new V1SecurityContext().runAsNonRoot(true).runAsUser(65532L).runAsGroup(65532L).readOnlyRootFilesystem(true).allowPrivilegeEscalation(false)
                .capabilities(new V1Capabilities().drop(List.of("ALL"))).seccompProfile(new V1SeccompProfile().type("RuntimeDefault"));
        var worker=new V1Container().name("k6").image(policy.imageDigest()).imagePullPolicy("IfNotPresent").command(List.of("k6"))
                .terminationMessagePath(K6TerminalSummary.PATH).terminationMessagePolicy("File")
                .args(List.of("--log-output=none","run","--quiet","--no-usage-report","--out","json=-","--config","/input/options.json","/input/entry.js"))
                .resources(new V1ResourceRequirements().requests(quantities).limits(quantities)).securityContext(security)
                .env(List.of(new V1EnvVar().name("K6_NO_COLOR").value("true"),new V1EnvVar().name("K6_ADDRESS").value(""),new V1EnvVar().name("TMPDIR").value("/output")))
                .volumeMounts(List.of(new V1VolumeMount().name("input").mountPath("/input").readOnly(true),new V1VolumeMount().name("output").mountPath("/output")));
        // Reserve the reviewed termination grace inside the total attempt ceiling.
        var pod=new V1PodSpec().containers(List.of(worker)).restartPolicy("Never").activeDeadlineSeconds((long)ticket.maximumSeconds()-bounds.gracefulStopSeconds()).automountServiceAccountToken(false).serviceAccountName(policy.serviceAccount()).runtimeClassName(policy.runtimeClass())
                .hostNetwork(false).hostPID(false).hostIPC(false).enableServiceLinks(false).dnsPolicy("None").dnsConfig(new V1PodDNSConfig().nameservers(List.of("127.0.0.1")))
                .terminationGracePeriodSeconds(bounds.gracefulStopSeconds().longValue()).securityContext(new V1PodSecurityContext().fsGroup(65532L).runAsNonRoot(true).seccompProfile(new V1SeccompProfile().type("RuntimeDefault")))
                .volumes(List.of(new V1Volume().name("input").configMap(new V1ConfigMapVolumeSource().name(ticket.workerName()).defaultMode(0444)),new V1Volume().name("output").emptyDir(new V1EmptyDirVolumeSource().sizeLimit(new Quantity(policy.temporaryStorageMiB()+"Mi")))));
        String host=target.host().replace("[","").replace("]","");
        if(host.contains(":") || host.matches("[0-9.]+")) require(literal(host).equals(address),"A literal target must match the pinned address.");
        else pod.hostAliases(List.of(new V1HostAlias().ip(address.getHostAddress()).hostnames(List.of(host))));
        var job=new V1Job().apiVersion("batch/v1").kind("Job").metadata(meta(ticket.workerName(),policy.namespace(),labels,annotations))
                .spec(new V1JobSpec().suspend(true).parallelism(1).completions(1).backoffLimit(0).podReplacementPolicy("Failed").activeDeadlineSeconds((long)ticket.maximumSeconds())
                        .template(new V1PodTemplateSpec().metadata(new V1ObjectMeta().labels(labels).annotations(annotations)).spec(pod)));
        var network=new V1NetworkPolicy().apiVersion("networking.k8s.io/v1").kind("NetworkPolicy").metadata(meta(ticket.workerName(),policy.namespace(),labels,annotations))
                .spec(new V1NetworkPolicySpec().podSelector(new V1LabelSelector().matchLabels(Map.of("loadpilot.dev/attempt",ticket.attemptId().toString())))
                        .policyTypes(List.of("Ingress","Egress")).ingress(List.of()).egress(List.of(new V1NetworkPolicyEgressRule()
                                .to(List.of(new V1NetworkPolicyPeer().ipBlock(new V1IPBlock().cidr(address.getHostAddress()+(address instanceof Inet6Address?"/128":"/32")))))
                                .ports(List.of(new V1NetworkPolicyPort().protocol("TCP").port(new IntOrString(port)))))));
        return new Plan(job,files,network,options,false);
    }
    private static boolean dnsName(String name) { return name!=null && name.length()<=63 && name.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"); }
    public static void validatePolicy(Policy policy) {
        require(policy!=null && dnsName(policy.namespace()) && dnsName(policy.serviceAccount()) && dnsName(policy.runtimeClass()),"Explicit execution namespace, service account and runtime class are required.");
        require(!Set.of("default","kube-system","kube-public","kube-node-lease").contains(policy.namespace()),"Execution workers require a dedicated namespace.");
        require(!policy.serviceAccount().equals("default"),"Execution workers require an explicit non-default service account.");
        require(policy.imageDigest()!=null && policy.imageDigest().length()<=512 && policy.imageDigest().matches("[a-z0-9]+(?:[._-]+[a-z0-9]+)*(?::[1-9][0-9]{0,4})?(?:/[a-z0-9]+(?:[._-]+[a-z0-9]+)*)+@sha256:[a-f0-9]{64}"),"An immutable runtime image digest is required.");
        String registry=policy.imageDigest().substring(0,policy.imageDigest().indexOf('/'));
        if(registry.contains(":")) require(Integer.parseInt(registry.substring(registry.indexOf(':')+1))<=65535,"Invalid runtime registry port.");
        require(policy.temporaryStorageMiB()>=16 && policy.temporaryStorageMiB()<=256,"Temporary storage policy must be bounded to 16–256 MiB.");
    }
    private static InetAddress literal(String value) {
        require(value!=null && (value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}") || (value.contains(":") && value.matches("[a-fA-F0-9:]+"))),"A literal pinned IP is required; the planner does not resolve DNS.");
        try { return InetAddress.getByName(value); } catch(UnknownHostException invalid) { throw new IllegalArgumentException("Invalid pinned IP."); }
    }
    private static V1ObjectMeta meta(String name,String namespace,Map<String,String> labels,Map<String,String> annotations) { return new V1ObjectMeta().name(name).namespace(namespace).labels(labels).annotations(annotations); }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception invalid) { throw new IllegalArgumentException("Cannot encode k6 configuration.",invalid); } }
    private static String hash(String source) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))); } catch(Exception impossible) { throw new IllegalStateException(impossible); } }
    private static void require(boolean condition,String message) { if(!condition) throw new IllegalArgumentException(message); }
}
