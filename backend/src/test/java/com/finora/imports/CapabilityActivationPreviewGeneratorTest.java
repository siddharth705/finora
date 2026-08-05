package com.finora.imports;

import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 1 "capture facts" (docs/engineering/financial-document-intelligence-principles.md): CSV
 * side of the capability-activation coverage {@code CapabilityActivationPdfPreviewGeneratorTest}
 * provides for PDF -- DR_CR_SUFFIX, LEADING_PLUS_CREDIT, and DATE_TIME_COLUMN are all implemented
 * in CsvParser/TransactionNormalizer and shared by both pipelines, but only reachable on the CSV
 * side through a real header row (a PDF fixture can't exercise CsvParser.findHeaderRowIndex).
 */
class CapabilityActivationPreviewGeneratorTest {

    private PreviewGenerator realGenerator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggest(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService, duplicateDetector);
        return new PreviewGenerator(new CsvParser(), transactionNormalizer, new StatementValidator(com.finora.imports.product.ProductDiscovery.standard()), new com.finora.imports.BalanceChainValidator());
    }

    private List<String> activatedCapabilities(String csv) throws Exception {
        var result = realGenerator().generateWithContext(UUID.randomUUID(), "statement.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        return result.documentContext().capabilities().stream().map(a -> a.capability()).toList();
    }

    @Test
    void drCrSuffixAmountColumn_recordsDrCrSuffix() throws Exception {
        String csv = "Date,Description,Amount\n01-07-2026,Cafe,37.94 Dr\n02-07-2026,Refund,10081.99 Cr\n";

        assertThat(activatedCapabilities(csv)).contains("DR_CR_SUFFIX");
    }

    @Test
    void leadingPlusAmountColumn_recordsLeadingPlusCredit() throws Exception {
        String csv = "Date,Description,Amount\n01-07-2026,Salary,+50000.00\n02-07-2026,Rent,1200.00\n";

        assertThat(activatedCapabilities(csv)).contains("LEADING_PLUS_CREDIT");
    }

    @Test
    void dateAndTimeColumn_recordsDateTimeColumn() throws Exception {
        // Header is plain "Date" (not "Date & Time") deliberately -- CsvParser.findHeaderRowIndex's
        // own header-recognition hint list doesn't include "date & time" (only TransactionNormalizer's
        // column-value hints do), so a CSV whose ONLY date header is "Date & Time" never gets its
        // header row recognized at all -- a real, separate gap, out of scope for this test. The
        // DATE_TIME_COLUMN signal itself only depends on the CELL VALUE carrying a trailing
        // time-of-day, not on which header hint matched it.
        String csv = "Date,Description,Amount,Type\n30/06/2026 14:18,UPI Payment,440.00,DR\n";

        assertThat(activatedCapabilities(csv)).contains("DATE_TIME_COLUMN");
    }

    @Test
    void runningBalanceColumn_recordsRunningBalance() throws Exception {
        String csv = "Date,Description,Amount,Type,Balance\n01-07-2026,Salary,50000.00,CR,50000.00\n";

        assertThat(activatedCapabilities(csv)).contains("RUNNING_BALANCE");
    }

    @Test
    void metadata_reportsCsvStructuralFacts() throws Exception {
        String csv = "Date,Description,Amount,Type\n01-07-2026,Salary,50000.00,CR\n";
        var result = realGenerator().generateWithContext(UUID.randomUUID(), "statement.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        var metadata = result.documentContext().buildMetadata();
        assertThat(metadata.sourceFormat()).isEqualTo("CSV");
        assertThat(metadata.parser()).isEqualTo("PreviewGenerator");
        assertThat(metadata.tables()).isEqualTo(1);
        assertThat(metadata.headers()).containsExactly("Date", "Description", "Amount", "Type");
        assertThat(result.documentContext().buildFingerprint()).matches("FP-1-[0-9A-F]{8}");
    }
}
