package com.finora.imports.jobs;

import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.storage.StatementIntegrityException;
import com.finora.imports.storage.StatementStorageException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Whether an exception caught while processing a queued import is worth retrying -- Premium
 * Import Reliability v1, §5.3. {@link ImportJobWorker}'s only planned caller (§5.5, not yet
 * wired): before this exists, every exception the worker catches is treated identically and
 * retried up to {@code ImportJob.MAX_ATTEMPTS} times regardless of whether retrying could ever
 * succeed -- a password-protected PDF and an R2 outage get the same 5 attempts and ~31 minutes.
 *
 * <h2>Why this is not a method on {@code ErrorCode}</h2>
 * {@code ErrorCode} is a vocabulary of known, named import/business failures -- what happened to
 * the document from a user's perspective. {@link StatementStorageException} and a {@link
 * DataAccessException} are not {@code ErrorCode}s and never will be: they are infrastructure
 * failures, classified by their Java TYPE, not by a code anyone chose. Folding that dispatch into
 * {@code ErrorCode} would mean a new infrastructure exception type requires touching the import
 * vocabulary enum to be classified at all, and a new {@code ErrorCode} would need to reason about
 * exception types it has nothing to do with. Splitting them keeps each free to evolve alone: this
 * class owns "what KIND of exception is this", {@code ErrorCode.retryPolicy()} owns "what does
 * Finora already know about this specific known failure" -- and this class defers to that field
 * for the one type that has an opinion of its own, {@link ApiException}, rather than
 * reimplementing it.
 *
 * <h2>An unrecognized exception is retried once, not five times, not zero</h2>
 * The honest answer to "is an exception this class has never seen before transient or permanent"
 * is "not yet known" -- defaulting to always-retryable repeats the full 31-minute cost on a bug
 * that will never succeed; defaulting to never-retryable risks permanently dead-lettering a
 * genuinely transient failure on its first occurrence, with nothing to say it happened. One retry
 * absorbs a real transient blip without the full cost of assuming every unknown failure is one;
 * this class only decides the classification, once per call -- the retry count itself, and what
 * "once" means in practice, is {@code ImportJob.recordFailure}'s job (§5.4), not this one's.
 */
@Component
public class ExceptionClassifier {

    /**
     * @param e the exception a caller is deciding whether to retry.
     * @return {@link ErrorCode.RetryPolicy#FAIL_FAST} for a known, permanent import/business
     *         failure; {@link ErrorCode.RetryPolicy#RETRY} for a recognized infrastructure
     *         exception; {@link ErrorCode.RetryPolicy#RETRY_ONCE_THEN_ALERT} for anything this
     *         method does not recognize, including an {@link ApiException} with no {@code
     *         ErrorCode} attached -- an unclassified {@code ApiException} is exactly as unknown
     *         to this class as any other exception type it has never seen.
     */
    public ErrorCode.RetryPolicy classify(Throwable e) {
        // BH-045: ahead of its parent, and deliberately NOT mapped to RETRY like the parent is.
        // StatementIntegrityException's own doc: "this is a correctness problem... retrying reads
        // the same wrong bytes forever" -- unlike a plain StatementStorageException (an object
        // that's missing or unreachable, which a real RETRY might outlast), a hash mismatch fails
        // identically on every one of RETRY's 5 attempts. RETRY_ONCE_THEN_ALERT is the closer fit
        // of the three policies here: it does not burn the full ~31-minute budget on a retry that
        // cannot succeed, and its ERROR severity (unlike RETRY's WARNING) pages someone for what
        // may be a tampered or corrupted statement, not a passing blip.
        //
        // Ahead of the ApiException branch too, now that it inspects the cause chain: an integrity
        // failure is what it is regardless of what carries it, and this ordering is what lets one
        // definition serve every caller instead of each branch reaching its own verdict.
        if (isIntegrityFailure(e)) return ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT;
        if (e instanceof ApiException api) {
            return api.getCode() != null ? api.getCode().retryPolicy() : ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT;
        }
        if (e instanceof StatementStorageException) return ErrorCode.RetryPolicy.RETRY;
        if (e instanceof DataAccessException) return ErrorCode.RetryPolicy.RETRY;
        return ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT;
    }

    /**
     * The one authoritative answer to "is this fundamentally an integrity failure".
     *
     * <p>It lives here because this class is already where the codebase decides what an exception
     * means, and because more than one caller needs the answer: {@link #classify} picks a retry
     * policy from it, and {@code ImportJobWorker} decides from it whether a dead-lettered job is
     * operator-remediable enough to enter the triage queue. Those two used to answer it
     * separately, which is a thing that only stays true by luck -- the same exception could carry
     * different semantics depending on which one asked.
     *
     * <p><b>Walks the cause chain.</b> Wrapping is idiomatic in this pipeline: {@code
     * GzipCompression}, {@code FilesystemStatementStorage} and {@code R2StatementStorage} all
     * rethrow as {@link StatementStorageException}, which is {@link StatementIntegrityException}'s
     * own PARENT. A top-level {@code instanceof} therefore fails in the most damaging direction --
     * a wrapped integrity failure reads as a plain storage outage and gets {@code RETRY}, spending
     * five attempts and ~31 minutes re-reading bytes that cannot become right, and delaying the
     * ERROR alert that should have fired on the second. Nothing wraps one today; the check is
     * cheap and the failure is silent, which is the combination worth pre-empting.
     *
     * <p>Static and pure, matching {@code ErrorCode.failureCodeOf}'s shape -- a caller needs the
     * answer, not an injected collaborator, and this depends on nothing but the throwable.
     */
    public static boolean isIntegrityFailure(Throwable e) {
        return causeOfType(e, StatementIntegrityException.class) != null;
    }

    /**
     * How far down a cause chain to look before giving up.
     *
     * <p>A bound rather than a cycle detector, because a cause chain can genuinely be cyclic and
     * the cheap guard for it is wrong. {@code initCause} rejects a throwable as its own cause, so
     * {@code t != t.getCause()} looks sufficient and is not: two throwables can each end up as the
     * other's cause ({@code new Exception(a)}, then {@code a.initCause(b)}), and that walk never
     * terminates. This runs on the worker thread, where "never terminates" means a stuck import
     * queue rather than an exception. Fifty is far past any real chain.
     */
    private static final int MAX_CAUSE_DEPTH = 50;

    /** First occurrence of {@code type} in the cause chain, or null. Bounded -- see
     *  {@link #MAX_CAUSE_DEPTH} for why a cycle is a real possibility and not paranoia. */
    private static <T extends Throwable> T causeOfType(Throwable throwable, Class<T> type) {
        Throwable t = throwable;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            Throwable next = t.getCause();
            if (next == t) {
                break;
            }
            t = next;
        }
        return null;
    }
}
