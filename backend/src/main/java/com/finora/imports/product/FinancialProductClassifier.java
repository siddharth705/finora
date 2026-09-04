package com.finora.imports.product;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Stage 2 of Financial Product Discovery: score collected evidence against every product hypothesis
 * and pick a winner, or refuse to.
 *
 * Runs BEFORE transactions are parsed, because what a section is determines how it should be read:
 * a savings account is a ledger, a term deposit is a balance plus a maturity date, a recurring
 * deposit is an installment schedule. Treating all three as ledgers is what produced empty accounts
 * from a combined statement.
 *
 * <h2>Three rules, each from a real failure</h2>
 *
 * <b>No single signal decides.</b> A hypothesis needs at least {@link #MIN_CORROBORATING_SIGNALS}
 * independent positive signals. One keyword used to be enough, and a fixed-deposit schedule with a
 * "Deposit(Mnth)" column -- the monthly contribution, not money moving in -- was read as a
 * transaction account and offered to the user as a savings account they did not have.
 *
 * <b>Contradiction disqualifies, it does not subtract.</b> A signal a product should never carry
 * means the reading is wrong, not marginally less likely. Subtracting a fixed penalty let a
 * contradicted hypothesis still win by piling up weak corroboration elsewhere.
 *
 * <b>Where a name was found outweighs which name it was.</b> A product named in a section's own
 * columns is close to decisive; the same name in a document-level summary is nearly worthless. See
 * {@link EvidenceSource}.
 *
 * <h2>Detection, not truth</h2>
 *
 * A winner here is a HYPOTHESIS, never a fact. It carries the evidence behind it so it can be
 * argued with, and it is not permitted to create anything until Stage 3
 * ({@link ProductValidator}) confirms the product can prove what it claims. When evidence does not
 * clear the bar the answer is {@link FinancialProductType#UNKNOWN} -- a successful outcome, not a
 * failure. An unidentified product is shown to the user to name once; it is never guessed into an
 * account, because a wrong product silently writes wrong data into someone's net worth.
 */
@Component
public class FinancialProductClassifier {

    /** How a single fact bore on a hypothesis. */
    public enum EvidenceKind {
        /** An expected signal was present. */
        POSITIVE,
        /** An expected signal was absent. Recorded, not merely skipped -- a claim with nothing
         *  behind it is a finding, and it is what Stage 3 reports on. */
        NEGATIVE,
        /** A signal this product should never carry was present. Disqualifying. */
        CONTRADICTORY
    }

    /**
     * One line of the reasoning, in a form that survives into an import report.
     *
     * {@code confidence} is PER SIGNAL, not a share of some overall total: it is how much this one
     * observation is worth, which is decided by where it was observed ({@link EvidenceSource}). A
     * result therefore reads "DESCRIPTION_COLUMN 1.0, PRODUCT_NAME 0.15" rather than a single
     * blended 0.93 that cannot be argued with. Zero for negative and contradictory evidence --
     * absence and contradiction are not observations with a strength, they are the absence of one
     * and the refutation of one.
     */
    public record Evidence(EvidenceKind kind, ProductSignal signal, double confidence, String detail) {

        static Evidence positive(ProductSignal signal, EvidenceSource source, String detail) {
            return new Evidence(EvidenceKind.POSITIVE, signal, weightOf(source), detail);
        }

        static Evidence negative(ProductSignal signal, String detail) {
            return new Evidence(EvidenceKind.NEGATIVE, signal, 0, detail);
        }

        static Evidence contradictory(ProductSignal signal, String detail) {
            return new Evidence(EvidenceKind.CONTRADICTORY, signal, 0, detail);
        }

        @Override
        public String toString() {
            String weight = kind == EvidenceKind.POSITIVE
                    ? " (" + round(confidence) + ")" : "";
            return kind + ": " + signal + weight
                    + (detail == null || detail.isBlank() ? "" : " -- " + detail);
        }
    }

    /**
     * What the classifier concluded, why, and how sure it is -- per signal, not one opaque number.
     *
     * {@code evidence} exists so a wrong answer can be argued with. Without it "this is a recurring
     * deposit, 0.8" is unfalsifiable, and the only way to debug a misclassification is to re-read
     * the PDF -- which is exactly the position the engine was in before.
     */
    public record ProductClassification(FinancialProductType type, double confidence,
                                        List<Evidence> evidence, SectionEvidence collected) {

        public boolean isConfident() { return confidence >= CONFIDENCE_THRESHOLD; }

        public List<Evidence> contradictions() {
            return evidence.stream().filter(e -> e.kind() == EvidenceKind.CONTRADICTORY).toList();
        }

        /** Human-readable reasoning, for the review screen and the import report. */
        public List<String> explain() {
            return evidence.stream().map(Evidence::toString).toList();
        }
    }

    /**
     * Below this, the answer is UNKNOWN and the user is asked. Set where a product with most of its
     * expected structure present clears the bar and a half-recognised one does not -- getting this
     * wrong in the permissive direction creates silent bad data in someone's net worth, while
     * getting it wrong in the strict direction costs one question on the review screen.
     */
    static final double CONFIDENCE_THRESHOLD = 0.6;

    /** The "no single keyword decides" rule, as a number. Two independent signals is the minimum
     *  that can distinguish a product from a coincidence. */
    private static final int MIN_CORROBORATING_SIGNALS = 2;

    /** What a positive signal is worth, by where it was observed. A column header belongs to its
     *  own table beyond argument; document-level text is barely evidence of anything about one
     *  section, and is weighted so it can corroborate a structural reading but never carry one. */
    private static double weightOf(EvidenceSource source) {
        return switch (source) {
            case COLUMN_HEADERS -> 1.0;
            case ROW_DATA -> 0.8;
            case SECTION_TEXT -> 0.6;
            case DOCUMENT_TEXT -> 0.15;
        };
    }

    private final ProductEvidenceCollector collector;

    public FinancialProductClassifier(ProductEvidenceCollector collector) {
        this.collector = collector;
    }

    /** Collects evidence and classifies in one call, for callers that have a section rather than a
     *  prepared {@link SectionEvidence}. */
    public ProductClassification classify(ProductEvidenceCollector.Section section) {
        return classify(collector.collect(section));
    }

    /** Scores already-collected evidence. Stage 1 and Stage 2 stay separately callable so evidence
     *  can be re-scored after a hypothesis changes without re-reading the document. */
    public ProductClassification classify(SectionEvidence collected) {
        ProductHypothesis winner = null;
        double winningScore = 0;
        List<Evidence> winningEvidence = List.of();
        // Why each rejected hypothesis was rejected. Kept rather than discarded: when the answer is
        // UNKNOWN, "which products were considered and what ruled each one out" is the entire
        // content of the answer, and it is what a review screen has to show the user.
        List<Evidence> rejections = new ArrayList<>();

        for (ProductHypothesis hypothesis : ProductHypothesis.all()) {
            List<Evidence> evidence = new ArrayList<>();
            double score = scoreOf(hypothesis, collected, evidence);
            if (score <= 0) {
                // Only the disqualifying lines are worth keeping -- a full expected-but-absent dump
                // for all fifteen hypotheses would bury the reason under its own noise.
                evidence.stream()
                        .filter(e -> e.kind() == EvidenceKind.CONTRADICTORY
                                || (e.kind() == EvidenceKind.NEGATIVE
                                    && e.signal() == ProductSignal.PRODUCT_NAME))
                        .forEach(rejections::add);
                continue;
            }

            // Strictly greater keeps declaration order meaningful: a tie goes to whichever product
            // was declared first, so a recurring deposit beats a fixed deposit on the maturity date
            // they share rather than the other way round.
            if (score > winningScore) {
                winningScore = score;
                winner = hypothesis;
                winningEvidence = evidence;
            }
        }

        if (winner == null) {
            List<Evidence> why = new ArrayList<>();
            why.add(Evidence.negative(ProductSignal.PRODUCT_NAME,
                    "no product hypothesis survived the evidence"));
            why.addAll(rejections);
            return new ProductClassification(FinancialProductType.UNKNOWN, 0.0, why, collected);
        }

        double confidence = Math.min(winningScore, 0.95);
        if (confidence < CONFIDENCE_THRESHOLD) {
            List<Evidence> evidence = new ArrayList<>(winningEvidence);
            evidence.add(Evidence.negative(ProductSignal.PRODUCT_NAME,
                    "best candidate " + winner.type() + " scored " + round(confidence)
                            + ", below the " + CONFIDENCE_THRESHOLD + " threshold -- asking the user"));
            return new ProductClassification(FinancialProductType.UNKNOWN, confidence, evidence, collected);
        }
        return new ProductClassification(winner.type(), confidence, winningEvidence, collected);
    }

    /**
     * Scores one hypothesis, appending its reasoning to {@code evidence}.
     *
     * @return a confidence in [0, 0.95], or 0 when the hypothesis is disqualified -- by a
     *         contradiction, or by not clearing {@link #MIN_CORROBORATING_SIGNALS}.
     */
    private double scoreOf(ProductHypothesis hypothesis, SectionEvidence collected,
                           List<Evidence> evidence) {
        // Contradictions first: a disqualified hypothesis is not worth scoring, and recording WHY
        // it was rejected is more useful than a score nobody will read.
        boolean contradicted = false;
        for (ProductSignal forbidden : hypothesis.forbidden()) {
            if (!collected.has(forbidden)) continue;
            EvidenceSource where = collected.strongestSourceFor(forbidden).orElse(EvidenceSource.DOCUMENT_TEXT);
            // A contradiction seen only in document-level text is not this section's contradiction
            // -- the same scoping rule that stops a leaked product name from deciding anything.
            if (where == EvidenceSource.DOCUMENT_TEXT) continue;
            // DESCRIPTION_COLUMN specifically, and only when SECTION_TEXT is the STRONGEST source
            // (strongestSourceFor already returns the max, so this can never suppress a genuine
            // COLUMN_HEADERS/ROW_DATA match): its own vocabulary includes "details", ordinary
            // English that shows up in prose having nothing to do with a table column ("for
            // details, contact your branch"). A real HDFC statement's routine TDS-apportionment
            // disclaimer matched it this way and, with no partial credit on a contradiction,
            // disqualified FIXED_DEPOSIT/RECURRING_DEPOSIT outright even with every genuine
            // structural signal for both present and strong. A real column named "Description" is
            // COLUMN_HEADERS-sourced and still disqualifies normally; only a bare prose mention is
            // exempted. See ProductEvidenceCollector's own vocabulary comment for why "details"
            // stays in the vocabulary rather than being removed there instead -- a real IndusInd
            // credit-card statement's own "For details, ..." sentence is genuine, if weak,
            // corroborating evidence FOR that section's real product, which removing the word
            // entirely would have thrown away along with the false positive.
            if (forbidden == ProductSignal.DESCRIPTION_COLUMN && where == EvidenceSource.SECTION_TEXT) continue;
            evidence.add(Evidence.contradictory(forbidden,
                    hypothesis.type() + " should not carry this, but it is present in "
                            + where.name().toLowerCase().replace('_', ' ')));
            contradicted = true;
        }
        if (contradicted) return 0;

        // A naming of THIS product, found somewhere that actually belongs to this section. A
        // document-level naming is excluded deliberately: it is what a combined statement's opening
        // summary leaks into whichever section happens to sit next to it.
        ObservedFact namedHere = null;
        for (ObservedFact naming : collected.factsFor(ProductSignal.PRODUCT_NAME)) {
            if (naming.named() == hypothesis.type() && naming.source() != EvidenceSource.DOCUMENT_TEXT) {
                namedHere = naming;
                break;
            }
        }

        if (hypothesis.requiresExplicitNaming() && namedHere == null) {
            evidence.add(Evidence.negative(ProductSignal.PRODUCT_NAME,
                    hypothesis.type() + " is structurally indistinguishable from a more common "
                            + "product, so it is only claimed when the document names it -- and it "
                            + "does not"));
            return 0;
        }

        // A named-only product (no structural vocabulary defined for it yet) is carried by its name
        // alone. That does not violate "no single signal decides": the naming produces a HYPOTHESIS
        // for the review screen, and Stage 3's empty proof set guarantees it can never be persisted
        // without a human. The min-corroboration rule below governs products the engine can
        // actually read structurally.
        if (hypothesis.expected().isEmpty()) {
            evidence.add(Evidence.positive(ProductSignal.PRODUCT_NAME, namedHere.source(),
                    "named \"" + namedHere.observed() + "\" in "
                            + namedHere.source().name().toLowerCase().replace('_', ' ')
                            + "; recognised by name only, so it cannot be created without review"));
            return weightOf(namedHere.source());
        }

        double earned = 0;
        double available = 0;
        int corroborating = 0;
        Set<ProductSignal> counted = EnumSet.noneOf(ProductSignal.class);

        for (ProductSignal expected : hypothesis.expected()) {
            available += 1.0;
            if (collected.has(expected)) {
                EvidenceSource where = collected.strongestSourceFor(expected).orElse(EvidenceSource.SECTION_TEXT);
                earned += weightOf(where);
                corroborating++;
                counted.add(expected);
                evidence.add(Evidence.positive(expected, where,
                        "observed in " + where.name().toLowerCase().replace('_', ' ')));
            } else {
                evidence.add(Evidence.negative(expected, "expected but absent"));
            }
        }

        // A naming of THIS product corroborates it, weighted by where it was found. A naming found
        // only in document-level text contributes almost nothing on purpose: it is what a
        // relationship summary leaks into every section of a combined statement.
        //
        // Bug fix: this loop used to add 1.0 to `available` and weightOf(source) to `earned` for
        // EVERY naming fact, while the expected-signal loop above collapses repeats to the
        // strongest source ("only the strongest occurrence matters"). Two loops feeding one
        // normalized ratio, applying opposite policies to the same question.
        //
        // Contributing little to the numerator while contributing fully to the denominator is not
        // a discount, it is a penalty: any evidence weighing less than the running score pulls the
        // score down. DOCUMENT_TEXT weighs 0.15 against a 0.6 threshold, so a document-level
        // naming reduced the confidence of any hypothesis it was recorded as supporting -- while
        // appearing in the evidence list as Evidence.positive. Measured: a SAVINGS section scoring
        // 0.829 on structure alone fell to 0.744 purely because the document also said "savings
        // account."
        //
        // demoteEnumeratedNames made it worse rather than safer. It rewrites a SECTION_TEXT naming
        // to DOCUMENT_TEXT when section text names two or more products -- the signature of a
        // combined statement, i.e. exactly the document class this classifier exists for -- but it
        // KEEPS the fact and only lowers its weight. So demotion moved a naming from 0.6 to 0.15
        // while it still added a full 1.0 to `available`: a mechanism written to neutralise a
        // leaked naming instead converted it into a penalty against the correct answer.
        //
        // Counting one naming per product, at its strongest source, is the same collapse policy
        // the expected-signal loop already uses and documents. It also makes demotion behave as
        // intended -- lowering a naming's weight without adding another unit of denominator.
        List<ObservedFact> namingsOfThisType = collected.factsFor(ProductSignal.PRODUCT_NAME).stream()
                .filter(naming -> naming.named() == hypothesis.type())
                .toList();
        if (!namingsOfThisType.isEmpty()) {
            ObservedFact strongest = namingsOfThisType.stream()
                    .max(java.util.Comparator.comparing(ObservedFact::source))
                    .orElseThrow();
            // Contributes at its OWN weight on both sides of the ratio, not 1.0 on the denominator
            // and its weight on the numerator. That asymmetry is what made weak evidence subtract:
            // since the score is below 1, adding equal amounts to earned and available always
            // moves it toward 1, so a naming is now a small positive rather than a net negative --
            // which is what "contributes almost nothing on purpose" was always meant to describe.
            //
            // Deliberately NOT applied to the expected-signal loop above, where `available += 1.0`
            // is the correct semantics: that denominator counts what the hypothesis EXPECTED, and
            // an expected-but-absent signal must still count as a full unit it failed to earn. A
            // naming is not an expectation, it is bonus corroboration, so it does not belong in
            // that denominator as a full unit.
            double namingWeight = weightOf(strongest.source());
            available += namingWeight;
            earned += namingWeight;
            if (strongest.source() != EvidenceSource.DOCUMENT_TEXT && counted.add(ProductSignal.PRODUCT_NAME)) {
                corroborating++;
            }
            evidence.add(Evidence.positive(ProductSignal.PRODUCT_NAME, strongest.source(),
                    "named \"" + strongest.observed() + "\" in "
                            + strongest.source().name().toLowerCase().replace('_', ' ')));
        }

        if (available == 0) return 0;
        if (corroborating < MIN_CORROBORATING_SIGNALS) {
            evidence.add(Evidence.negative(ProductSignal.PRODUCT_NAME,
                    "only " + corroborating + " independent signal(s) support " + hypothesis.type()
                            + "; " + MIN_CORROBORATING_SIGNALS + " required, so no single signal can decide"));
            return 0;
        }
        return Math.min(earned / available, 0.95);
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
