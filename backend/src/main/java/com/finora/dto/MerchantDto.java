package com.finora.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** See docs/financial-intelligence-engine-spec.md §5.1-5.4. topCategory/topCategoryConfidence are
 *  null for a merchant with no confirmations yet (a freshly-resolved merchant before any user
 *  confirms a category for it) -- distribution will be an empty list in that case, not absent. */
public record MerchantDto(
        UUID id, String canonicalName, String logoUrl, String website,
        String topCategory, Integer topCategoryConfidence,
        List<DistributionEntry> distribution
) {
    public record DistributionEntry(String category, int confirmationCount, int confidence) {}

    /** Every field optional -- only supplied ones change (same partial-update convention as
     *  TransactionDto.UpdateRequest / RuleDto.UpdateRequest). @Size bounds match
     *  merchants.canonical_name VARCHAR(255)/website VARCHAR(500) -- without these, an oversized
     *  value threw a raw DB constraint-violation (unhandled 500) instead of a clean 400. */
    public record UpdateRequest(@Size(max = 255) String canonicalName, @Size(max = 500) String website) {}

    public record MergeRequest(@NotNull(message = "mergeFromMerchantId is required") UUID mergeFromMerchantId) {}

    /** Backs POST /{merchantId}/confirm-category (spec §5.5). applyToTransactionId is the
     *  specific transaction this confirmation resolves -- see TransactionService.confirmMerchantCategory. */
    public record ConfirmCategoryRequest(
            @NotNull(message = "categoryId is required") UUID categoryId,
            @NotNull(message = "applyToTransactionId is required") UUID applyToTransactionId
    ) {}

    public record AuditEntry(String action, String previousCategory, String newCategory, Instant createdAt) {}
}
