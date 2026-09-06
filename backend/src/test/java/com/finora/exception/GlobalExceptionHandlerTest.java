package com.finora.exception;

import com.finora.security.RefreshTokenCookie;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the production-readiness fix to handleGeneric(): the raw exception message used to be
 * included in every 500 response regardless of profile -- not a full stack trace, but still a
 * real information-disclosure risk for a financial API (a SQL exception's message can carry
 * table/column/constraint names, for instance). Now only withheld specifically in the prod
 * profile, with the full exception always logged server-side either way (not asserted here --
 * that's a side effect on a real logger, not this method's return value).
 */
class GlobalExceptionHandlerTest {

    /** handleGeneric reads only the method and path, to name the failing endpoint in the log. */
    private static jakarta.servlet.http.HttpServletRequest request() {
        var request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/import/pdf/stage");
        return request;
    }

    @Test
    void handleGeneric_inProdProfile_withholdsTheRawExceptionMessage() {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        GlobalExceptionHandler handler = new GlobalExceptionHandler(environment, mock(RefreshTokenCookie.class));

        var response = handler.handleGeneric(new RuntimeException("column \"ssn\" violates not-null constraint"), request());

        assertThat(response.getBody().message()).isEqualTo("Unexpected error");
        assertThat(response.getBody().message()).doesNotContain("ssn", "constraint");
    }

    @Test
    void handleGeneric_outsideProdProfile_includesTheRawMessageForDeveloperConvenience() {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        GlobalExceptionHandler handler = new GlobalExceptionHandler(environment, mock(RefreshTokenCookie.class));

        var response = handler.handleGeneric(new RuntimeException("boom"), request());

        assertThat(response.getBody().message()).isEqualTo("Unexpected error: boom");
    }

    @Test
    void handleGeneric_withNoActiveProfilesAtAll_defaultsToIncludingTheMessage() {
        // e.g. a plain unit/integration test context with no profile set -- must not accidentally
        // behave as if it were prod just because the profiles array is empty.
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{});
        GlobalExceptionHandler handler = new GlobalExceptionHandler(environment, mock(RefreshTokenCookie.class));

        var response = handler.handleGeneric(new RuntimeException("boom"), request());

