package dev.loadpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.integrations.k6.K6LiveHttpMetrics;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class K6LiveHttpMetricsTest {
    final ObjectMapper json=new ObjectMapper();final K6LiveHttpMetrics parser=new K6LiveHttpMetrics(json);
    final Instant now=Instant.parse("2026-11-06T08:00:20Z"),from=now.minusSeconds(12),until=now.minusSeconds(2);
    String point(String metric,Instant time,double value,String status,String error) throws Exception {
        return now.minusSeconds(1)+" "+json.writeValueAsString(Map.of("type","Point","metric",metric,"data",Map.of("time",time.toString(),"value",value,"tags",Map.of("scenario","trial","status",status,"error_code",error,"url","https://private.invalid?token=private-token"))))+"\n";
    }
    String request(Instant time,double duration,String status,String error) throws Exception {return point("http_reqs",time,1,status,error)+point("http_req_duration",time,duration,status,error);}
    @Test void derivesRateErrorsAndP95FromMatchedRawWindowSamplesWithoutRetainingTags() throws Exception {
        String text=request(now.minusSeconds(9),100,"200","0")+request(now.minusSeconds(8),200,"500","0")+request(now.minusSeconds(7),300,"0","1050")
                +request(now.minusSeconds(13),9000,"500","0")+request(until,9000,"500","0")+point("http_req_failed",now.minusSeconds(8),0,"500","0");
        var result=parser.parse(text,from,until,now,"pod");
        assertThat(result.state()).isEqualTo("READY");assertThat(result.requestCount()).isEqualTo(3);assertThat(result.errorCount()).isEqualTo(2);
        assertThat(result.requestsPerSecond()).isEqualTo(.3);assertThat(result.p95LatencyMs()).isEqualTo(290);assertThat(result.errorRatio()).isEqualTo(2d/3);
        assertThat(result.windowFrom()).isEqualTo(from);assertThat(result.windowUntil()).isEqualTo(until);assertThat(result.coverage()).isEqualTo("BEST_EFFORT_LIVE_NOT_SLO");
        assertThat(result.toString()).doesNotContain("private-token","private.invalid","url");assertThat(parser.parse(text,from,until,now,"pod")).isEqualTo(result);
    }
    @Test void noRequestsAndPartialOrUnmatchedDataNeverBecomePassingOrZeroFilledMetrics() throws Exception {
        var empty=parser.parse("",from,until,now,"pod");assertThat(empty.state()).isEqualTo("NO_DATA");assertThat(empty.requestsPerSecond()).isNull();assertThat(empty.p95LatencyMs()).isNull();assertThat(empty.errorRatio()).isNull();
        assertThat(parser.parse(point("http_reqs",now.minusSeconds(5),1,"200","0"),from,until,now,"pod").state()).isEqualTo("INCOMPLETE");
        String complete=request(now.minusSeconds(5),100,"200","0");
        assertThat(parser.parse(complete.stripTrailing(),from,until,now,"pod").state()).isEqualTo("INCOMPLETE");
        assertThat(parser.parse("x".repeat(262144),from,until,now,"pod").state()).isEqualTo("LIMITED");
    }
    @Test void malformedFutureAmbiguousAndInventedCounterDataAreRejectedWithoutPrivateEcho() throws Exception {
        String valid=request(now.minusSeconds(5),100,"200","0");
        for(String text:List.of(valid.replace("\"value\":1.0","\"value\":2.0"),valid.replace("\"status\":\"200\"","\"status\":\"private-token\""),valid.replace("\"type\":\"Point\"","\"type\":\"Point\",\"type\":\"Point\""),request(now.plusSeconds(6),100,"200","0"))) {
            var result=parser.parse(text,from,until,now,"pod");assertThat(result.state()).isEqualTo("INVALID_DATA");assertThat(result.requestsPerSecond()).isNull();assertThat(result.toString()).doesNotContain("private-token");
        }
    }
}
