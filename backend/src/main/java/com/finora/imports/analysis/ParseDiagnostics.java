package com.finora.imports.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two numbers that decide whether a parser capability is worth building.
 *
 * <p>Both already existed as facts of a parse run and were both thrown away when the request ended.
 * That cost something concrete: a capability was designed, implemented, debugged through two
 * failures and then deleted, because measurement showed it never fired once across eleven real
 * statements. The measurement came from a temporary probe printing to a console on one laptop.
 * Nothing about it was repeatable, and nobody but the person running it could see it.
 *
 * <p>Persisting these turns that console output into evidence anyone can query, which is the whole
 * difference between "HSBC is broken" and "the engine cannot anchor rows whose date value falls
 * outside the header column, 97 times in this document".
 *
 * <h2>Why the histogram and not a set</h2>
 * {@code DocumentContext.record} has set semantics, so one occurrence of a reason in a 2500-line
 * document lights the same marker as two thousand. That makes "is this a geometry problem or a
 * format problem" unanswerable, because both reasons appear in every real statement measured —
 * including ones that parse perfectly. Only the proportion says where the fault is.
 *
 * @param rowCount          transactions actually extracted, across every section. Null when the
 *                          document failed before extraction, which is different from zero.
 * @param unanchoredReasons rows that failed to become transaction anchors, by reason. Never the
 *                          rows themselves — this table holds structure and outcome, never
 *                          statement content.
 */
public record ParseDiagnostics(Integer rowCount, Map<String, Integer> unanchoredReasons) {

    /** Nothing measured. Distinct from "measured, and the answer was zero". */
    public static final ParseDiagnostics NONE = new ParseDiagnostics(null, Map.of());

    public ParseDiagnostics {
        unanchoredReasons = byCountDescending(unanchoredReasons);
    }

    public static ParseDiagnostics of(int rowCount, Map<String, Integer> unanchoredReasons) {
        return new ParseDiagnostics(rowCount, unanchoredReasons);
    }

    /** Total rows that could not be anchored, however they failed. */
    public int unanchoredRowCount() {
        int total = 0;
        for (int count : unanchoredReasons.values()) total += count;
        return total;
    }

    /**
     * Ordered by count descending, then reason ascending.
     *
     * <p>Deliberately NOT {@code Map.copyOf}, and this is load-bearing rather than stylistic: that
     * returns an immutable map with unspecified iteration order, so it would scramble the histogram
     * on the way to the database and the same parse run could serialise two different ways. The
     * upstream {@code DocumentContext.unanchoredReasons()} already hands back a {@code Map.copyOf},
     * so insertion order is gone before this record ever sees it — sorting here is what makes the
     * stored JSON deterministic at all, and it puts the dominant reason first, which is the one
     * that decides whether a capability is worth building.
     */
    private static Map<String, Integer> byCountDescending(Map<String, Integer> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(raw.entrySet());
        entries.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));
        Map<String, Integer> ordered = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : entries) ordered.put(entry.getKey(), entry.getValue());
        return Collections.unmodifiableMap(ordered);
    }
}
