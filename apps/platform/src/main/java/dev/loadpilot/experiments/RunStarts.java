package dev.loadpilot.experiments;

import dev.loadpilot.integrations.k6.RuntimeAdmission;
import dev.loadpilot.shared.HttpRequestLog;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class RunStarts {
    public enum Gate {
        READY("The request can be queued for runtime checks."),
        MONITOR_DISABLED("Background execution monitoring is disabled. Ask the deployment operator to configure and enable it."),
        RUNTIME_ADMISSION_UNCONFIGURED("Runtime admission is not configured. Ask the deployment operator to configure signed runtime evidence.");

        private final String message;
        Gate(String message) { this.message = message; }
        public String message() { return message; }
    }
    public record Readiness(Gate gate, String message, UUID existingRunId) {}
    public static class Unavailable extends RuntimeException {
        private final Gate gate;
        Unavailable(Gate gate) { super(gate.message()); this.gate = gate; }
        public Gate gate() { return gate; }
    }
    private final RunIntents runs;private final StartWorkflows workflows;private final RuntimeAdmission admission;private final boolean monitoring;
    public RunStarts(RunIntents runs,StartWorkflows workflows,RuntimeAdmission admission,@Value("${platform.execution.monitor-enabled:false}") boolean monitoring){this.runs=runs;this.workflows=workflows;this.admission=admission;this.monitoring=monitoring;}
    private Gate gate() {
        if (!monitoring) return Gate.MONITOR_DISABLED;
        return admission.available() ? Gate.READY : Gate.RUNTIME_ADMISSION_UNCONFIGURED;
    }
    public Readiness readiness(UUID snapshotId) {
        var existingRunId = runs.checkStartInputs(snapshotId);
        var gate = gate();
        return new Readiness(gate, gate.message(), existingRunId);
    }
    @Transactional(propagation=Propagation.NEVER) public RunIntents.Run request(RunIntents.Start input,String actor){
        if(runs.findRequest(input.requestKey())!=null)return runs.reserve(input,actor); // Replay only; never adopt internal/legacy work.
        var gate = gate();
        if (gate != Gate.READY) {
            HttpRequestLog.recordReason(gate.name());
            throw new Unavailable(gate);
        }
        return workflows.reserve(input,actor); // Run/attempt/intent and queue entry commit together.
    }
}
