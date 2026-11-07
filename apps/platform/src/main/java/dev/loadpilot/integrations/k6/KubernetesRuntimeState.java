package dev.loadpilot.integrations.k6;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import okhttp3.Request;

/** Read-only, complete bounded readbacks. Dynamic resource versions/heartbeat times are not configuration identity. */
public final class KubernetesRuntimeState {
    private final ExecutionClusterClients clients;
    private final ObjectMapper json;
    private final String cniNamespace,cniDaemonset;
    public KubernetesRuntimeState(ExecutionClusterClients clients,ObjectMapper json,String cniNamespace,String cniDaemonset) {
        this.clients=clients;this.json=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        if(!SignedRuntimeEvidence.name(cniNamespace) || !SignedRuntimeEvidence.name(cniDaemonset))throw new IllegalArgumentException("Exact CNI DaemonSet identity is required.");
        this.cniNamespace=cniNamespace;this.cniDaemonset=cniDaemonset;
    }
    public static List<String> paths(JsonNode plan,String cniNamespace,String cniDaemonset) {
        String ns=plan.path("policy").path("namespace").asText(),sa=plan.path("policy").path("serviceAccount").asText(),runtime=plan.path("policy").path("runtimeClass").asText();
        for(String name:List.of(ns,sa,runtime,cniNamespace,cniDaemonset))if(!SignedRuntimeEvidence.name(name))throw SignedRuntimeEvidence.denied();
        return List.of("/api/v1/nodes?limit=33","/api/v1/namespaces/"+ns,"/api/v1/namespaces/"+ns+"/serviceaccounts/"+sa,
                "/apis/node.k8s.io/v1/runtimeclasses/"+runtime,"/apis/networking.k8s.io/v1/namespaces/"+ns+"/networkpolicies?limit=33",
                "/apis/apps/v1/namespaces/"+cniNamespace+"/daemonsets/"+cniDaemonset,"/api/v1/namespaces/"+cniNamespace+"/pods?limit=65");
    }
    public String read(K6PlanEnvelope.Prepared plan,List<SignedRuntimeEvidence.NodeProbe> probes) {
        return readForNodes(plan,probes.stream().map(p->new RuntimeProbePlan.Node(p.name(),p.uid())).toList());
    }
    /** Baseline read before probes exist; identities are checked against the complete current node list. */
    public String readForNodes(K6PlanEnvelope.Prepared plan,List<RuntimeProbePlan.Node> nodes) {
        try {
            var body=json.readTree(plan.canonicalJson());var paths=paths(body,cniNamespace,cniDaemonset);
            var binding=json.treeToValue(body.path("clusterBinding"),ExecutionClusterClients.Binding.class);
            var scope=new ExecutionClusterClients.Scope(binding.targetId(),binding.connectionRevision(),binding.namespace(),body.path("workerName").asText(),null,ExecutionClusterClients.Access.RUNTIME);
            Map<String,JsonNode> values=new TreeMap<>();long began=System.nanoTime();
            for(String path:paths){
                if(System.nanoTime()-began>10_000_000_000L)throw SignedRuntimeEvidence.denied();
                try(var session=clients.openRuntimeRead(scope,binding,plan,cniNamespace,cniDaemonset,path);
                    var response=session.client().getHttpClient().newCall(new Request.Builder().url(binding.origin()+path).get().build()).execute()){
                    if(response.code()!=200 || response.body()==null)throw SignedRuntimeEvidence.denied();values.put(path,json.readTree(response.body().string()));
                }
            }
            return digestForNodes(body,values,cniNamespace,cniDaemonset,nodes,json);
        }catch(Exception invalid){throw SignedRuntimeEvidence.denied();}
    }
    /** Also usable by a trusted out-of-band probe issuer to bind its signed artifact to these readbacks. */
    public static String digest(JsonNode plan,Map<String,JsonNode> reads,String cniNamespace,String cniDaemonset,List<SignedRuntimeEvidence.NodeProbe> probes,ObjectMapper mapper) {
        return digestForNodes(plan,reads,cniNamespace,cniDaemonset,probes.stream().map(p->new RuntimeProbePlan.Node(p.name(),p.uid())).toList(),mapper);
    }
    public static String digestForNodes(JsonNode plan,Map<String,JsonNode> reads,String cniNamespace,String cniDaemonset,List<RuntimeProbePlan.Node> probes,ObjectMapper mapper) {
        try {
            var paths=paths(plan,cniNamespace,cniDaemonset);if(!reads.keySet().equals(new HashSet<>(paths)))throw SignedRuntimeEvidence.denied();
            Map<String,String> expected=new HashMap<>();for(var probe:probes)if(expected.put(probe.name(),probe.uid())!=null)throw SignedRuntimeEvidence.denied();
            var nodes=items(reads.get(paths.get(0)),32);if(nodes.isEmpty() || nodes.size()!=expected.size())throw SignedRuntimeEvidence.denied();
            Set<String> nodeNames=new HashSet<>();Map<String,Object> normalized=new TreeMap<>();List<Object> nodeValues=new ArrayList<>();
            for(var node:nodes){
                var meta=node.path("metadata");String name=meta.path("name").asText();
                if(!nodeNames.add(name) || !Objects.equals(expected.get(name),meta.path("uid").asText()) || !ready(node)
                        || node.path("status").path("nodeInfo").path("bootID").asText().isBlank()
                        || node.path("status").path("nodeInfo").path("containerRuntimeVersion").asText().isBlank())throw SignedRuntimeEvidence.denied();
                var copy=(ObjectNode)node.deepCopy();var status=mapper.createObjectNode();status.set("nodeInfo",node.path("status").path("nodeInfo"));status.set("conditions",node.path("status").path("conditions"));copy.set("status",status);nodeValues.add(clean(copy,mapper));
            }
            normalized.put("nodes",sorted(nodeValues,mapper));
            for(int index:List.of(1,2,3,5)){
                var value=reads.get(paths.get(index));if(!SignedRuntimeEvidence.uid(value.path("metadata").path("uid").asText()) || value.path("metadata").hasNonNull("deletionTimestamp"))throw SignedRuntimeEvidence.denied();
                String expectedName=switch(index){case 1->plan.path("policy").path("namespace").asText();case 2->plan.path("policy").path("serviceAccount").asText();case 3->plan.path("policy").path("runtimeClass").asText();default->cniDaemonset;};
                if(!expectedName.equals(value.path("metadata").path("name").asText()))throw SignedRuntimeEvidence.denied();normalized.put(paths.get(index),clean(value,mapper));
            }
            var ds=reads.get(paths.get(5));if(ds.path("status").path("observedGeneration").asLong(-1)<ds.path("metadata").path("generation").asLong(0)
                    || ds.path("status").path("numberReady").asInt(-1)<nodes.size() || ds.path("status").path("numberUnavailable").asInt(0)!=0)throw SignedRuntimeEvidence.denied();
            List<Object> policies=new ArrayList<>();
            for(var policy:items(reads.get(paths.get(4)),32)){
                if(policy.path("metadata").path("name").equals(plan.path("network").path("metadata").path("name"))){
                    if(!policy.path("spec").equals(plan.path("network").path("spec")))throw SignedRuntimeEvidence.denied();
                    for(var entry:plan.path("network").path("metadata").path("labels").properties())if(!entry.getValue().equals(policy.path("metadata").path("labels").path(entry.getKey())))throw SignedRuntimeEvidence.denied();
                }else {
                    // Egress rules are additive. Per-run trace values must not evade a probe through another policy.
                    if(policy.path("spec").path("egress").isArray() && !policy.path("spec").path("egress").isEmpty())throw SignedRuntimeEvidence.denied();
                    policies.add(clean(policy,mapper));
                }
            }
            normalized.put("otherNetworkPolicies",sorted(policies,mapper));
            List<Object> cniPods=new ArrayList<>();Set<String> covered=new HashSet<>();String dsUid=ds.path("metadata").path("uid").asText();
            for(var pod:items(reads.get(paths.get(6)),64)){
                boolean owned=false;for(var owner:pod.path("metadata").path("ownerReferences"))if(dsUid.equals(owner.path("uid").asText()) && "DaemonSet".equals(owner.path("kind").asText()) && owner.path("controller").asBoolean())owned=true;
                if(!owned)continue;String node=pod.path("spec").path("nodeName").asText();
                if(!covered.add(node) || !nodeNames.contains(node) || !ready(pod) || pod.path("metadata").hasNonNull("deletionTimestamp") || pod.path("status").path("containerStatuses").isEmpty())throw SignedRuntimeEvidence.denied();
                for(var container:pod.path("status").path("containerStatuses"))if(!container.path("ready").asBoolean() || container.path("imageID").asText().isBlank())throw SignedRuntimeEvidence.denied();
                cniPods.add(clean(pod,mapper));
            }
            if(!covered.equals(nodeNames))throw SignedRuntimeEvidence.denied();normalized.put("cniPods",sorted(cniPods,mapper));
            var json=mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);return K6PlanEnvelope.hash(json.writeValueAsString(normalized));
        }catch(Exception invalid){throw SignedRuntimeEvidence.denied();}
    }
    private static List<JsonNode> items(JsonNode list,int maximum){
        if(list==null || !list.path("metadata").isObject() || !list.path("items").isArray() || list.path("items").size()>maximum
                || !list.path("metadata").path("continue").asText("").isEmpty() || list.path("metadata").path("remainingItemCount").asLong(0)>0)throw SignedRuntimeEvidence.denied();
        List<JsonNode> items=new ArrayList<>();list.path("items").forEach(items::add);return items;
    }
    private static boolean ready(JsonNode resource){
        int ready=0;for(var condition:resource.path("status").path("conditions"))if("Ready".equals(condition.path("type").asText()) && "True".equals(condition.path("status").asText()))ready++;
        return ready==1 && !resource.path("metadata").hasNonNull("deletionTimestamp");
    }
    private static Object clean(JsonNode value,ObjectMapper mapper){
        var copy=value.deepCopy();strip(copy);return mapper.convertValue(copy,Object.class);
    }
    private static void strip(JsonNode value){
        if(value.isObject()){
            ((ObjectNode)value).remove(List.of("resourceVersion","managedFields","lastHeartbeatTime","lastTransitionTime","creationTimestamp"));
            value.elements().forEachRemaining(KubernetesRuntimeState::strip);
        }else if(value.isArray())value.elements().forEachRemaining(KubernetesRuntimeState::strip);
    }
    private static List<Object> sorted(List<Object> values,ObjectMapper json){
        values.sort(Comparator.comparing(value->{try{return json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value);}catch(Exception invalid){throw SignedRuntimeEvidence.denied();}}));return values;
    }
}
