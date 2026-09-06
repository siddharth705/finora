package com.finora.onboarding;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_financial_focus")
public class UserFinancialFocus {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "focus_key", nullable = false, length = 30)
    private String focusKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UserFinancialFocus() {}

    public UserFinancialFocus(UUID userId, String focusKey) {
        this.userId = userId;
        this.focusKey = focusKey;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getFocusKey() { return focusKey; }
    public Instant getCreatedAt() { return createdAt; }
}
