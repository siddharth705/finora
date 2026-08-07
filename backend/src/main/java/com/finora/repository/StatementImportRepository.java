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
