package dev.loadpilot.experiments;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

/** Explicit backend setting; enabling observation cannot enable public Start or worker activation. */
@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="platform.execution.monitor-enabled",havingValue="true")
public class RunMonitorSchedule {
    @org.springframework.context.annotation.Bean public org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler(){
        var scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();scheduler.setPoolSize(2);scheduler.setThreadNamePrefix("execution-");scheduler.setWaitForTasksToCompleteOnShutdown(false);return scheduler;
    }
    private final RunMonitor monitor;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(RunMonitorSchedule.class);
    public RunMonitorSchedule(RunMonitor monitor) {this.monitor=monitor;}
    @Scheduled(fixedDelay=1000,initialDelay=1000) public void poll() {
        try {monitor.tick();}
        catch(RuntimeException unavailable) {log.warn("Run monitor queue is unavailable; polling will retry.");}
    }
}
