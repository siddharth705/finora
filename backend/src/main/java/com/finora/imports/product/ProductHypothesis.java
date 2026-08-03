package com.finora.imports.product;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.finora.imports.product.ProductSignal.*;

/**
 * What a product should look like, and -- just as importantly -- what it should NOT look like.
 *
 * Each hypothesis declares three sets:
 *
 * <ul>
 *   <li><b>expected</b> — signals this product normally carries. Present ones are POSITIVE evidence;
 *       absent ones are NEGATIVE evidence. Absence is deliberately recorded rather than ignored: a
 *       "fixed deposit" with no maturity date, no rate and no principal is a claim with nothing
 *       behind it, and that emptiness is the finding.</li>
 *   <li><b>forbidden</b> — signals this product should never carry. A present one is CONTRADICTORY
 *       evidence, which disqualifies the hypothesis outright rather than costing it a point (see
 *       {@link FinancialProductClassifier}). A maturity date on something calling itself a savings
 *       account does not make it a slightly-less-likely savings account; it means the reading is
 *       wrong.</li>
 *   <li><b>proof</b> — the subset a classification must actually demonstrate before anything is
 *       persisted. Read by Stage 3 ({@link ProductValidator}), not by scoring.</li>
 * </ul>
 *
 * Declaring these per product, in one table, is what keeps the classifier free of per-product
 * branching: adding a product is a row here, not an {@code if} somewhere in the scorer.
 */
