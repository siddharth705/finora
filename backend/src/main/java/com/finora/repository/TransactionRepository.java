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

    /** Projection backing the counterparty backfill: the narration is the classifier's ONLY input,
     *  so a full entity would be loaded per row for two fields. Also keeps the sweep out of the
     *  persistence context entirely -- see {@link #applyCounterpartyTyping} for why that matters. */
    interface CounterpartyBackfillRow {
        UUID getId();
        String getDescription();
    }

    /**
     * One page of rows the counterparty classifier has not answered for at the current revision.
     *
     * <p>NULL is the never-typed state (V143); {@code < :version} is the re-type-after-a-bump state
     * described on {@link com.finora.util.CounterpartyClassifier#VERSION}. Both are served by
     * {@code idx_transactions_counterparty_classifier_version}, and in the drained steady state the
     * predicate matches nothing, so the scheduled sweep costs an empty index probe.
     *
     * <p>No ORDER BY, deliberately. Progress does not depend on ordering: every returned row either
     * gets stamped (and leaves the candidate set) or fails and is retried, so a page is never the
     * same page twice unless nothing in it could be typed at all. Paying for a sort of the whole
     * candidate set on every pass would buy determinism nothing here needs.
     *
     * <p>Soft-deleted rows are excluded for free by {@code Transaction}'s {@code @SQLRestriction},
     * and that is the behaviour wanted: typing a row the user cannot see is wasted work, and if one
     * is ever restored it re-enters this query still carrying a NULL version.
     *
     * <p><b>When a user-facing correction for counterparty exists, it needs its own exclusion
     * here</b> -- the same role {@code category_manually_set} plays for the category columns. A
     * version comparison alone will happily overwrite a human's answer.
     */
    @Query("""
            SELECT t.id AS id, t.description AS description
            FROM Transaction t
            WHERE t.counterpartyClassifierVersion IS NULL
               OR t.counterpartyClassifierVersion < :version
            """)
    List<CounterpartyBackfillRow> findRowsNeedingCounterpartyTyping(@Param("version") short version,
                                                                     Pageable pageable);

    /**
     * Writes one row's counterparty answer.
     *
     * <p>A bulk update rather than loading the entity and saving it, for a reason beyond speed: a
     * JPQL update bypasses Hibernate's lifecycle callbacks, so it does NOT touch {@code updated_at}
     * or bump the optimistic-lock {@code version}. Backfilling a derived column must not look like
     * a user editing their transaction, and it must not lose a race against someone who genuinely
     * is editing it.
     *
     * @return 1 when the row was updated, 0 when it no longer matches (deleted between the
     *         discovery query and this write) -- the caller counts that as skipped, not failed
     */
    @Modifying
    @Query("""
            UPDATE Transaction t
            SET t.counterpartyType = :type,
                t.counterpartyKey = :key,
                t.counterpartyClassifierVersion = :version
            WHERE t.id = :id
            """)
    int applyCounterpartyTyping(@Param("id") UUID id,
                                 @Param("type") com.finora.util.CounterpartyType type,
                                 @Param("key") String key,
                                 @Param("version") short version);

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

    /** Like {@link #countByUserId}, but scoped to a specific set of accounts -- excludes a
     *  soft-deleted account's rows, which {@code countByUserId} alone would keep counting forever
     *  (see {@link #findByUserIdAndAccountIdIn}'s own doc comment). Pass the caller's own live
     *  account ids rather than re-deriving them here. */
    long countByUserIdAndAccountIdIn(UUID userId, java.util.Collection<UUID> accountIds);

    // Platform-wide (no userId scoping) -- backs the Admin Dashboard's Needs Attention section.
    // Both fields already existed for per-transaction purposes (see TransactionNormalizer /
    // ImportRuleLearningService for needsCategoryReview, DuplicateDetector for isDuplicateOf);
    // these are just the first queries counting them across every user rather than one at a time.
    long countByNeedsCategoryReviewTrue();
    long countByIsDuplicateOfIsNotNull();

    List<Transaction> findByUserIdAndNeedsCategoryReviewTrueOrderByTxnDateDesc(UUID userId);

    /** Like {@link #findByUserIdAndNeedsCategoryReviewTrueOrderByTxnDateDesc}, scoped to a set of
     *  live account ids -- excludes a soft-deleted account's rows, which otherwise keep surfacing
     *  in the "Ask Once" review queue forever (see {@link #findByUserIdAndAccountIdIn}'s own doc
     *  comment for why {@code findByUserId} alone is wrong here). */
    List<Transaction> findByUserIdAndNeedsCategoryReviewTrueAndAccountIdInOrderByTxnDateDesc(
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

    /** Like {@link #findByUserIdAndTxnDateBetween}, scoped to a set of live account ids -- excludes
     *  a soft-deleted account's rows, which {@code findByUserIdAndTxnDateBetween} alone would keep
     *  returning forever (see {@link #findByUserIdAndAccountIdIn}'s own doc comment). */
    List<Transaction> findByUserIdAndTxnDateBetweenAndAccountIdIn(
            UUID userId, LocalDate from, LocalDate to, java.util.Collection<UUID> accountIds);

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
     *
     * categoryIds is resolved the same way, one layer up, for the same reason: categoryId is a
     * plain UUID column with no JPA association to Category (see TransactionGroupingService's own
     * doc comment for the identical constraint on merchantId), so `t.categoryId IN :categoryIds`
     * is as close as this query can get to matching on a category's NAME directly.
     */
    /**
     * Deleted-account leak (see {@link #findByUserIdAndAccountIdIn}'s own doc comment): when the
     * caller didn't ask for one specific account (:accountId IS NULL), the result must still be
     * scoped to the user's live accounts, or a soft-deleted account's transactions surface in the
     * Ledger's default "all accounts" search forever. When :accountId IS supplied, that explicit
     * filter is trusted as-is (unchanged from before) and :liveAccountIds is not consulted --
     * TransactionService.getOwnedAccount-style ownership checks elsewhere already gate which
     * accountId values a caller can pass in the first place.
     */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.userId = :userId
          AND (:accountId IS NULL OR t.accountId = :accountId)
          AND (:accountId IS NOT NULL OR t.accountId IN :liveAccountIds)
          AND (:categoryId IS NULL OR t.categoryId = :categoryId)
          AND (:type IS NULL OR t.txnType = :type)
          AND (:status IS NULL OR t.reconciliationStatus = :status)
          AND (:dateFrom IS NULL OR t.txnDate >= :dateFrom)
          AND (:dateTo IS NULL OR t.txnDate <= :dateTo)
          AND (:amountMin IS NULL OR t.amount >= :amountMin)
          AND (:amountMax IS NULL OR t.amount <= :amountMax)
          AND (:keyword IS NULL
               OR LOWER(t.description) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')) ESCAPE '\\'
               OR LOWER(t.merchant) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')) ESCAPE '\\'
               OR t.categoryId IN :categoryIds
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
            @Param("status") Transaction.ReconciliationStatus status,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("amountMin") BigDecimal amountMin,
            @Param("amountMax") BigDecimal amountMax,
            @Param("keyword") String keyword,
            @Param("bankIds") List<String> bankIds,
            @Param("categoryIds") List<UUID> categoryIds,
            @Param("liveAccountIds") List<UUID> liveAccountIds,
            Pageable pageable
    );

    /** A2 (two-pass mobile audit, 2026-09-01). Description compared space-trimmed and case-folded,
     *  not raw -- see {@link #findPotentialDuplicatesByUserAndAccountIdIn}'s doc comment for why.
     *  No production call site (only {@code TransactionRepositoryIT} exercises it); kept consistent
     *  with its siblings so a future caller doesn't silently inherit the exact-match bug this fixed
     *  elsewhere. */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.userId = :userId AND t.accountId = :accountId AND t.txnDate = :date
          AND t.amount = :amount AND LOWER(TRIM(t.description)) = LOWER(TRIM(:description))
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
     *
     * <p>No call site -- kept consistent with {@link
     * #findPotentialDuplicatesByUserAndAccountIdIn}'s description normalization for the same
     * reason as {@link #findPotentialDuplicates} above.
     */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.userId = :userId AND t.txnDate = :date
          AND t.amount = :amount AND LOWER(TRIM(t.description)) = LOWER(TRIM(:description))
        """)
    List<Transaction> findPotentialDuplicatesByUser(
            @Param("userId") UUID userId, @Param("date") LocalDate date,
            @Param("amount") BigDecimal amount, @Param("description") String description);

    /** Like {@link #findPotentialDuplicatesByUser}, scoped to a set of live account ids --
     *  excludes a soft-deleted account's transactions, which the unscoped version would keep
     *  matching against forever (see {@link #findByUserIdAndAccountIdIn}'s own doc comment).
     *  Pass the caller's own live account ids rather than re-deriving them here.
     *
     *  <p>A2 (two-pass mobile audit, 2026-09-01; see
     *  docs/project-management/plans/mobile-correctness-trust-roadmap.md, Track A). Description
     *  compared space-trimmed and case-folded ({@code LOWER(TRIM(...))} on both sides), not the raw
     *  column value: two extractions of the same underlying bank line (a CSV export vs. a
     *  re-scraped PDF, or a bank that shifts capitalization between monthly exports) can differ
     *  by nothing more than case or a stray leading/trailing space, and exact equality treated
     *  those as two different transactions -- a false negative that silently double-counts real
     *  money, with no duplicate badge ever shown to the user.
     *
     *  <p>This SQL is the CONSTRAINT the two Java paths are held to, not the other way round:
     *  {@code TRIM()} here strips the space character only, while Java's {@code String.trim()}
     *  also strips tabs and newlines, so {@code DuplicateIndex.key} and {@code
     *  ReconciliationService.duplicateKey} both go through {@code
     *  com.finora.util.DuplicateMatching.normalizeDescription} to match this exactly rather than
     *  inlining {@code trim()}. {@code DuplicateIndexIT} asserts that equivalence against a real
     *  Postgres -- widening either side without the other silently changes which duplicates get
     *  surfaced, which is the failure mode this whole cluster of comments exists to prevent. */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.userId = :userId AND t.txnDate = :date
          AND t.amount = :amount AND LOWER(TRIM(t.description)) = LOWER(TRIM(:description))
          AND t.accountId IN :accountIds
        """)
    List<Transaction> findPotentialDuplicatesByUserAndAccountIdIn(
            @Param("userId") UUID userId, @Param("date") LocalDate date,
            @Param("amount") BigDecimal amount, @Param("description") String description,
            @Param("accountIds") java.util.Collection<UUID> accountIds);

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

    /** Like {@link #findCandidatesForGmailReconciliation}, scoped to a set of live account ids --
     *  excludes a soft-deleted account's transactions, which the unscoped version would keep
     *  matching against forever (see {@link #findByUserIdAndAccountIdIn}'s own doc comment).
     *  Pass the caller's own live account ids rather than re-deriving them here. */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.userId = :userId AND t.amount = :amount
          AND t.txnDate BETWEEN :startDate AND :endDate
          AND t.txnType = com.finora.entity.Transaction.Type.EXPENSE
          AND t.source <> com.finora.entity.Transaction.Source.GMAIL_IMPORT
          AND t.accountId IN :accountIds
        ORDER BY t.txnDate
        """)
    List<Transaction> findCandidatesForGmailReconciliationAndAccountIdIn(
            @Param("userId") UUID userId, @Param("amount") BigDecimal amount,
            @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate,
            @Param("accountIds") java.util.Collection<UUID> accountIds);

    /** Backs "View Imported Transactions" and "Delete Statement Import" — every transaction a
     *  given confirmed CSV import produced. See StatementImportService. */
    List<Transaction> findByStatementImportId(UUID statementImportId);

    /** Every transaction currently resolved to a given merchant -- used by MerchantService.merge()
     *  to repoint the absorbed merchant's transactions onto the surviving merchant (spec §5.4
     *  step 2), and available for a future merchant-detail "recent transactions" view. */
    List<Transaction> findByUserIdAndMerchantId(UUID userId, UUID merchantId);

    /** Like {@link #findByUserIdAndMerchantId}, scoped to a set of live account ids -- excludes a
     *  soft-deleted account's rows, which {@code findByUserIdAndMerchantId} alone would keep
     *  returning forever (see {@link #findByUserIdAndAccountIdIn}'s own doc comment). */
    List<Transaction> findByUserIdAndMerchantIdAndAccountIdIn(
            UUID userId, UUID merchantId, java.util.Collection<UUID> accountIds);

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

    /** Like {@link #findDistinctTransactionDates}, scoped to a set of live account ids -- excludes
     *  a soft-deleted account's dates, which {@code findDistinctTransactionDates} alone would keep
     *  surfacing in the Reports month dropdown forever (see {@link #findByUserIdAndAccountIdIn}'s
     *  own doc comment). */
    @Query("""
           SELECT DISTINCT t.txnDate FROM Transaction t
            WHERE t.userId = :userId AND t.accountId IN :accountIds
           """)
    List<LocalDate> findDistinctTransactionDates(@Param("userId") UUID userId,
                                                   @Param("accountIds") java.util.Collection<UUID> accountIds);

    /**
     * Whether this account already has a live transaction dated after {@code afterDate} that
     * isn't part of the statement now being confirmed.
     *
     * <p>A1 (two-pass mobile audit, 2026-09-01; see
     * docs/project-management/plans/mobile-correctness-trust-roadmap.md, Track A). {@code
     * ImportService.isMostRecentStatementForAccount} only ever compared a statement against OTHER
     * STATEMENTS. With no sibling statement newer than this one, an older-but-late-arriving
     * import's corroborated closing balance was applied outright, silently discarding whatever a
     * live transaction dated after it had already contributed to the balance, while that
     * transaction stayed fully visible (and counted) in the Ledger -- two disagreeing numbers with
     * no warning.
     *
     * <p><b>Which rows the two branches actually cover, precisely -- do not narrow this without
     * re-reading it.</b> {@code statementImportId IS NULL} covers MANUAL rows only ({@code
     * TransactionService.create} is the sole path that leaves it null; {@code
     * ImportService.persistSection} is the only {@code setStatementImportId} call site in the whole
     * of {@code main/}). A Gmail-synced receipt is NOT null here -- {@code
     * GmailReviewService.approve} routes through {@code ImportService.confirmSession}, so it gets a
     * {@code StatementImport} row of its own like any other import. Gmail rows are therefore caught
     * by the {@code <> :excludingStatementId} branch, not by the null branch. Both branches are
     * load-bearing for a different source, and the {@code IS NULL} half is still required on its own
     * terms because SQL {@code NULL <> x} is unknown rather than true, so a manual row would
     * otherwise be silently excluded.
     *
     * <p>Excludes the statement being confirmed by id, not by date: {@code ImportService.confirm}
     * has already saved this statement's own rows (with this statement's id) by the time this runs.
     * With today's caller that exclusion is belt-and-braces rather than load-bearing -- {@code
     * afterDate} is the statement's own {@code maxDate}, so none of its rows can satisfy {@code
     * txnDate > :afterDate} anyway -- but it becomes essential the moment the boundary is moved to
     * the printed period end, which is the obvious next edit here.
     */
    @Query("""
           SELECT CASE WHEN EXISTS (
                  SELECT 1 FROM Transaction t
                   WHERE t.userId = :userId
                     AND t.accountId = :accountId
                     AND t.txnDate > :afterDate
                     AND (t.statementImportId IS NULL OR t.statementImportId <> :excludingStatementId)
                  ) THEN true ELSE false END
           """)
    boolean existsLiveTransactionAfterDate(@Param("userId") UUID userId,
                                            @Param("accountId") UUID accountId,
                                            @Param("afterDate") LocalDate afterDate,
                                            @Param("excludingStatementId") UUID excludingStatementId);

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

    /** Like {@link #findByUserIdAndReconciliationStatusIn}, scoped to a set of live account ids --
     *  excludes a soft-deleted account's rows, which {@code findByUserIdAndReconciliationStatusIn}
     *  alone would keep returning forever (see {@link #findByUserIdAndAccountIdIn}'s own doc
     *  comment). */
    List<Transaction> findByUserIdAndReconciliationStatusInAndAccountIdIn(
            UUID userId, java.util.Collection<Transaction.ReconciliationStatus> statuses,
            java.util.Collection<UUID> accountIds);

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
