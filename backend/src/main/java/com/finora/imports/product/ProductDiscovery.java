package com.finora.imports.product;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.finora.imports.product.FinancialProductClassifier.ProductClassification;
import static com.finora.imports.product.ProductValidator.ValidationResult;

/**
 * The four stages of Financial Product Discovery, in the only order they are allowed to run:
 * Evidence Collection to Classification to Validation to Persistence.
 *
 * Downstream code is given a {@link DiscoveredProduct} and nothing else. That is the point: the
 * persistence gate is {@link DiscoveredProduct#mayCreateAutomatically()}, and it is impossible to
 * reach a product type without also holding the validation verdict that qualifies it. When
 * classification and persistence were separate concerns wired together by each caller, "did this
 * pass validation" was a question each caller could forget to ask.
 *
 * The engine never believes anything. It accumulates evidence, tests hypotheses, rejects
 * contradictions, validates the winner, and only then permits a write.
 */
@Component
public class ProductDiscovery {

    private final ProductEvidenceCollector collector;
    private final FinancialProductClassifier classifier;
    private final ProductValidator validator;

    public ProductDiscovery(ProductEvidenceCollector collector, FinancialProductClassifier classifier,
                            ProductValidator validator) {
        this.collector = collector;
        this.classifier = classifier;
        this.validator = validator;
    }

    /**
     * The standard pipeline, wired by hand.
     *
     * All four stages are pure functions of their input -- no repositories, no clock, no
     * configuration -- so constructing one outside Spring is not a testing shortcut that skips
     * something. Exists so a test (or a diagnostic) can build the real pipeline in one call rather
     * than restating its three-component assembly, which would then have to be updated in twenty
     * files every time a stage gains a dependency.
     */
    public static ProductDiscovery standard() {
        ProductEvidenceCollector collector = new ProductEvidenceCollector();
        return new ProductDiscovery(collector, new FinancialProductClassifier(collector),
                new ProductValidator());
    }

    /**
     * A product found in a document, with the full reasoning that produced it.
     *
     * @param classification what Stage 2 concluded, and the evidence behind it
     * @param validation     whether Stage 3 could prove it
     */
    public record DiscoveredProduct(ProductClassification classification, ValidationResult validation) {

        public FinancialProductType type() { return classification.type(); }

        public double confidence() { return classification.confidence(); }

        public SectionEvidence evidence() { return classification.collected(); }

        /**
         * The persistence gate. True only when the product was identified, proved itself, and is
         * something Finora actually models.
         *
         * Everything else -- unknown, unproven, or a real product with nowhere to go yet -- reaches
         * the review screen instead. An unclassifiable product must never block an import and must
         * never be silently created as an account.
         */
        public boolean mayCreateAutomatically() {
            return validation.isValidated()
                    && classification.isConfident()
                    && !type().requiresUserConfirmation();
        }

        /** True when this needs a human to name or confirm it before anything is created. */
        public boolean needsReview() {
            return !mayCreateAutomatically();
        }

        /** One product's contribution to the import report: what it is, how sure, and why. */
        public List<String> report() {
            List<String> lines = new ArrayList<>();
            lines.add(type() + " (confidence " + Math.round(confidence() * 100) + "%, "
                    + validation.verdict() + ")");
            lines.addAll(classification.explain());
            lines.addAll(validation.notes());
            return lines;
        }
    }

    /** Runs all four stages over one section of a document. */
    public DiscoveredProduct discover(ProductEvidenceCollector.Section section) {
        SectionEvidence evidence = collector.collect(section);
        ProductClassification classification = classifier.classify(evidence);
        ValidationResult validation = validator.validate(classification);
        return new DiscoveredProduct(classification, validation);
    }
}
