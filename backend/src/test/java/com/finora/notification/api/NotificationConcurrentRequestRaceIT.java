package com.finora.notification.api;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.repository.NotificationRepository;
import com.finora.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * {@code NotificationRepository#insertIfAbsent}'s {@code ON CONFLICT DO NOTHING} replaced a
 * {@code saveAndFlush} + {@code catch(DataIntegrityViolationException)} guard that was tried first
 * and did not survive contact with a genuine race -- see this codebase's own prior instance of the
 * identical shape of bug, {@code MerchantNormalizationEngine.addAlias}'s "Bug fix, second" doc
 * comment and {@link com.finora.service.MerchantConcurrentAliasRaceIT}, which this class is modeled
 * on directly. In short: once any statement in an open Postgres transaction fails, every LATER
 * statement on it -- a plain {@code SELECT} included -- fails with {@code current transaction is
 * aborted, commands ignored until end of transaction block} (SQLSTATE {@code 25P02}) until
 * {@code COMMIT} or {@code ROLLBACK}. Catching the translated exception in Java does not undo that;
 * only never raising it in the first place does, which is what {@code ON CONFLICT DO NOTHING} gets
 * for free.
 *
 * <p>{@code NotificationService.request()} is deliberately NOT {@code @Transactional} -- it must
 * join the CALLER's ambient transaction, the same way {@code MerchantNormalizationEngine.resolve()}
 * does for {@code addAlias()} on the real call path ({@code ImportService.confirm} looping
 * {@code resolve()} once per staged row). This test supplies that ambient transaction itself via the
 * shared {@link TransactionTemplate} bean ({@code BackgroundWorkConfig.transactionTemplate}, default
 * {@code REQUIRED} propagation), the same tool {@code MerchantLearningConfirmRaceIT} uses to control
 * transaction boundaries precisely around a method that does not open its own.
 *
 * <h2>Why this test does not use a {@code CyclicBarrier}</h2>
 *
 * <p>{@code MerchantLearningConfirmRaceIT} answered this for the same shape of bug and left the
 * answer in its own class comment: a {@code CyclicBarrier} on the two {@code request()} calls would
 * be racing the race -- it would pass or fail on scheduler luck. This test uses the same
 * deterministic technique {@code MerchantConcurrentAliasRaceIT} does: a {@code @MockitoSpyBean} hook
 * that pauses the first caller AFTER its insert has been issued (and is therefore genuinely holding
 * the row lock behind {@code UNIQUE(notification_key)}) but BEFORE its transaction commits, so the
 * second caller's insert is REALLY blocked at the database on that lock, not merely scheduled to run
 * around the same time.
 *
 * <h2>What's pinned here</h2>
 *
 * <p>Two genuinely-concurrent requests for the same {@code notificationKey} must both survive:
 * neither caller's transaction may fail, {@code notifications} ends up with exactly one row for that
 * key -- never two, never zero -- and, critically, EACH caller's own ambient transaction (win or
 * lose) must still be able to run a further, unrelated statement and commit afterwards. That last
 * property is the one the old {@code saveAndFlush} + catch guard could not deliver: a caught
 * exception does not un-poison the transaction it happened in.
 */
