package com.finora.notification.api;

import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.repository.NotificationRepository;
import com.finora.notification.template.RenderedMessage;
import com.finora.notification.template.TemplateRenderer;
import com.finora.notification.worker.NotificationDispatcher;
import com.finora.util.AfterCommit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
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

    // Mirror V125's own column widths (notifications.title/message/notification_key). Written in
    // exactly one place each -- see #truncate's own doc for why an over-length value must never
    // reach the insert below at all.
    private static final int MAX_TITLE_LENGTH = 300;
    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int MAX_KEY_LENGTH = 200;

    private final NotificationRepository repository;
    private final TemplateRenderer templateRenderer;
    private final NotificationPreferenceResolver preferenceResolver;
    private final NotificationDispatcher dispatcher;

    public NotificationService(NotificationRepository repository, TemplateRenderer templateRenderer,
            NotificationPreferenceResolver preferenceResolver, NotificationDispatcher dispatcher) {
        this.repository = repository;
        this.templateRenderer = templateRenderer;
        this.preferenceResolver = preferenceResolver;
        this.dispatcher = dispatcher;
    }

    /**
     * Requests delivery on each channel the user has enabled for this category.
     *
     * <p>Deliberately NOT annotated {@code @Transactional}: it must join the caller's existing
     * transaction so the row commits atomically with whatever the caller is recording. It is the
     * caller's transaction that makes this an outbox.
     *
     * <h2>The {@code exists} check is a fast path; {@code insertIfAbsent} is the real guarantee</h2>
     *
     * <p>{@code notification_key} is UNIQUE (V125). Checking {@code existsByNotificationKey} and
     * then inserting is a classic check-then-act race: two concurrent requests for the same key can
     * both see {@code false} and both attempt to insert. The actual insert therefore goes through
     * {@link NotificationRepository#insertIfAbsent}, an {@code INSERT ... ON CONFLICT DO NOTHING} --
     * see that method's own doc comment for why this codebase tried {@code saveAndFlush} +
     * {@code catch(DataIntegrityViolationException)} first (twice: once here, once in
     * {@code MerchantNormalizationEngine.addAlias}) and replaced both with this. A benign lost race
     * resolves atomically and silently at the database, so no exception is ever raised for it and
     * the caller's ambient transaction is never poisoned by it.
     *
     * <h2>Every insert is truncated to its column width first</h2>
     *
     * <p>{@code title}/{@code message} come from {@code TemplateRenderer.render}, which substitutes
     * caller-supplied params (e.g. {@code {{bank}}}, whose value can come from PDF-parsed
     * institution text) into a template with no length check of its own; {@code key} is
     * {@code request.notificationKey() + ":" + channel.name()}, similarly unbounded. An over-length
     * value hitting {@code title VARCHAR(300)}/{@code message VARCHAR(2000)}/
     * {@code notification_key VARCHAR(200)} (V125) raises a Postgres statement error INSIDE the
     * caller's own ambient transaction -- the surrounding {@code catch (RuntimeException)} below
     * swallows the exception but does not un-abort that transaction, so every later statement on it
     * fails and the caller's own COMMIT silently downgrades to ROLLBACK (SQLSTATE 25P02, the same
     * mechanism {@code MerchantNormalizationEngine.addAlias} and
     * {@link NotificationRepository#insertIfAbsent} already document). Truncating before the insert
     * is what actually keeps the promise this class's own doc comment makes: a caller's business
     * transaction must never fail because of a notification.
     *
     * <p>{@code key} is truncated by {@link #truncateKey}, not the same blind
     * {@link #truncate(String, int)} used for {@code title}/{@code message}. {@code key} is UNIQUE
     * and is what {@code insertIfAbsent}'s {@code ON CONFLICT DO NOTHING} keys idempotency off of --
     * a blind cut to 200 characters would make two DIFFERENT over-length keys that happen to share
     * their first 200 characters collide, and the second notification would be silently dropped as
     * if it were a duplicate of the first, with nothing but a {@code log.debug} (invisible at this
     * app's default INFO level) to show for it. {@code title}/{@code message} have no such
     * constraint -- they are display strings, not identifiers -- so a blind cut is fine for them.
     *
     * <h2>Delivery is nudged once this call's transaction actually commits</h2>
     *
     * <p>{@link NotificationDispatcher#nudge()} exists specifically for this ("near-immediate
     * delivery") but nothing called it -- every notification, including {@code PASSWORD_CHANGED},
     * used to wait for the 30-second poller. {@link AfterCommit#run} mirrors
     * {@code MerchantLearningEventPublisher.nudgeAfterCommit()}'s own
     * {@code TransactionSynchronizationManager} pattern (also used by {@code ImportJobService} and
     * {@code AdminLearningQueueService}) rather than a fifth hand-rolled copy of it: nudging before
     * the row is visible would race the dispatcher's own claim query against a row that was never
     * actually committed, so this only fires {@code afterCommit} -- or immediately when {@code
     * request} is called with no ambient transaction, since there is then nothing to wait for. Only
     * fires when this call actually wrote at least one row; a call that only hit the duplicate/
     * preference-suppressed paths has nothing new for the dispatcher to claim.
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
                String key = truncateKey(request.notificationKey() + ":" + channel.name());
                if (repository.existsByNotificationKey(key)) {
                    log.debug("Notification {} already requested, skipping duplicate", key);
                    continue;
                }
                RenderedMessage rendered =
                        templateRenderer.render(request.type(), channel, request.params());
                repository.insertIfAbsent(request.userId(), key, request.type().name(),
                                request.category().name(), channel.name(), request.priority().name(),
                                truncate(rendered.title(), MAX_TITLE_LENGTH),
                                truncate(rendered.body(), MAX_MESSAGE_LENGTH), now)
                        .ifPresentOrElse(written::add, () -> log.debug(
                                "Notification {} already requested, skipping duplicate", key));
            } catch (RuntimeException e) {
                // Never propagate: the caller's own work must not fail over a notification.
                log.error("Could not queue a {} notification on {} for user {}", request.type(),
                        channel, request.userId(), e);
            }
        }
        if (!written.isEmpty()) {
            AfterCommit.run("notification dispatch nudge", dispatcher::nudge);
        }
        return written;
    }

    /** Null-safe truncate to a column's own width -- {@code maxLength} differs per caller (title,
     *  message, key), which is why this is parameterized instead of reusing one of
     *  {@code Notification}/{@code NotificationLog}'s own hardcoded-to-2000 truncate helpers. */
    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /**
     * Truncates an over-length {@code notification_key} to {@link #MAX_KEY_LENGTH} without the
     * blind-cut collision a plain {@link #truncate(String, int)} would create -- see this class's
     * {@code request} doc comment for why {@code key} needs different treatment from
     * {@code title}/{@code message}. Two different over-length keys sharing their first 167
     * characters are still made distinct by the hash of each one's own FULL, untruncated value;
     * the same key hashes the same way every time, so idempotency across retries still holds.
     *
     * <p>167 header characters + {@code '#'} + 32 hex hash characters = 200 = exactly
     * {@link #MAX_KEY_LENGTH}, so the result always fits the column, never more.
     */
    private static String truncateKey(String key) {
        if (key == null || key.length() <= MAX_KEY_LENGTH) {
            return key;
        }
        return key.substring(0, 167) + "#" + sha256Hex(key).substring(0, 32);
    }

    /** Lowercase hex SHA-256, used only to disambiguate an over-length {@code notification_key}'s
     *  truncated tail -- see {@link #truncateKey}. Not a security boundary; collision resistance
     *  is what matters here, not secrecy. */
    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK spec; absence means a broken runtime, not a case to
            // handle -- same reasoning as ContentAddress.hashOf's identical catch.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
