package com.finora.repository;

import com.finora.entity.StatementImport;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StatementImportRepository extends JpaRepository<StatementImport, UUID> {

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
           """)
    Optional<java.time.LocalDate> findLatestPeriodEndForAccount(@Param("userId") UUID userId,
                                                                 @Param("accountId") UUID accountId,
                                                                 @Param("excludingId") UUID excludingId);

    List<StatementImport> findByUserIdOrderByImportedAtDesc(UUID userId);

    /**
     * The import an asynchronous job produced, if it produced one.
     *
     * <p>{@code Optional} rather than a list because V67's partial unique index makes it one: a
     * replayed job cannot import twice. Used by the unified import trace to answer "did this job
     * actually land transactions", which the job row itself cannot say -- it reaches COMPLETED when
     * staging finishes, and confirming is still the user's decision.
     */
    Optional<StatementImport> findByImportJobId(UUID importJobId);

    // Admin Portal, Operational Dashboard + Statement Import health provider -- imports.status
    // never actually leaves "COMPLETED" anywhere in this codebase today (CsvImportService/
    // StatementImportService both throw synchronously on a parse failure rather than persisting
    // a FAILED row), so "failed imports" has no real signal to report yet. transactionsSkipped
    // is the honest substitute: real evidence an import didn't cleanly account for every row,
    // without claiming a "failure" this pipeline can't actually detect. See
    // StatementImportHealthProvider's class comment for how this feeds the health panel.
    long countByImportedAtAfter(Instant threshold);

    @Query("SELECT COUNT(s) FROM StatementImport s WHERE s.importedAt >= :threshold AND s.transactionsSkipped > 0")
    long countWithSkippedRowsAfter(@Param("threshold") Instant threshold);

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
