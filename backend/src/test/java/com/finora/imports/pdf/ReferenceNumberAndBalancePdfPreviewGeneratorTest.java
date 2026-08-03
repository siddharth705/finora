package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagingResponse;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 1 "capture facts" (docs/engineering/financial-document-intelligence-principles.md):
 * referenceNumber/balanceAfter must survive staging end to end -- modeled on a real Canara Bank
 * statement's "Reference / Cheque No." column, previously silently discarded even on rows that
 * otherwise parsed successfully.
 */
class ReferenceNumberAndBalancePdfPreviewGeneratorTest {

    private PdfPreviewGenerator realGenerator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggest(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(java.util.List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService, duplicateDetector);

        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(),
                new PdfMetadataExtractor(), transactionNormalizer, com.finora.imports.product.ProductDiscovery.standard());
    }

    private StagingResponse generate() throws Exception {
        byte[] bytes = PdfFixtureBuilder.buildReferenceNumberAndBalanceSample();
        return realGenerator().generate(UUID.randomUUID(), "canara_statement.pdf", bytes);
    }

    @Test
    void generate_capturesReferenceNumberForEveryRow_insteadOfSilentlyDroppingIt() throws Exception {
        StagingResponse response = generate();

        assertThat(response.rows()).hasSize(3);
        assertThat(response.rows()).extracting(r -> r.referenceNumber())
                .containsExactly("234567890123", "10203040506070", "345678901234");
    }

    @Test
    void generate_capturesBalanceAfterForEveryRow() throws Exception {
        StagingResponse response = generate();

        assertThat(response.rows()).extracting(r -> r.balanceAfter())
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(
                        new BigDecimal("49000.00"), new BigDecimal("50000.00"), new BigDecimal("49850.00"));
    }
}
