package com.finora.imports;

import com.finora.dto.ImportDto.UnparseableRow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * What failed to parse in one import, counted by SHAPE and REASON -- never by value.
 *
 * The Capability Backlog in the engineering principles doc is currently a hand-maintained table of
 * anecdotes: each entry says "1 statement" or "6 of 7 real statements in the Aug 2026 validation
 * pass", because the only way to count was for someone to debug documents by hand. This is the
 * same information gathered automatically, so "which missing capability costs the most rows" is a
 * query rather than a recollection.
 *
 * <h2>Why a histogram and not the rows</h2>
 *
 * An unparseable row is a line of somebody's bank statement. Persisting the raw values in order to
 * count them would put customer statement content into a table whose whole purpose is engineering
 * metrics -- read by admins, retained indefinitely, and exactly the sort of place data ends up when
 * nobody decided to put it there. Everything kept here is statement furniture: an engine-authored
 * failure reason, and the header names of the columns involved.
 */
public record UnparseableRowSummary(Map<String, Integer> reasons,
                                    Map<String, Integer> columnSignatures,
                                    int total) {

    public static UnparseableRowSummary of(List<UnparseableRow> rows) {
        Map<String, Integer> reasons = new LinkedHashMap<>();
        Map<String, Integer> signatures = new LinkedHashMap<>();
        if (rows != null) {
            for (UnparseableRow row : rows) {
                reasons.merge(row.reason() == null ? "unspecified" : row.reason(), 1, Integer::sum);
                signatures.merge(signatureOf(row), 1, Integer::sum);
            }
        }
        return new UnparseableRowSummary(reasons, signatures, rows == null ? 0 : rows.size());
    }

    /** {@code @JsonIgnore} because Jackson otherwise serialises this accessor as a phantom "empty"
     *  field, which then fails to deserialise against the record's real components -- silently
     *  turning every stored summary into an unreadable row the coverage report skips. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isEmpty() { return total == 0; }

    /**
     * The row's column NAMES, sorted and joined -- "date|narration|amount".
     *
     * Sorted so the same layout produces the same signature regardless of the order a particular
     * parse happened to bucket its columns in; otherwise one layout would fragment into several
     * apparent shapes and every count would understate itself. Only keys are used -- the values are
     * the customer's.
     */
    private static String signatureOf(UnparseableRow row) {
        if (row.raw() == null || row.raw().isEmpty()) return "(no columns)";
        return String.join("|", new TreeSet<>(row.raw().keySet()));
    }
}
