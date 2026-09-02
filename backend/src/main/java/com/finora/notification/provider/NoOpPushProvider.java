package com.finora.notification.provider;

import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationChannel;
import com.finora.service.SilentProductionFallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fallback when no push credentials are configured.
 *
 * <p>Logs the notification id only -- never the title, body, token, or user identifier. This is a
 * deliberate correction of a real precedent in this codebase: NoOpSmsProvider once logged unmasked
 * phone numbers and amounts at INFO in production because only the real provider had adopted
 * masking. A no-op is not exempt from redaction.
 *
 * <p>Implements {@link SilentProductionFallback} (see {@code SilentFallbackConfigValidationTest}):
 * {@code PushConfig} selects this class instead of {@code FcmPushProvider} on exactly the same
 * missing-{@code FirebaseApp}-bean condition {@code FirebasePhoneVerificationProvider.isConfigured()}
 * already reports false for -- see {@code PushConfig}'s own doc comment. That condition is already
 * a hard boot failure in {@code ProductionConfigValidator} via the phone-verification check, so this
 * fallback can never actually be silently selected in a production deployment that passes that
 * check. Declaring the hint here anyway is what stops a future divergence (push and phone
 * verification gated on different config) from reopening a silent gap with nothing watching it.
 */
public class NoOpPushProvider implements NotificationChannelProvider, SilentProductionFallback {

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

    @Override
    public String requiredConfigHint() {
        return "GOOGLE_APPLICATION_CREDENTIALS";
    }
}
