package com.finora.imports.evidence;

import com.finora.imports.pdf.TextSource;

/**
 * One step in the pipeline a piece of evidence is downstream of -- design §1.2's evidence
 * dependency graph, made concrete as a small, closed, tagged hierarchy rather than a graph library.
 * Reasoning about a chain of these <em>as</em> a graph is sufficient; no traversal beyond "do two
 * chains share a node" (see {@link EvidenceAssessor#shareAnUpstreamFailureMode}) is ever required.
 *
 * <p>Two pieces of evidence sharing a node were both produced through the identical pipeline step,
 * right or wrong -- which is exactly what makes their later agreement NOT independent corroboration.
 * This is the mechanism that answers "evidence dimensions must not be counted as independent when
 * they share a material upstream failure mode" directly, rather than by an enumerated table of safe
 * and unsafe pairings that could drift out of sync with itself.
 */
public sealed interface ProvenanceNode {

    /** One acquisition engine's pass over the whole document. Two facts sharing an {@code Acquisition}
     *  node came from the identical extraction run -- the same engine, the same invocation. */
    record Acquisition(TextSource source) implements ProvenanceNode {}

    /** The step that decided which located section a run belongs to. This is the ICICI failure
     *  point this whole model exists to make detectable: two facts sharing a
     *  {@code SectionAttribution} node were both placed by the same section-boundary decision,
     *  right or wrong, regardless of which acquisition source fed it. */
    record SectionAttribution(int sectionIndex, TextSource fromSource) implements ProvenanceNode {}

    /** The column-boundary/layout interpretation a structured (row/column) fact was reconstructed
     *  against -- which x-coordinate ranges were decided to mean "this is the amount column," etc.
     *  Distinct from {@link Acquisition} on purpose: two facts can come from genuinely different
     *  acquisition engines (one native, one OCR) and still share this node, because an OCR pass
     *  very plausibly reuses the native page's own coordinate frame to make sense of where a
     *  recognised word sits at all. That is exactly the "PDFBox and Tesseract both agree on
     *  55,000 DEBIT when the real value is 55,000 CREDIT because a column was misidentified"
     *  scenario, made a trackable dependency instead of an unmodeled risk.
     *
     *  @param columnBoundarySignature a stable identifier for the specific set of column-anchor
     *         x-coordinates used -- two facts reconstructed against the same anchors carry the
     *         same signature regardless of which acquisition source produced the underlying text. */
    record ColumnLayoutInterpretation(int sectionIndex, String columnBoundarySignature)
            implements ProvenanceNode {}
}
