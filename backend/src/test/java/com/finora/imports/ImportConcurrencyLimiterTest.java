package com.finora.imports;

import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * These test REAL thread coordination (actual concurrent execution, actual blocking/timeout),
 * not mocked behavior -- that's the whole point of what this class exists to guarantee, so a
 * mock-based test wouldn't actually prove the gating works.
 */
class ImportConcurrencyLimiterTest {

    @Test
    void runGated_executesWorkAndReturnsItsResult() throws Exception {
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(3, 5000);

        String result = limiter.runGated(() -> "done");

        assertThat(result).isEqualTo("done");
    }

    @Test
    void runGated_releasesThePermitAfterCompletion_soASecondCallCanProceed() throws Exception {
        // Only one permit available -- if the first call didn't release it properly, this second
        // (sequential, not concurrent) call would hang until the timeout and fail.
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(1, 2000);

        limiter.runGated(() -> "first");
        String second = limiter.runGated(() -> "second");

        assertThat(second).isEqualTo("second");
    }

    @Test
    void runGated_releasesThePermitEvenIfTheWorkThrows() throws Exception {
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(1, 2000);

        assertThatThrownBy(() -> limiter.runGated(() -> { throw new RuntimeException("boom"); }))
                .hasMessage("boom");

        // The permit must have been released in the finally block despite the exception above --
        // otherwise this would hang until the 2s timeout and fail.
        String result = limiter.runGated(() -> "still works");
        assertThat(result).isEqualTo("still works");
    }

    @Test
    void runGated_throwsImportSystemBusy_whenNoPermitFreesUpWithinTheTimeout() throws Exception {
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(1, 200); // short timeout for the test
        CountDownLatch permitHeld = new CountDownLatch(1); // signaled once the holder actually has the permit
        CountDownLatch releasePermit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Occupies the single permit and doesn't release it until the test says to.
            pool.submit(() -> {
                try {
                    limiter.runGated(() -> {
                        permitHeld.countDown();
                        releasePermit.await();
                        return null;
                    });
                } catch (Exception ignored) { /* not under test here */ }
            });
            assertThat(permitHeld.await(2, TimeUnit.SECONDS)).as("holder actually acquired the permit").isTrue();

            assertThatThrownBy(() -> limiter.runGated(() -> "should not run"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCode.IMPORT_SYSTEM_BUSY))
                    .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(503));

            releasePermit.countDown();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void runGated_boundsActualConcurrency_neverMoreThanMaxConcurrentAtOnce() throws Exception {
        int maxConcurrent = 3;
        int totalTasks = 12;
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(maxConcurrent, 10_000);
        AtomicInteger current = new AtomicInteger(0);
        AtomicInteger observedMax = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(totalTasks);

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < totalTasks; i++) {
                futures.add(pool.submit(() -> limiter.runGated(() -> {
                    int now = current.incrementAndGet();
                    observedMax.updateAndGet(prevMax -> Math.max(prevMax, now));
                    try {
                        Thread.sleep(50); // hold the permit briefly so overlaps are actually observable
                    } finally {
                        current.decrementAndGet();
                    }
                    return null;
                })));
            }
            for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);

            assertThat(observedMax.get()).isLessThanOrEqualTo(maxConcurrent);
        } finally {
            pool.shutdownNow();
        }
    }
}
