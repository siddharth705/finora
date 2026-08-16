package com.finora.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Used whenever no email provider API key is configured — logs instead of sending, and
 *  isConfigured() returning false is what tells AuthService it's still safe (and necessary)
 *  to return the reset link directly in the API response rather than a dead-end message. */
public class NoOpEmailProvider implements EmailProvider, SilentProductionFallback {

    private static final Logger log = LoggerFactory.getLogger(NoOpEmailProvider.class);

    @Override
    public boolean isConfigured() { return false; }

    @Override
    public EmailResult send(EmailMessage message) {
        log.info("No email provider configured — would have sent \"{}\" to {}", message.subject(), message.to());
        return EmailResult.failure(ProviderType.RESEND, "No email provider configured");
    }

    @Override
    public EmailResult sendPasswordResetEmail(String toEmail, String resetLink) {
        // Bug fix: this used to log the raw, valid reset link at INFO -- the exact same value
        // ProductionConfigValidator hard-fails prod boot over leaking anywhere reachable by a
        // non-owner (see that class's own doc comment: a full account-takeover primitive). The
        // link itself is still returned directly in the API response in this no-provider-
        // configured path (AuthService.forgotPassword()'s documented dev-convenience fallback),
        // so logging it too adds a second, unnecessary place it could leak from (e.g. a
        // long-retained log aggregator) for no operational benefit -- the log line doesn't need
        // the link to be useful.
        log.info("No email provider configured — would have sent a password reset link to {}", toEmail);
        return EmailResult.failure(ProviderType.RESEND, "No email provider configured");
    }

    @Override
    public EmailResult sendWelcomeEmail(String toEmail, String fullName) {
        log.info("No email provider configured — would have sent a welcome email to {}", toEmail);
        return EmailResult.failure(ProviderType.RESEND, "No email provider configured");
    }

    @Override
    public EmailResult sendPasswordChangedEmail(String toEmail) {
        log.info("No email provider configured — would have sent a password-changed notification to {}", toEmail);
        return EmailResult.failure(ProviderType.RESEND, "No email provider configured");
    }

    @Override
    public EmailResult sendAccountDeactivatedEmail(String toEmail, java.time.Instant deactivatedAt, String device, String ip) {
        log.info("No email provider configured — would have sent an account-deactivated notification to {}", toEmail);
        return EmailResult.failure(ProviderType.RESEND, "No email provider configured");
    }

    @Override
    public EmailResult sendAccountReactivatedEmail(String toEmail) {
        log.info("No email provider configured — would have sent an account-reactivated notification to {}", toEmail);
        return EmailResult.failure(ProviderType.RESEND, "No email provider configured");
    }

    @Override
    public EmailResult sendAccountDeletedEmail(String toEmail, java.time.Instant deletedAt) {
        log.info("No email provider configured — would have sent an account-deleted notification to {}", toEmail);
        return EmailResult.failure(ProviderType.RESEND, "No email provider configured");
    }

    @Override
    public String requiredConfigHint() { return "RESEND_API_KEY"; }
}
