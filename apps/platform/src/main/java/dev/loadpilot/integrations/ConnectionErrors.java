package dev.loadpilot.integrations;

import dev.loadpilot.targets.TargetProblem;
import java.time.Instant;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages="dev.loadpilot.integrations")
public class ConnectionErrors {
    @ExceptionHandler(ConnectionProblem.class) ResponseEntity<?> problem(ConnectionProblem error) { return error(error.status, error.code, error.getMessage()); }
    @ExceptionHandler(TargetProblem.class) ResponseEntity<?> target(TargetProblem error) { return error(error.status(), error.code(), error.getMessage()); }
    @ExceptionHandler(DataAccessException.class) ResponseEntity<?> database() { return error(503, "CONNECTION_UNAVAILABLE", "Connection storage is unavailable. Try again later."); }
    @ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid() { return error(400, "CONNECTION_INVALID", "Check connection fields and certificate authorities."); }
    private ResponseEntity<?> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message, "timestamp", Instant.now().toString()));
    }
}
