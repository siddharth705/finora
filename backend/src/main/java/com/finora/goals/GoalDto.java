package com.finora.goals;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record GoalDto(UUID id, String name, BigDecimal targetAmount, BigDecimal currentAmount, LocalDate targetDate) {
    // The frontend (Goals.tsx) already guards against a non-positive target amount and blank
    // name client-side, but nothing enforced this server-side -- any other API caller could
    // create a goal with a zero/negative target or a blank name. currentAmount is intentionally
    // left nullable (a brand-new goal legitimately starts with none saved yet) but still can't
    // be negative if provided.
    public record CreateRequest(
            @NotBlank(message = "Goal name is required") String name,
            @NotNull(message = "Target amount is required") @DecimalMin(value = "0.01", message = "Target amount must be greater than zero") BigDecimal targetAmount,
            @DecimalMin(value = "0.00", message = "Starting amount can't be negative") BigDecimal currentAmount,
            LocalDate targetDate) {}

    // A contribution of zero or a negative amount would silently corrupt a goal's currentAmount
    // (see GoalService.addContribution's own floor-at-zero defense-in-depth for the belt-and-
    // braces half of this fix).
    public record ContributionRequest(
            @NotNull(message = "Contribution amount is required") @DecimalMin(value = "0.01", message = "Contribution amount must be greater than zero") BigDecimal amount) {}
}
