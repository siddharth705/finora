package com.finora.service;

import com.finora.config.EmailProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Sends real emails via Resend's HTTP API (https://resend.com) — chosen for a dead-simple API
 * (one POST, no SDK needed) and a generous free tier suitable for a pre-launch app. Uses
 * Spring's RestClient (built into spring-web since Boot 3.2, no new dependency needed).
 *
 * A send failure here is deliberately swallowed (logged, not rethrown) rather than failing the
 * whole forgotPassword() request — a user whose reset request succeeded server-side shouldn't
 * see an error just because the email provider had a transient hiccup; the generic "if an
 * account exists..." response is still accurate either way.
 */
public class ResendEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailService.class);
    private static final String RESEND_API_URL = "https://api.resend.com/emails";

    private final EmailProperties emailProperties;
    private final RestClient restClient;

    public ResendEmailService(EmailProperties emailProperties) {
        this.emailProperties = emailProperties;
        this.restClient = RestClient.create();
    }

    @Override
    public boolean isConfigured() { return true; }

    @Override
    public void sendPasswordResetEmail(String toEmail, String resetLink) {
        String html = """
                <p>We received a request to reset your Finora password.</p>
                <p><a href="%s">Click here to set a new password</a> — this link expires in 30 minutes.</p>
                <p>If you didn't request this, you can safely ignore this email.</p>
                """.formatted(resetLink);

        try {
            restClient.post()
                    .uri(RESEND_API_URL)
                    .header("Authorization", "Bearer " + emailProperties.getApiKey())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "from", emailProperties.getFromAddress(),
                            "to", List.of(toEmail),
                            "subject", "Reset your Finora password",
                            "html", html
                    ))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("Failed to send password reset email to {}: {}", toEmail, e.getMessage());
        }
    }
}
