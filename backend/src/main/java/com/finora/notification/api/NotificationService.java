package com.finora.notification.api;

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
     * <h2>The {@code exists} check is a fast path; {@code insertIfAbsent} is the real guarantee</h2>
     *
     * <p>{@code notification_key} is UNIQUE (V124). Checking {@code existsByNotificationKey} and
     * then inserting is a classic check-then-act race: two concurrent requests for the same key can
     * both see {@code false} and both attempt to insert. The actual insert therefore goes through
     * {@link NotificationRepository#insertIfAbsent}, an {@code INSERT ... ON CONFLICT DO NOTHING} --
     * see that method's own doc comment for why this codebase tried {@code saveAndFlush} +
     * {@code catch(DataIntegrityViolationException)} first (twice: once here, once in
     * {@code MerchantNormalizationEngine.addAlias}) and replaced both with this. A benign lost race
     * resolves atomically and silently at the database, so no exception is ever raised for it and
     * the caller's ambient transaction is never poisoned by it.
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
                repository.insertIfAbsent(request.userId(), key, request.type().name(),
                                request.category().name(), channel.name(), request.priority().name(),
                                rendered.title(), rendered.body(), now)
                        .ifPresentOrElse(written::add, () -> log.debug(
                                "Notification {} already requested, skipping duplicate", key));
            } catch (RuntimeException e) {
                // Never propagate: the caller's own work must not fail over a notification.
                log.error("Could not queue a {} notification on {} for user {}", request.type(),
                        channel, request.userId(), e);
            }
        }
        return written;
    }
}
