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
     * A separate pool for import-job nudges.
     *
     * <p>Separate from the learning pool rather than shared, because the two have opposite shapes.
     * A learning apply is a handful of small writes; an import parses a whole statement and can run
     * for seconds. Sharing one pool would let one large import block every learning nudge behind
     * it, which is the starvation the learning pool's own comment says it exists to avoid.
     *
     * <p>Still small, and for the same reason: the work is database-bound against a pool capped at
     * 10, so more threads here would only queue harder on connections while competing with the
     * request threads serving users. Concurrency comes from running more instances (design phase
     * 6), not from more threads in one.
     *
     * <p>{@code CallerRunsPolicy} matches the learning pool: when the pool and queue are both full
     * the nudge runs on the calling thread, so the cost is a slower response rather than a lost
     * trigger. Nothing is lost either way -- the job row is already committed and the poller is the
     * backstop -- but a discarded nudge would silently delay an import to the next poll.
     */
    @Bean("importQueueExecutor")
    public Executor importQueueExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("import-queue-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // Let an in-flight import finish on shutdown rather than being killed mid-parse. Bounded so
        // a wedged parse cannot hold a deploy open -- the job stays in flight and recoverAbandoned
        // returns it to the queue.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    /**
     * SEC-07 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Dedicated to
     * dispatching the actual outbound password-reset-email API call off the request thread.
     *
     * <p>Before this, {@code AuthService.forgotPassword()}'s existing-account branch registered
     * the send via {@link com.finora.util.AfterCommit#run}, whose callback runs SYNCHRONOUSLY on
     * the request thread once the transaction commits -- so an existing account paid for a real
     * network round trip to the email provider before the HTTP response went out, while the
     * non-existing-account branch returned after a single {@code SELECT}. Identical response
     * bodies either way, but the latency gap was a clean, remotely measurable account-enumeration
     * oracle. Submitting the send to this executor from inside that same after-commit callback
     * turns the callback itself back into cheap, near-instant work (an in-memory queue hand-off),
     * closing the gap the same way the non-existing branch was already fast: neither branch blocks
     * the response on a third-party network call.
     *
     * <p>{@code CallerRunsPolicy}, matching the learning/import pools above: under sustained
     * saturation (this codebase has no realistic path to that -- {@code forgotPassword} is already
     * rate-limited, {@code resetPasswordLimiter}) the send would run on the calling thread instead
     * of being dropped, which degrades the timing property gracefully rather than losing the email
     * -- the same trade this class already makes twice above.
     */
    @Bean("authEmailExecutor")
    public Executor authEmailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("auth-email-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        // The work submitted here calls AuditService.record(), which stamps every row with
        // MDC.get("requestId") for support/traceability -- correct on the request thread this
        // callback used to run on, but MDC is thread-local and doesn't cross an executor boundary
        // on its own. Without this decorator the audit row this executor exists to send would
        // silently lose its correlation to the request that triggered it, on every single call.
        executor.setTaskDecorator(runnable -> {
            var context = org.slf4j.MDC.getCopyOfContextMap();
            return () -> {
                if (context != null) org.slf4j.MDC.setContextMap(context);
                try {
                    runnable.run();
                } finally {
                    org.slf4j.MDC.clear();
                }
            };
        });
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
