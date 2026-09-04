package com.finora.imports;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.dto.ImportDto.UnparseableRow;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;

import java.util.ArrayList;
import java.util.List;

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
     * The whole-document form: throws when NO section of a document staged a transaction.
     *
     * <p>P-002 Fix 1. Both callers used to ask this question only of documents that located a
     * single section, and both said so in a comment ("more than one detected section means the
     * engine plainly found something"). That reading is wrong on real statements: a credit-card
     * statement whose fee schedule and MITC paragraphs are each mistaken for a table header
     * produces eight located sections and not one transaction, and {@link
     * StagedAccountSectionFilter} passes every one of them through precisely because none has rows
     * -- it defers the verdict to this check. Gated on section count, this check never saw the
     * document, and the user was offered eight empty accounts to confirm. The number of sections
     * says how the page was cut up; it says nothing about whether anything was read.
     *
     * <p>Summed across sections rather than asked per section, deliberately: one empty section
     * inside a document that parsed elsewhere is a non-account (the filter's job, and it already
     * does it), whereas an empty document is a failed extraction (this check's job). The recovered
     * line count quoted in the message is whole-document for the same reason.
     *
     * <p>For a one-section document this is exactly the argument the single-section call site used
     * to build by hand, so that long-standing rejection keeps its code, its message and its count.
     */
    static void rejectIfNothingWasExtracted(List<StagedAccountSection> sections, DocumentContext ctx) {
        rejectIfNothingWasExtracted(wholeDocumentView(sections), ctx);
    }

    /**
     * Every section's rows and recovered text as one {@link StagingResponse}, built only to be read
     * by the check above and then dropped -- nothing receives it.
     *
     * <p>{@code detectedAccount} is null on purpose. A document's sections can have detected
     * different accounts, and choosing one of them here would be inventing an answer to a question
     * nobody asked; the check reads only {@code rows()} and the SIZE of {@code unparseableRows()}.
     */
    private static StagingResponse wholeDocumentView(List<StagedAccountSection> sections) {
        List<StagedRow> rows = new ArrayList<>();
        List<UnparseableRow> recovered = new ArrayList<>();
        int totalParsed = 0;
        int flaggedDuplicates = 0;
        for (StagedAccountSection section : sections) {
            if (section.rows() != null) rows.addAll(section.rows());
            if (section.unparseableRows() != null) recovered.addAll(section.unparseableRows());
            totalParsed += section.totalParsed();
            flaggedDuplicates += section.flaggedDuplicates();
        }
        return new StagingResponse(rows, totalParsed, flaggedDuplicates, null, recovered);
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

        // Checked before the generic locatedATable branch below, and for the same reason
        // hasNoExtractableText() is checked first: it is the most specific thing knowable. A
        // statement that states its own zero activity did not defeat extraction -- there was
        // nothing here to extract -- and IMPORT_007's "could not read any transactions" is simply
        // false about the cause. See ExplicitZeroActivityDetector's own doc comment for the
        // evidence and IMPORT_NO_ACTIVITY_IN_PERIOD's for why this is a separate code rather than
        // a reworded IMPORT_007.
        int recoveredLines = staged.unparseableRows() == null ? 0 : staged.unparseableRows().size();
        if (ctx != null && ctx.explicitZeroActivityDeclared()) {
            // Same recovered-lines suffix the generic branch below appends, and for the same
            // reason: a row declaring the statement's own zero activity does not mean every OTHER
            // row in this section parsed cleanly. A boilerplate/disclaimer row can still land in
            // unparseableRows() alongside it, and that diagnostic must not silently vanish just
            // because this branch's cause is different from IMPORT_007's.
            throw new ApiException(ErrorCode.IMPORT_NO_ACTIVITY_IN_PERIOD,
                    ErrorCode.IMPORT_NO_ACTIVITY_IN_PERIOD.defaultMessage()
                            + (recoveredLines > 0
                            ? " " + recoveredLines + " line(s) of text were recovered and recorded for review."
                            : ""));
        }

        boolean locatedATable = ctx != null && ctx.buildMetadata().tables() > 0;
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
