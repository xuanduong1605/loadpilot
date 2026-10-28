package dev.loadpilot.targets;

import java.time.Instant;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackageClasses = TargetApi.class)
public class TargetErrors {
    @ExceptionHandler(TargetProblem.class) ResponseEntity<?> problem(TargetProblem error) {
        return response(error.status, error.code, error.getMessage());
    }
    @ExceptionHandler(DuplicateKeyException.class) ResponseEntity<?> duplicate() {
        return response(409, "TARGET_CONFLICT", "An active system already uses this name.");
    }
    @ExceptionHandler(DataAccessException.class) ResponseEntity<?> unavailable() {
        return response(503, "TARGETS_UNAVAILABLE", "Systems are unavailable. Try again later.");
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid(Exception error) {
        String message = "Check the request fields.";
        if (error instanceof MethodArgumentNotValidException invalid && invalid.getFieldError() != null)
            message = "Check the " + invalid.getFieldError().getField() + " field.";
        return response(400, "VALIDATION_FAILED", message);
    }
    private ResponseEntity<?> response(int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message, "timestamp", Instant.now().toString()));
    }
}
