package dev.loadpilot.experiments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Independent of the worker stop and start schedulers, including when Kubernetes log reads are slow. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="platform.execution.monitor-enabled",havingValue="true")
public class RunPartialResultSchedule {
    private static final Logger log=LoggerFactory.getLogger(RunPartialResultSchedule.class);
    private final RunPartialResults results;
    private final RunIntents runs;
    private final WorkerHttpMetrics metrics;

    public RunPartialResultSchedule(RunPartialResults results,RunIntents runs,WorkerHttpMetrics metrics) {
        this.results=results;
        this.runs=runs;
        this.metrics=metrics;
    }

    @Bean
    static ThreadPoolTaskScheduler partialHttpScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("partial-http-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    @Scheduled(scheduler="partialHttpScheduler",fixedDelay=1000,initialDelay=1000)
    public void poll() {
        try {
            var id=results.claimNext();
            if(id!=null)results.retain(id,metrics.readForRetention(runs.get(id)));
        } catch(RuntimeException unavailable) {
            log.warn("Partial worker HTTP results are unavailable; polling will retry.");
        }
    }
}
