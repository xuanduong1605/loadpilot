package dev.loadpilot.experiments;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="platform.execution.monitor-enabled",havingValue="true")
public class RunStartSchedule {
    private final RunStartProcessor processor;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(RunStartSchedule.class);
    public RunStartSchedule(RunStartProcessor processor){this.processor=processor;}
    @Scheduled(fixedDelay=1000,initialDelay=1000) public void poll(){try{processor.tick();}catch(RuntimeException unavailable){log.warn("Start workflow queue is unavailable; scheduling will retry.");}}
}
