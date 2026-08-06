package com.finora.service;

import com.finora.entity.MerchantLearningEvent;
import com.finora.repository.MerchantLearningEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Applies queued merchant-learning confirmations, one at a time, outside the import transaction
 * that produced them.
 *
 * <h2>Why the transaction boundaries look like this</h2>
 * The obvious implementation — claim a batch and process it in one transaction — cannot work, for
 * the reason this whole milestone exists. {@code MerchantLearningService.confirm} does a
 * check-then-act against {@code UNIQUE(user_id, merchant_id, category_id)}, so a lost race throws
 * a constraint violation, and a constraint violation marks the transaction rollback-only. Once
 * that happens the failure CANNOT be recorded in the same transaction: the write to
 * {@code last_error} would itself be rolled back, and the event would return to the queue with no
 * evidence of what went wrong, forever. {@code MerchantNormalizationEngine.resolve} states the
 * rule this runs into — "By the time the constraint fires the transaction is already poisoned, and
 * no handling un-poisons it."
 *
 * <p>So there are three boundaries, deliberately:
 *
 * <ol>
 *   <li><b>Claim</b> — select due rows {@code FOR UPDATE SKIP LOCKED}, flip them to PROCESSING,
 *       commit. The lock stops a concurrent worker taking them <em>during</em> the claim; the
 *       PROCESSING status stops one taking them afterwards, since claims only look at PENDING.</li>
 *   <li><b>Apply</b> — one transaction per event. A poisoned transaction costs exactly one
 *       event.</li>
 *   <li><b>Record the outcome</b> — a fresh transaction, entered only after the apply transaction
 *       has already rolled back. This one is not poisoned, so the failure actually persists.</li>
 * </ol>
 *
 * <p>{@link TransactionTemplate} rather than {@code @Transactional} on three private methods,
 * because Spring does not proxy self-invocation — the annotations would be silently ignored and
 * everything would run in one transaction, which is precisely the bug. Explicit boundaries also
 * make the three-phase structure visible to the next reader instead of implied.
 */
@Component
public class MerchantLearningEventWorker {

    private static final Logger log = LoggerFactory.getLogger(MerchantLearningEventWorker.class);

    /** How many events one pass claims. Bounded so a backlog drains steadily instead of holding a
     *  connection from a pool capped at 10 for an unbounded stretch. */
    private static final int BATCH_SIZE = 50;

    /** How many stuck rows one pass recovers. Same reasoning as the batch size. */
    private static final int RECOVERY_BATCH_SIZE = 50;

    /**
     * How long a row may sit in PROCESSING before it is assumed abandoned.
     *
     * <p>Generous on purpose. The cost of recovering too early is applying a confirmation twice —
     * which corrupts {@code confirmation_count}, and confirmation counts decide the auto-applied
     * category. The cost of recovering too late is a delay. Those are not symmetric, so this waits
     * far longer than any real apply could take.
     */
    private static final Duration PROCESSING_TIMEOUT = Duration.ofMinutes(15);

