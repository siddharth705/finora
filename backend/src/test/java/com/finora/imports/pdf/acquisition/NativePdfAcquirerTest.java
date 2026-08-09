package com.finora.imports.pdf.acquisition;

import com.finora.imports.pdf.PdfTextExtractor;
import com.finora.imports.pdf.PositionedText;
import com.finora.imports.pdf.TextSource;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OCR-1: the seam recognition will plug into, and the guarantee that adding it changed nothing.
 *
 * <p>The point of this milestone is a boundary, not a feature. Everything downstream of acquisition
 * must remain unable to tell whether characters were read from a text layer or recognised from
 * pixels — which is only worth anything if introducing the boundary left the existing path exactly
 * as it was. These tests assert that first and the new capability second.
 */
class NativePdfAcquirerTest {

    private final NativePdfAcquirer acquirer = new NativePdfAcquirer(new PdfTextExtractor());

    @Test
    void readsTheSameRunsTheExtractorAlwaysDid() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildWrappedHeaderDepositScheduleSample();

        List<PositionedText> direct = new PdfTextExtractor().extract(pdf);
        AcquiredDocument acquired = acquirer.acquire(pdf, null);

        assertThat(acquired.runs())
                .as("acquisition adapts the extractor; it must not reinterpret it")
                .isEqualTo(direct);
    }

    @Test
    void stampsNativeProvenanceAndClaimsNoConfidence() throws Exception {
        AcquiredDocument acquired = acquirer.acquire(
                PdfFixtureBuilder.buildWrappedHeaderDepositScheduleSample(), null);

        assertThat(acquired.source()).isEqualTo(TextSource.NATIVE_PDF);
        assertThat(acquired.runs()).isNotEmpty();
        assertThat(acquired.runs()).allSatisfy(run -> {
            assertThat(run.source()).isEqualTo(TextSource.NATIVE_PDF);
            // Null, not 1.0. Native extraction does not estimate -- it reads what the file states,
            // and a confidence of 1.0 would be a claim about correctness rather than a report of
            // certainty. "Not applicable" and "certainly right" are different assertions.
            assertThat(run.confidence())
                    .as("nothing was inferred, so there is no estimate to report")
                    .isNull();
            assertThat(run.isRecognised()).isFalse();
        });
        assertThat(acquired.recognisedRuns()).isEmpty();
    }

    @Test
    void supportsIsAboutTheContainer_notTheQualityOfWhatIsInside() {
        assertThat(acquirer.supports("%PDF-1.4\nanything".getBytes())).isTrue();
        assertThat(acquirer.supports("this is not a pdf".getBytes())).isFalse();
        assertThat(acquirer.supports(new byte[0])).isFalse();
        assertThat(acquirer.supports(null)).isFalse();
    }

    /**
     * A document's own source is derived from its runs rather than declared, so it cannot drift
     * from what they say. The mixed case is modelled from the outset because it is real: a cover
     * page with a text layer above a scanned transaction table is exactly this, and it is why
     * acquisition is not a document-wide either/or.
     */
    @Test
    void aDocumentMixingBothSourcesReportsItself() {
        PositionedText read = new PositionedText("Txn Date", 50f, 100f, 0, 33f);
        PositionedText recognised = new PositionedText("1,250.00", 300f, 120f, 0, 38f,
                9f, 0.91f, TextSource.OCR);

        assertThat(AcquiredDocument.of(List.of(read)).source()).isEqualTo(TextSource.NATIVE_PDF);
        assertThat(AcquiredDocument.of(List.of(recognised)).source()).isEqualTo(TextSource.OCR);
        assertThat(AcquiredDocument.of(List.of(read, recognised)).source())
                .as("one document, two origins -- and it must say so rather than pick one")
                .isEqualTo(TextSource.NATIVE_PLUS_OCR);
        assertThat(AcquiredDocument.of(List.of(read, recognised)).recognisedRuns())
                .containsExactly(recognised);
    }

    /**
     * The provenance invariant, pinned: a document's source is DERIVED from its runs and cannot be
     * declared to be anything else.
     *
     * <p>Deriving it inside a factory would still leave the canonical constructor able to assert a
     * document was natively read when its runs say otherwise. A wrong provenance is worse than
     * none: reconciliation would trust characters that had in fact been inferred. So the
     * constructor refuses, rather than silently correcting -- quietly overwriting the argument
     * would leave a caller's mistaken belief invisible to them.
     */
    @Test
    void aCallerCannotDeclareAProvenanceTheRunsContradict() {
        PositionedText read = new PositionedText("Txn Date", 50f, 100f, 0, 33f);
        PositionedText recognised = new PositionedText("1,250.00", 300f, 120f, 0, 38f,
                9f, 0.91f, TextSource.OCR);

        assertThatThrownBy(() -> new AcquiredDocument(List.of(recognised), TextSource.NATIVE_PDF))
                .as("recognised characters must never be recordable as natively read")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("derived from the runs");

        assertThatThrownBy(() -> new AcquiredDocument(List.of(read, recognised), TextSource.OCR))
                .as("a mixed document must not be flattened to either one")
                .isInstanceOf(IllegalArgumentException.class);

        // And the truthful declarations are accepted, so the guard is about correctness rather
        // than about forbidding the constructor.
        assertThat(new AcquiredDocument(List.of(read), TextSource.NATIVE_PDF).source())
                .isEqualTo(TextSource.NATIVE_PDF);
        assertThat(new AcquiredDocument(List.of(read, recognised), TextSource.NATIVE_PLUS_OCR).source())
                .isEqualTo(TextSource.NATIVE_PLUS_OCR);
    }

    /** An empty document has nothing recognised in it, so it cannot be mixed. */
    @Test
    void anEmptyDocumentDoesNotInventAMixedOrigin() {
        assertThat(AcquiredDocument.of(List.of()).source()).isEqualTo(TextSource.NATIVE_PDF);
    }

    /**
     * Every construction path that predates provenance still produces exactly what it used to.
     * Thirty-seven call sites across the pipeline and its fixtures rely on this, and a default
     * that silently changed one of them would alter parsing for reasons nothing in this milestone
     * intends.
     */
    @Test
    void theConstructorsThatPredateProvenanceAreUnchanged() {
        PositionedText widthless = new PositionedText("Balance", 430f, 100f, 0);
        PositionedText measured = new PositionedText("Balance", 430f, 100f, 0, 62f);

        assertThat(widthless.width()).isZero();
        assertThat(widthless.endX()).isEqualTo(widthless.x());
        assertThat(measured.endX()).isEqualTo(492f);

        assertThat(List.of(widthless, measured)).allSatisfy(run -> {
            assertThat(run.source()).isEqualTo(TextSource.NATIVE_PDF);
            assertThat(run.confidence()).isNull();
            assertThat(run.height()).isZero();
            assertThat(run.endY()).isEqualTo(run.y());
        });
    }
}
