package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces a real SBI Credit Card statement shape at invented coordinates and text, per the
 * Synthetic Fixture Policy: a caption line ("for Statement Period: ... to ...") sits just below
 * the accepted header's own sub-label ("( ` )", itself printed slightly lower than "Amount" as a
 * stacked two-line cell) -- close enough (a real 2.36pt gap) that {@code groupIntoRows}' chain-
 * based clustering (fixed as part of Phase 2E.5's HSBC row-formation fix, see
 * header-reconstruction-design.md §9.4) correctly folds it onto the header's own physical row,
 * rather than the header's fixed first-member anchor keeping it on a separate line the way the
 * pre-fix anchor-based comparison did.
 *
 * <p>Before this test's own fix (a {@code buildHeaderColumns} vocabulary filter), that had a
 * silent side effect: {@code buildHeaderColumns} turns every coalesced cell of the accepted header
 * row into a column name unconditionally, so the caption became a phantom third column that no
 * real transaction data was ever near enough to populate -- and since no row ever carries it and
 * headerNames themselves are never exposed in {@code LocatedSection}'s own returned data, the
 * caption's real text vanished from the document's output entirely: not a row, not auxiliary text,
 * nothing. A genuine information-loss regression, caught by {@code HeaderProseRejectionTest}'s own
 * real-SBI-trace assertion the moment chain-based row formation started reaching this shape.
 */
class OrphanedHeaderRowCaptionTest {

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    @Test
    void captionOnTheHeadersOwnPhysicalRow_doesNotBecomeAPhantomColumn_andSurvivesAsAuxiliaryText() {
        List<PositionedText> positioned = new ArrayList<>();
        // Header: "Date" | "Amount" over its own stacked sub-label "( Rs )" -- the sub-label sits
        // 0.8pt below "Amount", same real stacked-cell shape SBI's own header uses.
        positioned.add(run("Date", 40f, 30f, 114.2f));
        positioned.add(run("Amount", 379f, 50f, 114.2f));
        positioned.add(run("( Rs )", 413f, 40f, 115.0f));
        // The caption: 2.36pt below the sub-label -- inside groupIntoRows' 3.0pt tolerance measured
        // chain-wise from the sub-label, the same real HSBC-motivated fix this test's fixture
        // exploits to reach this shape at all.
        positioned.add(run("for Statement Period: 01 Jun 26 to 30 Jun 26", 154f, 220f, 117.36f));

        // Two real transactions -- neither carries any text anywhere near the caption's own x
        // (154f), so nothing ever populates a phantom column there.
        positioned.add(run("01 Jun 26", 32f, 55f, 140f));
        positioned.add(run("SAMPLE MERCHANT ONE", 73f, 90f, 140f));
        positioned.add(run("500.00", 380f, 50f, 140f));
        positioned.add(run("02 Jun 26", 32f, 55f, 155f));
        positioned.add(run("SAMPLE MERCHANT TWO", 73f, 90f, 155f));
        positioned.add(run("750.00", 380f, 50f, 155f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        PdfTableLocator.LocatedSection section = doc.sections().get(0);

        assertThat(section.rows())
                .as("two real transactions, neither polluted by the caption")
                .hasSize(2);
        for (var row : section.rows()) {
            assertThat(row.keySet())
                    .as("only the two real columns -- no phantom column for the caption's own x position")
                    .containsExactlyInAnyOrder("Date", "Amount");
        }

        assertThat(section.auxiliaryText())
                .as("the caption's real text survives as auxiliary text instead of silently vanishing")
                .anyMatch(line -> line.contains("for Statement Period: 01 Jun 26 to 30 Jun 26"));
    }
}
