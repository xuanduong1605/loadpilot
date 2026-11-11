package dev.loadpilot.experiments;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.integrations.k6.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** One scoped final collection, no scheduling/mutation or public evidence-write endpoint. */
@Service
public class WorkerResultService {
    private final RunIntents runs;private final ExecutionPlans plans;private final ExecutionClusterClients clients;private final RunResults results;private final ObjectMapper json;
    public WorkerResultService(RunIntents runs,ExecutionPlans plans,ExecutionClusterClients clients,RunResults results,ObjectMapper json) {this.runs=runs;this.plans=plans;this.clients=clients;this.results=results;this.json=json;}
    /** Returns null only when a complete terminal Job read verifies that there are no worker Pods. */
    @Transactional(propagation=Propagation.NEVER) public RunResults.Saved collect(UUID runId) {
        var existing=results.saved(runId);if(existing!=null)return existing;
        var run=runs.get(runId);
        if(!run.status().terminal() || run.workerUid()==null) throw new ExperimentDrafts.Problem(409,"A terminal run with a bound worker is required for final results.");
        var plan=plans.get(runId);var binding=plans.clusterBinding(runId);var job=plans.workerJob(run);
        if(run.operations().stream().noneMatch(op->op.kind().equals("SUBMIT") && op.id().equals(plan.submitOperationId()) && op.status().equals("CONFIRMED"))) throw new ExperimentDrafts.Problem(409,"Confirmed submission is required for final results.");
        var ticket=new RunDispatch.Ticket(run.id(),run.snapshotId(),run.revision(),run.attemptId(),run.shardId(),plan.submitOperationId(),run.workerName(),run.maximumSeconds(),run.deadlineAt());
        var scope=new ExecutionClusterClients.Scope(run.targetId(),binding.connectionRevision(),binding.namespace(),run.workerName(),run.workerUid(),ExecutionClusterClients.Access.READ);
        K6TerminalSummary.Collected collected;
        try(var session=clients.openBound(scope,binding);var collector=new KubernetesWorkerCollector(session.client())) {collected=collector.collectFinal(ticket,job,run.workerUid(),json);}
        return collected==null?null:results.record(runId,run.revision(),run.workerUid(),collected);
    }
}
