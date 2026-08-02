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
        log.info("No email provider configured — would have sent a password reset link to {} ({})", toEmail, resetLink);
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
    public String requiredConfigHint() { return "RESEND_API_KEY"; }
}
