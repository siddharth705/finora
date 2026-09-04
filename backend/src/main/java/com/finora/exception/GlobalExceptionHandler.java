package com.finora.exception;

import com.finora.dto.ApiResponse;
import com.finora.security.RefreshTokenCookie;
import com.finora.util.LogSanitizer;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Arrays;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.time.format.DateTimeParseException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Every code that ends a refresh session without reissuing a replacement (rotate()'s idle-
     * timeout, absolute-cap and reuse-detection branches -- see RefreshTokenService). A refresh
     * cookie is HttpOnly, so the client cannot clear it itself: leaving it in place after one of
     * these responses means the browser's very next automatic refresh attempt re-presents the
     * same now-dead token, turning an ordinary idle timeout into a reuse-detection response that
     * reads as a suspected theft.
     */
    private static final java.util.Set<ErrorCode> TERMINATES_REFRESH_SESSION = java.util.Set.of(
            ErrorCode.AUTH_SESSION_IDLE, ErrorCode.AUTH_SESSION_MAX_AGE, ErrorCode.AUTH_SESSION_REVOKED);

    private final Environment environment;
    private final RefreshTokenCookie refreshTokenCookie;

    public GlobalExceptionHandler(Environment environment, RefreshTokenCookie refreshTokenCookie) {
        this.environment = environment;
        this.refreshTokenCookie = refreshTokenCookie;
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
        //
        // BH-043: a 5xx whose code is ErrorCode.intentionalRejection() is the one exception to
        // "always ERROR" above -- see that field's own doc. IMPORT_SYSTEM_BUSY now fires on every
        // ordinary burst past the concurrency limit, not rarely after a 20s timeout, so treating
        // every occurrence as an alarming server failure (full stack trace, ERROR-level) would
        // flood exactly the alerting this class exists to keep meaningful with what the limiter
        // is designed to do correctly. WARN, no trace: the code and message already say
        // everything there is to know, there is no origin to go find.
        //
        // Post-merge review: is5xxServerError() is checked in BOTH branches now, not just the
        // ERROR one -- intentionalRejection()'s own doc is explicit that it's about "a 5xx
        // carrying this code," and every 4xx above this block is documented and tested to stay
        // completely silent. Without this guard, a future ErrorCode that set
        // intentionalRejection=true on a 4xx (a plausible mistake -- someone copying
        // IMPORT_SYSTEM_BUSY's pattern to opt out of ERROR-logging without noticing 4xx never
        // reached that branch anyway) would start WARN-logging a class of error this class's own
        // contract says must never be logged at all.
        if (ex.getStatus().is5xxServerError() && ex.getCode() != null && ex.getCode().intentionalRejection()) {
            log.warn("Deliberate rejection ApiException [{}] on {} {}: {}",
                    errorCode, LogSanitizer.sanitize(request.getMethod()),
                    LogSanitizer.sanitize(request.getRequestURI()), ex.getMessage());
        } else if (ex.getStatus().is5xxServerError()) {
            log.error("Server-error ApiException [{}] on {} {}: {}",
                    errorCode, LogSanitizer.sanitize(request.getMethod()),
                    LogSanitizer.sanitize(request.getRequestURI()), ex.getMessage(), ex);
        }

        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.getStatus());
        if (ex.getCode() != null && TERMINATES_REFRESH_SESSION.contains(ex.getCode())) {
            response.header(HttpHeaders.SET_COOKIE, refreshTokenCookie.clear().toString());
        }
        return response.body(ApiResponse.error(ex.getMessage(), errorCode, detailsWithActionRequired(ex)));
    }

    /**
     * Merges {@code ErrorCode.userActionRequired()} into the exception's own details, centralized
     * here rather than left for each of the ~24 {@code ApiException} throw sites to remember --
     * this handler already does the identical thing for {@code errorCode} two lines above.
     *
     * <p>Closes a drift risk Sprint 4 item 22 shipped with and flagged rather than fixed: the
     * frontend previously kept its own hardcoded copy of which codes are user-actionable
     * ({@code importFailureMessages.ts}), a boolean CLASSIFICATION that has to exactly agree with
     * this enum, unlike curated message text, which is deliberately independent. The async path
     * (queued imports) already avoided this by computing {@code userStatus} once, backend-side,
     * and putting it on the wire ({@code UserFacingImportStatus}); this is the same fix for the
     * synchronous path, which has no {@code ImportJob} to compute one on.
     *
     * <p>{@code ex.getDetails()} is {@link java.util.Collections#emptyMap()} for the overwhelming
     * majority of throw sites -- copied into a new mutable map rather than merged in place, since
     * that immutable empty map (and any caller-supplied {@code Map.of(...)}) would throw on
     * {@code put}.
     */
    private static java.util.Map<String, Object> detailsWithActionRequired(ApiException ex) {
        if (ex.getCode() == null) return ex.getDetails();
        java.util.Map<String, Object> merged = new java.util.HashMap<>(ex.getDetails());
        merged.put("userActionRequired", ex.getCode().userActionRequired());
        return merged;
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentials() {
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
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied() {
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
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock() {
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
        log.warn("Data integrity violation on {} {}", LogSanitizer.sanitize(request.getMethod()),
                LogSanitizer.sanitize(request.getRequestURI()), ex);
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
    public ResponseEntity<ApiResponse<Void>> handleMalformedRequestBody() {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("The request body is missing or malformed.", "MALFORMED_REQUEST_BODY"));
    }

    /**
     * The query-parameter and path-variable channels, closing the same gap
     * {@link #handleMalformedRequestBody} closed for the request-body channel.
     *
     * <p>Three entirely client-caused binding failures had no handler, so all three fell through
     * to the catch-all below: a 500 with {@code errorCode: "INTERNAL_ERROR"}, logged via
     * {@code log.error("Unhandled exception on {} {}")}. That is the defect this class's own
     * comment already names for malformed bodies -- "entirely the caller's mistake, not this
     * server failing, but it came back as an opaque 500 ... polluting error logs/alerting with
     * ordinary bad input" -- fixed on one channel and not the others.
     *
     * <p>Worth being explicit about why Spring did not cover this by itself:
     * {@code DefaultHandlerExceptionResolver} DOES map all three to 400 out of the box, but a
     * {@code @ExceptionHandler(Exception.class)} in a {@code @RestControllerAdvice} is consulted
     * first and matches everything, so the catch-all shadowed the framework's own correct
     * behaviour. Registering them explicitly is what un-shadows it.
     *
     * <ul>
     *   <li>{@code DateTimeParseException} -- {@code ReportService.forMonth} calls
     *       {@code YearMonth.parse} on a completely unvalidated {@code @RequestParam String month}.
     *       {@code AdminUserAnalyticsController.parseMonth} already guards the identical parse and
     *       returns a 400 naming the format; the self-service report path never got it.</li>
     *   <li>{@code MethodArgumentTypeMismatchException} -- any of the {@code @PathVariable UUID}
     *       bindings given a non-UUID path segment.</li>
     *   <li>{@code MissingServletRequestParameterException} -- a required parameter omitted.</li>
     * </ul>
     *
     * <p>The messages name the parameter but never echo the submitted value. In non-prod profiles
     * the catch-all appends {@code ex.getMessage()}, and for a DateTimeParseException that quotes
     * the raw user-supplied string straight back into the response.
     */
    @ExceptionHandler({
            DateTimeParseException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBindingFailure(Exception ex) {
        String message = switch (ex) {
            case MissingServletRequestParameterException missing ->
                    "Required parameter '" + missing.getParameterName() + "' is missing.";
            case MethodArgumentTypeMismatchException mismatch ->
                    "Parameter '" + mismatch.getName() + "' is not in the expected format.";
            default -> "A date parameter is not in the expected format (use YYYY-MM-DD, or YYYY-MM for a month).";
        };
        return ResponseEntity.badRequest().body(ApiResponse.error(message, "INVALID_PARAMETER"));
    }

    /**
     * Bug 09 (docs/quality/bug-reports/BUG_REVIEW_REPORT.md). The same catch-all shadowing
     * {@link #handleBindingFailure} and {@link #handleUploadTooLarge} document for their own
     * exceptions -- Spring's {@code DefaultHandlerExceptionResolver} already maps both of these
     * correctly (405 / 415), and the {@code Exception} catch-all below shadowed that mapping.
     * A wrong HTTP verb on an existing route ({@code GET /auth/login}) or a wrong
     * {@code Content-Type} came back as a 500 {@code INTERNAL_ERROR}, logged as an unhandled
     * exception -- routine scanner traffic and misconfigured clients polluting error-rate
     * alerting with what is, from the server's point of view, entirely correct behaviour.
     *
     * <p>{@code ex.getMethod()} is the HTTP verb the caller sent, not customer data, so it is
     * safe to echo -- unlike the raw exception text {@link #handleMalformedRequestBody} and
     * {@link #handleDataIntegrityViolation} deliberately withhold.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error(ex.getMethod() + " is not supported for this endpoint.",
                        "METHOD_NOT_ALLOWED"));
    }

    /** The {@code Content-Type} counterpart to {@link #handleMethodNotSupported} -- see that
     *  method's own doc comment for the shared fix (Bug 09). */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported() {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.error("That request's Content-Type is not supported for this endpoint.",
                        "UNSUPPORTED_MEDIA_TYPE"));
    }

    /**
     * An upload past {@code spring.servlet.multipart.max-file-size} (BH-010).
     *
     * <p>Spring's own {@code DefaultHandlerExceptionResolver} maps this correctly, and the
     * catch-all below shadowed it -- the same mechanism {@link #handleBindingFailure} documents for
     * the three binding exceptions. So an 11 MB statement came back as a 500 {@code INTERNAL_ERROR}
     * logged as an unhandled exception: the user was told the server had broken when the real
     * answer was "your statement is too big", and the error-rate alert counted it as a fault.
     *
     * <p>A year of statements as one PDF is an ordinary thing to try, not an attack, so the message
     * names the limit rather than being deliberately vague. The limit is not a secret -- it is in
     * the OpenAPI description and discoverable in one request either way.
     */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(
            org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        long maxBytes = ex.getMaxUploadSize();
        String limit = maxBytes > 0 ? " (limit " + (maxBytes / (1024 * 1024)) + " MB)" : "";
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error("That file is too large to upload" + limit
                        + ". Try splitting the statement, or upload it as a CSV export instead.",
                        "UPLOAD_TOO_LARGE"));
    }

    /**
     * The last unmapped client-input channel (BH-008, BH-009).
     *
     * <p>Two endpoints reached {@code IllegalArgumentException} from ordinary query parameters and
     * both produced a 500: {@code GET /import/jobs?limit=0} clamps only the upper bound and hands
     * {@code PageRequest.of(0, 0)} a size Spring Data rejects, and
     * {@code TransactionService.search} passes {@code sortDir} into
     * {@code Sort.Direction.fromString} unvalidated -- in the same method whose own comment
     * explains that it clamps page and size precisely so a malformed param stops 500ing. Two of
     * three inputs were fixed and the third was not.
     *
     * <p>Registered as a HANDLER rather than fixed only at those two call sites, deliberately: the
     * call-site clamps are still the right thing (a client should get a sensible page, not an
     * error), and this is the backstop for the next parameter nobody thought to clamp. It is the
     * same argument this class already made four times over -- the pattern is that a routine bad
     * input must not be logged and alerted as a server fault.
     *
     * <p>Does NOT echo {@code ex.getMessage()}. Spring Data's text is safe today, but this handler
     * now catches anything in the application that throws {@code IllegalArgumentException}, and
     * some of those messages will quote the value that caused them -- which on this API is
     * customer data.
     *
     * <p><b>Logged at WARN, with the stack trace, and that is deliberate.</b> This handler cannot
     * distinguish "a client sent limit=0" from "a service threw IllegalArgumentException because of
     * a real bug", and answering 400 for the second is a way to make an internal defect look like
     * the caller's fault and disappear. Debug would hide it -- prod runs at INFO. So the client
     * gets the correct status and the server keeps the evidence: a 400 here is still a line an
     * engineer can find. If a legitimate client turns one of these into steady traffic, the answer
     * is to validate that parameter at its call site (as {@code PageBounds} does) so it never
     * reaches here, not to quieten this.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex,
                                                                    HttpServletRequest request) {
        log.warn("Rejecting a request with an unusable argument on {} {}. If this is not a bad "
                        + "parameter from the caller, it is a bug in the handler for that route.",
                LogSanitizer.sanitize(request.getMethod()), LogSanitizer.sanitize(request.getRequestURI()), ex);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("One of the request's parameters is not valid.", "INVALID_PARAMETER"));
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
        log.error("Unhandled exception on {} {}", LogSanitizer.sanitize(request.getMethod()),
                LogSanitizer.sanitize(request.getRequestURI()), ex);
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        String message = isProd ? "Unexpected error" : "Unexpected error: " + ex.getMessage();
        return ResponseEntity.internalServerError().body(ApiResponse.error(message, "INTERNAL_ERROR"));
    }
}
