# Phase A: Notification Platform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `com.finora.notification` module — a provider-agnostic, transactionally durable, template-driven notification layer across email, SMS, and mobile push — so that callers stop talking to providers directly.

**Architecture:** A transactional outbox. `NotificationService.request(...)` writes a `notifications` row inside the *caller's own* DB transaction (the same way `AuditService.record()` already does). A separate `NotificationDispatcher` worker — shaped exactly like the existing `MerchantLearningEventWorker` (poll → `FOR UPDATE SKIP LOCKED` claim → send → `retryScheduled`/`deadLettered`) — picks up undispatched rows and delivers them through channel providers that wrap the existing `EmailProvider`/`SmsProvider`, plus a genuinely new push channel (FCM/APNs). Modular monolith: one Spring Boot deployable, one database, no message broker.

**Tech Stack:** Java 25, Spring Boot 3.5.16, Jakarta Persistence (no Lombok), PostgreSQL + Flyway, JUnit 5 + AssertJ + Mockito (plain `mock(Class.class)`, not annotations), Testcontainers for integration tests.

**Spec:**
- `docs/superpowers/specs/2026-09-02-import-failure-triage-and-notification-platform-design.md` (§3 Phase A)
- `docs/proposals/notification-communication-platform-proposal.md` — **the authoritative design.** This plan executes §2–§5 of that document. It is frozen; do not redesign it.

---

## Global Constraints

Copied verbatim from the source documents. Every task's requirements implicitly include this section.

- **This is execution of a frozen design, not a redesign.** Per the proposal §7: "Implement against the architecture already locked in §2–§5 — this is a proposal to execute, not a proposal to redesign. Revisit the architecture only if new codebase evidence contradicts something stated here (e.g. a referenced class was renamed or removed); a preference for a different pattern discovered mid-implementation is not sufficient reason on its own."
- **Gate override, recorded deliberately.** The proposal's §7 safety gate has one unchecked item: "Sentry + production monitoring ready". Implementation is proceeding anyway — this was surfaced explicitly and decided by the project owner in the 2026-09-02 brainstorming session. It is a known, accepted risk, not an oversight. Re-confirm with the owner before starting if significant time has passed.
- **Locked decisions (proposal §7), not open for reconsideration:**
  - Modular-monolith module `com.finora.notification` — not a separate service, no message broker (no Kafka, no RabbitMQ).
  - Transactional outbox + `NotificationDispatcher` worker — **not** an in-memory event bus. This codebase has zero `ApplicationEventPublisher`/`@EventListener` usage; do not introduce it.
  - Existing `EmailProvider`/`SmsProvider` stay as-is and are *wrapped*, not replaced. Push (FCM/APNs) is the only new channel.
  - `device_tokens.encrypted_token` is **encrypted, not hashed** — the dispatcher must hand the real token to FCM/APNs on every send.
  - Lifecycle states capped at `CREATED / QUEUED / PROCESSING / SENT / FAILED / RETRYING / DEAD_LETTER`. **No `DELIVERED` or `READ`** — no provider webhook exists to populate them truthfully.
  - Deferred stays deferred: delivery/read tracking, localization/i18n, marketing notifications, service extraction, advanced analytics, in-app inbox UI.
- **`notification_templates` ships English-only.** No `language` column, no i18n. It can be added later without breaking the schema.
- **Flyway migration versions must never be hardcoded from this plan.** Multiple Claude Code sessions work this repo concurrently and duplicate version numbers have broken `main` three times. Immediately before writing *each* migration, run:
  ```bash
  git fetch origin && ls backend/src/main/resources/db/migration | sort -V | tail -5
  ```
  and use the next free number. `V122` was the latest observed at plan-writing time — that is a snapshot, **not a reservation**.
- **Codebase conventions to follow exactly** (verified in this repo, not assumed):
  - `jakarta.persistence.*`, never `javax`. **No Lombok anywhere** — hand-write getters, a `protected` no-arg constructor for JPA, and a static factory for real construction.
  - Enum columns: `@Enumerated(EnumType.STRING)` in Java + `VARCHAR(n) NOT NULL` in SQL. Never a native Postgres enum — these lists are expected to grow.
  - Queue-shaped entities use `@GeneratedValue` UUID ids and **no** `@Version` (rows are claimed exclusively via `FOR UPDATE SKIP LOCKED`, not raced via optimistic locking).
  - Index naming `idx_<table>_<purpose>`; prefer partial indexes scoped to the claimable subset.
  - Use `COMMENT ON TABLE` / `COMMENT ON COLUMN` liberally for anything non-obvious.
  - Provider bean selection is a runtime credential check in a `@Bean` method (see `EmailConfig`/`SmsConfig`), **never** `@Profile`.
  - A notification send must **never** fail the caller's request — providers swallow failures into a result object rather than throwing.
  - Never log a raw secret, token, phone number, or email — mask/redact by default, including in no-op fallbacks.
  - Tests: `mock(Class.class)` assigned in `@BeforeEach` (no `@Mock`/`@InjectMocks`, no `@ExtendWith(MockitoExtension.class)`), AssertJ assertions, `ReflectionTestUtils.setField` for no-setter fields. Integration tests extend `AbstractIntegrationTest` (Testcontainers) and must carry `@Isolated`.
- **Build/test commands** (run from repo root):
  ```bash
  cd backend && ./mvnw test -Dtest=ClassName
  ```

---

## File Structure

New module, mirroring the proposal's §2.1 package layout:

```
backend/src/main/java/com/finora/notification/
├── api/         NotificationService, NotificationRequest
├── domain/      Notification, NotificationLog, DeviceToken, NotificationTemplate,
│                NotificationPreference, NotificationType, NotificationCategory,
│                NotificationChannel, NotificationPriority, NotificationStatus
├── provider/    NotificationChannelProvider (iface), EmailNotificationProvider,
│                SmsNotificationProvider, FcmPushProvider, ApnsPushProvider,
│                NoOpPushProvider, PushSendResult
├── template/    TemplateRenderer, RenderedMessage
├── worker/      NotificationDispatcher
└── repository/  NotificationRepository, NotificationLogRepository,
                 DeviceTokenRepository, NotificationTemplateRepository,
                 NotificationPreferenceRepository
```

Plus: `com/finora/controller/DeviceTokenController.java`, `com/finora/controller/AdminNotificationController.java`, `com/finora/service/AdminNotificationService.java`, `com/finora/config/PushConfig.java`, and additions to `com/finora/config/BackgroundWorkConfig.java`.

Each file has one responsibility. The `provider/` package is the only place that knows how to talk to an external system; `worker/` is the only place that decides *when*; `api/` is the only surface callers touch.

---

## Task 1: Notification domain enums, entity, migration, and repository

**Files:**
- Create: `backend/src/main/java/com/finora/notification/domain/NotificationChannel.java`
- Create: `backend/src/main/java/com/finora/notification/domain/NotificationCategory.java`
- Create: `backend/src/main/java/com/finora/notification/domain/NotificationPriority.java`
- Create: `backend/src/main/java/com/finora/notification/domain/NotificationStatus.java`
- Create: `backend/src/main/java/com/finora/notification/domain/NotificationType.java`
- Create: `backend/src/main/java/com/finora/notification/domain/Notification.java`
- Create: `backend/src/main/resources/db/migration/V<next>__notifications.sql`
- Create: `backend/src/main/java/com/finora/notification/repository/NotificationRepository.java`
- Test: `backend/src/test/java/com/finora/notification/domain/NotificationTest.java`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: `Notification` entity with `static Notification create(UUID userId, NotificationType type, NotificationCategory category, NotificationChannel channel, NotificationPriority priority, String notificationKey, String title, String message, Instant now)`; instance methods `markQueued(Instant)`, `markProcessing(Instant)`, `markSent(Instant)`, `FailureOutcome recordFailure(String error, Instant now)`; enum `Notification.FailureOutcome { RETRY_SCHEDULED, DEAD_LETTERED, ALREADY_FINISHED }`; constant `Notification.MAX_ATTEMPTS = 5`. `NotificationRepository.claimDue(Instant now, int batchSize)` returns `List<Notification>`. `NotificationRepository.existsByNotificationKey(String)` returns `boolean`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/finora/notification/domain/NotificationTest.java`:

```java
package com.finora.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Notification state machine. Mockito-free -- this is a pure entity test,
 * matching the style of other entity state-machine tests in this codebase.
 */
class NotificationTest {

    private Notification newNotification() {
        return Notification.create(
                UUID.randomUUID(),
                NotificationType.IMPORT_STATEMENT_READY,
                NotificationCategory.FINANCIAL,
                NotificationChannel.EMAIL,
                NotificationPriority.NORMAL,
                "IMPORT_READY_test-key",
                "Your statement is ready",
                "We finished importing your statement.",
                Instant.parse("2026-09-02T10:00:00Z"));
    }

