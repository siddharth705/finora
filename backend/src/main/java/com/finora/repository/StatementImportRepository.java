package com.finora.repository;

import com.finora.entity.StatementImport;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
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
}
