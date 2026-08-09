package com.finora.imports;

import com.finora.dto.ImportDto.StagingResponse;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;

/**
 * The one rule that decides whether the engine got anything usable out of a document.
 *
 * <p>Extracted from {@code ImportService} when admin analysis needed it too. Left where it was, the
 * two paths would have disagreed about the same file: a document yielding no transactions is
 * {@code IMPORT_001} for a customer, and the admin tool — which calls the preview generator
 * directly, below the layer that threw — would have recorded it as PARSED with zero rows. An
 * analysis workbench whose verdict differs from what the customer actually got is worse than no
 * workbench, because the engineer investigating a complaint would be looking at a different
 * outcome than the one being complained about.
 */
final class ExtractionCheck {

    private ExtractionCheck() {
    }

    /**
     * Throws when a document produced no transactions at all.
     *
     * <p>Distinguishes the two ways that happens, because they need different fixes: no table
     * found anywhere ({@code IMPORT_NO_HEADER_DETECTED}) is a layout the engine does not
     * recognise, whereas a table located but unreadable ({@code IMPORT_NO_TRANSACTIONS_FOUND}) is
     * a layout it recognises and cannot parse.
     */
    static void rejectIfNothingWasExtracted(StagingResponse staged, DocumentContext ctx) {
        if (!staged.rows().isEmpty()) return;

        // Checked first, because it is the most specific thing knowable and the only one of the
        // three that is certain. A document with no extractable text is not a layout the engine
        // failed to recognise -- there was nothing to lay out. Told it "could not find a
        // transaction table", a user looks for a problem with their statement's format; the actual
        // answer is that Finora cannot read images yet, which is ours and not theirs.
        //
        // Before this, a scanned PDF and a genuinely empty one were indistinguishable to the user:
        // both produced zero rows, zero recovered lines and the same message. The one fact the
        // engine knew for certain never reached them.
        //
        // The message states the observation and the limitation, and claims neither that the file
        // is a bank statement nor that recognition would succeed on it. Those are not established
        // by an absence of text.
        if (ctx != null && ctx.hasNoExtractableText()) {
            throw new ApiException(ErrorCode.IMPORT_SCANNED_OCR_REQUIRED,
                    "This PDF has no text in it -- every page is an image, so there was nothing for "
                            + "Finora to read. Statements exported directly from your bank's website "
                            + "or app usually contain text and import correctly.");
        }

        boolean locatedATable = ctx != null && ctx.buildMetadata().tables() > 0;
        int recoveredLines = staged.unparseableRows() == null ? 0 : staged.unparseableRows().size();
        throw new ApiException(
                locatedATable ? ErrorCode.IMPORT_NO_TRANSACTIONS_FOUND : ErrorCode.IMPORT_NO_HEADER_DETECTED,
                (locatedATable
                        ? "Finora found a transaction table in this statement but could not read any transactions from it."
                        : "Finora could not find a transaction table anywhere in this statement.")
                        + (recoveredLines > 0
                        ? " " + recoveredLines + " line(s) of text were recovered and recorded for review."
                        : ""));
    }
}
