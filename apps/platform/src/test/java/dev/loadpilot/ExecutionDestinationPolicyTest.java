package dev.loadpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.integrations.k6.ExecutionDestinationPolicy;
import dev.loadpilot.integrations.k6.K6JobPlan;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ExecutionDestinationPolicyTest {
    final String origin="https://fixture.invalid";
    List<InetAddress> ips(String... values) throws Exception {
        List<InetAddress> result=new ArrayList<>();for(String value:values) result.add(InetAddress.getByName(value));return result;
    }
    ExecutionDestinationPolicy policy(Map<String,List<String>> config,ExecutionDestinationPolicy.Resolver resolver) {
        return new ExecutionDestinationPolicy(config,resolver,Duration.ofSeconds(2));
    }
    @Test void unconfiguredAndDeniedOriginsNeverResolve() {
        var calls=new AtomicInteger();
        try(var empty=policy(Map.of(),host->{calls.incrementAndGet();return List.of();});
            var configured=policy(Map.of(origin,List.of("192.0.2.10")),host->{calls.incrementAndGet();return List.of();})) {
            assertThatThrownBy(()->empty.pin(origin)).isInstanceOfSatisfying(ExecutionDestinationPolicy.Problem.class,error->assertThat(error.code()).isEqualTo("EXECUTION_POLICY_UNCONFIGURED"));
            assertThatThrownBy(()->configured.pin("https://other.invalid")).isInstanceOfSatisfying(ExecutionDestinationPolicy.Problem.class,error->assertThat(error.status()).isEqualTo(403));
            assertThat(calls).hasValue(0);
        }
    }
    @Test void acceptsExplicitPrivateLabOriginAndNormalizesDefaultPortAndCase() throws Exception {
        try(var policy=policy(Map.of("https://FIXTURE.invalid:443/",List.of("10.2.3.4")),host->ips("10.2.3.4"))) {
            assertThat(policy.pin("https://fixture.invalid/api")).isEqualTo(new K6JobPlan.PinnedTarget("fixture.invalid","10.2.3.4",443));
        }
    }
    @Test void everyDnsAnswerMustBeAllowedForThatExactOrigin() throws Exception {
        var answers=new AtomicReference<>(ips("192.0.2.10","192.0.2.11"));
        try(var policy=policy(Map.of(origin,List.of("192.0.2.10"),"https://other.invalid",List.of("192.0.2.11")),host->answers.get())) {
            assertThatThrownBy(()->policy.pin(origin)).isInstanceOfSatisfying(ExecutionDestinationPolicy.Problem.class,error->assertThat(error.code()).isEqualTo("EXECUTION_DESTINATION_DENIED"));
            answers.set(ips("192.0.2.11"));
            assertThatThrownBy(()->policy.pin(origin)).isInstanceOf(ExecutionDestinationPolicy.Problem.class);
            answers.set(ips("192.0.2.10","169.254.169.254"));
            assertThatThrownBy(()->policy.pin(origin)).isInstanceOf(ExecutionDestinationPolicy.Problem.class);
        }
    }
    @Test void pinIsDeterministicButRebindingRequiresNewPlan() throws Exception {
        var answers=new AtomicReference<>(ips("192.0.2.11","192.0.2.10"));
        try(var policy=policy(Map.of(origin,List.of("192.0.2.10","192.0.2.11")),host->answers.get())) {
            var pinned=policy.pin(origin);assertThat(pinned.ip()).isEqualTo("192.0.2.10");
            answers.set(ips("192.0.2.10","192.0.2.11"));policy.verifyPin(origin,pinned);
            answers.set(ips("192.0.2.11"));
            assertThatThrownBy(()->policy.verifyPin(origin,pinned)).isInstanceOfSatisfying(ExecutionDestinationPolicy.Problem.class,error->assertThat(error.code()).isEqualTo("EXECUTION_DESTINATION_CHANGED"));
        }
    }
    @Test void literalIpv6NeedsNoDnsAndCanonicalizesAddress() {
        var calls=new AtomicInteger();
        try(var policy=policy(Map.of("https://[fd00::10]:8443",List.of("fd00:0:0:0:0:0:0:10")),host->{calls.incrementAndGet();return List.of();})) {
            var pin=policy.pin("https://[fd00::10]:8443/api");assertThat(pin.ip()).isEqualTo("fd00:0:0:0:0:0:0:10");assertThat(pin.port()).isEqualTo(8443);assertThat(calls).hasValue(0);
        }
    }
    @Test void unsafeAddressesCannotBeAuthorizedEvenExplicitly() {
        for(String ip:List.of("127.0.0.1","0.1.2.3","0.0.0.0","169.254.169.254","224.0.0.1","255.255.255.255","::1","::","fd00:ec2::254","100.100.100.200","::ffff:7f00:1")) {
            assertThatThrownBy(()->policy(Map.of(origin,List.of(ip)),host->List.of())).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void rejectsMalformedUrlsWithoutDnsOrCredentialEcho() {
        var calls=new AtomicInteger();
        try(var policy=policy(Map.of(origin,List.of("192.0.2.10")),host->{calls.incrementAndGet();return List.of();})) {
            for(String url:List.of("file:///test","https://user:private-value@fixture.invalid","https://fixture.invalid?x=1","https://fixture.invalid#fragment","https://fixture.invalid:0","https://fixture.invalid:65536","https://fixture.invalid.","https://127.1","https://*.invalid"))
                assertThatThrownBy(()->policy.pin(url)).isInstanceOf(ExecutionDestinationPolicy.Problem.class).hasMessageNotContaining("private-value");
            assertThat(calls).hasValue(0);
        }
    }
    @Test void validatesStrictBoundedJsonAndRejectsAmbiguousOrigins() {
        for(String config:List.of("[]","{} {}","{\"https://fixture.invalid\":[]}","{\"https://fixture.invalid\":[123]}","{\"https://fixture.invalid\":[\"resolve.invalid\"]}","{\"https://fixture.invalid/path\":[\"192.0.2.10\"]}","{\"https://fixture.invalid\":[\"192.0.2.10\"],\"https://fixture.invalid\":[\"192.0.2.11\"]}","{\"https://fixture.invalid\":[\"192.0.2.10\"],\"https://fixture.invalid:443\":[\"192.0.2.11\"]}"))
            assertThatThrownBy(()->new ExecutionDestinationPolicy(config,new ObjectMapper())).isInstanceOf(IllegalArgumentException.class);
        try(var empty=new ExecutionDestinationPolicy("",new ObjectMapper())) {
            assertThatThrownBy(()->empty.pin(origin)).isInstanceOfSatisfying(ExecutionDestinationPolicy.Problem.class,error->assertThat(error.status()).isEqualTo(503));
        }
    }
    @Test void resolverTimeoutsCannotCreateUnboundedThreadsOrQueuedWork() {
        var release=new CountDownLatch(1);var calls=new AtomicInteger();
        try(var policy=new ExecutionDestinationPolicy(Map.of(origin,List.of("192.0.2.10")),host->{
            calls.incrementAndGet();boolean done=false;while(!done) {try {release.await();done=true;}catch(InterruptedException ignored) { /* model native DNS ignoring cancellation */ }}return List.of();
        },Duration.ofMillis(50))) {
            for(int i=0;i<2;i++) assertThatThrownBy(()->policy.pin(origin)).isInstanceOfSatisfying(ExecutionDestinationPolicy.Problem.class,error->assertThat(error.code()).isEqualTo("EXECUTION_DNS_UNAVAILABLE"));
            assertThatThrownBy(()->policy.pin(origin)).isInstanceOfSatisfying(ExecutionDestinationPolicy.Problem.class,error->assertThat(error.code()).isEqualTo("EXECUTION_DNS_BUSY"));
            assertThat(calls).hasValue(2);
        } finally {release.countDown();}
    }
    @Test void resolutionFailureAndEmptyOrOversizedAnswersNeverFallBack() {
        try(var failed=policy(Map.of(origin,List.of("192.0.2.10")),host->{throw new UnknownHostException();});
            var empty=policy(Map.of(origin,List.of("192.0.2.10")),host->List.of());
            var oversized=policy(Map.of(origin,List.of("192.0.2.10")),host->Collections.nCopies(17,InetAddress.getByName("192.0.2.10")))) {
            assertThatThrownBy(()->failed.pin(origin)).isInstanceOfSatisfying(ExecutionDestinationPolicy.Problem.class,error->assertThat(error.status()).isEqualTo(503));
            assertThatThrownBy(()->empty.pin(origin)).isInstanceOfSatisfying(ExecutionDestinationPolicy.Problem.class,error->assertThat(error.status()).isEqualTo(403));
            assertThatThrownBy(()->oversized.pin(origin)).isInstanceOf(ExecutionDestinationPolicy.Problem.class);
        }
    }
}
