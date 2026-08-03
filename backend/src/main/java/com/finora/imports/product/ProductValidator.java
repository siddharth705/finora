package com.finora.imports.product;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.finora.imports.product.FinancialProductClassifier.ProductClassification;

/**
 * Stage 3 of Financial Product Discovery: can this product PROVE it is what Stage 2 thinks it is?
 *
 * Classification and validation are deliberately different questions, asked by different classes.
 * Stage 2 asks "what does the evidence most look like"; a best answer always exists, even when
 * every candidate is weak. Stage 3 asks "is there enough here to act on", which has no obligation
 * to say yes. Collapsing the two is how a plausible reading becomes a created account.
 *
 * Nothing may be persisted on a classification alone -- see {@link ProductDiscovery}, which is the
 * only thing downstream code is given.
 */
@Component
public class ProductValidator {

    public enum Verdict {
        /** The product demonstrated every field required to act on it. */
        VALIDATED,
        /** Recognised, but missing what it needs to prove itself. Surfaced to the user rather than
         *  created -- an unproven product is not an error, it is a question. */
        UNPROVEN,
        /** Nothing was recognised at all. */
        UNIDENTIFIED
    }

    public record ValidationResult(Verdict verdict, List<ProductSignal> missing, List<String> notes) {

        public boolean isValidated() { return verdict == Verdict.VALIDATED; }

        public static ValidationResult validated(List<String> notes) {
            return new ValidationResult(Verdict.VALIDATED, List.of(), notes);
        }
    }

    public ValidationResult validate(ProductClassification classification) {
        FinancialProductType type = classification.type();
        SectionEvidence evidence = classification.collected();

        if (type == FinancialProductType.UNKNOWN) {
            return new ValidationResult(Verdict.UNIDENTIFIED, List.of(),
                    List.of("no product identified; the user is asked to name it once"));
        }

        ProductHypothesis hypothesis = ProductHypothesis.forType(type);
        if (hypothesis == null) {
            // A product type with no hypothesis cannot be proven, so it is never silently trusted.
            return new ValidationResult(Verdict.UNPROVEN, List.of(),
                    List.of("no validation rules defined for " + type));
        }

        List<ProductSignal> missing = new ArrayList<>();
        for (ProductSignal required : hypothesis.proof()) {
            if (!evidence.has(required)) missing.add(required);
        }

        List<String> notes = new ArrayList<>();
        if (hypothesis.proof().isEmpty()) {
            // Deliberately not treated as "trivially valid". A product with no proof obligations is
            // one the engine has no structural vocabulary for yet (see ProductHypothesis's
            // named-only wrappers) -- it was recognised by name alone, which is exactly the case
            // that must reach a human rather than create something.
            notes.add(type + " is recognised by name only; no structural proof is defined for it yet");
            return new ValidationResult(Verdict.UNPROVEN, List.of(), notes);
        }

        if (!missing.isEmpty()) {
            notes.add(type + " could not prove itself: missing " + missing);
            return new ValidationResult(Verdict.UNPROVEN, missing, notes);
        }

        // A contradiction should already have disqualified the hypothesis in Stage 2. Re-checked
        // here because this is the gate that authorises writing to someone's net worth, and a gate
        // that trusts an upstream stage to have been correct is not a gate.
        if (!classification.contradictions().isEmpty()) {
            notes.add("contradictory evidence survived classification: " + classification.contradictions());
            return new ValidationResult(Verdict.UNPROVEN, List.of(), notes);
        }

        notes.add(type + " proved " + hypothesis.proof());
        return ValidationResult.validated(notes);
    }
}
