package com.finora.service;

import com.finora.entity.MerchantLearningEvent;
import com.finora.repository.MerchantLearningEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Hands a merchant-learning confirmation to the queue instead of applying it inline.
 *
 * <p>Deliverable 0 of the Import Reliability Milestone. Two properties matter and they pull in
 * opposite directions, which is why this class exists rather than a bare repository call:
 *
 * <ol>
 *   <li><b>The row is written in the CALLER's transaction.</b> If the import rolls back, the queued
 *       learning must roll back with it — otherwise a worker later applies a confirmation for
 *       transactions that do not exist. So {@code enqueue} deliberately does not open a transaction
 *       of its own.</li>
 *   <li><b>The worker runs only AFTER that transaction commits.</b> Processing inside it would put
 *       learning back on the import's critical path, and a constraint violation would once again
 *       roll the statement back — which is Bug 02 by a different route.</li>
 * </ol>
 *
 * <p>{@code afterCommit} is what satisfies both at once. The insert is transactional; only the
 * <em>nudge</em> is deferred. This is the same shape {@code SetupService.completeSetup} uses to
 * defer deleting the installation key file until the transaction that authorised the deletion has
 * actually committed.
 *
 * <p>The nudge is an optimisation, never a guarantee. If the process dies between commit and
 * notify, the row is still PENDING and {@code MerchantLearningEventWorker}'s poller collects it on
 * the next pass. That is the whole reason the poller exists even though the nudge normally wins by
 * several orders of magnitude.
 */
@Component
public class MerchantLearningEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(MerchantLearningEventPublisher.class);

    private final MerchantLearningEventRepository repository;
    private final MerchantLearningEventWorker worker;

    public MerchantLearningEventPublisher(MerchantLearningEventRepository repository,
                                           MerchantLearningEventWorker worker) {
        this.repository = repository;
        this.worker = worker;
    }

    /**
     * Queues a confirmation, to be applied once the calling transaction commits.
     *
     * <p>Joins whatever transaction the caller is in — that is the point, not an oversight. Callers
     * outside a transaction still work: the save commits on its own and the nudge fires
     * immediately, since there is no commit to wait for.
     *
     * @param sourceStatementImportId the confirmed import this came from, and what the admin queue
     *                                links back to. Present for every import; null when the
     *                                confirmation was not earned by one — a bulk recategorization
     *                                (WI1A) is a real learning event with no statement behind it
     * @param sourceImportSessionId    the staging/review session, when there was one. Null for the
     *                                 direct-file confirm path and for every non-import caller —
     *                                 pass null rather than inventing an id, or an operator
     *                                 following the link lands on a session that never existed
     */
    public void enqueue(UUID userId, UUID merchantId, UUID categoryId,
                         UUID sourceStatementImportId, UUID sourceImportSessionId) {
        MerchantLearningEvent event = repository.save(MerchantLearningEvent.pending(
                userId, merchantId, categoryId, sourceStatementImportId, sourceImportSessionId));
        nudgeAfterCommit(event.getId());
    }

    private void nudgeAfterCommit(UUID eventId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // No transaction to wait for. The save above is already durable, so drain now.
            worker.nudge();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // Never let a failure to NUDGE fail anything: the import has committed, the row is
                // durable, and the poller is the backstop. Throwing here would surface an error to
                // a user whose import genuinely succeeded.
                try {
                    worker.nudge();
                } catch (RuntimeException e) {
                    log.warn("Could not nudge the learning worker for event {}; the poller will "
                            + "pick it up.", eventId, e);
                }
            }
        });
    }
}
