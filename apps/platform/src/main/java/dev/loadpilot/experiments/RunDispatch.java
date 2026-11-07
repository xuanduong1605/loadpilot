package dev.loadpilot.experiments;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Produces a committed intent ticket, not permission to bypass adapter preflight or stop fencing. */
@Service
public class RunDispatch {
    public record Ticket(UUID runId, UUID snapshotId, long revision, UUID attemptId, UUID shardId, UUID submitOperationId,
                         String workerName, int maximumSeconds, OffsetDateTime deadlineAt, OffsetDateTime submissionStartedAt) {
        /** Read/legacy evidence tickets do not grant new planning authority. */
        public Ticket(UUID runId,UUID snapshotId,long revision,UUID attemptId,UUID shardId,UUID submitOperationId,String workerName,int maximumSeconds,OffsetDateTime deadlineAt) {
            this(runId,snapshotId,revision,attemptId,shardId,submitOperationId,workerName,maximumSeconds,deadlineAt,null);
        }
    }
    private final RunIntents runs;
    private final TransactionTemplate transaction;
    public RunDispatch(RunIntents runs, PlatformTransactionManager manager) {
        this.runs=runs;this.transaction=new TransactionTemplate(manager);
    }
    public Ticket prepare(UUID runId) {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Dispatch preparation must begin outside an existing transaction.");
        var committed=transaction.execute(status->runs.claimSubmission(runId));
        if(committed==null) throw new IllegalStateException("No committed submission intent.");
        var submit=committed.operations().stream().filter(op->op.kind().equals("SUBMIT")).findFirst().orElseThrow();
        return new Ticket(committed.id(),committed.snapshotId(),committed.revision(),committed.attemptId(),committed.shardId(),submit.id(),
                committed.workerName(),committed.maximumSeconds(),committed.deadlineAt(),runs.submissionStartedAt(runId));
    }
}
