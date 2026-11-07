package dev.loadpilot.experiments;

import dev.loadpilot.integrations.*;
import dev.loadpilot.integrations.k6.K6LiveHttpMetrics;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Current target resource observations for a run, never a completed load measurement or SLO verdict. */
@Service
public class RunMetrics {
    public record Resources(String state,String message,String source,String scope,String namespace,String deployment,String container,
                            String workloadUid,long targetRevision,long connectionRevision,Instant collectedAt,Instant oldestSampleAt,Instant newestSampleAt,Integer selectedPods,Integer readyPods,
                            BigDecimal cpuMillicores,BigDecimal memoryBytes,List<Inspection.Pod> pods) {}
    public record View(UUID runId,long runRevision,RunLifecycle.Status status,String verdict,int pollAfterSeconds,
                       String requestedLoadUnit,Integer requestedMeasurementRate,Resources resources,K6LiveHttpMetrics.Snapshot http) {}
    private final RunIntents runs;private final ExperimentSnapshots.Store snapshots;private final SystemInspector inspector;private final WorkerHttpMetrics http;
    public RunMetrics(RunIntents runs,ExperimentSnapshots.Store snapshots,SystemInspector inspector,WorkerHttpMetrics http) {this.runs=runs;this.snapshots=snapshots;this.inspector=inspector;this.http=http;}
    @Transactional(propagation=Propagation.NEVER)
    public View read(UUID runId) {
        var run=runs.get(runId);var input=snapshots.get(run.snapshotId()).inputs();var target=input.target();
        if(!collecting(run)) return view(run,input,empty(target,run.status().terminal()?"RUN_ENDED":"WAITING",run.status().terminal()?"Live polling has ended; these are not retained test results.":"Waiting for a running worker."));
        Inspection observed;
        try {observed=inspector.resources(run.targetId());}
        catch(ConnectionProblem unavailable) {return view(runs.get(runId),input,empty(target,unavailable.code(),"Resource collection is unavailable. Retry after the polling interval."));}
        var current=runs.get(runId);
        if(!collecting(current)) return view(current,input,empty(target,"RUN_ENDED","Live polling has ended; these are not retained test results."));
        if(observed==null || !target.id().equals(observed.targetId()) || target.revision()!=observed.targetRevision())
            return view(current,input,empty(target,"SCOPE_CHANGED","System metadata changed after review. Current resources cannot be attributed to this run."));
        boolean kubeReady=observed.kubernetes()!=null && "READY".equals(observed.kubernetes().state());
        var pods=observed.pods()==null?List.<Inspection.Pod>of():observed.pods();
        String state=observed.metrics()==null?"UNAVAILABLE":observed.metrics().state();
        boolean valid=kubeReady && "READY".equals(state) && !pods.isEmpty();
        Instant now=Instant.now(),oldest=null,newest=null;BigDecimal cpu=BigDecimal.ZERO,memory=BigDecimal.ZERO;var names=new HashSet<String>();
        for(var pod:pods) {
            var usage=pod==null?null:pod.usage();
            if(usage==null || pod.name()==null || !names.add(pod.name()) || !"READY".equals(usage.state()) || usage.timestamp()==null || usage.timestamp().isBefore(now.minusSeconds(120)) || usage.timestamp().isAfter(now.plusSeconds(5))
                    || usage.cpuMillicores()==null || usage.memoryBytes()==null || usage.cpuMillicores().signum()<0 || usage.memoryBytes().signum()<0) {valid=false;continue;}
            cpu=cpu.add(usage.cpuMillicores());memory=memory.add(usage.memoryBytes());
            if(oldest==null || usage.timestamp().isBefore(oldest)) oldest=usage.timestamp();
            if(newest==null || usage.timestamp().isAfter(newest)) newest=usage.timestamp();
        }
        if(!valid && "READY".equals(state)) state="INCOMPLETE";
        var resources=new Resources(state,valid?"Current selected-container samples; not the experiment measurement window.":"Required selected-container samples are missing, stale or unavailable.",
                "Kubernetes PodMetrics","CURRENT_TARGET_SELECTED_CONTAINER",target.namespace(),target.deployment(),target.container(),observed.workload()==null?null:observed.workload().uid(),target.revision(),observed.connectionRevision(),observed.collectedAt(),oldest,newest,
                kubeReady?pods.size():null,kubeReady?(int)pods.stream().filter(Objects::nonNull).filter(p->Boolean.TRUE.equals(p.ready())).count():null,valid?cpu:null,valid?memory:null,pods);
        return view(current,input,resources);
    }
    private boolean collecting(RunIntents.Run run) {return run.status()==RunLifecycle.Status.RUNNING || run.status()==RunLifecycle.Status.STOPPING || run.status()==RunLifecycle.Status.RECOVERY_REQUIRED;}
    private View view(RunIntents.Run run,ExperimentSnapshots.Inputs input,Resources resources) {
        return new View(run.id(),run.revision(),run.status(),"UNEVALUATED",run.status().terminal()?0:5,input.loadUnit(),input.measurementRate(),resources,
                collecting(run)?http.read(run):K6LiveHttpMetrics.unavailable("UNAVAILABLE","Waiting for a running worker; no live HTTP metrics are collected.",Instant.now()));
    }
    private Resources empty(ExperimentSnapshots.TargetInput target,String state,String message) {
        return new Resources(state,message,"Kubernetes PodMetrics","CURRENT_TARGET_SELECTED_CONTAINER",target.namespace(),target.deployment(),target.container(),null,target.revision(),0,Instant.now(),null,null,null,null,null,null,List.of());
    }
}
