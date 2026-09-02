package com.finora.notification.worker;

import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.provider.ChannelSendResult;
import com.finora.notification.provider.NotificationChannelProvider;
import com.finora.notification.repository.NotificationRepository;
import com.finora.observability.WorkerExecution;
import com.finora.observability.WorkerObservability;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls the notification outbox and delivers each row through its channel provider.
 *
 * <p>Shaped directly after MerchantLearningEventWorker: fixedDelay polling (never fixedRate, so a
 * slow pass cannot overlap itself), a FOR UPDATE SKIP LOCKED claim, one transaction per item, and
 * a failure-recording transaction entered only after the send transaction has already rolled back
 * -- otherwise the failure write would be poisoned by the same rollback.
 *
 * <p>{@link #drainOnce()} and {@link #recoverAbandoned()} are public, synchronous, and deliberately
 * do not consult the enabled flag, so tests can drive them with the scheduler switched off.
 *
 * <p><b>Out of scope, deliberately:</b> the per-attempt delivery log ({@code NotificationLog} /
 * {@code NotificationLogRepository}) is Task 4's, together with its own migration. This class's
 * send path -- {@link #recordSuccess}, {@link #recordFailure}, {@link #failTerminally} -- is
 * written so Task 4 can add a log-row write inside each without restructuring anything here.
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private static final String WORKER = "notification-dispatcher";
    private static final String JOB_KIND = "notification";
    private static final int BATCH_SIZE = 50;
    private static final int RECOVERY_BATCH_SIZE = 50;
    private static final Duration PROCESSING_TIMEOUT = Duration.ofMinutes(15);

    private final NotificationRepository repository;
    private final Map<NotificationChannel, NotificationChannelProvider> providers =
            new EnumMap<>(NotificationChannel.class);
    private final WorkerObservability observability;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.notification.queue.enabled:true}")
    private boolean enabled;

    public NotificationDispatcher(NotificationRepository repository,
            List<NotificationChannelProvider> providerList, WorkerObservability observability,
            TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.observability = observability;
        this.transactionTemplate = transactionTemplate;
        for (NotificationChannelProvider provider : providerList) {
            this.providers.put(provider.channel(), provider);
        }
        observability.publishQueueDepth(WORKER, JOB_KIND,
                () -> repository.countByStatus(com.finora.notification.domain.NotificationStatus.QUEUED));
        observability.publishOldestPendingAge(WORKER, JOB_KIND, repository::findOldestPendingAt);
    }

    /**
     * The backstop. Recovers anything left stranded in PROCESSING before draining, matching
     * {@code MerchantLearningEventWorker.poll()}'s ordering: recovery runs first so a row a
     * crashed worker abandoned rejoins the queue in the same pass that goes on to drain it, rather
     * than waiting for a whole extra poll interval.
     */
    @Scheduled(fixedDelayString = "${app.notification.queue.poll-interval-ms:30000}",
            initialDelayString = "${app.notification.queue.initial-delay-ms:15000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        recoverAbandoned();
        drainOnce();
    }

    /** Fire-and-forget trigger so a request thread can get near-immediate delivery. */
    @Async("notificationQueueExecutor")
    public void nudge() {
        if (!enabled) {
            return;
        }
        try (WorkerExecution execution = observability.begin(WORKER, JOB_KIND)) {
            drain(execution);
        }
    }

    /** Synchronous single pass, for tests and admin-triggered drains. Ignores the enabled flag. */
    public int drainOnce() {
        try (WorkerExecution execution = observability.begin(WORKER, JOB_KIND)) {
            return drain(execution);
        }
    }

    private int drain(WorkerExecution execution) {
        List<Notification> claimed = claimBatch();
        execution.claimed(claimed.size());
        for (Notification notification : claimed) {
            deliverOne(execution, notification);
        }
        return claimed.size();
    }

    private List<Notification> claimBatch() {
        return transactionTemplate.execute(status -> {
            Instant now = Instant.now();
            List<Notification> due = repository.claimDue(now, BATCH_SIZE);
            for (Notification notification : due) {
                notification.markProcessing(now);
                repository.save(notification);
            }
            return due;
        });
    }

    private void deliverOne(WorkerExecution execution, Notification notification) {
        UUID id = notification.getId();
        execution.started(id, notification.getCreatedAt());
        NotificationChannelProvider provider = providers.get(notification.getChannel());

        if (provider == null || !provider.isConfigured()) {
            // Never going to succeed. Retrying five times would only delay the inevitable.
            failTerminally(execution, notification,
                    "no configured provider for channel " + notification.getChannel());
            return;
        }

        ChannelSendResult result;
        try {
            result = provider.send(notification);
        } catch (RuntimeException e) {
            // A provider is contractually not supposed to throw, but a bug in one must not take
            // the whole drain pass down with it.
            log.error("Provider for channel {} threw while sending notification {}",
                    notification.getChannel(), id, e);
            result = ChannelSendResult.failure(notification.getChannel().name(),
                    "provider threw: " + e.getClass().getSimpleName());
        }

        if (result.success()) {
            recordSuccess(execution, notification, result);
        } else {
            recordFailure(execution, notification, result);
        }
    }

    /**
     * The catch here mirrors {@code MerchantLearningEventWorker.recordFailure}'s own: recording an
     * outcome is itself a database write, and it can itself fail. Uncaught, that exception would
     * propagate out of {@link #deliverOne} and abort {@link #drain}'s loop over the rest of the
     * claimed batch -- one bad write stranding every other row in this pass at PROCESSING. Caught
     * and logged instead, the row simply stays PROCESSING and {@link #recoverAbandoned()} returns
     * it to the queue on a later pass; there is nobody to hand the exception to here (a scheduler
     * tick or an async nudge), so logging is the only option, same as the reference worker.
     */
    private void recordSuccess(WorkerExecution execution, Notification notification,
            ChannelSendResult result) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                notification.markSent(Instant.now());
                repository.save(notification);
                // Task 4 adds a NotificationLog row here, one per send attempt.
            });
            execution.completed(notification.getId());
        } catch (RuntimeException e) {
            log.error("Could not record delivery success for notification {}",
                    notification.getId(), e);
            execution.failureNotRecorded(notification.getId(), e);
        }
    }

    /** See {@link #recordSuccess}'s doc comment for why this has its own outer catch. */
    private void recordFailure(WorkerExecution execution, Notification notification,
            ChannelSendResult result) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                Notification.FailureOutcome outcome =
                        notification.recordFailure(result.detail(), Instant.now());
                repository.save(notification);
                // Task 4 adds a NotificationLog row here, one per send attempt.
                if (outcome == Notification.FailureOutcome.DEAD_LETTERED) {
                    execution.deadLettered(notification.getId(), notification.getAttemptCount(),
                            new IllegalStateException(result.detail()));
                } else if (outcome == Notification.FailureOutcome.RETRY_SCHEDULED) {
                    execution.retryScheduled(notification.getId(), notification.getAttemptCount());
                }
            });
        } catch (RuntimeException e) {
            log.error("Could not record delivery failure for notification {}",
                    notification.getId(), e);
            execution.failureNotRecorded(notification.getId(), e);
        }
    }

    /** See {@link #recordSuccess}'s doc comment for why this has its own outer catch. */
    private void failTerminally(WorkerExecution execution, Notification notification,
            String reason) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                for (int i = notification.getAttemptCount(); i < Notification.MAX_ATTEMPTS; i++) {
                    notification.recordFailure(reason, Instant.now());
                }
                repository.save(notification);
                // Task 4 adds a NotificationLog row here, one per send attempt.
            });
            execution.deadLettered(notification.getId(), notification.getAttemptCount(),
                    new IllegalStateException(reason));
        } catch (RuntimeException e) {
            log.error("Could not record dead-letter for notification {}", notification.getId(), e);
            execution.failureNotRecorded(notification.getId(), e);
        }
    }

    /**
     * Returns rows stuck in PROCESSING past the timeout to the queue without charging an attempt --
     * the worker that claimed them died, which is not the notification's fault.
     *
     * <p>Public and synchronous for the same reason {@link #drainOnce} is: {@link #poll} is gated
     * by the enabled flag, so a test that switches the scheduler off to stay deterministic cannot
     * reach recovery through it. Driving it directly is the only way to assert this behaviour
     * without a live scheduler.
     *
     * <p>Opens its own {@link WorkerExecution} (scheduler-prefixed, matching
     * {@code MerchantLearningEventWorker.recoverAbandoned()}) so a recovery this call makes is
     * both correlated in its own right and reported through {@link WorkerExecution#recovered(int)}
     * -- without that signal, a worker dying mid-batch is invisible to anyone watching metrics or
     * Sentry, not just delayed.
     */
    public int recoverAbandoned() {
        try (WorkerExecution execution = observability.beginScheduled(WORKER, JOB_KIND)) {
            Instant cutoff = Instant.now().minus(PROCESSING_TIMEOUT);
            List<Notification> abandoned =
                    repository.findAbandoned(cutoff, PageRequest.of(0, RECOVERY_BATCH_SIZE));
            if (abandoned.isEmpty()) {
                return 0;
            }
            transactionTemplate.executeWithoutResult(status -> {
                for (Notification notification : abandoned) {
                    notification.recoverFromAbandonment(Instant.now());
                    repository.save(notification);
                }
            });
            log.info("Recovered {} abandoned notifications", abandoned.size());
            execution.recovered(abandoned.size());
            return abandoned.size();
        }
    }
}
