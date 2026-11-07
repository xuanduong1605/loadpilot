package dev.loadpilot.experiments;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/experiments/runs")
public class RunApi {
    private final RunIntents runs;
    private final RunMetrics metrics;
    private final RunResults results;
    private final RunStarts starts;
    private final StartWorkflows workflows;
    public RunApi(RunIntents runs,RunMetrics metrics,RunResults results,RunStarts starts,StartWorkflows workflows) { this.runs=runs;this.metrics=metrics;this.results=results;this.starts=starts;this.workflows=workflows; }
    @PostMapping public ResponseEntity<?> start(@Valid @RequestBody RunIntents.Start request,Principal actor) {
        try{return ResponseEntity.accepted().body(starts.request(request,actor.getName()));}
        catch(RunStarts.Unavailable unavailable){
        return ResponseEntity.status(503).body(Map.of("code","EXECUTION_UNAVAILABLE",
                "message","Starting runs is unavailable until runtime admission and background monitoring are verified."));}
    }
    @GetMapping("/{id}/start-workflow") public StartWorkflows.View workflow(@PathVariable UUID id){return workflows.read(id);}
    @GetMapping("/{id}") public RunIntents.Run get(@PathVariable UUID id) { return runs.get(id); }
    @GetMapping("/{id}/metrics") public RunMetrics.View metrics(@PathVariable UUID id) { return metrics.read(id); }
    @GetMapping("/{id}/result") public RunResults.View result(@PathVariable UUID id) { return results.read(id); }
    @PostMapping("/{id}/stop") public RunIntents.Run stop(@PathVariable UUID id, Principal actor) { return runs.stop(id,actor.getName()); }
}