    @Test
    void create_startsInCreatedStatusWithNoAttempts() {
        Notification n = newNotification();

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.CREATED);
        assertThat(n.getAttemptCount()).isZero();
        assertThat(n.getSentAt()).isNull();
    }

    @Test
    void markSent_recordsTimestampAndTerminalStatus() {
        Notification n = newNotification();
        Instant sentAt = Instant.parse("2026-09-02T10:05:00Z");

        n.markQueued(Instant.parse("2026-09-02T10:01:00Z"));
        n.markProcessing(Instant.parse("2026-09-02T10:04:00Z"));
        n.markSent(sentAt);

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(n.getSentAt()).isEqualTo(sentAt);
    }

    @Test
    void recordFailure_schedulesRetryWithBackoffUntilMaxAttempts() {
        Notification n = newNotification();
        Instant now = Instant.parse("2026-09-02T10:00:00Z");

        Notification.FailureOutcome outcome = n.recordFailure("provider timeout", now);

        assertThat(outcome).isEqualTo(Notification.FailureOutcome.RETRY_SCHEDULED);
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(n.getAttemptCount()).isEqualTo(1);
        // 2^1 minutes of backoff -- same exponential shape as MerchantLearningEvent.
        assertThat(n.getNextAttemptAt()).isEqualTo(now.plusSeconds(120));
    }

    @Test
    void recordFailure_deadLettersOnceAttemptsAreExhausted() {
        Notification n = newNotification();
        Instant now = Instant.parse("2026-09-02T10:00:00Z");

        Notification.FailureOutcome outcome = null;
        for (int i = 0; i < Notification.MAX_ATTEMPTS; i++) {
            outcome = n.recordFailure("provider timeout", now);
        }

        assertThat(outcome).isEqualTo(Notification.FailureOutcome.DEAD_LETTERED);
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        assertThat(n.getAttemptCount()).isEqualTo(Notification.MAX_ATTEMPTS);
    }

    @Test
    void recordFailure_onAlreadySentNotificationIsIgnored() {
        Notification n = newNotification();
        n.markQueued(Instant.parse("2026-09-02T10:01:00Z"));
        n.markProcessing(Instant.parse("2026-09-02T10:02:00Z"));
        n.markSent(Instant.parse("2026-09-02T10:03:00Z"));

        Notification.FailureOutcome outcome =
                n.recordFailure("late failure", Instant.parse("2026-09-02T10:04:00Z"));

        assertThat(outcome).isEqualTo(Notification.FailureOutcome.ALREADY_FINISHED);
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=NotificationTest
```

Expected: FAIL — compilation error, `NotificationType`/`Notification` etc. do not exist.

- [ ] **Step 3: Write the enums**

`backend/src/main/java/com/finora/notification/domain/NotificationChannel.java`:

```java
package com.finora.notification.domain;

/** Delivery channels. PUSH is the only genuinely new one; EMAIL and SMS wrap existing providers. */
public enum NotificationChannel {
    EMAIL,
    SMS,
    PUSH
}
```

`backend/src/main/java/com/finora/notification/domain/NotificationCategory.java`:

```java
package com.finora.notification.domain;

/**
 * Preference grouping. SECURITY notifications default on and are the ones a user should not be
 * able to silence on their only verified channel; MARKETING is a placeholder with no send logic
 * in v1 (proposal section 4, explicitly out of scope).
 */
public enum NotificationCategory {
    SECURITY,
    FINANCIAL,
    MARKETING
}
```

`backend/src/main/java/com/finora/notification/domain/NotificationPriority.java`:

```java
package com.finora.notification.domain;

/**
 * Urgency, used to inform channel selection. The field exists from the start specifically so a
 * security alert cannot end up buried among low-priority noise later (proposal section 2.1).
 */
public enum NotificationPriority {
    CRITICAL,
    HIGH,
    NORMAL,
    LOW
}
```

`backend/src/main/java/com/finora/notification/domain/NotificationStatus.java`:

```java
package com.finora.notification.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle, deliberately capped at what this system can observe truthfully.
 *
 * <p>DELIVERED and READ are absent on purpose: neither Resend nor 2Factor has a delivery webhook
 * wired up in this codebase, so those states could never be populated honestly and would sit
 * permanently stale. They return only once provider webhooks exist (proposal section 2.5).
 *
 * <p>SENT means the provider's synchronous API call returned success. That is the only
 * confirmation any provider gives us today.
 */
public enum NotificationStatus {
    CREATED,
    QUEUED,
    PROCESSING,
    SENT,
    FAILED,
    RETRYING,
    DEAD_LETTER;

    /** No further dispatch attempt will be made for a notification in one of these states. */
    public static final Set<NotificationStatus> TERMINAL =
            EnumSet.of(SENT, DEAD_LETTER);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
```

`backend/src/main/java/com/finora/notification/domain/NotificationType.java`:

```java
package com.finora.notification.domain;

/**
 * Semantic trigger names. A caller names the event, never a title/body string -- copy lives in
 * notification_templates so it is reviewable in one place and reusable across channels.
 *
 * <p>Add a value here together with its notification_templates rows; a type with no template row
 * cannot render and will dead-letter.
 */
public enum NotificationType {
    PASSWORD_CHANGED,
    IMPORT_STATEMENT_READY
}
```

- [ ] **Step 4: Write the Notification entity**

`backend/src/main/java/com/finora/notification/domain/Notification.java`:

```java
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
```

- [ ] **Step 5: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=NotificationTest
```

Expected: PASS (5 tests).

- [ ] **Step 6: Pick the migration version, then write the migration**

First — **do not skip this**, concurrent sessions have collided three times:

```bash
git fetch origin && ls backend/src/main/resources/db/migration | sort -V | tail -5
```

Use the next free version. Create `backend/src/main/resources/db/migration/V<next>__notifications.sql`:

```sql
-- The notification outbox. NotificationService.request() writes a row here inside the caller's
-- own transaction; NotificationDispatcher claims and delivers it. Durable by construction: a
-- crash between "the event happened" and "the user was told" leaves a replayable row.

CREATE TABLE notifications (
    id                UUID PRIMARY KEY,
    user_id           UUID NOT NULL REFERENCES users(id),
    notification_key  VARCHAR(200) NOT NULL UNIQUE,
    type              VARCHAR(64) NOT NULL,
    category          VARCHAR(32) NOT NULL,
    channel           VARCHAR(16) NOT NULL,
    priority          VARCHAR(16) NOT NULL,
    status            VARCHAR(16) NOT NULL DEFAULT 'CREATED',
    title             VARCHAR(300) NOT NULL,
    message           VARCHAR(2000) NOT NULL,
    attempt_count     INTEGER NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ NOT NULL,
    last_error        VARCHAR(2000),
    sent_at           TIMESTAMPTZ,
    read_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL
);

-- Partial index scoped to the claimable subset, so it stays proportional to the live backlog
-- rather than to every notification ever sent.
CREATE INDEX idx_notifications_claimable
    ON notifications (next_attempt_at)
    WHERE status IN ('CREATED', 'QUEUED', 'RETRYING');

CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at DESC);

COMMENT ON TABLE notifications IS
    'Transactional outbox for user notifications. Written in the caller''s transaction, delivered '
    'asynchronously by NotificationDispatcher.';
COMMENT ON COLUMN notifications.notification_key IS
    'Caller-supplied deterministic idempotency key, e.g. IMPORT_READY_{jobId}. UNIQUE so a backend '
    'retry or a redelivered job cannot produce a duplicate send -- for financial events a duplicate '
    'is a trust problem, not just noise.';
COMMENT ON COLUMN notifications.status IS
    'CREATED/QUEUED/PROCESSING/SENT/FAILED/RETRYING/DEAD_LETTER. Deliberately no DELIVERED or READ: '
    'no provider webhook exists to populate them truthfully (see the notification platform proposal '
    'section 2.5). Plain VARCHAR, not a native enum, matching every other status column here.';
COMMENT ON COLUMN notifications.read_at IS
    'Client-reported in-app open time for a future inbox. Unrelated to provider delivery '
    'confirmation; nothing populates it in v1.';
```

- [ ] **Step 7: Write the repository**

`backend/src/main/java/com/finora/notification/repository/NotificationRepository.java`:

```java
package com.finora.notification.repository;

import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    boolean existsByNotificationKey(String notificationKey);

    Optional<Notification> findByNotificationKey(String notificationKey);

    Page<Notification> findByStatus(NotificationStatus status, Pageable pageable);

    long countByStatus(NotificationStatus status);

    /**
     * Claims a batch of due notifications for this worker only.
     *
     * <p>Native because JPQL has no portable FOR UPDATE SKIP LOCKED. This app is Postgres-only by
     * design (ADR-003), so a native query is the right tool here -- the same choice
     * MerchantLearningEventRepository.claimDueEvents made.
     */
    @Query(value = """
           SELECT * FROM notifications
            WHERE status IN ('CREATED', 'QUEUED', 'RETRYING')
              AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            FOR UPDATE SKIP LOCKED
            LIMIT :batchSize
           """, nativeQuery = true)
    List<Notification> claimDue(@Param("now") Instant now, @Param("batchSize") int batchSize);

    /** PROCESSING rows that outlived the worker that claimed them. */
    @Query("""
           SELECT n FROM Notification n
            WHERE n.status = com.finora.notification.domain.NotificationStatus.PROCESSING
              AND n.nextAttemptAt < :cutoff
           """)
    List<Notification> findAbandoned(@Param("cutoff") Instant cutoff, Pageable pageable);

    @Query("""
           SELECT MIN(n.nextAttemptAt) FROM Notification n
            WHERE n.status IN (com.finora.notification.domain.NotificationStatus.CREATED,
                               com.finora.notification.domain.NotificationStatus.QUEUED,
                               com.finora.notification.domain.NotificationStatus.RETRYING)
           """)
    Optional<Instant> findOldestPendingAt();
}
```

- [ ] **Step 8: Verify the migration applies and the entity maps**

```bash
cd backend && ./mvnw test -Dtest=NotificationTest
```

Expected: PASS. Then confirm the full suite still boots (the new migration runs against Testcontainers on any IT):

```bash
cd backend && ./mvnw test -Dtest=TransactionRepositoryIT
```

Expected: PASS — proves the new migration applies cleanly to a real Postgres.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/finora/notification backend/src/main/resources/db/migration backend/src/test/java/com/finora/notification
git commit -m "feat(notification): add Notification outbox entity, enums, migration, repository"
```

---

## Task 2: NotificationService.request() — the outbox write path

**Files:**
- Create: `backend/src/main/java/com/finora/notification/api/NotificationRequest.java`
- Create: `backend/src/main/java/com/finora/notification/api/NotificationService.java`
- Test: `backend/src/test/java/com/finora/notification/api/NotificationServiceTest.java`

**Interfaces:**
- Consumes: `Notification.create(...)`, `NotificationRepository.existsByNotificationKey(String)`, `NotificationRepository.save(...)` from Task 1.
- Produces: `NotificationService.request(NotificationRequest)` returning `Optional<UUID>` (empty when suppressed as a duplicate); `NotificationRequest` record with static factory `NotificationRequest.of(UUID userId, NotificationType type, NotificationCategory category, NotificationPriority priority, String notificationKey, Set<NotificationChannel> channels, Map<String,String> params)`. Task 5/6/10 providers and Task 3's dispatcher consume the rows this writes. **Phase B calls this method.**

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/finora/notification/api/NotificationServiceTest.java`:

```java
package com.finora.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.repository.NotificationRepository;
import com.finora.notification.template.RenderedMessage;
import com.finora.notification.template.TemplateRenderer;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Mockito-based unit tests against mocked repositories, matching this codebase's established
 * pattern (GoalServiceTest, MerchantLearningServiceTest).
 */
class NotificationServiceTest {

    private NotificationRepository repository;
    private TemplateRenderer templateRenderer;
    private NotificationPreferenceResolver preferenceResolver;
    private NotificationService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
        templateRenderer = mock(TemplateRenderer.class);
        preferenceResolver = mock(NotificationPreferenceResolver.class);
        service = new NotificationService(repository, templateRenderer, preferenceResolver);

        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.existsByNotificationKey(anyString())).thenReturn(false);
        when(preferenceResolver.isEnabled(any(), any(), any())).thenReturn(true);
        when(templateRenderer.render(any(), any(), any()))
                .thenReturn(new RenderedMessage("Title", "Body"));
    }

    private NotificationRequest request(Set<NotificationChannel> channels, String key) {
        return NotificationRequest.of(userId, NotificationType.IMPORT_STATEMENT_READY,
                NotificationCategory.FINANCIAL, NotificationPriority.NORMAL, key, channels,
                Map.of("bank", "HDFC"));
    }

    @Test
    void request_writesOneRowPerChannel() {
        service.request(request(Set.of(NotificationChannel.EMAIL, NotificationChannel.PUSH), "K1"));

        verify(repository, times(2)).save(any(Notification.class));
    }

    @Test
    void request_suppressesADuplicateIdempotencyKey() {
        when(repository.existsByNotificationKey("K1:EMAIL")).thenReturn(true);

        service.request(request(Set.of(NotificationChannel.EMAIL), "K1"));

        verify(repository, never()).save(any(Notification.class));
    }

    @Test
    void request_skipsAChannelTheUserHasDisabled() {
        when(preferenceResolver.isEnabled(userId, NotificationCategory.FINANCIAL,
                NotificationChannel.SMS)).thenReturn(false);

        service.request(request(Set.of(NotificationChannel.EMAIL, NotificationChannel.SMS), "K1"));

        verify(repository, times(1)).save(any(Notification.class));
    }

    @Test
    void request_returnsTheIdsItActuallyWrote() {
        var ids = service.request(request(Set.of(NotificationChannel.EMAIL), "K1"));

        assertThat(ids).hasSize(1);
    }

    @Test
    void request_neverThrowsWhenTheTemplateIsMissing() {
        when(templateRenderer.render(any(), any(), any()))
                .thenThrow(new IllegalStateException("no template"));

        // A notification failure must never fail the caller's own business transaction.
        assertThat(service.request(request(Set.of(NotificationChannel.EMAIL), "K1"))).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=NotificationServiceTest
```

Expected: FAIL — `NotificationRequest`, `NotificationService`, `TemplateRenderer`, `RenderedMessage`, `NotificationPreferenceResolver` do not exist.

- [ ] **Step 3: Write the request record**

`backend/src/main/java/com/finora/notification/api/NotificationRequest.java`:

```java
package com.finora.notification.api;

import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What a caller asks for. Callers name the event semantically and supply parameters -- they never
 * pass a title/body string, because copy lives in notification_templates.
 *
 * @param notificationKey deterministic idempotency key, e.g. {@code IMPORT_READY_{jobId}}. The
 *     channel is appended per row, so one request across two channels yields two distinct keys.
 */
public record NotificationRequest(
        UUID userId,
        NotificationType type,
        NotificationCategory category,
        NotificationPriority priority,
        String notificationKey,
        Set<NotificationChannel> channels,
        Map<String, String> params) {

    public static NotificationRequest of(UUID userId, NotificationType type,
            NotificationCategory category, NotificationPriority priority, String notificationKey,
            Set<NotificationChannel> channels, Map<String, String> params) {
        return new NotificationRequest(userId, type, category, priority, notificationKey, channels,
                Map.copyOf(params));
    }
}
```

- [ ] **Step 4: Write the preference resolver interface**

`backend/src/main/java/com/finora/notification/api/NotificationPreferenceResolver.java`:

```java
package com.finora.notification.api;

import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import java.util.UUID;

/**
 * Whether a user wants a given category on a given channel. Task 8 supplies the real
 * implementation; until then a permissive default is wired in so the outbox path is testable.
 */
public interface NotificationPreferenceResolver {
    boolean isEnabled(UUID userId, NotificationCategory category, NotificationChannel channel);
}
```

- [ ] **Step 5: Write the service**

`backend/src/main/java/com/finora/notification/api/NotificationService.java`:

```java
package com.finora.notification.api;

import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.repository.NotificationRepository;
import com.finora.notification.template.RenderedMessage;
import com.finora.notification.template.TemplateRenderer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The only surface callers touch. Writes outbox rows inside the caller's own transaction -- the
 * same pattern AuditService.record() already uses -- so a crash before dispatch leaves replayable
 * work rather than a lost event.
 *
 * <p>This method must never throw. A caller's business transaction (an import completing, a
 * password changing) is not allowed to fail because a notification could not be composed.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;
    private final TemplateRenderer templateRenderer;
    private final NotificationPreferenceResolver preferenceResolver;

    public NotificationService(NotificationRepository repository, TemplateRenderer templateRenderer,
            NotificationPreferenceResolver preferenceResolver) {
        this.repository = repository;
        this.templateRenderer = templateRenderer;
        this.preferenceResolver = preferenceResolver;
    }

    /**
     * Requests delivery on each channel the user has enabled for this category.
     *
     * <p>Deliberately NOT annotated {@code @Transactional}: it must join the caller's existing
     * transaction so the row commits atomically with whatever the caller is recording. It is the
     * caller's transaction that makes this an outbox.
     *
     * @return the ids actually written; a channel suppressed by preference or idempotency is
     *     simply absent.
     */
    public List<UUID> request(NotificationRequest request) {
        List<UUID> written = new ArrayList<>();
        Instant now = Instant.now();
        for (NotificationChannel channel : request.channels()) {
            try {
                if (!preferenceResolver.isEnabled(request.userId(), request.category(), channel)) {
                    continue;
                }
                String key = request.notificationKey() + ":" + channel.name();
                if (repository.existsByNotificationKey(key)) {
                    log.debug("Notification {} already requested, skipping duplicate", key);
                    continue;
                }
                RenderedMessage rendered =
                        templateRenderer.render(request.type(), channel, request.params());
                Notification notification = Notification.create(request.userId(), request.type(),
                        request.category(), channel, request.priority(), key, rendered.title(),
                        rendered.body(), now);
                notification.markQueued(now);
                written.add(repository.save(notification).getId());
            } catch (RuntimeException e) {
                // Never propagate: the caller's own work must not fail over a notification.
                log.error("Could not queue a {} notification on {} for user {}", request.type(),
                        channel, request.userId(), e);
            }
        }
        return written;
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=NotificationServiceTest
```

Expected: PASS (5 tests). `TemplateRenderer`/`RenderedMessage` are created in Task 7 — to keep this task independently testable, create the minimal versions now as part of this step:

`backend/src/main/java/com/finora/notification/template/RenderedMessage.java`:

```java
package com.finora.notification.template;

/** A template rendered for one channel. */
public record RenderedMessage(String title, String body) {}
```

`backend/src/main/java/com/finora/notification/template/TemplateRenderer.java` — interface only for now; Task 7 supplies the DB-backed implementation:

```java
package com.finora.notification.template;

import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationType;
import java.util.Map;

public interface TemplateRenderer {
    /**
     * @throws IllegalStateException when no active template exists for this type/channel -- a
     *     missing template is a deployment error, not a per-send condition to swallow silently.
     */
    RenderedMessage render(NotificationType type, NotificationChannel channel,
            Map<String, String> params);
}
```

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/notification backend/src/test/java/com/finora/notification
git commit -m "feat(notification): add NotificationService outbox write path with idempotency"
```

---

## Task 3: NotificationDispatcher worker

**Files:**
- Create: `backend/src/main/java/com/finora/notification/provider/NotificationChannelProvider.java`
- Create: `backend/src/main/java/com/finora/notification/provider/ChannelSendResult.java`
- Create: `backend/src/main/java/com/finora/notification/worker/NotificationDispatcher.java`
- Modify: `backend/src/main/java/com/finora/config/BackgroundWorkConfig.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/resources/application-test.yml`
- Test: `backend/src/test/java/com/finora/notification/worker/NotificationDispatcherTest.java`

**Interfaces:**
- Consumes: `NotificationRepository.claimDue(...)`, `findAbandoned(...)`, `findOldestPendingAt()`, `countByStatus(...)`, and `Notification`'s state-machine methods from Task 1.
- Produces: `NotificationDispatcher.drainOnce()` returning `int` (count processed) — public and synchronous so tests drive it without a live scheduler; `NotificationDispatcher.recoverAbandoned()` returning `int`; `NotificationDispatcher.nudge()` (async, fire-and-forget). `NotificationChannelProvider` interface: `NotificationChannel channel()`, `boolean isConfigured()`, `ChannelSendResult send(Notification notification)`. Tasks 5, 6, 10, 11 implement `NotificationChannelProvider`.

- [ ] **Step 1: Write the provider interface and result type**

`backend/src/main/java/com/finora/notification/provider/ChannelSendResult.java`:

```java
package com.finora.notification.provider;

/**
 * Outcome of one delivery attempt. Providers return this instead of throwing, matching
 * ResendEmailProvider/TwoFactorSmsProvider -- a send failure is data, not an exception.
 *
 * @param providerName low-cardinality provider identifier for the notification_logs row
 * @param detail human-readable outcome, already masked/redacted by the provider
 */
public record ChannelSendResult(boolean success, String providerName, String detail) {

    public static ChannelSendResult success(String providerName, String detail) {
        return new ChannelSendResult(true, providerName, detail);
    }

    public static ChannelSendResult failure(String providerName, String detail) {
        return new ChannelSendResult(false, providerName, detail);
    }
}
```

`backend/src/main/java/com/finora/notification/provider/NotificationChannelProvider.java`:

```java
package com.finora.notification.provider;

import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationChannel;

/**
 * Delivers a notification on exactly one channel. Implementations wrap the existing
 * EmailProvider/SmsProvider or talk to FCM/APNs; the dispatcher selects one by
 * {@link #channel()} and never knows which concrete provider it got.
 */
public interface NotificationChannelProvider {

    NotificationChannel channel();

    /** False when credentials are absent -- the dispatcher dead-letters rather than retrying forever. */
    boolean isConfigured();

    /** Must not throw; failures come back as {@link ChannelSendResult#failure}. */
    ChannelSendResult send(Notification notification);
}
```

- [ ] **Step 2: Write the failing dispatcher test**

Create `backend/src/test/java/com/finora/notification/worker/NotificationDispatcherTest.java`:

```java
package com.finora.notification.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationStatus;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.provider.ChannelSendResult;
import com.finora.notification.provider.NotificationChannelProvider;
import com.finora.notification.repository.NotificationLogRepository;
import com.finora.notification.repository.NotificationRepository;
import com.finora.observability.WorkerExecution;
import com.finora.observability.WorkerObservability;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mockito-based, matching this codebase's established worker-test pattern (ImportJobWorkerTest),
 * rather than a Spring-context IT. The TransactionTemplate is stubbed to run its callback inline.
 */
class NotificationDispatcherTest {

    private NotificationRepository repository;
    private NotificationLogRepository logRepository;
    private NotificationChannelProvider emailProvider;
    private WorkerObservability observability;
    private TransactionTemplate transactionTemplate;
    private NotificationDispatcher dispatcher;

    private Notification pending;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
        logRepository = mock(NotificationLogRepository.class);
        emailProvider = mock(NotificationChannelProvider.class);
        observability = mock(WorkerObservability.class);
        transactionTemplate = mock(TransactionTemplate.class);

        when(observability.beginScheduled(any(), any())).thenReturn(mock(WorkerExecution.class));
        when(observability.begin(any(), any())).thenReturn(mock(WorkerExecution.class));
        when(emailProvider.channel()).thenReturn(NotificationChannel.EMAIL);
        when(emailProvider.isConfigured()).thenReturn(true);

        // Run both TransactionTemplate forms inline so the worker's logic is what is under test.
        when(transactionTemplate.execute(any())).thenAnswer(
                inv -> ((org.springframework.transaction.support.TransactionCallback<?>)
                        inv.getArgument(0)).doInTransaction(null));

        pending = Notification.create(UUID.randomUUID(), NotificationType.IMPORT_STATEMENT_READY,
                NotificationCategory.FINANCIAL, NotificationChannel.EMAIL,
                NotificationPriority.NORMAL, "K1:EMAIL", "Title", "Body", Instant.now());

        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findById(any())).thenReturn(Optional.of(pending));
        when(repository.findOldestPendingAt()).thenReturn(Optional.empty());

        dispatcher = new NotificationDispatcher(repository, logRepository,
                List.of(emailProvider), observability, transactionTemplate);
    }

    @Test
    void drainOnce_marksSentWhenTheProviderSucceeds() {
        when(repository.claimDue(any(), anyInt())).thenReturn(List.of(pending));
        when(emailProvider.send(any())).thenReturn(ChannelSendResult.success("resend", "ok"));

        int processed = dispatcher.drainOnce();

        assertThat(processed).isEqualTo(1);
        assertThat(pending.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(pending.getSentAt()).isNotNull();
    }

    @Test
    void drainOnce_schedulesRetryWhenTheProviderFails() {
        when(repository.claimDue(any(), anyInt())).thenReturn(List.of(pending));
        when(emailProvider.send(any()))
                .thenReturn(ChannelSendResult.failure("resend", "502 from provider"));

        dispatcher.drainOnce();

        assertThat(pending.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(pending.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void drainOnce_deadLettersWhenNoProviderIsConfiguredForTheChannel() {
        when(emailProvider.isConfigured()).thenReturn(false);
        when(repository.claimDue(any(), anyInt())).thenReturn(List.of(pending));

        dispatcher.drainOnce();

        // An unconfigured provider will never succeed; retrying five times just wastes the queue.
        assertThat(pending.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
    }

    @Test
    void drainOnce_returnsZeroWhenNothingIsDue() {
        when(repository.claimDue(any(), anyInt())).thenReturn(List.of());

        assertThat(dispatcher.drainOnce()).isZero();
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=NotificationDispatcherTest
```

Expected: FAIL — `NotificationDispatcher` and `NotificationLogRepository` do not exist.

- [ ] **Step 4: Write the dispatcher**

`backend/src/main/java/com/finora/notification/worker/NotificationDispatcher.java`:

```java
package com.finora.notification.worker;

import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationLog;
import com.finora.notification.provider.ChannelSendResult;
import com.finora.notification.provider.NotificationChannelProvider;
import com.finora.notification.repository.NotificationLogRepository;
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
    private final NotificationLogRepository logRepository;
    private final Map<NotificationChannel, NotificationChannelProvider> providers =
            new EnumMap<>(NotificationChannel.class);
    private final WorkerObservability observability;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.notification.queue.enabled:true}")
    private boolean enabled;

    public NotificationDispatcher(NotificationRepository repository,
            NotificationLogRepository logRepository, List<NotificationChannelProvider> providerList,
            WorkerObservability observability, TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.logRepository = logRepository;
        this.observability = observability;
        this.transactionTemplate = transactionTemplate;
        for (NotificationChannelProvider provider : providerList) {
            this.providers.put(provider.channel(), provider);
        }
        observability.publishQueueDepth(WORKER, JOB_KIND,
                () -> repository.countByStatus(com.finora.notification.domain.NotificationStatus.QUEUED));
        observability.publishOldestPendingAge(WORKER, JOB_KIND, repository::findOldestPendingAt);
    }

    @Scheduled(fixedDelayString = "${app.notification.queue.poll-interval-ms:30000}",
            initialDelayString = "${app.notification.queue.initial-delay-ms:15000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        try (WorkerExecution execution = observability.beginScheduled(WORKER, JOB_KIND)) {
            drain(execution);
        }
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

    private void recordSuccess(WorkerExecution execution, Notification notification,
            ChannelSendResult result) {
        transactionTemplate.executeWithoutResult(status -> {
            notification.markSent(Instant.now());
            repository.save(notification);
            logRepository.save(NotificationLog.of(notification.getId(), result.providerName(),
                    result.detail(), true, notification.getAttemptCount(), Instant.now()));
        });
        execution.completed(notification.getId());
    }

    private void recordFailure(WorkerExecution execution, Notification notification,
            ChannelSendResult result) {
        transactionTemplate.executeWithoutResult(status -> {
            Notification.FailureOutcome outcome =
                    notification.recordFailure(result.detail(), Instant.now());
            repository.save(notification);
            logRepository.save(NotificationLog.of(notification.getId(), result.providerName(),
                    result.detail(), false, notification.getAttemptCount(), Instant.now()));
            if (outcome == Notification.FailureOutcome.DEAD_LETTERED) {
                execution.deadLettered(notification.getId(), notification.getAttemptCount(),
                        new IllegalStateException(result.detail()));
            } else if (outcome == Notification.FailureOutcome.RETRY_SCHEDULED) {
                execution.retryScheduled(notification.getId(), notification.getAttemptCount());
            }
        });
    }

    private void failTerminally(WorkerExecution execution, Notification notification,
            String reason) {
        transactionTemplate.executeWithoutResult(status -> {
            for (int i = notification.getAttemptCount(); i < Notification.MAX_ATTEMPTS; i++) {
                notification.recordFailure(reason, Instant.now());
            }
            repository.save(notification);
            logRepository.save(NotificationLog.of(notification.getId(),
                    notification.getChannel().name(), reason, false,
                    notification.getAttemptCount(), Instant.now()));
        });
        execution.deadLettered(notification.getId(), notification.getAttemptCount(),
                new IllegalStateException(reason));
    }

    /**
     * Returns rows stuck in PROCESSING past the timeout to the queue without charging an attempt --
     * the worker that claimed them died, which is not the notification's fault.
     */
    public int recoverAbandoned() {
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
        return abandoned.size();
    }
}
```

- [ ] **Step 5: Add the executor bean**

Modify `backend/src/main/java/com/finora/config/BackgroundWorkConfig.java` — add a `notificationQueueExecutor` bean alongside the existing `learningQueueExecutor` / `importQueueExecutor` / `authEmailExecutor`, copying their exact shape:

```java
    @Bean("notificationQueueExecutor")
    public Executor notificationQueueExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("notification-queue-");
        // CallerRunsPolicy, never a discard policy: durability lives in the DB row, and running
        // on the caller's thread under saturation is strictly better than dropping the nudge.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
```

- [ ] **Step 6: Add configuration properties**

Modify `backend/src/main/resources/application.yml`, under the existing `app:` block:

```yaml
app:
  notification:
    queue:
      enabled: ${NOTIFICATION_QUEUE_ENABLED:true}
      poll-interval-ms: 30000
      initial-delay-ms: 15000
```

Modify `backend/src/main/resources/application-test.yml`, alongside the existing `app.learning.queue.enabled: false`:

```yaml
app:
  notification:
    queue:
      # Off under test so the suite is deterministic. Tests drive NotificationDispatcher directly
      # via drainOnce()/recoverAbandoned(), which deliberately do not consult this flag.
      enabled: false
```

- [ ] **Step 7: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=NotificationDispatcherTest
```

Expected: PASS (4 tests). `NotificationLog`/`NotificationLogRepository` land in Task 4 — create minimal versions now so this compiles, then Task 4 adds their migration and tests.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/finora/notification backend/src/main/java/com/finora/config/BackgroundWorkConfig.java backend/src/main/resources/application.yml backend/src/main/resources/application-test.yml backend/src/test/java/com/finora/notification
git commit -m "feat(notification): add NotificationDispatcher worker with claim/retry/dead-letter"
```

---

## Task 4: Notification delivery log

**Files:**
- Create: `backend/src/main/java/com/finora/notification/domain/NotificationLog.java`
- Create: `backend/src/main/java/com/finora/notification/repository/NotificationLogRepository.java`
- Create: `backend/src/main/resources/db/migration/V<next>__notification_logs.sql`
- Test: `backend/src/test/java/com/finora/notification/repository/NotificationLogRepositoryIT.java`

**Interfaces:**
- Consumes: `Notification` from Task 1; called by `NotificationDispatcher` from Task 3.
- Produces: `NotificationLog.of(UUID notificationId, String provider, String response, boolean success, int attempt, Instant at)`; `NotificationLogRepository.findByNotificationIdOrderByTimestampDesc(UUID)`.

- [ ] **Step 1: Write the entity**

`backend/src/main/java/com/finora/notification/domain/NotificationLog.java`:

```java
package com.finora.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One delivery attempt against one provider. Append-only: the notifications row holds current
 * state, this holds the history of how it got there, which is what an admin needs when asking
 * "why did this fail".
 *
 * <p>{@code success} means the provider's synchronous call returned OK -- not that the message was
 * delivered. Nothing in this codebase can currently know the latter.
 */
@Entity
@Table(name = "notification_logs")
public class NotificationLog {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(nullable = false, length = 64)
    private String provider;

    @Column(length = 2000)
    private String response;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "timestamp", nullable = false, updatable = false)
    private Instant timestamp;

    protected NotificationLog() {
        // for JPA
    }

    private NotificationLog(UUID notificationId, String provider, String response, boolean success,
            int attempt, Instant timestamp) {
        this.notificationId = notificationId;
        this.provider = provider;
        this.response = response;
        this.success = success;
        this.attempt = attempt;
        this.timestamp = timestamp;
    }

    public static NotificationLog of(UUID notificationId, String provider, String response,
            boolean success, int attempt, Instant timestamp) {
        return new NotificationLog(notificationId, provider, truncate(response), success, attempt,
                timestamp);
    }

    private static String truncate(String response) {
        if (response == null) {
            return null;
        }
        return response.length() <= 2000 ? response : response.substring(0, 2000);
    }

    public UUID getId() {
        return id;
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public String getProvider() {
        return provider;
    }

    public String getResponse() {
        return response;
    }

    public boolean isSuccess() {
        return success;
    }

    public int getAttempt() {
        return attempt;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
```

- [ ] **Step 2: Write the repository**

`backend/src/main/java/com/finora/notification/repository/NotificationLogRepository.java`:

```java
package com.finora.notification.repository;

import com.finora.notification.domain.NotificationLog;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {

    List<NotificationLog> findByNotificationIdOrderByTimestampDesc(UUID notificationId);

    @Query("""
           SELECT COUNT(l) FROM NotificationLog l
            WHERE l.success = :success AND l.timestamp >= :since
           """)
    long countByOutcomeSince(@Param("success") boolean success, @Param("since") Instant since);
}
```

- [ ] **Step 3: Pick the migration version, then write the migration**

```bash
git fetch origin && ls backend/src/main/resources/db/migration | sort -V | tail -5
```

Create `backend/src/main/resources/db/migration/V<next>__notification_logs.sql`:

```sql
-- Append-only history of delivery attempts. The notifications row holds current state; this holds
-- how it got there, which is what an admin needs when answering "why did this fail".

CREATE TABLE notification_logs (
    id              UUID PRIMARY KEY,
    notification_id UUID NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    provider        VARCHAR(64) NOT NULL,
    response        VARCHAR(2000),
    success         BOOLEAN NOT NULL,
    attempt         INTEGER NOT NULL,
    timestamp       TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_notification_logs_notification
    ON notification_logs (notification_id, timestamp DESC);

CREATE INDEX idx_notification_logs_failures
    ON notification_logs (timestamp DESC)
    WHERE success = FALSE;

COMMENT ON TABLE notification_logs IS
    'One row per delivery attempt per provider. Append-only.';
COMMENT ON COLUMN notification_logs.success IS
    'The provider''s synchronous API call returned OK. NOT a delivery confirmation -- no provider '
    'webhook exists in this codebase, which is also why notifications has no DELIVERED state.';
COMMENT ON COLUMN notification_logs.response IS
    'Provider response or error detail, already masked/redacted by the provider before it reaches '
    'here. Never store a raw credential or an unmasked recipient.';
```

- [ ] **Step 4: Write the integration test**

Create `backend/src/test/java/com/finora/notification/repository/NotificationLogRepositoryIT.java`:

```java
package com.finora.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.AbstractIntegrationTest;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationLog;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationStatus;
import com.finora.notification.domain.NotificationType;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exercises behavior only a real Postgres can validate: the FOR UPDATE SKIP LOCKED claim query and
 * the cascade from notifications to notification_logs.
 */
class NotificationLogRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationLogRepository logRepository;

    @Autowired
    private UserRepository userRepository;

    private UUID userId;

    @BeforeEach
    void setUp() {
        // Build a real user row -- notifications.user_id is a foreign key.
        User user = userRepository.save(newTestUser());
        userId = user.getId();
    }

    private Notification persistNotification(String key, Instant nextAttemptAt) {
        Notification n = Notification.create(userId, NotificationType.IMPORT_STATEMENT_READY,
                NotificationCategory.FINANCIAL, NotificationChannel.EMAIL,
                NotificationPriority.NORMAL, key, "Title", "Body", nextAttemptAt);
        n.markQueued(nextAttemptAt);
        return notificationRepository.save(n);
    }

    @Test
    @Transactional
    void claimDue_returnsOnlyNotificationsWhoseNextAttemptHasArrived() {
        Instant now = Instant.now();
        persistNotification("DUE:EMAIL", now.minusSeconds(60));
        persistNotification("FUTURE:EMAIL", now.plusSeconds(600));

        List<Notification> claimed = notificationRepository.claimDue(now, 10);

        assertThat(claimed).extracting(Notification::getNotificationKey)
                .containsExactly("DUE:EMAIL");
    }

    @Test
    @Transactional
    void claimDue_ignoresTerminalNotifications() {
        Instant now = Instant.now();
        Notification sent = persistNotification("SENT:EMAIL", now.minusSeconds(60));
        sent.markSent(now);
        notificationRepository.save(sent);

        assertThat(notificationRepository.claimDue(now, 10)).isEmpty();
    }

    @Test
    @Transactional
    void existsByNotificationKey_enforcesIdempotency() {
        persistNotification("K1:EMAIL", Instant.now());

        assertThat(notificationRepository.existsByNotificationKey("K1:EMAIL")).isTrue();
        assertThat(notificationRepository.existsByNotificationKey("K2:EMAIL")).isFalse();
    }

    @Test
    @Transactional
    void logs_areRetrievableNewestFirst() {
        Notification n = persistNotification("K1:EMAIL", Instant.now());
        Instant t1 = Instant.parse("2026-09-02T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-02T10:05:00Z");
        logRepository.save(NotificationLog.of(n.getId(), "resend", "502", false, 1, t1));
        logRepository.save(NotificationLog.of(n.getId(), "resend", "ok", true, 2, t2));

        List<NotificationLog> logs =
                logRepository.findByNotificationIdOrderByTimestampDesc(n.getId());

        assertThat(logs).extracting(NotificationLog::getAttempt).containsExactly(2, 1);
    }
}
```

**Note:** `newTestUser()` does not exist as a shared helper — before writing this, check how `TransactionRepositoryIT.setUp()` constructs its `User` fixture and copy that construction inline rather than inventing a helper.

- [ ] **Step 5: Run the tests**

```bash
cd backend && ./mvnw test -Dtest=NotificationLogRepositoryIT
```

Expected: PASS (4 tests), against a real Postgres via Testcontainers.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/notification backend/src/main/resources/db/migration backend/src/test/java/com/finora/notification
git commit -m "feat(notification): add notification delivery log with attempt history"
```

---

## Task 5: Email channel provider

**Files:**
- Create: `backend/src/main/java/com/finora/notification/provider/EmailNotificationProvider.java`
- Test: `backend/src/test/java/com/finora/notification/provider/EmailNotificationProviderTest.java`

**Interfaces:**
- Consumes: `NotificationChannelProvider`, `ChannelSendResult` from Task 3; the existing `EmailProvider` interface (`com.finora.service.EmailProvider`) — `boolean isConfigured()`, `EmailResult send(EmailMessage message)`.
- Produces: a `NotificationChannelProvider` bean for `NotificationChannel.EMAIL`, auto-collected into `NotificationDispatcher`'s constructor list.

- [ ] **Step 1: Read the existing types before writing anything**

```bash
sed -n '1,60p' backend/src/main/java/com/finora/service/EmailProvider.java
sed -n '1,60p' backend/src/main/java/com/finora/service/EmailMessage.java
sed -n '1,60p' backend/src/main/java/com/finora/service/EmailResult.java
```

The generic `send(EmailMessage)` path is the one to wrap — the purpose-built methods (`sendPasswordResetEmail` etc.) stay for their existing callers and are not touched. Confirm `EmailMessage`'s exact constructor/factory shape and `EmailResult`'s accessors (`success()`, `provider()`) before Step 3, and adjust the code below to match what you find rather than assuming.

- [ ] **Step 2: Write the failing test**

Create `backend/src/test/java/com/finora/notification/provider/EmailNotificationProviderTest.java`:

```java
package com.finora.notification.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import com.finora.repository.UserRepository;
import com.finora.service.EmailProvider;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmailNotificationProviderTest {

    private EmailProvider emailProvider;
    private UserRepository userRepository;
    private EmailNotificationProvider provider;

    @BeforeEach
    void setUp() {
        emailProvider = mock(EmailProvider.class);
        userRepository = mock(UserRepository.class);
        provider = new EmailNotificationProvider(emailProvider, userRepository);
        when(emailProvider.isConfigured()).thenReturn(true);
    }

    private Notification notification() {
        return Notification.create(UUID.randomUUID(), NotificationType.IMPORT_STATEMENT_READY,
                NotificationCategory.FINANCIAL, NotificationChannel.EMAIL,
                NotificationPriority.NORMAL, "K1:EMAIL", "Statement ready",
                "We finished importing your statement.", Instant.now());
    }

    @Test
    void channel_isEmail() {
        assertThat(provider.channel()).isEqualTo(NotificationChannel.EMAIL);
    }

    @Test
    void send_failsWhenTheUserHasNoEmailOnFile() {
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.detail()).doesNotContain("@");
    }

    @Test
    void send_neverThrowsWhenTheUnderlyingProviderThrows() {
        when(userRepository.findById(any())).thenThrow(new RuntimeException("db down"));

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=EmailNotificationProviderTest
```

Expected: FAIL — `EmailNotificationProvider` does not exist.

- [ ] **Step 4: Write the provider**

`backend/src/main/java/com/finora/notification/provider/EmailNotificationProvider.java`. **Adapt the `EmailMessage`/`EmailResult` construction to whatever Step 1 showed** — the surrounding structure is what matters:

```java
package com.finora.notification.provider;

import com.finora.entity.User;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationChannel;
import com.finora.repository.UserRepository;
import com.finora.service.EmailProvider;
import com.finora.service.EmailResult;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Wraps the existing EmailProvider. Deliberately a wrapper, not a replacement: ResendEmailProvider
 * is already tested, already handles timeouts and masking, and already has live callers.
 */
@Component
public class EmailNotificationProvider implements NotificationChannelProvider {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationProvider.class);
    private static final String PROVIDER_NAME = "email";

    private final EmailProvider emailProvider;
    private final UserRepository userRepository;

    public EmailNotificationProvider(EmailProvider emailProvider, UserRepository userRepository) {
        this.emailProvider = emailProvider;
        this.userRepository = userRepository;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public boolean isConfigured() {
        return emailProvider.isConfigured();
    }

    @Override
    public ChannelSendResult send(Notification notification) {
        try {
            Optional<User> user = userRepository.findById(notification.getUserId());
            if (user.isEmpty() || user.get().getEmail() == null
                    || user.get().getEmail().isBlank()) {
                // Never put the (missing or present) address in the detail -- it lands in
                // notification_logs, which admins read.
                return ChannelSendResult.failure(PROVIDER_NAME, "no email address on file");
            }
            EmailResult result = emailProvider.send(buildMessage(user.get().getEmail(),
                    notification.getTitle(), notification.getMessage()));
            return result.success()
                    ? ChannelSendResult.success(PROVIDER_NAME, result.provider().name())
                    : ChannelSendResult.failure(PROVIDER_NAME, "provider reported failure");
        } catch (RuntimeException e) {
            log.error("Email notification {} could not be sent", notification.getId(), e);
            return ChannelSendResult.failure(PROVIDER_NAME,
                    "exception: " + e.getClass().getSimpleName());
        }
    }

    // Build using whatever factory/constructor EmailMessage actually exposes -- see Step 1.
    private com.finora.service.EmailMessage buildMessage(String to, String subject, String body) {
        return new com.finora.service.EmailMessage(to, subject, body);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=EmailNotificationProviderTest
```

Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/notification/provider backend/src/test/java/com/finora/notification/provider
git commit -m "feat(notification): add email channel provider wrapping EmailProvider"
```

---

## Task 6: SMS channel provider

**Files:**
- Create: `backend/src/main/java/com/finora/notification/provider/SmsNotificationProvider.java`
- Test: `backend/src/test/java/com/finora/notification/provider/SmsNotificationProviderTest.java`

**Interfaces:**
- Consumes: `NotificationChannelProvider`, `ChannelSendResult` from Task 3; existing `com.finora.service.SmsProvider` — `boolean isConfigured()`, `SmsResult send(SmsRequest request)`.
- Produces: a `NotificationChannelProvider` bean for `NotificationChannel.SMS`.

- [ ] **Step 1: Read the existing types**

```bash
sed -n '1,60p' backend/src/main/java/com/finora/service/SmsProvider.java
sed -n '1,60p' backend/src/main/java/com/finora/service/SmsRequest.java
sed -n '1,60p' backend/src/main/java/com/finora/service/SmsResult.java
```

- [ ] **Step 2: Write the failing test**

Create `backend/src/test/java/com/finora/notification/provider/SmsNotificationProviderTest.java`:

```java
package com.finora.notification.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import com.finora.repository.UserRepository;
import com.finora.service.SmsProvider;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SmsNotificationProviderTest {

    private SmsProvider smsProvider;
    private UserRepository userRepository;
    private SmsNotificationProvider provider;

    @BeforeEach
    void setUp() {
        smsProvider = mock(SmsProvider.class);
        userRepository = mock(UserRepository.class);
        provider = new SmsNotificationProvider(smsProvider, userRepository);
        when(smsProvider.isConfigured()).thenReturn(true);
    }

    private Notification notification() {
        return Notification.create(UUID.randomUUID(), NotificationType.IMPORT_STATEMENT_READY,
                NotificationCategory.FINANCIAL, NotificationChannel.SMS,
                NotificationPriority.NORMAL, "K1:SMS", "Statement ready", "Your import finished.",
                Instant.now());
    }

    @Test
    void channel_isSms() {
        assertThat(provider.channel()).isEqualTo(NotificationChannel.SMS);
    }

    @Test
    void send_failsWithoutLeakingThePhoneNumberWhenNoneIsOnFile() {
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.detail()).doesNotContain("+");
    }

    @Test
    void send_neverThrows() {
        when(userRepository.findById(any())).thenThrow(new RuntimeException("db down"));

        assertThat(provider.send(notification()).success()).isFalse();
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=SmsNotificationProviderTest
```

Expected: FAIL — `SmsNotificationProvider` does not exist.

- [ ] **Step 4: Write the provider**

`backend/src/main/java/com/finora/notification/provider/SmsNotificationProvider.java` — same structure as `EmailNotificationProvider`, resolving the user's phone number instead of email, building whatever `SmsRequest` shape Step 1 revealed, and returning masked details only. Follow `TwoFactorSmsProvider`'s precedent: never let a raw credential or unmasked recipient reach the result detail, because it is persisted to `notification_logs`.

```java
package com.finora.notification.provider;

import com.finora.entity.User;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationChannel;
import com.finora.repository.UserRepository;
import com.finora.service.SmsProvider;
import com.finora.service.SmsResult;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Wraps the existing SmsProvider. See EmailNotificationProvider for the shared rationale. */
@Component
public class SmsNotificationProvider implements NotificationChannelProvider {

    private static final Logger log = LoggerFactory.getLogger(SmsNotificationProvider.class);
    private static final String PROVIDER_NAME = "sms";

    private final SmsProvider smsProvider;
    private final UserRepository userRepository;

    public SmsNotificationProvider(SmsProvider smsProvider, UserRepository userRepository) {
        this.smsProvider = smsProvider;
        this.userRepository = userRepository;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SMS;
    }

    @Override
    public boolean isConfigured() {
        return smsProvider.isConfigured();
    }

    @Override
    public ChannelSendResult send(Notification notification) {
        try {
            Optional<User> user = userRepository.findById(notification.getUserId());
            if (user.isEmpty() || user.get().getPhoneNumber() == null
                    || user.get().getPhoneNumber().isBlank()) {
                return ChannelSendResult.failure(PROVIDER_NAME, "no phone number on file");
            }
            SmsResult result = smsProvider.send(buildRequest(user.get().getPhoneNumber(),
                    notification.getMessage()));
            return result.success()
                    ? ChannelSendResult.success(PROVIDER_NAME, "sent")
                    : ChannelSendResult.failure(PROVIDER_NAME, "provider reported failure");
        } catch (RuntimeException e) {
            log.error("SMS notification {} could not be sent", notification.getId(), e);
            return ChannelSendResult.failure(PROVIDER_NAME,
                    "exception: " + e.getClass().getSimpleName());
        }
    }

    // Build using whatever factory/constructor SmsRequest actually exposes -- see Step 1.
    private com.finora.service.SmsRequest buildRequest(String phoneNumber, String body) {
        return new com.finora.service.SmsRequest(phoneNumber, body);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=SmsNotificationProviderTest
```

Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/notification/provider backend/src/test/java/com/finora/notification/provider
git commit -m "feat(notification): add SMS channel provider wrapping SmsProvider"
```

---

## Task 7: Notification templates and rendering

**Files:**
- Create: `backend/src/main/java/com/finora/notification/domain/NotificationTemplate.java`
- Create: `backend/src/main/java/com/finora/notification/repository/NotificationTemplateRepository.java`
- Create: `backend/src/main/java/com/finora/notification/template/DatabaseTemplateRenderer.java`
- Create: `backend/src/main/resources/db/migration/V<next>__notification_templates.sql`
- Test: `backend/src/test/java/com/finora/notification/template/DatabaseTemplateRendererTest.java`

**Interfaces:**
- Consumes: `TemplateRenderer` interface and `RenderedMessage` from Task 2.
- Produces: `DatabaseTemplateRenderer implements TemplateRenderer`; `NotificationTemplateRepository.findByTypeAndChannelAndActiveTrue(NotificationType, NotificationChannel)` returning `Optional<NotificationTemplate>`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/finora/notification/template/DatabaseTemplateRendererTest.java`:

```java
package com.finora.notification.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationTemplate;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.repository.NotificationTemplateRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatabaseTemplateRendererTest {

    private NotificationTemplateRepository repository;
    private DatabaseTemplateRenderer renderer;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationTemplateRepository.class);
        renderer = new DatabaseTemplateRenderer(repository);
    }

    private void stubTemplate(String title, String body) {
        when(repository.findByTypeAndChannelAndActiveTrue(any(), any()))
                .thenReturn(Optional.of(NotificationTemplate.of(
                        NotificationType.IMPORT_STATEMENT_READY, NotificationChannel.EMAIL, title,
                        body)));
    }

    @Test
    void render_substitutesParameters() {
        stubTemplate("Your {{bank}} statement is ready",
                "We imported {{count}} transactions from your {{bank}} statement.");

        RenderedMessage rendered = renderer.render(NotificationType.IMPORT_STATEMENT_READY,
                NotificationChannel.EMAIL, Map.of("bank", "HDFC", "count", "42"));

        assertThat(rendered.title()).isEqualTo("Your HDFC statement is ready");
        assertThat(rendered.body())
                .isEqualTo("We imported 42 transactions from your HDFC statement.");
    }

    @Test
    void render_leavesUnknownPlaceholdersIntactRatherThanEmitting_null() {
        stubTemplate("Hello {{name}}", "Body");

        RenderedMessage rendered = renderer.render(NotificationType.IMPORT_STATEMENT_READY,
                NotificationChannel.EMAIL, Map.of());

        // Better a visible {{name}} in a log than the literal string "null" in a user's inbox.
        assertThat(rendered.title()).isEqualTo("Hello {{name}}");
    }

    @Test
    void render_throwsWhenNoActiveTemplateExists() {
        when(repository.findByTypeAndChannelAndActiveTrue(any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> renderer.render(NotificationType.IMPORT_STATEMENT_READY,
                NotificationChannel.EMAIL, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IMPORT_STATEMENT_READY");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=DatabaseTemplateRendererTest
```

Expected: FAIL — `NotificationTemplate` and `DatabaseTemplateRenderer` do not exist.

- [ ] **Step 3: Write the entity**

`backend/src/main/java/com/finora/notification/domain/NotificationTemplate.java`:

```java
package com.finora.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Notification copy, per type and channel. Centralized so wording is reviewable in one place
 * rather than hardcoded across ImportService, BudgetService and every future caller.
 *
 * <p>English only in v1. A language column can be added later without breaking this schema; i18n
 * is a separate initiative (no message bundles, no locale resolver exist today).
 */
@Entity
@Table(name = "notification_templates")
public class NotificationTemplate {

    @Id
    @GeneratedValue
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private NotificationType type;

    /** Wording differs by channel: push is terse, email has room. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationChannel channel;

    @Column(name = "title_template", nullable = false, length = 300)
    private String titleTemplate;

    @Column(name = "body_template", nullable = false, length = 2000)
    private String bodyTemplate;

    @Column(nullable = false)
    private boolean active = true;

    protected NotificationTemplate() {
        // for JPA
    }

    private NotificationTemplate(NotificationType type, NotificationChannel channel,
            String titleTemplate, String bodyTemplate) {
        this.type = type;
        this.channel = channel;
        this.titleTemplate = titleTemplate;
        this.bodyTemplate = bodyTemplate;
    }

    public static NotificationTemplate of(NotificationType type, NotificationChannel channel,
            String titleTemplate, String bodyTemplate) {
        return new NotificationTemplate(type, channel, titleTemplate, bodyTemplate);
    }

    public UUID getId() {
        return id;
    }

    public NotificationType getType() {
        return type;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public String getTitleTemplate() {
        return titleTemplate;
    }

    public String getBodyTemplate() {
        return bodyTemplate;
    }

    public boolean isActive() {
        return active;
    }
}
```

- [ ] **Step 4: Write the repository and renderer**

`backend/src/main/java/com/finora/notification/repository/NotificationTemplateRepository.java`:

```java
package com.finora.notification.repository;

import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationTemplate;
import com.finora.notification.domain.NotificationType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, UUID> {

    Optional<NotificationTemplate> findByTypeAndChannelAndActiveTrue(NotificationType type,
            NotificationChannel channel);
}
```

`backend/src/main/java/com/finora/notification/template/DatabaseTemplateRenderer.java`:

```java
package com.finora.notification.template;

import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationTemplate;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.repository.NotificationTemplateRepository;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Renders {{placeholder}} templates from the notification_templates table.
 *
 * <p>Deliberately a plain string substitution, not a templating engine: the parameter maps are
 * small and flat, and pulling in Thymeleaf/Freemarker for this would be more machinery than the
 * problem warrants.
 */
@Component
public class DatabaseTemplateRenderer implements TemplateRenderer {

    private final NotificationTemplateRepository repository;

    public DatabaseTemplateRenderer(NotificationTemplateRepository repository) {
        this.repository = repository;
    }

    @Override
    public RenderedMessage render(NotificationType type, NotificationChannel channel,
            Map<String, String> params) {
        NotificationTemplate template = repository.findByTypeAndChannelAndActiveTrue(type, channel)
                .orElseThrow(() -> new IllegalStateException(
                        "No active notification template for " + type + " on " + channel
                                + ". A type without a template row cannot be delivered."));
        return new RenderedMessage(substitute(template.getTitleTemplate(), params),
                substitute(template.getBodyTemplate(), params));
    }

    private String substitute(String template, Map<String, String> params) {
        String result = template;
        for (Map.Entry<String, String> param : params.entrySet()) {
            if (param.getValue() == null) {
                continue;
            }
            result = result.replace("{{" + param.getKey() + "}}", param.getValue());
        }
        // An unmatched {{placeholder}} is left as-is on purpose -- a visible placeholder is a
        // better failure than the literal text "null" reaching a user.
        return result;
    }
}
```

- [ ] **Step 5: Pick the migration version, then write the migration**

```bash
git fetch origin && ls backend/src/main/resources/db/migration | sort -V | tail -5
```

Create `backend/src/main/resources/db/migration/V<next>__notification_templates.sql`:

```sql
-- Notification copy, per type and channel. Centralized so wording is reviewable in one place
-- instead of hardcoded across every calling service.

CREATE TABLE notification_templates (
    id             UUID PRIMARY KEY,
    type           VARCHAR(64) NOT NULL,
    channel        VARCHAR(16) NOT NULL,
    title_template VARCHAR(300) NOT NULL,
    body_template  VARCHAR(2000) NOT NULL,
    active         BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (type, channel, active)
);

COMMENT ON TABLE notification_templates IS
    'English-only in v1. A language column can be added later without breaking this schema; i18n '
    'is a separate initiative (this app has no message bundles or locale resolver today).';
COMMENT ON COLUMN notification_templates.channel IS
    'Wording differs by channel -- push is terse, email has room.';

-- Seed the two types NotificationType declares. A type with no template row cannot be delivered,
-- so these ship together with the enum values rather than being configured post-deploy.
INSERT INTO notification_templates (id, type, channel, title_template, body_template) VALUES
    (gen_random_uuid(), 'IMPORT_STATEMENT_READY', 'EMAIL',
     'Your {{bank}} statement is ready',
     'Good news -- we finished the additional checks on your {{bank}} statement and imported it '
     'successfully. You can view your transactions in the app now.'),
    (gen_random_uuid(), 'IMPORT_STATEMENT_READY', 'PUSH',
     'Statement ready',
     'Your {{bank}} statement has been imported.'),
    (gen_random_uuid(), 'PASSWORD_CHANGED', 'EMAIL',
     'Your password was changed',
     'The password on your Finora account was just changed. If this was not you, reset your '
     'password immediately.'),
    (gen_random_uuid(), 'PASSWORD_CHANGED', 'PUSH',
     'Password changed',
     'Your Finora password was just changed.');
```

- [ ] **Step 6: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=DatabaseTemplateRendererTest
```

Expected: PASS (3 tests).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/notification backend/src/main/resources/db/migration backend/src/test/java/com/finora/notification
git commit -m "feat(notification): add database-backed notification templates and renderer"
```

---

## Task 8: Notification preferences

**Files:**
- Create: `backend/src/main/java/com/finora/notification/domain/NotificationPreference.java`
- Create: `backend/src/main/java/com/finora/notification/repository/NotificationPreferenceRepository.java`
- Create: `backend/src/main/java/com/finora/notification/api/DatabaseNotificationPreferenceResolver.java`
- Create: `backend/src/main/resources/db/migration/V<next>__notification_preferences.sql`
- Test: `backend/src/test/java/com/finora/notification/api/DatabaseNotificationPreferenceResolverTest.java`

**Interfaces:**
- Consumes: `NotificationPreferenceResolver` interface from Task 2.
- Produces: `DatabaseNotificationPreferenceResolver implements NotificationPreferenceResolver` — the bean `NotificationService` autowires.

**Product decision resolved here:** the proposal (§2.3) flagged "forcibly on vs defaulted on" for SECURITY notifications as an open question. **Decision: SECURITY is forcibly on** — `isEnabled` returns `true` for `NotificationCategory.SECURITY` without consulting the table at all. Rationale: a user who has silenced security alerts cannot be told their password changed, which is the one notification whose absence is itself a security problem. Encode this in code and in the migration comment.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/finora/notification/api/DatabaseNotificationPreferenceResolverTest.java`:

```java
package com.finora.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPreference;
import com.finora.notification.repository.NotificationPreferenceRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatabaseNotificationPreferenceResolverTest {

    private NotificationPreferenceRepository repository;
    private DatabaseNotificationPreferenceResolver resolver;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(NotificationPreferenceRepository.class);
        resolver = new DatabaseNotificationPreferenceResolver(repository);
    }

    @Test
    void securityNotificationsAreAlwaysEnabled() {
        boolean enabled = resolver.isEnabled(userId, NotificationCategory.SECURITY,
                NotificationChannel.EMAIL);

        assertThat(enabled).isTrue();
        // Not even consulted -- security alerts are not silenceable.
        verify(repository, never()).findByUserIdAndCategoryAndChannel(any(), any(), any());
    }

    @Test
    void financialNotificationsDefaultToEnabledWhenNoPreferenceRowExists() {
        when(repository.findByUserIdAndCategoryAndChannel(any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThat(resolver.isEnabled(userId, NotificationCategory.FINANCIAL,
                NotificationChannel.EMAIL)).isTrue();
    }

    @Test
    void financialNotificationsRespectAnExplicitOptOut() {
        when(repository.findByUserIdAndCategoryAndChannel(userId, NotificationCategory.FINANCIAL,
                NotificationChannel.SMS))
                .thenReturn(Optional.of(NotificationPreference.of(userId,
                        NotificationCategory.FINANCIAL, NotificationChannel.SMS, false)));

        assertThat(resolver.isEnabled(userId, NotificationCategory.FINANCIAL,
                NotificationChannel.SMS)).isFalse();
    }

    @Test
    void marketingNotificationsDefaultToDisabled() {
        when(repository.findByUserIdAndCategoryAndChannel(any(), any(), any()))
                .thenReturn(Optional.empty());

        // Opt-in, not opt-out -- no send logic exists for MARKETING in v1 regardless.
        assertThat(resolver.isEnabled(userId, NotificationCategory.MARKETING,
                NotificationChannel.EMAIL)).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=DatabaseNotificationPreferenceResolverTest
```

Expected: FAIL — the classes do not exist.

- [ ] **Step 3: Write the entity, repository, and resolver**

`backend/src/main/java/com/finora/notification/domain/NotificationPreference.java`:

```java
package com.finora.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** One user's opt-in/out for a category on a channel. Absent row means the category default. */
@Entity
@Table(name = "notification_preferences")
public class NotificationPreference {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationChannel channel;

    @Column(nullable = false)
    private boolean enabled;

    protected NotificationPreference() {
        // for JPA
    }

    private NotificationPreference(UUID userId, NotificationCategory category,
            NotificationChannel channel, boolean enabled) {
        this.userId = userId;
        this.category = category;
        this.channel = channel;
        this.enabled = enabled;
    }

    public static NotificationPreference of(UUID userId, NotificationCategory category,
            NotificationChannel channel, boolean enabled) {
        return new NotificationPreference(userId, category, channel, enabled);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public NotificationCategory getCategory() {
        return category;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
```

`backend/src/main/java/com/finora/notification/repository/NotificationPreferenceRepository.java`:

```java
package com.finora.notification.repository;

import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPreference;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferenceRepository
        extends JpaRepository<NotificationPreference, UUID> {

    Optional<NotificationPreference> findByUserIdAndCategoryAndChannel(UUID userId,
            NotificationCategory category, NotificationChannel channel);

    List<NotificationPreference> findByUserId(UUID userId);
}
```

`backend/src/main/java/com/finora/notification/api/DatabaseNotificationPreferenceResolver.java`:

```java
package com.finora.notification.api;

import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.repository.NotificationPreferenceRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves whether a user wants a category on a channel.
 *
 * <p>SECURITY is forcibly on and never consults the table: a user who has silenced security alerts
 * cannot be told their password changed, and that notification's absence is itself the security
 * problem. This resolves the open question the notification proposal left in section 2.3.
 */
@Component
public class DatabaseNotificationPreferenceResolver implements NotificationPreferenceResolver {

    private final NotificationPreferenceRepository repository;

    public DatabaseNotificationPreferenceResolver(NotificationPreferenceRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean isEnabled(UUID userId, NotificationCategory category,
            NotificationChannel channel) {
        if (category == NotificationCategory.SECURITY) {
            return true;
        }
        return repository.findByUserIdAndCategoryAndChannel(userId, category, channel)
                .map(preference -> preference.isEnabled())
                .orElseGet(() -> defaultFor(category));
    }

    /** MARKETING is opt-in; everything else is opt-out. */
    private boolean defaultFor(NotificationCategory category) {
        return category != NotificationCategory.MARKETING;
    }
}
```

- [ ] **Step 4: Pick the migration version, then write the migration**

```bash
git fetch origin && ls backend/src/main/resources/db/migration | sort -V | tail -5
```

Create `backend/src/main/resources/db/migration/V<next>__notification_preferences.sql`:

```sql
CREATE TABLE notification_preferences (
    id       UUID PRIMARY KEY,
    user_id  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category VARCHAR(32) NOT NULL,
    channel  VARCHAR(16) NOT NULL,
    enabled  BOOLEAN NOT NULL,
    UNIQUE (user_id, category, channel)
);

CREATE INDEX idx_notification_preferences_user ON notification_preferences (user_id);

COMMENT ON TABLE notification_preferences IS
    'Per-user opt-in/out. An absent row means the category default: MARKETING is opt-in, every '
    'other category is opt-out.';
COMMENT ON COLUMN notification_preferences.category IS
    'SECURITY rows are never consulted -- security notifications are forcibly on, because a user '
    'who silenced them could not be told their password changed. Rows may exist for SECURITY '
    'without effect.';
```

- [ ] **Step 5: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=DatabaseNotificationPreferenceResolverTest
```

Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/notification backend/src/main/resources/db/migration backend/src/test/java/com/finora/notification
git commit -m "feat(notification): add per-user notification preferences with forced security opt-in"
```

---

## Task 9: Device token registration with at-rest encryption

**Files:**
- Create: `backend/src/main/java/com/finora/notification/domain/DeviceToken.java`
- Create: `backend/src/main/java/com/finora/notification/repository/DeviceTokenRepository.java`
- Create: `backend/src/main/java/com/finora/notification/api/DeviceTokenService.java`
- Create: `backend/src/main/java/com/finora/controller/DeviceTokenController.java`
- Create: `backend/src/main/resources/db/migration/V<next>__device_tokens.sql`
- Test: `backend/src/test/java/com/finora/notification/api/DeviceTokenServiceTest.java`

**Interfaces:**
- Consumes: existing `com.finora.security.crypto.EncryptionService` — `EncryptedValue encrypt(String plaintext)`, `String decrypt(EncryptedValue value)`; `com.finora.security.crypto.EncryptedValue` — `record EncryptedValue(String keyId, String ciphertext)`.
- Produces: `DeviceTokenService.register(UUID userId, String platform, String rawToken)`; `DeviceTokenService.activeTokensFor(UUID userId)` returning `List<String>` (decrypted, for the push provider); `DeviceTokenService.revoke(UUID userId, String rawToken)`.

- [ ] **Step 1: Read the existing encryption chain first**

```bash
sed -n '1,80p' backend/src/main/java/com/finora/security/crypto/EncryptionService.java
sed -n '1,60p' backend/src/main/java/com/finora/integrations/google/GmailConnection.java
sed -n '1,40p' backend/src/main/resources/db/migration/V80__gmail_oauth_connections.sql
```

`GmailConnection` is the exact model: a `encrypted_*` column paired with an `encryption_key_id` column, a `credential()` method reassembling them into an `EncryptedValue`, a `storeCredential(EncryptedValue)` setter, and **no getter returning a decrypted value** — decryption requires the `EncryptionService`, keeping "who can read this" a wiring question.

- [ ] **Step 2: Write the failing test**

Create `backend/src/test/java/com/finora/notification/api/DeviceTokenServiceTest.java`:

```java
package com.finora.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finora.notification.domain.DeviceToken;
import com.finora.notification.repository.DeviceTokenRepository;
import com.finora.security.crypto.EncryptedValue;
import com.finora.security.crypto.EncryptionService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeviceTokenServiceTest {

    private DeviceTokenRepository repository;
    private EncryptionService encryptionService;
    private DeviceTokenService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(DeviceTokenRepository.class);
        encryptionService = mock(EncryptionService.class);
        service = new DeviceTokenService(repository, encryptionService);

        when(repository.save(any(DeviceToken.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encryptionService.encrypt(anyString()))
                .thenAnswer(inv -> new EncryptedValue("v1", "cipher:" + inv.getArgument(0)));
        when(encryptionService.decrypt(any()))
                .thenAnswer(inv -> ((EncryptedValue) inv.getArgument(0)).ciphertext()
                        .replace("cipher:", ""));
    }

    @Test
    void register_storesTheTokenEncryptedWithItsKeyId() {
        when(repository.findByUserIdAndTokenFingerprint(any(), anyString()))
                .thenReturn(Optional.empty());

        DeviceToken saved = service.register(userId, "ANDROID", "fcm-token-abc");

        // The raw token must never be what lands in the column.
        assertThat(saved.getEncryptedToken()).isEqualTo("cipher:fcm-token-abc");
        assertThat(saved.getEncryptionKeyId()).isEqualTo("v1");
    }

    @Test
    void register_isIdempotentForARepeatedTokenOnTheSameDevice() {
        DeviceToken existing = DeviceToken.register(userId, "ANDROID",
                new EncryptedValue("v1", "cipher:fcm-token-abc"), "fingerprint",
                java.time.Instant.now());
        when(repository.findByUserIdAndTokenFingerprint(any(), anyString()))
                .thenReturn(Optional.of(existing));

        DeviceToken saved = service.register(userId, "ANDROID", "fcm-token-abc");

        assertThat(saved).isSameAs(existing);
        assertThat(saved.getRevokedAt()).isNull();
    }

    @Test
    void activeTokensFor_returnsDecryptedTokensForSending() {
        DeviceToken token = DeviceToken.register(userId, "ANDROID",
                new EncryptedValue("v1", "cipher:fcm-token-abc"), "fingerprint",
                java.time.Instant.now());
        when(repository.findByUserIdAndRevokedAtIsNull(userId)).thenReturn(List.of(token));

        assertThat(service.activeTokensFor(userId)).containsExactly("fcm-token-abc");
    }

    @Test
    void activeTokensFor_skipsATokenThatCannotBeDecrypted() {
        DeviceToken token = DeviceToken.register(userId, "ANDROID",
                new EncryptedValue("v0", "unreadable"), "fingerprint", java.time.Instant.now());
        when(repository.findByUserIdAndRevokedAtIsNull(userId)).thenReturn(List.of(token));
        when(encryptionService.decrypt(any()))
                .thenThrow(new com.finora.security.crypto.EncryptionException("wrong key"));

        // One undecryptable row must not stop every other device from getting the push.
        assertThat(service.activeTokensFor(userId)).isEmpty();
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=DeviceTokenServiceTest
```

Expected: FAIL — `DeviceToken`, `DeviceTokenRepository`, `DeviceTokenService` do not exist.

- [ ] **Step 4: Write the entity**

`backend/src/main/java/com/finora/notification/domain/DeviceToken.java`:

```java
package com.finora.notification.domain;

import com.finora.security.crypto.EncryptedValue;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A device's push token, encrypted at rest.
 *
 * <p>Encrypted, not hashed -- and this is the whole point of the distinction. A password is only
 * ever compared, so a one-way hash works. The dispatcher must hand FCM/APNs the actual token on
 * every send, so it must be recoverable; a hash would make this table useless for its own purpose.
 *
 * <p>Follows GmailConnection exactly: ciphertext and key id stored as a pair, reassembled through
 * {@link #credential()}, with no getter that returns a decrypted value -- reading it requires the
 * EncryptionService, which keeps "who can read this" a wiring question rather than a field
 * visibility question.
 */
@Entity
@Table(name = "device_tokens")
public class DeviceToken {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 16)
    private String platform;

    @Column(name = "encrypted_token", nullable = false, columnDefinition = "TEXT")
    private String encryptedToken;

    @Column(name = "encryption_key_id", nullable = false, length = 64)
    private String encryptionKeyId;

    /**
     * SHA-256 of the raw token, for equality lookups only. The ciphertext cannot be matched
     * directly because AES-GCM uses a fresh random IV per call, so the same token encrypts to a
     * different string every time.
     */
    @Column(name = "token_fingerprint", nullable = false, length = 64)
    private String tokenFingerprint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected DeviceToken() {
        // for JPA
    }

    private DeviceToken(UUID userId, String platform, EncryptedValue token, String fingerprint,
            Instant now) {
        this.userId = userId;
        this.platform = platform;
        this.encryptedToken = token.ciphertext();
        this.encryptionKeyId = token.keyId();
        this.tokenFingerprint = fingerprint;
        this.createdAt = now;
        this.lastSeenAt = now;
    }

    public static DeviceToken register(UUID userId, String platform, EncryptedValue token,
            String fingerprint, Instant now) {
        return new DeviceToken(userId, platform, token, fingerprint, now);
    }

    /** Reassembles the stored halves into the shape EncryptionService.decrypt takes. */
    public EncryptedValue credential() {
        return new EncryptedValue(encryptionKeyId, encryptedToken);
    }

    public void touch(Instant now) {
        this.lastSeenAt = now;
        this.revokedAt = null;
    }

    /** Soft revoke on logout or uninstall detection -- never a hard delete, so the trail survives. */
    public void revoke(Instant now) {
        this.revokedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getPlatform() {
        return platform;
    }

    public String getEncryptedToken() {
        return encryptedToken;
    }

    public String getEncryptionKeyId() {
        return encryptionKeyId;
    }

    public String getTokenFingerprint() {
        return tokenFingerprint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
```

- [ ] **Step 5: Write the repository and service**

`backend/src/main/java/com/finora/notification/repository/DeviceTokenRepository.java`:

```java
package com.finora.notification.repository;

import com.finora.notification.domain.DeviceToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    Optional<DeviceToken> findByUserIdAndTokenFingerprint(UUID userId, String tokenFingerprint);

    List<DeviceToken> findByUserIdAndRevokedAtIsNull(UUID userId);
}
```

`backend/src/main/java/com/finora/notification/api/DeviceTokenService.java`:

```java
package com.finora.notification.api;

import com.finora.notification.domain.DeviceToken;
import com.finora.notification.repository.DeviceTokenRepository;
import com.finora.security.crypto.EncryptionException;
import com.finora.security.crypto.EncryptionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registers, resolves, and revokes device push tokens. Never logs a raw token. */
@Service
public class DeviceTokenService {

    private static final Logger log = LoggerFactory.getLogger(DeviceTokenService.class);

    private final DeviceTokenRepository repository;
    private final EncryptionService encryptionService;

    public DeviceTokenService(DeviceTokenRepository repository,
            EncryptionService encryptionService) {
        this.repository = repository;
        this.encryptionService = encryptionService;
    }

    @Transactional
    public DeviceToken register(UUID userId, String platform, String rawToken) {
        String fingerprint = fingerprint(rawToken);
        Instant now = Instant.now();
        Optional<DeviceToken> existing =
                repository.findByUserIdAndTokenFingerprint(userId, fingerprint);
        if (existing.isPresent()) {
            existing.get().touch(now);
            return repository.save(existing.get());
        }
        return repository.save(DeviceToken.register(userId, platform,
                encryptionService.encrypt(rawToken), fingerprint, now));
    }

    /** Decrypted tokens for the push providers. One unreadable row must not silence a whole user. */
    @Transactional(readOnly = true)
    public List<String> activeTokensFor(UUID userId) {
        List<String> tokens = new ArrayList<>();
        for (DeviceToken token : repository.findByUserIdAndRevokedAtIsNull(userId)) {
            try {
                tokens.add(encryptionService.decrypt(token.credential()));
            } catch (EncryptionException e) {
                log.error("Cannot decrypt device token {} -- skipping it. Check "
                        + "FINORA_ENCRYPTION_KEY against the runbook.", token.getId());
            }
        }
        return tokens;
    }

    @Transactional
    public void revoke(UUID userId, String rawToken) {
        repository.findByUserIdAndTokenFingerprint(userId, fingerprint(rawToken))
                .ifPresent(token -> {
                    token.revoke(Instant.now());
                    repository.save(token);
                });
    }

    /**
     * SHA-256 of the raw token. Needed because AES-GCM's fresh per-call IV means the same token
     * never encrypts to the same ciphertext, so the encrypted column cannot be matched on.
     */
    private String fingerprint(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
```

- [ ] **Step 6: Write the registration controller**

`backend/src/main/java/com/finora/controller/DeviceTokenController.java` — a `POST /api/v1/device-tokens` taking `{platform, token}` for the authenticated user and a `DELETE` to revoke. **Before writing it**, read an existing authenticated (non-admin) controller to copy how the current user's id is resolved — do not invent a mechanism:

```bash
grep -rn "getCurrentUserId\|@AuthenticationPrincipal" backend/src/main/java/com/finora/controller --include=*.java | head -10
```

Follow whatever that shows. The request body must never be logged.

- [ ] **Step 7: Pick the migration version, then write the migration**

```bash
git fetch origin && ls backend/src/main/resources/db/migration | sort -V | tail -5
```

Create `backend/src/main/resources/db/migration/V<next>__device_tokens.sql`:

```sql
CREATE TABLE device_tokens (
    id                UUID PRIMARY KEY,
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    platform          VARCHAR(16) NOT NULL,
    encrypted_token   TEXT NOT NULL,       -- AES-256-GCM ciphertext from EncryptionService (ADR-007),
                                            -- base64 of [IV || ciphertext+tag].
    encryption_key_id VARCHAR(64) NOT NULL, -- Which key encrypted the value above. Without this, a key
                                            -- rotation cannot tell which rows are already migrated.
    token_fingerprint VARCHAR(64) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    last_seen_at      TIMESTAMPTZ NOT NULL,
    revoked_at        TIMESTAMPTZ,
    UNIQUE (user_id, token_fingerprint)
);

CREATE INDEX idx_device_tokens_active
    ON device_tokens (user_id)
    WHERE revoked_at IS NULL;

COMMENT ON TABLE device_tokens IS
    'Push tokens, encrypted at rest. Encrypted and NOT hashed on purpose: the dispatcher hands the '
    'real token to FCM/APNs on every send, so it must be recoverable -- a one-way hash would make '
    'this table useless for its own purpose.';
COMMENT ON COLUMN device_tokens.token_fingerprint IS
    'SHA-256 of the raw token, for equality lookups only. AES-GCM uses a fresh random IV per call, '
    'so the same token never encrypts to the same ciphertext and the encrypted column cannot be '
    'matched on directly.';
COMMENT ON COLUMN device_tokens.revoked_at IS
    'Soft revoke on logout or uninstall detection, never a hard delete, so the trail survives.';
```

- [ ] **Step 8: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=DeviceTokenServiceTest
```

Expected: PASS (4 tests).

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/finora/notification backend/src/main/java/com/finora/controller/DeviceTokenController.java backend/src/main/resources/db/migration backend/src/test/java/com/finora/notification
git commit -m "feat(notification): add encrypted device token registration"
```

---

## Task 10: FCM push provider (Android)

**Files:**
- Create: `backend/src/main/java/com/finora/notification/provider/PushNotificationProvider.java`
- Create: `backend/src/main/java/com/finora/notification/provider/FcmPushProvider.java`
- Create: `backend/src/main/java/com/finora/notification/provider/NoOpPushProvider.java`
- Create: `backend/src/main/java/com/finora/config/PushConfig.java`
- Test: `backend/src/test/java/com/finora/notification/provider/FcmPushProviderTest.java`

**Interfaces:**
- Consumes: `NotificationChannelProvider`/`ChannelSendResult` from Task 3; `DeviceTokenService.activeTokensFor(UUID)` from Task 9.
- Produces: a `NotificationChannelProvider` bean for `NotificationChannel.PUSH`, selected in `PushConfig` by a runtime credential check (real provider when configured, `NoOpPushProvider` otherwise) — the `EmailConfig`/`SmsConfig` pattern, **never** `@Profile`.

- [ ] **Step 1: Confirm the Firebase credential situation before writing code**

The proposal (§2.2, §6) notes FCM should reuse the Firebase project already configured for phone auth (`GOOGLE_APPLICATION_CREDENTIALS`) and flags an open item: *"FCM reuses the existing Firebase project — confirm no conflict with the phone-auth service account's existing scopes before implementation."*

```bash
sed -n '1,80p' backend/src/main/java/com/finora/config/FirebaseConfig.java
grep -rn "firebase-admin" backend/pom.xml
```

If `firebase-admin` is already a dependency and initialized, reuse that `FirebaseApp`. If the service account lacks FCM scope, **stop and report** — that is a credential/console change only the project owner can make, not something to work around in code.

- [ ] **Step 2: Write the failing test**

Create `backend/src/test/java/com/finora/notification/provider/FcmPushProviderTest.java`:

```java
package com.finora.notification.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finora.notification.api.DeviceTokenService;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FcmPushProviderTest {

    private DeviceTokenService deviceTokenService;
    private FcmMessageSender messageSender;
    private FcmPushProvider provider;

    @BeforeEach
    void setUp() {
        deviceTokenService = mock(DeviceTokenService.class);
        messageSender = mock(FcmMessageSender.class);
        provider = new FcmPushProvider(deviceTokenService, messageSender);
    }

    private Notification notification() {
        return Notification.create(UUID.randomUUID(), NotificationType.IMPORT_STATEMENT_READY,
                NotificationCategory.FINANCIAL, NotificationChannel.PUSH,
                NotificationPriority.NORMAL, "K1:PUSH", "Statement ready", "Your import finished.",
                Instant.now());
    }

    @Test
    void channel_isPush() {
        assertThat(provider.channel()).isEqualTo(NotificationChannel.PUSH);
    }

    @Test
    void send_failsWhenTheUserHasNoRegisteredDevice() {
        when(deviceTokenService.activeTokensFor(any())).thenReturn(List.of());

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.detail()).isEqualTo("no registered device");
    }

    @Test
    void send_succeedsWhenAtLeastOneDeviceAccepts() {
        when(deviceTokenService.activeTokensFor(any())).thenReturn(List.of("tokenA", "tokenB"));
        when(messageSender.send(any(), any(), any())).thenReturn(false).thenReturn(true);

        assertThat(provider.send(notification()).success()).isTrue();
    }

    @Test
    void send_neverLeaksARawTokenIntoTheResultDetail() {
        when(deviceTokenService.activeTokensFor(any())).thenReturn(List.of("secret-token"));
        when(messageSender.send(any(), any(), any())).thenReturn(false);

        // The detail is persisted to notification_logs, which admins read.
        assertThat(provider.send(notification()).detail()).doesNotContain("secret-token");
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=FcmPushProviderTest
```

Expected: FAIL — the classes do not exist.

- [ ] **Step 4: Write the sender seam, the provider, and the no-op**

`backend/src/main/java/com/finora/notification/provider/FcmMessageSender.java` — a one-method seam so the provider is unit-testable without a live Firebase:

```java
package com.finora.notification.provider;

/** One-method seam over the Firebase SDK, so FcmPushProvider is testable without a live project. */
public interface FcmMessageSender {
    /** @return true when FCM accepted the message for this token. Must not throw. */
    boolean send(String deviceToken, String title, String body);
}
```

`backend/src/main/java/com/finora/notification/provider/FcmPushProvider.java`:

```java
package com.finora.notification.provider;

import com.finora.notification.api.DeviceTokenService;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationChannel;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers to every registered device for a user, succeeding if any one accepts.
 *
 * <p>Not a {@code @Component}: PushConfig selects between this and NoOpPushProvider by a runtime
 * credential check, matching EmailConfig/SmsConfig. This codebase does not use {@code @Profile}
 * for provider selection.
 */
public class FcmPushProvider implements NotificationChannelProvider {

    private static final Logger log = LoggerFactory.getLogger(FcmPushProvider.class);
    private static final String PROVIDER_NAME = "fcm";

    private final DeviceTokenService deviceTokenService;
    private final FcmMessageSender messageSender;

    public FcmPushProvider(DeviceTokenService deviceTokenService, FcmMessageSender messageSender) {
        this.deviceTokenService = deviceTokenService;
        this.messageSender = messageSender;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.PUSH;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public ChannelSendResult send(Notification notification) {
        try {
            List<String> tokens = deviceTokenService.activeTokensFor(notification.getUserId());
            if (tokens.isEmpty()) {
                return ChannelSendResult.failure(PROVIDER_NAME, "no registered device");
            }
            int accepted = 0;
            for (String token : tokens) {
                if (messageSender.send(token, notification.getTitle(), notification.getMessage())) {
                    accepted++;
                }
            }
            // Counts only -- a raw token must never reach the detail, which is persisted to
            // notification_logs and read by admins.
            return accepted > 0
                    ? ChannelSendResult.success(PROVIDER_NAME,
                            accepted + " of " + tokens.size() + " devices accepted")
                    : ChannelSendResult.failure(PROVIDER_NAME,
                            "all " + tokens.size() + " devices rejected");
        } catch (RuntimeException e) {
            log.error("Push notification {} could not be sent", notification.getId(), e);
            return ChannelSendResult.failure(PROVIDER_NAME,
                    "exception: " + e.getClass().getSimpleName());
        }
    }
}
```

`backend/src/main/java/com/finora/notification/provider/NoOpPushProvider.java`:

```java
package com.finora.notification.provider;

import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fallback when no push credentials are configured.
 *
 * <p>Logs the notification id only -- never the title, body, token, or user identifier. This is a
 * deliberate correction of a real precedent in this codebase: NoOpSmsProvider once logged unmasked
 * phone numbers and amounts at INFO in production because only the real provider had adopted
 * masking. A no-op is not exempt from redaction.
 */
public class NoOpPushProvider implements NotificationChannelProvider {

    private static final Logger log = LoggerFactory.getLogger(NoOpPushProvider.class);

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.PUSH;
    }

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public ChannelSendResult send(Notification notification) {
        log.info("Push is not configured; would have sent notification {}", notification.getId());
        return ChannelSendResult.failure("noop-push", "push provider not configured");
    }
}
```

`backend/src/main/java/com/finora/config/PushConfig.java` — mirror `EmailConfig` exactly:

```java
package com.finora.config;

import com.finora.notification.api.DeviceTokenService;
import com.finora.notification.provider.FcmMessageSender;
import com.finora.notification.provider.FcmPushProvider;
import com.finora.notification.provider.NoOpPushProvider;
import com.finora.notification.provider.NotificationChannelProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the push provider by a runtime credential check, matching EmailConfig and SmsConfig.
 * Not @Profile -- this codebase selects providers on configuration presence, not on profile.
 */
@Configuration
public class PushConfig {

    @Bean
    public NotificationChannelProvider pushNotificationProvider(
            DeviceTokenService deviceTokenService, ObjectProvider<FcmMessageSender> messageSender) {
        FcmMessageSender sender = messageSender.getIfAvailable();
        if (sender != null) {
            return new FcmPushProvider(deviceTokenService, sender);
        }
        return new NoOpPushProvider();
    }
}
```

The real `FcmMessageSender` implementation (a `@Component` that only registers itself when Firebase credentials are present) is written in this step against whatever Step 1 established about the existing `FirebaseApp` wiring.

- [ ] **Step 5: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=FcmPushProviderTest
```

Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/notification/provider backend/src/main/java/com/finora/config/PushConfig.java backend/src/test/java/com/finora/notification/provider
git commit -m "feat(notification): add FCM push provider with no-op fallback"
```

---

## Task 11: APNs push provider (iOS)

**Files:**
- Create: `backend/src/main/java/com/finora/notification/provider/ApnsMessageSender.java`
- Modify: `backend/src/main/java/com/finora/notification/provider/FcmPushProvider.java` → generalize to route by `DeviceToken.platform`
- Modify: `backend/src/main/java/com/finora/config/PushConfig.java`
- Test: `backend/src/test/java/com/finora/notification/provider/PushRoutingTest.java`

**Interfaces:**
- Consumes: everything from Task 10.
- Produces: push delivery that routes each token to FCM or APNs by its stored `platform`.

**Blocking prerequisite:** APNs needs an Apple push certificate/key that does not exist yet (the proposal §2.2 calls this out as "new credential/certificate setup required"). **This is an owner task, not an engineering one.** Before starting: confirm the APNs key is provisioned. If not, **stop and report** — implement the routing seam (so Android ships) and leave APNs behind its unconfigured no-op rather than blocking Task 12.

- [ ] **Step 1: Change `activeTokensFor` to return platform-tagged tokens**

`DeviceTokenService.activeTokensFor(UUID)` currently returns `List<String>`. Change it to return `List<DeviceTokenService.ResolvedToken>` where:

```java
    /** A decrypted token plus the platform that decides which gateway sends it. */
    public record ResolvedToken(String token, String platform) {}
```

Update `DeviceTokenServiceTest` accordingly (the existing assertions become `extracting(ResolvedToken::token)`).

- [ ] **Step 2: Write the failing routing test**

Create `backend/src/test/java/com/finora/notification/provider/PushRoutingTest.java`:

```java
package com.finora.notification.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.notification.api.DeviceTokenService;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PushRoutingTest {

    private DeviceTokenService deviceTokenService;
    private FcmMessageSender fcm;
    private ApnsMessageSender apns;
    private FcmPushProvider provider;

    @BeforeEach
    void setUp() {
        deviceTokenService = mock(DeviceTokenService.class);
        fcm = mock(FcmMessageSender.class);
        apns = mock(ApnsMessageSender.class);
        provider = new FcmPushProvider(deviceTokenService, fcm, apns);
        when(fcm.send(any(), any(), any())).thenReturn(true);
        when(apns.send(any(), any(), any())).thenReturn(true);
    }

    private Notification notification() {
        return Notification.create(UUID.randomUUID(), NotificationType.IMPORT_STATEMENT_READY,
                NotificationCategory.FINANCIAL, NotificationChannel.PUSH,
                NotificationPriority.NORMAL, "K1:PUSH", "Title", "Body", Instant.now());
    }

    @Test
    void androidTokensGoToFcmOnly() {
        when(deviceTokenService.activeTokensFor(any())).thenReturn(
                List.of(new DeviceTokenService.ResolvedToken("tokenA", "ANDROID")));

        provider.send(notification());

        verify(fcm).send(eq("tokenA"), any(), any());
        verify(apns, never()).send(any(), any(), any());
    }

    @Test
    void iosTokensGoToApnsOnly() {
        when(deviceTokenService.activeTokensFor(any())).thenReturn(
                List.of(new DeviceTokenService.ResolvedToken("tokenI", "IOS")));

        provider.send(notification());

        verify(apns).send(eq("tokenI"), any(), any());
        verify(fcm, never()).send(any(), any(), any());
    }

    @Test
    void aMixedDeviceSetReachesBothGateways() {
        when(deviceTokenService.activeTokensFor(any())).thenReturn(List.of(
                new DeviceTokenService.ResolvedToken("tokenA", "ANDROID"),
                new DeviceTokenService.ResolvedToken("tokenI", "IOS")));

        assertThat(provider.send(notification()).success()).isTrue();
        verify(fcm).send(eq("tokenA"), any(), any());
        verify(apns).send(eq("tokenI"), any(), any());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=PushRoutingTest
```

Expected: FAIL — `ApnsMessageSender` does not exist and `FcmPushProvider` has no three-arg constructor.

- [ ] **Step 4: Add the APNs seam and route by platform**

`backend/src/main/java/com/finora/notification/provider/ApnsMessageSender.java` — same one-method shape as `FcmMessageSender`. In `FcmPushProvider`, take both senders and dispatch on `ResolvedToken.platform()`: `"IOS"` → APNs, everything else → FCM. Keep the "succeed if any device accepts" rule and the counts-only detail string unchanged. Rename the class to `PlatformRoutingPushProvider` if the FCM-specific name now misleads — update `PushConfig` and both tests if you do.

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd backend && ./mvnw test -Dtest='PushRoutingTest,FcmPushProviderTest,DeviceTokenServiceTest'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/notification backend/src/main/java/com/finora/config/PushConfig.java backend/src/test/java/com/finora/notification
git commit -m "feat(notification): route push delivery to FCM or APNs by device platform"
```

---

## Task 12: Admin notification dashboard

**Files:**
- Create: `backend/src/main/java/com/finora/controller/AdminNotificationController.java`
- Create: `backend/src/main/java/com/finora/service/AdminNotificationService.java`
- Create: `backend/src/main/resources/db/migration/V<next>__notification_admin_permission.sql`
- Create: `admin-portal/src/pages/Notifications.tsx`
- Modify: the admin-portal route table (find it: `grep -rn "LearningQueue" admin-portal/src --include=*.tsx`)
- Test: `backend/src/test/java/com/finora/service/AdminNotificationServiceTest.java`

**Interfaces:**
- Consumes: `NotificationRepository`, `NotificationLogRepository` from Tasks 1 and 4.
- Produces: `GET /api/v1/admin/notifications` (paged list, filter by status), `GET /api/v1/admin/notifications/summary` (sent/failed counts, by channel), `GET /api/v1/admin/notifications/{id}` (detail + attempt log).

**Scope guard:** the proposal (§2.5, §4) limits this to a list plus basic send-outcome counts. **No trend charts, no engagement scoring, no open rates** — there is no volume yet to make analytics meaningful.

- [ ] **Step 1: Read the reference implementation**

```bash
sed -n '1,120p' backend/src/main/java/com/finora/controller/AdminLearningQueueController.java
sed -n '1,60p' admin-portal/src/pages/LearningQueue.tsx
sed -n '1,40p' backend/src/main/resources/db/migration/V63__learning_queue_admin.sql
```

Mirror these. Note `LearningQueue.tsx`'s stated principle: show names not UUIDs, and never re-derive server-owned state-machine rules client-side.

- [ ] **Step 2: Pick the migration version, then seed the permission**

```bash
git fetch origin && ls backend/src/main/resources/db/migration | sort -V | tail -5
```

Create `backend/src/main/resources/db/migration/V<next>__notification_admin_permission.sql`:

```sql
INSERT INTO permissions (name, description) VALUES
    ('NOTIFICATION_MANAGE',
     'View the notification delivery dashboard and inspect send failures. Read-only: it grants no '
     'ability to send a notification, and no user or merchant management capability.');

-- ADMIN and SUPER_ADMIN, matching every permission added since V24. SUPER_ADMIN needs its own
-- explicit grant -- its V16 "every permission" catch-all was a one-time snapshot, not a standing
-- rule, so a new permission is not picked up by it automatically.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'SUPER_ADMIN') AND p.name = 'NOTIFICATION_MANAGE';
```

**Both inserts are mandatory** — a permission with no `role_permissions` row grants nothing to anyone.

- [ ] **Step 3: Write the failing service test**

Create `backend/src/test/java/com/finora/service/AdminNotificationServiceTest.java` covering: the summary returns sent/failed counts; the list filters by status; detail includes the attempt log newest-first; a missing id produces the codebase's standard not-found `ApiException` (check what `AdminLearningQueueService` throws and match it exactly).

- [ ] **Step 4: Run test to verify it fails, then implement**

```bash
cd backend && ./mvnw test -Dtest=AdminNotificationServiceTest
```

Then write `AdminNotificationService` (`@Transactional(readOnly = true)`, no mutations — this dashboard is read-only) and `AdminNotificationController` with `@RequestMapping("/api/v1/admin/notifications")` and class-level `@PreAuthorize("hasAuthority('NOTIFICATION_MANAGE')")`.

- [ ] **Step 5: Build the admin page**

`admin-portal/src/pages/Notifications.tsx`, copying `LearningQueue.tsx`'s structure: `AdminLayout` + `RequirePermission` + `DataTable`/`Pagination`, React Query, status filter chips. Register the route alongside the learning-queue route.

- [ ] **Step 6: Verify in the browser**

Start the admin portal and confirm the page renders, the filter chips work, and a seeded failed notification shows its provider/error/time. Do not report this task complete on a passing test suite alone — this is a UI surface.

- [ ] **Step 7: Run the full backend suite**

```bash
cd backend && ./mvnw test
```

Expected: PASS. If a failure is unrelated to this diff, **surface it rather than fixing it** — out-of-scope CI failures get flagged, not unilaterally repaired.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/finora backend/src/main/resources/db/migration backend/src/test/java/com/finora admin-portal/src
git commit -m "feat(notification): add read-only admin notification dashboard"
```

---

## Task 13: OTP verification attempt cap

**Files:**
- Modify: `backend/src/main/java/com/finora/service/PasswordChangeService.java`
- Modify: `backend/src/main/java/com/finora/entity/PasswordChangeSession.java`
- Create: `backend/src/main/resources/db/migration/V<next>__password_change_session_otp_attempts.sql`
- Test: `backend/src/test/java/com/finora/service/PasswordChangeServiceTest.java` (extend)

**Interfaces:**
- Consumes: nothing from Tasks 1–12 — this is independent and may be done at any point.
- Produces: a bounded OTP retry cap on `PasswordChangeService.verifyOtp`.

**Context:** proposal §2.4, folded into this work rather than jumping the queue. Today OTP verification is bounded only by the shared `RateLimitFilter` and the 15-minute session expiry — there is no attempt-specific cap. Mirror the login lockout pattern already in `AuthService`.

- [ ] **Step 1: Read the two existing patterns**

```bash
grep -n "verifyOtp" backend/src/main/java/com/finora/service/PasswordChangeService.java
grep -n "failedLoginAttempts\|lockout\|lockedUntil" backend/src/main/java/com/finora/service/AuthService.java
```

Copy `AuthService`'s lockout shape rather than inventing a second vocabulary.

- [ ] **Step 2: Write the failing test**

Extend `PasswordChangeServiceTest` with: a wrong OTP increments the attempt counter; the Nth wrong attempt invalidates the session; a correct OTP resets the counter; an already-exhausted session rejects even a correct OTP.

- [ ] **Step 3: Run to verify it fails, implement, re-run**

```bash
cd backend && ./mvnw test -Dtest=PasswordChangeServiceTest
```

Add an `otp_attempt_count` column (pick the migration version fresh — `git fetch origin && ls backend/src/main/resources/db/migration | sort -V | tail -5`), an entity counter with the same cap constant style `AuthService` uses, and the check in `verifyOtp`. Ensure the audit trail records an exhausted-attempts outcome, matching how `AuthService` audits a lockout.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/finora backend/src/main/resources/db/migration backend/src/test/java/com/finora
git commit -m "feat(auth): cap OTP verification attempts on password-change sessions"
```

---

## Phase A completion checklist

Before declaring Phase A done and starting Phase B:

- [ ] `cd backend && ./mvnw test` passes in full.
- [ ] `NotificationService.request(...)` is callable and writes rows in the caller's transaction — **this is Phase B's hard dependency.**
- [ ] `NotificationDispatcher` delivers on EMAIL and PUSH at minimum (SMS and APNs may remain unconfigured no-ops if credentials are not provisioned; say so explicitly rather than implying they work).
- [ ] `NotificationType.IMPORT_STATEMENT_READY` has active template rows for EMAIL and PUSH — Phase B needs both.
- [ ] Admin dashboard renders and shows real delivery outcomes, verified in a browser.
- [ ] Every new migration got a freshly-checked version number, and `git fetch origin && ls backend/src/main/resources/db/migration | sort -V | tail -5` shows no duplicate versions.
- [ ] Self-review the full diff for bugs and gaps before opening the PR.

---

## Self-Review Notes

Checked against the spec and the frozen proposal:

- **Spec coverage:** proposal §2.1 (module, outbox, dispatcher, templates, priority) → Tasks 1–3, 7; §2.2 (push, device tokens, encryption) → Tasks 9–11; §2.3 (preferences) → Task 8; §2.4 (OTP cap, delivery-status capture) → Tasks 13 and 4; §2.5 (logs, lifecycle, idempotency, retry, admin view) → Tasks 1, 4, 12.
- **Open questions resolved in-plan:** the "forcibly on vs defaulted on" question for SECURITY (Task 8, decided: forcibly on, with rationale) and the retry backoff schedule (Task 1, decided: 2^n minutes, matching `MerchantLearningEvent`). Two remain owner-blocked and are marked **stop and report** rather than guessed: the Firebase service-account FCM scope (Task 10 Step 1) and the APNs certificate (Task 11 prerequisite).
- **Deliberately deferred, matching the proposal:** `DELIVERED`/`READ` states, webhook signature verification (no webhook endpoint exists to secure yet), i18n, marketing send logic, in-app inbox UI.
- **Known soft spots for the implementer:** Tasks 5, 6, and 9 each open with a "read the existing type first" step because the exact constructor shapes of `EmailMessage`, `SmsRequest`, and the current-user resolution helper were not read in full at plan-writing time. The surrounding structure is correct; adapt the construction lines to what you find rather than forcing the code as written.
