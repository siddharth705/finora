package com.finora.imports;

import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.ExpectedEntity;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Presence;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Row;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.ZeroTransactions;
import com.finora.imports.pdf.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OCR-2D: a document with no text says so, instead of blaming its own layout.
 *
 * <p>Before this, a scanned PDF and a genuinely empty one were indistinguishable to the user. Both
 * produced zero rows, zero recovered lines, and "Could not find a transaction table in this file" —
 * which sends someone looking for a problem with their statement's format when the actual answer is
 * that Finora cannot read images yet. The engine knew the difference with certainty and never said
 * it.
 *
 * <p>What this deliberately does NOT claim: that the file is a bank statement, that it is a scan
 * rather than a photograph or an image export, or that recognition would succeed on it. None of
 * those follow from an absence of text. The classification names the evidence.
 */
class ImageOnlyDocumentTest {

    private static SyntheticStatementDefinition declaration() {
        return new SyntheticStatementDefinition("image-only-001", List.of(
                new ExpectedEntity("savings", "SAVINGS", Presence.DETECTED, null,
                        ZeroTransactions.FALSE, List.of(
                                new Row(LocalDate.of(2026, 6, 5), "SALARY CREDIT",
                                        new BigDecimal("55000.00"), true)))),
                List.of());
    }

    @Test
    void aScannedDocumentReportsThatItIsAnImage_notThatItsLayoutIsUnknown() throws Exception {
        byte[] scanned = PdfFixtureBuilder.renderScanned(declaration());
        assertThat(new PdfTextExtractor().extract(scanned))
                .as("the fixture's premise")
                .isEmpty();

        DocumentContext ctx = new DocumentContext("PDF", "ImageOnlyDocumentTest");
        ctx.recordExtractedRuns(0);

        assertThat(catchApi(ctx))
                .extracting(ApiException::getCode)
                .isEqualTo(ErrorCode.IMPORT_SCANNED_OCR_REQUIRED);
    }

    /**
     * THE discrimination. A document with plenty of text and no readable table is a layout problem
     * and must keep saying so — a real SBI statement is exactly this: 66 lines of text recovered,
     * zero transactions. Calling that "an image" would be a worse answer than the one it replaces.
     */
    @Test
    void aTextBearingDocumentWithNoTableIsStillALayoutProblem() {
        DocumentContext ctx = new DocumentContext("PDF", "ImageOnlyDocumentTest");
        ctx.recordExtractedRuns(1408);

        assertThat(catchApi(ctx))
                .extracting(ApiException::getCode)
                .isEqualTo(ErrorCode.IMPORT_NO_HEADER_DETECTED);
    }

    /**
     * An unrecorded count means nobody looked, which must not present itself as "we looked and the
     * document is an image". Every caller predating this signal keeps its previous behaviour.
     */
    @Test
    void anUnrecordedCountIsNotTreatedAsAnImage() {
        DocumentContext ctx = new DocumentContext("PDF", "ImageOnlyDocumentTest");

        assertThat(ctx.hasNoExtractableText()).isFalse();
        assertThat(catchApi(ctx))
                .extracting(ApiException::getCode)
                .isEqualTo(ErrorCode.IMPORT_NO_HEADER_DETECTED);
    }

    @Test
    void theMessageStatesTheObservationAndTheLimitation_withoutClaimingTheDocumentsType() {
        DocumentContext ctx = new DocumentContext("PDF", "ImageOnlyDocumentTest");
        ctx.recordExtractedRuns(0);

        String message = catchApi(ctx).getMessage();

        assertThat(message).containsIgnoringCase("no text").containsIgnoringCase("image");
        // It must not assert what the engine cannot know from an absence of text.
        assertThat(message)
                .doesNotContainIgnoringCase("scanned")
                .doesNotContainIgnoringCase("OCR")
                .doesNotContainIgnoringCase("bank statement,");
    }

    @Test
    void aDocumentThatDidYieldRowsIsNeverRejected() {
        DocumentContext ctx = new DocumentContext("PDF", "ImageOnlyDocumentTest");
        ctx.recordExtractedRuns(0);

        // Zero runs but rows present is not a state the pipeline can reach; asserted anyway, because
        // the guard's first line is what makes that true and a reorder would silently break it.
        ExtractionCheck.rejectIfNothingWasExtracted(ExtractionCheckFixtures.withRows(), ctx);
    }

    private static ApiException catchApi(DocumentContext ctx) {
        return ExtractionCheckFixtures.catchRejection(ctx);
    }
}
