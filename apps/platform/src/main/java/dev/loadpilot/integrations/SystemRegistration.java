package dev.loadpilot.integrations;

import dev.loadpilot.targets.Target;
import dev.loadpilot.targets.TargetInput;
import dev.loadpilot.targets.TargetStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account, destination and certificate policy remain identical to the existing connection flow. */
@Service
public class SystemRegistration {
    public record Input(@NotNull @Valid TargetInput system, @NotNull @Valid ConnectionInput connection) {
        @Override public String toString() { return "SystemRegistration.Input[credentials=redacted]"; }
    }
    private final TargetStore targets;
    private final ConnectionStore connections;
    public SystemRegistration(TargetStore targets, ConnectionStore connections) {
        this.targets = targets; this.connections = connections;
    }
    @Transactional
    public Target register(Input input, String actor) {
        var system = input.system();
        var connection = input.connection();
        if (system.revision() != null || (system.credentialRef() != null && !system.credentialRef().isEmpty())
                || (system.monitoringRef() != null && !system.monitoringRef().isEmpty())
                || connection.revision() != 0 || connection.targetRevision() != 1)
            throw ConnectionProblem.invalid();
        Target target = targets.create(system, actor);
        connections.save(target.id(), connection, actor);
        return targets.get(target.id());
    }
}
