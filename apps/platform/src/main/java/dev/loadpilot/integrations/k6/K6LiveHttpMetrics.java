package dev.loadpilot.integrations.k6;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Bounded recent-window display only. Never evaluates SLOs or retains raw tags/log messages. */
public final class K6LiveHttpMetrics {
    public record Identity(UUID runId,UUID attemptId,UUID shardId,String jobUid) {}
    public record Snapshot(String state,String message,Double requestsPerSecond,Double p95LatencyMs,Double errorRatio,
                           Long requestCount,Long errorCount,Instant windowFrom,Instant windowUntil,Instant collectedAt,
                           String podUid,String source,String algorithm,String coverage,Identity identity) {
        public Snapshot correlated(dev.loadpilot.experiments.RunDispatch.Ticket ticket,String jobUid) {
            return new Snapshot(state,message,requestsPerSecond,p95LatencyMs,errorRatio,requestCount,errorCount,windowFrom,windowUntil,collectedAt,podUid,source,algorithm,coverage,new Identity(ticket.runId(),ticket.attemptId(),ticket.shardId(),jobUid));
        }
    }
    private record Key(Instant time,int status,int error) {}
    private static final class Samples {int requests,durations;}
    private final ObjectMapper json;
    public K6LiveHttpMetrics(ObjectMapper json) {this.json=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);}
    public static Snapshot unavailable(String state,String message,Instant now) {return result(state,message,null,null,null,null,null,null,null,now,null);}
    private static Snapshot result(String state,String message,Double rate,Double p95,Double ratio,Long requests,Long errors,Instant from,Instant until,Instant now,String pod) {
        return new Snapshot(state,message,rate,p95,ratio,requests,errors,from,until,now,pod,"k6 JSON / Kubernetes Pod log","live-http-json-v1/type7","BEST_EFFORT_LIVE_NOT_SLO",null);
    }
    public Snapshot parse(String text,Instant from,Instant until,Instant now,String podUid) {
        if(from==null || until==null || now==null || !from.isBefore(until) || Duration.between(from,until).compareTo(Duration.ofSeconds(15))>0 || until.isAfter(now)) throw new IllegalArgumentException("Invalid live metric window.");
        if(text==null || text.getBytes(StandardCharsets.UTF_8).length>=262144) return unavailable("LIMITED","Worker metric response exceeded the live collection bound.",now);
        if(!text.isEmpty() && !text.endsWith("\n")) return unavailable("INCOMPLETE","Worker metric response ended in a partial line.",now);
        var groups=new HashMap<Key,Samples>();var durations=new ArrayList<Double>();Instant lastLogTime=null;
        try {
            for(String line:text.split("\n")) {
                if(line.isEmpty()) continue;
                if(line.length()>16384) return unavailable("LIMITED","Worker metric line exceeded the live collection bound.",now);
                int separator=line.indexOf(' ');if(separator<1) throw new IllegalArgumentException();
                Instant logTime=Instant.parse(line.substring(0,separator));
                if(logTime.isAfter(now.plusSeconds(5)) || (lastLogTime!=null && logTime.isBefore(lastLogTime))) throw new IllegalArgumentException();lastLogTime=logTime;
                String payload=line.substring(separator+1);if(!payload.startsWith("{")) continue;
                var point=json.readTree(payload);String metric=point.path("metric").asText();
                if(!"Point".equals(point.path("type").asText()) || !(metric.equals("http_reqs") || metric.equals("http_req_duration"))) continue;
                var data=point.path("data");var tags=data.path("tags");
                if(!tags.path("scenario").isTextual()) throw new IllegalArgumentException();
                if(!"trial".equals(tags.path("scenario").asText())) continue;
                Instant time=Instant.parse(data.path("time").asText());if(time.isAfter(now.plusSeconds(5))) throw new IllegalArgumentException();
                if(time.isBefore(from) || !time.isBefore(until)) continue;
                String status=tags.path("status").asText();String error=tags.path("error_code").asText("0");
                if(!status.matches("0|[1-5][0-9]{2}") || !error.matches("[0-9]{1,5}") || !data.path("value").isNumber()) throw new IllegalArgumentException();
                double value=data.path("value").asDouble();if(!Double.isFinite(value) || value<0) throw new IllegalArgumentException();
                var key=new Key(time,Integer.parseInt(status),Integer.parseInt(error));var samples=groups.computeIfAbsent(key,ignored->new Samples());
                if(metric.equals("http_reqs")) {if(value!=1) throw new IllegalArgumentException();samples.requests++;}
                else {durations.add(value);samples.durations++;}
                if(durations.size()>10000 || groups.size()>10000) return unavailable("LIMITED","Worker metric sample count exceeded the live collection bound.",now);
            }
            long requests=0,errors=0;
            for(var entry:groups.entrySet()) {
                var samples=entry.getValue();if(samples.requests!=samples.durations) return unavailable("INCOMPLETE","Request count and duration samples do not reconcile in the live window.",now);
                requests+=samples.requests;var key=entry.getKey();if(key.status()<200 || key.status()>399 || key.error()!=0) errors+=samples.requests;
            }
            if(requests==0) return result("NO_DATA","No matched HTTP request samples are available in this live window.",null,null,null,0L,null,from,until,now,podUid);
            durations.sort(Double::compare);double index=.95*(durations.size()-1);int lower=(int)Math.floor(index),upper=(int)Math.ceil(index);
            double p95=durations.get(lower)+(durations.get(upper)-durations.get(lower))*(index-lower);
            double seconds=Duration.between(from,until).toNanos()/1_000_000_000d;
            return result("READY","Recent-window HTTP samples; not the final experiment measurement or SLO verdict.",requests/seconds,p95,(double)errors/requests,requests,errors,from,until,now,podUid);
        } catch(Exception invalid) {return unavailable("INVALID_DATA","Worker metric samples could not be verified.",now);}
    }
}
