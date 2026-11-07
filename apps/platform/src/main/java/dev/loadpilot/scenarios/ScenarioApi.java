package dev.loadpilot.scenarios;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioApi {
    public record Input(@NotBlank @Size(max=120) String name, @NotBlank @Size(max=65536) String source,
                        @Positive Integer expectedVersion) {}
    public record Summary(UUID id, String name, int latestVersion, OffsetDateTime createdAt) {}
    public record Page(List<Summary> items, long total, int offset, int limit) {}
    public record Version(UUID scenarioId, String name, int version, String source, String sha256,
                          String createdBy, OffsetDateTime createdAt, boolean approved) {}
    private final ScenarioStore store;
    public ScenarioApi(ScenarioStore store) { this.store = store; }
    @GetMapping public Page list(@RequestParam(defaultValue="0") int offset, @RequestParam(defaultValue="12") int limit) { return store.list(offset, limit); }
    @GetMapping("/{id}") public Version latest(@PathVariable UUID id) { return store.latest(id); }
    @GetMapping("/{id}/versions/{version}") public Version version(@PathVariable UUID id, @PathVariable int version) { return store.version(id, version); }
    @PostMapping public ResponseEntity<Version> create(@Valid @RequestBody Input input, Principal actor) {
        var version = store.save(null, input, actor.getName());
        return ResponseEntity.created(java.net.URI.create("/api/scenarios/" + version.scenarioId() + "/versions/1")).body(version);
    }
    @PostMapping("/{id}/versions") public Version save(@PathVariable UUID id, @Valid @RequestBody Input input, Principal actor) { return store.save(id, input, actor.getName()); }
}
