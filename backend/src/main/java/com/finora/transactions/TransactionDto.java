package com.finora.transactions;

import com.finora.entity.Transaction;
import jakarta.validation.constraints.Size;

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

    // Bug fix: neither request record had any Bean Validation at all, and TransactionController's
    // create()/update() didn't even apply @Valid -- description (VARCHAR(500)) could only fail
    // via a raw DataIntegrityViolationException (an unhandled 500 instead of a clean 400), and
    // notes (TEXT)/tags (VARCHAR(255)[], no length cap in the DB) had no bound at all: any
    // authenticated user could submit a multi-megabyte notes field or a huge tags array, self-
    // service reachable, persisted indefinitely and re-serialized on every subsequent read.
    private static final String DESCRIPTION_SIZE_MESSAGE = "Description can't exceed 500 characters";
    private static final String NOTES_SIZE_MESSAGE = "Notes can't exceed 5000 characters";
    private static final String TAGS_COUNT_MESSAGE = "A transaction can have at most 20 tags";
    private static final String TAG_SIZE_MESSAGE = "Each tag can't exceed 255 characters";

    public record CreateRequest(UUID accountId, String categoryName, LocalDate date,
                                 @Size(max = 500, message = DESCRIPTION_SIZE_MESSAGE) String description,
                                 BigDecimal amount, String type,
                                 @Size(max = 20, message = TAGS_COUNT_MESSAGE)
                                 List<@Size(max = 255, message = TAG_SIZE_MESSAGE) String> tags) {}

    /**
     * Full-edit payload for the Transactions page's Edit action. Deliberately excludes accountId:
     * the edit form lets you fix category, merchant, description, date, amount, type, notes and
     * tags on an existing entry, but moving a transaction to a different account is a bigger
     * decision (it changes which account's balance the amount should count against) that this
     * form doesn't offer — delete and re-create under the right account instead.
     */
    public record UpdateRequest(LocalDate date,
                                 @Size(max = 500, message = DESCRIPTION_SIZE_MESSAGE) String description,
                                 @Size(max = 255, message = "Merchant can't exceed 255 characters") String merchant,
                                 BigDecimal amount, String type, String categoryName,
                                 @Size(max = 5000, message = NOTES_SIZE_MESSAGE) String notes,
                                 @Size(max = 20, message = TAGS_COUNT_MESSAGE)
                                 List<@Size(max = 255, message = TAG_SIZE_MESSAGE) String> tags) {}

    public record FilterRequest(UUID accountId, UUID categoryId, String type, LocalDate dateFrom,
                                 LocalDate dateTo, BigDecimal amountMin, BigDecimal amountMax,
                                 String keyword, int page, int size, String sortField, String sortDir) {}
}
