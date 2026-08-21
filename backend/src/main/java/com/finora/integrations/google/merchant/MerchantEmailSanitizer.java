package com.finora.integrations.google.merchant;

import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * The one gate between a merchant email's raw HTML and every parser in this package — Phase C5,
 * gmail-transaction-sync proposal §12.4 ("Never trust the email").
 *
 * <h2>Why this exists as its own class rather than a step inside each parser</h2>
 *
 * If sanitization were each parser's own responsibility, adding a seventh merchant means adding a
 * seventh place that could get it wrong — and getting it wrong here is not a formatting bug, it is
 * arbitrary attacker-controlled HTML reaching whatever renders or logs a parser's intermediate
 * output. Centralising it means there is exactly one policy to audit, and {@link MerchantEmailParser}
 * is typed so a raw string cannot reach {@code parse} at all — see {@link SanitizedGmailMessage}.
 *
 * <h2>Allowlist, not denylist</h2>
 *
 * {@link PolicyFactory} enumerates what survives; everything else — {@code <script>}, every
 * {@code on*} event handler, every {@code javascript:} URL, {@code <iframe>}, {@code <object>},
 * remote-tracking {@code <img>} — is dropped by construction, including a construct nobody has
 * thought of yet. A denylist only ever covers what somebody already thought to block.
 *
 * <h2>What is allowed, and why exactly this much</h2>
 *
 * {@code BLOCKS + TABLES + FORMATTING}. Receipt templates are overwhelmingly table-based (item rows,
 * a totals table), so {@code TABLES} is what keeps that structure legible to a parser instead of
 * collapsing into one run-on line. Deliberately excludes {@code LINKS} and everything image-shaped:
 * a parser reads text, it does not render a clickable or fetchable email, and a live {@code <a href>}
 * or {@code <img src>} is attack surface (a tracking pixel, a phishing link) with no parsing benefit.
 *
 * <h2>Plain text is derived from the sanitized output, not from the raw one</h2>
 *
 * {@link SanitizedGmailMessage#plainText()} is produced by stripping tags out of the ALREADY-safe
 * HTML this class returns. That ordering is what keeps the tag-stripping step from being a second,
 * unaudited sanitizer: by the time it runs, every dangerous construct is already gone, so a simple
 * strip cannot reintroduce anything — it can only affect parsing quality, never safety.
 */
@Component
public class MerchantEmailSanitizer {

    private static final PolicyFactory POLICY = Sanitizers.BLOCKS
            .and(Sanitizers.TABLES)
            .and(Sanitizers.FORMATTING);

    /** Collapses runs of whitespace left behind once tags are stripped, so "Total<td>₹1,299</td>"
     *  reads as "Total ₹1,299" rather than "Total₹1,299" or a wall of blank lines. */
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final Pattern BLANK_LINE_RUN = Pattern.compile("\\n{2,}");

    /**
     * Sanitizes one message body. Safe to call with attacker-controlled input by construction — that
     * is the entire point of this class.
     */
    public SanitizedGmailMessage sanitize(String gmailMessageId, String authenticatedDomain,
                                          String rawHtml) {
        String safeHtml = rawHtml == null ? "" : POLICY.sanitize(rawHtml);
        String plainText = toPlainText(safeHtml);
        return new SanitizedGmailMessage(gmailMessageId, authenticatedDomain, safeHtml, plainText);
    }

    private static String toPlainText(String safeHtml) {
        String stripped = TAG.matcher(safeHtml).replaceAll(" ");
        stripped = WHITESPACE_RUN.matcher(stripped).replaceAll(" ");
        stripped = BLANK_LINE_RUN.matcher(stripped).replaceAll("\n");
        return stripped.strip();
    }
}
