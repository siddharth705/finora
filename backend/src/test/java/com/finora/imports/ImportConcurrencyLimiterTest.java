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
 * These test REAL thread coordination (actual concurrent execution, actual permit contention),
 * not mocked behavior -- that's the whole point of what this class exists to guarantee, so a
 * mock-based test wouldn't actually prove the gating works.
 *
 * BH-043: ImportConcurrencyLimiter used to block the calling thread (up to a configured timeout)
 * waiting for a permit to free up. It now does an instant, non-blocking check instead -- see the
 * class's own doc comment. That changed what's testable here: there is no more "wait, then
 * eventually succeed/time out" behavior to exercise, only "permit available right now, or not."
 * Tests below that used to rely on the old blocking wait have been rewritten accordingly rather
 * than dropped, so the coverage they existed for (permit exhaustion returns IMPORT_SYSTEM_BUSY;
 * the limiter never lets more than maxConcurrent run at once) is preserved under the new
 * semantics.
 */
class ImportConcurrencyLimiterTest {

    @Test
    void runGated_executesWorkAndReturnsItsResult() throws Exception {
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(3);

        String result = limiter.runGated(() -> "done");

        assertThat(result).isEqualTo("done");
    }

    @Test
    void runGated_releasesThePermitAfterCompletion_soASecondCallCanProceed() throws Exception {
        // Only one permit available -- if the first call didn't release it properly, this second
        // (sequential, not concurrent) call would find no permit available and fail immediately
        // with IMPORT_SYSTEM_BUSY instead of succeeding.
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(1);

        limiter.runGated(() -> "first");
        String second = limiter.runGated(() -> "second");

        assertThat(second).isEqualTo("second");
    }

    @Test
    void runGated_releasesThePermitEvenIfTheWorkThrows() throws Exception {
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(1);

        assertThatThrownBy(() -> limiter.runGated(() -> { throw new RuntimeException("boom"); }))
                .hasMessage("boom");

        // The permit must have been released in the finally block despite the exception above --
        // otherwise this would immediately fail with IMPORT_SYSTEM_BUSY (no permit available),
        // since the limiter no longer waits for one to free up.
        String result = limiter.runGated(() -> "still works");
        assertThat(result).isEqualTo("still works");
    }

    @Test
    void runGated_throwsImportSystemBusy_immediately_whenNoPermitIsAvailable() throws Exception {
        // BH-043: replaces the old runGated_throwsImportSystemBusy_whenNoPermitFreesUpWithinTheTimeout,
        // which held the single permit, let the calling thread block for the configured timeout,
        // and asserted the eventual timeout threw IMPORT_SYSTEM_BUSY. That behavior no longer
        // exists -- runGated() now rejects the instant no permit is free, so this asserts the
        // rejection is actually instant (not merely that it eventually happens): if runGated() were
        // still blocking under the hood, this would take as long as whatever wait was configured;
        // since it doesn't block at all, it must return in a handful of milliseconds.
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(1);
        CountDownLatch permitHeld = new CountDownLatch(1); // signaled once the holder actually has the permit
        CountDownLatch releasePermit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            // Occupies the single permit and doesn't release it until the test says to, so the
            // rejection below observes a genuinely exhausted limiter, not a race against a fast
            // release.
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

            long startNanos = System.nanoTime();
            assertThatThrownBy(() -> limiter.runGated(() -> "should not run"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCode.IMPORT_SYSTEM_BUSY))
                    .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(503));
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

            // 2000ms, not a tighter bound: this is a real wall-clock measurement, and a GC pause or
            // a loaded CI runner could occasionally push actual (non-blocking) elapsed time past a
            // very tight threshold with no blocking having occurred at all. Still two orders of
            // magnitude below the old 20000ms timeout this replaces -- comfortably proves "did not
            // wait for the old timeout" without the assertion itself becoming the flaky part.
            assertThat(elapsedMs)
                    .as("rejection must be instant -- BH-043's entire point is that runGated() never "
                            + "parks the calling (Tomcat request) thread waiting for a permit")
                    .isLessThan(2000);

