package com.finora.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Financial Intelligence Workspace, System Settings module. Separate from {@link User}'s own
 * personal-preference columns (lowBalanceThreshold/theme/timezone) -- see V22 migration's comment
 * for why. One row per user, created lazily on first read (see UserSettingsWorkspaceService).
 */
@Entity
@Table(name = "user_settings")
public class UserSettings {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    // 0-100, same scale as ConfidenceEngine's existing int confidence values -- see V22's
    // migration comment for why this isn't a 0.0-1.0 decimal.
    @Column(name = "auto_apply_confidence_threshold", nullable = false)
    private int autoApplyConfidenceThreshold = 90;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public int getAutoApplyConfidenceThreshold() { return autoApplyConfidenceThreshold; }
    public void setAutoApplyConfidenceThreshold(int autoApplyConfidenceThreshold) { this.autoApplyConfidenceThreshold = autoApplyConfidenceThreshold; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
