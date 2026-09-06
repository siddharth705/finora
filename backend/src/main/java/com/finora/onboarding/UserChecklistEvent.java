package com.finora.onboarding;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_checklist_events")
public class UserChecklistEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "item_key", nullable = false, length = 30)
    private String itemKey;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt = Instant.now();

    public UserChecklistEvent() {}

    public UserChecklistEvent(UUID userId, String itemKey) {
        this.userId = userId;
        this.itemKey = itemKey;
    }

    public UUID getUserId() { return userId; }
    public String getItemKey() { return itemKey; }
    public Instant getCompletedAt() { return completedAt; }
}
