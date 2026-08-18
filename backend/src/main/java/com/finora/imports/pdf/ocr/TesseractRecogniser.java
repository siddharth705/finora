package com.finora.imports.pdf.ocr;

import com.finora.imports.pdf.acquisition.AcquiredDocument;
import com.finora.imports.pdf.acquisition.RecognisingTextAcquirer;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Tesseract as a routable acquirer: recognise, assemble, hand back runs.
 *
 * <h2>Why this is a production component now</h2>
 *
 * OCR-3A measured Tesseract 5.5.3 through the real parser and OCR-3B closed the run-segmentation
 * gap that measurement found (see {@code docs/engineering/import/ocr-engine-evaluation.md}) — ten
 * of ten synthetic statement layouts reach the same ledger through OCR as through native
 * extraction at 300 DPI. That evidence, plus a deliberate deployment decision to install the
 * binary in the production image, is what justifies registering this as a {@code @Component}
 * rather than leaving it under {@code src/test} as OCR-4 originally did.
 *
 * <h2>Unconditional registration is still safe</h2>
 *
 * This bean is registered whether or not the {@code tesseract} binary is actually present on the
 * running machine. That is deliberate, not an oversight: {@link #supports} reports the binary's
 * absence honestly via {@link TesseractEngine#available()}, and {@code RoutingTextAcquirer} skips
 * any acquirer whose {@code supports()} is false. A deployment that somehow lacks the binary
 * degrades to exactly today's behaviour -- {@code IMPORT_SCANNED_OCR_REQUIRED} -- rather than
 * failing to start or throwing partway through an import. No {@code @ConditionalOnProperty} is
 * needed on top of a check the class already makes for itself.
 *
 * <p>{@code RoutingTextAcquirer}'s own tests still run with no recogniser at all -- that
 * configuration is not abandoned, it is simply no longer the one the running application ships in.
 */
@Component
public final class TesseractRecogniser implements RecognisingTextAcquirer {

    /**
     * The resolution a production acquirer rasterises at.
     *
     * <p>Not a preference -- OCR-3B swept ledger equivalence across ten statement layouts at both
     * 150 and 300 DPI and found 150 DPI never exceeds seven of ten at any run-assembly threshold,
     * while 300 DPI reaches nine or ten across a broad band. This is that measured number, kept on
     * the class that actually rasterises in production rather than duplicated on the evaluation
     * harness that measured it.
     */
    public static final int OCR_DPI = 300;

    private final TesseractEngine engine = new TesseractEngine();

    @Override
    public AcquiredDocument acquire(byte[] fileBytes, String password) throws IOException {
        var recognised = RunAssembler.assemble(engine.recognise(fileBytes, OCR_DPI));
        return AcquiredDocument.of(RecognisedTextAdapter.toPositionedText(recognised));
    }

    /**
     * Whether the engine is installed at all.
     *
     * <p>Capability, honestly reported: an engine that is not present cannot attempt anything, and
     * saying so here means routing skips it rather than catching an exception from it. The password
     * is not consulted because Tesseract reads pixels -- an encrypted PDF that PDFBox could not open
     * will not have rendered, and that failure belongs to rendering.
     */
    @Override
    public boolean supports(byte[] fileBytes) {
        return TesseractEngine.available();
    }
}
