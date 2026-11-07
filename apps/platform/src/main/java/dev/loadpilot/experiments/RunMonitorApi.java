package dev.loadpilot.experiments;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/experiments/runs")
public class RunMonitorApi {
    public record Status(boolean enabled,RunMonitorStore.View monitor) {}
    private final RunMonitorStore store;
    private final boolean enabled;
    public RunMonitorApi(RunMonitorStore store,@Value("${platform.execution.monitor-enabled:false}") boolean enabled) {this.store=store;this.enabled=enabled;}
    @GetMapping("/{id}/monitor") public Status get(@PathVariable UUID id) {return new Status(enabled,store.read(id));}
}
