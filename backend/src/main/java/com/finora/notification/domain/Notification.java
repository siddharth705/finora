package com.finora.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One outbox row: a notification that has been requested but not necessarily delivered.
 *
 * <p>Written inside the caller's own transaction by {@code NotificationService.request(...)}, the
 * same way {@code AuditService.record()} writes its row inside the caller's transaction today.
 * That is what makes this durable: a crash between "the thing happened" and "the user was told"
 * leaves a replayable row rather than a lost in-memory event.
 *
 * <p>No {@code @Version}: rows are claimed exclusively via FOR UPDATE SKIP LOCKED by a single
 * worker, so there is no concurrent-writer race to lose. This matches MerchantLearningEvent and
 * deliberately differs from ImportJob, which is raced by a user-initiated cancel.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    /** General delivery retries before a notification is dead-lettered. */
    public static final int MAX_ATTEMPTS = 5;

    /** What recordFailure decided, so the caller can emit the right observability signal. */
    public enum FailureOutcome {
        RETRY_SCHEDULED,
        DEAD_LETTERED,
        ALREADY_FINISHED
    }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "notification_key", nullable = false, unique = true, length = 200)
    private String notificationKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationStatus status = NotificationStatus.CREATED;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(nullable = false, length = 2000)
    private String message;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    /**
     * When the user opened this in the app. Client-reported, unrelated to provider delivery
     * confirmation -- it has no webhook dependency, which is why it survives while DELIVERED does
     * not. Nothing populates it in v1; it exists so a future in-app inbox needs no schema change.
     */
    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Notification() {
        // for JPA
    }

    private Notification(UUID userId, NotificationType type, NotificationCategory category,
            NotificationChannel channel, NotificationPriority priority, String notificationKey,
            String title, String message, Instant now) {
        this.userId = userId;
        this.type = type;
        this.category = category;
        this.channel = channel;
        this.priority = priority;
        this.notificationKey = notificationKey;
        this.title = title;
        this.message = message;
        this.createdAt = now;
        this.nextAttemptAt = now;
    }

    public static Notification create(UUID userId, NotificationType type,
            NotificationCategory category, NotificationChannel channel,
            NotificationPriority priority, String notificationKey, String title, String message,
            Instant now) {
        return new Notification(userId, type, category, channel, priority, notificationKey, title,
                message, now);
    }

    public void markQueued(Instant now) {
        this.status = NotificationStatus.QUEUED;
        this.nextAttemptAt = now;
    }

    public void markProcessing(Instant now) {
        this.status = NotificationStatus.PROCESSING;
        this.nextAttemptAt = now;
    }

    public void markSent(Instant now) {
        this.status = NotificationStatus.SENT;
        this.sentAt = now;
        this.lastError = null;
    }

    /**
     * Records a delivery failure and decides whether to retry.
     *
     * <p>Backoff is 2^attemptCount minutes, the same exponential shape MerchantLearningEvent
     * already uses -- not an immediate infinite retry loop.
     */
    public FailureOutcome recordFailure(String error, Instant now) {
        if (status.isTerminal()) {
            return FailureOutcome.ALREADY_FINISHED;
        }
        this.attemptCount++;
        this.lastError = truncate(error);
        if (attemptCount >= MAX_ATTEMPTS) {
            this.status = NotificationStatus.DEAD_LETTER;
            this.nextAttemptAt = now;
            return FailureOutcome.DEAD_LETTERED;
        }
        this.status = NotificationStatus.RETRYING;
        this.nextAttemptAt = now.plusSeconds(60L * (1L << attemptCount));
        return FailureOutcome.RETRY_SCHEDULED;
    }

    /** Returns a PROCESSING row that outlived its timeout to the queue without charging an attempt. */
    public void recoverFromAbandonment(Instant now) {
        if (status != NotificationStatus.PROCESSING) {
            return;
        }
        this.status = NotificationStatus.QUEUED;
        this.nextAttemptAt = now;
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 2000 ? error : error.substring(0, 2000);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getNotificationKey() {
        return notificationKey;
    }

    public NotificationType getType() {
        return type;
    }

    public NotificationCategory getCategory() {
        return category;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public NotificationPriority getPriority() {
        return priority;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
