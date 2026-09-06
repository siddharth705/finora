package com.finora.notification.provider;

import com.finora.config.EmailProperties;
import com.finora.entity.User;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationType;
import com.finora.repository.UserRepository;
import com.finora.service.EmailMessage;
import com.finora.service.EmailProvider;
import com.finora.service.EmailResult;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
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

    /**
     * Product decision, 2026-09-06: an email a customer might reasonably want to reply to and
     * reach a person -- a statement held for review, or one released and ready -- goes out as
     * {@code support@}, not {@code noreply@}. {@code PASSWORD_CHANGED}'s template row exists
     * ({@code V127}) but has no live caller (the actual password-changed email is
     * {@code ResendEmailProvider.sendPasswordChangedEmail}, a hand-built send outside this outbox
     * entirely) -- included here anyway against the day it gains one, so a future caller inherits
     * the right sender by construction instead of by remembering to update this set.
     */
    private static final Set<NotificationType> SUPPORT_SENDER_TYPES =
            EnumSet.of(NotificationType.IMPORT_STATEMENT_HELD, NotificationType.IMPORT_STATEMENT_READY);

    private final EmailProvider emailProvider;
    private final UserRepository userRepository;
    private final EmailProperties emailProperties;

    public EmailNotificationProvider(EmailProvider emailProvider, UserRepository userRepository,
                                      EmailProperties emailProperties) {
        this.emailProvider = emailProvider;
        this.userRepository = userRepository;
        this.emailProperties = emailProperties;
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
            if (user.isEmpty()) {
                // Permanent: retrying cannot make a user row that doesn't exist appear.
                return ChannelSendResult.permanentFailure(PROVIDER_NAME, "no email address on file");
            }
            User u = user.get();
            // A purged account's email is overwritten with a synthetic, non-blank sentinel
            // ("deleted-<id>@deleted.finora.invalid" -- see AccountPurgeSweepService.purgeOne()),
            // so the null/blank check below would not catch it. A notification for this user can
            // still be in flight (queued, mid-retry-backoff, or recovered from an abandoned
            // PROCESSING row) after the purge runs, and must not be handed to the real provider.
            if (u.isDeleted()) {
                // Permanent: a purge is not undone by waiting and retrying.
                return ChannelSendResult.permanentFailure(PROVIDER_NAME, "user account deleted");
            }
            if (u.getEmail() == null || u.getEmail().isBlank()) {
                // Never put the (missing or present) address in the detail -- it lands in
                // notification_logs, which admins read. Permanent: nothing about a retry populates
                // this user's email address.
                return ChannelSendResult.permanentFailure(PROVIDER_NAME, "no email address on file");
            }
            EmailResult result = emailProvider.send(buildMessage(u.getEmail(), notification.getType(),
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

    // Every DB-template email now goes out as branded HTML (EmailLayout), not the bare plain-text
    // sentence this used to send verbatim -- found live in testing: "this one line is looking
    // very bad". `text` still carries the original plain sentence as a fallback for clients that
    // strip HTML entirely, which is cheap correctness EmailLayout's own escaping doesn't cost us.
    private EmailMessage buildMessage(String to, NotificationType type, String subject, String body) {
        EmailMessage.Sender sender = SUPPORT_SENDER_TYPES.contains(type)
                ? EmailMessage.Sender.SUPPORT : EmailMessage.Sender.DEFAULT;
        String html = EmailLayout.wrap(subject, body, sender == EmailMessage.Sender.SUPPORT,
                emailProperties.getSupportFromAddress());
        return new EmailMessage(to, subject, html, body, null, null, sender);
    }
}
