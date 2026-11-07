package dev.loadpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.scenarios.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ScriptParserTest {
    @TempDir Path temporary;
    @Test @EnabledIfEnvironmentVariable(named="TEST_NODE_EXECUTABLE", matches=".+")
    void actualExecutionHelperBindsTimeoutAndOriginAndNeverRunsSource() {
        var parser=new ScriptParser(System.getenv("TEST_NODE_EXECUTABLE"),System.getenv("TEST_VALIDATOR_SCRIPT"),new ObjectMapper());
        String source="import http from 'k6/http'; export default function(){http.get('https://fixture.invalid/api',{timeout:'1s',redirects:0});}";
        assertThat(parser.parseExecution(source,"https://fixture.invalid",1000).policyAccepted()).isTrue();
        assertThat(parser.parseExecution(source,"https://fixture.invalid",999).policyAccepted()).isFalse();
        assertThat(parser.parseExecution(source,"https://other.invalid",1000).policyAccepted()).isFalse();
        assertThat(parser.parseExecution("export default function(){while(true){}}","https://fixture.invalid",1000).policyAccepted()).isTrue();
        assertThat(parser.parseExecution("export default function(){http.get(__ENV.TOKEN);}","https://fixture.invalid",1000).policyAccepted()).isFalse();
    }
    @Test @EnabledIfEnvironmentVariable(named="TEST_NODE_EXECUTABLE", matches=".+")
    void executionCannotAcceptLegacyStaticValidatorOutputOrMissingHelper() throws Exception {
        Path helper=temporary.resolve("static.cjs");
        Files.writeString(helper,"process.stdout.write(JSON.stringify({syntaxValid:true,policyAccepted:true,policyVersion:'static-review-v1',parserVersion:'8.19.0',nodeMajor:24,findings:[]}))");
        var parser=new ScriptParser(System.getenv("TEST_NODE_EXECUTABLE"),helper.toString(),new ObjectMapper());
        assertThatThrownBy(()->parser.parseExecution("export default function(){}","https://fixture.invalid",1000)).isInstanceOf(ScenarioStore.Problem.class);
        assertThatThrownBy(()->new ScriptParser("","",new ObjectMapper()).parseExecution("export default function(){}","https://fixture.invalid",1000)).isInstanceOf(ScenarioStore.Problem.class);
    }
    @Test void missingConfigurationFailsClosed() {
        var parser = new ScriptParser("", "", new ObjectMapper());
        assertThatThrownBy(() -> parser.parse("export default function() {}"))
                .isInstanceOfSatisfying(ScenarioStore.Problem.class, error -> assertThat(error.getMessage()).contains("not configured"));
    }
    @Test @EnabledIfEnvironmentVariable(named="TEST_NODE_EXECUTABLE", matches=".+")
    void actualHelperParsesWithoutRunningSubmittedInitCode() {
        var parser = new ScriptParser(System.getenv("TEST_NODE_EXECUTABLE"), System.getenv("TEST_VALIDATOR_SCRIPT"), new ObjectMapper());
        var result = parser.parse("throw new Error('never-executed'); export default function() {} ");
        assertThat(result.syntaxValid()).isTrue(); assertThat(result.policyAccepted()).isTrue();
        assertThat(parser.parse("export default function( {").syntaxValid()).isFalse();
        assertThat(parser.parse("import x from 'https://fixture.invalid/module.js'; export default function() {}").policyAccepted()).isFalse();
    }
    @Test @EnabledIfEnvironmentVariable(named="TEST_NODE_EXECUTABLE", matches=".+")
    void timeoutAndMalformedOutputFailClosed() throws Exception {
        Path helper = temporary.resolve("helper.cjs");
        Files.writeString(helper, "while (true) {}");
        var parser = new ScriptParser(System.getenv("TEST_NODE_EXECUTABLE"), helper.toString(), new ObjectMapper());
        long started = System.nanoTime();
        assertThatThrownBy(() -> parser.parse("submitted source")).isInstanceOfSatisfying(ScenarioStore.Problem.class, error -> assertThat(error.getMessage()).contains("timed out"));
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(6000);
        Files.writeString(helper, "process.stdout.write('{}')");
        assertThatThrownBy(() -> parser.parse("submitted source")).isInstanceOf(ScenarioStore.Problem.class);
        Files.writeString(helper, "process.stdout.write('x'.repeat(20000))");
        assertThatThrownBy(() -> parser.parse("submitted source")).isInstanceOf(ScenarioStore.Problem.class);
    }
}
