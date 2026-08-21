package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagingResponse;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.PdfPreviewGenerator.PdfGenerationResult;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.pdf.fixtures.PdfTrace;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * How much evidence it takes before a section is called a credit card.
 *
 * <p>The free-text half of that decision used to fire off a SINGLE phrase match, which
 * misclassified real savings/current accounts two ways: off the generic anti-phishing notice
 * every bank prints ("... never share your card number ..."), and off a relationship summary
 * table's shared "Credit Limit" column header reprinted once per account category. Both are
 * covered below, together with the true positives that must keep working, the row-header path
 * that this never touched, and the exact threshold either side of the boundary.
 *
 * <p>Two follow-up findings are covered here too, both from measuring the rule against documents
 * it had not been measured against before. The phrase list was missing whole issuers' vocabulary
 * ("Minimum Payment Due", "Total Amount Due"), because substring matching does not collapse one
 * spelling into another; and "credit limit" was carrying its share of the evidence as a bare word,
 * which is what let a summary column header and an overdraft's terms stand in for a card
 * statement's field. It is now required to look like a labelled field with an amount. The
 * two-distinct-signal threshold itself is unchanged throughout.
 *
 * <p>Asserted through the whole generator rather than the private predicate, because the thing
 * that actually matters to a user is the account type the review form gets prefilled with --
 * {@code suggestedAccountType} -- plus the {@code CREDIT_CARD_SUMMARY_SIGNAL} capability the
 * document context records for the corpus.
 */
class CreditCardSignalEvidenceThresholdPdfPreviewGeneratorTest {

