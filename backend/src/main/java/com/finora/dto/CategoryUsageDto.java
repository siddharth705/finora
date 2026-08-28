package com.finora.dto;

/**
 * What a category delete would touch. {@code learningRowCount} is the Learning Engine's
 * per-merchant training data ({@code merchant_category_learning}) -- counted and reported
 * alongside the other three because it is a dependent in exactly the same sense: its FK is
 * {@code ON DELETE CASCADE}, so a delete with no reassignment target destroys it silently,
 * which is the loss {@code MerchantLearningService.onCategoryDeleted} exists to prevent.
 */
public record CategoryUsageDto(long transactionCount, boolean hasBudget, long ruleCount,
                                long learningRowCount) {}
