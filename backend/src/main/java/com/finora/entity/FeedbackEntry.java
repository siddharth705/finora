package com.finora.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One piece of product feedback — "was this helpful", "report an issue", "suggest an improvement".
 *
 * <p>Deliberately not the same table as {@link SupportTicket}. Feedback needs no status tracking
 * and no per-row admin action, only aggregation; giving it a status column it never leaves would
 * invite exactly the triage workflow this scope excludes.
 */
@Entity
@Table(name = "feedback_entries")
public class FeedbackEntry extends BaseEntity {

    public enum Type {
        BUG, FEATURE_REQUEST, IMPROVEMENT, GENERAL
    }

    /**
     * Which feature the feedback came from — the aggregation axis this table exists to serve.
     *
     * <p>Enum-backed with <b>no</b> database CHECK constraint, unlike most enums you might expect
     * to constrain. This is the one column that gains a value every time a feature ships, and
     * CHECK-constrained enum columns have already proven expensive here: V95 added one on
     * {@code sign_in_method} and V96 exists for no other purpose than dropping and recreating it
     * to admit one more value. Validation lives at the API boundary instead, so adding a value
     * stays a one-constant change with no migration.
     *
     * <p>A free-text key with a frontend-maintained list was considered and rejected: it trades
     * the migration for typo drift, which destroys the aggregation this column exists for.
     */
    public enum Context {
        DASHBOARD, TRANSACTIONS, REPORTS, BUDGETS, GOALS, IMPORT_FLOW, ACCOUNTS, SETTINGS, HELP, OTHER
    }

    /** Which client. Separate from {@link Context}, which is which feature. */
    public enum Source {
        WEB, MOBILE_ANDROID, MOBILE_IOS
    }

    /**
     * Who submitted it. Authentication is required in v1, so in practice this is always set.
     *
     * <p>The column is nullable so that opening feedback to logged-out users later is a controller
     * change and nothing else — no migration, no backfill. Stated here and in V148 so the
     * nullability reads as a reserved option rather than an oversight.
     */
    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(name = "context", nullable = false)
    private Context context;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private Source source;

    @Column(name = "message", nullable = false)
    private String message;

    @Column(name = "app_version")
    private String appVersion;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public Context getContext() { return context; }
    public void setContext(Context context) { this.context = context; }

    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String appVersion) { this.appVersion = appVersion; }
}
