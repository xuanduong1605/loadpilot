package dev.loadpilot.targets;

import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/targets")
public class TargetApi {
    private final TargetStore targets;
    public TargetApi(TargetStore targets) { this.targets = targets; }

    @GetMapping
    TargetStore.Page list(@RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "20") int limit,
                          @RequestParam(defaultValue = "false") boolean includeArchived) { return targets.list(offset, limit, includeArchived); }
    @GetMapping("/{id}") Target get(@PathVariable UUID id) { return targets.get(id); }
    @PostMapping ResponseEntity<Target> create(@Valid @RequestBody TargetInput input, Principal actor) {
        Target created = targets.create(input, actor.getName());
        return ResponseEntity.created(URI.create("/api/targets/" + created.id())).body(created);
    }
    @PutMapping("/{id}") Target update(@PathVariable UUID id, @Valid @RequestBody TargetInput input, Principal actor) {
        return targets.update(id, input, actor.getName());
    }
    record Archive(long revision) {}
    @PostMapping("/{id}/archive") Target archive(@PathVariable UUID id, @RequestBody Archive input, Principal actor) {
        return targets.archive(id, input.revision(), actor.getName());
    }
}
