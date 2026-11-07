package dev.loadpilot.integrations.k6;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import dev.loadpilot.integrations.ConnectionStore;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.JSON;
import io.kubernetes.client.openapi.models.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Explicit operator grants for control-plane access only; never inherits inspection privileges. */
@Component
public class ExecutionClusterClients {
    public interface MaterialSource {ConnectionStore.View view(UUID id);ConnectionStore.KubernetesMaterial kubernetesMaterial(UUID id);}
    public enum Access { READ, STOP, PREPARE, METRICS, ACTIVATE, RECOVER, RUNTIME, PROBE_READ }
    public record Scope(UUID targetId,long connectionRevision,String namespace,String workerName,String jobUid,Access access) {}
    public record Binding(UUID targetId,long connectionRevision,String origin,List<String> addresses,String namespace,List<String> operations,String caSha256) {
        public Binding { addresses=addresses.stream().distinct().sorted().toList();operations=operations.stream().distinct().sorted().toList(); }
    }
    private record Grant(long revision,URI origin,List<InetAddress> addresses,String namespace,Set<Access> operations) {}
    private record Preparation(String path,JsonNode body) {}
    private record Activation(JsonNode patch) {}
    private record Recovery(String path,JsonNode body) {}
    public static final class Problem extends RuntimeException {
        private final int status;
        public Problem(int status,String message){super(message);this.status=status;}
        public int status(){return status;}
    }
    public static final class Session implements AutoCloseable {
        private final ApiClient client;private final long revision;
        Session(ApiClient client,long revision){this.client=client;this.revision=revision;}
        public ApiClient client(){return client;}public long connectionRevision(){return revision;}
        @Override public String toString(){return "ExecutionClusterSession[credentials=redacted]";}
        @Override public void close(){client.getHttpClient().connectionPool().evictAll();client.getHttpClient().dispatcher().executorService().shutdown();}
    }
    private final MaterialSource connections;private final ObjectMapper json;private final ObjectMapper strict;private final Map<UUID,Grant> grants;
    @Autowired public ExecutionClusterClients(@Value("${platform.execution.cluster-grants-json:}") String config,ObjectMapper json,ConnectionStore connections){
        this(config,json,new MaterialSource(){public ConnectionStore.View view(UUID id){return connections.view(id);}public ConnectionStore.KubernetesMaterial kubernetesMaterial(UUID id){return connections.kubernetesMaterial(id);}});
    }
    public ExecutionClusterClients(String config,ObjectMapper json,MaterialSource connections){
        this.connections=connections;this.json=json;this.strict=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);this.grants=parse(config);
    }
    public Binding binding(UUID targetId,String namespace) {
        if(grants.isEmpty()) throw new Problem(503,"Execution cluster access is not configured.");
        var grant=grants.get(targetId);
        if(grant==null || !grant.namespace().equals(namespace)) throw new Problem(403,"Execution cluster scope is not allowed.");
        var view=connections.view(targetId);
        if(view==null || view.revision()!=grant.revision()) throw new Problem(409,"Execution connection revision changed.");
        URI endpoint;try{endpoint=origin(view.kubernetesUrl());}catch(RuntimeException invalid){throw new Problem(403,"Stored execution endpoint is invalid.");}
        if(!endpoint.equals(grant.origin())) throw new Problem(409,"Stored execution endpoint differs from its operator grant.");
        return new Binding(targetId,view.revision(),endpoint.toString(),grant.addresses().stream().map(InetAddress::getHostAddress).toList(),namespace,
                grant.operations().stream().map(Enum::name).toList(),K6PlanEnvelope.hash(view.kubernetesCa()==null?"":view.kubernetesCa()));
    }
    public Session openBound(Scope scope,Binding expected) {
        return openBound(scope,expected,null,null,null,null);
    }
    public Session openActivationRead(Scope scope,Binding expected) {
        if(scope==null || scope.access()!=Access.ACTIVATE) throw new Problem(403,"Explicit activation inspection scope is required.");
        return openBound(scope,expected,null,null,new Activation(null),null);
    }
    public Session openActivationPatch(Scope scope,Binding expected,KubernetesWorkerActivator.Prepared command) {
        KubernetesWorkerActivator.validateCommand(command,json);
        if(scope==null || scope.access()!=Access.ACTIVATE || !Objects.equals(scope.jobUid(),command.jobUid()) || !Objects.equals(scope.namespace(),command.namespace()) || !Objects.equals(scope.workerName(),command.workerName())) throw new Problem(403,"Exact saved activation scope is required.");
        try {return openBound(scope,expected,null,null,new Activation(strict.readTree(command.patchJson())),null);}
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid) {throw new Problem(403,"Exact saved activation patch is required.");}
    }
    /** Exact Pod reads/logs only. The collector must prove the Pod's Job ownership before opening this scope. */
    public Session openWorkerLogs(Scope scope,Binding expected,KubernetesWorkerLogs.PodIdentity pod) {
        if(scope==null || scope.access()!=Access.METRICS || pod==null || !name(pod.name()) || !Objects.equals(scope.namespace(),pod.namespace())
                || !pod.name().startsWith(scope.workerName()+"-") || pod.uid()==null || !pod.uid().matches("[A-Za-z0-9._:-]{1,128}") || !Objects.equals(scope.jobUid(),pod.jobUid()))
            throw new Problem(403,"An exact proven metric Pod scope is required.");
        return openBound(scope,expected,null,pod,null,null);
    }
    /** Resource-specific permission from exact saved plan bytes; never grants activation or arbitrary creates. */
    public Session openPreparation(Scope scope,Binding expected,K6PlanEnvelope.Prepared plan,KubernetesWorkerPreparer.Resource resource) {
        if(scope==null || scope.access()!=Access.PREPARE || scope.jobUid()!=null || expected==null || resource==null || plan==null || plan.canonicalJson()==null
                || plan.canonicalJson().getBytes(StandardCharsets.UTF_8).length>524288 || !K6PlanEnvelope.hash(plan.canonicalJson()).equals(plan.sha256()))
            throw new Problem(403,"Exact suspended resource preparation scope is required.");
        Preparation permission;
        try {
            var envelope=strict.readTree(plan.canonicalJson());
            if(!"k6-execution-plan-v2".equals(envelope.path("schemaVersion").asText()) || !Set.of("job-planner-v2","job-planner-v3","job-planner-v4","job-planner-v5","job-planner-v6").contains(envelope.path("plannerVersion").asText())
                    || envelope.path("submissionReady").asBoolean(true) || !envelope.path("job").path("spec").path("suspend").asBoolean()
                    || !expected.equals(strict.treeToValue(envelope.path("clusterBinding"),Binding.class))
                    || !scope.targetId().equals(expected.targetId()) || !scope.namespace().equals(expected.namespace()) || !scope.workerName().equals(envelope.path("workerName").asText())) throw new IllegalArgumentException();
            String field=switch(resource) {case FILES->"files";case NETWORK->"network";case JOB->"job";};
            var value=envelope.path(field);var metadata=value.path("metadata");
            if(!scope.namespace().equals(metadata.path("namespace").asText()) || !scope.workerName().equals(metadata.path("name").asText())
                    || !envelope.path("submitOperationId").isTextual() || !envelope.path("submitOperationId").asText().equals(metadata.path("annotations").path("loadpilot.dev/submit-operation").asText())) throw new IllegalArgumentException();
            // Compare the exact JSON that the pinned SDK emits, including its quantity serialization.
            Object model=switch(resource) {
                case FILES->JSON.deserialize(value.toString(),V1ConfigMap.class);
                case NETWORK->JSON.deserialize(value.toString(),V1NetworkPolicy.class);
                case JOB->JSON.deserialize(value.toString(),V1Job.class);
            };
            String path=switch(resource) {
                case FILES->"/api/v1/namespaces/"+scope.namespace()+"/configmaps";
                case NETWORK->"/apis/networking.k8s.io/v1/namespaces/"+scope.namespace()+"/networkpolicies";
                case JOB->"/apis/batch/v1/namespaces/"+scope.namespace()+"/jobs";
            };
            var body=strict.readTree(JSON.serialize(model));
            if(resource==KubernetesWorkerPreparer.Resource.FILES && !body.path("immutable").asBoolean()) throw new IllegalArgumentException();
            if(!value.equals(body)) throw new IllegalArgumentException(); // Never normalize away an unreviewed field.
            permission=new Preparation(path,body);
        } catch(Exception invalid) {throw new Problem(403,"Saved resource preparation permission cannot be verified.");}
        return openBound(scope,expected,permission,null,null,null);
    }
    public Session openRecovery(Scope scope,Binding expected,K6PlanEnvelope.Prepared plan,KubernetesWorkerPreparer.Resource resource,KubernetesWorkerRecovery.Delete command){
        try{
            if(scope==null || scope.access()!=Access.RECOVER || expected==null || plan==null || plan.canonicalJson().getBytes(StandardCharsets.UTF_8).length>524288 || !K6PlanEnvelope.hash(plan.canonicalJson()).equals(plan.sha256()))throw new IllegalArgumentException();
            var body=strict.readTree(plan.canonicalJson());if(!expected.equals(strict.treeToValue(body.path("clusterBinding"),Binding.class)) || !scope.workerName().equals(body.path("workerName").asText()) || !scope.namespace().equals(expected.namespace()))throw new IllegalArgumentException();
            String path=KubernetesWorkerRecovery.path(resource,scope.namespace(),scope.workerName());JsonNode delete=null;
            if(command!=null){if(resource!=command.resource() || !scope.namespace().equals(command.namespace()) || !scope.workerName().equals(command.name()))throw new IllegalArgumentException();delete=strict.readTree(JSON.serialize(KubernetesWorkerRecovery.options(command)));}
            return openBound(scope,expected,null,null,null,new Recovery(path,delete));
        }catch(Problem known){throw known;}catch(Exception invalid){throw new Problem(403,"Exact saved resource recovery scope is required.");}
    }
    /** One exact read from the bounded runtime-inspection contract; never a generic cluster reader. */
    public Session openRuntimeRead(Scope scope,Binding expected,K6PlanEnvelope.Prepared plan,String cniNamespace,String cniDaemonset,String path) {
        try {
            if(scope==null || scope.access()!=Access.RUNTIME || expected==null || plan==null || plan.canonicalJson()==null || plan.canonicalJson().getBytes(StandardCharsets.UTF_8).length>524288 || !K6PlanEnvelope.hash(plan.canonicalJson()).equals(plan.sha256()))throw new IllegalArgumentException();
            var body=strict.readTree(plan.canonicalJson());
            if(!expected.equals(strict.treeToValue(body.path("clusterBinding"),Binding.class)) || !scope.workerName().equals(body.path("workerName").asText()) || !scope.namespace().equals(body.path("policy").path("namespace").asText())
                    || !KubernetesRuntimeState.paths(body,cniNamespace,cniDaemonset).contains(path))throw new IllegalArgumentException();
            return openBound(scope,expected,null,null,null,new Recovery(path,null));
        }catch(Problem known){throw known;}catch(Exception invalid){throw new Problem(403,"Exact runtime inspection scope is required.");}
    }
    /** Fixed probe readback only. This permission has no probe creation, activation, deletion or signing authority. */
    public Session openProbeRead(Scope scope,Binding expected,K6PlanEnvelope.Prepared original,RuntimeProbePlan.Prepared probe,String path,String podName) {
        try {
            var body=new RuntimeProbePlan(json).verify(original,probe);
            if(scope==null || scope.access()!=Access.PROBE_READ || expected==null || !expected.equals(strict.treeToValue(body.path("clusterBinding"),Binding.class))
                    || !scope.namespace().equals(expected.namespace()) || !scope.namespace().equals(body.path("job").path("metadata").path("namespace").asText()) || !scope.workerName().equals(body.path("job").path("metadata").path("name").asText())
                    || !KubernetesProbeCollector.paths(body,podName).contains(path))throw new IllegalArgumentException();
            return openBound(scope,expected,null,null,null,new Recovery(path,null));
        }catch(Problem known){throw known;}catch(Exception invalid){throw new Problem(403,"Exact fixed probe inspection scope is required.");}
    }
    private Session openBound(Scope scope,Binding expected,Preparation preparation,KubernetesWorkerLogs.PodIdentity logs,Activation activation,Recovery recovery) {
        if(scope==null) throw new Problem(403,"Execution cluster scope is not allowed.");
        if(expected==null || !expected.equals(binding(scope.targetId(),scope.namespace())) || scope.connectionRevision()!=expected.connectionRevision())
            throw new Problem(409,"Execution cluster binding changed after plan review.");
        var session=open(scope,preparation,logs,activation,recovery);
        // Metadata can change between the public view and credential retrieval. Recheck before returning access.
        try {if(!expected.equals(binding(scope.targetId(),scope.namespace()))) throw new Problem(409,"Execution cluster binding changed after plan review.");return session;}
        catch(RuntimeException changed){session.close();throw changed;}
    }
    public Session open(Scope scope) {
        return open(scope,null,null,null,null);
    }
    private Session open(Scope scope,Preparation preparation,KubernetesWorkerLogs.PodIdentity logs,Activation activation,Recovery recovery) {
        if(grants.isEmpty()) throw new Problem(503,"Execution cluster access is not configured.");
        if(scope==null || !name(scope.namespace()) || !name(scope.workerName()) || scope.access()==null) throw new Problem(403,"Execution cluster scope is not allowed.");
        if(scope.access()==Access.PREPARE && preparation==null) throw new Problem(403,"Exact saved resource permission is required for preparation.");
        if(scope.access()==Access.METRICS && logs==null) throw new Problem(403,"Exact proven Pod permission is required for metrics.");
        if(scope.access()==Access.ACTIVATE && activation==null) throw new Problem(403,"Exact activation inspection or patch permission is required.");
        if(scope.access()==Access.RECOVER && recovery==null)throw new Problem(403,"Exact resource recovery permission is required.");
        if(scope.access()==Access.RUNTIME && (recovery==null || recovery.body()!=null))throw new Problem(403,"Exact runtime inspection permission is required.");
        if(scope.access()==Access.PROBE_READ && (recovery==null || recovery.body()!=null))throw new Problem(403,"Exact probe inspection permission is required.");
        var grant=grants.get(scope.targetId());
        if(grant==null || !grant.namespace().equals(scope.namespace()) || !grant.operations().contains(scope.access())) throw new Problem(403,"Execution cluster scope is not allowed.");
        if(grant.revision()!=scope.connectionRevision()) throw new Problem(409,"Execution connection revision changed.");
        if((scope.access()==Access.STOP || scope.access()==Access.METRICS || scope.access()==Access.ACTIVATE) && (scope.jobUid()==null || !scope.jobUid().matches("[A-Za-z0-9._:-]{1,128}"))) throw new Problem(403,"A bound worker UID is required for cluster control access.");
        var material=connections.kubernetesMaterial(scope.targetId());
        if(material==null || material.revision()!=scope.connectionRevision()) throw new Problem(409,"Execution connection revision changed.");
        URI endpoint;try{endpoint=origin(material.url());}catch(RuntimeException invalid){throw new Problem(403,"Stored execution endpoint is invalid.");}
        if(!endpoint.equals(grant.origin())) throw new Problem(409,"Stored execution endpoint differs from its operator grant.");
        if(material.token()==null || material.token().isBlank() || material.token().contains("\r") || material.token().contains("\n")) throw new Problem(503,"Execution credential is unavailable.");
        try {
            var client=new ApiClient().setBasePath(endpoint.toString());
            if(material.ca()!=null && !material.ca().isBlank()) client.setSslCaCert(new ByteArrayInputStream(material.ca().getBytes(StandardCharsets.US_ASCII)));
            client.setApiKey(material.token());client.setApiKeyPrefix("Bearer");
            String host=endpoint.getHost().replace("[","").replace("]","");int port=endpoint.getPort()<0?443:endpoint.getPort();
            String job="/apis/batch/v1/namespaces/"+scope.namespace()+"/jobs/"+scope.workerName(),pods="/api/v1/namespaces/"+scope.namespace()+"/pods";
            client.setHttpClient(client.getHttpClient().newBuilder().proxy(Proxy.NO_PROXY).dns(requested->{if(!host.equalsIgnoreCase(requested))throw new UnknownHostException("Execution API host changed.");return grant.addresses();})
                    .addInterceptor(chain->{var request=chain.request();var url=request.url();
                        boolean same="https".equals(url.scheme()) && host.equalsIgnoreCase(url.host()) && port==url.port();
                        boolean read=scope.access()==Access.READ && request.method().equals("GET") && ((url.encodedPath().equals(job) && url.query()==null)
                                || (url.encodedPath().equals(pods) && "17".equals(url.queryParameter("limit")) && url.queryParameterNames().equals(Set.of("limit"))));
                        boolean stop=scope.access()==Access.STOP && request.method().equals("DELETE") && url.encodedPath().equals(job) && url.query()==null;
                        boolean prepare=scope.access()==Access.PREPARE && preparation!=null && request.method().equals("POST") && url.encodedPath().equals(preparation.path()) && url.query()==null;
                        String pod=logs==null?"":"/api/v1/namespaces/"+logs.namespace()+"/pods/"+logs.name();
                        boolean metrics=scope.access()==Access.METRICS && logs!=null && request.method().equals("GET") && ((url.encodedPath().equals(pod) && url.query()==null)
                                || (url.encodedPath().equals(pod+"/log") && url.querySize()==6 && url.queryParameterNames().equals(Set.of("container","follow","previous","timestamps","sinceSeconds","limitBytes"))
                                && "k6".equals(url.queryParameter("container")) && "false".equals(url.queryParameter("follow")) && "false".equals(url.queryParameter("previous"))
                                && "true".equals(url.queryParameter("timestamps")) && "20".equals(url.queryParameter("sinceSeconds")) && "262144".equals(url.queryParameter("limitBytes"))));
                        boolean activationRead=scope.access()==Access.ACTIVATE && activation!=null && activation.patch()==null && request.method().equals("GET") && url.query()==null
                                && Set.of(job,"/api/v1/namespaces/"+scope.namespace()+"/configmaps/"+scope.workerName(),"/apis/networking.k8s.io/v1/namespaces/"+scope.namespace()+"/networkpolicies/"+scope.workerName()).contains(url.encodedPath());
                        boolean activationPatch=scope.access()==Access.ACTIVATE && activation!=null && activation.patch()!=null && request.method().equals("PATCH") && url.query()==null && url.encodedPath().equals(job);
                        boolean recover=scope.access()==Access.RECOVER && recovery!=null && url.query()==null && url.encodedPath().equals(recovery.path()) && request.method().equals(recovery.body()==null?"GET":"DELETE");
                        boolean runtimeRead=(scope.access()==Access.RUNTIME || scope.access()==Access.PROBE_READ) && recovery!=null && recovery.body()==null && request.method().equals("GET")
                                && (url.encodedPath()+(url.encodedQuery()==null?"":"?"+url.encodedQuery())).equals(recovery.path());
                        if(!same || (!read && !stop && !prepare && !metrics && !activationRead && !activationPatch && !recover && !runtimeRead)) throw new IOException("Execution API request scope rejected.");
                        if(recover && recovery.body()!=null){var body=request.body();if(body==null || body.contentLength()<0 || body.contentLength()>8192)throw new IOException("Recovery delete rejected.");var buffer=new okio.Buffer();body.writeTo(buffer);if(buffer.size()>8192 || !recovery.body().equals(strict.readTree(buffer.readUtf8())))throw new IOException("Recovery delete differs from its intent.");}
                        if(activationPatch) {
                            var body=request.body();if(body==null || body.contentLength()<0 || body.contentLength()>65536 || body.contentType()==null || !body.contentType().toString().startsWith("application/json-patch+json")) throw new IOException("Execution activation body rejected.");
                            var buffer=new okio.Buffer();body.writeTo(buffer);if(buffer.size()>65536 || !activation.patch().equals(strict.readTree(buffer.readUtf8()))) throw new IOException("Execution activation differs from the saved intent.");
                        }
                        if(prepare) {
                            var body=request.body();if(body==null || body.contentLength()<0 || body.contentLength()>524288) throw new IOException("Execution preparation body rejected.");
                            var buffer=new okio.Buffer();body.writeTo(buffer);
                            if(buffer.size()>524288 || !preparation.body().equals(strict.readTree(buffer.readUtf8()))) throw new IOException("Execution preparation body differs from the saved resource.");
                        }
                        if(stop){var body=request.body();if(body==null || body.contentLength()<0 || body.contentLength()>8192)throw new IOException("Execution stop body rejected.");
                            var buffer=new okio.Buffer();body.writeTo(buffer);var value=json.readTree(buffer.readUtf8());
                            if(!scope.jobUid().equals(value.path("preconditions").path("uid").asText()) || !"Foreground".equals(value.path("propagationPolicy").asText()) || value.has("gracePeriodSeconds"))throw new IOException("Execution stop identity rejected.");}
                        return chain.proceed(request);
                    }).build());
            return new Session(KubernetesWorkerTransport.bound(client),material.revision());
        } catch(RuntimeException invalid){throw new Problem(503,"Execution TLS client could not be configured.");}
    }
    private Map<UUID,Grant> parse(String config){
        if(config==null || config.isBlank())return Map.of();
        if(config.getBytes(StandardCharsets.UTF_8).length>16384)throw invalid();
        try{var body=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(config);
            if(!body.isArray() || body.size()>16)throw invalid();Map<UUID,Grant> result=new HashMap<>();
            for(var entry:body){if(!entry.isObject() || !entry.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()).equals(Set.of("targetId","connectionRevision","origin","addresses","namespace","operations")))throw invalid();
                if(!entry.path("targetId").isTextual() || !entry.path("origin").isTextual() || !entry.path("namespace").isTextual() || !entry.path("connectionRevision").isIntegralNumber() || !entry.path("connectionRevision").canConvertToLong() || entry.path("connectionRevision").asLong()<1)throw invalid();
                var endpoint=origin(entry.path("origin").asText());String namespace=entry.path("namespace").asText();if(!name(namespace) || Set.of("default","kube-system","kube-public","kube-node-lease").contains(namespace))throw invalid();
                var ips=entry.path("addresses");if(!ips.isArray() || ips.isEmpty() || ips.size()>16)throw invalid();List<InetAddress> addresses=new ArrayList<>();
                for(var value:ips){String ip=value.asText();if(!value.isTextual() || !(ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}") || (ip.contains(":") && ip.matches("[a-fA-F0-9:]+"))))throw invalid();var address=InetAddress.getByName(ip);
                    var raw=address.getAddress();if(address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress() || (raw.length==4 && ((raw[0]&255)==0 || (raw[0]&255)>=224)) || address.getHostAddress().equals("100.100.100.200") || address.getHostAddress().equals("fd00:ec2:0:0:0:0:0:254"))throw invalid();addresses.add(address);}
                var ops=entry.path("operations");if(!ops.isArray() || ops.isEmpty() || ops.size()>Access.values().length)throw invalid();Set<Access> access=new HashSet<>();for(var op:ops){if(!op.isTextual() || !access.add(Access.valueOf(op.asText())))throw invalid();}
                if(result.put(UUID.fromString(entry.path("targetId").asText()),new Grant(entry.path("connectionRevision").asLong(),endpoint,List.copyOf(addresses),namespace,Set.copyOf(access)))!=null)throw invalid();}
            return Map.copyOf(result);
        }catch(Exception error){throw invalid();}
    }
    private static URI origin(String value){var uri=URI.create(value);if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || uri.getPort()==0 || uri.getPort()>65535 || !(uri.getPath()==null || uri.getPath().isEmpty() || uri.getPath().equals("/")))throw invalid();return URI.create(value.replaceAll("/+$",""));}
    private static boolean name(String value){return value!=null && value.length()<=63 && value.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?");}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid explicit execution cluster grants.");}
}
