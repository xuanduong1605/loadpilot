package dev.loadpilot.experiments;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(basePackageClasses = ExperimentSettingsApi.class)
public class ExperimentSettingsErrors {
    @ExceptionHandler(dev.loadpilot.scenarios.ScenarioStore.Problem.class)
    ResponseEntity<?> scenario(dev.loadpilot.scenarios.ScenarioStore.Problem error) {
        return ResponseEntity.status(error.status()).body(Map.of("code", "SCENARIO_INVALID", "message", error.getMessage()));
    }
    @ExceptionHandler(dev.loadpilot.targets.TargetProblem.class)
    ResponseEntity<?> target(dev.loadpilot.targets.TargetProblem error) {
        return ResponseEntity.status(error.status()).body(Map.of("code", error.code(), "message", error.getMessage()));
    }
    @ExceptionHandler(ExperimentDrafts.Problem.class)
    ResponseEntity<?> draft(ExperimentDrafts.Problem error) {
        return ResponseEntity.status(error.status).body(Map.of("code", "DRAFT_INVALID", "message", error.getMessage()));
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<?> database() {
        return ResponseEntity.status(503).body(Map.of("code", "DRAFTS_UNAVAILABLE", "message", "Experiment drafts are unavailable. Try again later."));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid(Exception error) {
        String message = "Provide numeric values within the displayed bounds.";
        if (error instanceof MethodArgumentNotValidException invalid && invalid.getFieldError() != null)
            message = "Check " + invalid.getFieldError().getField() + ": " + invalid.getFieldError().getDefaultMessage();
        return ResponseEntity.badRequest().body(Map.of("code", "SETTINGS_INVALID", "message", message));
    }
}
