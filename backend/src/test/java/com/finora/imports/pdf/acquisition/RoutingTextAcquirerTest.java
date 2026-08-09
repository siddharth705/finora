package com.finora.imports.pdf.acquisition;

import com.finora.imports.pdf.PdfTextExtractor;
import com.finora.imports.pdf.PositionedText;
import com.finora.imports.pdf.TextSource;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.pdf.fixtures.ScannedPdfFixture;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The routing decision, tested without an OCR engine.
 *
 * <p>Deliberately so. No recogniser ships by default, so the configuration almost every environment
 * runs is the one with an empty recogniser list -- and a routing layer whose tests only pass with an
 * engine installed would leave that configuration unverified. The recognisers here are stand-ins
 * that return canned runs, which is enough to assert every branch of the decision. Whether a real
 * engine READS a document correctly is a different question, measured by the OCR-3A harness.
 */
class RoutingTextAcquirerTest {

    private static NativePdfAcquirer nativeAcquirer() {
        return new NativePdfAcquirer(new PdfTextExtractor());
    }

    /** A recogniser that returns one run and counts how often it was asked. */
    private static class CountingRecogniser implements RecognisingTextAcquirer {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public AcquiredDocument acquire(byte[] fileBytes, String password) {
            calls.incrementAndGet();
            return AcquiredDocument.of(List.of(new PositionedText("RECOGNISED", 50f, 100f, 0,
                    60f, 8f, 0.9f, TextSource.OCR)));
        }

        @Override
        public boolean supports(byte[] fileBytes) {
            return true;
        }
    }

    /**
     * THE safety property: a document with a text layer never reaches a recogniser.
     *
     * <p>Asserted on the recogniser never being CALLED rather than on the output being native.
     * Identical output would also be produced by a recogniser that happened to agree, and the claim
     * being made is stronger than agreement -- it is that no statement which works today can change
     * behaviour because routing exists.
     */
    @Test
    void aDocumentWithATextLayerIsNeverHandedToARecogniser() throws Exception {
        var recogniser = new CountingRecogniser();
        var routing = new RoutingTextAcquirer(nativeAcquirer(), List.of(recogniser));

        var acquired = routing.acquire(PdfFixtureBuilder.buildReferenceNumberAndBalanceSample(), null);

        assertThat(recogniser.calls.get()).as("the recogniser must not even be consulted").isZero();
        assertThat(acquired.source()).isEqualTo(TextSource.NATIVE_PDF);
        assertThat(acquired.runs()).isNotEmpty();
    }

    /** And the same document read with no recogniser deployed gives the identical result. */
    @Test
    void routingChangesNothingWhenThereIsTextToRead() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildReferenceNumberAndBalanceSample();

        var withEngine = new RoutingTextAcquirer(nativeAcquirer(), List.of(new CountingRecogniser()));
        var without = new RoutingTextAcquirer(nativeAcquirer(), List.of());
        var direct = nativeAcquirer();

        assertThat(text(withEngine.acquire(pdf, null)))
                .isEqualTo(text(without.acquire(pdf, null)))
                .isEqualTo(text(direct.acquire(pdf, null)));
    }

    /** A document with no text layer is what a recogniser is for. */
    @Test
    void aDocumentWithNoTextLayerIsHandedToTheRecogniser() throws Exception {
        var recogniser = new CountingRecogniser();
        var routing = new RoutingTextAcquirer(nativeAcquirer(), List.of(recogniser));
        byte[] scanned = ScannedPdfFixture.scan(PdfFixtureBuilder.buildReferenceNumberAndBalanceSample());

        var acquired = routing.acquire(scanned, null);

        assertThat(recogniser.calls.get()).isEqualTo(1);
        assertThat(acquired.source()).isEqualTo(TextSource.OCR);
        assertThat(text(acquired)).contains("RECOGNISED");
    }

    /**
     * With no recogniser deployed -- the default -- a scanned document still yields nothing, which
     * is what {@code ExtractionCheck} turns into IMPORT_SCANNED_OCR_REQUIRED.
     *
     * <p>The absence of an engine must produce the message the product already has, not a new
     * failure mode. This is the configuration production runs today.
     */
    @Test
    void withNoRecogniserDeployedAScannedDocumentStillYieldsNothing() throws Exception {
        var routing = new RoutingTextAcquirer(nativeAcquirer(), List.of());
        byte[] scanned = ScannedPdfFixture.scan(PdfFixtureBuilder.buildReferenceNumberAndBalanceSample());

        assertThat(routing.acquire(scanned, null).runs()).isEmpty();
    }

    /**
     * A recogniser that throws must fail like one that is absent, not like a bug.
     *
     * <p>An engine is an operational dependency: it can be missing, misconfigured, or out of memory
     * on a large scan. None of that should reach the user as anything other than the existing "this
     * PDF has no text in it" -- and the next recogniser still gets its turn.
     */
    @Test
    void aRecogniserThatFailsDoesNotFailTheImportDifferently() throws Exception {
        RecognisingTextAcquirer broken = new RecognisingTextAcquirer() {
            @Override public AcquiredDocument acquire(byte[] b, String p) throws IOException {
                throw new IOException("engine not installed");
            }
            @Override public boolean supports(byte[] b) { return true; }
        };
        var working = new CountingRecogniser();
        byte[] scanned = ScannedPdfFixture.scan(PdfFixtureBuilder.buildReferenceNumberAndBalanceSample());

        assertThat(new RoutingTextAcquirer(nativeAcquirer(), List.of(broken)).acquire(scanned, null).runs())
                .as("a failing engine leaves the empty native result")
                .isEmpty();
        assertThat(text(new RoutingTextAcquirer(nativeAcquirer(), List.of(broken, working))
                .acquire(scanned, null)))
                .as("and does not consume the turn of one that works")
                .contains("RECOGNISED");
    }

    /** A recogniser that cannot attempt these bytes is skipped rather than asked. */
    @Test
    void aRecogniserThatDoesNotSupportTheBytesIsSkipped() throws Exception {
        var declining = new CountingRecogniser() {
            @Override public boolean supports(byte[] fileBytes) { return false; }
        };
        byte[] scanned = ScannedPdfFixture.scan(PdfFixtureBuilder.buildReferenceNumberAndBalanceSample());

        var acquired = new RoutingTextAcquirer(nativeAcquirer(), List.of(declining)).acquire(scanned, null);

        assertThat(declining.calls.get()).isZero();
        assertThat(acquired.runs()).isEmpty();
    }

    /** A recogniser that returns nothing is not treated as having succeeded. */
    @Test
    void aRecogniserThatFindsNothingLetsTheNextOneTry() throws Exception {
        RecognisingTextAcquirer silent = new RecognisingTextAcquirer() {
            @Override public AcquiredDocument acquire(byte[] b, String p) { return AcquiredDocument.of(List.of()); }
            @Override public boolean supports(byte[] b) { return true; }
        };
        var working = new CountingRecogniser();
        byte[] scanned = ScannedPdfFixture.scan(PdfFixtureBuilder.buildReferenceNumberAndBalanceSample());

        assertThat(text(new RoutingTextAcquirer(nativeAcquirer(), List.of(silent, working))
                .acquire(scanned, null))).contains("RECOGNISED");
    }

    private static String text(AcquiredDocument acquired) {
        return acquired.runs().stream().map(PositionedText::text).reduce("", (a, b) -> a + "|" + b);
    }
}
