package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * The one and only row in platform_settings (see V27__platform_settings.sql -- id is pinned to 1
 * by a CHECK constraint). Platform-wide, not per-user -- contrast with UserSettings, which is one
 * row per account. Read/written exclusively through PlatformSettingsService, gated by
 * SYSTEM_SETTINGS.
 */
@Entity
@Table(name = "platform_settings")
public class PlatformSettings {

    @Id
    private short id = 1;

    @Column(name = "registrations_enabled", nullable = false)
    private boolean registrationsEnabled = true;

    @Column(name = "max_failed_login_attempts", nullable = false)
    private int maxFailedLoginAttempts = 5;

    @Column(name = "lockout_duration_minutes", nullable = false)
    private int lockoutDurationMinutes = 15;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    // See V33__bootstrap_admin.sql / SetupService. Starts false on every fresh install; flips to
    // true exactly once, inside the same transaction that creates the first real SUPER_ADMIN and
    // locks the bootstrap account. BootstrapService checks this single column on every startup
    // rather than scanning the users table for an existing SUPER_ADMIN.
    @Column(name = "setup_completed", nullable = false)
    private boolean setupCompleted = false;

    public short getId() { return id; }
    public boolean isRegistrationsEnabled() { return registrationsEnabled; }
    public void setRegistrationsEnabled(boolean registrationsEnabled) { this.registrationsEnabled = registrationsEnabled; }
    public int getMaxFailedLoginAttempts() { return maxFailedLoginAttempts; }
    public void setMaxFailedLoginAttempts(int maxFailedLoginAttempts) { this.maxFailedLoginAttempts = maxFailedLoginAttempts; }
    public int getLockoutDurationMinutes() { return lockoutDurationMinutes; }
    public void setLockoutDurationMinutes(int lockoutDurationMinutes) { this.lockoutDurationMinutes = lockoutDurationMinutes; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public boolean isSetupCompleted() { return setupCompleted; }
    public void setSetupCompleted(boolean setupCompleted) { this.setupCompleted = setupCompleted; }
}
