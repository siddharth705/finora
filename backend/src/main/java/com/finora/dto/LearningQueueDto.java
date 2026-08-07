package com.finora.dto;

import com.finora.entity.MerchantLearningEvent;
import com.finora.repository.MerchantLearningEventRepository.LearningQueueRow;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the admin merchant-learning queue.
 *
 * <p>Shaped by a single requirement: an operator must be able to answer every question about a
 * failed event from this payload alone, without opening a database client. Concretely —
 *
 * <ul>
 *   <li><b>What failed?</b> {@code merchantName} + {@code categoryName} (the confirmation that
 *       could not be applied), not just their ids.</li>
 *   <li><b>Why?</b> {@code lastError}.</li>
 *   <li><b>Which user?</b> {@code userEmail}, with {@code userId} for navigation.</li>
 *   <li><b>Which statement?</b> {@code statementFileName} + {@code statementImportId}.</li>
 *   <li><b>Which session?</b> {@code importSessionId}, null when the import never had one.</li>
 *   <li><b>How many retries?</b> {@code attemptCount} against {@code maxAttempts}.</li>
 *   <li><b>When does it retry?</b> {@code nextAttemptAt}, plus {@code retryable} so the UI does
 *       not have to re-derive the state machine.</li>
 * </ul>
 *
 * <p>{@code maxAttempts} and {@code retryable} are computed server-side deliberately. Both are
 * facts about the queue's rules, and a client that re-derives them will drift from the worker the
 * first time the cap or the terminal states change — the UI would then offer a Retry button for an
 * event the backend refuses, which is worse than offering none.
 *
 * <p>Name-vs-id pairs are carried for the same reason the projection joins them: an id alone sends
 * the operator to the database, which is the outcome this page exists to prevent. Names are
 * nullable because the row they came from may since have been deleted, and an event whose merchant
 * is gone is exactly the kind an operator most needs to see.
 */
public record LearningQueueDto(
        UUID id,
        String status,
        int attemptCount,
        int maxAttempts,
        boolean retryable,
        Instant nextAttemptAt,
        String lastError,
        Instant firstFailedAt,
        Instant lastRetryAt,
        Instant createdAt,

        // --- correlation: who and what this event came from -------------------------------------
        UUID userId,
        String userEmail,
        UUID merchantId,
        String merchantName,
        UUID categoryId,
        String categoryName,
        UUID statementImportId,
        String statementFileName,
        UUID importSessionId
) {

    public static LearningQueueDto from(LearningQueueRow row) {
        return new LearningQueueDto(
                row.getId(),
                row.getStatus().name(),
                row.getAttemptCount(),
                MerchantLearningEvent.MAX_ATTEMPTS,
                // Only a FAILED event can be retried by hand. PENDING is already scheduled and
                // retrying it would just move next_attempt_at; PROCESSING is claimed by a worker
                // right now; COMPLETED and RESOLVED are terminal and done.
                row.getStatus() == MerchantLearningEvent.Status.FAILED,
                row.getNextAttemptAt(),
                row.getLastError(),
                row.getFirstFailedAt(),
                row.getLastRetryAt(),
                row.getCreatedAt(),
                row.getUserId(),
                row.getUserEmail(),
                row.getMerchantId(),
                row.getMerchantName(),
                row.getCategoryId(),
                row.getCategoryName(),
                row.getStatementImportId(),
                row.getStatementFileName(),
                row.getImportSessionId());
    }

    /** The counts behind the status filter chips, so the page can show "3 failed" without
     *  fetching every page to count them. */
    public record QueueSummary(long pending, long processing, long failed, long completed, long resolved) {}
}