class NotificationConcurrentRequestRaceIT extends AbstractIntegrationTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /** Real behaviour by default. Spied only to pause the first writer right after its insert is
     *  issued (so the row lock behind {@code UNIQUE(notification_key)} is held for real) and before
     *  its transaction commits -- see the class comment for why this replaces a
     *  {@code CyclicBarrier}.
     *
     *  <p>The stub below does NOT delegate to {@code invocation.callRealMethod()} -- the bean
     *  Spring wires here is a dynamic proxy over {@code SimpleJpaRepository}, not a concrete class,
     *  and Mockito cannot invoke a "real" method through a spy of an interface proxy ("Cannot call
     *  abstract real method on java object"). The stub instead issues the identical native SQL
     *  {@link NotificationRepository#insertIfAbsent} runs, directly through the
     *  {@code EntityManager}, so the row lock it takes (and the blocking behaviour the second caller
     *  depends on) is the real thing, not a simulation. Kept in sync by hand with that method's own
     *  SQL, the same tradeoff its doc comment names. */
    @MockitoSpyBean
    private NotificationRepository notificationRepository;

    private static final String FIRST_THREAD = "notification-race-first";

    private UUID newUser() {
        User user = new User();
        user.setEmail("notification-race-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Notification Race IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user).getId();
    }

    /**
     * Confirms {@code insertIfAbsent}'s {@code RETURNING id} actually maps to {@code Optional<UUID>}
     * before trusting it inside a concurrency test, where a silent mapping failure (e.g. an always-
     * empty result, or a mapping exception) would be indistinguishable from a genuine race failure.
     * A fresh key must return a present id on its first insert; the identical call for the SAME key
     * afterwards -- no concurrency involved, just a plain repeat -- must return empty, not throw and
     * not map to a present-but-null value.
     */
    @Test
    void insertIfAbsentReturnsThePresentIdOnceAndEmptyOnEveryRepeat() {
        UUID userId = newUser();
        String key = "SANITY_" + UUID.randomUUID();
        Instant now = Instant.now();

        Optional<UUID> first = transactionTemplate.execute(tx -> notificationRepository.insertIfAbsent(
                userId, key, "IMPORT_STATEMENT_READY", "FINANCIAL", "EMAIL", "NORMAL", "Title", "Body",
                now));
        Optional<UUID> second = transactionTemplate.execute(tx -> notificationRepository.insertIfAbsent(
                userId, key, "IMPORT_STATEMENT_READY", "FINANCIAL", "EMAIL", "NORMAL", "Title", "Body",
                now));

        assertThat(first)
                .as("RETURNING id must map to a present UUID for the row this call actually inserted")
                .isPresent();
        assertThat(second)
                .as("ON CONFLICT DO NOTHING must map to empty, not a present-but-null id or an exception")
                .isEmpty();
        assertThat(notificationRepository.findByNotificationKey(key))
                .as("exactly one row must exist for the key regardless of how many times this was called")
                .isPresent();
    }

    /**
     * Two callers, genuinely overlapping, both requesting the SAME notification for the SAME user --
     * the shape {@code addAlias}'s own doc comment describes as "one person confirming two statements
     * from two tabs", here as two concurrent triggers of the identical deterministic idempotency key
     * (e.g. a redelivered background job racing the original delivery).
     *
     * <p>The FIRST caller is parked with its insert already issued but its transaction still open.
     * The SECOND caller is started on its own thread specifically because it is expected to block for
     * real inside the database for as long as that lock is held -- calling it on the test thread would
     * hang the test on the same block. Releasing the first lets its transaction commit, which is what
     * unblocks the second's insert, which then genuinely collides with the now-committed row.
     *
     * <p>Each caller, after {@code request()} returns (win or lose), runs one more, unrelated
     * statement inside the SAME ambient transaction and lets it commit. That is the property the old
     * {@code saveAndFlush} + catch guard could not deliver: it is not enough that {@code request()}
     * itself does not throw, the transaction it ran in has to still be usable afterwards.
     */
    @Test
    void twoCallersRequestingTheSameNotificationKeyBothSurvive() throws Exception {
        UUID userId = newUser();
        String notificationKey = "IMPORT_READY_" + UUID.randomUUID();
        NotificationRequest request = NotificationRequest.of(userId, NotificationType.IMPORT_STATEMENT_READY,
                NotificationCategory.FINANCIAL, NotificationPriority.NORMAL, notificationKey,
                Set.of(NotificationChannel.EMAIL), Map.of());

        CountDownLatch firstHasInsertedButNotCommitted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        AtomicReference<List<UUID>> firstResult = new AtomicReference<>();
        AtomicReference<List<UUID>> secondResult = new AtomicReference<>();

        doAnswer(invocation -> {
            UUID uid = invocation.getArgument(0);
            String key = invocation.getArgument(1);
            String type = invocation.getArgument(2);
            String category = invocation.getArgument(3);
            String channel = invocation.getArgument(4);
            String priority = invocation.getArgument(5);
            String title = invocation.getArgument(6);
            String message = invocation.getArgument(7);
            Instant now = invocation.getArgument(8);

            // The exact SQL NotificationRepository#insertIfAbsent runs -- kept identical rather than
            // delegated to, because Mockito cannot call a "real" method through a spy of an
            // interface-backed Spring Data proxy (see the field's own doc comment).
            List<?> rows = entityManager.createNativeQuery("""
                    INSERT INTO notifications
                        (id, user_id, notification_key, type, category, channel, priority, status,
                         title, message, attempt_count, next_attempt_at, created_at)
                    VALUES
                        (gen_random_uuid(), :userId, :notificationKey, :type, :category, :channel,
                         :priority, 'QUEUED', :title, :message, 0, :now, :now)
                    ON CONFLICT (notification_key) DO NOTHING
                    RETURNING id
                    """)
                    .setParameter("userId", uid)
                    .setParameter("notificationKey", key)
                    .setParameter("type", type)
                    .setParameter("category", category)
                    .setParameter("channel", channel)
                    .setParameter("priority", priority)
                    .setParameter("title", title)
                    .setParameter("message", message)
                    .setParameter("now", now)
                    .getResultList();
            Optional<UUID> result = rows.isEmpty() ? Optional.empty() : Optional.of((UUID) rows.get(0));

            if (Thread.currentThread().getName().equals(FIRST_THREAD)) {
                firstHasInsertedButNotCommitted.countDown();
                assertThat(releaseFirst.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }).when(notificationRepository).insertIfAbsent(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), any());

        Thread first = new Thread(() -> {
            try {
                firstResult.set(transactionTemplate.execute(tx -> {
                    List<UUID> result = notificationService.request(request);
                    assertThat(countUsersNamed(userId)).isEqualTo(1L);
                    return result;
                }));
            } catch (Throwable t) {
                firstFailure.set(t);
            }
        }, FIRST_THREAD);
        first.start();

        assertThat(firstHasInsertedButNotCommitted.await(30, TimeUnit.SECONDS))
                .as("the first caller must actually be parked with its insert in place, uncommitted")
                .isTrue();

        Thread second = new Thread(() -> {
            try {
                secondResult.set(transactionTemplate.execute(tx -> {
                    List<UUID> result = notificationService.request(request);
                    assertThat(countUsersNamed(userId)).isEqualTo(1L);
                    return result;
                }));
            } catch (Throwable t) {
                secondFailure.set(t);
            }
        }, "notification-race-second");
        second.start();

        // No signal available from this side of the process for "the second caller is now blocked
        // inside the database" -- give it a moment to actually reach and issue its INSERT before
        // releasing the first caller, so the two genuinely overlap rather than running sequentially
        // by accident.
        Thread.sleep(500);

        releaseFirst.countDown();
        first.join(TimeUnit.SECONDS.toMillis(30));
        second.join(TimeUnit.SECONDS.toMillis(30));
        assertThat(first.isAlive()).as("the first caller must have finished, not hung").isFalse();
        assertThat(second.isAlive()).as("the second caller must have finished, not hung").isFalse();

        assertThat(firstFailure.get())
                .as("neither caller's transaction may fail, and the post-request statement inside it "
                        + "must have run and committed")
                .isNull();
        assertThat(secondFailure.get())
                .as("the second caller lost the race and must survive it -- request() must never fail "
                        + "the transaction it happens to be sharing with the rest of a caller's work, "
                        + "including work that runs AFTER request() returns")
                .isNull();

        assertThat(firstResult.get()).isNotNull();
        assertThat(secondResult.get()).isNotNull();

        // Exactly one of the two callers can have actually written the row; the other's request()
        // call must have returned an empty list (its own "never fail the caller" contract met by
        // returning normally with nothing written), not a phantom id for a row that was never
        // inserted, and not an exception.
        int totalWritten = firstResult.get().size() + secondResult.get().size();
        assertThat(totalWritten)
                .as("exactly one of the two callers' request() calls must report having written the row")
                .isEqualTo(1);

        String rowKey = notificationKey + ":EMAIL";
        assertThat(notificationRepository.findByNotificationKey(rowKey))
                .as("exactly one row must survive -- the write that lost the race must not have "
                        + "vanished silently, and UNIQUE(notification_key) rules out two")
                .isPresent();
    }

    private long countUsersNamed(UUID userId) {
        return entityManager
                .createQuery("SELECT COUNT(u) FROM User u WHERE u.id = :id", Long.class)
                .setParameter("id", userId)
                .getSingleResult();
    }
}
