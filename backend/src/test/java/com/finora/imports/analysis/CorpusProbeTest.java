package com.finora.imports.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the two things in the probe that can silently produce a wrong record: how per-section
 * verification outcomes are collapsed, and how a failure is represented.
 */
class CorpusProbeTest {

    /**
     * The regression this exists for. "Last section wins" was the first implementation, and on
     * Shivani_HDFC -- three sections, a COLUMN_AMBIGUITY WARNING on one of them -- a later section's
     * VERIFIED overwrote the warning and the document recorded as PARSED_COMPLETE. A composite
     * statement is exactly where a per-section problem is easiest to lose, and a corpus diff built on
     * records like that would report no change while one section had stopped parsing.
     */
    @ParameterizedTest(name = "worse({0}, {1}) = {2}")
    @CsvSource({
            "VERIFIED,       WARNING,        WARNING",
            "WARNING,        VERIFIED,       WARNING",     // the ordering the bug got wrong
            "VERIFIED,       FAILED,         FAILED",
            "FAILED,         VERIFIED,       FAILED",
            "WARNING,        FAILED,         FAILED",
            "FAILED,         WARNING,        FAILED",
            "NOT_APPLICABLE, VERIFIED,       VERIFIED",
            "VERIFIED,       NOT_APPLICABLE, VERIFIED",
            "VERIFIED,       VERIFIED,       VERIFIED",
    })
    void theWorstOutcomeSurvivesRegardlessOfSectionOrder(String a, String b, String expected) {
        assertThat(CorpusProbe.worse(a, b)).isEqualTo(expected);
    }

    @Test
    @DisplayName("an unrecognised outcome ranks lowest rather than masking a real failure")
    void unknownOutcomesDoNotOutrankKnownOnes() {
        assertThat(CorpusProbe.severity("SOMETHING_NEW")).isZero();
        assertThat(CorpusProbe.worse("FAILED", "SOMETHING_NEW")).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("a failure becomes a parseable record, so one bad statement cannot end the sweep")
    void errorRecordIsValidJsonAndNamesTheFile() {
        String json = CorpusProbe.errorRecord(Path.of("/tmp/Odd 'name'.pdf"),
                new IllegalStateException("boom \"quoted\""));

        assertThat(json).startsWith("{").endsWith("}")
                .contains("\"status\":\"error\"")
                .contains("\"file\":\"Odd 'name'.pdf\"")
                .contains("\"type\":\"IllegalStateException\"")
                // The message is escaped rather than breaking the record it is embedded in.
                .contains("\\\"quoted\\\"");
    }

    // ------------------------------------------------- section detail must not be flattened

    private static CorpusProbe.Section sec(int index, int rows, String product, String type) {
        return new CorpusProbe.Section(index, rows, product, type, null, 0.5, false,
                Map.of("BALANCE_CHAIN", "VERIFIED"));
    }

    /**
     * THE regression this whole step exists for. Rows moving between sections while the document total
     * holds constant is an RD's transactions landing in the Savings account -- wrong money attributed
     * to the wrong product. Every aggregate is identical: 90 rows, 3 sections, 2 row-bearing sections.
     * Only the ordered per-section detail distinguishes them.
     */
    @Test
    @DisplayName("[75,15,0] and [15,75,0] produce different records despite an identical total of 90")
    void rowsMovingBetweenSectionsRemainsVisible() {
        String before = CorpusProbe.sectionsJson(List.of(
                sec(0, 75, "UNKNOWN", "SAVINGS"), sec(1, 15, "UNKNOWN", "SAVINGS"), sec(2, 0, "UNKNOWN", "SAVINGS")));
        String after = CorpusProbe.sectionsJson(List.of(
                sec(0, 15, "UNKNOWN", "SAVINGS"), sec(1, 75, "UNKNOWN", "SAVINGS"), sec(2, 0, "UNKNOWN", "SAVINGS")));

        assertThat(before).isNotEqualTo(after);
        assertThat(before).contains("\"index\":0,\"rows\":75").contains("\"index\":1,\"rows\":15");
        assertThat(after).contains("\"index\":0,\"rows\":15").contains("\"index\":1,\"rows\":75");
    }

    /** Shivani_HDFC's shape: the RD and FD sections must survive as records, not vanish. */
    @Test
    @DisplayName("zero-row sections are recorded, not dropped")
    void zeroRowSectionsSurvive() {
        String json = CorpusProbe.sectionsJson(List.of(
                sec(0, 75, "UNKNOWN", "SAVINGS"), sec(1, 0, "UNKNOWN", "SAVINGS"), sec(2, 0, "UNKNOWN", "SAVINGS")));

        assertThat(json).contains("\"index\":1,\"rows\":0").contains("\"index\":2,\"rows\":0");
        // Three entries, so Step 3 can see a section disappear even when no rows change.
        assertThat(json.split("\"index\":").length - 1).isEqualTo(3);
    }

    /** A product type changing per section is the signal that RD/FD classification got fixed. */
    @Test
    @DisplayName("per-section product type is preserved, so RD/FD reclassification is visible")
    void perSectionProductTypeIsPreserved() {
        String broken = CorpusProbe.sectionsJson(List.of(
                sec(0, 75, "UNKNOWN", "SAVINGS"), sec(1, 0, "UNKNOWN", "SAVINGS")));
        String fixed = CorpusProbe.sectionsJson(List.of(
                sec(0, 75, "SAVINGS", "SAVINGS"), sec(1, 0, "RECURRING_DEPOSIT", "INVESTMENT")));

        assertThat(broken).isNotEqualTo(fixed);
        assertThat(fixed).contains("RECURRING_DEPOSIT");
    }

    /** Per-section verification must not collapse into one document verdict. */
    @Test
    @DisplayName("a warning on one section stays attached to that section")
    void perSectionVerificationStaysPerSection() {
        String json = CorpusProbe.sectionsJson(List.of(
                new CorpusProbe.Section(0, 75, "UNKNOWN", "SAVINGS", null, 0.5, false,
                        Map.of("COLUMN_AMBIGUITY", "VERIFIED")),
                new CorpusProbe.Section(1, 0, "UNKNOWN", "SAVINGS", null, 0.5, true,
                        Map.of("COLUMN_AMBIGUITY", "WARNING"))));

        assertThat(json).contains("\"WARNING\"").contains("\"VERIFIED\"");
        assertThat(json.indexOf("VERIFIED")).isLessThan(json.indexOf("WARNING"));
    }

    @Test
    @DisplayName("a section with no detected account renders nulls rather than fabricated values")
    void missingAccountIdentityRendersNull() {
        String json = CorpusProbe.sectionsJson(List.of(
                new CorpusProbe.Section(0, 0, null, null, null, 0.0, false, Map.of())));

        assertThat(json).contains("\"detectedProduct\":null")
                .contains("\"accountNumberMasked\":null")
                .doesNotContain("\"UNKNOWN\"");
    }

    @Test
    void anEmptySectionListIsAnEmptyArray() {
        assertThat(CorpusProbe.sectionsJson(List.of())).isEqualTo("[]");
    }

    @Test
    void aNullMessageDoesNotProduceInvalidJson() {
        assertThat(CorpusProbe.errorRecord(Path.of("x.pdf"), new RuntimeException()))
                .contains("\"message\":\"null\"");
    }
}
