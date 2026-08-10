/**
 * ADR-006's evidence-decision-and-reconciliation model (field-level evidence, same-fact
 * correlation, cross-source comparison, and dimension-combined assessment).
 *
 * <p><b>Two citation forms appear throughout this package's javadoc, and they are not
 * interchangeable:</b>
 * <ul>
 *   <li>{@code ADR-006 §N} (bare number, or {@code §4a}/{@code §4b}/{@code §5a}) cites the
 *       committed, authoritative document: {@code docs/architecture/adr-006-evidence-decision-reconciliation.md}.</li>
 *   <li>{@code design §N.M} (decimal-numbered) cites this package's own detailed-design working
 *       notes -- the finer-grained breakdown (§1.1 field-candidate shape, §1.2 provenance nodes,
 *       §2.x correlation, §3.x dimension assessment, etc.) produced and adversarially reviewed
 *       while implementing this ADR, but not itself a committed repository document. Comments must
 *       not say "ADR-006 §1.2" -- the committed ADR has no decimal subsections at all -- since that
 *       misattributes which document fixed a given detail.</li>
 * </ul>
 *
 * <p>See {@link com.finora.imports.evidence.FieldCandidate} and
 * {@link com.finora.imports.evidence.FieldAssessment}'s own javadoc for a related, easy-to-miss
 * distinction: the committed ADR's own prose uses "FieldCandidate" to mean the combined
 * value-plus-three-dimensions-plus-decision type. In this implementation, that combined concept is
 * {@code FieldAssessment}; {@code FieldCandidate} itself is the narrower, fact-grain-only type it
 * wraps. See the implementation note at the top of the ADR document for the full explanation.
 */
package com.finora.imports.evidence;
