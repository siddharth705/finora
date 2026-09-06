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
     * Which configured address a message goes out under -- product decision, 2026-09-06: an email
     * a customer might reasonably want to reply to and reach a person (held-import/held-statement
     * notices) goes out as {@code support@}, not {@code noreply@}; everything else (welcome,
     * password reset/changed, account lifecycle) stays on the existing {@code noreply@} address.
     * {@link com.finora.config.EmailProperties#getSupportFromAddress} resolves the actual value --
     * this enum only says which one, never carries an address itself, so a deployment can change
     * either address without touching a single caller.
     */
    public enum Sender { DEFAULT, SUPPORT }

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
