package dev.loadpilot;

import dev.loadpilot.integrations.*;
import java.net.InetAddress;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ConnectionSafetyTest {
    private final SecretCipher cipher = new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
    @Test void secretsHaveRandomNoncesAndCannotBeMovedToAnotherSystemOrField() {
        String first = cipher.seal("fixture-secret", "target-a:kube");
        String second = cipher.seal("fixture-secret", "target-a:kube");
        assertThat(first).doesNotContain("fixture-secret").isNotEqualTo(second);
        assertThat(cipher.open(first, "target-a:kube")).isEqualTo("fixture-secret");
        assertThatThrownBy(() -> cipher.open(first, "target-b:kube")).isInstanceOf(ConnectionProblem.class);
        assertThatThrownBy(() -> cipher.open(first, "target-a:prom")).isInstanceOf(ConnectionProblem.class);
        assertThatThrownBy(() -> cipher.open(first.substring(0, first.length()-4)+"AAAA", "target-a:kube")).isInstanceOf(ConnectionProblem.class);
        assertThat(new SecretCipher("").available()).isFalse();
        assertThatThrownBy(() -> new SecretCipher("invalid").seal("secret", "context")).isInstanceOf(ConnectionProblem.class);
    }
    @Test void exactOriginsAndEveryResolvedAddressAreCheckedIncludingRebindingAndMetadata() throws Exception {
        var policy = new EndpointPolicy("https://cluster.example.invalid:6443", "127.0.0.1,169.254.169.254");
        assertThat(policy.validate("https://cluster.example.invalid:6443/", true).getHost()).isEqualTo("cluster.example.invalid");
        for (String url : List.of("https://cluster.example.invalid:6443.evil.invalid", "http://cluster.example.invalid:6443", "https://user:password@cluster.example.invalid:6443", "https://cluster.example.invalid:6443?token=secret", "https://unrelated.invalid"))
            assertThatThrownBy(() -> policy.validate(url, true)).isInstanceOf(ConnectionProblem.class);
        assertThat(policy.check(List.of(InetAddress.getByName("127.0.0.1")))).hasSize(1);
        assertThatThrownBy(() -> policy.check(List.of(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("10.0.0.2"))))
                .isInstanceOf(ConnectionProblem.class);
        assertThatThrownBy(() -> policy.check(List.of(InetAddress.getByName("169.254.169.254")))).isInstanceOf(ConnectionProblem.class);
        assertThatThrownBy(() -> new EndpointPolicy("", "").validate("https://cluster.example.invalid", true)).isInstanceOf(ConnectionProblem.class);
    }
}
