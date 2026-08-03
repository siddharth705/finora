package com.finora.imports.pdf;

import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real-document-evidenced bug fix: against an actual Axis Bank Neo Rupay credit card statement,
 * PdfMetadataExtractor previously extracted NONE of Payment Due Date, Credit Limit, or Account
 * Holder Name, even though all three are genuinely present in the file -- Payment Due Date and
 * Credit Limit sit in a multi-column payment-summary grid (a header line of several labels, then
 * a value line of several values) the original single-field GRID_DUE_DATE_LABEL fallback never
 * covered, and Account Holder Name has no label anywhere at all, appearing only as the document's
 * literal first line.
 */
class MultiColumnPaymentSummaryGridPdfPreviewGeneratorTest {

    private PdfPreviewGenerator realGenerator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggest(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService, duplicateDetector);

        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(),
                new PdfMetadataExtractor(), transactionNormalizer, com.finora.imports.product.ProductDiscovery.standard());
    }

    private DetectedAccountInfo detect() throws Exception {
        return realGenerator().generate(UUID.randomUUID(), "axis_statement.pdf",
                PdfFixtureBuilder.buildMultiColumnPaymentSummaryGridSample()).detectedAccount();
    }

    @Test
    void generate_extractsPaymentDueDate_fromTheMultiColumnGrid_notThePeriodRangeDate() throws Exception {
        DetectedAccountInfo detected = detect();

        // 20/07/2026 is the real due-date column; 01/06/2026 and 30/06/2026 are the Statement
        // Period range sharing the same row -- must not be picked instead.
        assertThat(detected.paymentDueDate()).isEqualTo(LocalDate.of(2026, 7, 20));
    }

    @Test
    void generate_extractsCreditLimit_notAvailableCreditLimit_fromTheMultiColumnGrid() throws Exception {
        DetectedAccountInfo detected = detect();

        assertThat(detected.creditLimit()).isEqualByComparingTo(new BigDecimal("100000.00"));
    }

    @Test
    void generate_extractsAccountHolderName_fromTheUnlabeledLeadingLine() throws Exception {
        DetectedAccountInfo detected = detect();

        assertThat(detected.accountHolderName()).isEqualTo("RAHUL VERMA");
    }

    @Test
    void generate_stillDetectsCreditCard_andParsesTheTransactionRow() throws Exception {
        var response = realGenerator().generate(UUID.randomUUID(), "axis_statement.pdf",
                PdfFixtureBuilder.buildMultiColumnPaymentSummaryGridSample());

        assertThat(response.detectedAccount().suggestedAccountType()).isEqualTo("CREDIT_CARD");
        assertThat(response.rows()).hasSize(1);
        assertThat(response.rows().get(0).amount()).isEqualByComparingTo("500.00");
    }

    @Test
    void existingBankLetterheadFixtures_stillNeverMisreadTheirLeadingBankNameLineAsAnAccountHolder() throws Exception {
        // Guards the exact false-positive the leading-name-line pattern risks: "AXIS BANK" and
        // "HDFC BANK" both shape-match a plausible name (two capitalized words, no digits) just as
        // well as a real customer's name does -- only the BankRegistry rejection tells them apart.
        var axis = realGenerator().generate(UUID.randomUUID(), "a.pdf",
                PdfFixtureBuilder.buildDrCrSuffixAmountColumnSample());
        assertThat(axis.detectedAccount().accountHolderName()).isNull();

        var hdfc = realGenerator().generate(UUID.randomUUID(), "b.pdf",
                PdfFixtureBuilder.buildWrappedDescriptionCreditCardSample());
        assertThat(hdfc.detectedAccount().accountHolderName()).isNull();
    }
}
