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
        Map<String, String> templateVariables
) {
    public EmailMessage {
        attachments = attachments == null ? List.of() : attachments;
        templateVariables = templateVariables == null ? Map.of() : templateVariables;
    }

    public static EmailMessage html(String to, String subject, String html) {
        return new EmailMessage(to, subject, html, null, List.of(), Map.of());
    }
}
