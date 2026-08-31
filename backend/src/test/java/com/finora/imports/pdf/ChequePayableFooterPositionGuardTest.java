package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test: CHEQUE_PAYABLE_FOOTER_MARKER's own doc comment claims this sentence, confirmed
 * single-occurrence via grep, always means a real Axis Bank credit-card statement's true end. A
 * second real Axis document falsifies that: it prints the identical sentence on page 1, as part of
 * an ordinary payment-instructions panel next to the summary, well before the real transaction
 * table finishes. Fully synthetic fixtures -- no real document text quoted.
 */
class ChequePayableFooterPositionGuardTest {

    private static PositionedText run(String text, float x, float width, float y, int page) {
        return new PositionedText(text, x, y, page, width);
    }

    @Test
    void chequePayableFooterSentence_onAnEarlyPage_doesNotPrematurelyCloseTheSection() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(run("Date", 40f, 30f, 100f, 0));
        positioned.add(run("Description", 100f, 80f, 100f, 0));
        positioned.add(run("Amount", 300f, 45f, 100f, 0));
        positioned.add(run("11 Jul 26", 40f, 45f, 120f, 0));
        positioned.add(run("Card purchase one", 100f, 80f, 120f, 0));
        positioned.add(run("390.00", 300f, 40f, 120f, 0));
        // An early-page payment-instructions panel that happens to share this sentence, well
        // before the document's true end -- confirmed via pdftotext against the real document
        // this regression was found on.
        positioned.add(run("Your cheque should be payable to Axis Bank Card No.XXXXXXXXXXXX1234",
                20f, 400f, 140f, 0));
        // Page 1: a real transaction that must survive -- this is the actual rest of the table.
        positioned.add(run("12 Jul 26", 40f, 45f, 50f, 1));
        positioned.add(run("Card purchase two", 100f, 80f, 50f, 1));
        positioned.add(run("25.00", 300f, 40f, 50f, 1));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows)
                .as("the cheque-payable sentence on an early page must not close the section before "
                        + "the real table finishes -- it only means the document's true end when it "
                        + "sits on the document's own actual last page")
                .hasSize(2);
        assertThat(rows.get(1))
                .containsEntry("Description", "Card purchase two")
                .containsEntry("Amount", "25.00");
    }

    @Test
    void chequePayableFooterSentence_onTheDocumentsActualLastPage_stillClosesTheSection() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(run("Date", 40f, 30f, 100f, 0));
        positioned.add(run("Description", 100f, 80f, 100f, 0));
        positioned.add(run("Amount", 300f, 45f, 100f, 0));
        positioned.add(run("11 Jul 26", 40f, 45f, 120f, 0));
        positioned.add(run("Card purchase one", 100f, 80f, 120f, 0));
        positioned.add(run("390.00", 300f, 40f, 120f, 0));
        // Same page: the document's true end -- no later page exists at all, exactly the shape
        // CHEQUE_PAYABLE_FOOTER_MARKER was originally evidenced from.
        positioned.add(run("Your cheque should be payable to Axis Bank Card No.XXXXXXXXXXXX1234",
                20f, 400f, 140f, 0));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows)
                .as("on the document's own actual last page, this sentence still means the true end")
                .hasSize(1);
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("CHEQUE_PAYABLE_FOOTER_CLOSED");
    }
}
