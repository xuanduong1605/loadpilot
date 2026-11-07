package dev.loadpilot.experiments;

public final class RunLifecycle {
    private RunLifecycle() {}
    public enum WorkerState { PENDING, RUNNING, SUCCEEDED, FAILED, TERMINATED, UNREACHABLE }
    public enum Status {
        QUEUED, SUBMITTING, RUNNING, STOPPING, COMPLETED, FAILED, CANCELLED, RECOVERY_REQUIRED;
        public boolean terminal() { return this == COMPLETED || this == FAILED || this == CANCELLED; }
        public Status requestStop() {
            if (terminal() || this == STOPPING) return this;
            return this == QUEUED ? CANCELLED : STOPPING;
        }
        public Status observe(WorkerState worker, boolean allWorkersTerminated) {
            if (this == QUEUED || terminal()) throw new ExperimentDrafts.Problem(409, "This run cannot accept worker observations.");
            if (worker == WorkerState.UNREACHABLE) return this == STOPPING ? STOPPING : RECOVERY_REQUIRED;
            if (this == STOPPING) return allWorkersTerminated ? CANCELLED : STOPPING;
            if (worker == WorkerState.PENDING) return this == RUNNING ? RECOVERY_REQUIRED : SUBMITTING;
            if (worker == WorkerState.RUNNING || !allWorkersTerminated) return RUNNING;
            return worker == WorkerState.SUCCEEDED ? COMPLETED : FAILED;
        }
    }
}
