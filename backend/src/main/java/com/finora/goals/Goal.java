package com.finora.goals;

import com.finora.entity.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "goals")
// See Account.java's @SQLDelete comment: BaseEntity's @Version means Hibernate binds a
// second (version) parameter on delete regardless of custom SQL, so it must be present here.
@SQLDelete(sql = "UPDATE goals SET deleted_at = now(), version = version + 1 WHERE id = ? AND version = ?")
@SQLRestriction("deleted_at IS NULL")
public class Goal extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String name;

    @Column(name = "target_amount", nullable = false)
    private BigDecimal targetAmount;

    @Column(name = "current_amount", nullable = false)
    private BigDecimal currentAmount = BigDecimal.ZERO;

    @Column(name = "target_date")
    private LocalDate targetDate;

    // D-25 PR3-B. Set once by GoalService.markCompletedIfReached, the first time currentAmount
    // reaches targetAmount -- never overwritten afterward. See V94's migration comment.
    @Column(name = "completed_at")
    private Instant completedAt;

    // Contributions are fetched via GoalContributionRepository.findByGoalId() in the
    // service layer rather than mapped here, to keep the entity graph simple.

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getTargetAmount() { return targetAmount; }
    public void setTargetAmount(BigDecimal targetAmount) { this.targetAmount = targetAmount; }
    public BigDecimal getCurrentAmount() { return currentAmount; }
    public void setCurrentAmount(BigDecimal currentAmount) { this.currentAmount = currentAmount; }
    public LocalDate getTargetDate() { return targetDate; }
    public void setTargetDate(LocalDate targetDate) { this.targetDate = targetDate; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
