package dev.loadpilot.integrations;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EndpointPolicy {
    private final Set<String> origins;
    private final Set<String> addresses;
    public EndpointPolicy(@Value("${platform.connections.allowed-origins:}") String origins,
                          @Value("${platform.connections.allowed-addresses:}") String addresses) {
        this.origins = split(origins);
        this.addresses = new HashSet<>();
        for (String ip : split(addresses)) {
            try {
                if (!ip.matches("[0-9.]+") && !ip.contains(":")) throw new IllegalArgumentException();
                this.addresses.add(InetAddress.getByName(ip).getHostAddress());
            } catch (UnknownHostException | IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Invalid connection address allowlist");
            }
        }
    }
    private Set<String> split(String value) {
        Set<String> result = new TreeSet<>();
        for (String item : value.split(",")) if (!item.isBlank()) result.add(item.trim());
        return result;
    }
    public Set<String> origins() { return Collections.unmodifiableSet(origins); }
    public URI validate(String value, boolean requireTls) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535
                    || !("https".equals(uri.getScheme()) || (!requireTls && "http".equals(uri.getScheme()))))
                throw ConnectionProblem.invalid();
            String origin = uri.getScheme() + "://" + uri.getRawAuthority();
            if (!origins.contains(origin)) throw denied();
            return uri;
        } catch (IllegalArgumentException invalid) { throw ConnectionProblem.invalid(); }
    }
    public List<InetAddress> resolve(String host) throws UnknownHostException {
        return check(Arrays.asList(InetAddress.getAllByName(host)));
    }
    // Public for deterministic policy tests; all DNS results must be allowed, not just the first.
    public List<InetAddress> check(List<InetAddress> resolved) {
        if (resolved.isEmpty()) throw denied();
        for (InetAddress ip : resolved)
            if (ip.isAnyLocalAddress() || ip.isMulticastAddress() || ip.isLinkLocalAddress()
                    || !addresses.contains(ip.getHostAddress())) throw denied();
        return List.copyOf(resolved);
    }
    private ConnectionProblem denied() { return new ConnectionProblem(403, "DESTINATION_DENIED", "This destination is not allowed. Ask the platform operator to allow its origin and resolved addresses."); }
}
