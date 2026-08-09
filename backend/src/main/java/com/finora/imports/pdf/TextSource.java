package com.finora.imports.pdf;

/**
 * How a text run was acquired from a document.
 *
 * <p>Carried on every {@link PositionedText} so that a fact derived from a statement can always
 * answer "where did this come from". The distinction matters because the two mechanisms fail in
 * completely different ways: native extraction reads glyphs the file already contains and is exact
 * or absent, while recognition infers glyphs from pixels and can be confidently wrong. A pipeline
 * that cannot tell them apart cannot weigh them differently.
 *
 * <p><b>This is provenance, not authority.</b> Neither value entitles anything downstream to treat
 * a number as correct. Acquisition supplies evidence; deterministic reconciliation and validation
 * decide whether that evidence is sufficient — the same separation the verification rules already
 * observe, where a parse being self-consistent is not a claim that it is right.
 */
public enum TextSource {

    /** Glyphs read from the document's own text layer. Exact when present: nothing was inferred. */
    NATIVE_PDF,

    /** Characters recognised from an image. Inferred, and therefore able to be wrong in ways
     *  native extraction cannot be — a misread digit looks exactly like a correct one. */
    OCR,

    /**
     * A document assembled from both, because native extraction covered some of it and recognition
     * was needed for the rest.
     *
     * <p>Exists from the outset deliberately. Real statements are not uniformly one or the other:
     * a cover page carries native text while the transaction table is a scan, or an appended
     * certificate is image-only. Modelling acquisition as a document-wide either/or would make
     * "OCR only the part that needs it" unrepresentable, and force the expensive, less accurate
     * choice of recognising an entire file because one region of it was unreadable.
     *
     * <p>Only ever describes a DOCUMENT. An individual run always has one origin.
     */
    NATIVE_PLUS_OCR
}
