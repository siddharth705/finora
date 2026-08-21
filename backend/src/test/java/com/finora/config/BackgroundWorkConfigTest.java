package com.finora.config;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-07 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Covers only
 * {@code authEmailExecutor}'s own doc-commented behaviour -- the MDC propagation its
 * {@code TaskDecorator} exists for -- not the whole class; {@code learningQueueExecutor}/
 * {@code importQueueExecutor} are already exercised indirectly through their real consumers'
 * {@code @Async} integration coverage, and adding a parallel direct test for those two isn't part
 * of this fix.
 */
class BackgroundWorkConfigTest {

    @Test
    void submittedWorkSeesTheCallingThreadsRequestId() throws InterruptedException {
        Executor executor = new BackgroundWorkConfig().authEmailExecutor();
        AtomicReference<String> seenOnWorkerThread = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        MDC.put("requestId", "req-abc-123");
        try {
            executor.execute(() -> {
                seenOnWorkerThread.set(MDC.get("requestId"));
                done.countDown();
            });
        } finally {
            MDC.remove("requestId");
        }

        assertThat(done.await(5, TimeUnit.SECONDS)).as("submitted work ran").isTrue();
        assertThat(seenOnWorkerThread.get()).isEqualTo("req-abc-123");
    }

    /**
     * Without this, a pooled thread would leak the FIRST task's requestId into the SECOND task
     * it ever runs if that second task's caller had none of its own set -- MDC is thread-local and
     * ThreadPoolTaskExecutor reuses threads, so "no context to copy" must still mean "cleared,"
     * not "whatever was there last time."
     */
    @Test
    void doesNotLeakAnEarlierTasksRequestIdIntoALaterOneWithNoContextOfItsOwn() throws InterruptedException {
        Executor executor = new BackgroundWorkConfig().authEmailExecutor();
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        AtomicReference<String> seenOnSecondTask = new AtomicReference<>();

        MDC.put("requestId", "req-first-task");
        try {
            executor.execute(firstDone::countDown);
        } finally {
            MDC.remove("requestId");
        }
        assertThat(firstDone.await(5, TimeUnit.SECONDS)).isTrue();

        // No MDC set on this thread for the second submission -- deliberately, to catch stale
        // reuse of whatever the pool's worker thread saw last.
        executor.execute(() -> {
            seenOnSecondTask.set(MDC.get("requestId"));
            secondDone.countDown();
        });

        assertThat(secondDone.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seenOnSecondTask.get()).isNull();
    }
}
