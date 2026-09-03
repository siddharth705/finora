package com.finora.imports.pdf;

import com.finora.imports.pdf.fixtures.PdfTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-document proof for {@link PdfTableLocator#resolveYearlessDate} (via its test-only
 * accessor): a real HSBC credit-card statement's one transaction that cycle prints its date as a
 * bare day+month with no year, and the statement period printed elsewhere on the same page
 * would supply the year that resolves it.
 *
 * <p><b>Cannot prove the recovered VALUE from this committed trace.</b> The current redaction
 * policy (allowlistFingerprint 286ED08D) replaces every digit with "9" and every letter with "X"
 * uniformly, including inside date-shaped runs -- "10 AUG 2026" becomes "99 XXX 9999" and "30JUN"
 * becomes "99XXX", destroying both the full-date shape {@link #yearsByPage} needs and the {@link
 * PdfTableLocator#WEAK_DAY_MONTH} shape {@link PdfTableLocator#resolveYearlessDate} needs. This is
 * the same limitation already documented on {@code
 * PaymentDueDateGridRegressionTest.extract_returnsNull_onTheRealRedactedSbiTrace} for a different
 * capability's trace -- see that test's own doc comment. What this test proves instead: real
 * geometry from the actual document, once redacted the same way any real document's PII would be,
 * correctly produces NO resolvable date rather than fabricating one from redaction placeholders
 * that happen to look date-shaped.
 *
 * <p>Deliberately does not assert anything about the whole document's row count. A confirmed,
 * separate, pre-existing quirk (unrelated to this fix -- the header-based path, untouched by this
 * change, picks up 2 garbage rows from this document's own Loan Summary table on the TRACE that it
 * does not pick up on the real PDF, most likely because redaction altered something about that
 * table's own header detection) means this trace's row count does not match the real document's.
 * The real document's row count (confirmed via {@code scripts/corpus-run.py} against the actual
 * PDF, both before and after this fix: unchanged at 0) is documented in
 * docs/superpowers/plans/2026-09-01-hsbc-yearless-date-resolution.md instead of asserted here,
 * since only the real PDF is authoritative for that fact -- not this trace.
 */
class HsbcCreditCardYearlessDateRegressionTest {

    private final PdfTableLocator locator = new PdfTableLocator();

    @Test
    void declinesToResolveARedactedDateRatherThanFabricatingOne() {
        List<PositionedText> runs = PdfTrace.load("hsbc-credit-card-yearless-dates");
        List<PositionedText> page0 = runs.stream().filter(t -> t.pageIndex() == 0).toList();
        assertThat(page0).isNotEmpty();

        Map<Integer, PdfTableLocator.PageDateEvidence> byPage =
                locator.yearsByPageForTest(page0.stream().map(List::of).toList());
        Set<Integer> page0Years =
                byPage.getOrDefault(0, PdfTableLocator.PageDateEvidence.NONE).years();
        assertThat(page0Years)
                .as("the real statement period's full dates are redacted into a shape "
                        + "(\"99 XXX 9999\") CsvParser.parseDate correctly rejects -- no year "
                        + "context should be recoverable from this specific trace")
                .isEmpty();

        boolean anyResolved = page0.stream()
                .anyMatch(cell -> locator.resolveYearlessDateForTest(cell.text().trim(), page0Years) != null);
        assertThat(anyResolved)
                .as("with no year context available, nothing on this redacted page should resolve")
                .isFalse();
    }
}
