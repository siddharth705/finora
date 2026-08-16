package com.finora.imports.jobs;

import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.storage.StatementIntegrityException;
import com.finora.imports.storage.StatementStorageException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Premium Import Reliability v1, §5.3. Standalone from {@code ImportJobWorker} on purpose --
 * nothing here starts a Spring context or touches the worker; this proves the classification
 * function in isolation, before item 4 wires it into anything that can actually retry.
 */
class ExceptionClassifierTest {

    private final ExceptionClassifier classifier = new ExceptionClassifier();

    @Test
    void aKnownImportFailure_isFailFast() {
        // Every current ErrorCode defaults to FAIL_FAST (previous commit) -- this is the
        // representative case Sid's brief named, not the exhaustive one below.
        assertThat(classifier.classify(new ApiException(ErrorCode.IMPORT_PDF_PASSWORD_REQUIRED)))
                .isEqualTo(ErrorCode.RetryPolicy.FAIL_FAST);
    }

    @Test
    void everyErrorCode_classifiesToExactlyItsOwnRetryPolicy_notAHardcodedValue() {
        // Proves delegation, not duplication: if this class ever grew its own switch over
        // ErrorCode instead of reading retryPolicy(), a future code with a non-default policy
        // would silently classify wrong here while ErrorCode itself said the right thing. Covers
        // every current code, including ones with no ImportJobWorker relevance (TXN/AUTH/generic)
        // -- classify() has no opinion about WHICH codes it will realistically see, only about
        // what a given code's own policy says.
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(classifier.classify(new ApiException(code)))
                    .as("classify() for %s must equal %s.retryPolicy(), not a value this class invented", code, code)
                    .isEqualTo(code.retryPolicy());
        }
    }

    @Test
    void anApiExceptionWithNoErrorCode_isRetryOnceThenAlert_notFailFast() {
        // The pre-existing codeless-throw pattern (new ApiException(HttpStatus, message)) still
        // exists elsewhere in the codebase. An ApiException that carries no ErrorCode is exactly
        // as unclassified to this method as any exception type it has never seen -- not the same
        // as a KNOWN permanent failure, which is what FAIL_FAST would wrongly imply.
        assertThat(classifier.classify(new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "no code here")))
                .isEqualTo(ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT);
    }

    @Test
    void statementStorageFailure_isRetried() {
        assertThat(classifier.classify(new StatementStorageException("R2 unavailable")))
                .isEqualTo(ErrorCode.RetryPolicy.RETRY);
    }

    /**
     * BH-045. {@link StatementIntegrityException} extends {@link StatementStorageException}, so an
     * {@code instanceof StatementStorageException} check alone would silently also match it -- this
     * proves the subclass is checked first and gets a genuinely different policy, not the parent's.
     * Per that exception's own class doc, "retrying reads the same wrong bytes forever": RETRY (the
     * parent's policy) would spend the full 5-attempt budget on a mismatch that cannot resolve
     * itself, at only WARNING severity. RETRY_ONCE_THEN_ALERT is the closer fit of the three
     * policies this class has -- it does not waste that budget, and its ERROR severity is
     * appropriate for what may be a tampered or corrupted statement, not a passing outage.
     */
    @Test
    void statementIntegrityFailure_isRetriedOnceThenAlerted_notTreatedAsAnOrdinaryStorageRetry() {
        assertThat(classifier.classify(new StatementIntegrityException("hash mismatch")))
                .isEqualTo(ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT);
    }

    @Test
    void aDatabaseFailure_isRetried() {
        DataAccessException dbFailure = new DataAccessException("connection timed out") { };
        assertThat(classifier.classify(dbFailure)).isEqualTo(ErrorCode.RetryPolicy.RETRY);
    }

    @Test
    void anUnrecognizedApplicationException_isRetriedOnceThenAlerted_notFailFastNorUnlimitedRetry() {
        // "Does not consume all 5 retries" as an observed runtime behaviour is item 4's job
        // (ImportJob.recordFailure/the worker's attempt loop, neither touched here) -- what this
        // class can and does prove is that it returns a DIFFERENT classification than a known
        // permanent failure or a known transient one, for exactly the case (a genuine bug) where
        // spending all 5 attempts is wasted and spending zero risks losing a real transient crash.
        assertThat(classifier.classify(new NullPointerException("boom")))
                .isEqualTo(ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT);
        assertThat(classifier.classify(new IllegalStateException("unexpected state")))
                .isEqualTo(ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT);
    }

    @Test
    void allThreeOutcomesAreDistinguishable() {
        // A sanity check the individual tests above don't state directly: a permanent failure, a
        // transient one, and an unknown one must not collapse to the same policy, or the whole
        // point of classifying is lost.
        var failFast = classifier.classify(new ApiException(ErrorCode.IMPORT_NO_HEADER_DETECTED));
        var retry = classifier.classify(new StatementStorageException("boom"));
        var retryOnceThenAlert = classifier.classify(new RuntimeException("mystery"));

        assertThat(java.util.Set.of(failFast, retry, retryOnceThenAlert)).hasSize(3);
    }
}
