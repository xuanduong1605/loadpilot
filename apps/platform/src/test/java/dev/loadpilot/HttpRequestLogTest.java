package dev.loadpilot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.loadpilot.experiments.ExperimentSettingsErrors;
import dev.loadpilot.experiments.RunApi;
import dev.loadpilot.experiments.RunIntents;
import dev.loadpilot.shared.HttpErrorLogAdvice;
import dev.loadpilot.shared.HttpRequestLog;
import jakarta.servlet.http.Cookie;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HttpRequestLogTest {
    @Test void correlatesRequestsWithRouteTemplatesAndSafeErrorDetails() throws Exception {
        var runs=mock(RunIntents.class);
        var api=new RunApi(runs,null,null,null,null);
        var mvc=MockMvcBuilders.standaloneSetup(api)
                .setControllerAdvice(new ExperimentSettingsErrors(),new HttpErrorLogAdvice())
                .addFilters(new HttpRequestLog()).build();
        UUID id=UUID.randomUUID();
        when(runs.get(id)).thenThrow(new DataAccessResourceFailureException("private SQL statement",
                new SQLException("private database exception detail","25006")));

        var log=(Logger)LoggerFactory.getLogger(HttpRequestLog.class);
        var events=new ListAppender<ILoggingEvent>();
        events.start();
        log.addAppender(events);
        try {
            var failure=mvc.perform(get("/api/experiments/runs/{id}",id)
                            .queryParam("token","private-query-value")
                            .header(HttpRequestLog.HEADER,"untrusted-request-id")
                            .header("Authorization","Bearer private-header-value")
                            .cookie(new Cookie("LOADPILOT_SESSION","private-cookie-value")))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("DRAFTS_UNAVAILABLE"))
                    .andReturn().getResponse();
            String first=failure.getHeader(HttpRequestLog.HEADER);
            var success=mvc.perform(get("/api/experiments/runs/{id}",UUID.randomUUID()))
                    .andExpect(status().isOk()).andReturn().getResponse();
            String second=success.getHeader(HttpRequestLog.HEADER);
            assertThat(first).matches("[0-9a-f-]{36}").isNotEqualTo(second);
            assertThat(second).matches("[0-9a-f-]{36}");
            assertThat(events.list).hasSize(2);
            assertThat(events.list.getFirst().getLevel()).isEqualTo(Level.ERROR);
            assertThat(events.list.getFirst().getFormattedMessage())
                    .contains("request_id="+first,"method=GET","route=/api/experiments/runs/{id}","status=503",
                            "code=DRAFTS_UNAVAILABLE","failure_type=DataAccessResourceFailureException","sqlstate=25006")
                    .containsPattern("duration_ms=[0-9]+")
                    .doesNotContain(id.toString(),"private","untrusted-request-id");
            assertThat(events.list.getLast().getFormattedMessage())
                    .contains("request_id="+second,"status=200","code=-","failure_type=-","sqlstate=-");
        } finally {
            log.detachAppender(events);
            events.stop();
        }
    }
}
