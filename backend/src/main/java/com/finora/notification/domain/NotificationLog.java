package com.finora.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One delivery attempt against one provider. Append-only: the notifications row holds current
 * state, this holds the history of how it got there, which is what an admin needs when asking
 * "why did this fail".
 *
 * <p>{@code success} means the provider's synchronous call returned OK -- not that the message was
 * delivered. Nothing in this codebase can currently know the latter.
 *
 * <h2>{@code response} is defense-in-depth, not a trusted-input field</h2>
 *
 * <p>{@code ChannelSendResult.detail()}'s own doc comment says a provider's detail string is
 * expected to already be masked before it reaches here -- but no concrete
 * {@code NotificationChannelProvider} exists yet, and that contract is a doc comment, not
 * something the compiler enforces. A future provider that echoes a raw exception message (a mail
 * server's "550 no such user: alice@example.com", an SMS gateway's "invalid MSISDN 9198xxxxxxx",
 * or a stack trace that happens to embed a bearer token) must not be able to land an email
 * address, phone number, or token in this table just because it failed to redact one itself.
 * {@link #of} therefore redacts common PII/secret shapes out of {@code response} before
 * truncating and storing it, unconditionally, on every row.
 */
@Entity
@Table(name = "notification_logs")
public class NotificationLog {

    private static final int MAX_RESPONSE_LENGTH = 2000;

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

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(nullable = false, length = 64)
    private String provider;

    @Column(length = 2000)
    private String response;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "timestamp", nullable = false, updatable = false)
    private Instant timestamp;

    protected NotificationLog() {
        // for JPA
    }

    private NotificationLog(UUID notificationId, String provider, String response, boolean success,
            int attempt, Instant timestamp) {
        this.notificationId = notificationId;
        this.provider = provider;
        this.response = response;
        this.success = success;
        this.attempt = attempt;
        this.timestamp = timestamp;
    }

    public static NotificationLog of(UUID notificationId, String provider, String response,
            boolean success, int attempt, Instant timestamp) {
        return new NotificationLog(notificationId, provider, sanitize(response), success, attempt,
                timestamp);
    }

    /** Redacts, then truncates. Redaction must run first: truncating first can cut a PII shape in
     * half at the boundary and let the remaining half slip through unredacted. */
    private static String sanitize(String response) {
        if (response == null) {
            return null;
        }
        String redacted = EMAIL_PATTERN.matcher(response).replaceAll("[redacted-email]");
        redacted = PHONE_PATTERN.matcher(redacted).replaceAll("[redacted-phone]");
        redacted = TOKEN_PATTERN.matcher(redacted).replaceAll("[redacted-token]");
        return truncate(redacted);
    }

    private static String truncate(String response) {
        return response.length() <= MAX_RESPONSE_LENGTH
                ? response
                : response.substring(0, MAX_RESPONSE_LENGTH);
    }

    public UUID getId() {
        return id;
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public String getProvider() {
        return provider;
    }

    public String getResponse() {
        return response;
    }

    public boolean isSuccess() {
        return success;
    }

    public int getAttempt() {
        return attempt;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
