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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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

    /** "14 Aug 2026, 20:15" -- deliberately not locale-sensitive (fixed ENGLISH/pattern), since
     *  this renders server-side into an email every recipient sees identically. */
    private static final DateTimeFormatter DEACTIVATED_AT_FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", java.util.Locale.ENGLISH);

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
        body.put("from", fromHeader(message.sender()));
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
     *  a display name -- otherwise just the bare address, exactly today's existing behavior.
     *  {@code sender} picks which of the three configured addresses this particular message goes
     *  out under -- see {@link EmailMessage.Sender}'s own doc for which emails ask for which. */
    private String fromHeader(EmailMessage.Sender sender) {
        String address = switch (sender) {
            case SUPPORT -> emailProperties.getSupportFromAddress();
            case BILLING -> emailProperties.getBillingFromAddress();
            case DEFAULT -> emailProperties.getFromAddress();
        };
        String fromName = emailProperties.getFromName();
        return (fromName != null && !fromName.isBlank())
                ? fromName + " <" + address + ">"
                : address;
    }

    private static String applyTemplateVariables(String content, Map<String, String> variables) {
        if (content == null || variables.isEmpty()) return content;
        String result = content;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }

    /** Every noreply@ email ends with this -- a real, clickable support address rather than the
     *  vague "contact support" several of these used to say with nothing to click. These emails
     *  stay on noreply@ deliberately (product decision, 2026-09-06): a security/account-lifecycle
     *  notice is not a conversation, so replying to it should not silently go nowhere -- this line
     *  is the actual way to reach a person, not a decoration. */
    // synthetic-ok: Fynora's own published support mailbox, not a customer address
    private static final String SUPPORT_LINE =
            "<p style=\"color:#6b7280;\">Need help? Email <a href=\"mailto:support@fynora.net\">" // synthetic-ok
                    + "support@fynora.net</a>.</p>"; // synthetic-ok

    @Override
    public EmailResult sendPasswordResetEmail(String toEmail, String resetLink) {
        String html = """
                <p>We received a request to reset your Fynora password.</p>
                <p><a href="%s">Click here to set a new password</a> — this link expires in 30 minutes.</p>
                <p>If you didn't request this, you can safely ignore this email.</p>
                %s
                """.formatted(resetLink, SUPPORT_LINE);
        return send(EmailMessage.html(toEmail, "Reset your Fynora password", html));
    }

    @Override
    public EmailResult sendEmailVerificationEmail(String toEmail, String verifyLink) {
        String html = """
                <p>Please verify your email address to finish setting up your Fynora account.</p>
                <p><a href="%s">Click here to verify your email</a> — this link expires in 24 hours.</p>
                <p>If you didn't create a Fynora account, you can safely ignore this email.</p>
                %s
                """.formatted(verifyLink, SUPPORT_LINE);
        return send(EmailMessage.html(toEmail, "Verify your Fynora email address", html));
    }

    @Override
    public EmailResult sendEmailChangeVerificationEmail(String toEmail, String verifyLink) {
        String html = """
                <p>Confirm this email address to finish changing the email on your Fynora account.</p>
                <p><a href="%s">Click here to confirm</a> — this link expires in 15 minutes.</p>
                <p>If you didn't request this change, you can safely ignore this email — your account's
                email address will not change unless you click the link above.</p>
                %s
                """.formatted(verifyLink, SUPPORT_LINE);
        return send(EmailMessage.html(toEmail, "Confirm your new Fynora email address", html));
    }

    @Override
    public EmailResult sendWelcomeEmail(String toEmail, String fullName) {
        String html = """
                <p>Welcome to Fynora, %s!</p>
                <p>Your account is ready — import a bank statement or connect an account to get started.</p>
                %s
                """.formatted(fullName, SUPPORT_LINE);
        return send(EmailMessage.html(toEmail, "Welcome to Fynora", html));
    }

    @Override
    public EmailResult sendPasswordChangedEmail(String toEmail) {
        String html = """
                <p>Your Fynora password was just changed.</p>
                <p>If this wasn't you, change your password again immediately.</p>
                %s
                """.formatted(SUPPORT_LINE);
        return send(EmailMessage.html(toEmail, "Your Fynora password was changed", html));
    }

    @Override
    public EmailResult sendAccountDeactivatedEmail(String toEmail, Instant deactivatedAt, String device, String ip) {
        // UTC, not the account's own timezone -- this method has no access to it, and a plain,
        // explicitly-labelled UTC timestamp is unambiguous where a bare local-looking one would
        // not be. Same reasoning DEACTIVATED_AT_FORMAT below applies to itself.
        String when = DEACTIVATED_AT_FORMAT.format(deactivatedAt.atZone(ZoneOffset.UTC));
        // Best-effort labels degrade to omission, not a placeholder like "Unknown" that would read
        // as a real (if unhelpful) fact about the request.
        String deviceLine = (device != null && !device.isBlank())
                ? "<p>Device: %s</p>".formatted(device) : "";
        String ipLine = (ip != null && !ip.isBlank())
                ? "<p>IP address: %s</p>".formatted(ip) : "";
        String html = """
                <p>Your Fynora account was deactivated on %s (UTC).</p>
                %s%s
                <p>Your data is retained securely. Sign in again any time to reactivate your account.</p>
                <p>If you didn't do this, act now:</p>
                %s
                """.formatted(when, deviceLine, ipLine, SUPPORT_LINE);
        return send(EmailMessage.html(toEmail, "Your Fynora account was deactivated", html));
    }

    @Override
    public EmailResult sendAccountReactivatedEmail(String toEmail) {
        String html = """
                <p>Welcome back — your Fynora account has been reactivated.</p>
                <p>If you didn't do this, act now:</p>
                %s
                """.formatted(SUPPORT_LINE);
        return send(EmailMessage.html(toEmail, "Your Fynora account was reactivated", html));
    }

    @Override
    public EmailResult sendAccountDeletedEmail(String toEmail, Instant deletedAt) {
        String when = DEACTIVATED_AT_FORMAT.format(deletedAt.atZone(ZoneOffset.UTC));
        String html = """
                <p>Your Fynora account and all your data were permanently deleted on %s (UTC).</p>
                <p>This cannot be undone.</p>
                <p>If you didn't do this:</p>
                %s
                """.formatted(when, SUPPORT_LINE);
        return send(EmailMessage.html(toEmail, "Your Fynora account has been deleted", html));
    }

    @Override
    public EmailResult sendSubscriptionActivatedEmail(String toEmail, String fullName, String planName, String billingCycle) {
        String cadence = "YEARLY".equals(billingCycle) ? "yearly" : "monthly";
        String html = """
                <p>Hi %s,</p>
                <p>Your Fynora <strong>%s</strong> subscription is now active, billed %s.</p>
                <p>You can review your plan, payment history, or make changes any time from Billing in the app.</p>
                <p>Questions about this charge? Just reply to this email.</p>
                """.formatted(fullName, planName, cadence);
        return send(EmailMessage.html(toEmail, "Your Fynora " + planName + " subscription is active",
                html, EmailMessage.Sender.BILLING));
    }
}
