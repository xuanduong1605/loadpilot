package dev.loadpilot.integrations.k6;

import com.fasterxml.jackson.databind.*;
import dev.loadpilot.experiments.*;
import io.kubernetes.client.custom.IntOrString;
import io.kubernetes.client.openapi.JSON;
import io.kubernetes.client.openapi.models.*;
import java.net.*;
import java.time.*;
import java.util.*;

/** Pure fixed-source probe construction. This class cannot create resources, sign reports or run submitted code. */
public final class RuntimeProbePlan {
    public static final String VERSION="runtime-probe-plan-v1",MESSAGE_PATH="/output/loadpilot-probe.json";
    public enum Phase { BASELINE_BEFORE,ALLOWED,BLOCKED,TIMEOUT,VALID_GUARD,LATE_GUARD,EXPIRED_GUARD,DEADLINE,CLOCK,BASELINE_AFTER }
    public record Node(String name,String uid) {}
    /** Explicit owned canary: ready must respond2xx, stall must stay reachable without returning before timeout. */
    public record Canary(String origin,String ip,String readyPath,String stallPath) {}
    public record Prepared(String canonicalJson,String sha256) {}
    private final ObjectMapper json;
    public RuntimeProbePlan(ObjectMapper json){this.json=json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);}

    public Prepared construct(K6PlanEnvelope.Prepared original,UUID probeId,Node node,Canary canary,String allowedUrl,Phase phase,Instant now){
        try {
            String profile=SignedRuntimeEvidence.profile(original,json);var source=json.readTree(original.canonicalJson());
            if(probeId==null || node==null || !SignedRuntimeEvidence.name(node.name()) || !SignedRuntimeEvidence.uid(node.uid()) || phase==null || now==null)throw denied();
            var origin=uri(canary.origin(),true);var ready=path(canary.readyPath());var stall=path(canary.stallPath());
            var canaryIp=ip(canary.ip());String canaryHost=origin.getHost();int canaryPort=port(origin);
            if(literalHost(canaryHost) && !ip(canaryHost).equals(canaryIp))throw denied();
            var allowed=uri(allowedUrl,false);String targetHost=source.path("destination").path("host").asText();int targetPort=source.path("destination").path("port").asInt();
            var targetIp=ip(source.path("destination").path("ip").asText());
            if(!targetHost.equalsIgnoreCase(allowed.getHost()) || targetPort!=port(allowed) || (literalHost(targetHost) && !ip(targetHost).equals(targetIp))
                    || (targetIp.equals(canaryIp) && targetPort==canaryPort) || (targetHost.equalsIgnoreCase(canaryHost) && !targetIp.equals(canaryIp)))throw denied();
            var policy=json.treeToValue(source.path("policy"),K6JobPlan.Policy.class);K6JobPlan.validatePolicy(policy);
            if(!policy.namespace().equals(source.path("clusterBinding").path("namespace").asText()))throw denied();
            int timeout=source.path("requiredSourcePolicy").path("requestTimeoutMs").asInt(-1),startup=source.path("workerClock").path("startupTimeoutSeconds").asInt(-1);
            var job=JSON.<V1Job>deserialize(source.path("job").toString(),V1Job.class);var pod=job.getSpec().getTemplate().getSpec();
            if(pod.getContainers()==null || pod.getContainers().size()!=1 || (pod.getInitContainers()!=null && !pod.getInitContainers().isEmpty())
                    || (pod.getEphemeralContainers()!=null && !pod.getEphemeralContainers().isEmpty()) || !policy.imageDigest().equals(pod.getContainers().getFirst().getImage())
                    || timeout<1 || timeout>120000 || startup<1 || startup>300 || pod.getActiveDeadlineSeconds()==null || pod.getActiveDeadlineSeconds()<5
                    || pod.getTerminationGracePeriodSeconds()==null || pod.getTerminationGracePeriodSeconds()<0 || pod.getTerminationGracePeriodSeconds()>60
                    || job.getSpec().getActiveDeadlineSeconds()==null || job.getSpec().getActiveDeadlineSeconds()>7200
                    || 3L*timeout+1000>=pod.getActiveDeadlineSeconds()*1000L)throw denied();
            if(pod.getAffinity()!=null || pod.getNodeName()!=null)throw denied();
            String name="lpp-"+probeId.toString().replace("-","").substring(0,16)+"-"+phase.ordinal()+"-"+K6PlanEnvelope.hash(node.uid()).substring(0,8);
            var labels=Map.of("app.kubernetes.io/name","loadpilot-runtime-probe","loadpilot.dev/probe",probeId.toString(),"loadpilot.dev/probe-phase",phase.name().toLowerCase(Locale.ROOT),"loadpilot.dev/probe-node",K6PlanEnvelope.hash(node.uid()).substring(0,16));
            var annotations=Map.of("loadpilot.dev/probe-version",VERSION,"loadpilot.dev/runtime-profile",profile,"loadpilot.dev/probe-node-uid",node.uid());
            long maximum=job.getSpec().getActiveDeadlineSeconds();
            var options=new TreeMap<String,Object>();options.put("discardResponseBodies",true);options.put("maxRedirects",0);options.put("insecureSkipTLSVerify",false);
            options.put("systemTags",List.of());options.put("summaryTrendStats",List.of("min","max"));
            options.put("scenarios",Map.of("trial",phase==Phase.CLOCK?Map.of("executor","constant-vus","vus",1,"duration","4s","gracefulStop","0s")
                    :Map.of("executor","shared-iterations","vus",1,"iterations",1,"maxDuration",pod.getActiveDeadlineSeconds()+"s","gracefulStop","0s")));
            String targetUrl=switch(phase){case ALLOWED->allowed.toASCIIString();case BASELINE_BEFORE,BASELINE_AFTER,BLOCKED->origin.toASCIIString()+ready;case TIMEOUT->origin.toASCIIString()+stall;default->null;};
            String program=program(probeId,node,phase,timeout,targetUrl);
            // All request-bearing/clock/deadline phases also reject a late deployment before entering owned code.
            var submitted=(phase==Phase.LATE_GUARD || phase==Phase.EXPIRED_GUARD?now.minusSeconds(startup+60L):now).atOffset(ZoneOffset.UTC);
            var deadline=(phase==Phase.EXPIRED_GUARD?now.minusSeconds(1):now.plusSeconds(maximum+startup+60L)).atOffset(ZoneOffset.UTC);
            var ticket=new RunDispatch.Ticket(probeId,probeId,1,probeId,probeId,probeId,"lp-"+probeId,(int)maximum,deadline,submitted);
            var bounds=new ExecutionBounds(null,1,1,1,50,64,timeout,startup,pod.getTerminationGracePeriodSeconds().intValue(),1);
            String entry=K6RuntimeGuard.entry(ticket,bounds)+summary(probeId,node,phase);
            var files=new V1ConfigMap().apiVersion("v1").kind("ConfigMap").immutable(true).metadata(meta(name,policy.namespace(),labels,annotations))
                    .data(Map.of("scenario.js",program,"entry.js",entry,"options.json",json.writeValueAsString(options)));
            var worker=pod.getContainers().getFirst();worker.command(List.of("k6")).args(List.of("--log-format=raw","--log-output="+(phase==Phase.CLOCK?"stdout":"none"),"run","--quiet","--no-usage-report","--config","/input/options.json","/input/entry.js"))
                    .terminationMessagePath(MESSAGE_PATH).terminationMessagePolicy("File");
            if(pod.getVolumes()==null || pod.getVolumes().size()!=2)throw denied();
            for(var volume:pod.getVolumes())if("input".equals(volume.getName())){if(volume.getConfigMap()==null)throw denied();volume.getConfigMap().name(name);}
            // Affinity retains scheduler + RuntimeClass constraints; nodeName would bypass scheduling.
            pod.affinity(new V1Affinity().nodeAffinity(new V1NodeAffinity().requiredDuringSchedulingIgnoredDuringExecution(new V1NodeSelector().nodeSelectorTerms(List.of(
                    new V1NodeSelectorTerm().matchFields(List.of(new V1NodeSelectorRequirement().key("metadata.name").operator("In").values(List.of(node.name())))))))));
            if(pod.getNodeName()!=null)throw denied();
            List<V1HostAlias> aliases=new ArrayList<>();if(pod.getHostAliases()!=null)aliases.addAll(pod.getHostAliases());
            if(!literalHost(canaryHost) && !canaryHost.equalsIgnoreCase(targetHost))aliases.add(new V1HostAlias().ip(canaryIp.getHostAddress()).hostnames(List.of(canaryHost)));pod.hostAliases(aliases.isEmpty()?null:aliases);
            job.metadata(meta(name,policy.namespace(),labels,annotations));job.getSpec().suspend(false).selector(null).manualSelector(null);
            job.getSpec().getTemplate().metadata(new V1ObjectMeta().labels(labels).annotations(annotations));
            List<V1NetworkPolicyEgressRule> egress=switch(phase){
                case BASELINE_BEFORE,BASELINE_AFTER,TIMEOUT->List.of(rule(canaryIp,canaryPort));
                case ALLOWED,BLOCKED->List.of(rule(targetIp,targetPort));default->List.of();};
            var network=new V1NetworkPolicy().apiVersion("networking.k8s.io/v1").kind("NetworkPolicy").metadata(meta(name,policy.namespace(),labels,annotations))
                    .spec(new V1NetworkPolicySpec().podSelector(new V1LabelSelector().matchLabels(labels)).policyTypes(List.of("Ingress","Egress")).ingress(List.of()).egress(egress));
            var values=new TreeMap<String,Object>();values.put("schemaVersion",VERSION);values.put("profileSha256",profile);values.put("sourcePlanSha256",original.sha256());
            values.put("probeId",probeId.toString());values.put("phase",phase.name());values.put("node",node);values.put("createdAt",now.toString());values.put("prepareUntil",now.plusSeconds(startup).toString());
            values.put("clusterBinding",source.path("clusterBinding"));values.put("requestedHttpCalls",targetUrl==null?0:3);values.put("requestTimeoutMs",timeout);
            values.put("canary",canary);values.put("allowedUrl",allowed.toASCIIString());values.put("job",json.readValue(JSON.serialize(job),Object.class));values.put("files",json.readValue(JSON.serialize(files),Object.class));values.put("network",json.readValue(JSON.serialize(network),Object.class));
            String text=json.writeValueAsString(values);if(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)throw denied();return new Prepared(text,K6PlanEnvelope.hash(text));
        }catch(Exception invalid){throw denied();}
    }
    /** Rebuild from original reviewed bytes; a caller-supplied hash is never authority for different probe code/spec. */
    public JsonNode verify(K6PlanEnvelope.Prepared original,Prepared prepared){
        try {
            if(prepared==null || prepared.canonicalJson()==null || prepared.canonicalJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536 || !K6PlanEnvelope.hash(prepared.canonicalJson()).equals(prepared.sha256()))throw denied();
            var body=json.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(prepared.canonicalJson());
            var rebuilt=construct(original,UUID.fromString(body.path("probeId").asText()),json.treeToValue(body.path("node"),Node.class),json.treeToValue(body.path("canary"),Canary.class),
                    body.path("allowedUrl").asText(),Phase.valueOf(body.path("phase").asText()),Instant.parse(body.path("createdAt").asText()));
            if(!rebuilt.equals(prepared))throw denied();return body;
        }catch(Exception invalid){throw denied();}
    }
    private String program(UUID id,Node node,Phase phase,int timeout,String url) throws Exception {
        String preamble="import http from 'k6/http';\nimport {sleep} from 'k6';\nimport {Counter,Trend} from 'k6/metrics';\n"+
                "const attempts=new Counter('probe_attempts'), successes=new Counter('probe_successes'), responses=new Counter('probe_responses'), timeouts=new Counter('probe_timeouts'), iterations=new Counter('probe_iterations'), elapsed=new Trend('probe_elapsed_ms');\n";
        String body=switch(phase){
            case DEADLINE->"for (;;) {}";
            case CLOCK->"console.log("+json.writeValueAsString("LP_PROBE_CLOCK:"+id+":"+node.uid()+":")+"+Date.now()); sleep(0.1);";
            case VALID_GUARD,LATE_GUARD,EXPIRED_GUARD->"iterations.add(1);";
            default->"for(let i=0;i<3;i++){const before=Date.now();const r=http.get("+json.writeValueAsString(url)+",{timeout:"+json.writeValueAsString(timeout+"ms")+",redirects:0});elapsed.add(Date.now()-before);attempts.add(1);responses.add(r.status>0?1:0);successes.add(r.status>=200&&r.status<300&&(!r.error_code||r.error_code===0)?1:0);timeouts.add(r.error_code===1050?1:0);}";
        };
        return preamble+"export default function(){"+body+"}\n";
    }
    private String summary(UUID id,Node node,Phase phase) throws Exception {
        String identity=json.writeValueAsString(Map.of("schemaVersion","runtime-probe-message-v1","probeId",id.toString(),"nodeUid",node.uid(),"phase",phase.name()));
        return "export function handleSummary(d){const count=n=>d.metrics[n]?d.metrics[n].values.count:0;const trend=d.metrics.probe_elapsed_ms;return {"+json.writeValueAsString(MESSAGE_PATH)+":JSON.stringify({..."+identity+",attempts:count('probe_attempts'),successes:count('probe_successes'),responses:count('probe_responses'),timeouts:count('probe_timeouts'),iterations:count('probe_iterations'),minimumElapsedMs:trend?trend.values.min:null,maximumElapsedMs:trend?trend.values.max:null})};}\n";
    }
    private static V1NetworkPolicyEgressRule rule(InetAddress address,int port){return new V1NetworkPolicyEgressRule().to(List.of(new V1NetworkPolicyPeer().ipBlock(new V1IPBlock().cidr(address.getHostAddress()+(address instanceof Inet6Address?"/128":"/32")))))
            .ports(List.of(new V1NetworkPolicyPort().protocol("TCP").port(new IntOrString(port))));}
    private static V1ObjectMeta meta(String name,String ns,Map<String,String> labels,Map<String,String> annotations){return new V1ObjectMeta().name(name).namespace(ns).labels(labels).annotations(annotations);}
    private static URI uri(String value,boolean origin){
        try{if(value==null || value.length()>2048)throw denied();var uri=URI.create(value);if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || port(uri)<1 || port(uri)>65535 || (origin && uri.getRawPath()!=null && !uri.getRawPath().isEmpty()))throw denied();return uri;}catch(Exception invalid){throw denied();}
    }
    private static String path(String value){if(value==null || value.length()>256 || !value.startsWith("/") || value.startsWith("//") || value.contains("\\") || value.contains("?") || value.contains("#") || value.chars().anyMatch(c->c<33 || c>126))throw denied();return value;}
    private static int port(URI uri){return uri.getPort()<0?("https".equals(uri.getScheme())?443:80):uri.getPort();}
    private static boolean literalHost(String host){return host.contains(":") || host.matches("[0-9.]+");}
    private static InetAddress ip(String value){
        try{String text=value==null?"":value.replace("[","").replace("]","");if(!(text.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}") || (text.contains(":") && text.matches("[a-fA-F0-9:]+"))))throw denied();var address=InetAddress.getByName(text);var raw=address.getAddress();if(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isMulticastAddress() || (raw.length==4 && ((raw[0]&255)==0 || (raw[0]&255)>=224)) || address.getHostAddress().equals("100.100.100.200") || address.getHostAddress().equals("fd00:ec2:0:0:0:0:0:254"))throw denied();return address;}catch(Exception invalid){throw denied();}
    }
    private static IllegalArgumentException denied(){return new IllegalArgumentException("A bounded reviewed runtime profile, exact node and explicit distinct canary scope are required for fixed probes.");}
}
