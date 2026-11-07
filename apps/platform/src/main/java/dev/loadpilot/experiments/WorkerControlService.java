package dev.loadpilot.experiments;

import dev.loadpilot.integrations.k6.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Bound-client façade, not a scheduler or public execution endpoint. */
@Service
public class WorkerControlService {
    private final RunIntents runs;private final ExecutionPlans plans;private final ExecutionClusterClients clients;
    private final WorkerObservationService observer;private final WorkerStopDispatch stops;
    public WorkerControlService(RunIntents runs,ExecutionPlans plans,ExecutionClusterClients clients,WorkerObservationService observer,WorkerStopDispatch stops) {
        this.runs=runs;this.plans=plans;this.clients=clients;this.observer=observer;this.stops=stops;
    }
    @Transactional(propagation=Propagation.NEVER) public RunIntents.Run observe(UUID runId) {
        var run=runs.get(runId);
        if(run.status()==RunLifecycle.Status.QUEUED || run.status().terminal()) throw new ExperimentDrafts.Problem(409,"This run cannot collect worker evidence.");
        var binding=plans.clusterBinding(runId);
        var scope=new ExecutionClusterClients.Scope(run.targetId(),binding.connectionRevision(),binding.namespace(),run.workerName(),run.workerUid(),ExecutionClusterClients.Access.READ);
        try(var session=clients.openBound(scope,binding);var collector=new KubernetesWorkerCollector(session.client())) {return observer.observe(runId,collector);}
    }
    @Transactional(propagation=Propagation.NEVER) public WorkerStopDispatch.Result dispatchPendingStop(UUID runId) {
        var run=runs.get(runId);
        if(run.status()!=RunLifecycle.Status.STOPPING || run.workerUid()==null) throw new ExperimentDrafts.Problem(409,"A stopping run with a bound worker is required.");
        var binding=plans.clusterBinding(runId);
        var scope=new ExecutionClusterClients.Scope(run.targetId(),binding.connectionRevision(),binding.namespace(),run.workerName(),run.workerUid(),ExecutionClusterClients.Access.STOP);
        try(var session=clients.openBound(scope,binding);var stopper=new KubernetesWorkerStopper(session.client())) {return stops.request(runId,stopper);}
    }
}
