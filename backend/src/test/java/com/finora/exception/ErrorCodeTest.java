package com.finora.exception;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ErrorCode.RetryPolicy} -- Premium Import Reliability v1, §5.1. Additive infrastructure:
 * this commit changes no behavior, since nothing reads {@code retryPolicy()} yet (that's
 * {@code ExceptionClassifier}, §5.3, and {@code ImportJobWorker}'s catch site, §5.5). What has to
 * be true right now is that adding the field didn't silently change any existing code's default,
 * and that the three values the reliability plan's three-tier model specifies actually exist.
 */
class ErrorCodeTest {

    @Test
    void everyExistingCodeDefaultsToFailFast() {
        // The three-arg constructor is what every ErrorCode constant in the enum body still uses
        // -- confirmed here by asserting the default across ALL of them, not a hand-picked sample,
        // so a future code added with the short constructor is covered by this test automatically
        // without anyone remembering to add it to a list.
        assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::retryPolicy).distinct().toList())
                .as("every ErrorCode uses the short constructor today; none has opted into a "
                        + "different policy yet")
                .containsExactly(ErrorCode.RetryPolicy.FAIL_FAST);
    }

    @Test
    void retryPolicyHasExactlyTheThreeTiersTheReliabilityPlanSpecifies() {
        assertThat(ErrorCode.RetryPolicy.values()).containsExactly(
                ErrorCode.RetryPolicy.FAIL_FAST,
                ErrorCode.RetryPolicy.RETRY,
                ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT);
    }

    @Test
    void aRepresentativeImportCodeIsFailFast() {
        // A named spot-check on top of the exhaustive check above, for a code whose FAIL_FAST-ness
        // is actually load-bearing (a corrupt PDF genuinely cannot be fixed by retrying) rather
        // than incidental -- this is the case ExceptionClassifier will need to get right first.
        assertThat(ErrorCode.IMPORT_CORRUPT_PDF.retryPolicy()).isEqualTo(ErrorCode.RetryPolicy.FAIL_FAST);
    }

    // ---------------------------------------------------------------- userActionRequired (§1, Sprint 4 item 20a)

    /**
     * The five codes Premium Import Reliability v1 §1's table originally named, plus every code
     * added since that also opted in, asserted exhaustively against the live enum (not a
     * hand-picked subset) so a future code added to this set -- or silently dropped from it -- is
     * caught here rather than only in a UI that happens to render the wrong copy for it.
     *
     * <p>Bug fix: SEC-02 (docs/quality/bug-reports/2026-08-19-security-review-findings.md) added
     * {@code IMPORT_PDF_TOO_LARGE} with {@code userActionRequired = true} -- its own doc comment
     * gives the same reasoning as {@code IMPORT_CORRUPT_PDF}'s sibling codes above ("split it up"
     * is a real, followable instruction) -- but this exhaustive assertion was never updated to
     * include it, so CI failed on every run past that commit until now.
     */
    @Test
    void theCodesTheReliabilityPlanAndItsSuccessorsNameRequireUserAction() {
        assertThat(Arrays.stream(ErrorCode.values()).filter(ErrorCode::userActionRequired).toList())
                .containsExactlyInAnyOrder(
                        ErrorCode.IMPORT_PDF_PASSWORD_REQUIRED,
                        ErrorCode.IMPORT_PDF_PASSWORD_INVALID,
                        ErrorCode.IMPORT_SCANNED_OCR_REQUIRED,
                        ErrorCode.IMPORT_NO_HEADER_DETECTED,
                        ErrorCode.IMPORT_NO_TRANSACTIONS_FOUND,
                        ErrorCode.IMPORT_PDF_TOO_LARGE);
    }

    @Test
    void corruptPdfStaysPlainFailed() {
        // The plan's own table draws this line explicitly: a corrupt/truncated PDF has no single
        // thing to tell the user to check or change, unlike the five above -- named here since it's
        // the one code most likely to be mistaken for ACTION_REQUIRED by a future edit (it IS a
        // "try again" case, just not a user-actionable one).
        assertThat(ErrorCode.IMPORT_CORRUPT_PDF.userActionRequired()).isFalse();
    }

    @Test
    void userActionRequiredOrDefaultTranslatesAKnownActionableCode() {
        assertThat(ErrorCode.userActionRequiredOrDefault("IMPORT_NO_HEADER_DETECTED")).isTrue();
    }

    @Test
    void userActionRequiredOrDefaultIsFalseForAKnownNonActionableCode() {
        assertThat(ErrorCode.userActionRequiredOrDefault("IMPORT_CORRUPT_PDF")).isFalse();
    }

    @Test
    void userActionRequiredOrDefaultIsFalseForAnExceptionClassNameRatherThanThrowing() {
        // Mirrors wireCodeOrNullReturnsNullForAnExceptionClassNameRatherThanThrowing -- the same
        // stored-value ambiguity, the same safe fallback.
        assertThat(ErrorCode.userActionRequiredOrDefault("NullPointerException")).isFalse();
    }

    @Test
    void userActionRequiredOrDefaultIsFalseForANullInput() {
        assertThat(ErrorCode.userActionRequiredOrDefault(null)).isFalse();
    }

    // ---------------------------------------------------------------- wireCodeOrNull (§3.1)

    @Test
    void wireCodeOrNullTranslatesAKnownEnumNameToItsWireCode() {
        assertThat(ErrorCode.wireCodeOrNull("IMPORT_NO_HEADER_DETECTED")).isEqualTo("IMPORT_001");
    }

    @Test
    void wireCodeOrNullReturnsNullForAnExceptionClassNameRatherThanThrowing() {
        // The fallback both StatementAnalysisSession.failureCode and ImportJob.failureCode use
        // when a failure never carried an ApiException with a code -- ErrorCode.valueOf would
        // throw on this input, and a raw Java class name is not something either caller should
        // pass through to a customer response regardless.
        assertThat(ErrorCode.wireCodeOrNull("NullPointerException")).isNull();
    }

    @Test
    void wireCodeOrNullReturnsNullForANullInput() {
        assertThat(ErrorCode.wireCodeOrNull(null)).isNull();
    }

    // ---------------------------------------------------------------- failureCodeOf (§3.1)

    @Test
    void failureCodeOfAnApiExceptionWithACodeIsTheCodesEnumName() {
        assertThat(ErrorCode.failureCodeOf(new ApiException(ErrorCode.IMPORT_CORRUPT_PDF)))
                .isEqualTo("IMPORT_CORRUPT_PDF");
    }

    @Test
    void failureCodeOfACodelessApiExceptionIsNull() {
        // The pre-existing codeless-throw pattern (new ApiException(HttpStatus, message)) still
        // exists elsewhere in the codebase -- an ApiException carrying no ErrorCode has nothing to
        // name here, same as any other exception type this method has never seen.
        assertThat(ErrorCode.failureCodeOf(
                new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "no code here")))
                .isNull();
    }

    @Test
    void failureCodeOfAnyOtherExceptionIsItsSimpleClassName() {
        assertThat(ErrorCode.failureCodeOf(new NullPointerException("boom"))).isEqualTo("NullPointerException");
        assertThat(ErrorCode.failureCodeOf(new IllegalStateException("boom"))).isEqualTo("IllegalStateException");
    }

    /**
     * The pairing this exists for: what {@link ErrorCode#failureCodeOf} writes,
     * {@link ErrorCode#wireCodeOrNull} must be able to read back correctly -- proven end to end
     * rather than trusting each half's own unit tests to agree with each other by coincidence.
     */
    @Test
    void failureCodeOfAndWireCodeOrNullRoundTripForACuratedFailure() {
        String stored = ErrorCode.failureCodeOf(new ApiException(ErrorCode.IMPORT_NO_HEADER_DETECTED));
        assertThat(ErrorCode.wireCodeOrNull(stored)).isEqualTo("IMPORT_001");
    }
}