public record ProductHypothesis(FinancialProductType type, Set<ProductSignal> expected,
                                Set<ProductSignal> forbidden, Set<ProductSignal> proof,
                                boolean requiresExplicitNaming) {

    private static final Map<FinancialProductType, ProductHypothesis> BY_TYPE = new LinkedHashMap<>();

    private static void define(FinancialProductType type, List<ProductSignal> expected,
                               List<ProductSignal> forbidden, List<ProductSignal> proof) {
        define(type, expected, forbidden, proof, false);
    }

    /**
     * @param requiresExplicitNaming true for a product that is STRUCTURALLY indistinguishable from
     *        a more common one, and can therefore only be claimed when the document says so. A
     *        current account is a savings-shaped ledger; an overdraft is a savings-shaped ledger
     *        with a limit. Without this, scoring alone picks whichever hypothesis expects the least
     *        -- a normalised score inherently favours the shorter expectation set -- so an ordinary
     *        savings statement classified as a current account purely because CURRENT expected two
     *        fewer fields.
     */
    private static void define(FinancialProductType type, List<ProductSignal> expected,
                               List<ProductSignal> forbidden, List<ProductSignal> proof,
                               boolean requiresExplicitNaming) {
        BY_TYPE.put(type, new ProductHypothesis(type, Set.copyOf(expected), Set.copyOf(forbidden),
                Set.copyOf(proof), requiresExplicitNaming));
    }

    static {
        // A transaction account is a LEDGER: dated events with descriptions and a running balance.
        // It is contradicted by anything that belongs to an instrument with a term -- a maturity
        // date, an installment schedule, an EMI -- and by credit-card payment-summary fields.
        define(FinancialProductType.SAVINGS,
                List.of(DATE_COLUMN, DESCRIPTION_COLUMN, DEBIT_CREDIT_COLUMNS, RUNNING_BALANCE_COLUMN,
                        TRANSACTION_ROWS, OPENING_BALANCE_FIELD, CLOSING_BALANCE_FIELD),
                List.of(MATURITY_FIELD, INSTALLMENT_FIELD, EMI_FIELD, MINIMUM_DUE_FIELD, TOTAL_DUE_FIELD),
                List.of(DATE_COLUMN, DESCRIPTION_COLUMN, TRANSACTION_ROWS));

        // Structurally identical to SAVINGS -- only the document's own words separate them.
        define(FinancialProductType.CURRENT,
                List.of(DATE_COLUMN, DESCRIPTION_COLUMN, DEBIT_CREDIT_COLUMNS, RUNNING_BALANCE_COLUMN,
                        TRANSACTION_ROWS),
                List.of(MATURITY_FIELD, INSTALLMENT_FIELD, EMI_FIELD, MINIMUM_DUE_FIELD),
                List.of(DATE_COLUMN, DESCRIPTION_COLUMN, TRANSACTION_ROWS), true);

        define(FinancialProductType.OVERDRAFT,
                List.of(DATE_COLUMN, DESCRIPTION_COLUMN, RUNNING_BALANCE_COLUMN, TRANSACTION_ROWS,
                        CREDIT_LIMIT_FIELD),
                List.of(MATURITY_FIELD, INSTALLMENT_FIELD, MINIMUM_DUE_FIELD),
                List.of(DATE_COLUMN, TRANSACTION_ROWS), true);

        define(FinancialProductType.WALLET,
                List.of(DATE_COLUMN, DESCRIPTION_COLUMN, TRANSACTION_ROWS, RUNNING_BALANCE_COLUMN),
                List.of(MATURITY_FIELD, INSTALLMENT_FIELD, EMI_FIELD, CREDIT_LIMIT_FIELD),
                List.of(DATE_COLUMN, TRANSACTION_ROWS), true);

        // A credit card carries a ledger AND a payment summary. The summary fields are what separate
        // it from a savings account, since both have dated transactions with descriptions.
        define(FinancialProductType.CREDIT_CARD,
                List.of(MINIMUM_DUE_FIELD, TOTAL_DUE_FIELD, CREDIT_LIMIT_FIELD, CARD_NUMBER_FIELD,
                        DATE_COLUMN, DESCRIPTION_COLUMN),
                List.of(MATURITY_FIELD, INSTALLMENT_FIELD, OPENING_BALANCE_FIELD),
                List.of(CARD_NUMBER_FIELD, TOTAL_DUE_FIELD));

        // A recurring deposit is a fixed deposit plus a schedule of contributions. INSTALLMENT_FIELD
        // is what distinguishes the two, and it is why an RD is declared BEFORE an FD: declaration
        // order breaks ties, so the more specific product wins on the maturity date they share.
        // Reordering these two silently turns every RD into an FD.
        define(FinancialProductType.RECURRING_DEPOSIT,
                List.of(INSTALLMENT_FIELD, MATURITY_FIELD, DATE_COLUMN, INTEREST_RATE_FIELD),
                List.of(DESCRIPTION_COLUMN, MINIMUM_DUE_FIELD, EMI_FIELD),
                List.of(INSTALLMENT_FIELD));

        // A fixed deposit is a principal, a rate and a maturity -- and NO ledger. The forbidden set
        // is what stops a savings account whose narration mentions a deposit from reading as one:
        // a description column means events, and a deposit has none.
        define(FinancialProductType.FIXED_DEPOSIT,
                List.of(MATURITY_FIELD, INTEREST_RATE_FIELD, PRINCIPAL_FIELD, DATE_COLUMN),
                List.of(DESCRIPTION_COLUMN, INSTALLMENT_FIELD, MINIMUM_DUE_FIELD, EMI_FIELD),
                List.of(MATURITY_FIELD));

        define(FinancialProductType.LOAN,
                List.of(EMI_FIELD, OUTSTANDING_FIELD, TENURE_FIELD, INTEREST_RATE_FIELD),
                List.of(MINIMUM_DUE_FIELD, CARD_NUMBER_FIELD),
                List.of(OUTSTANDING_FIELD));

        // Investment wrappers Finora recognises but has little structural vocabulary for yet. They
        // are named-only: an empty expectation set means nothing but the document's own words can
        // support them, and an empty proof set means Stage 3 always reports UNPROVEN, so they reach
        // the review screen and never auto-create. That is the honest state -- the engine can say
        // "a mutual fund statement is in here somewhere" without pretending it can read one.
        for (FinancialProductType named : List.of(FinancialProductType.PPF, FinancialProductType.EPF,
                FinancialProductType.NPS, FinancialProductType.DEMAT,
                FinancialProductType.MUTUAL_FUND)) {
            define(named, List.of(), List.of(DESCRIPTION_COLUMN, MINIMUM_DUE_FIELD), List.of(), true);
        }
    }

    public static ProductHypothesis forType(FinancialProductType type) {
        return BY_TYPE.get(type);
    }

    /** Every hypothesis, in declaration order. Order is meaningful: it breaks ties toward the more
     *  specific product, so a recurring deposit wins over a fixed deposit on the maturity field
     *  they share rather than the other way round. */
    public static List<ProductHypothesis> all() {
        return List.copyOf(BY_TYPE.values());
    }
}
