package dev.loadpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.integrations.k6.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ExecutionRuntimePolicyTest {
    final ObjectMapper json=new ObjectMapper();
    String config() { return "{\"namespace\":\"loadpilot-runners\",\"imageDigest\":\"example.invalid/k6@sha256:"+"a".repeat(64)+"\",\"serviceAccount\":\"loadpilot-runner\",\"runtimeClass\":\"sandboxed\",\"temporaryStorageMiB\":32}"; }
    @Test void disabledIsExplicitAndNeverInventsAnImageOrRuntime() {
        var policy=new ExecutionRuntimePolicy("",json);
        assertThatThrownBy(policy::current).isInstanceOfSatisfying(ExecutionRuntimePolicy.Problem.class,error->assertThat(error.code()).isEqualTo("EXECUTION_RUNTIME_UNCONFIGURED"));
    }
    @Test void retainsExactlyTheOperatorPolicyAndSupportsExplicitRegistryPort() {
        var policy=new ExecutionRuntimePolicy(config(),json).current();
        assertThat(policy).isEqualTo(new K6JobPlan.Policy("loadpilot-runners","example.invalid/k6@sha256:"+"a".repeat(64),"loadpilot-runner","sandboxed",32));
        var port=new ExecutionRuntimePolicy(config().replace("example.invalid/k6","registry.invalid:5000/team/k6"),json).current();
        assertThat(port.imageDigest()).startsWith("registry.invalid:5000/team/k6@sha256:");
    }
    @Test void rejectsUnknownMissingDuplicateTrailingAndNonIntegralFields() {
        for(String value:List.of("{}","[]",config()+" {}",config().replace("32}","32,\"privileged\":true}"),
                config().replace("32}","32,\"namespace\":\"elsewhere\"}"),config().replace("32}","32.5}"),config().replace("32}","\"32\"}"),
                config().replace("32}","null}"),config().replace("32}","2147483648}"),config().replace("\"sandboxed\"","null")))
            assertThatThrownBy(()->new ExecutionRuntimePolicy(value,json)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsMutableImagesCredentialsAndUnsafeDefaultIdentitiesWithoutEcho() {
        for(String value:List.of(config().replace("example.invalid/k6@sha256:"+"a".repeat(64),"example.invalid/k6:latest"),
                config().replace("example.invalid/k6","https://user:private-value"),config().replace("example.invalid/k6","registry.invalid:70000/k6"),
                config().replace("loadpilot-runners","kube-system"),config().replace("loadpilot-runners","default"),config().replace("loadpilot-runner\"","default\""),
                config().replace("\"sandboxed\"","\"\""),config().replace("32}","0}"),config().replace("32}","257}")))
            assertThatThrownBy(()->new ExecutionRuntimePolicy(value,json)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-value");
    }
}
