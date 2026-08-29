package com.finora.repository;

import com.finora.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /** How many transactions point at one merchant. Backs the Review Center's "discarding this
     *  would strip the merchant from N ledger rows" guard. */
    long countByMerchantId(UUID merchantId);

    /** How many of a user's transactions are assigned to one category. Backs the category
     *  delete-confirmation dialog's usage summary. */
    long countByUserIdAndCategoryId(UUID userId, UUID categoryId);

    /** Bulk-reassigns every one of a user's transactions off a deleted category onto its
     *  replacement. Backs {@code CategoryService.delete}. */
    @Modifying
    @Query("UPDATE Transaction t SET t.categoryId = :newCategoryId " +
           "WHERE t.userId = :userId AND t.categoryId = :oldCategoryId")
    void reassignCategory(@Param("userId") UUID userId,
                           @Param("oldCategoryId") UUID oldCategoryId,
                           @Param("newCategoryId") UUID newCategoryId);

    /**
     * Transaction counts for many merchants at once, as (merchantId, count) pairs.
     *
     * <p>Exists so the Merchant Review Center's list does not call {@link #countByMerchantId} once
     * per row. That is the N+1 this codebase documents avoiding in AnalyticsService and
     * WorkspaceDashboardService, and a review queue is exactly where the row count is large.
     */
    @Query("""
           SELECT t.merchantId, COUNT(t) FROM Transaction t
            WHERE t.merchantId IN :merchantIds
            GROUP BY t.merchantId
           """)
    List<Object[]> countByMerchantIdIn(@Param("merchantIds") java.util.Collection<UUID> merchantIds);

    // Admin Portal, Operational Dashboard KPI -- "Transactions processed today," same
    // countByCreatedAtAfter convention UserRepository already uses for "new users last N days."
    long countByCreatedAtAfter(Instant threshold);

    // Admin Portal, Operational Dashboard "vs yesterday" delta -- yesterday's count for the same
    // tile, using the platform reporting zone's calendar-day boundaries (see
    // AdminOperationalDashboardService.overview()).
    long countByCreatedAtBetween(Instant start, Instant end);

    /** Projection backing {@link #countByAccountForUser} -- one row per account, never per
     *  transaction, so this scales with account count rather than transaction count. */
    interface AccountTransactionCount {
        UUID getAccountId();
        Long getCount();
    }

    /** Backs the Accounts page's "N transactions" card stat (see AccountService.listForUser) --
     *  a single grouped COUNT query instead of loading every transaction just to count them. */
    @Query("SELECT t.accountId AS accountId, COUNT(t) AS count FROM Transaction t WHERE t.userId = :userId GROUP BY t.accountId")
    List<AccountTransactionCount> countByAccountForUser(@Param("userId") UUID userId);

    List<Transaction> findByUserId(UUID userId);

    /** Like {@link #findByUserId}, but scoped to a specific set of accounts -- for a caller that
     *  must exclude soft-deleted accounts' transactions rather than every transaction the user has
     *  ever owned (see DashboardService's own doc comment on why {@code findByUserId} alone is
     *  wrong for a dashboard total: {@code Transaction.deleted_at} is set when a TRANSACTION is
     *  removed, not when its owning ACCOUNT is -- deleting an account never touches this column, so
     *  {@code findByUserId} keeps returning a soft-deleted account's rows for good, not just during
     *  its retention window). Pass the caller's own live account ids (e.g.
     *  {@code accountRepository.findByUserId(userId)}, which already applies that filter) rather
     *  than re-deriving it here. */
    List<Transaction> findByUserIdAndAccountIdIn(UUID userId, java.util.Collection<UUID> accountIds);

    // Backs the admin User detail view's "N transactions" stat (AdminUserService.getUser).
    long countByUserId(UUID userId);

    // Platform-wide (no userId scoping) -- backs the Admin Dashboard's Needs Attention section.
    // Both fields already existed for per-transaction purposes (see TransactionNormalizer /
    // ImportRuleLearningService for needsCategoryReview, DuplicateDetector for isDuplicateOf);
    // these are just the first queries counting them across every user rather than one at a time.
    long countByNeedsCategoryReviewTrue();
    long countByIsDuplicateOfIsNotNull();

    List<Transaction> findByUserIdAndNeedsCategoryReviewTrueOrderByTxnDateDesc(UUID userId);

    /** Like {@link #findByUserIdAndNeedsCategoryReviewTrueOrderByTxnDateDesc}, but scoped to a
     *  specific set of accounts -- same reason {@link #findByUserIdAndAccountIdIn} exists next to
     *  {@link #findByUserId}: a deleted account's transactions never get their own
     *  {@code deleted_at} set (by design, for the 7-day retention window), so the userId-only query
     *  kept surfacing them on the needs-review queue forever, well past account deletion. Pass the
     *  caller's own live account ids. */
    List<Transaction> findByUserIdAndAccountIdInAndNeedsCategoryReviewTrueOrderByTxnDateDesc(
            UUID userId, java.util.Collection<UUID> accountIds);

    /**
     * SEC-06 (docs/quality/bug-reports/2026-08-19-security-review-findings.md) -- the idempotency
     * check TransactionService.create() runs before inserting a new row. See V97's migration
     * comment for why this is scoped by userId as well as the key: the column has no cross-user
     * uniqueness of its own, only per-user, same as every other per-user identifier in this app.
     *
     * <p>Bug fix (gap review of SEC-06): this used to be a plain derived query, which Hibernate
     * runs through {@code Transaction}'s own {@code @SQLRestriction("deleted_at IS NULL")} --
     * silently, on every query Hibernate generates against the entity, derived or JPQL alike (see
     * that annotation's own doc comment on the entity). A retry of the exact same request against a
     * since-soft-deleted transaction therefore found nothing here, fell through to a second INSERT,
     * and collided with the still-present {@code idempotency_key} value on the deleted row at V97's
     * own unique index -- the opposite of what V97's migration comment requires: a key must keep
     * resolving to the same identity "even if soft-deleted." A native query is not run through
     * Hibernate's HQL translator, which is the layer that injects the restriction, so this sees a
     * soft-deleted row exactly like a live one -- an identity lookup, not a liveness check.
     */
    @Query(value = "SELECT * FROM transactions WHERE user_id = :userId AND idempotency_key = :idempotencyKey",
            nativeQuery = true)
    java.util.Optional<Transaction> findByUserIdAndIdempotencyKey(
            @Param("userId") UUID userId, @Param("idempotencyKey") String idempotencyKey);

    List<Transaction> findByUserIdAndTxnDateBetween(UUID userId, LocalDate from, LocalDate to);

    /**
     * Backs the Ledger page's multi-filter search. All filter params are optional (nullable) —
     * pass null to skip that condition. This mirrors the client-side filter engine 1:1 so the
     * frontend can be a thin layer over this query.
     *
     * The explicit CAST(:keyword AS string) on the LOWER()/CONCAT() branches isn't decorative —
     * without it, calling this with keyword == null throws "function lower(bytea) does not exist"
     * on real Postgres. The PGJDBC driver can't infer a type for an untyped null bind parameter
     * passed through CONCAT()/LOWER(), and Postgres resolves that ambiguity to bytea rather than
     * text. Casting gives the parameter an explicit type so the null case never reaches the
     * ambiguous path — confirmed against a real Postgres error log, not a hypothetical.
     */
    /**
     * bankIds is pre-resolved one layer up (TransactionService.search), not looked up here --
     * com.finora.util.BankRegistry is a static in-memory registry, not a database table, so it
     * can't be joined against in JPQL. TransactionService matches `keyword` against bank
     * official/short names via BankRegistry.search(...) first, and passes the resulting bank ids
     * down as bankIds -- always a real (possibly empty) list, never null: Hibernate needs an
     * actual collection bound to an IN parameter regardless of whether this branch of the OR
     * chain ends up mattering for a given row, and an empty list correctly evaluates that
     * sub-clause to false rather than matching everything.
     */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.userId = :userId
          AND (:accountId IS NULL OR t.accountId = :accountId)
          AND (:categoryId IS NULL OR t.categoryId = :categoryId)
          AND (:type IS NULL OR t.txnType = :type)
          AND (:dateFrom IS NULL OR t.txnDate >= :dateFrom)
          AND (:dateTo IS NULL OR t.txnDate <= :dateTo)
          AND (:amountMin IS NULL OR t.amount >= :amountMin)
          AND (:amountMax IS NULL OR t.amount <= :amountMax)
          AND (:keyword IS NULL
               OR LOWER(t.description) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')) ESCAPE '\\'
               OR LOWER(t.merchant) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')) ESCAPE '\\'
               OR t.accountId IN (
                    SELECT a.id FROM Account a
                    WHERE a.userId = :userId
                      AND (
                           LOWER(a.name) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')) ESCAPE '\\'
                        OR (a.accountHolderName IS NOT NULL AND LOWER(a.accountHolderName) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')) ESCAPE '\\')
                        OR (a.branchName IS NOT NULL AND LOWER(a.branchName) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')) ESCAPE '\\')
                        OR (a.ifscCode IS NOT NULL AND LOWER(a.ifscCode) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')) ESCAPE '\\')
                        OR a.bankId IN :bankIds
                      )
               ))
        """)
    Page<Transaction> search(
            @Param("userId") UUID userId,
            @Param("accountId") UUID accountId,
            @Param("categoryId") UUID categoryId,
            @Param("type") Transaction.Type type,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("amountMin") BigDecimal amountMin,
            @Param("amountMax") BigDecimal amountMax,
            @Param("keyword") String keyword,
            @Param("bankIds") List<String> bankIds,
            Pageable pageable
    );

    @Query("""
        SELECT t FROM Transaction t
        WHERE t.userId = :userId AND t.accountId = :accountId AND t.txnDate = :date
          AND t.amount = :amount AND t.description = :description
        """)
    List<Transaction> findPotentialDuplicates(
            @Param("userId") UUID userId, @Param("accountId") UUID accountId,
            @Param("date") LocalDate date, @Param("amount") BigDecimal amount,
            @Param("description") String description);

    /**
     * Same duplicate heuristic as findPotentialDuplicates, but not scoped to one account —
     * used at CSV staging time (CsvImportService.parseRow), before the user has chosen or
     * created the account this import is going into. Also incidentally catches "you already
     * logged this transaction under a different account by mistake," which the account-scoped
     * version can't.
     */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.userId = :userId AND t.txnDate = :date
          AND t.amount = :amount AND t.description = :description
        """)
    List<Transaction> findPotentialDuplicatesByUser(
            @Param("userId") UUID userId, @Param("date") LocalDate date,
            @Param("amount") BigDecimal amount, @Param("description") String description);

    /**
     * Candidate bank-side transactions for C6.4's cross-source reconciliation: same user, same
     * amount, txn date within a window around a Gmail receipt's date. Description is deliberately
     * NOT compared here -- a receipt's description is a merchant domain ({@code "amazon.in"}) and
     * a bank line reads something like {@code "AMZN MKTPLACE 4521"}, so exact-equality (what
     * {@link #findPotentialDuplicatesByUser} does) would never fire between the two. Narrowing to
     * amount + date window here, then scoring merchant-name similarity in Java
     * ({@code GmailReconciliationMatcher}), keeps the fuzzy part out of SQL.
     *
     * <p>Excludes {@code GMAIL_IMPORT}-sourced rows: a receipt is matched against the bank side of
     * the ledger, not against another already-confirmed receipt -- see the design proposal's own
     * distinction between this direction and the "both already landed" case it deliberately holds.
     */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.userId = :userId AND t.amount = :amount
          AND t.txnDate BETWEEN :startDate AND :endDate
          AND t.txnType = com.finora.entity.Transaction.Type.EXPENSE
          AND t.source <> com.finora.entity.Transaction.Source.GMAIL_IMPORT
        ORDER BY t.txnDate
        """)
    List<Transaction> findCandidatesForGmailReconciliation(
            @Param("userId") UUID userId, @Param("amount") BigDecimal amount,
            @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    /** Backs "View Imported Transactions" and "Delete Statement Import" — every transaction a
     *  given confirmed CSV import produced. See StatementImportService. */
    List<Transaction> findByStatementImportId(UUID statementImportId);

    /** Every transaction currently resolved to a given merchant -- used by MerchantService.merge()
     *  to repoint the absorbed merchant's transactions onto the surviving merchant (spec §5.4
     *  step 2), and available for a future merchant-detail "recent transactions" view. */
    List<Transaction> findByUserIdAndMerchantId(UUID userId, UUID merchantId);

    /** Used when deleting a statement: any surviving transaction whose duplicate/transfer
     *  pairing pointed at one of the transactions being removed needs its reconciliation flags
     *  reset, or it'd be left referencing a row that no longer visibly exists. */
    List<Transaction> findByIsDuplicateOfIn(List<UUID> ids);
    List<Transaction> findByTransferPairIdIn(List<UUID> ids);

    /** Backs the Statement Imports page's per-import "duplicate count" (Financial Intelligence
     *  Workspace, Statement Imports module) -- one grouped COUNT query for the whole user rather
     *  than one query per statement, same AccountTransactionCount-style projection used by
     *  countByAccountForUser above. Only rows with a non-null statementImportId are counted (a
     *  manually-entered transaction can be flagged DUPLICATE too, but it has no statement to
     *  attribute the count to). */
    interface StatementImportDuplicateCount {
        UUID getStatementImportId();
        Long getCount();
    }

    @Query("SELECT t.statementImportId AS statementImportId, COUNT(t) AS count FROM Transaction t " +
           "WHERE t.userId = :userId AND t.reconciliationStatus = :status AND t.statementImportId IS NOT NULL " +
           "GROUP BY t.statementImportId")
    List<StatementImportDuplicateCount> countDuplicatesByStatementImportForUser(
            @Param("userId") UUID userId, @Param("status") Transaction.ReconciliationStatus status);

    /** Same reasoning as findByIsDuplicateOfIn/findByTransferPairIdIn -- an INCOME row marked
     *  REFUND points back at the EXPENSE it reverses via refundOfTransactionId (see
     *  ReconciliationService's refund pass); deleting that expense must not leave the refund
     *  pointing at a row that no longer exists. */
    List<Transaction> findByRefundOfTransactionIdIn(List<UUID> ids);

    /**
     * The months this user has any transaction in, newest last.
     *
     * <p>BH-042: {@code ReportService.availableMonths} loaded every transaction the user has ever
     * had, mapped each to a {@code YearMonth}, and then discarded all but the distinct values --
     * to populate a dropdown. The answer is a handful of strings and the query was proportional to
     * the whole ledger. The database can produce exactly the distinct set.
     *
     * <p>Returns the first day of each month rather than a formatted string: date formatting is
     * not the database's job, and {@code YearMonth.from} in the caller keeps the wire format
     * decided in one place.
     */
    @Query("""
           SELECT DISTINCT t.txnDate FROM Transaction t
            WHERE t.userId = :userId
           """)
    List<LocalDate> findDistinctTransactionDates(@Param("userId") UUID userId);

    /**
     * Every refund leg this user has, whenever it landed.
     *
     * <p>BH-005: {@code ReportService} reports one calendar month by date range, but a refund
     * routinely arrives in a LATER month than the purchase it reverses -- so the rows that offset
     * a month's expenses are frequently outside the window that month was queried with. Netting
     * against only the in-window refunds would fix the same-month case and leave the cross-month
     * case reporting the purchase at full price for ever, which is the defect.
     *
     * <p>Unpaginated deliberately, and safe to be: this returns only rows reconciliation has
     * matched as refunds, which is a small fraction of a ledger. It is not a general
     * "all transactions" load.
     */
    List<Transaction> findByUserIdAndReconciliationStatus(
            UUID userId, Transaction.ReconciliationStatus status);

    /**
     * Same as {@link #findByUserIdAndReconciliationStatus}, for a caller that needs more than one
     * status at once -- specifically {@code RefundNetting.from}'s callers, which must feed it both
     * {@code REFUND} and {@code REVERSAL} rows (both are refund legs {@code RefundNetting} nets
     * off their original expense the same way; see that class). Kept as a separate method rather
     * than replacing the single-status one above: most callers of the singular form genuinely want
     * exactly one status, and forcing a {@code List.of(status)} everywhere there would read as
     * ceremony with no benefit.
     */
    List<Transaction> findByUserIdAndReconciliationStatusIn(
            UUID userId, java.util.Collection<Transaction.ReconciliationStatus> statuses);

    // Admin Portal, Reconciliation Monitor module -- platform-wide breakdown of every
    // reconciliation outcome, one grouped COUNT query rather than loading every transaction into
    // memory (WorkspaceDashboardService.summarize() does that, but per-user; doing the same
    // in-memory pattern platform-wide would mean loading the entire transactions table). Same
    // "simple indexed counts, not a new reporting subsystem" discipline as AdminStatsService.
    @Query("SELECT t.reconciliationStatus, COUNT(t) FROM Transaction t GROUP BY t.reconciliationStatus")
    List<Object[]> platformReconciliationStatusCounts();

    // isRecurring is a separate boolean flag, not a ReconciliationStatus value (see Transaction
    // entity) -- needs its own count alongside the grouped query above.
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.isRecurring = true")
    long countPlatformRecurring();

    // Admin Portal, Platform Analytics module -- platform-wide EXPENSE spend grouped by
    // categoryId/merchantId, same exclusion rules as AnalyticsService.activeExpenseTransactions()
    // (no duplicates, no transfers, no REFUND-status income). Grouped by id here, not name --
    // Transaction has no mapped association to Category/Merchant (just a plain UUID FK column),
    // so there's no JPQL join path to their name field. AdminPlatformAnalyticsService resolves
    // names and re-groups by name in Java as a second step (see its class comment for why that's
    // the right place for it, not a bigger schema change).
    @Query("SELECT t.categoryId, COUNT(t), SUM(t.amount) FROM Transaction t " +
           "WHERE t.isDuplicateOf IS NULL AND t.isTransfer = false AND t.reconciliationStatus <> :refundStatus " +
           "AND t.txnType = :expenseType AND t.categoryId IS NOT NULL GROUP BY t.categoryId")
    List<Object[]> platformCategorySpendTotals(@Param("refundStatus") Transaction.ReconciliationStatus refundStatus,
                                                @Param("expenseType") Transaction.Type expenseType);

    @Query("SELECT t.merchantId, COUNT(t), SUM(t.amount) FROM Transaction t " +
           "WHERE t.isDuplicateOf IS NULL AND t.isTransfer = false AND t.reconciliationStatus <> :refundStatus " +
           "AND t.txnType = :expenseType AND t.merchantId IS NOT NULL GROUP BY t.merchantId")
    List<Object[]> platformMerchantSpendTotals(@Param("refundStatus") Transaction.ReconciliationStatus refundStatus,
                                                @Param("expenseType") Transaction.Type expenseType);

    /**
     * AccountPurgeSweepService. Native, bypassing Hibernate's {@code @SQLDelete} entirely -- a
     * derived/JPQL {@code deleteByUserId} on this entity would only soft-delete (set
     * {@code deleted_at}), not purge, since {@code Transaction extends BaseEntity}. Named
     * {@code hardDeleteByUserId}, not {@code deleteByUserId}, so the bypass is visible at every
     * call site -- same naming discipline {@link #findByUserId} above.
     *
     * <p>One native statement for the whole user, not a loop of {@code repository.delete(entity)}:
     * this table has two self-referential FKs ({@code is_duplicate_of}, {@code transfer_pair_id}),
     * and Postgres only checks non-deferred FK constraints at end-of-statement, not per row. A
     * single bulk {@code DELETE} removes every row for the user atomically, so two of their own
     * transactions pointing at each other never trip a constraint violation -- a row-by-row loop
     * could, depending on iteration order.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "DELETE FROM transactions WHERE user_id = :userId", nativeQuery = true)
    void hardDeleteByUserId(@Param("userId") UUID userId);
}
