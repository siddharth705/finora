package com.finora.repository;

import com.finora.entity.StatementImport;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StatementImportRepository extends JpaRepository<StatementImport, UUID> {

    /**
     * Every column any caller of this repository actually needs for a list/summary view,
     * deliberately excluding {@code fileContent}.
     *
     * <p>Bug fix (Phase C review, widened in a later pass): {@code @Basic(fetch = FetchType.LAZY)}
     * on {@code fileContent} is a no-op without Hibernate bytecode enhancement, which this build
     * does not configure -- confirmed by this same class's own {@code findLatestPeriodEndForAccount}
     * comment above, documenting the identical eager-load behavior for this exact field. The plain
     * entity-returning finder this projection replaces -- {@code findByUserIdOrderByImportedAtDesc}
     * -- therefore pulled every legacy (database-stored) statement's full raw bytes into heap on
     * every list/summary read that reached it: {@code DataExportService.buildBundle} (fixed
     * first), then, once the same bug was found still live in five more callers in the same review
     * pass, {@code AccountService.listForUser} (the hottest of the six -- every account-list page
     * view), {@code StatementImportService.listGroupedByAccount}, {@code
     * AnalyticsService.importStatistics}, and {@code AccountPurgeSweepService.purgeOne}.
     * {@code CapabilityCoverageService.forUser} needed different columns entirely (see {@link
     * CapabilityData}), and {@code WorkspaceDashboardService.summarize} needed no columns at all
     * (see {@link #countByUserId}), so those two moved to their own query instead of this one.
     *
     * <p>The entity-returning finder was deleted once its last caller moved off it -- a
     * fileContent-eager finder with zero remaining callers is exactly the kind of ready-made
     * footgun this class's own removed {@code findByIdIncludingDeleted} note already warns about
     * (see the bottom of this file). Restore it from git history if a genuine need for the full
     * entity ever appears.
     *
     * <p>This projection selects only the columns {@code StatementImportDto.Summary}, export/
     * statement-history ZIP-entry naming, and per-account statement/import-date/transaction-count
     * rollups actually use, so the generated SQL never touches {@code file_content} at all -- the
     * one reliable way to keep it out of memory here, since the annotation alone does not.
     */
    interface StatementMetadata {
        UUID getId();
        UUID getAccountId();
        String getFileName();
        LocalDate getStatementPeriodStart();
        LocalDate getStatementPeriodEnd();
        BigDecimal getOpeningBalance();
        BigDecimal getClosingBalance();
        // Credit-card statement entity, roadmap item 6 -- see StatementImport's own doc comment on
        // these two fields. Null for a non-credit-card statement, same as on the entity.
        BigDecimal getTotalAmountDue();
        LocalDate getPaymentDueDate();
        int getTransactionsImported();
        int getTransactionsSkipped();
        Instant getImportedAt();
    }

    @Query("""
           SELECT s.id AS id, s.accountId AS accountId, s.fileName AS fileName,
                  s.statementPeriodStart AS statementPeriodStart, s.statementPeriodEnd AS statementPeriodEnd,
                  s.openingBalance AS openingBalance, s.closingBalance AS closingBalance,
                  s.totalAmountDue AS totalAmountDue, s.paymentDueDate AS paymentDueDate,
                  s.transactionsImported AS transactionsImported, s.transactionsSkipped AS transactionsSkipped,
                  s.importedAt AS importedAt
             FROM StatementImport s
            WHERE s.userId = :userId
            ORDER BY s.importedAt DESC
           """)
    List<StatementMetadata> findMetadataByUserIdOrderByImportedAtDesc(@Param("userId") UUID userId);

    /** Like {@link #findMetadataByUserIdOrderByImportedAtDesc}, scoped to a set of live account
     *  ids -- excludes a soft-deleted account's statements, which the unscoped finder would keep
     *  returning forever (see {@link #countByUserIdAndAccountIdIn}'s own doc comment). */
    @Query("""
           SELECT s.id AS id, s.accountId AS accountId, s.fileName AS fileName,
                  s.statementPeriodStart AS statementPeriodStart, s.statementPeriodEnd AS statementPeriodEnd,
                  s.openingBalance AS openingBalance, s.closingBalance AS closingBalance,
                  s.totalAmountDue AS totalAmountDue, s.paymentDueDate AS paymentDueDate,
                  s.transactionsImported AS transactionsImported, s.transactionsSkipped AS transactionsSkipped,
                  s.importedAt AS importedAt
             FROM StatementImport s
            WHERE s.userId = :userId AND s.accountId IN :accountIds
            ORDER BY s.importedAt DESC
           """)
    List<StatementMetadata> findMetadataByUserIdAndAccountIdInOrderByImportedAtDesc(
            @Param("userId") UUID userId, @Param("accountIds") java.util.Collection<UUID> accountIds);

    /**
     * Every statement for one account that has a printed period -- the input
     * {@code StatementCoverageAnalyzer} needs (see that class's own doc comment). Statements with
     * no printed period (today, always a CSV import -- see the coverage proposal's §3/§7) are
     * excluded rather than passed through with a null period, which the analyzer has nowhere to
     * place on a timeline; CSV coverage is explicitly out of scope for this phase.
     *
     * <p>Ordered by period start so the analyzer's own sort is redundant defense, not the only
     * ordering guarantee.
     */
    @Query("""
           SELECT s.id AS id, s.accountId AS accountId, s.fileName AS fileName,
                  s.statementPeriodStart AS statementPeriodStart, s.statementPeriodEnd AS statementPeriodEnd,
                  s.openingBalance AS openingBalance, s.closingBalance AS closingBalance,
                  s.totalAmountDue AS totalAmountDue, s.paymentDueDate AS paymentDueDate,
                  s.transactionsImported AS transactionsImported, s.transactionsSkipped AS transactionsSkipped,
                  s.importedAt AS importedAt
             FROM StatementImport s
            WHERE s.userId = :userId AND s.accountId = :accountId
              AND s.statementPeriodStart IS NOT NULL AND s.statementPeriodEnd IS NOT NULL
              AND s.supersededBy IS NULL
            ORDER BY s.statementPeriodStart
           """)
    List<StatementMetadata> findMetadataWithPeriodByUserIdAndAccountId(@Param("userId") UUID userId,
                                                                        @Param("accountId") UUID accountId);

    /**
     * The latest statement period end already on file for this account, ignoring one row.
     *
     * <p>BH-042/BH-024. {@code ImportService.isMostRecentStatementForAccount} used to answer this
     * by loading EVERY statement import the user has -- entities, including the file_content
     * column's mapping -- and filtering in memory, once per confirm, inside the confirm
     * transaction. {@code confirmMultiSection} paid it once per account section. The answer is a
     * single date.
     *
     * <p>Excludes the import being confirmed by id, because it has just been saved and would
     * otherwise compare against itself and always win.
     *
     * @return empty when this is the account's only statement, or when no other one states a period
     */
    @Query("""
           SELECT MAX(si.statementPeriodEnd) FROM StatementImport si
            WHERE si.userId = :userId
              AND si.accountId = :accountId
              AND si.id <> :excludingId
              AND si.supersededBy IS NULL
           """)
    Optional<java.time.LocalDate> findLatestPeriodEndForAccount(@Param("userId") UUID userId,
                                                                 @Param("accountId") UUID accountId,
                                                                 @Param("excludingId") UUID excludingId);

    /**
     * How many OTHER statements exist for this account, ignoring one row (same exclusion as {@link
     * #findLatestPeriodEndForAccount}).
     *
     * <p>Bug fix: SQL {@code MAX()} silently ignores NULL rows, so once a statement can legitimately
     * have a null {@code statementPeriodEnd} (an import whose PDF never printed a period, now that
     * the transaction-range guess that used to guarantee a value has been removed -- see {@code
     * ImportService.persistSection}'s own comment), {@code findLatestPeriodEndForAccount} returning
     * empty stopped meaning only "this is the account's only statement" -- it now also means "other
     * statements exist, but none of them states a period," which is a DIFFERENT, unsafe case to treat
     * the same way. This lets {@code isMostRecentStatementForAccount} tell the two apart instead of
     * defaulting to "most recent" (and authorizing a closing-balance overwrite) whenever an undated
     * sibling is silently invisible to the aggregate.
     */
    @Query("""
           SELECT COUNT(si) FROM StatementImport si
            WHERE si.userId = :userId
              AND si.accountId = :accountId
              AND si.id <> :excludingId
           """)
    long countOtherStatementsForAccount(@Param("userId") UUID userId,
                                         @Param("accountId") UUID accountId,
                                         @Param("excludingId") UUID excludingId);

    /**
     * The closing balance of this account's chronologically previous statement -- the one whose
     * own period ends on or before the statement now being confirmed begins -- for {@link
     * com.finora.imports.OpeningBalanceCarryForward}. The mirror image of {@link
     * #findLatestPeriodEndForAccount}: that one looks forward (is anything newer already on
     * file, for the closing side); this one looks backward (is anything older already on file,
     * for the opening side).
     *
     * <p>No {@code excludingId} parameter, unlike the two queries above -- this runs BEFORE the
     * statement being confirmed is saved (see {@code ImportService.persistSection}, which sets
     * the opening balance before it calls {@code statementImportRepository.save}), so there is no
     * row yet to accidentally match against itself.
     *
     * <p>Ordered by period end, then by import time as the tiebreak for same-day statements
     * (a composite multi-account statement, or a genuine re-import), so the result is
     * deterministic rather than whatever order Postgres happens to return.
     *
     * @return empty when this account has no earlier statement with both a stated period end
     *         at or before {@code newStatementStart} and a stated closing balance
     */
    @Query("""
           SELECT si.closingBalance FROM StatementImport si
            WHERE si.userId = :userId
              AND si.accountId = :accountId
              AND si.statementPeriodEnd IS NOT NULL
              AND si.statementPeriodEnd <= :newStatementStart
              AND si.closingBalance IS NOT NULL
              AND si.supersededBy IS NULL
            ORDER BY si.statementPeriodEnd DESC, si.importedAt DESC
           """)
    List<BigDecimal> findPriorStatementClosingBalanceForAccount(@Param("userId") UUID userId,
                                                                  @Param("accountId") UUID accountId,
                                                                  @Param("newStatementStart") LocalDate newStatementStart,
                                                                  Pageable pageable);

    /** {@code WorkspaceDashboardService.summarize}'s "N statements imported" tile only ever called
     *  {@code .size()} on the entity-returning finder's full result -- a database COUNT is
     *  strictly better than fetching (and projecting) any columns at all for that, {@code
     *  fileContent} included. See {@link StatementMetadata}'s own doc comment for the rest of that
     *  finder's removal. */
    long countByUserId(UUID userId);

    /** Like {@link #countByUserId}, but scoped to a specific set of accounts -- for a caller that
     *  must exclude soft-deleted accounts' statements. {@code StatementImport.deleted_at} is set
     *  when a STATEMENT is removed, not when its owning ACCOUNT is (deleting an account never
     *  touches this column, by design -- see {@code StatementImportService.listGroupedByAccount}'s
     *  own 7-day retention window, which relies on the statement staying visible after its account
     *  is gone). {@code countByUserId} alone therefore keeps counting a deleted account's
     *  statements forever, not just during that window. Pass the caller's own live account ids
     *  (e.g. {@code accountRepository.findByUserId(userId)}) rather than re-deriving them here. */
    long countByUserIdAndAccountIdIn(UUID userId, java.util.Collection<UUID> accountIds);

    /** Every statement import that carries credit-card summary fields -- {@code totalAmountDue} is
     *  null for a non-credit-card statement and populated whenever {@code
     *  CreditCardSummaryExtractor} found a payment-summary panel (see {@code StatementImport
     *  .totalAmountDue}'s own doc comment), so this is the credit-card-statement filter for free,
     *  with no new column. Backs {@code ReconciliationService}'s CC_PAYMENT pass (roadmap Phase 3). */
    List<StatementImport> findByUserIdAndTotalAmountDueIsNotNull(UUID userId);

    /**
     * The {@code id}/{@code activatedCapabilitiesJson}/{@code unparseableSummaryJson} columns
     * {@code CapabilityCoverageService.forUser} actually reads, deliberately excluding {@code
     * fileContent} -- same bug, same fix, as {@link StatementMetadata}, but a disjoint set of
     * columns: coverage aggregation has no use for a statement's account, balances or file name,
     * and {@link StatementMetadata} has no use for either JSON column here. {@code id} is kept
     * (unlike {@link StatementMetadata}, which never needed it) purely so a malformed row can still
     * be logged by which import produced it -- see {@code CapabilityCoverageService
     * .capabilitiesOf}/{@code unparseableOf}.
     */
    interface CapabilityData {
        UUID getId();
        String getActivatedCapabilitiesJson();
        String getUnparseableSummaryJson();
    }

    @Query("""
           SELECT s.id AS id, s.activatedCapabilitiesJson AS activatedCapabilitiesJson,
                  s.unparseableSummaryJson AS unparseableSummaryJson
             FROM StatementImport s
            WHERE s.userId = :userId
           """)
    List<CapabilityData> findCapabilityDataByUserId(@Param("userId") UUID userId);

    /**
     * The import an asynchronous job produced, if it produced one.
     *
     * <p>{@code Optional} rather than a list because V67's partial unique index makes it one: a
     * replayed job cannot import twice. Used by the unified import trace to answer "did this job
     * actually land transactions", which the job row itself cannot say -- it reaches COMPLETED when
     * staging finishes, and confirming is still the user's decision.
     */
    Optional<StatementImport> findByImportJobId(UUID importJobId);

    // Admin Portal, Operational Dashboard + Statement Import health provider -- a statement_imports
    // row can only ever represent a completed import (CsvImportService/StatementImportService both
    // throw synchronously on a parse failure rather than persisting a row for it; V81 removed the
    // status column this table briefly carried for exactly that reason -- it could never hold
    // anything else), so "failed imports" has no real signal to report from this table.
    // transactionsSkipped is the honest substitute: real evidence an import didn't cleanly account
    // for every row, without claiming a "failure" this pipeline can't actually detect. See
    // StatementImportHealthProvider's class comment for how this feeds the health panel.
    long countByImportedAtAfter(Instant threshold);

    // Admin Portal, Operational Dashboard "vs yesterday" delta -- yesterday's count for the same
    // "Imports today" tile.
    long countByImportedAtBetween(Instant start, Instant end);

    /** D-27 PR3-D: the "first import" activation-funnel stage -- how many distinct users have
     *  EVER completed a statement import. Native, bypassing {@code @SQLRestriction} the same way
     *  as {@code BudgetRepository.countDistinctUsersEverActivated} -- see that method's own doc
     *  comment for why a growth milestone must survive the import later being deleted. */
    @Query(value = "SELECT COUNT(DISTINCT user_id) FROM statement_imports", nativeQuery = true)
    long countDistinctUsersEverActivated();

    /** {@code FinancialJourneyService}'s FIRST_IMPORT milestone: this ONE user's earliest
     *  statement import ever, regardless of whether it (or every other import they've made) has
     *  since been deleted. Same bypass, same "a milestone is a permanent behavioral fact once
     *  reached" reasoning as {@link #countDistinctUsersEverActivated} just above -- a user who
     *  deletes their only statement (to fix a bad import and re-upload, say) plainly did still
     *  import a statement at some point, and the dashboard's own onboarding checklist must not
     *  un-tick that. Epoch millis, not {@code Instant}: {@code findObjectsUnreferencedSince}
     *  below explains why a native query here has no other reliable way to hand back a JDBC
     *  timestamp column without naming FG-019's banned {@code java.sql.Timestamp}. Null when this
     *  user has never imported a statement (a bare SQL {@code MIN} over zero rows). */
    @Query(value = "SELECT (EXTRACT(EPOCH FROM MIN(imported_at)) * 1000)::bigint FROM statement_imports WHERE user_id = :userId",
           nativeQuery = true)
    Long findEarliestImportedAtEverEpochMillis(@Param("userId") UUID userId);

    @Query("SELECT COUNT(s) FROM StatementImport s WHERE s.importedAt >= :threshold AND s.transactionsSkipped > 0")
    long countWithSkippedRowsAfter(@Param("threshold") Instant threshold);

    // Admin Portal, Operational Dashboard "vs yesterday" delta for the same tile. Inclusive on
    // both ends, matching Spring Data's own Between semantics -- same as the derived
    // countByImportedAtBetween sibling just above, which can't be told to do otherwise.
    @Query("SELECT COUNT(s) FROM StatementImport s WHERE s.importedAt >= :start AND s.importedAt <= :end AND s.transactionsSkipped > 0")
    long countWithSkippedRowsBetween(@Param("start") Instant start, @Param("end") Instant end);

    // Admin Portal, Operational Dashboard -- "Recent Imports" tile (see Phase 7's scope-reduction
    // note: this codebase has no background job queue, so a real per-import status list is the
    // closest honest equivalent to a job monitor).
    List<StatementImport> findAllByOrderByImportedAtDesc(Pageable pageable);

    /**
     * Every import that carries a layout fingerprint, for LayoutIntelligenceService.
     *
     * Deliberately platform-wide and deliberately not user-scoped: the question "how many DISTINCT
     * document layouts does Finora see, and which recur" is not answerable one user at a time. What
     * makes that acceptable is what the caller does with the rows -- every record it returns is
     * keyed by fingerprint and carries counts and header names only, never a user, account,
     * transaction or bank. See that service's class doc.
     *
     * Rows predating V39 have a null fingerprint and are excluded rather than grouped under one
     * phantom "unknown layout" bucket that would dominate every count.
     */
    @Query("SELECT s FROM StatementImport s WHERE s.layoutFingerprint IS NOT NULL")
    List<StatementImport> findAllWithLayoutFingerprint();

    /**
     * Whether any LIVE (non-soft-deleted) row still references this object key.
     *
     * <p>BH-017. Derived, so {@code @SQLRestriction("deleted_at IS NULL")} applies exactly as it
     * does to every other lookup on this entity -- a soft-deleted row is, by the app's own model,
     * no longer a current reference, so it correctly does not keep an object alive here. See
     * {@code StatementStorageSweepService}, which OR's this against
     * {@code ImportSessionRepository.existsByObjectKey} to decide whether an object is reclaimable.
     *
     * <p><b>BH-039: deliberately global, never add a {@code userId} parameter.</b> Content
     * addressing has no tenant prefix ({@code ContentAddress}'s class doc) -- two different users
     * who upload byte-identical documents share one object, by design. Scoping this by user would
     * make the sweep delete another tenant's only copy of a shared document the moment THIS
     * tenant's reference disappears. {@code StatementStorageSweepServiceIT
     * .sweep_doesNotReclaimAnObjectStillReferencedByAnotherTenantsLiveRow} is the regression test
     * for exactly this.
     */
    boolean existsByObjectKey(String objectKey);

    /**
     * BH-017 sweep candidates: every {@code (content_hash, object_key)} pair whose most recent
     * removal from this table -- a user deleting that statement, i.e. the soft-delete's
     * {@code deleted_at} -- was more than {@code cutoff} ago.
     *
     * <p>Native, deliberately: {@code @SQLRestriction} hides {@code deleted_at IS NOT NULL} rows
     * from every HQL/derived query on this entity, and reading {@code deleted_at} is the entire
     * point here. This is also why the ordinary {@code @SQLDelete} soft-delete is what makes this
     * table's history queryable at all -- {@code import_sessions} has no equivalent, so content
     * whose only-ever reference was an {@code import_sessions} row that has since been hard-deleted
     * by {@code ImportSessionService}'s 48h TTL sweep leaves no trace here or anywhere else in the
     * database. That gap is real and is not closed by this query; see
     * {@code StatementStorageSweepService}'s class doc.
     *
     * <p>{@code MAX(deleted_at)}, not {@code MIN} or "any": several sections of one composite
     * statement, or several re-imports, can legitimately share one {@code object_key}, each
     * soft-deleted independently. The object is only unreferenced BY THIS TABLE once the LAST of
     * them was removed, so the retention window has to be measured from that point, not the first
     * -- using an earlier one would reclaim an object while a more-recently-deleted row (still
     * within its own re-import grace period) pointed at it.
     *
     * <p>This is only the discovery half of the sweep. It can be stale by the time the caller acts
     * on it -- {@code StatementStorageSweepService} re-checks the reference count fresh, via
     * {@link #existsByObjectKey} and {@code ImportSessionRepository.existsByObjectKey}, immediately
     * before calling {@code StatementStorage.delete} on each candidate.
     *
     * <p>The third column is {@code MAX(deleted_at)} as epoch milliseconds, not a timestamp --
     * FG-019 (see {@code ProductionCodeHygieneTest}) bans {@code java.sql.Timestamp} from
     * production code, and a native query's {@code Object[]} projection has no other way to hand
     * a JDBC timestamp column back without naming that type. A {@code bigint} of milliseconds
     * comes back as a plain {@code Long}, which {@link Instant#ofEpochMilli} converts with no
     * legacy date type anywhere in this class or its caller.
     */
    @Query(value = """
            SELECT content_hash, object_key, (EXTRACT(EPOCH FROM MAX(deleted_at)) * 1000)::bigint
              FROM statement_imports
             WHERE object_key IS NOT NULL AND deleted_at IS NOT NULL AND deleted_at < :cutoff
             GROUP BY content_hash, object_key
             ORDER BY MAX(deleted_at) ASC
             LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findObjectsUnreferencedSince(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    // Removed: findByIdIncludingDeleted(UUID). It bypassed the entity's
    // @SQLRestriction("deleted_at IS NULL") AND took no user id, so it read any user's statement
    // by primary key alone -- with zero callers anywhere in the codebase. An unscoped cross-user
    // read with no caller is not dormant, it is a ready-made one waiting for whoever needs "just
    // load it by id" next; and being unused, nothing would have failed when they used it wrongly.
    // ScopedIdentityLookupTest enforces user-scoping on the lookups that exist, which is exactly
    // why an unused unscoped one is worth deleting rather than leaving for that test to grow a
    // case for. Restore it from git history if a genuine caller ever appears -- with a user id
    // parameter.
}
