package com.finora.budgets;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record BudgetDto(UUID id, UUID categoryId, String categoryName, BigDecimal monthlyLimit, BigDecimal spentThisMonth) {
    // Budgets.tsx already guards client-side against a zero/negative monthlyLimit before
    // dividing by it for the progress bar -- nothing enforced this server-side, so a
    // non-browser caller could set a budget limit of 0 or negative.
    public record UpsertRequest(
            @NotBlank(message = "Category name is required") String categoryName,
            @NotNull(message = "Monthly limit is required") @DecimalMin(value = "0.01", message = "Monthly limit must be greater than zero") BigDecimal monthlyLimit) {}
}
