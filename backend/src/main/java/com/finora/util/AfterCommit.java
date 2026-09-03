package com.finora.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs work once the surrounding transaction is durable, or immediately when there is no
 * transaction to wait for.
 *
 * <h2>What this is for</h2>
 *
 * <p>Two different things go wrong when a side effect that reaches outside the database happens
 * <em>inside</em> a transaction, and both have bitten this codebase:
 *
 * <ul>
 *   <li><b>It holds a pooled connection across a network call.</b> The pool is capped at 10
 *       ({@code DB_POOL_MAX_SIZE}), so a third-party endpoint that hangs does not degrade one
 *       feature — it starves every endpoint of connections.</li>
 *   <li><b>It can act on a transaction that then rolls back.</b> An email saying "your password
 *       was changed", sent for a change that was subsequently undone, is worse than no email.</li>
 * </ul>
 *
 * <h2>Why immediate execution outside a transaction is the right fallback, not an error</h2>
 *
 * <p>{@code isSynchronizationActive()} is false in a unit test that calls a service directly with
 * no Spring transaction in play, and in any caller that legitimately has none. Refusing there would
 * make this unusable from exactly the tests that exercise these paths. Running immediately is what
 * every hand-rolled copy of this already does.
 *
 * <h2>Failures here cannot reach the caller</h2>
 *
 * <p>Applies to both branches, deliberately. In the deferred (post-commit) branch, by the time this
 * runs the transaction has committed and the response is on its way — there is nobody left to hand
 * an exception to, and throwing would only surface as an error in Spring's synchronization loop
 * that could skip other registered callbacks. In the immediate (no-ambient-transaction) branch, the
 * caller — {@code NotificationService.request()} among others — is typically documented as never
 * throwing itself, precisely BECAUSE this method promises not to; a bug fix once let a
 * {@code RuntimeException} escape this branch uncaught (a {@code TaskRejectedException} from a
 * shut-down executor was the realistic trigger) and broke exactly that promise for its caller. Both
 * branches log instead of throwing, for the same reason.
 *
 * <h2>The seven copies this does not yet replace</h2>
 *
 * <p>{@code TransactionService}, {@code ImportJobService}, {@code LayoutRegistryService},
 * {@code MerchantLearningEventPublisher}, {@code MerchantNormalizationEngine},
 * {@code AdminLearningQueueService} and {@code SetupService} each register this by hand. They are
 * correct as written, so converting them is a behaviour-preserving refactor rather than a fix, and
 * it is deliberately not bundled into a bug-fix change. It is worth doing: this codebase's own
 * comments keep pointing out that a rule with N copies is a rule that eventually has N behaviours,
 * and {@code AccountBalanceConvention} exists because that already happened once with the
 * credit-card sign convention.
 */
public final class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {}

    /**
     * @param description what the work is, for the log line if it fails. Short and specific --
     *                    "welcome email" rather than "task", since this is all an operator will
     *                    have to go on when it does not happen.
     */
    public static void run(String description, Runnable work) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // Bug fix: this used to call work.run() with no try/catch, contradicting this class's
            // own "failures here cannot reach the caller" guarantee -- a RuntimeException thrown
            // with no ambient transaction (e.g. TaskRejectedException from a shut-down executor)
            // propagated straight out of run() and, from there, out of whatever caller assumed this
            // could never throw (NotificationService.request()'s own class doc promises exactly
            // that). The afterCommit() branch below already caught this correctly; this branch is
            // the same fallback path -- immediate execution instead of a deferred callback -- and
            // needs the identical guarantee.
            try {
                work.run();
            } catch (RuntimeException e) {
                log.error("Post-commit work failed: {}. There was no ambient transaction to affect.",
                        description, e);
            }
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    work.run();
                } catch (RuntimeException e) {
                    log.error("Post-commit work failed: {}. The transaction it followed is "
                            + "committed and unaffected.", description, e);
                }
            }
        });
    }
}
