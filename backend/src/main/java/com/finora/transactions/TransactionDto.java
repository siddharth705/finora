package com.finora.transactions;

import com.finora.entity.Transaction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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

    /**
     * SEC-06 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). {@code
     * idempotencyKey} is optional and additive -- see V97's migration comment for the full design
     * reasoning. This canonical constructor carries it as the last field; the shorter constructor
     * below preserves every existing 7-arg call site (production clients that don't send a key yet,
     * and the large existing test suite) rather than forcing an unrelated change on all of them for
     * one new, optional field.
     */
    public record CreateRequest(UUID accountId, String categoryName, LocalDate date,
                                 @Size(max = 500, message = DESCRIPTION_SIZE_MESSAGE) String description,
                                 BigDecimal amount, String type,
                                 @Size(max = 20, message = TAGS_COUNT_MESSAGE)
                                 List<@Size(max = 255, message = TAG_SIZE_MESSAGE) String> tags,
                                 @Size(max = 255, message = "Idempotency key can't exceed 255 characters")
                                 String idempotencyKey) {
        public CreateRequest(UUID accountId, String categoryName, LocalDate date, String description,
                              BigDecimal amount, String type, List<String> tags) {
            this(accountId, categoryName, date, description, amount, type, tags, null);
        }
    }

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

    public record FilterRequest(UUID accountId, UUID categoryId, String type, String status, LocalDate dateFrom,
                                 LocalDate dateTo, BigDecimal amountMin, BigDecimal amountMax,
                                 String keyword, int page, int size, String sortField, String sortDir) {}

    /**
     * The most rows one bulk call may touch.
     *
     * <p>Both bulk endpoints took an unbounded {@code List<UUID>} straight off the request body,
     * with no size constraint and no rate limiter on {@code /api/v1/transactions/**}. Each element
     * costs a findById plus a write, and bulkDelete then runs reconcileForUser() and
     * detectForUser() on top -- so a single ~10 MB JSON array (roughly 270,000 UUIDs) turned one
     * authenticated request into hundreds of thousands of queries, holding a connection from a
     * pool capped at 10. Every id also went into a jsonb audit row, equally unbounded.
     *
     * <p>500 is far above any selection a review table realistically produces and far below
     * anything that hurts. This is the clamp discipline PageBounds already applies to every read
     * endpoint; the write path never got it.
     */
    public static final int MAX_BULK_IDS = 500;

    private static final String BULK_SIZE_MESSAGE =
            "A bulk action can cover at most " + MAX_BULK_IDS + " transactions at a time.";

    /**
     * Replaces a raw {@code Map<String, Object>} body that was cast by hand:
     * {@code (List<String>) body.get("ids")}, then {@code UUID.fromString}, then
     * {@code (String) body.get("category")}. It was the only endpoint in the application not
     * taking a typed, validated DTO, so it bypassed bean validation entirely and turned ordinary
     * bad input into 500s -- a missing key threw NullPointerException, a non-array {@code ids}
     * threw ClassCastException, a malformed uuid threw IllegalArgumentException, and all three
     * landed on the catch-all handler as INTERNAL_ERROR logged as "Unhandled exception". A null
     * category reached resolveOrCreateCategory unchecked.
     */
    public record BulkRecategorizeRequest(
            @NotEmpty(message = "Select at least one transaction.")
            @Size(max = MAX_BULK_IDS, message = BULK_SIZE_MESSAGE)
            List<UUID> ids,
            @NotBlank(message = "Category name is required")
            @Size(max = 255, message = "Category name can't exceed 255 characters")
            String category) {}

    /** Same bound as {@link BulkRecategorizeRequest}, for the delete endpoint's id list. */
    public record BulkDeleteRequest(
            @NotEmpty(message = "Select at least one transaction.")
            @Size(max = MAX_BULK_IDS, message = BULK_SIZE_MESSAGE)
            List<UUID> ids) {}
}