    private PdfPreviewGenerator realGenerator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService, duplicateDetector, com.finora.imports.TestRuleEngines.empty());

        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(),
                new PdfMetadataExtractor(), transactionNormalizer, com.finora.imports.product.ProductDiscovery.standard(), new com.finora.imports.product.ProductAttributeExtractor(), new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(), new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(), new com.finora.imports.ColumnAmbiguityValidator(), new com.finora.imports.RowAccountingValidator(), new com.finora.imports.CreditCardStatementTotalsValidator(), new com.finora.imports.CreditCardFlowReconciliationValidator()),
                com.finora.imports.TestRuleEngines.empty());
    }

    private String suggestedAccountType(byte[] pdfBytes, String filename) throws Exception {
        StagingResponse response = realGenerator().generate(UUID.randomUUID(), filename, pdfBytes);
        return response.detectedAccount().suggestedAccountType();
    }

    private List<String> activatedCapabilities(byte[] pdfBytes, String filename) throws Exception {
        PdfGenerationResult result = realGenerator().generateSectionsWithContext(UUID.randomUUID(), filename, pdfBytes);
        return result.documentContext().capabilities().stream().map(a -> a.capability()).toList();
    }

    // ---------- false positives that used to happen ----------

    @Test
    void antiPhishingNoticeMentioningACardNumber_isNotACreditCardStatement() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildIncidentalCardNumberSecurityNoticeSample();

        assertThat(suggestedAccountType(pdf, "savings_with_security_notice.pdf")).isEqualTo("SAVINGS");
    }

    @Test
    void antiPhishingNoticeMentioningACardNumber_doesNotRecordTheCreditCardCapability() throws Exception {
        List<String> activated = activatedCapabilities(
                PdfFixtureBuilder.buildIncidentalCardNumberSecurityNoticeSample(), "savings_with_security_notice.pdf");

        assertThat(activated).doesNotContain("CREDIT_CARD_SUMMARY_SIGNAL");
    }

    @Test
    void aSummaryTableRepeatingOneCreditLimitColumnHeader_isNotACreditCardStatement() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildRepeatedCreditLimitColumnSummarySample();

        // Three occurrences of "Credit Limit", one distinct phrase -- the count that matters is
        // the second one.
        assertThat(suggestedAccountType(pdf, "relationship_summary_statement.pdf")).isEqualTo("SAVINGS");
    }

    @Test
    void aSummaryTableRepeatingOneCreditLimitColumnHeader_doesNotRecordTheCreditCardCapability() throws Exception {
        List<String> activated = activatedCapabilities(
                PdfFixtureBuilder.buildRepeatedCreditLimitColumnSummarySample(), "relationship_summary_statement.pdf");

        assertThat(activated).doesNotContain("CREDIT_CARD_SUMMARY_SIGNAL");
    }

    // ---------- the threshold itself ----------

    @Test
    void exactlyOneDistinctSignalPhrase_isNotEnoughToClassifyACreditCard() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildOneDistinctCreditCardPhraseSample();

        assertThat(suggestedAccountType(pdf, "one_signal_statement.pdf")).isEqualTo("SAVINGS");
        assertThat(activatedCapabilities(pdf, "one_signal_statement.pdf"))
                .doesNotContain("CREDIT_CARD_SUMMARY_SIGNAL");
    }

    @Test
    void exactlyTwoDistinctSignalPhrases_areEnoughToClassifyACreditCard() throws Exception {
        // The same document as above with one extra line, so the ONLY difference between passing
        // and failing the threshold is the second distinct phrase.
        byte[] pdf = PdfFixtureBuilder.buildTwoDistinctCreditCardPhrasesSample();

        assertThat(suggestedAccountType(pdf, "two_signal_statement.pdf")).isEqualTo("CREDIT_CARD");
        assertThat(activatedCapabilities(pdf, "two_signal_statement.pdf"))
                .contains("CREDIT_CARD_SUMMARY_SIGNAL");
    }

    // ---------- true positives that must keep working ----------

    @Test
    void aRealCreditCardPaymentSummary_isStillClassifiedAsACreditCard_drCrSuffixLayout() throws Exception {
        assertThat(suggestedAccountType(PdfFixtureBuilder.buildDrCrSuffixAmountColumnSample(),
                "dr_cr_suffix_statement.pdf")).isEqualTo("CREDIT_CARD");
    }

    @Test
    void aRealCreditCardPaymentSummary_isStillClassifiedAsACreditCard_wrappedDescriptionLayout() throws Exception {
        assertThat(suggestedAccountType(PdfFixtureBuilder.buildWrappedDescriptionCreditCardSample(),
                "credit_card_statement.pdf")).isEqualTo("CREDIT_CARD");
    }

    @Test
    void aRealCreditCardPaymentSummary_isStillClassifiedAsACreditCard_multiColumnGridLayout() throws Exception {
        assertThat(suggestedAccountType(PdfFixtureBuilder.buildMultiColumnPaymentSummaryGridSample(),
                "neo_rupay_statement.pdf")).isEqualTo("CREDIT_CARD");
    }

    @Test
    void aRealCreditCardPaymentSummary_isStillClassifiedAsACreditCard_gridMetadataFallbackLayout() throws Exception {
        assertThat(suggestedAccountType(PdfFixtureBuilder.buildGridMetadataFallbackSample(),
                "tata_neu_statement.pdf")).isEqualTo("CREDIT_CARD");
    }

    // ---------- the row-header path, which is independent of all of the above ----------

    @Test
    void aLabelledCardNumberColumn_stillClassifiesACreditCard_withNoPaymentSummaryTextAtAll() throws Exception {
        // "card number" was dropped from the FREE-TEXT list only. The row-level header check is a
        // separate path and keeps its own three keys ("card number", "minimum due", "minimum
        // amount due"); a document whose only evidence is a labelled column must still be caught.
        byte[] pdf = PdfFixtureBuilder.buildCardNumberColumnOnlySample();

        assertThat(suggestedAccountType(pdf, "card_number_column_statement.pdf")).isEqualTo("CREDIT_CARD");
        assertThat(activatedCapabilities(pdf, "card_number_column_statement.pdf"))
                .contains("CREDIT_CARD_SUMMARY_SIGNAL");
    }

    // ---------- issuer vocabulary: the spellings real statements actually print ----------

    /**
     * The false negative this vocabulary fix closed, asserted from the other side now.
     *
     * <p>The phrase list used to hold "total payment due", "minimum amount due" and "minimum due"
     * only. Substring matching does not collapse those into the spellings real issuers print --
     * "Minimum Payment Due" (Axis) contains none of them, and "Total Amount Due" (HDFC, AU)
     * contains none of them either -- so a genuine card statement worded that way reached at most
     * one listed phrase and was prefilled as SAVINGS. This fixture carries no credit-limit field
     * and nothing else card-shaped, so it can ONLY pass on the two added spellings.
     */
    @Test
    void aCardStatementWordedTheWayRealIssuersWordIt_isClassifiedAsACreditCard() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildRealWorldPaymentSummaryLabelWordingSample();

        assertThat(suggestedAccountType(pdf, "label_wording_card_statement.pdf")).isEqualTo("CREDIT_CARD");
        assertThat(activatedCapabilities(pdf, "label_wording_card_statement.pdf"))
                .contains("CREDIT_CARD_SUMMARY_SIGNAL");
    }

    /**
     * Axis's own wording, on the Axis-derived fixture, with nothing added to it.
     *
     * <p>Worth its own test beside {@link #aRealCreditCardPaymentSummary_isStillClassifiedAsACreditCard_drCrSuffixLayout}
     * because of how that test was previously being kept green: an earlier pass appended a
     * synthetic "Credit Limit 1,50,000.00" clause to this fixture's payment-summary line, which
     * made the assertion pass while the classifier could not in fact recognise Axis's vocabulary
     * at all. The clause is gone; the two signals below are Axis's actual labels.
     */
    @Test
    void axisRealWording_totalPaymentDuePlusMinimumPaymentDue_isTwoDistinctSignals() {
        long signals = signalCount(
                "Total Payment Due 27,665.16 Dr Minimum Payment Due 577.00 Dr");

        assertThat(signals).isEqualTo(2);
    }

    /** HDFC's own wording, same story -- "Total Amount Due" plus "Minimum Due", no credit-limit
     *  clause, no synthetic help. */
    @Test
    void hdfcRealWording_totalAmountDuePlusMinimumDue_isTwoDistinctSignals() {
        long signals = signalCount("Total Amount Due 950.00 Minimum Due 100.00");

        assertThat(signals).isEqualTo(2);
    }

    /** No single label may count twice: none of the listed phrases is a substring of another, so
     *  one label is one signal and a lone payment-summary line can never self-corroborate. */
    @Test
    void oneLabelIsOneSignal_evenWhereTheSpellingsOverlapInWords() {
        assertThat(signalCount("Minimum Payment Due 577.00")).isEqualTo(1);
        assertThat(signalCount("Total Amount Due 950.00")).isEqualTo(1);
        assertThat(signalCount("Minimum Amount Due 200.00")).isEqualTo(1);
    }

    // ---------- the credit limit, as a labelled field rather than a word ----------

    /** The shape AU's real statement prints, in each of the separator/currency variants a
     *  statement's text layer plausibly yields. Each is ONE signal, not a classification. */
    @Test
    void aLabelledCreditLimitFieldWithAnAmount_countsAsOneSignal() {
        assertThat(signalCount("Total Credit Limit: ₹1,00,000.00")).isEqualTo(1);
        assertThat(signalCount("Credit Limit 1,50,000.00")).isEqualTo(1);
        assertThat(signalCount("Available Credit Limit - Rs. 45,000")).isEqualTo(1);
        assertThat(signalCount("Credit Limit: INR 1,00,000.00")).isEqualTo(1);
    }

    /**
     * The adversarial half: text that mentions a credit limit without being one, which is exactly
     * what the old bare-substring entry could not tell apart.
     *
     * <p>"Credit Limit" as a summary-table column header has no amount after it; an overdraft's
     * terms put prose between the label and the number; and "credit" and "limit" have to be
     * adjacent, so an unrelated sentence that happens to contain both words near an amount is not
     * a match either.
     */
    @Test
    void aCreditLimitMentionedWithoutBeingALabelledField_countsForNothing() {
        assertThat(signalCount("Deposits and Investments      Balance      Credit Limit")).isZero();
        assertThat(signalCount("Savings Account   101,595.09   Credit Limit   Not Applicable")).isZero();
        assertThat(signalCount("Overdraft facility: your sanctioned credit limit is 2,00,000.00.")).isZero();
        assertThat(signalCount("No credit check required, limit 1 per customer.")).isZero();
        assertThat(signalCount("Your credit score and the limit of 5,000 free transfers")).isZero();
        assertThat(signalCount("Credit Limit")).isZero();
        assertThat(signalCount("Credit Limit: Not Applicable")).isZero();
    }

    // ---------- adversarial probe, now closed ----------

    /**
     * Was pinned as a known unfixed false positive: an ordinary current account whose overdraft
     * terms name both a "credit limit" and a "minimum due", clearing the two-signal threshold
     * without the document being a credit card.
     *
     * <p>Requiring the credit limit to be a LABELLED FIELD closed it without touching the
     * threshold. The fixture's wording is unchanged from when it false-positived -- an overdraft's
     * terms describe the limit in prose ("your sanctioned credit limit is 2,00,000.00"), which is
     * not the field shape, so the document now carries one signal instead of two.
     *
     * <p>The narrower claim being made here is worth stating plainly: this is not proof that no
     * overdraft statement can ever false-positive. A document that printed "Credit Limit:
     * 2,00,000.00" as an actual labelled field alongside a "minimum due" still would. What the
     * structural check buys is that the mention has to look like a card statement's field, not
     * merely contain the words -- and prose, which is how the observed adversarial shape reads, no
     * longer qualifies.
     */
    @Test
    void currentAccountOverdraftTerms_areNoLongerAFalsePositive() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildOverdraftTermsCurrentAccountSample();

        assertThat(suggestedAccountType(pdf, "current_account_overdraft_statement.pdf")).isEqualTo("SAVINGS");
        assertThat(activatedCapabilities(pdf, "current_account_overdraft_statement.pdf"))
                .doesNotContain("CREDIT_CARD_SUMMARY_SIGNAL");
    }

    // ---------- a real statement, end to end ----------

    /**
     * The whole rule measured against a real AU Small Finance Bank credit-card statement, via the
     * committed positional trace of its text layer rather than a fixture anyone here wrote.
     *
     * <p>This document is the reason both halves of the fix exist, and it is the one that shows
     * why "does it classify correctly" was too weak a question to have been asking. Under the
     * previous rule it passed on EXACTLY two signals -- "Minimum amount due", plus a bare "credit
     * limit" substring -- i.e. with zero margin, and one of those two was the entry that
     * false-positived HSBC. It now clears the threshold on three, and every one of them is
     * something a card statement genuinely prints: "Minimum amount due", "Total amount due" (a
     * spelling that was not on the list at all), and a real labelled "Total Credit Limit:
     * ₹1,00,000.00" field.
     */
    @Test
    void aRealAuCreditCardStatement_clearsTheThresholdWithMarginToSpare() {
        List<String> auxiliaryText = auPaymentSummarySectionText();

        assertThat(new PdfPreviewGenerator((PdfTextExtractor) null, null, null, null, null, null, null, null)
                .countDistinctCreditCardSignals(auxiliaryText))
                .as("AU's payment summary: 'Total amount due' + 'Minimum amount due' + a labelled "
                        + "'Total Credit Limit: <amount>' field")
                .isEqualTo(3);
    }

    /** Names the three signals individually, so a regression that swapped one for another (and so
     *  kept the count at three) still fails here. */
    @Test
    void aRealAuCreditCardStatement_carriesEachOfTheThreeSignalsIndividually() {
        List<String> lower = auPaymentSummarySectionText().stream()
                .filter(l -> l != null).map(l -> l.toLowerCase(java.util.Locale.ROOT)).toList();

        assertThat(lower).anyMatch(l -> l.contains("total amount due"));
        assertThat(lower).anyMatch(l -> l.contains("minimum amount due"));
        assertThat(lower).anyMatch(l -> l.contains("total credit limit: ₹1,00,000.00"));
    }

    /**
     * The same document, but through {@code generate} from PDF bytes rather than from the located
     * section -- the path a user's upload actually takes -- so the assertion above cannot be
     * passing on a section that the real pipeline would never have assembled.
     */
    @Test
    void aRealAuCreditCardStatement_isPrefilledAsACreditCardEndToEnd() throws Exception {
        byte[] pdf = renderTrace(PdfTrace.load(AU_CREDIT_CARD_TRACE));

        assertThat(suggestedAccountType(pdf, "au_credit_card_statement.pdf")).isEqualTo("CREDIT_CARD");
        assertThat(activatedCapabilities(pdf, "au_credit_card_statement.pdf"))
                .contains("CREDIT_CARD_SUMMARY_SIGNAL");
    }

    // ---------- helpers ----------

    private static final String AU_CREDIT_CARD_TRACE = "au-credit-card-statement";

    /** The AU statement's first section -- the one carrying the payment summary block. */
    private List<String> auPaymentSummarySectionText() {
        return new PdfTableLocator().locateAll(PdfTrace.load(AU_CREDIT_CARD_TRACE), null)
                .sections().get(0).auxiliaryText();
    }

    private long signalCount(String... auxiliaryLines) {
        return new PdfPreviewGenerator((PdfTextExtractor) null, null, null, null, null, null, null, null)
                .countDistinctCreditCardSignals(List.of(auxiliaryLines));
    }

    /**
     * Draws a trace's runs back onto a PDF at their captured coordinates. Deliberately the same
     * technique the corpus measurement harness uses: the layout is reconstructed, not the original
     * file, which is enough for a classification question that reads the text layer.
     *
     * <p>Non-ASCII glyphs are dropped, since the standard-14 font cannot encode them -- which
     * incidentally exercises the currency symbol being optional in the credit-limit field pattern:
     * end to end, AU's line arrives as "Total Credit Limit: 1,00,000.00" with no symbol at all.
     */
    private static byte[] renderTrace(List<PositionedText> runs) throws Exception {
        int pageCount = runs.stream().mapToInt(PositionedText::pageIndex).max().orElse(0) + 1;
        try (PDDocument doc = new PDDocument()) {
            for (int p = 0; p < pageCount; p++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                float h = PDRectangle.A4.getHeight();
                final int pageIndex = p;
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 9f);
                    for (PositionedText r : runs) {
                        if (r.pageIndex() != pageIndex) continue;
                        String text = ascii(r.text());
                        if (text.isEmpty()) continue;
                        cs.beginText();
                        cs.newLineAtOffset(r.x(), h - r.y());
                        cs.showText(text);
                        cs.endText();
                    }
                }
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    private static String ascii(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) if (c >= 32 && c < 127) sb.append(c);
        return sb.toString();
    }
}
