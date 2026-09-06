package com.finora.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/**
 * {@code AfterCommit.run}'s whole reason to exist is its "never throws" guarantee -- callers like
 * {@code NotificationService.request()} are documented as never throwing themselves precisely
 * because this method promises not to. Same transaction-simulation technique
 * {@code NotificationServiceTest} already uses ({@code TransactionSynchronizationManager}/
 * {@code TransactionSynchronizationUtils}) rather than a real Spring context or database.
 */
class AfterCommitTest {

    @AfterEach
    void clearAnySynchronization() {
        // Defensive: a test that fails partway through (or a future one) must never leak an active
        // synchronization into the next test's JVM-wide ThreadLocal state.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void run_executesWorkImmediately_whenThereIsNoAmbientTransaction() {
        AtomicBoolean ran = new AtomicBoolean(false);

        AfterCommit.run("test work", () -> ran.set(true));

        assertThat(ran).isTrue();
    }

    /**
     * Bug fix: this branch used to call {@code work.run()} with no try/catch, contradicting this
     * class's own "failures here cannot reach the caller" guarantee. The realistic trigger is a
     * {@code TaskRejectedException} from a shut-down executor -- a plain {@code RuntimeException}
     * here stands in for it, since the guarantee is about the exception type, not its source.
     */
    @Test
    void run_neverThrows_whenWorkThrowsAndThereIsNoAmbientTransaction() {
        Runnable failingWork = () -> {
            throw new RuntimeException("simulated TaskRejectedException");
        };

        assertThatCode(() -> AfterCommit.run("test work", failingWork)).doesNotThrowAnyException();
    }

    @Test
    void run_doesNotExecuteWork_beforeTheAmbientTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            AtomicBoolean ran = new AtomicBoolean(false);

            AfterCommit.run("test work", () -> ran.set(true));

            assertThat(ran).isFalse();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void run_executesWork_onceTheAmbientTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            AtomicBoolean ran = new AtomicBoolean(false);

            AfterCommit.run("test work", () -> ran.set(true));
            TransactionSynchronizationUtils.triggerAfterCommit();

            assertThat(ran).isTrue();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void run_doesNotExecuteWork_whenTheAmbientTransactionRollsBackInstead() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            AtomicBoolean ran = new AtomicBoolean(false);

            AfterCommit.run("test work", () -> ran.set(true));
            // No triggerAfterCommit() here -- simulating a rollback, on which afterCommit is never
            // invoked at all.

            assertThat(ran).isFalse();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** Regression coverage for the branch that already worked before this fix: a failure surfacing
     *  only once the transaction has actually committed must not propagate out of the
     *  {@code TransactionSynchronization} callback either. */
    @Test
    void run_neverThrows_whenWorkThrowsAfterTheAmbientTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            Runnable failingWork = () -> {
                throw new RuntimeException("simulated failure");
            };
            AfterCommit.run("test work", failingWork);

            assertThatCode(TransactionSynchronizationUtils::triggerAfterCommit)
                    .doesNotThrowAnyException();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
