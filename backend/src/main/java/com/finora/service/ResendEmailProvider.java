package com.finora.service;

import com.finora.config.EmailProperties;
import com.finora.util.EmailMasking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends real emails via Resend's HTTP API (https://resend.com) — chosen for a dead-simple API
 * (one POST, no SDK needed) and a generous free tier suitable for a pre-launch app. Uses
 * Spring's RestClient (built into spring-web since Boot 3.2, no new dependency needed).
 *
 * A send failure here is deliberately swallowed (logged, not rethrown, reflected only in
 * EmailResult.success()) rather than failing the caller's whole request — e.g. a user whose
 * forgotPassword() request succeeded server-side shouldn't see an error just because the email
 * provider had a transient hiccup; the generic "if an account exists..." response is still
 * accurate either way.
 */
public class ResendEmailProvider implements EmailProvider {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailProvider.class);
    private static final String RESEND_API_URL = "https://api.resend.com/emails";

    private final EmailProperties emailProperties;
    private final RestClient restClient;

    /**
     * Connect and read timeouts, both of them, because {@code RestClient.create()} sets neither.
     *
     * <p>BH-016. Without a read timeout this call can block for as long as the far end keeps the
     * socket open. That used to happen inside a {@code @Transactional} method, holding one of ten
     * pooled database connections, so a hung Resend endpoint starved the whole application rather
     * than just delaying an email. The sends have moved after commit, which fixes the connection
     * half -- but an unbounded wait would still pin a request thread indefinitely, so the timeout
     * is the other half rather than an alternative to it.
     *
     * <p>Ten seconds to connect and twenty to read: an email is a best-effort notification whose
     * failure is already swallowed into {@link EmailResult}, so waiting longer buys nothing anybody
     * is waiting for.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    public ResendEmailProvider(EmailProperties emailProperties) {
        this.emailProperties = emailProperties;
        ClientHttpRequestFactorySettings timeouts = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(CONNECT_TIMEOUT)
                .withReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(timeouts))
                .build();
    }

    @Override
    public boolean isConfigured() { return true; }

    /** Resend's own response shape -- {"id": "..."} on success. Only the field this app actually
     *  reads is modeled; unknown fields are ignored by Jackson's default configuration. */
    private record ResendResponse(String id) {}

    @Override
    public EmailResult send(EmailMessage message) {
        String html = applyTemplateVariables(message.html(), message.templateVariables());
        String text = applyTemplateVariables(message.text(), message.templateVariables());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", fromHeader());
        body.put("to", List.of(message.to()));
        body.put("subject", message.subject());
        if (html != null) body.put("html", html);
        if (text != null) body.put("text", text);
        if (!message.attachments().isEmpty()) {
            body.put("attachments", message.attachments().stream()
                    .map(a -> Map.of("filename", a.filename(), "content", Base64.getEncoder().encodeToString(a.content())))
                    .toList());
        }

        try {
            ResendResponse response = restClient.post()
                    .uri(RESEND_API_URL)
                    .header("Authorization", "Bearer " + emailProperties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(ResendResponse.class);
            String messageId = response != null ? response.id() : null;
            log.info("Email sent via Resend to {} (subject=\"{}\", messageId={})",
                    EmailMasking.mask(message.to()), message.subject(), messageId);
            return EmailResult.success(ProviderType.RESEND, messageId);
        } catch (Exception e) {
            log.error("Failed to send email to {} (subject=\"{}\"): {}",
                    EmailMasking.mask(message.to()), message.subject(), e.getMessage());
            return EmailResult.failure(ProviderType.RESEND, e.getMessage());
        }
    }

    /** "Name <email>" when EMAIL_FROM_NAME is set, matching how every real mail client renders
     *  a display name -- otherwise just the bare address, exactly today's existing behavior. */
    private String fromHeader() {
        String fromName = emailProperties.getFromName();
        return (fromName != null && !fromName.isBlank())
                ? fromName + " <" + emailProperties.getFromAddress() + ">"
                : emailProperties.getFromAddress();
    }

    private static String applyTemplateVariables(String content, Map<String, String> variables) {
        if (content == null || variables.isEmpty()) return content;
        String result = content;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }

    @Override
    public EmailResult sendPasswordResetEmail(String toEmail, String resetLink) {
        String html = """
                <p>We received a request to reset your Finora password.</p>
                <p><a href="%s">Click here to set a new password</a> — this link expires in 30 minutes.</p>
                <p>If you didn't request this, you can safely ignore this email.</p>
                """.formatted(resetLink);
        return send(EmailMessage.html(toEmail, "Reset your Finora password", html));
    }

    @Override
    public EmailResult sendWelcomeEmail(String toEmail, String fullName) {
        String html = """
                <p>Welcome to Finora, %s!</p>
                <p>Your account is ready — import a bank statement or connect an account to get started.</p>
                """.formatted(fullName);
        return send(EmailMessage.html(toEmail, "Welcome to Finora", html));
    }

    @Override
    public EmailResult sendPasswordChangedEmail(String toEmail) {
        String html = """
                <p>Your Finora password was just changed.</p>
                <p>If this wasn't you, contact support immediately and change your password again.</p>
                """;
        return send(EmailMessage.html(toEmail, "Your Finora password was changed", html));
    }
}
