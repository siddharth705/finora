package com.finora.service;

import java.util.List;
import java.util.Map;

/**
 * Provider-agnostic email content -- EmailProvider implementations translate this into whatever
 * shape the real API (Resend, or a future replacement) actually expects. html/text are both
 * optional individually (a caller may send HTML-only or text-only), but at least one should be
 * set. templateVariables are substituted into html/text as {{name}} placeholders before sending
 * -- deliberately simple string substitution, not a templating engine, since no caller needs more
 * than that today.
 */
public record EmailMessage(
        String to,
        String subject,
        String html,
        String text,
        List<EmailAttachment> attachments,
        Map<String, String> templateVariables,
        Sender sender
) {
    /**
     * Which configured address a message goes out under -- a caller names the BUCKET, never an
     * address itself, so a deployment can change any of these without touching a single caller.
     * {@link com.finora.config.EmailProperties} resolves each to the actual value.
     *
     * <p>{@code SUPPORT} (product decision, 2026-09-06): an email a customer might reasonably want
     * to reply to and reach a person (held-import/held-statement notices) goes out as
     * {@code support@}, not {@code noreply@}; everything else (welcome, password reset/changed,
     * account lifecycle) stays on {@code DEFAULT} (the existing {@code noreply@} address).
     *
     * <p>{@code BILLING} (Subscription Billing V3, merged the same day): billing/subscription
     * correspondence sends from a distinct, recognizably billing-specific address -- originally
     * built as a raw {@code String from} override on this record with its own {@code htmlFrom}
     * factory; folded into this enum instead of kept as a second, parallel "which sender" concept
     * once the two features landed on the same day and needed reconciling. Behavior for the one
     * caller ({@code ResendEmailProvider.sendSubscriptionActivatedEmail}) is unchanged.
     */
    public enum Sender { DEFAULT, SUPPORT, BILLING }

    public EmailMessage {
        attachments = attachments == null ? List.of() : attachments;
        templateVariables = templateVariables == null ? Map.of() : templateVariables;
        sender = sender == null ? Sender.DEFAULT : sender;
    }

    public static EmailMessage html(String to, String subject, String html) {
        return new EmailMessage(to, subject, html, null, List.of(), Map.of(), Sender.DEFAULT);
    }

    public static EmailMessage html(String to, String subject, String html, Sender sender) {
        return new EmailMessage(to, subject, html, null, List.of(), Map.of(), sender);
    }
}
