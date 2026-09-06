package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A second transaction on the same day, printed with no date of its own -- confirmed on a real
 * HSBC savings statement (OCR-acquired, but the mechanism is generic to any acquisition source;
 * see this class's own {@code SAME_DAY_CONTINUATION_TRANSACTION} doc comment for the full trace).
 *
 * <p>The date-anchor model this class otherwise relies on ({@code hasDateValue} marks a new
 * transaction) assumes every transaction prints its own date. A document whose second same-day
 * transaction does not repeat the date has nothing to anchor on, and once its own reference-number
 * lines exhaust the trailing-continuation cap (both the plain count and the pitch-based rescue),
 * its amount+balance row falls into the ordinary "leading narration for whatever anchor comes
 * next" path -- misdating it onto the FOLLOWING day's transaction, and (once a third transaction's
 * own balance later collides with the value that misattribution left behind) losing that third
 * transaction's structured values entirely.
 *
 * <p>Coordinates and shapes only, per the Synthetic Fixture Policy -- every value below is
 * invented. The final gap between the second transaction's own reference lines and its
 * amount+balance row is deliberately widened past {@code BLOCK_PITCH_TOLERANCE}: a perfectly
 * uniform line pitch would let {@code continuesTheBlock} rescue every row regardless of the cap,
 * which is not this class's usual behaviour -- the real document's own OCR-measured gaps drift by
 * exactly this much for the identical reason (see the real trace this test's own class-level
 * comment cites).
 */
class SameDayContinuationTransactionPdfTableLocatorTest {

    private static PositionedText run(String text, float x, float endX, float y) {
        return new PositionedText(text, x, y, 0, endX - x);
    }

    @Test
    void secondSameDayTransactionWithNoDateOfItsOwn_getsItsOwnRowUnderTheSameDate() {
        List<PositionedText> runs = new ArrayList<>();
        runs.add(run("Date", 30f, 64f, 100f));
        runs.add(run("Details", 90f, 220f, 100f));
        runs.add(run("Withdrawals", 300f, 360f, 100f));
        runs.add(run("Deposits", 400f, 460f, 100f));
        runs.add(run("Balance", 500f, 560f, 100f));

        // First transaction's anchor row -- its own date, its own leading narration.
        runs.add(run("01 Jun 2026", 30f, 90f, 120f));
        runs.add(run("REF0001", 90f, 160f, 120f));
        runs.add(run("REF0002", 90f, 160f, 132f));
        runs.add(run("John Smith", 90f, 160f, 144f));
        runs.add(run("500.00", 400f, 460f, 144f));
        runs.add(run("1500.00", 500f, 560f, 144f));

        // Second transaction, SAME DAY -- no date of its own. Two reference lines exhaust the
        // trailing cap's aligned-narration extension exactly as the real document does, and the
        // final gap to its own amount+balance row is widened past BLOCK_PITCH_TOLERANCE (13.7pt
        // vs. blockPitch's learned 12pt) so continuesTheBlock cannot rescue it either -- the same
        // drift the real OCR-acquired document showed at the equivalent row.
        runs.add(run("REF0003", 90f, 160f, 156f));
        runs.add(run("REF0004", 90f, 160f, 168f));
        runs.add(run("Acme Corp", 90f, 160f, 181.7f));
        runs.add(run("800.00", 300f, 360f, 181.7f));
        runs.add(run("700.00", 500f, 560f, 181.7f));

        // Third transaction, a genuinely new day.
        runs.add(run("02 Jun 2026", 30f, 90f, 195f));
        runs.add(run("REF0005", 90f, 160f, 195f));
        runs.add(run("REF0006", 90f, 160f, 207f));
        runs.add(run("Payee X", 90f, 160f, 219f));
        runs.add(run("200.00", 300f, 360f, 219f));
        runs.add(run("500.00", 500f, 560f, 219f));

        DocumentContext ctx = new DocumentContext("PDF", "SameDayContinuationTransactionPdfTableLocatorTest");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);

        assertThat(ctx.capabilities()).extracting(c -> c.capability())
                .contains("SAME_DAY_CONTINUATION_TRANSACTION");
        assertThat(doc.sections()).hasSize(1);

        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows).hasSize(3);

        assertThat(rows.get(0)).containsEntry("Date", "01 Jun 2026")
                .containsEntry("Deposits", "500.00")
                .containsEntry("Balance", "1500.00");
        // The second transaction inherits the first's date -- the document never reprints it --
        // and keeps ITS OWN amount and balance, not the first transaction's or the third's.
        assertThat(rows.get(1)).containsEntry("Date", "01 Jun 2026")
                .containsEntry("Withdrawals", "800.00")
                .containsEntry("Balance", "700.00");
        assertThat(rows.get(2)).containsEntry("Date", "02 Jun 2026")
                .containsEntry("Withdrawals", "200.00")
                .containsEntry("Balance", "500.00");
    }
}
