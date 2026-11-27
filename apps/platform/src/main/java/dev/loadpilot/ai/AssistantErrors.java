package dev.loadpilot.ai;

import dev.loadpilot.shared.HttpRequestLog;
import dev.loadpilot.experiments.ExperimentDrafts;
import dev.loadpilot.scenarios.ScenarioStore;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(basePackageClasses = AssistantApi.class)
class AssistantErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> problem(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode())
                .body(Map.of("code", "ASSISTANT_REQUEST_FAILED", "message", failure.getReason()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid() {
        return ResponseEntity.badRequest().body(Map.of("code", "ASSISTANT_INVALID", "message", "Check the Assistant request fields and text length."));
    }

    @ExceptionHandler({ExperimentDrafts.Problem.class, ScenarioStore.Problem.class})
    ResponseEntity<?> record(RuntimeException failure) {
        int status = failure instanceof ExperimentDrafts.Problem experiment ? experiment.status() : ((ScenarioStore.Problem) failure).status();
        if (status >= 500) return unavailable(failure);
        return ResponseEntity.status(status).body(Map.of("code", "ASSISTANT_RECORD_UNAVAILABLE", "message", failure.getMessage()));
    }

    @ExceptionHandler({DataAccessException.class, IllegalStateException.class})
    ResponseEntity<?> unavailable(RuntimeException failure) {
        HttpRequestLog.recordFailure(failure);
        return ResponseEntity.status(503).body(Map.of("code", "ASSISTANT_UNAVAILABLE", "message", "Assistant is unavailable. Try again later."));
    }
}
