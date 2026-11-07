package dev.loadpilot.integrations.k6;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import dev.loadpilot.experiments.RunDispatch;
import io.kubernetes.client.openapi.models.V1Job;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Fixed, redacted whole-run summary. Never a warm-up-excluded SLO measurement. */
public final class K6TerminalSummary {
    public static final String PATH="/output/loadpilot-result.json";
    public static final String SCHEMA="k6-terminal-summary-v1";
    public static final int MAX_BYTES=3072;
    public record DurationMs(Double min,Double max,Double avg,Double med,Double p90,Double p95,Double p99) {}
    public record Summary(String schemaVersion,UUID runId,UUID attemptId,UUID shardId,String snapshotSha256,String scriptSha256,
                          Double durationMs,Long httpRequests,Double k6HttpFailureRate,Long iterations,Long droppedIterations,DurationMs httpDurationMs) {
        public String availability() {
            if(httpRequests!=null && httpRequests==0) return "NO_REQUESTS";
            return httpRequests==null || durationMs==null || durationMs<=0 || k6HttpFailureRate==null || httpDurationMs==null
                    || List.of(httpDurationMs.min()==null,httpDurationMs.max()==null,httpDurationMs.avg()==null,httpDurationMs.med()==null,httpDurationMs.p90()==null,httpDurationMs.p95()==null,httpDurationMs.p99()==null).contains(true)
                    ? "INCOMPLETE" : "AVAILABLE";
        }
    }
    public record Collected(String podUid,Instant startedAt,Instant finishedAt,int exitCode,Summary summary) {}
    private final ObjectMapper json;
    public K6TerminalSummary(ObjectMapper json) {this.json=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);}

    public Summary parse(String text,RunDispatch.Ticket ticket,V1Job expected) {
        try {
            require(text!=null && !text.isBlank() && text.getBytes(StandardCharsets.UTF_8).length<MAX_BYTES);
            var root=json.readTree(text);
            fields(root,Set.of("schemaVersion","runId","attemptId","shardId","snapshotSha256","scriptSha256","durationMs","httpRequests","k6HttpFailureRate","iterations","droppedIterations","httpDurationMs"));
            require(SCHEMA.equals(root.path("schemaVersion").asText()) && ticket.runId().toString().equals(root.path("runId").asText())
                    && ticket.attemptId().toString().equals(root.path("attemptId").asText()) && ticket.shardId().toString().equals(root.path("shardId").asText()));
            var annotations=expected.getMetadata().getAnnotations();
            String snapshot=annotations.get("loadpilot.dev/snapshot-sha256"),script=annotations.get("loadpilot.dev/script-sha256");
            require(snapshot!=null && snapshot.matches("[a-f0-9]{64}") && script!=null && script.matches("[a-f0-9]{64}")
                    && snapshot.equals(root.path("snapshotSha256").asText()) && script.equals(root.path("scriptSha256").asText()));
            Double duration=number(root.get("durationMs"),ticket.maximumSeconds()*1000d);
            Long requests=count(root.get("httpRequests")),iterations=count(root.get("iterations")),dropped=count(root.get("droppedIterations"));
            Double failures=number(root.get("k6HttpFailureRate"),1);
            DurationMs stats=null;var trend=root.get("httpDurationMs");
            if(!trend.isNull()) {
                fields(trend,Set.of("min","max","avg","med","p90","p95","p99"));
                double ceiling=ticket.maximumSeconds()*1000d;
                stats=new DurationMs(number(trend.get("min"),ceiling),number(trend.get("max"),ceiling),number(trend.get("avg"),ceiling),number(trend.get("med"),ceiling),number(trend.get("p90"),ceiling),number(trend.get("p95"),ceiling),number(trend.get("p99"),ceiling));
                if(stats.min()!=null && stats.max()!=null) {
                    require(stats.min()<=stats.max());
                    for(Double value:new Double[]{stats.avg(),stats.med(),stats.p90(),stats.p95(),stats.p99()}) if(value!=null) require(value>=stats.min() && value<=stats.max());
                }
                var percentiles=new Double[]{stats.med(),stats.p90(),stats.p95(),stats.p99()};
                for(int i=1;i<percentiles.length;i++) if(percentiles[i-1]!=null && percentiles[i]!=null) require(percentiles[i-1]<=percentiles[i]);
            }
            // With no requests, neither percentiles nor a failure ratio are observations.
            if(requests!=null && requests==0) {stats=null;failures=null;}
            return new Summary(SCHEMA,ticket.runId(),ticket.attemptId(),ticket.shardId(),snapshot,script,duration,requests,failures,iterations,dropped,stats);
        } catch(Exception invalid) {throw new IllegalArgumentException("Worker summary is missing, incomplete in transport, or cannot be verified.");}
    }
    private static void fields(JsonNode node,Set<String> expected) {
        require(node!=null && node.isObject());var actual=new HashSet<String>();node.fieldNames().forEachRemaining(actual::add);require(actual.equals(expected));
    }
    private static Double number(JsonNode value,double maximum) {
        require(value!=null);if(value.isNull()) return null;
        require(value.isNumber());double number=value.asDouble();require(Double.isFinite(number) && number>=0 && number<=maximum);return number;
    }
    private static Long count(JsonNode value) {
        require(value!=null);if(value.isNull()) return null;
        require(value.isIntegralNumber() && value.canConvertToLong());long number=value.asLong();require(number>=0 && number<=9_007_199_254_740_991L);return number;
    }
    private static void require(boolean value) {if(!value)throw new IllegalArgumentException();}

    public static String summaryFunction(ObjectMapper json,RunDispatch.Ticket ticket,String snapshotHash,String scriptHash) {
        try {
            String identity=json.writeValueAsString(Map.of("schemaVersion",SCHEMA,"runId",ticket.runId().toString(),"attemptId",ticket.attemptId().toString(),"shardId",ticket.shardId().toString(),"snapshotSha256",snapshotHash,"scriptSha256",scriptHash));
            return """
                    export function handleSummary(data) {
                      const result = %s;
                      const metric = (name, field) => {
                        const value = data.metrics && data.metrics[name] && data.metrics[name].values && data.metrics[name].values[field];
                        return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null;
                      };
                      const count = name => { const value = metric(name, 'count'); return Number.isSafeInteger(value) ? value : null; };
                      const duration = data.state && data.state.testRunDurationMs;
                      result.durationMs = typeof duration === 'number' && Number.isFinite(duration) && duration >= 0 ? duration : null;
                      result.httpRequests = count('http_reqs');
                      result.k6HttpFailureRate = metric('http_req_failed', 'rate');
                      result.iterations = count('iterations');
                      result.droppedIterations = count('dropped_iterations');
                      result.httpDurationMs = { min: metric('http_req_duration', 'min'), max: metric('http_req_duration', 'max'),
                        avg: metric('http_req_duration', 'avg'), med: metric('http_req_duration', 'med'),
                        p90: metric('http_req_duration', 'p(90)'), p95: metric('http_req_duration', 'p(95)'), p99: metric('http_req_duration', 'p(99)') };
                      return { '%s': JSON.stringify(result) };
                    }
                    """.formatted(identity,PATH);
        } catch(com.fasterxml.jackson.core.JsonProcessingException impossible) {throw new IllegalStateException("Cannot encode worker summary identity.");}
    }
}
