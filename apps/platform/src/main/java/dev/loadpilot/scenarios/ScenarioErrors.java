package dev.loadpilot.scenarios;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackageClasses=ScenarioApi.class)
public class ScenarioErrors {
    @ExceptionHandler(ScenarioStore.Problem.class) ResponseEntity<?> problem(ScenarioStore.Problem error) {
        return ResponseEntity.status(error.status).body(Map.of("code", "SCENARIO_INVALID", "message", error.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("code", "SCENARIO_INVALID", "message", "Check the scenario name, source and version fields."));
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> database() {
        return ResponseEntity.status(503).body(Map.of("code", "SCENARIOS_UNAVAILABLE", "message", "Scenario storage is unavailable. Your edits have not been discarded."));
    }
}
