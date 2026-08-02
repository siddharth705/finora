package com.finora.service;

/**
 * Abstraction over "actually send an email" so business services never talk to Resend (or any
 * future replacement) directly -- same PhoneVerificationProvider-style boundary. isConfigured()
 * is what lets AuthService.forgotPassword() decide whether it's safe to omit the reset link from
 * the API response (real email exists, so leaking the link in the response body would be a
 * needless exposure) or whether it needs to fall back to returning the link directly (no email
 * provider configured -- a dev-environment convenience, not a production posture).
 *
 * send() is the generic entry point (HTML/plaintext/attachments/template variables -- see
 * EmailMessage); sendPasswordResetEmail/sendWelcomeEmail/sendPasswordChangedEmail are the actual
 * purpose-built emails this app sends today, each building its own EmailMessage internally so
 * callers never construct one by hand for a well-known email type.
 */
public interface EmailProvider {
    boolean isConfigured();

    EmailResult send(EmailMessage message);

    EmailResult sendPasswordResetEmail(String toEmail, String resetLink);
    EmailResult sendWelcomeEmail(String toEmail, String fullName);
    EmailResult sendPasswordChangedEmail(String toEmail);
}
