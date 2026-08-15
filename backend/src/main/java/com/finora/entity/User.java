package com.finora.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue
    private UUID id;

    // NOT unique on its own since V52: the same person may hold a USER-scope account and an
    // ADMIN-scope account under one email. Uniqueness is (LOWER(email), account_scope), enforced
    // by uq_users_email_scope -- a functional index, so it cannot be expressed as a column
    // constraint here.
    @Column(nullable = false)
    private String email;

    /**
     * Which portal this account belongs to: {@code USER} or {@code ADMIN}.
     *
     * The rule is one email and one mobile number per user -- scoped to a portal rather than
     * global, so an administrator who also uses Finora personally does not have to invent a second
     * email address to sign up with.
     *
     * Deliberately NOT derived from {@link #role}. Roles change (a user is promoted, an admin
     * demoted) and since V16 one account can hold several at once, so uniqueness keyed on them
     * would start rejecting legitimate role changes as duplicates. This answers a different and
     * stable question -- which portal is this account FOR -- and is what login disambiguates on.
     *
     * It grants nothing. Authorization stays role-based, so an ADMIN-scope account holding no
     * admin role has exactly the access its roles give it, which is none.
     */
    @Column(name = "account_scope", nullable = false, length = 10)
    private String accountScope = SCOPE_USER;

    public static final String SCOPE_USER = "USER";
    public static final String SCOPE_ADMIN = "ADMIN";

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    /** The floor every account sits at, and what {@code RoleService.revokeRole} resets the legacy
     *  column to when it revokes the role that column names. Named rather than repeated as a
     *  literal so the default and the demotion target cannot drift into disagreeing. */
    public static final String DEFAULT_ROLE = "USER";

    /** The role that can administer everything, including granting and revoking roles. Named
     *  because {@code RoleService.revokeRole} has to recognise it to refuse demoting the last one
     *  -- a platform with no SUPER_ADMIN cannot be recovered through any endpoint, since
     *  BootstrapService only mints a bootstrap account while setup_completed is false. */
    public static final String SUPER_ADMIN_ROLE = "SUPER_ADMIN";

    /** The status a usable account carries. */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** Admin-locked (AdminUserService.suspend) -- no self-service way back, distinct from
     *  STATUS_DEACTIVATED below on purpose. See isSuspended(). */
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    /** Self-service, reversible: the user chose to step away. Blocks login like SUSPENDED, but
     *  AuthService.login() recognizes it separately and offers a reactivation path instead of a
     *  dead-end rejection -- see isDeactivated() and AuthService.reactivate().
     *
     *  Permanent deletion (PENDING_DELETION/DELETED) is a later phase and deliberately has no
     *  constants here yet -- see V81's own comment on why that schema ships alongside the code
     *  that writes it, not ahead of it. */
    public static final String STATUS_DEACTIVATED = "DEACTIVATED";

    /** Every value users_deactivation_reason_check (V82) allows -- kept as one Java-side list so
     *  UserAccountLifecycleService.deactivate()'s validation and the DB constraint can never name
     *  a different set. Product feedback / churn-analysis categories, not technical states -- see
     *  deactivationReason's own doc comment. */
    public static final java.util.List<String> DEACTIVATION_REASONS = java.util.List.of(
            "TAKING_A_BREAK", "NOT_USING_ANYMORE", "PRIVACY_CONCERNS", "USING_ANOTHER_APP", "OTHER");

    // Legacy single-role string, kept for backward compatibility -- see
    // V16__rbac_roles_permissions.sql and AuthorizationService. New code assigning a user
    // access beyond this should prefer `roles` below (supports more than one, and drives
    // fine-grained permissions rather than just USER/ADMIN); this column is not being actively
    // deprecated yet since a good deal of existing code (registration, tests) still reads/writes
    // it directly, but it should not gain new callers going forward.
    //
    // Because AuthorizationService resolves a Role BY this string and grants its whole permission
    // set, this column is not merely informational -- it is a live grant. Anything that revokes
    // access has to consider it, which is exactly what RoleService.revokeRole failed to do.
    @Column(nullable = false)
    private String role = DEFAULT_ROLE;

    // Database-driven RBAC (docs/engineering-directive-phase1.md, Priority 2). A user can hold
    // zero or more explicit roles here in addition to whatever `role` above implies --
    // AuthorizationService computes the union of both when building the authenticated
    // principal's granted authorities, so adding a row here can only ever grant additional
    // access relative to today's behavior, never take any away.
    //
    // EAGER stays: every authenticated request resolves authorities from this set
    // (AuthorizationService), so it is genuinely needed on essentially every load, and making it
    // LAZY would trade an N+1 for a LazyInitializationException outside the persistence context.
    // @BatchSize is what actually fixes the cost: nested EAGER @ManyToMany with no batching made
    // AdminUserService.list() issue 1 + N + (N x roles-per-user) queries -- it paginates with plain
    // JPQL, no fetch join, then touches getRoles(). With batching, the N per-user role loads
    // collapse into ceil(N / 25) IN-clause queries. 25 matches the admin list's own page size, so
    // one page is one batch.
    @ManyToMany(fetch = FetchType.EAGER)
    @BatchSize(size = 25)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<Role> roles = new HashSet<>();

    @Column(name = "low_balance_threshold", nullable = false)
    private BigDecimal lowBalanceThreshold = BigDecimal.valueOf(2000);

    // "system" (not a color scheme itself) means "follow the device" — see V9 migration and
    // the frontend's ThemeContext, which normalizes anything else unrecognized back to this too.
    @Column(nullable = false)
    private String theme = "system";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "phone_number", length = 20, unique = true)
    private String phoneNumber;

    @Column(name = "phone_verified", nullable = false)
    private boolean phoneVerified = false;

    // ACTIVE / SUSPENDED / DEACTIVATED -- see V23__user_account_status.sql (original two values)
    // and V81__account_lifecycle_status.sql (DEACTIVATED, including the widened CHECK constraint
    // -- this column is NOT free text, the DB enforces the full set too). Checked in
    // AuthService.login and AuthService.refresh; none of these values obtain a new access token,
    // but any access token issued before the status change keeps working until it naturally
    // expires (15 min default) since JwtAuthFilter doesn't re-check the database on every request.
    // That's a deliberate tradeoff, not an oversight -- see AdminUserService.suspend's doc
    // comment. Every status change that must take effect immediately also calls
    // RefreshTokenService.revokeAllForUser in the same transaction.
    @Column(nullable = false)
    private String status = STATUS_ACTIVE;

    // Product-feedback capture for self-service deactivation (V82) -- see
    // UserAccountLifecycleService.deactivate()'s own doc comment. One of DEACTIVATION_REASONS
    // above, or null for an account deactivated before this column existed. Deliberately NOT
    // cleared on reactivation: churn analysis needs the last reason a user gave even after they
    // come back, the same "persists indefinitely" precedent as passwordChangedAt (V40).
    @Column(name = "deactivation_reason", length = 50)
    private String deactivationReason;

    // Optional free text alongside the reason above -- bounded to spare a churn-analysis query
    // from an unbounded text column, not because 500 characters is a meaningful product limit.
    @Column(name = "deactivation_note", length = 500)
    private String deactivationNote;

    // When the CURRENT (or most recent) self-service deactivation was accepted -- distinct from
    // updatedAt, which moves on every unrelated edit. Powers the configurable self-service
    // reactivation window (AuthService.login()'s deactivated branch, app.account-lifecycle.
    // reactivation-window-*).
    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    // IANA timezone name (e.g. "Asia/Kolkata", "America/New_York"), used to resolve the
    // Dashboard's time-of-day greeting server-side-of-truth instead of trusting whatever the
    // browser's local clock happens to think "now" is — see UserSettingsService and the
    // Settings page's Timezone dropdown. Defaults to Asia/Kolkata (see V11 migration) since
    // every bundled sample statement/currency format in this app is India-specific; users
    // anywhere else can change it in Settings.
    @Column(nullable = false)
    private String timezone = "Asia/Kolkata";

    // Null for every account that has never changed its password since this column was added
    // (V40) -- never backfilled to a guess. Set by AuthService.changePassword() and
    // AuthService.resetPassword() (a forgot-password reset is still a password change for this
    // purpose), read by Settings' Security section ("Last changed ...").
    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    // --- getters / setters ---
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getAccountScope() { return accountScope; }
    public void setAccountScope(String accountScope) { this.accountScope = accountScope; }
    public Set<Role> getRoles() { return roles; }
    public void setRoles(Set<Role> roles) { this.roles = roles; }
    public BigDecimal getLowBalanceThreshold() { return lowBalanceThreshold; }
    public void setLowBalanceThreshold(BigDecimal v) { this.lowBalanceThreshold = v; }
    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public int getFailedLoginAttempts() { return failedLoginAttempts; }
    public void setFailedLoginAttempts(int failedLoginAttempts) { this.failedLoginAttempts = failedLoginAttempts; }
    public Instant getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(Instant lockedUntil) { this.lockedUntil = lockedUntil; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public boolean isPhoneVerified() { return phoneVerified; }
    public void setPhoneVerified(boolean phoneVerified) { this.phoneVerified = phoneVerified; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isSuspended() { return STATUS_SUSPENDED.equals(status); }
    public boolean isDeactivated() { return STATUS_DEACTIVATED.equals(status); }
    public String getDeactivationReason() { return deactivationReason; }
    public void setDeactivationReason(String deactivationReason) { this.deactivationReason = deactivationReason; }
    public String getDeactivationNote() { return deactivationNote; }
    public void setDeactivationNote(String deactivationNote) { this.deactivationNote = deactivationNote; }
    public Instant getDeactivatedAt() { return deactivatedAt; }
    public void setDeactivatedAt(Instant deactivatedAt) { this.deactivatedAt = deactivatedAt; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public Instant getPasswordChangedAt() { return passwordChangedAt; }
    public void setPasswordChangedAt(Instant passwordChangedAt) { this.passwordChangedAt = passwordChangedAt; }
}
