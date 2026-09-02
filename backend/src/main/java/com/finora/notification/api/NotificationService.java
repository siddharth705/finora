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
import org.springframework.dao.DataIntegrityViolationException;
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
     * <h2>The {@code exists} check is a fast path, not a guarantee</h2>
     *
     * <p>{@code notification_key} is UNIQUE (V123). Checking {@code existsByNotificationKey} and
     * then saving is a classic check-then-act race: two concurrent requests for the same key can
     * both see {@code false} and both attempt to insert. Saving with a plain {@code save()} would
     * let that race surface as a constraint violation at the CALLER's own commit -- after this
     * method has already returned successfully -- which is exactly the failure this class exists to
     * prevent (see {@code GmailMessageDiscoveryService.record()} for the same problem solved the
     * same way elsewhere in this codebase). Using {@code saveAndFlush} forces the constraint check
     * to happen synchronously, inside this method's own {@code try}, where a
     * {@link DataIntegrityViolationException} is caught and treated exactly like the {@code exists}
     * branch above: a suppressed duplicate, not a failure.
     *
     * <p><b>Known residual limitation.</b> Postgres aborts the entire surrounding transaction the
     * instant any statement on it errors, including this flush -- catching the translated exception
     * in Java stops it from propagating out of this method, but does not by itself make the rest of
     * the ambient transaction usable again (the same fact {@code MerchantAliasRepository
     * .insertIfAbsent}'s doc comment and {@code BackgroundWorkConfig}'s document for the equivalent
     * problem elsewhere in this codebase, both solved with either {@code REQUIRES_NEW} or a native
     * {@code ON CONFLICT DO NOTHING} insert instead of a catch). Neither of those tools was used
     * here: {@code REQUIRES_NEW} would decouple every notification row's commit from the caller's
     * transaction, defeating the outbox guarantee this class exists to provide, and a native insert
     * would require re-deriving {@link Notification}'s initial-state invariants outside the entity.
     * In the rare event this backstop is actually hit -- concurrent requests for the identical
     * key racing inside the exists check's own window, which the fast path above prevents in every
     * other case -- any further statement in the SAME caller transaction (another channel in this
     * loop, or the caller's own subsequent work) can still fail. That is narrower than the bug this
     * method fixes, which failed on every hit rather than only a race within a race, but it is not
     * airtight. Flagged for a follow-up decision rather than solved unilaterally here.
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
                try {
                    written.add(repository.saveAndFlush(notification).getId());
                } catch (DataIntegrityViolationException dup) {
                    // Lost the race against a concurrent writer for the same key: the same no-op
                    // outcome as the exists() branch above, not a failure.
                    log.debug("Notification {} already requested, skipping duplicate", key);
                }
            } catch (RuntimeException e) {
                // Never propagate: the caller's own work must not fail over a notification.
                log.error("Could not queue a {} notification on {} for user {}", request.type(),
                        channel, request.userId(), e);
            }
        }
        return written;
    }
}
