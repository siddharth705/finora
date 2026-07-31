package com.finora.exception;

import com.finora.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Arrays;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Environment environment;

    public GlobalExceptionHandler(Environment environment) {
        this.environment = environment;
    }

    // An unmapped route (typo'd path, wrong method, disabled feature) should 404, not 500 —
    // this is what was silently masking the missing /actuator/health dependency earlier.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("No such endpoint: " + ex.getResourcePath(), "NOT_FOUND"));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException ex) {
        // ex.getCode() is null for the many existing throw sites still using the plain
        // (status, message) constructor — falls back to the status name exactly as before this
        // was introduced. See ErrorCode's class doc: codes get adopted call-site by call-site.
        String errorCode = ex.getCode() != null ? ex.getCode().code() : ex.getStatus().name();
        return ResponseEntity.status(ex.getStatus())
                .body(ApiResponse.error(ex.getMessage(), errorCode, ex.getDetails()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Invalid credentials", "UNAUTHORIZED"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return ResponseEntity.badRequest().body(ApiResponse.error(message, "VALIDATION_ERROR"));
    }

    /**
     * Bug fix (production-readiness pass): this used to include the raw exception's own message
     * in every 500 response, in every profile, unconditionally — not a full stack trace, but
     * still a real information-disclosure risk for a financial API (a SQL exception's message can
     * carry table/column/constraint names, a file-path exception can carry server filesystem
     * layout, and so on). The exception itself — full detail, every profile — is always logged
     * server-side first, correlation-ID-tagged (see CorrelationIdFilter/the logging.pattern in
     * application.yml), so nothing is lost for debugging; only the CLIENT-facing response now
     * withholds the raw message specifically in the prod profile.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        String message = isProd ? "Unexpected error" : "Unexpected error: " + ex.getMessage();
        return ResponseEntity.internalServerError().body(ApiResponse.error(message, "INTERNAL_ERROR"));
    }
}
