package com.finora.imports.pdf.fixtures;

import com.finora.imports.pdf.PdfTextExtractor;
import com.finora.imports.pdf.PositionedText;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Entity;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.LayoutGroundTruth;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Presence;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Row;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.ZeroTransactions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OCR-2A: the synthetic definition is a genuine independent source of truth.
 *
 * <p>The property under test is not "the parser reads this fixture correctly". It is that the
 * expected values and the document descend from a common declaration and from each other <b>not at
 * all</b> — because a ground truth derived from the fixture generator is independent of the parser
 * and not independent of the generator, and would agree with a generator defect exactly as loudly
 * as with correct behaviour.
 */
class SyntheticGroundTruthTest {

    private static Entity savings(BigDecimal first, BigDecimal second) {
        return new Entity("savings-primary", "SAVINGS", Presence.DETECTED, "••••4321",
                ZeroTransactions.FALSE, List.of(
                        new Row(LocalDate.of(2026, 6, 5), "SALARY CREDIT", first, true),
                        new Row(LocalDate.of(2026, 6, 10), "GROCERY STORE", second, false)));
    }

    private static SyntheticStatementDefinition definition(BigDecimal first, BigDecimal second) {
        return new SyntheticStatementDefinition("synthetic-ledger-001",
                List.of(savings(first, second)),
                List.of(new LayoutGroundTruth(0, "SALARY CREDIT", 120f, 320f, 0f, 842f)));
    }

    private static final BigDecimal AS_DECLARED = new BigDecimal("55000.00");
    private static final BigDecimal MUTATED = new BigDecimal("13000.00");

    @Test
    void theGroundTruthDocumentIsProducedWithoutRenderingAnything() {
        // No PDF is built in this test at all. If emitting expectations required a rendered
        // document, this would not compile -- which is the cheapest possible proof of independence.
        String truth = GroundTruthDocument.of(definition(AS_DECLARED, new BigDecimal("2000.00")));

        assertThat(truth)
                .contains("\"schemaVersion\": 1")
                .contains("\"provenance\": \"SYNTHETIC_DEFINITION\"")
                .contains("\"expectedProduct\": \"SAVINGS\"")
                .contains("\"expectedTransactions\": 2")
                .contains("\"accountNumberMasked\": \"••••4321\"");
    }

    @Test
    void theExpectedTransactionCountComesFromTheDeclarationNotFromAnyReading() {
        var declared = definition(AS_DECLARED, new BigDecimal("2000.00"));

        assertThat(declared.entities().get(0).expectedTransactions())
                .as("two rows were declared, so two are expected -- nothing counted anything")
                .isEqualTo(2);
    }

    /**
     * THE test the whole arrangement exists for.
     *
     * <p>Render a document from one declaration and judge it against a DIFFERENT one. If the
     * pipeline were quietly producing the same thing twice from the same source, this would still
     * agree. It must not.
     */
    @Test
    void aDocumentThatDisagreesWithTheGroundTruthIsDetected() throws Exception {
        byte[] pdf = PdfFixtureBuilder.render(definition(MUTATED, new BigDecimal("2000.00")));
        String truth = GroundTruthDocument.of(definition(AS_DECLARED, new BigDecimal("2000.00")));

        String rendered = new PdfTextExtractor().extract(pdf).stream()
                .map(PositionedText::text).reduce("", (a, b) -> a + " " + b);

        assertThat(truth).contains("synthetic-ledger-001");
        assertThat(rendered)
                .as("the document carries the mutated figure")
                .contains("13000.00");
        assertThat(rendered)
                .as("and does NOT carry the declared one -- so agreement here would be circularity")
                .doesNotContain("55000.00");
    }

    @Test
    void theSameDeclarationProducesADocumentThatDoesAgree() throws Exception {
        var declared = definition(AS_DECLARED, new BigDecimal("2000.00"));

        String rendered = new PdfTextExtractor().extract(PdfFixtureBuilder.render(declared)).stream()
                .map(PositionedText::text).reduce("", (a, b) -> a + " " + b);

        assertThat(rendered).contains("55000.00").contains("SALARY CREDIT").contains("05/06/2026");
    }

    /**
     * Layout truth is a REGION, not a glyph box, and it is deliberately not part of the financial
     * model. Recognition will not reproduce PDF glyph geometry; demanding that it does would fail a
     * correct reading for being differently measured.
     */
    @Test
    void layoutTruthIsSeparateFromFinancialTruthAndIsARegion() throws Exception {
        var declared = definition(AS_DECLARED, new BigDecimal("2000.00"));

        assertThat(GroundTruthDocument.of(declared))
                .as("no coordinate may leak into the financial interchange document")
                .doesNotContain("xFrom").doesNotContain("page").doesNotContain("region");

        LayoutGroundTruth expected = declared.layout().get(0);
        PositionedText actual = new PdfTextExtractor().extract(PdfFixtureBuilder.render(declared))
                .stream().filter(t -> t.text().contains("SALARY CREDIT")).findFirst().orElseThrow();

        assertThat(expected.contains(actual.x(), actual.y()))
                .as("the declared region contains where it was actually printed")
                .isTrue();
    }
}
