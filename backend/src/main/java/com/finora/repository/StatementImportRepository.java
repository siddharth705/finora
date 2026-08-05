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
     * Loads one row bypassing the {@code @SQLRestriction("deleted_at IS NULL")} on the entity.
     *
     * <p>Native SQL because Spring Data applies the soft-delete restriction to JPQL, and a
     * soft-deleted statement still holds its bytes and its content address -- so anything
     * reasoning about stored content has to be able to see it.
     */
    @Query(value = "SELECT * FROM statement_imports WHERE id = :id", nativeQuery = true)
    Optional<StatementImport> findByIdIncludingDeleted(@Param("id") UUID id);
}
