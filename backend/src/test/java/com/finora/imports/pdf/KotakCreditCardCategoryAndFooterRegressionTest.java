package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import com.finora.imports.pdf.fixtures.PdfTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Captured from a real Kotak Mahindra Bank credit-card statement whose ledger groups rows under
 * bare category-header lines ("Payments and Other Credits", "Primary Card Transactions- <masked
 * card>", "Retail Purchases and Cash Transactions") with no date or amount of their own, whose
 * page-1 footer is an instructional payment-methods legend, whose payment due date is stated as a
 * sentence ("Remember to pay by <date>") rather than any "Due Date" label, and whose statement
 * period is stated inside the transaction table's own repeated header row rather than any
 * pre-table field. See CREDIT_CARD_CATEGORY_HEADER, PAGE_LEGEND_BLOCK_START and
 * PAYMENT_DUE_DATE_SENTENCE's own doc comments.
 */
class KotakCreditCardCategoryAndFooterRegressionTest {

    private static final String TRACE = "kotak-credit-card-category-sections-and-page-footer";

    @Test
    void categoryHeadersAndThePageFooterLegendNeverPolluteAnyTransactionDescription() {
        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(PdfTrace.load(TRACE), ctx);

        assertThat(doc.sections()).hasSize(1);
        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows).isNotEmpty();

        for (Map<String, String> row : rows) {
            for (String value : row.values()) {
                if (value == null) continue;
                String lower = value.toLowerCase();
                assertThat(lower).as("row must not carry the 'Payments and Other Credits' category header: " + row)
                        .doesNotContain("payments and other credits");
                assertThat(lower).as("row must not carry the 'Primary Card Transactions' category header: " + row)
                        .doesNotContain("primary card transactions");
                assertThat(lower).as("row must not carry the 'Retail Purchases' category header: " + row)
                        .doesNotContain("retail purchases");
                assertThat(lower).as("row must not carry the page-footer payment-instructions legend: " + row)
                        .doesNotContain("using the following");
                assertThat(lower).as("row must not carry the page-footer legend's 'contact us' sentence: " + row)
                        .doesNotContain("contact us");
            }
        }

        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("TRANSACTION_CATEGORY_HEADER_SUPPRESSED", "PAGE_LEGEND_BLOCK_SUPPRESSED");
    }

    @Test
    void paymentDueDateIsReadFromItsSentenceRatherThanStayingNull() {
        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(PdfTrace.load(TRACE), ctx);

        var metadata = new PdfMetadataExtractor().extract(doc.sections().get(0).auxiliaryText(), ctx);

        assertThat(metadata.paymentDueDate()).isNotNull();
    }

    @Test
    void statementPeriodIsReadFromTheTablesOwnRepeatedHeaderRow() {
        DocumentContext ctx = new DocumentContext("PDF", "test");
        var range = TransactionTableDateRangeExtractor.extract(PdfTrace.load(TRACE), ctx);

        assertThat(range.start()).as("printed period start").isNotNull();
        assertThat(range.end()).as("printed period end").isNotNull();
        assertThat(range.start()).isBefore(range.end());
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("PRINTED_TRANSACTION_TABLE_DATE_RANGE");
    }
}
