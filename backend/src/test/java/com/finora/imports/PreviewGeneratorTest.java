package com.finora.imports;

import com.finora.dto.ImportDto.StagingResponse;
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
 * "Never lose information" (see docs/engineering/financial-document-intelligence-principles.md)
 * at the whole-file level: a CSV whose header row is never recognized used to come back with an
 * empty unparseableRows list, indistinguishable from a genuinely blank upload.
 */
class PreviewGeneratorTest {

    private PreviewGenerator realGenerator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggest(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService, duplicateDetector);
        return new PreviewGenerator(new CsvParser(), transactionNormalizer, new StatementValidator(com.finora.imports.product.ProductDiscovery.standard()), new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(), new com.finora.imports.StatementTotalsValidator()));
    }

    @Test
    void generate_surfacesEveryNonBlankLine_whenNoHeaderRowIsRecognizedAnywhere() throws Exception {
        String csv = "Some Institution\nThis file uses a layout the engine does not recognize\nfoo,bar,baz\n";
        StagingResponse response = realGenerator().generate(UUID.randomUUID(), "unrecognized.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(response.rows()).isEmpty();
        assertThat(response.unparseableRows()).hasSize(3);
        assertThat(response.unparseableRows()).allMatch(
                r -> r.reason().equals("No column header row was recognized anywhere in this file"));
        assertThat(response.unparseableRows()).anyMatch(r -> "Some Institution".equals(r.raw().get("column 1")));
    }
}