        assertThat(response.getBody().message()).isEqualTo("Unexpected error: boom");
    }

    @Test
    void handleGeneric_alwaysReturns500() {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        GlobalExceptionHandler handler = new GlobalExceptionHandler(environment, mock(RefreshTokenCookie.class));

        var response = handler.handleGeneric(new RuntimeException("boom"), request());

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().errorCode()).isEqualTo("INTERNAL_ERROR");
    }

    /**
     * Bug fix: a concurrent write to a @Version-carrying entity (e.g. Account, hit by two
     * simultaneous transaction posts) throws OptimisticLockingFailureException, which previously
     * had no dedicated handler and fell through to handleGeneric() above -- a routine, expected
     * concurrency conflict returned an opaque 500 instead of a clear, actionable 409.
     */
    @Test
    void handleOptimisticLock_returns409_withAClearRetryMessage_notTheGeneric500() {
        Environment environment = mock(Environment.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(environment, mock(RefreshTokenCookie.class));

        var response = handler.handleOptimisticLock();

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().errorCode()).isEqualTo("CONFLICT");
        assertThat(response.getBody().message()).contains("refresh and try again");
    }

    /**
     * Bug fix: a 5xx ApiException returned a server error with NO log line at all.
     *
     * Every 4xx here is a deliberate, expected rejection and correctly stays silent -- but a 5xx is
     * this server saying it failed, and those were invisible. IMPORT_SYSTEM_BUSY (503) and the
     * Firebase-unconfigured 503 both produced a failing response leaving nothing behind to find it
     * by. Found while hunting a production 500 whose stack trace could not be located anywhere,
     * which is exactly what this gap costs.
     *
     * Asserted through a captured appender rather than by eyeballing output, so "it logs" is a
     * fact the build checks rather than a claim in a comment.
     *
     * <p>Uses {@code ErrorCode.INTERNAL_ERROR} rather than {@code IMPORT_SYSTEM_BUSY} as the
     * example 5xx (BH-043: {@code IMPORT_SYSTEM_BUSY} now has {@code intentionalRejection=true}
     * and logs at WARN, not ERROR -- see {@link #handleApiException_logsAnIntentionalRejection_atWarnWithNoStackTrace}
     * below for that path specifically). This test is about the general "a 5xx this enum has no
     * opinion about is a genuine failure" rule, which {@code INTERNAL_ERROR} still exercises.
     */
    @Test
    void handleApiException_logsA5xx_soAServerFailureIsNeverSilent() {
        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            GlobalExceptionHandler handler = new GlobalExceptionHandler(mock(Environment.class), mock(RefreshTokenCookie.class));

            var response = handler.handleApiException(
                    new ApiException(ErrorCode.INTERNAL_ERROR), request());

            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel())
                    .isEqualTo(ch.qos.logback.classic.Level.ERROR);
            // The endpoint has to be in the line, or finding which call failed means correlating
            // by timestamp -- the exact problem that made the production 500 so hard to locate.
            assertThat(appender.list.get(0).getFormattedMessage())
                    .contains("/api/v1/import/pdf/stage")
                    .contains("GEN_002");
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * BH-043: the one exception to the rule the test above covers. IMPORT_SYSTEM_BUSY now fires
     * on every ordinary burst past the concurrency limit (see ImportConcurrencyLimiter's own doc),
     * not rarely after a 20s timeout -- logging it at ERROR with a full stack trace on every
     * occurrence would make routine, correctly-handled backpressure look like a server outage and
     * could flood ERROR-level alerting with what the system is doing right. WARN, and no
     * stack-trace argument to the log call (asserted via {@code getThrowableProxy()} being null,
     * not just eyeballing the formatted message) -- the code and message already say everything
     * there is to know; there is no origin to go find.
     */
    @Test
    void handleApiException_logsAnIntentionalRejection_atWarnWithNoStackTrace() {
        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            GlobalExceptionHandler handler = new GlobalExceptionHandler(mock(Environment.class), mock(RefreshTokenCookie.class));

            var response = handler.handleApiException(
                    new ApiException(ErrorCode.IMPORT_SYSTEM_BUSY), request());

            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel())
                    .isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(appender.list.get(0).getThrowableProxy())
                    .as("no stack trace attached -- nothing to find, the message already says why")
                    .isNull();
            assertThat(appender.list.get(0).getFormattedMessage())
                    .contains("/api/v1/import/pdf/stage")
                    .contains("IMPORT_006");
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * Bug fix: malformed JSON in a @RequestBody (e.g. a truncated body, or the wrong type for a
     * field) throws HttpMessageNotReadableException during argument resolution -- before
     * handleValidation() ever runs. This previously had no dedicated handler and fell through to
     * handleGeneric(), turning an ordinary client mistake into an opaque 500 instead of a clean
     * 400 -- the same bug shape already fixed for AccessDeniedException and
     * OptimisticLockingFailureException in this class.
     */
    @Test
    void handleMalformedRequestBody_returns400_notTheGeneric500() {
        Environment environment = mock(Environment.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(environment, mock(RefreshTokenCookie.class));

        var response = handler.handleMalformedRequestBody();

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().errorCode()).isEqualTo("MALFORMED_REQUEST_BODY");
    }

    // ---------------------------------------------------------------- userActionRequired (§1, Sprint 4 item 22)

    /**
     * Closes the drift risk Sprint 4 item 22 shipped with and flagged rather than fixed: the
     * frontend used to keep its own hardcoded copy of which codes are user-actionable. This
     * centralizes the merge here, exactly like errorCode two lines above it -- so every one of
     * ErrorCode's ~24 constants gets this on the wire with no throw-site changes needed.
     */
    @Test
    void handleApiException_addsUserActionRequiredTrue_forAnActionableCode() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(mock(Environment.class), mock(RefreshTokenCookie.class));

        var response = handler.handleApiException(
                new ApiException(ErrorCode.IMPORT_NO_HEADER_DETECTED), request());

        assertThat(response.getBody().details()).containsEntry("userActionRequired", true);
    }

    @Test
    void handleApiException_addsUserActionRequiredFalse_forANonActionableCode() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(mock(Environment.class), mock(RefreshTokenCookie.class));

        var response = handler.handleApiException(
                new ApiException(ErrorCode.IMPORT_CORRUPT_PDF), request());

        assertThat(response.getBody().details()).containsEntry("userActionRequired", false);
    }

    /**
     * The pre-existing, still-common case: a throw site using the plain (status, message)
     * constructor has no ErrorCode at all -- see ErrorCode's own class doc on incremental
     * adoption. There is no classification to offer here, so this must not invent one (e.g. by
     * defaulting to false and thereby claiming a considered answer that was never actually given).
     */
    @Test
    void handleApiException_addsNoUserActionRequiredKey_whenTheExceptionCarriesNoErrorCode() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(mock(Environment.class), mock(RefreshTokenCookie.class));

        var response = handler.handleApiException(
                new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "plain message"), request());

        assertThat(response.getBody().details()).doesNotContainKey("userActionRequired");
    }

    /**
     * No current throw site populates a non-empty details map (every one of the ~75 in this
     * backend uses the 2/3-arg constructors, which default to Collections.emptyMap()) -- but the
     * merge itself (new HashMap<>(ex.getDetails())) is written to preserve whatever WAS there,
     * only adding userActionRequired alongside it, and that behavior had no test exercising it
     * until now.
     */
    @Test
    void handleApiException_preservesTheThrowSitesOwnDetails_whileAddingUserActionRequired() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(mock(Environment.class), mock(RefreshTokenCookie.class));

        var response = handler.handleApiException(
                new ApiException(ErrorCode.IMPORT_NO_HEADER_DETECTED.defaultStatus(),
                        ErrorCode.IMPORT_NO_HEADER_DETECTED, ErrorCode.IMPORT_NO_HEADER_DETECTED.defaultMessage(),
                        java.util.Map.of("rowsChecked", 12)),
                request());

        assertThat(response.getBody().details())
                .containsEntry("rowsChecked", 12)
                .containsEntry("userActionRequired", true);
    }

    /**
     * The other half, and the reason this isn't just "log everything": a 4xx is the server working
     * correctly. Logging every rejected password or malformed upload at ERROR would bury the real
     * failures this change exists to surface.
     */
    @Test
    void handleApiException_staysSilentForA4xx_whichIsTheServerWorkingCorrectly() {
        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            GlobalExceptionHandler handler = new GlobalExceptionHandler(mock(Environment.class), mock(RefreshTokenCookie.class));

            var response = handler.handleApiException(
                    new ApiException(ErrorCode.IMPORT_NO_TRANSACTIONS_FOUND), request());

            assertThat(response.getStatusCode().value()).isEqualTo(422);
            assertThat(appender.list).isEmpty();
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ---------------------------------------------------------------- Bug 09

    /**
     * Bug 09. A wrong HTTP verb on an existing route (GET on a POST-only endpoint, say) used to
     * fall through the catch-all below and come back as a 500 INTERNAL_ERROR instead of the 405
     * Spring's own DefaultHandlerExceptionResolver would have produced unassisted.
     */
    @Test
    void handleMethodNotSupported_returns405_notTheGeneric500() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(mock(Environment.class), mock(RefreshTokenCookie.class));

        var response = handler.handleMethodNotSupported(
                new org.springframework.web.HttpRequestMethodNotSupportedException("GET"));

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getBody().errorCode()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(response.getBody().message()).contains("GET");
    }

    /** Bug 09's other half -- a wrong Content-Type used to hit the same catch-all. */
    @Test
    void handleMediaTypeNotSupported_returns415_notTheGeneric500() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(mock(Environment.class), mock(RefreshTokenCookie.class));

        var response = handler.handleMediaTypeNotSupported();

        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertThat(response.getBody().errorCode()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }
}
