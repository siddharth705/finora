package com.finora.transactions;

import com.finora.entity.Transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record TransactionDto(
        UUID id,
        UUID accountId,
        UUID categoryId,
        String categoryName,
        LocalDate date,
        String description,
        String merchant,
        String paymentMethod,
        BigDecimal amount,
        String type,
        List<String> tags,
        String notes,
        String reconciliationStatus,
        boolean recurring,
        boolean needsCategoryReview,
        boolean categoryManuallySet
) {
    public static TransactionDto from(Transaction t, String categoryName) {
        return new TransactionDto(t.getId(), t.getAccountId(), t.getCategoryId(), categoryName, t.getTxnDate(),
                t.getDescription(), t.getMerchant(), t.getPaymentMethod(), t.getAmount(),
                t.getTxnType().name(), t.getTags(), t.getNotes(), t.getReconciliationStatus().name(), t.isRecurring(),
                t.isNeedsCategoryReview(), t.isCategoryManuallySet());
    }

    public record CreateRequest(UUID accountId, String categoryName, LocalDate date, String description,
                                 BigDecimal amount, String type, List<String> tags) {}

    /**
     * Full-edit payload for the Transactions page's Edit action. Deliberately excludes accountId:
     * the edit form lets you fix category, merchant, description, date, amount, type, notes and
     * tags on an existing entry, but moving a transaction to a different account is a bigger
     * decision (it changes which account's balance the amount should count against) that this
     * form doesn't offer — delete and re-create under the right account instead.
     */
    public record UpdateRequest(LocalDate date, String description, String merchant, BigDecimal amount,
                                 String type, String categoryName, String notes, List<String> tags) {}

    public record FilterRequest(UUID accountId, UUID categoryId, String type, LocalDate dateFrom,
                                 LocalDate dateTo, BigDecimal amountMin, BigDecimal amountMax,
                                 String keyword, int page, int size, String sortField, String sortDir) {}
}
