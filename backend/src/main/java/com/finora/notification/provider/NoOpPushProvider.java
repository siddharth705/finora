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
