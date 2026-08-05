package com.finora.exception;

import com.finora.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
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
    public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException ex, HttpServletRequest request) {
        // ex.getCode() is null for the many existing throw sites still using the plain
        // (status, message) constructor — falls back to the status name exactly as before this
        // was introduced. See ErrorCode's class doc: codes get adopted call-site by call-site.
        String errorCode = ex.getCode() != null ? ex.getCode().code() : ex.getStatus().name();

        // Bug fix: a 5xx ApiException returned a server error with NO log line at all. Every 4xx
        // here is a deliberate, expected rejection (bad input, missing record, failed permission)
        // and correctly stays silent -- but a 5xx is this server saying it failed, and those were
        // invisible: IMPORT_SYSTEM_BUSY (503) and the Firebase-unconfigured 503 both produced a
        // failing response and left nothing behind to find it by. Found while hunting a production
        // 500 whose stack trace could not be located, which is exactly the cost of this gap.
        //
        // Logged WITH the stack trace, since a 5xx that was thrown deliberately still needs its
        // origin identified, and with the request line so the endpoint is known without correlating
        // by timestamp. Deliberately not logging query string or body -- this is a financial API
        // and those carry customer data.
        if (ex.getStatus().is5xxServerError()) {
            log.error("Server-error ApiException [{}] on {} {}: {}",
                    errorCode, request.getMethod(), request.getRequestURI(), ex.getMessage(), ex);
        }

        return ResponseEntity.status(ex.getStatus())
                .body(ApiResponse.error(ex.getMessage(), errorCode, ex.getDetails()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Invalid credentials", "UNAUTHORIZED"));
    }

    /**
     * Bug fix: a failed {@code @PreAuthorize} check (every Admin*Controller endpoint) throws
     * Spring Security's AccessDeniedException from INSIDE the AOP-proxied controller-method call
     * -- i.e. during DispatcherServlet handler dispatch, not from the security filter chain. With
     * no handler for it here, it fell through to the catch-all Exception handler below: every
     * authenticated-but-unauthorized admin request returned 500 "Unexpected error" / INTERNAL_ERROR
     * instead of 403, and got logged as log.error("Unhandled exception", ...) — polluting error
     * logs/alerting with what's actually routine, expected authorization enforcement, not a real
     * failure. SecurityConfig's own AccessDeniedException handling (ExceptionTranslationFilter,
     * wired via RestAuthenticationEntryPoint's 401-vs-403 split) only ever sees this exception when
     * it's thrown from within the filter chain itself (e.g. a path-based authorizeHttpRequests
     * rule) -- never from a method-level @PreAuthorize check, which is exactly the mechanism every
     * admin controller in this codebase actually uses.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ErrorCode.AUTH_FORBIDDEN.defaultMessage(), ErrorCode.AUTH_FORBIDDEN.code()));
    }

    /**
     * Bug fix: Account carries @Version (BaseEntity), so two concurrent writes to the same
     * account -- a double-click submit, or a transaction posted from a second tab while the first
     * is still in flight -- make the losing request's save() throw
     * ObjectOptimisticLockingFailureException (Spring's translation of JPA's
     * OptimisticLockException). With no handler for it here, it fell through to the catch-all
     * Exception handler below: a routine, expected concurrency conflict returned an opaque 500
     * "Unexpected error" and got logged as log.error("Unhandled exception", ...) — the exact same
     * "expected condition treated as an alarming failure" issue the AccessDeniedException handler
     * above was already fixed for. @Version itself still does its job (no silent lost update --
     * the loser's write is correctly rejected); this only fixes what the CLIENT sees when that
     * happens, so a UI can tell the user to refresh and retry instead of showing a generic error.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("This record was just updated by another request — refresh and try again.", "CONFLICT"));
    }

    /**
     * Bug fix: a unique-constraint violation is the database enforcing a rule the application also
     * knows about, and it is entirely a client-input condition -- but with no handler it fell
     * through to the catch-all below and came back as a 500 "Unexpected error", logged as
     * "Unhandled exception". Same gap already closed for AccessDeniedException,
     * OptimisticLockingFailureException and HttpMessageNotReadableException in this class; this is
     * the fourth instance of one pattern.
     *
     * <p>Reachable wherever a check-then-act race can lose: duplicate categories, budgets,
     * net-worth snapshots, merchant aliases, and the V52 scoped email/phone indexes. It matters
     * most on the import path -- {@code MerchantNormalizationEngine.addAlias} is check-then-act
     * against {@code UNIQUE(user_id, normalized_alias)}, and because that runs inside the confirm
     * transaction, a 500 there rolled back the user's ENTIRE import rather than the one row.
     *
     * <p>409 CONFLICT, matching the optimistic-lock handler above: both mean "someone else got
     * there first, retry." Deliberately does not echo the exception's message -- the constraint
     * text can name schema internals, and for this API the offending value may be customer data.
     * The full exception is logged at warn (not error): it is expected under concurrency, but a
     * sustained rise in these is a real signal worth being able to see.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException ex,
                                                                          HttpServletRequest request) {
        log.warn("Data integrity violation on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("That conflicts with a record that already exists — refresh and try again.", "CONFLICT"));
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
     * Bug fix: malformed JSON in a @RequestBody (truncated body, wrong type for a field, invalid
     * syntax) throws HttpMessageNotReadableException from Jackson during argument resolution --
     * before any @Valid constraint even runs, so handleValidation() above never sees it. With no
     * handler for it here, it fell through to the catch-all Exception handler below: the exact
     * same "routine, expected client-input problem treated as an alarming server failure" gap
     * already fixed for AccessDeniedException and OptimisticLockingFailureException in this same
     * class -- a malformed request body is entirely the caller's mistake, not this server failing,
     * but it came back as an opaque 500 "Unexpected error" and got logged as
     * log.error("Unhandled exception", ...), polluting error logs/alerting with ordinary bad input.
     * Deliberately not logging the exception's own message -- Jackson's parse-error text can quote
     * back raw request body content, which for this API may be customer financial data.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMalformedRequestBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("The request body is missing or malformed.", "MALFORMED_REQUEST_BODY"));
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
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex, HttpServletRequest request) {
        // The request line is included because "Unhandled exception" alone forced anyone reading
        // the log to correlate by timestamp to work out WHICH endpoint failed. Method + path costs
        // nothing and is the first thing you want. Deliberately no query string or body -- this is
        // a financial API and both carry customer data.
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        String message = isProd ? "Unexpected error" : "Unexpected error: " + ex.getMessage();
        return ResponseEntity.internalServerError().body(ApiResponse.error(message, "INTERNAL_ERROR"));
    }
}
