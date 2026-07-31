package com.finora.service;

/**
 * Abstraction over "actually send an email" so AuthService doesn't care which provider is
 * behind it. isConfigured() is what lets forgotPassword() decide whether it's safe to omit
 * the reset link from the API response (real email exists, so leaking the link in the response
 * body would be a needless exposure) or whether it needs to fall back to returning the link
 * directly (no email service configured — this is a dev-environment convenience, not a
 * production posture).
 */
public interface EmailService {
    boolean isConfigured();
    void sendPasswordResetEmail(String toEmail, String resetLink);
}
