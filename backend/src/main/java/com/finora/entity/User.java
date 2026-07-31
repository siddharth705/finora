package com.finora.entity;

import jakarta.persistence.*;
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

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    // Legacy single-role string, kept for backward compatibility -- see
    // V16__rbac_roles_permissions.sql and AuthorizationService. New code assigning a user
    // access beyond this should prefer `roles` below (supports more than one, and drives
    // fine-grained permissions rather than just USER/ADMIN); this column is not being actively
    // deprecated yet since a good deal of existing code (registration, tests) still reads/writes
    // it directly, but it should not gain new callers going forward.
    @Column(nullable = false)
    private String role = "USER";

    // Database-driven RBAC (docs/engineering-directive-phase1.md, Priority 2). A user can hold
    // zero or more explicit roles here in addition to whatever `role` above implies --
    // AuthorizationService computes the union of both when building the authenticated
    // principal's granted authorities, so adding a row here can only ever grant additional
    // access relative to today's behavior, never take any away.
    @ManyToMany(fetch = FetchType.EAGER)
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

    // "ACTIVE" or "SUSPENDED" -- see V23__user_account_status.sql. Checked in AuthService.login
    // and AuthService.refresh; a suspended user can't obtain a new access token, but any access
    // token issued before the suspension keeps working until it naturally expires (15 min
    // default) since JwtAuthFilter doesn't re-check the database on every request. That's a
    // deliberate tradeoff, not an oversight -- see AdminUserService.suspend's doc comment.
    @Column(nullable = false)
    private String status = "ACTIVE";

    // IANA timezone name (e.g. "Asia/Kolkata", "America/New_York"), used to resolve the
    // Dashboard's time-of-day greeting server-side-of-truth instead of trusting whatever the
    // browser's local clock happens to think "now" is — see UserSettingsService and the
    // Settings page's Timezone dropdown. Defaults to Asia/Kolkata (see V11 migration) since
    // every bundled sample statement/currency format in this app is India-specific; users
    // anywhere else can change it in Settings.
    @Column(nullable = false)
    private String timezone = "Asia/Kolkata";

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
    public boolean isSuspended() { return "SUSPENDED".equals(status); }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
}
