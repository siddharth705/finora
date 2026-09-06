package com.finora.imports;

import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExtractionCheck} in isolation -- same package-private direct-call pattern as
 * {@code ImageOnlyDocumentTest}, one level below {@link ExplicitZeroActivityPdfPreviewGeneratorIT},
 * which proves a real fixture's bytes actually set the flag this test hand-sets.
 *
 * <p>The discrimination this exists to prove: two documents that both stage zero rows and both
 * locate a table must not collapse into the same code. {@code IMPORT_007} means the table was
 * found and every row in it was rejected -- a real extraction failure. This is the other one: the
 * statement's own printed summary already said there was nothing to find.
 */
class ExplicitZeroActivityRejectionTest {

    private static DocumentContext ctxWithTable(boolean explicitZero) {
        DocumentContext ctx = new DocumentContext("PDF", "ExplicitZeroActivityRejectionTest");
        ctx.recordTable();
        if (explicitZero) ctx.recordExplicitZeroActivityDeclared();
        return ctx;
    }

    @Test
    void aDeclaredZeroActivityDocumentIsRejectedWithItsOwnCode_notNoTransactionsFound() {
        DocumentContext ctx = ctxWithTable(true);

        assertThat(catchApi(ctx)).extracting(ApiException::getCode)
                .isEqualTo(ErrorCode.IMPORT_NO_ACTIVITY_IN_PERIOD);
    }

    @Test
    void anUndeclaredZeroRowsDocumentStillGetsTheOrdinaryNoTransactionsFoundCode() {
        DocumentContext ctx = ctxWithTable(false);

        assertThat(catchApi(ctx)).extracting(ApiException::getCode)
                .isEqualTo(ErrorCode.IMPORT_NO_TRANSACTIONS_FOUND);
    }

    /** Checked before the generic locatedATable branch -- even a document where NO table was
     *  located at all must defer to the explicit-zero signal if somehow both were true, since it is
     *  the more specific, more certain fact. Not reachable from a real parse today (the detector
     *  only ever sees rows from a located section), but the ORDER of these checks is the contract
     *  under test, not just their outcome individually. */
    @Test
    void explicitZeroActivityWinsOverNoTableLocatedToo() {
        DocumentContext ctx = new DocumentContext("PDF", "ExplicitZeroActivityRejectionTest");
        ctx.recordExplicitZeroActivityDeclared();

        assertThat(catchApi(ctx)).extracting(ApiException::getCode)
                .isEqualTo(ErrorCode.IMPORT_NO_ACTIVITY_IN_PERIOD);
    }

    @Test
    void theMessageStatesTheStatementsOwnClaim_notThatFinoraCouldNotReadIt() {
        String message = catchApi(ctxWithTable(true)).getMessage();

        assertThat(message).doesNotContainIgnoringCase("could not read");
        assertThat(message).containsIgnoringCase("no transactions");
    }

    @Test
    void aDocumentThatDidStageRowsIsNeverRejectedEvenWithTheFlagSet() {
        DocumentContext ctx = ctxWithTable(true);

        ExtractionCheck.rejectIfNothingWasExtracted(ExtractionCheckFixtures.withRows(), ctx);
    }

    private static ApiException catchApi(DocumentContext ctx) {
        return ExtractionCheckFixtures.catchRejection(ctx);
    }
}
