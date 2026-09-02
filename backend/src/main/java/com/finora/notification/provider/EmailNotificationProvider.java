package com.finora.notification.provider;

import com.finora.entity.User;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationChannel;
import com.finora.repository.UserRepository;
import com.finora.service.EmailMessage;
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
            EmailResult result = emailProvider.send(buildMessage(u.getEmail(),
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

    // EmailMessage is a 6-component record (to, subject, html, text, attachments,
    // templateVariables), not the 3-arg shape the brief sketched -- a Notification's message is
    // plain text, so it goes in the text slot; the compact constructor turns a null
    // attachments/templateVariables into empty collections.
    private EmailMessage buildMessage(String to, String subject, String body) {
        return new EmailMessage(to, subject, null, body, null, null);
    }
}
