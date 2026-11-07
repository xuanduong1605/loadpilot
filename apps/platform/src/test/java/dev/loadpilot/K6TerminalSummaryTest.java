package dev.loadpilot;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.loadpilot.integrations.k6.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class K6TerminalSummaryTest {
    final ObjectMapper json=new ObjectMapper();
    final K6TerminalSummary parser=new K6TerminalSummary(json);
    K6JobPlanTest.Fixture fixture() throws Exception {var factory=new K6JobPlanTest();return factory.fixture(factory.steady(),"export default function() {}");}
    ObjectNode message(K6JobPlanTest.Fixture fixture) {
        var root=json.createObjectNode();root.put("schemaVersion",K6TerminalSummary.SCHEMA).put("runId",fixture.ticket().runId().toString()).put("attemptId",fixture.ticket().attemptId().toString()).put("shardId",fixture.ticket().shardId().toString())
                .put("snapshotSha256",fixture.snapshot().sha256()).put("scriptSha256",fixture.snapshot().inputs().scenario().script().sha256()).put("durationMs",20000).put("httpRequests",100).put("k6HttpFailureRate",.02).put("iterations",100).put("droppedIterations",2);
        root.putObject("httpDurationMs").put("min",10).put("max",500).put("avg",100).put("med",90).put("p90",200).put("p95",300).put("p99",450);return root;
    }
    io.kubernetes.client.openapi.models.V1Job job(K6JobPlanTest.Fixture f) {var factory=new K6JobPlanTest();return factory.planner.construct(f.snapshot(),f.ticket(),factory.policy,factory.destination,factory.now).job();}
    @Test void retainsNativeWholeRunValuesAndIdentityWithoutInventingSloSemantics() throws Exception {
        var f=fixture();var result=parser.parse(message(f).toString(),f.ticket(),job(f));
        assertThat(result.runId()).isEqualTo(f.ticket().runId());assertThat(result.httpRequests()).isEqualTo(100);assertThat(result.durationMs()).isEqualTo(20000);
        assertThat(result.k6HttpFailureRate()).isEqualTo(.02);assertThat(result.httpDurationMs().p95()).isEqualTo(300);assertThat(result.availability()).isEqualTo("AVAILABLE");
        var empty=message(f).put("httpRequests",0);var none=parser.parse(empty.toString(),f.ticket(),job(f));
        assertThat(none.availability()).isEqualTo("NO_REQUESTS");assertThat(none.httpDurationMs()).isNull();assertThat(none.k6HttpFailureRate()).isNull();
        empty.putNull("httpRequests");assertThat(parser.parse(empty.toString(),f.ticket(),job(f)).availability()).isEqualTo("INCOMPLETE");
    }
    @Test void rejectsTruncationOversizePrivateFieldsDuplicateKeysTrailingDataAndWrongIdentities() throws Exception {
        var f=fixture();String valid=message(f).toString();var job=job(f);
        for(String bad:List.of("",valid.substring(0,valid.length()-1),valid+" {}"," ".repeat(3072),valid.replace("\"durationMs\":20000","\"durationMs\":20000,\"durationMs\":1000"),
                message(f).put("url","https://private-token.invalid").toString(),message(f).put("runId",UUID.randomUUID().toString()).toString(),message(f).put("scriptSha256","a".repeat(64)).toString()))
            assertThatThrownBy(()->parser.parse(bad,f.ticket(),job)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-token");
    }
    @Test void rejectsInvalidNumericCountsRatesDurationAndContradictoryPercentiles() throws Exception {
        var f=fixture();var job=job(f);
        var values=new ArrayList<ObjectNode>();values.add(message(f).put("httpRequests",1.5));values.add(message(f).put("httpRequests",-1));values.add(message(f).put("httpRequests",9_007_199_254_740_992L));
        values.add(message(f).put("k6HttpFailureRate",1.01));values.add(message(f).put("durationMs",26000));values.add(message(f).put("durationMs","NaN"));
        var unordered=message(f);((ObjectNode)unordered.get("httpDurationMs")).put("p95",40);values.add(unordered);
        var range=message(f);((ObjectNode)range.get("httpDurationMs")).put("min",1000);values.add(range);
        for(var bad:values)assertThatThrownBy(()->parser.parse(bad.toString(),f.ticket(),job)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void generatesOnlyFixedSummaryFieldsAndExplicitTerminationFileWithoutRawData() throws Exception {
        var f=fixture();var factory=new K6JobPlanTest();var plan=factory.planner.construct(f.snapshot(),f.ticket(),factory.policy,factory.destination,factory.now);
        var worker=plan.job().getSpec().getTemplate().getSpec().getContainers().getFirst();
        assertThat(worker.getTerminationMessagePath()).isEqualTo(K6TerminalSummary.PATH);assertThat(worker.getTerminationMessagePolicy()).isEqualTo("File");
        assertThat(json.readTree(plan.optionsJson()).path("summaryTrendStats").toString()).contains("p(99)");
        String entry=plan.files().getData().get("entry.js");assertThat(entry).contains(f.ticket().runId().toString(),"Number.isSafeInteger",K6TerminalSummary.PATH).doesNotContain("JSON.stringify(data)","stdout", "console.log");
        Path dir=Path.of(System.getProperty("user.dir"),"target","summary-fixture");Files.createDirectories(dir);
        Files.writeString(dir.resolve("entry.js"),entry);Files.writeString(dir.resolve("expected.json"),message(f).toString());
        Files.writeString(dir.resolve("clock.json"),json.writeValueAsString(Map.of("submitted",f.ticket().submissionStartedAt().toInstant().toEpochMilli(),"startupMs",f.snapshot().inputs().executionBounds().startupTimeoutSeconds()*1000,"deadline",f.ticket().deadlineAt().toInstant().toEpochMilli(),"maximumMs",f.ticket().maximumSeconds()*1000)));
    }
}
