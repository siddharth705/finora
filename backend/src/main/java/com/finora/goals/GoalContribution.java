package com.finora.goals;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "goal_contributions")
public class GoalContribution {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "goal_id", nullable = false)
    private UUID goalId;

    @Column(nullable = false)
    private BigDecimal amount;

    // No default here on purpose -- LocalDate.now() (server zone) was the bug (see
    // GoalService.create()/addContribution(), both of which now set this explicitly using the
    // contributing user's own timezone). Leaving an implicit default would mean any future
    // caller that forgets to set this explicitly gets silently the wrong date instead of a loud
    // NOT NULL constraint failure pointing straight at the omission.
    @Column(name = "contributed_at", nullable = false)
    private LocalDate contributedAt;

    public UUID getId() { return id; }
    public UUID getGoalId() { return goalId; }
    public void setGoalId(UUID goalId) { this.goalId = goalId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public LocalDate getContributedAt() { return contributedAt; }
    public void setContributedAt(LocalDate contributedAt) { this.contributedAt = contributedAt; }
}
