package dev.loadpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.fixtures.InspectionFixture;
import dev.loadpilot.integrations.*;
import dev.loadpilot.targets.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InspectionAdapterTest {
    private SystemInspector inspector(InspectionFixture fixture, String token, String ca) {
        var targets = mock(TargetStore.class);
        var connections = mock(ConnectionStore.class);
        var id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(targets.get(id)).thenReturn(new Target(id,"Fixture",fixture.apiOrigin()+"/health","STAGING","fixture","fixture-api","api",null,null,1,Instant.now(),Instant.now(),null));
        when(connections.material(id)).thenReturn(new ConnectionStore.Material(1,fixture.origin(),ca,token,fixture.origin(),ca,token));
        return new SystemInspector(targets,connections,new EndpointPolicy(fixture.origin()+","+fixture.apiOrigin(),"127.0.0.1"),new ObjectMapper());
    }
    private Inspection inspect(SystemInspector inspector) { return inspector.inspect(UUID.fromString("00000000-0000-0000-0000-000000000001")); }
    @Test void realTlsJavaClientFiltersOwnershipAndRetainsUnitsAndSampleTime() throws Exception {
        try (var fixture = new InspectionFixture(0,0)) {
            var result = inspect(inspector(fixture,"fixture-readonly-token",fixture.ca));
            assertThat(result.api().state()).isEqualTo("READY");
            assertThat(result.kubernetes().state()).isEqualTo("READY");
            assertThat(result.prometheus().state()).isEqualTo("READY");
            assertThat(result.metrics().state()).isEqualTo("READY");
            assertThat(result.workload().desiredReplicas()).isEqualTo(1);
            assertThat(result.pods()).hasSize(1);
            var pod = result.pods().getFirst();
            assertThat(pod.name()).isEqualTo("fixture-pod");
            assertThat(pod.cpuRequestMillicores()).isEqualByComparingTo("100");
            assertThat(pod.usage().cpuMillicores()).isEqualByComparingTo("125");
            assertThat(pod.usage().memoryBytes()).isEqualByComparingTo("33554432");
            assertThat(pod.usage().timestamp()).isNotNull();
            assertThat(pod.usage().window()).isEqualTo("30s");
            assertThat(fixture.requests).allMatch(request -> request.startsWith("GET "));
        }
    }
    @Test void permissionFailureIsDistinctFromApiAvailabilityAndDoesNotLeakBodies() throws Exception {
        try (var fixture = new InspectionFixture(0,0)) {
            fixture.mode = "forbidden";
            var result = inspect(inspector(fixture,"fixture-readonly-token",fixture.ca));
            assertThat(result.api().state()).isEqualTo("READY");
            assertThat(result.kubernetes().state()).isEqualTo("FORBIDDEN");
            assertThat(result.kubernetes().message()).doesNotContain("private upstream detail");
            assertThat(result.pods()).isEmpty();
            var denied = inspect(inspector(fixture,"wrong-token",fixture.ca));
            assertThat(denied.kubernetes().state()).isEqualTo("UNAUTHORIZED");
        }
    }
    @Test void missingStaleAndMalformedMetricsNeverTurnIntoZeroConsumption() throws Exception {
        try (var fixture = new InspectionFixture(0,0)) {
            var inspector = inspector(fixture,"fixture-readonly-token",fixture.ca);
            for (String mode : new String[]{"missing-metrics","stale","malformed-metrics"}) {
                fixture.mode = mode;
                var result = inspect(inspector);
                assertThat(result.kubernetes().state()).isEqualTo("READY");
                assertThat(result.metrics().state()).isNotEqualTo("READY");
                assertThat(result.pods().getFirst().usage().cpuMillicores()).isNull();
                assertThat(result.pods().getFirst().usage().memoryBytes()).isNull();
            }
        }
    }
    @Test void tlsRedirectAndBodyLimitCannotBeBypassed() throws Exception {
        try (var fixture = new InspectionFixture(0,0)) {
            assertThat(inspect(inspector(fixture,"fixture-readonly-token","")).kubernetes().state()).isEqualTo("TLS_ERROR");
            var inspector = inspector(fixture,"fixture-readonly-token",fixture.ca);
            fixture.mode = "redirect";
            assertThat(inspect(inspector).api().state()).isEqualTo("REDIRECT_BLOCKED");
            assertThat(fixture.requests).noneMatch(request -> request.contains("/trap"));
            fixture.mode = "oversize";
            assertThat(inspect(inspector).api().state()).isEqualTo("RESPONSE_TOO_LARGE");
            fixture.mode = "truncated";
            assertThat(inspect(inspector).kubernetes().state()).isEqualTo("INCOMPLETE");
        }
    }
    @Test void stalledApiHasFiniteTimeoutWhileWorkloadStillInspects() throws Exception {
        try (var fixture = new InspectionFixture(0,0)) {
            fixture.mode = "timeout";
            long start = System.nanoTime();
            var result = inspect(inspector(fixture,"fixture-readonly-token",fixture.ca));
            assertThat(result.api().state()).isEqualTo("TIMEOUT");
            assertThat(result.kubernetes().state()).isEqualTo("READY");
            assertThat(java.time.Duration.ofNanos(System.nanoTime()-start).toSeconds()).isLessThan(12);
        }
    }
}
