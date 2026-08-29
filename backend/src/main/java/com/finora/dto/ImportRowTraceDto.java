package com.finora.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One import, row by row -- Founder Operations Dashboard, Import Explorer (docs/proposals/
 * reconciliation-evolution-roadmap-proposal.md Part 9), scoped to successfully-imported rows
 * only: a row's content is already fully persisted once it becomes a {@code Transaction}, so
 * tracing it back to its original position needs no new privacy exception. A dropped or
 * excluded-by-user row stays aggregate-only, same as {@code ImportTrace}'s existing verification
 * findings -- see {@code Transaction.sourceRowPosition}'s own doc comment for why.
 */
public final class ImportRowTraceDto {

    private ImportRowTraceDto() {
        // Namespace for the nested records, per CODING_STANDARDS' DTO convention.
    }

    /**
     * {@code rows} is empty (not missing) when this import predates {@code sourceRowPosition}, or
     * was confirmed by a client that predates echoing {@code ConfirmedRow.rowPosition} -- "no
     * position data available for this import" is a real, statable answer, not an error.
     */
    public record Trace(UUID statementImportId, List<RowOutcome> rows) {}

    /** Sorted by {@code rowPosition} ascending -- only rows this import actually has a position
     *  for; a transaction imported before this field existed simply is not listed. */
    public record RowOutcome(int rowPosition, UUID transactionId, String description,
                              BigDecimal amount, LocalDate txnDate) {}
}
