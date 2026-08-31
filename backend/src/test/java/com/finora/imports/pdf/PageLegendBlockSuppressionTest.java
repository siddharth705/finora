package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PAGE_LEGEND_BLOCK_SUPPRESSED. Verified against a real SBI credit-card statement: a legal/legend
 * block ("Transactions highlighted in grey color...", the "C=Credit ; D=Debit..." abbreviation
 * key, an "Important Messages" heading, then open-ended late-payment-charges prose) prints at the
 * bottom of EVERY page, not once at the true end of the table. With no recognized boundary marker
 * for it, the ordinary trailing-continuation merge glued the whole block onto the last real
 * transaction above the page break -- see pageLegendBlockActive's own doc comment in
 * PdfTableLocator.
 */
class PageLegendBlockSuppressionTest {

    private static PositionedText run(String text, float x, float width, float y, int page) {
        return new PositionedText(text, x, y, page, width);
    }

    @Test
    void legendBlockAtAPageBreak_doesNotPolluteTheLastTransactionAboveIt_andRealRowsResumeOnTheNextPage() {
        List<PositionedText> positioned = new ArrayList<>();
        // Page 0: header, one real transaction, then the page-end legend block.
        positioned.add(run("Date", 40f, 30f, 100f, 0));
        positioned.add(run("Description", 100f, 80f, 100f, 0));
        positioned.add(run("Amount", 300f, 45f, 100f, 0));
        positioned.add(run("11 Jul 26", 40f, 45f, 120f, 0));
        positioned.add(run("UPI-VMPL DEL 24", 100f, 80f, 120f, 0));
        positioned.add(run("390.00", 300f, 40f, 120f, 0));
        positioned.add(run("Transactions highlighted in grey color, if any, do not form part of "
                + "Purchases & Other Debits", 20f, 400f, 140f, 0));
        positioned.add(run("C=Credit ; D=Debit; EN=Encash; FP=Flexipay", 20f, 350f, 150f, 0));
        positioned.add(run("Important Messages", 250f, 90f, 160f, 0));
        positioned.add(run("W.e.f. 1st May 2026, Late Payment Charges will be revised", 20f, 400f, 170f, 0));
        // Page 1: the header repeats, then real transactions resume.
        positioned.add(run("Date", 40f, 30f, 50f, 1));
        positioned.add(run("Description", 100f, 80f, 50f, 1));
        positioned.add(run("Amount", 300f, 45f, 50f, 1));
        positioned.add(run("12 Jul 26", 40f, 45f, 70f, 1));
        positioned.add(run("UPI-ZOMATO", 100f, 80f, 70f, 1));
        positioned.add(run("25.00", 300f, 40f, 70f, 1));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
                .as("the legend block must not be glued onto the last transaction above the page break")
                .containsEntry("Description", "UPI-VMPL DEL 24")
                .containsEntry("Amount", "390.00");
        assertThat(rows.get(1))
                .as("a real transaction on the next page must still be recovered, not discarded with the legend")
                .containsEntry("Description", "UPI-ZOMATO")
                .containsEntry("Amount", "25.00");

        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("PAGE_LEGEND_BLOCK_SUPPRESSED");
    }
}
