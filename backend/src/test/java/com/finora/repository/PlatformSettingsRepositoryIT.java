package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * markSetupCompletedIfNotAlready() -- proves the atomic UPDATE ... WHERE setup_completed = false
 * this replaces SetupService's old check-then-write race with actually behaves atomically against
 * a real Postgres, not just in a single-threaded mock. An H2/mock-based test can't validate this
 * at all: the whole bug this closes only exists because two REAL overlapping transactions can both
 * pass a plain read-check before either commits -- see PlatformSettingsService.tryMarkSetupCompleted's
 * own doc comment.
 */
class PlatformSettingsRepositoryIT extends AbstractIntegrationTest {

    @Autowired private PlatformSettingsRepository platformSettingsRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    // The concurrency test below deliberately doesn't run inside @Transactional (each thread
    // needs its own real transaction to race the others meaningfully) so its write genuinely
    // commits to this suite's shared Testcontainers instance -- reset it after every test so a
    // later test class (should one ever come to depend on setup_completed's initial value) isn't
    // affected by test execution order.
    @AfterEach
    void resetSetupCompletedFlag() {
        platformSettingsRepository.findById((short) 1).ifPresent(settings -> {
            settings.setSetupCompleted(false);
            platformSettingsRepository.save(settings);
        });
    }

    @Test
    @Transactional
    void markSetupCompletedIfNotAlready_returnsOne_onlyOnce() {
        // The migration always inserts the singleton row with setup_completed = false, and no
        // other test in this suite flips it back -- if this ever proves flaky because another
        // test left it true, that test is the one that needs fixing, not this assertion relaxed.
        int firstAttempt = platformSettingsRepository.markSetupCompletedIfNotAlready();
        assertThat(firstAttempt).isEqualTo(1);

        int secondAttempt = platformSettingsRepository.markSetupCompletedIfNotAlready();
        assertThat(secondAttempt).isEqualTo(0);
    }

    @Test
    void markSetupCompletedIfNotAlready_underRealConcurrency_letsExactlyOneCallerWin() throws Exception {
        // The actual race SetupService.completeSetup() used to lose: many threads, each its own
        // real connection/transaction (not the same @Transactional test method, which would
        // serialize everything on one connection and prove nothing), all racing the same atomic
        // UPDATE at once.
        int threadCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    // Each thread needs its OWN transaction, which is exactly what the comment
                    // above describes but what this test never actually supplied. The repository
                    // method is @Modifying with no @Transactional of its own (its production
                    // callers, PlatformSettingsService.tryMarkSetupCompleted and
                    // SetupService.completeSetup, are both transactional), so calling it bare from
                    // a pool thread threw "Executing an update/delete query" before the race was
                    // ever run. A TransactionTemplate per call gives each thread a real,
                    // independent transaction on its own connection.
                    return new TransactionTemplate(transactionManager).execute(
                            status -> platformSettingsRepository.markSetupCompletedIfNotAlready());
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            AtomicInteger winners = new AtomicInteger();
            for (Future<Integer> f : futures) {
                if (f.get(10, TimeUnit.SECONDS) == 1) winners.incrementAndGet();
            }
            assertThat(winners.get()).as("exactly one concurrent caller should win the race").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
