package com.finora.notification.domain;

import java.util.regex.Pattern;

/**
 * Redacts common PII/secret shapes (emails, phone numbers, token-alphabet strings) out of a
 * free-text string before it is allowed to reach a column an admin can read.
 *
 * <h2>Why this exists as its own class</h2>
 *
 * <p>This logic used to live only inside {@link NotificationLog#sanitize}, whose own class doc
 * argued that "a provider's detail string is already masked" is a doc comment, not something the
 * compiler enforces, and that redaction must therefore be unconditional and applied at the point of
 * write. That reasoning is not actually specific to {@code notification_logs.response} -- it applies
 * identically to {@link Notification#recordFailure}'s {@code last_error}, which used to only
 * truncate the same {@code detail} string {@code NotificationLog.of} redacts, and which
 * {@code NotificationAdminDto}/{@code NotificationAdminDetailDto} surface straight to the admin
 * portal. Both writers take the exact same input ({@code ChannelSendResult.detail()}, ultimately a
 * provider's own free-text outcome), so the redaction rules belong in one place both can call rather
 * than as two regex sets that could quietly drift apart. {@link Notification} and
 * {@link NotificationLog} already share this package, so this stays here rather than moving into the
 * broader {@code com.finora.util} package -- there is no third consumer today, and promoting it out
 * of the notification domain can happen if one shows up.
 *
 * <p>Redaction only; truncation to a column's own width is each caller's own concern (their limits
 * differ in principle even though both happen to be 2000 today), and must run AFTER redaction --
 * truncating first can cut a PII shape in half at the boundary and let the remaining half slip
 * through unredacted.
 */
final class PiiRedactor {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** A run of 7+ digits, optionally broken up by spaces/dashes/dots/parens -- covers most phone
     * number formats (with or without a country code) without also eating short numbers like an
     * HTTP status code or a retry count. */
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(?<![\\w.])\\+?(?:\\d[ .\\-()]{0,2}){7,}\\d(?![\\w.])");

    /** A long run of token-alphabet characters -- covers API keys, bearer tokens, and JWT segments,
     * which are all built from this alphabet and are never this long by coincidence. */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\b[A-Za-z0-9_-]{24,}\\b");

    private PiiRedactor() {}

    /** Replaces email/phone/token-shaped substrings with a redaction placeholder. Null-safe --
     *  returns null for null input rather than throwing, so a caller can pipe it straight into its
     *  own null-safe truncate without an extra guard. */
    static String redact(String value) {
        if (value == null) {
            return null;
        }
        String redacted = EMAIL_PATTERN.matcher(value).replaceAll("[redacted-email]");
        redacted = PHONE_PATTERN.matcher(redacted).replaceAll("[redacted-phone]");
        redacted = TOKEN_PATTERN.matcher(redacted).replaceAll("[redacted-token]");
        return redacted;
    }
}
