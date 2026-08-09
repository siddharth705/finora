package com.finora.imports.pdf.acquisition;

import java.io.IOException;

/**
 * Gets text out of a document. One implementation reads a PDF's own text layer; another will
 * recognise characters from page images.
 *
 * <h2>Acquisition is not a financial decision</h2>
 *
 * An acquirer reports what it found and how sure it is. It has no authority to declare a figure
 * correct, and nothing downstream should grant it any. A recogniser that is 96% confident about
 * the characters "40,000.00" has said nothing about whether that number is a credit, whether it
 * belongs to this account, or whether it belongs in this row -- those are questions for
 * reconciliation and the deterministic validators, which is where the existing verification rules
 * already sit.
 *
 * <p>This interface exists before any recogniser does, and that is deliberate. Choosing an engine
 * first couples the financial pipeline to whichever one was easiest to integrate; defining the
 * seam first means engines can be measured against the same ground truth and swapped on evidence.
 *
 * <h2>Choosing between acquirers</h2>
 *
 * Not here. An implementation reports what it can do and what it found; which one runs is
 * {@link RoutingTextAcquirer}'s decision, made in one place so that it can be read and tested as
 * one thing.
 *
 * <p>That routing waited for evidence, and the evidence is narrow. Character density is known NOT
 * to be a signal -- across the corpus, 993 chars/page yields 58 transaction rows while 1545 and
 * 1799 chars/page yield none -- so no "this text layer looks poor" threshold exists or should be
 * invented. The only measured trigger is a document with no text at all.
 */
public interface DocumentTextAcquirer {

    /**
     * @param password the document open password, or null when none was supplied
     * @return the document's text with its provenance; never null
     */
    AcquiredDocument acquire(byte[] fileBytes, String password) throws IOException;

    /** Whether this acquirer can attempt the given bytes at all. Deliberately not "should it" --
     *  capability is a property of the acquirer, whereas choosing between two capable acquirers is
     *  a routing decision this package does not make. */
    boolean supports(byte[] fileBytes);
}
