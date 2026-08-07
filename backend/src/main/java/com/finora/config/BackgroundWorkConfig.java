package com.finora.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * The first background-execution infrastructure in this application.
 *
 * <p>Four separate classes previously recorded its absence as a deliberate constraint —
 * {@code RateLimiter}, {@code ImportSession}, {@code ImportSessionService} and
 * {@code ImportSessionRepository} all explain a design choice with "this codebase has no
 * background job infrastructure yet". It does now, and those comments are the places to revisit if
 * their opportunistic workarounds are ever worth replacing. <b>This does not license adding
 * scheduled jobs freely.</b> It exists for the merchant-learning queue, whose durability comes from
 * the database rather than from these threads.
 *
 * <p><b>The threads are not the queue.</b> That distinction is the whole design. Work is durable
 * because it is a row in {@code merchant_learning_events}; this executor only decides how promptly
 * somebody looks. A pool that is full, a thread that dies, or a deploy that restarts the JVM loses
 * nothing — the row is still PENDING and the poller collects it. An in-memory queue would have made
 * every one of those a data-loss event.
 */
@Configuration
@EnableScheduling
@EnableAsync
public class BackgroundWorkConfig {

    /**
     * A small, dedicated pool for learning-queue nudges.
     *
     * <p>Dedicated rather than Spring's shared default executor so a slow or wedged learning apply
     * cannot starve any other {@code @Async} work added later. Small because the work is
     * database-bound against a connection pool capped at 10 ({@code DB_POOL_MAX_SIZE}) — more
     * threads here would only queue harder on connections, and would compete with the request
     * threads actually serving users.
     *
     * <p>{@code CallerRunsPolicy} is deliberate and worth understanding: when the pool and its
     * queue are both full, the nudge runs on the calling thread instead of being discarded. The
     * caller is a request thread that has already committed the user's import, so the cost is a
     * slightly slower response, never a lost event. The alternative, {@code AbortPolicy}, would
     * throw into {@code afterCommit} — which the publisher catches, so the event would still be
     * safe, but it would be silently delayed to the next poll for no reason.
     */
    @Bean("learningQueueExecutor")
    public Executor learningQueueExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("learning-queue-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // Let an in-flight nudge finish on shutdown rather than being killed mid-apply. Bounded, so
        // a wedged apply cannot hold a deploy open indefinitely -- the row stays PENDING and the
        // next instance's poller picks it up.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    /**
     * Explicit transaction boundaries, for code that needs more than one of them in a single
     * method.
     *
     * <p>{@code MerchantLearningEventWorker} needs exactly that: claim, apply, and record-the-
     * outcome must be three separate transactions, because a constraint violation in the apply
     * poisons its transaction and the failure record would be rolled back along with it.
     * {@code @Transactional} cannot express that within one class — Spring does not proxy
     * self-invocation, so the annotations would be silently ignored and everything would run in
     * one transaction, which is the exact bug.
     */
    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