            releasePermit.countDown();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void runGated_boundsActualConcurrency_neverMoreThanMaxConcurrentAtOnce() throws Exception {
        // BH-043: previously fired totalTasks (> maxConcurrent) at once and relied on the old
        // blocking wait to queue the excess behind the first maxConcurrent, letting all of them
        // eventually run and observing that overlap never exceeded maxConcurrent. That queueing no
        // longer happens, so this now uses latches for a deterministic assertion instead: exactly
        // maxConcurrent callers, released together, must all be granted a permit and observed
        // running concurrently -- proving the limiter grants up to (not fewer than) the configured
        // number of permits at once.
        int maxConcurrent = 3;
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(maxConcurrent);
        AtomicInteger current = new AtomicInteger(0);
        AtomicInteger observedMax = new AtomicInteger(0);
        CountDownLatch allRunning = new CountDownLatch(maxConcurrent);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(maxConcurrent);

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < maxConcurrent; i++) {
                futures.add(pool.submit(() -> limiter.runGated(() -> {
                    int now = current.incrementAndGet();
                    observedMax.updateAndGet(prevMax -> Math.max(prevMax, now));
                    allRunning.countDown();
                    release.await();
                    current.decrementAndGet();
                    return null;
                })));
            }

            assertThat(allRunning.await(2, TimeUnit.SECONDS))
                    .as("all maxConcurrent callers were granted a permit and are running concurrently")
                    .isTrue();
            assertThat(observedMax.get()).isEqualTo(maxConcurrent);

            release.countDown();
            for (Future<?> f : futures) f.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * BH-043 review: the test above proves the limiter grants exactly maxConcurrent permits when
     * asked for exactly maxConcurrent -- it never races more callers than there are permits, so it
     * couldn't catch a future regression that admitted MORE than maxConcurrent under real
     * contention (e.g. a hand-rolled counter replacing Semaphore with a check-then-increment race).
     * This fires 3x as many callers as permits, all at once, and proves the held-concurrently count
     * never exceeds maxConcurrent even under genuine oversubscription -- Semaphore's own atomicity
     * is what this pins down, not just this class's plumbing around it.
     */
    @Test
    void runGated_neverExceedsMaxConcurrent_evenWhenGenuinelyOversubscribed() throws Exception {
        int maxConcurrent = 3;
        int attempts = maxConcurrent * 3;
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(maxConcurrent);
        AtomicInteger current = new AtomicInteger(0);
        AtomicInteger observedMax = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);
        CountDownLatch maxConcurrentReached = new CountDownLatch(maxConcurrent);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(attempts);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);

        try {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    try {
                        limiter.runGated(() -> {
                            int now = current.incrementAndGet();
                            observedMax.updateAndGet(prevMax -> Math.max(prevMax, now));
                            maxConcurrentReached.countDown();
                            release.await();
                            current.decrementAndGet();
                            return null;
                        });
                    } catch (ApiException e) {
                        rejected.incrementAndGet();
                    } catch (Exception ignored) {
                        // not under test here
                    } finally {
                        allDone.countDown();
                    }
                });
            }

            assertThat(maxConcurrentReached.await(2, TimeUnit.SECONDS))
                    .as("maxConcurrent callers actually got a permit and are running concurrently")
                    .isTrue();
            release.countDown();
            assertThat(allDone.await(5, TimeUnit.SECONDS)).as("every attempt resolved").isTrue();

            assertThat(observedMax.get())
                    .as("never more than maxConcurrent held a permit at once, even racing %d callers for %d permits",
                            attempts, maxConcurrent)
                    .isEqualTo(maxConcurrent);
            assertThat(rejected.get()).isEqualTo(attempts - maxConcurrent);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void runGated_rejectsExcessRequestsInstantly_ratherThanQueueingThemBehindTheLimit() throws Exception {
        // BH-043's core behavior change, proven at a limit greater than 1 (the timeout-replacement
        // test above already covers maxConcurrent=1): once every permit is taken, further callers
        // must be rejected immediately, not queued to run once a holder releases.
        int maxConcurrent = 3;
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(maxConcurrent);
        CountDownLatch allHoldersReady = new CountDownLatch(maxConcurrent);
        CountDownLatch releaseHolders = new CountDownLatch(1);
        ExecutorService holderPool = Executors.newFixedThreadPool(maxConcurrent);

        try {
            for (int i = 0; i < maxConcurrent; i++) {
                holderPool.submit(() -> limiter.runGated(() -> {
                    allHoldersReady.countDown();
                    releaseHolders.await();
                    return null;
                }));
            }
            assertThat(allHoldersReady.await(2, TimeUnit.SECONDS)).as("every permit is taken").isTrue();

            for (int i = 0; i < 4; i++) {
                assertThatThrownBy(() -> limiter.runGated(() -> "should not run"))
                        .isInstanceOf(ApiException.class)
                        .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCode.IMPORT_SYSTEM_BUSY));
            }

            releaseHolders.countDown();
        } finally {
            holderPool.shutdownNow();
        }
    }
}
