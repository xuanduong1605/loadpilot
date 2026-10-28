package dev.loadpilot.integrations;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/integrations")
public class ConnectionApi {
    private final ConnectionStore store;
    private final SecretCipher cipher;
    private final EndpointPolicy policy;
    private final SystemInspector inspector;
    public ConnectionApi(ConnectionStore store, SecretCipher cipher, EndpointPolicy policy, SystemInspector inspector) { this.store = store; this.cipher = cipher; this.policy = policy; this.inspector = inspector; }
    @GetMapping("/config") Map<String, Object> config() { return Map.of("encryptionAvailable", cipher.available(), "allowedOrigins", policy.origins()); }
    @GetMapping("/targets/{id}/connection") ConnectionStore.View connection(@PathVariable UUID id) { return store.view(id); }
    @GetMapping("/targets/{id}/status") Inspection status(@PathVariable UUID id) { return inspector.inspect(id); }
    @PutMapping("/targets/{id}/connection") ConnectionStore.View save(@PathVariable UUID id, @Valid @RequestBody ConnectionInput input, Principal actor, HttpServletRequest request) {
        boolean loopbackHost = java.util.Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(request.getServerName());
        boolean loopbackPeer = java.util.Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1").contains(request.getRemoteAddr());
        if (!request.isSecure() && !(loopbackHost && loopbackPeer))
            throw new ConnectionProblem(400, "SECURE_TRANSPORT_REQUIRED", "Use HTTPS or a loopback tunnel before submitting connection credentials.");
        return store.save(id, input, actor.getName());
    }
}
