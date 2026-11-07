package dev.loadpilot.integrations.k6;

import dev.loadpilot.experiments.*;

/** Trusted wrapper only. Late workers abort before submitted iteration code; Pod deadlines bound in-iteration stalls. */
public final class K6RuntimeGuard {
    public static final String VERSION="worker-clock-v1";
    private K6RuntimeGuard() {}
    public static String entry(RunDispatch.Ticket ticket,ExecutionBounds bounds) {
        if(ticket.submissionStartedAt()==null) throw new IllegalArgumentException("A committed submission clock is required for worker startup.");
        long submitted=ticket.submissionStartedAt().toInstant().toEpochMilli();
        long startup=Math.addExact(submitted,Math.multiplyExact(bounds.startupTimeoutSeconds().longValue(),1000));
        long deadline=ticket.deadlineAt().toInstant().toEpochMilli();
        long maximum=Math.multiplyExact((long)ticket.maximumSeconds(),1000);
        if(submitted<0 || deadline>9_007_199_254_740_991L || startup>deadline || maximum<1) throw new IllegalArgumentException("Invalid worker clock bounds.");
        return """
            import platformExecution from 'k6/execution';
            import iteration from './scenario.js';
            // worker-clock-v1: immutable reviewed epoch bounds; no user source rewriting.
            export default function(data) {
              const now = Date.now();
              const started = platformExecution.scenario.startTime;
              if (platformExecution.scenario.name !== 'trial' || !Number.isSafeInteger(started) ||
                  !Number.isSafeInteger(now) || started < %d || now < started ||
                  started >= %d || now >= %d || started + %d > %d) {
                platformExecution.test.abort('LOADPILOT_WORKER_CLOCK_REJECTED');
                return;
              }
              return iteration(data);
            }
            """.formatted(submitted,startup,deadline,maximum,deadline);
    }
}
