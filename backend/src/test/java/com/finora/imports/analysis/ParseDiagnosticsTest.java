package com.finora.imports.analysis;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ParseDiagnosticsTest {

    @Test
    void reasonsAreOrderedByCountDescending() {
        // The dominant reason is the one that decides whether a capability is worth building, so it
        // reads first rather than wherever the upstream map happened to put it.
        var diagnostics = ParseDiagnostics.of(569, new LinkedHashMap<>(Map.of(
                "UNANCHORED_ROWS_ABANDONED", 12,
                "NO_DATE_IN_ANCHOR_COLUMN", 649,
                "AMOUNT_CELL_UNPARSEABLE", 88)));

        assertThat(diagnostics.unanchoredReasons().keySet())
                .containsExactly("NO_DATE_IN_ANCHOR_COLUMN", "AMOUNT_CELL_UNPARSEABLE", "UNANCHORED_ROWS_ABANDONED");
    }

    @Test
    void tiedCountsAreOrderedByReasonNameSoTheOutputIsFullyDeterministic() {
        // Count alone leaves ties unordered, and an unordered tie is enough to make the same parse
        // run serialise two different strings on two runs -- which would show up later as a
        // spurious "the diagnostics changed" when nothing about the document did.
        var diagnostics = ParseDiagnostics.of(0, new LinkedHashMap<>(Map.of(
                "ZEBRA_REASON", 5, "ALPHA_REASON", 5, "MIKE_REASON", 5)));

        assertThat(diagnostics.unanchoredReasons().keySet())
                .containsExactly("ALPHA_REASON", "MIKE_REASON", "ZEBRA_REASON");
    }

    @Test
    void orderingHoldsForAMapLargeEnoughThatHashOrderWouldNotBeInsertionOrder() {
        // The specific trap this guards. Map.copyOf and Map.of return an immutable map with
        // UNSPECIFIED iteration order, and DocumentContext.unanchoredReasons() already returns a
        // Map.copyOf -- so insertion order is gone before ParseDiagnostics ever sees it. With few
        // entries a hash map often happens to iterate in a plausible order and a weaker test would
        // pass by luck; with twenty it will not.
        Map<String, Integer> scrambled = new HashMap<>();
        for (int i = 0; i < 20; i++) scrambled.put("REASON_" + i, i);

        var ordered = ParseDiagnostics.of(0, Map.copyOf(scrambled)).unanchoredReasons();

        assertThat(List.copyOf(ordered.values())).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(ordered.keySet()).first().isEqualTo("REASON_19");
    }

    @Test
    void unanchoredRowCountSumsEveryReason() {
        var diagnostics = ParseDiagnostics.of(569, Map.of("A", 649, "B", 88, "C", 12));
        assertThat(diagnostics.unanchoredRowCount()).isEqualTo(749);
    }

    @Test
    void noneMeansNotMeasuredWhichIsNotTheSameAsMeasuringZero() {
        // A document that never opened and a document that opened and yielded nothing are
        // different findings, and collapsing them would send someone looking for a parser
        // capability when the real answer was a wrong password.
        assertThat(ParseDiagnostics.NONE.rowCount()).isNull();
        assertThat(ParseDiagnostics.NONE.unanchoredReasons()).isEmpty();
        assertThat(ParseDiagnostics.of(0, Map.of()).rowCount()).isZero();
    }

    @Test
    void aNullHistogramIsToleratedRatherThanThrowing() {
        // Diagnostics must never be the reason an import fails; a defensive empty is the right
        // answer for a telemetry value.
        assertThat(new ParseDiagnostics(3, null).unanchoredReasons()).isEmpty();
    }

    @Test
    void theReturnedHistogramCannotBeMutatedByItsCaller() {
        var diagnostics = ParseDiagnostics.of(1, Map.of("A", 1));
        assertThat(diagnostics.unanchoredReasons()).isUnmodifiable();
    }
}
