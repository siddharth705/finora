package com.finora.repository;

import com.finora.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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

    // Backs the admin User detail view's "N transactions" stat (AdminUserService.getUser).
    long countByUserId(UUID userId);

    // Platform-wide (no userId scoping) -- backs the Admin Dashboard's Needs Attention section.
    // Both fields already existed for per-transaction purposes (see TransactionNormalizer /
    // ImportRuleLearningService for needsCategoryReview, DuplicateDetector for isDuplicateOf);
    // these are just the first queries counting them across every user rather than one at a time.
    long countByNeedsCategoryReviewTrue();
    long countByIsDuplicateOfIsNotNull();

    List<Transaction> findByUserIdAndNeedsCategoryReviewTrueOrderByTxnDateDesc(UUID userId);

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
}
