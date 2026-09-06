package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code headerSignature} hashes a header row's RAW cells, before {@code coalesceHeaderRuns}/
 * {@code reconstructHeader} have a chance to normalize it -- see that call site's own comment. On
 * two real credit-card statements (SBI, Indusland), a repeated page header renders with a
 * different raw cell shape than the first page's header even though it means the exact same
 * columns, so the header-diff split treats the repeat as a genuinely new table and opens a second
 * section for what both real documents confirm is one continuous ledger, same account both times.
 * {@link PdfTableLocator#remergeSameTableSections} folds them back together once reconciliation
 * (which the split decision itself never waits for) proves the two sections' final column sets
 * are identical.
 *
 * <p>Coordinates and shapes only, per the Synthetic Fixture Policy -- every value below is
 * invented. This fixture reproduces the mechanism generically (a same-shaped header re-detected
 * with an extra blank cell, forcing a raw-signature mismatch) rather than either real document's
 * own specific rendering quirk.
 */
class ReconciledHeaderSectionsRemergedPdfTableLocatorTest {

    private static PositionedText run(String text, float x, float endX, float y) {
        return new PositionedText(text, x, y, 0, endX - x);
    }

    @Test
    void sameAccountRepeatedHeader_misdetectedAsNew_remergesIntoOneSection() {
        List<PositionedText> runs = new ArrayList<>();
        // First page: the real ledger's header, and one transaction row.
        runs.add(run("Date", 30f, 64f, 100f));
        runs.add(run("Transaction Details", 90f, 220f, 100f));
        runs.add(run("Amount", 400f, 440f, 100f));
        runs.add(run("01 Jun 2026", 30f, 90f, 120f));
        runs.add(run("SAMPLE MERCHANT ONE", 90f, 220f, 120f));
        runs.add(run("500.00", 400f, 440f, 120f));

        // Page break, then the SAME header repeats -- but with an extra blank trailing cell (a
        // real page's own currency-marker column, printed on some pages and not others), enough
        // to make the raw signature differ from the first page's even though every real column
        // name is identical. Real evidence: this exact "same table, glued back together once
        // reconciliation runs" shape is what BLANK_COLUMN_NAME_QUALIFIED already exists for
        // elsewhere in this class -- reused here as the synthetic trigger, not the real cause on
        // either evidencing document (SBI's own trigger is cross-row detachment, Indusland's is
        // same-row word-fragmentation; both still land on an identical RECONCILED header, which is
        // the one thing this test needs to reproduce).
        runs.add(run("Date", 30f, 64f, 200f));
        runs.add(run("Transaction Details", 90f, 220f, 200f));
        runs.add(run("Amount", 400f, 440f, 200f));
        runs.add(run("(`)", 450f, 460f, 200f));
        runs.add(run("02 Jun 2026", 30f, 90f, 220f));
        runs.add(run("SAMPLE MERCHANT TWO", 90f, 220f, 220f));
        runs.add(run("300.00", 400f, 440f, 220f));

        DocumentContext ctx = new DocumentContext("PDF", "ReconciledHeaderSectionsRemergedPdfTableLocatorTest");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);

        assertThat(ctx.capabilities()).extracting(c -> c.capability())
                .contains("RECONCILED_HEADER_SECTIONS_REMERGED");
        assertThat(doc.sections()).hasSize(1);

        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("Amount", "500.00");
        assertThat(rows.get(1)).containsEntry("Amount", "300.00");
    }

    /** Guard: two DIFFERENT real accounts sharing one column layout, split by a genuine identity
     *  banner (not a rendering quirk), must never be folded back into one section. */
    @Test
    void differentAccountsWithTheSameColumnLayout_areNeverRemerged() {
        List<PositionedText> runs = new ArrayList<>();
        runs.add(run("Date", 30f, 64f, 100f));
        runs.add(run("Transaction Details", 90f, 220f, 100f));
        runs.add(run("Amount", 400f, 440f, 100f));
        runs.add(run("01 Jun 2026", 30f, 90f, 120f));
        runs.add(run("SAMPLE MERCHANT ONE", 90f, 220f, 120f));
        runs.add(run("500.00", 400f, 440f, 120f));

        // A genuine SECTION_MARKER banner introducing a second, different real account -- same
        // column layout, but a different, positively-confirmed identity.
        runs.add(run("SAVINGS ACCOUNT - 1111222233334444", 30f, 300f, 180f)); // synthetic-ok: invented placeholder digits, not a real account number
        runs.add(run("Date", 30f, 64f, 200f));
        runs.add(run("Transaction Details", 90f, 220f, 200f));
        runs.add(run("Amount", 400f, 440f, 200f));
        runs.add(run("02 Jun 2026", 30f, 90f, 220f));
        runs.add(run("SAMPLE MERCHANT TWO", 90f, 220f, 220f));
        runs.add(run("300.00", 400f, 440f, 220f));

        DocumentContext ctx = new DocumentContext("PDF", "ReconciledHeaderSectionsRemergedPdfTableLocatorTest");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);

        assertThat(ctx.capabilities()).extracting(c -> c.capability())
                .doesNotContain("RECONCILED_HEADER_SECTIONS_REMERGED");
        assertThat(doc.sections()).hasSize(2);
    }
}
