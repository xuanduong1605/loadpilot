package dev.loadpilot;

import dev.loadpilot.experiments.*;
import dev.loadpilot.integrations.k6.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class K6RuntimeGuardTest {
    RunDispatch.Ticket ticket(OffsetDateTime submitted,OffsetDateTime deadline) {
        UUID attempt=UUID.randomUUID();return new RunDispatch.Ticket(UUID.randomUUID(),UUID.randomUUID(),2,attempt,UUID.randomUUID(),UUID.randomUUID(),"lp-"+attempt,60,deadline,submitted);
    }
    ExecutionBounds bounds(int startup){return new ExecutionBounds(null,1,1,1,250,128,1000,startup,2,1);}
    @Test void missingSubmissionClockCannotAuthorizeANewPlan() {
        var now=OffsetDateTime.now();var old=ticket(null,now.plusSeconds(120));
        assertThatThrownBy(()->K6RuntimeGuard.entry(old,bounds(30))).hasMessage("A committed submission clock is required for worker startup.");
    }
    @Test @EnabledIfEnvironmentVariable(named="TEST_K6_EXECUTABLE",matches=".+")
    void nativeEngineGuardsOneFixedOwnedNoHttpIteration(@TempDir Path dir) throws Exception {
        // This is the entire fixed test program. Never substitute stored/user source into this native test.
        Files.writeString(dir.resolve("scenario.js"),"import {Counter} from 'k6/metrics'; const entered=new Counter('fixture_entered'); export default function(){entered.add(1);}\n");
        Files.writeString(dir.resolve("options.json"),"{\"scenarios\":{\"trial\":{\"executor\":\"shared-iterations\",\"vus\":1,\"iterations\":1,\"maxDuration\":\"1s\",\"gracefulStop\":\"0s\"}}}");
        for(String mode:List.of("valid","late-startup","insufficient-headroom","expired")) {
            var now=OffsetDateTime.now(ZoneOffset.UTC);var submitted=mode.equals("late-startup")?now.minusSeconds(60):mode.equals("expired")?now.minusSeconds(120):now;
            var deadline=mode.equals("expired")?now.minusSeconds(1):mode.equals("insufficient-headroom")?now.plusSeconds(10):now.plusSeconds(120);
            String entry=K6RuntimeGuard.entry(ticket(submitted,deadline),bounds(mode.equals("insufficient-headroom")?5:30));
            entry+="export function handleSummary(d){return {'stdout':JSON.stringify({entered:d.metrics.fixture_entered ? d.metrics.fixture_entered.values.count : 0})};}\n";
            Files.writeString(dir.resolve("entry.js"),entry);
            var command=new ProcessBuilder(System.getenv("TEST_K6_EXECUTABLE"),"--log-output=none","run","--quiet","--no-usage-report","--config",dir.resolve("options.json").toString(),dir.resolve("entry.js").toString()).redirectErrorStream(true);
            String systemRoot=System.getenv("SystemRoot");command.environment().clear();if(systemRoot!=null)command.environment().put("SystemRoot",systemRoot);
            var child=command.start();
            try {
                if(!child.waitFor(10,TimeUnit.SECONDS))throw new AssertionError("Fixed native clock fixture timed out");
                String output=new String(child.getInputStream().readNBytes(16385),java.nio.charset.StandardCharsets.UTF_8);
                assertThat(output.length()).isLessThanOrEqualTo(16384);
                assertThat(child.exitValue()).as(mode+": "+output).isEqualTo(mode.equals("valid")?0:108);
                if(mode.equals("valid"))assertThat(output).contains("\"entered\":1");else assertThat(output).doesNotContain("\"entered\":1");
            } finally {if(child.isAlive())child.destroyForcibly();}
        }
        System.out.println("Native k6 clock fixture: one fixed no-HTTP iteration accepted; late/headroom/expired runs exit108 before fixture iteration. No target load.");
    }
}
