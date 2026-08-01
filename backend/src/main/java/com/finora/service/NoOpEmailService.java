package com.finora.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Used whenever no email provider API key is configured — logs instead of sending, and
 *  isConfigured() returning false is what tells AuthService it's still safe (and necessary)
 *  to return the reset link directly in the API response rather than a dead-end message. */
public class NoOpEmailService implements EmailService, SilentProductionFallback {

    private static final Logger log = LoggerFactory.getLogger(NoOpEmailService.class);

    @Override
    public boolean isConfigured() { return false; }

    @Override
    public void sendPasswordResetEmail(String toEmail, String resetLink) {
        log.info("No email provider configured — would have sent a password reset link to {} ({})", toEmail, resetLink);
    }

    @Override
    public String requiredConfigHint() { return "RESEND_API_KEY"; }
}