    private final MerchantLearningEventRepository repository;
    private final MerchantLearningService learningService;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.learning.queue.enabled:true}")
    private boolean enabled;

    public MerchantLearningEventWorker(MerchantLearningEventRepository repository,
                                        MerchantLearningService learningService,
                                        TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.learningService = learningService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * The backstop. Collects anything the nudge missed — a process that died between commit and
     * notify, a retry whose backoff has now elapsed, a row abandoned by a crashed worker.
     *
     * <p>{@code fixedDelay}, not {@code fixedRate}: the next pass starts after the previous one
     * finishes, so a slow pass cannot cause overlapping runs to pile up.
     */
    @Scheduled(fixedDelayString = "${app.learning.queue.poll-interval-ms:30000}",
               initialDelayString = "${app.learning.queue.initial-delay-ms:15000}")
    public void poll() {
        if (!enabled) return;
        recoverAbandoned();
        drainOnce();
    }

    /**
     * Fire-and-forget trigger for {@link MerchantLearningEventPublisher}, so a confirmation is
     * usually applied within milliseconds instead of waiting for the next poll.
     *
     * <p>Runs on a separate thread deliberately: the caller is a request thread that has just
     * committed a user's import, and it must not wait for learning to be applied. Concurrent runs
     * with the poller are safe — {@code SKIP LOCKED} is what makes that true.
     */
    @Async("learningQueueExecutor")
    public void nudge() {
        if (!enabled) return;
        drainOnce();
    }

    /**
     * Claims and processes one batch. Returns how many events were processed.
     *
     * <p>Public and synchronous so tests can drive the queue deterministically rather than waiting
     * on a scheduler.
     */
    public int drainOnce() {
        List<UUID> claimed = claimBatch();
        for (UUID eventId : claimed) {
            applyOne(eventId);
        }
        return claimed.size();
    }

    /** Phase 1. Holds the row lock only for the length of this transaction; the PROCESSING status
     *  it writes is what keeps other workers off the rows afterwards. */
    private List<UUID> claimBatch() {
        List<UUID> ids = transactionTemplate.execute(status -> {
            Instant now = Instant.now();
            List<MerchantLearningEvent> due = repository.claimDueEvents(now, BATCH_SIZE);
            due.forEach(event -> event.markProcessing(now));
            repository.saveAll(due);
            return due.stream().map(MerchantLearningEvent::getId).toList();
        });
        return ids == null ? List.of() : ids;
    }

    /** Phases 2 and 3. The catch is outside the transaction template on purpose — by the time it
     *  runs, the apply transaction has already rolled back, so {@link #recordFailure} gets a clean
     *  one to write into. */
    private void applyOne(UUID eventId) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                MerchantLearningEvent event = repository.findById(eventId).orElse(null);
                if (event == null) {
                    // The merchant, category or user was deleted between claim and apply; the
                    // CASCADE took the event with it. Nothing to do and nothing wrong.
                    return;
                }
                learningService.confirm(event.getUserId(), event.getMerchantId(), event.getCategoryId());
                event.markCompleted(Instant.now());
                repository.save(event);
            });
        } catch (RuntimeException e) {
            recordFailure(eventId, e);
        }
    }

    private void recordFailure(UUID eventId, RuntimeException cause) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                MerchantLearningEvent event = repository.findById(eventId).orElse(null);
                if (event == null) return;
                event.recordFailure(describe(cause), Instant.now());
                repository.save(event);
                if (event.getStatus() == MerchantLearningEvent.Status.FAILED) {
                    log.error("Merchant learning event {} failed {} times and will not be retried "
                            + "automatically; it is now visible in the admin queue. merchant={} "
                            + "category={}", eventId, event.getAttemptCount(), event.getMerchantId(),
                            event.getCategoryId(), cause);
                } else {
                    log.warn("Merchant learning event {} failed (attempt {}), retrying at {}",
                            eventId, event.getAttemptCount(), event.getNextAttemptAt());
                }
            });
        } catch (RuntimeException e) {
            // Recording the failure itself failed -- the row stays PROCESSING and recoverAbandoned
            // will return it to the queue. Logged rather than rethrown: this runs on a scheduler or
            // an async nudge, and there is nobody to hand an exception to.
            log.error("Could not record the failure of merchant learning event {}", eventId, e);
        }
    }

    /**
     * Returns events abandoned in PROCESSING to the queue.
     *
     * <p>A worker that dies mid-apply releases its row lock but leaves the status at PROCESSING,
     * and no claim will ever see it again because claims only look at PENDING. Without this, one
     * crashed instance silently strands everything it had in flight.
     */
    private void recoverAbandoned() {
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();
            List<MerchantLearningEvent> stuck = repository.findStuckInProcessing(
                    now.minus(PROCESSING_TIMEOUT), PageRequest.of(0, RECOVERY_BATCH_SIZE));
            if (stuck.isEmpty()) return;
            log.warn("Returning {} merchant learning event(s) to the queue after {} in PROCESSING "
                    + "-- a worker most likely died mid-apply.", stuck.size(), PROCESSING_TIMEOUT);
            stuck.forEach(event -> event.recordFailure(
                    "Abandoned in PROCESSING for longer than " + PROCESSING_TIMEOUT, now));
            repository.saveAll(stuck);
        });
    }

    /** Class name plus message. The class name matters as much as the text: "constraint violation"
     *  and "merchant not found" need different responses from whoever reads the admin queue. */
    private static String describe(RuntimeException e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
