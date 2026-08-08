package com.finora.imports.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;

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

    @Test
    void aNullMessageDoesNotProduceInvalidJson() {
        assertThat(CorpusProbe.errorRecord(Path.of("x.pdf"), new RuntimeException()))
                .contains("\"message\":\"null\"");
    }
}
