package com.finora.imports;

import com.finora.dto.ImportDto;
import com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Checks a credit-card statement's own billing equation against itself:
 *
 * <pre>
 *   previousBalance + purchases + cashAdvances + fees - paymentsAndCredits == totalAmountDue
 * </pre>
 *
 * <p><b>The credit-card counterpart of {@link StatementTotalsValidator}, not a second opinion on
 * it.</b> That validator reconciles a savings statement's printed opening/closing balance against
 * the SUM OF PARSED TRANSACTION ROWS. This validator never reads a transaction row at all — every
 * field on both sides of the equation comes from the same billing-summary panel the bank prints
 * about itself (see {@link com.finora.imports.pdf.CreditCardSummaryExtractor}). That is deliberate:
 * the Credit Card Direction Evidence Study found half the real credit-card corpus has a
 * transaction TABLE that does not form correctly (ICICI CC, HDFC — see the Open Investigations in
 * the architecture doc), while their summary panels may still be readable. A validator that needed
 * parsed rows would be exactly as broken as the table it depends on; this one is not, on purpose.
 *
 * <p><b>What a mismatch actually means here.</b> The bank's own printed numbers necessarily agree
 * with each other on the real document — a mismatch means THIS EXTRACTION misread one of the five
 * component fields (most likely a column-anchor mismatch on the summary grid, the same class of bug
 * {@link com.finora.imports.pdf.StatementSummaryExtractor} was built defensively against). That is
 * why this reports {@code WARNING}, never {@code FAILED}: unlike a savings statement's balance
 * chain, a mismatch here does not implicate the customer's transactions at all, only this
 * extraction's own reading of the summary panel.
 *
 * <p><b>Never infers a missing field or corrects a transaction.</b> If the panel does not print
 * previous balance, purchases, payments/credits, and the total amount due, this reports
 * {@code NOT_APPLICABLE} rather than guessing — the same "missing validation is safer than a wrong
 * one" discipline the Credit Card Direction Evidence Study itself was built around. Cash advances
 * and fees are the two exceptions, treated as zero when absent rather than blocking the check —
 * see {@link CreditCardSummaryEvidence#hasReconcilableFields()}'s own doc comment for the real
 * document (AU) that motivated extending that treatment from fees to cash advances too.
 *
 * <p><b>A cross-strategy disagreement outranks the equation check.</b> {@code extract} always runs
 * both GRID and INLINE_LABEL_VALUE, and {@link CreditCardSummaryEvidence#conflictingFields()} names
 * any field where they found different numbers. That is checked FIRST, before either
 * {@code NOT_APPLICABLE} or the equation math: two independent readings disagreeing is itself proof
 * one of them is wrong, regardless of whether the winning strategy's own result looks complete or
 * would otherwise have balanced.
 */
@Component
public class CreditCardStatementTotalsValidator {

    /** Stable machine identifier — clients group and explain by it, so it must not track wording. */
    public static final String RULE = "CREDIT_CARD_STATEMENT_TOTALS";

    public ImportDto.VerificationFinding check(CreditCardSummaryEvidence summary) {
        Map<String, Object> details = new LinkedHashMap<>();

        if (summary != null && !summary.conflictingFields().isEmpty()) {
            details.put("conflictingFields", summary.conflictingFields());
            details.put("reason", "SUMMARY_EXTRACTION_CONFLICT: GRID and INLINE_LABEL_VALUE read "
                    + "different values for the same field(s) on this statement's billing-summary "
                    + "panel. At least one of the two readings is wrong, so neither is trusted here.");
            return new ImportDto.VerificationFinding(RULE, "WARNING", details);
        }

        if (summary == null || !summary.hasReconcilableFields()) {
            details.put("reason", "This statement's billing-summary panel did not print enough of "
                    + "previous balance, purchases, and payments/credits, alongside the total amount "
                    + "due, to check them against each other.");
            return new ImportDto.VerificationFinding(RULE, "NOT_APPLICABLE", details);
        }

        BigDecimal cashAdvances = summary.cashAdvances() == null ? BigDecimal.ZERO : summary.cashAdvances();
        BigDecimal fees = summary.fees() == null ? BigDecimal.ZERO : summary.fees();
        BigDecimal expectedTotalAmountDue = summary.previousBalance()
                .add(summary.purchases())
                .add(cashAdvances)
                .add(fees)
                .subtract(summary.paymentsAndCredits());
        BigDecimal difference = summary.totalAmountDue().subtract(expectedTotalAmountDue);

        // extractionMethod: not persisted (see the allowlist), but available on the live finding for
        // debugging and user explanation -- which strategy read these numbers is real information,
        // just not one this validator needs to decide VERIFIED/WARNING/NOT_APPLICABLE.
        details.put("extractionMethod", summary.extractionMethod().name());
        details.put("previousBalance", summary.previousBalance());
        details.put("purchases", summary.purchases());
        details.put("cashAdvances", cashAdvances);
        details.put("fees", fees);
        details.put("paymentsAndCredits", summary.paymentsAndCredits());
        details.put("totalAmountDue", summary.totalAmountDue());
        details.put("expectedTotalAmountDue", expectedTotalAmountDue);
        details.put("difference", difference);

        if (difference.signum() == 0) {
            return new ImportDto.VerificationFinding(RULE, "VERIFIED", details);
        }

        details.put("explanation", "The previous balance, purchases, cash advances, fees, and "
                + "payments/credits this statement prints about itself do not add up to its own "
                + "printed total amount due. This does not implicate any parsed transaction -- no "
                + "transaction row was read to produce this finding -- it means this extraction "
                + "misread one of the statement's own summary figures.");
        return new ImportDto.VerificationFinding(RULE, "WARNING", details);
    }
}
