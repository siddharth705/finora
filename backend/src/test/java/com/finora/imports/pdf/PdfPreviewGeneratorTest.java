package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagingResponse;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.TransactionNormalizer;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Golden-fixture test against src/test/resources/pdf/sbi_sample_statement.pdf -- a synthetic
 * statement generated specifically for this milestone (there's no real bank PDF available to
 * test against in the environment this was built in), laid out as a real digital PDF with
 * genuine per-field text-drawing positions, not hand-written to match whatever the parser
 * happens to expect. The exact expected values below (account holder "JOHN DOE", 6 transaction
 * rows including the two balance-only opening/closing rows, etc.) match exactly what
 * generate_fixture.py drew onto that PDF -- see this test class's own values as the source of
 * truth for what the fixture contains, since this repo doesn't carry the fixture-generation
 * script itself, only the resulting PDF.
 *
 * IMPORTANT: written without the ability to compile or run it in the environment this was
 * built in (no JDK/Maven available there) -- reasoned through carefully against PDFBox's
 * documented API and the fixture's known exact content, but genuinely unverified by actually
 * running it. Treat a first run of this test, one way or the other, as real information: if it
 * fails, that's either a real bug in the extraction code or an incorrect assumption in this test
 * -- not a reason to assume the test itself must be right.
 */
class PdfPreviewGeneratorTest {

    private byte[] fixtureBytes() throws Exception {
        Path path = Path.of("src/test/resources/pdf/sbi_sample_statement.pdf");
        return Files.readAllBytes(path);
    }

    private PdfPreviewGenerator realGenerator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggest(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(java.util.List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService, duplicateDetector);

        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(),
                new PdfMetadataExtractor(), transactionNormalizer);
    }

    @Test
    void generate_extractsAllSixRowsFromTheGoldenFixture() throws Exception {
        StagingResponse response = realGenerator().generate(UUID.randomUUID(), "sbi_sample_statement.pdf", fixtureBytes());

        // OPENING BALANCE, 4 real transactions, CLOSING BALANCE -- all 6 rows have a parseable
        // date and amount (the balance column, for the two balance-only rows), so all 6 should
        // survive TransactionNormalizer.normalize() the same way a CSV opening/closing-balance
        // row would.
        assertThat(response.rows()).hasSize(6);
    }

    @Test
    void generate_correctlyDistinguishesDebitFromCredit_byColumnPosition() throws Exception {
        StagingResponse response = realGenerator().generate(UUID.randomUUID(), "sbi_sample_statement.pdf", fixtureBytes());

        // The whole point of position-aware extraction: SWIGGY (450.00) is a debit, SALARY
        // (75000.00) is a credit -- both are just plain numbers with no other distinguishing
        // feature in the extracted text, so getting this right depends entirely on
        // PdfTableLocator having correctly bucketed each amount into its actual column.
        var swiggy = response.rows().stream().filter(r -> r.description().contains("SWIGGY")).findFirst().orElseThrow();
        assertThat(swiggy.type()).isEqualTo("EXPENSE");
        assertThat(swiggy.amount()).isEqualByComparingTo("450.00");

        var salary = response.rows().stream().filter(r -> r.description().contains("SALARY")).findFirst().orElseThrow();
        assertThat(salary.type()).isEqualTo("INCOME");
        assertThat(salary.amount()).isEqualByComparingTo("75000.00");
    }

    @Test
    void generate_extractsAccountMetadataFromTheHeaderLines() throws Exception {
        StagingResponse response = realGenerator().generate(UUID.randomUUID(), "sbi_sample_statement.pdf", fixtureBytes());
        var detected = response.detectedAccount();

        assertThat(detected.accountHolderName()).isEqualTo("JOHN DOE");
        assertThat(detected.branchName()).isEqualTo("MG ROAD BRANCH");
        assertThat(detected.ifscCode()).isEqualTo("SBIN0001234");
        // Masked, not raw -- CsvParser.maskAccountNumber("000123456789") keeps the last 4 digits.
        assertThat(detected.accountNumberMasked()).endsWith("6789");
    }

    @Test
    void generate_derivesOpeningAndClosingBalanceFromTheBalanceOnlyRows() throws Exception {
        StagingResponse response = realGenerator().generate(UUID.randomUUID(), "sbi_sample_statement.pdf", fixtureBytes());
        var detected = response.detectedAccount();

        assertThat(detected.openingBalance()).isEqualByComparingTo("50000.00");
        assertThat(detected.closingBalance()).isEqualByComparingTo("117209.50");
    }

    @Test
    void generate_parsesTheStatementPeriodFromItsOwnHeaderLine() throws Exception {
        StagingResponse response = realGenerator().generate(UUID.randomUUID(), "sbi_sample_statement.pdf", fixtureBytes());
        var detected = response.detectedAccount();

        assertThat(detected.statementPeriodStart()).isEqualTo(java.time.LocalDate.of(2026, 7, 1));
        assertThat(detected.statementPeriodEnd()).isEqualTo(java.time.LocalDate.of(2026, 7, 31));
    }
}
