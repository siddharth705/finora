package com.finora.imports.pdf.acquisition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * Decides how a document's text is obtained: read it, and recognise it only when there is nothing
 * to read.
 *
 * <h2>The rule, and why it is this one</h2>
 *
 * <pre>
 *   native extraction always runs first
 *   any runs at all      -> return them, untouched
 *   zero runs            -> hand the bytes to a recogniser, if one is deployed
 *   zero runs, no engine -> return the empty result and let the existing error explain it
 * </pre>
 *
 * <p><b>Zero, not a threshold.</b> The obvious design routes on "the text layer looks poor", and
 * there is no evidence for what poor means. Measured across the real corpus, character density says
 * nothing useful: 993 characters per page yields 58 transaction rows, while 1545 and 1799 per page
 * yield none. A density cutoff would be a guess wearing an authoritative number. The one signal
 * that has been measured is total absence -- {@code DocumentContext.hasNoExtractableText} -- and
 * that is the only one acted on here.
 *
 * <p><b>Native-first is a safety property, not a preference.</b> Because a document with even one
 * native run returns before any recogniser is consulted, no statement that works today can change
 * behaviour because of this class. That is structural rather than asserted: there is no path
 * through {@link #acquire} that reaches a recogniser while native text exists.
 *
 * <h2>What this deliberately does not do</h2>
 *
 * No per-page routing and no {@code NATIVE_PLUS_OCR}. A cover page with a text layer above a
 * scanned table is a real shape -- {@link AcquiredDocument} already models it -- but choosing to
 * recognise part of a document needs a measurement of which parts are missing, and that measurement
 * does not exist. Building it on a guess would produce a document whose provenance is confident and
 * wrong, which is worse than one that is simply native.
 *
 * <p>No confidence thresholds. An engine's confidence is recorded, never acted on: OCR-3A measured
 * Tesseract reporting ~0.96 on a row whose value the pipeline then got wrong, so confidence has
 * been shown NOT to predict financial correctness on this pipeline. Reconciliation and the
 * deterministic validators remain the things that decide whether a figure is trustworthy.
 */
@Component
@Primary
public class RoutingTextAcquirer implements DocumentTextAcquirer {

    private static final Logger log = LoggerFactory.getLogger(RoutingTextAcquirer.class);

    private final NativePdfAcquirer nativeAcquirer;
    private final List<RecognisingTextAcquirer> recognisers;

    /**
     * @param recognisers every deployed recogniser, in Spring's order. Normally EMPTY: no
     *                    recogniser ships by default, because an OCR engine is an operational
     *                    dependency of the deployment rather than a library, and a routing layer
     *                    that only works once one is installed would be untestable in the
     *                    configuration almost every environment actually runs.
     */
    public RoutingTextAcquirer(NativePdfAcquirer nativeAcquirer,
                               List<RecognisingTextAcquirer> recognisers) {
        this.nativeAcquirer = nativeAcquirer;
        this.recognisers = List.copyOf(recognisers);
    }

    @Override
    public AcquiredDocument acquire(byte[] fileBytes, String password) throws IOException {
        AcquiredDocument read = nativeAcquirer.acquire(fileBytes, password);
        if (!read.runs().isEmpty()) {
            return read;
        }

        for (RecognisingTextAcquirer recogniser : recognisers) {
            if (!recogniser.supports(fileBytes)) continue;
            try {
                AcquiredDocument recognised = recogniser.acquire(fileBytes, password);
                if (!recognised.runs().isEmpty()) {
                    return recognised;
                }
            } catch (IOException | RuntimeException e) {
                // A recogniser that fails must not fail the import differently from one that is
                // absent. Falling through leaves the empty native result, so the user gets the
                // existing "this PDF has no text in it" message rather than a stack trace about an
                // engine they have never heard of -- and the next recogniser still gets a turn.
                log.warn("recogniser {} could not read this document; continuing without it",
                        recogniser.getClass().getSimpleName(), e);
            }
        }

        // Deliberately the native result rather than an empty literal: it is what the document
        // actually yielded, and ExtractionCheck already turns it into IMPORT_SCANNED_OCR_REQUIRED.
        return read;
    }

    /** Whatever native extraction can attempt, since it is always the first thing tried. */
    @Override
    public boolean supports(byte[] fileBytes) {
        return nativeAcquirer.supports(fileBytes);
    }
}
