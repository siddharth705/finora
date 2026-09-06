package com.finora.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One in-product support request.
 *
 * <p>Deliberately small: a user creates it, an admin moves its status, done. No SLA timer, no
 * routing, no conversation thread — see {@code docs/proposals/support-help-feedback-proposal.md}
 * for why each of those is excluded rather than merely deferred.
 *
 * <h2>No {@code @SQLDelete}, unlike {@link Account} and {@link Budget}</h2>
 *
 * <p>This class extends {@link BaseEntity} and therefore inherits a {@code deleted_at} column, but
 * it deliberately does <b>not</b> carry the {@code @SQLDelete}/{@code @SQLRestriction} soft-delete
 * pair those two use. That omission is load-bearing, not an oversight, and copying the pattern
 * across from {@code Account} would be a real bug:
 *
 * <ul>
 *   <li>There is no delete endpoint for a ticket in v1, user-facing or admin, so nothing would
 *       ever set {@code deleted_at} through normal use.</li>
 *   <li>The one path that <i>does</i> delete these rows is account purge, and it needs a genuine
 *       {@code DELETE}. With {@code @SQLDelete} a repository delete becomes an {@code UPDATE},
 *       so a departing user's tickets — their own free-text description of a problem with their
 *       money — would silently survive the purge while appearing to have been removed.</li>
 * </ul>
 *
 * <p>{@code deleted_at} therefore exists in the schema and stays null forever. A column being
 * present is not the same as deletion being supported.
 *
 * <p><b>Always use the return value of {@code repository.save(...)}</b> — see {@link BaseEntity}'s
 * own doc for why the initialised {@code @Version} field makes Hibernate {@code merge()} rather
 * than {@code persist()} a brand-new instance.
 */
@Entity
@Table(name = "support_tickets")
public class SupportTicket extends BaseEntity {

    /** What the request is about. Adding a value is a one-constant change: V145 has no CHECK. */
    public enum Category {
        STATEMENT_IMPORT, CATEGORIZATION, ACCOUNT_LINKING, DATA_ACCURACY, TECHNICAL_ISSUE, OTHER
    }

    /**
     * The ticket lifecycle.
     *
     * <p>{@link #canTransitionTo} is the single source of truth for what is legal; the service
     * rejects anything else with a 409. Two rules in it are decisions rather than conveniences and
     * are easy to "fix" by accident:
     *
     * <ul>
     *   <li>{@code RESOLVED} moves only to {@code CLOSED}. A resolved ticket is never reopened —
     *       a customer whose issue turns out not to be fixed raises a new request. Because there
     *       is no conversation thread in v1, this also means the user-facing ticket detail has to
     *       say so; otherwise they wait on a ticket nobody will look at again.</li>
     *   <li>{@code OPEN} may go straight to {@code CLOSED}, so spam and duplicates close in one
     *       step rather than being walked through a fictitious {@code IN_PROGRESS} hop purely to
     *       satisfy the validator.</li>
     * </ul>
     */
    public enum Status {
        OPEN, IN_PROGRESS, RESOLVED, CLOSED;

        /** Whether {@code this -> target} is a legal move. Identity is not a transition. */
        public boolean canTransitionTo(Status target) {
            if (target == null || target == this) {
                return false;
            }
            return switch (this) {
                case OPEN -> true;
                case IN_PROGRESS -> target == RESOLVED || target == CLOSED;
                case RESOLVED -> target == CLOSED;
                case CLOSED -> false;
            };
        }
    }

    @Column(name = "ticket_number", nullable = false, unique = true)
    private String ticketNumber;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private ClientPlatform source;

    @Column(name = "subject", nullable = false)
    private String subject;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "app_version")
    private String appVersion;

    /**
     * The admin currently working this, or null. Nullable by design and nullable in the schema:
     * V145 uses {@code ON DELETE SET NULL} so a departing admin's account does not rewrite the
     * record of what they were doing.
     */
    @Column(name = "claimed_by_admin_id")
    private UUID claimedByAdminId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    public String getTicketNumber() { return ticketNumber; }
    public void setTicketNumber(String ticketNumber) { this.ticketNumber = ticketNumber; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public ClientPlatform getSource() { return source; }
    public void setSource(ClientPlatform source) { this.source = source; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String appVersion) { this.appVersion = appVersion; }

    public UUID getClaimedByAdminId() { return claimedByAdminId; }
    public void setClaimedByAdminId(UUID claimedByAdminId) { this.claimedByAdminId = claimedByAdminId; }

    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }

    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
}
