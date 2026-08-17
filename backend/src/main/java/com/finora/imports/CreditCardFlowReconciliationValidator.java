package com.finora.imports;

import com.finora.dto.ImportDto;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks that a credit-card statement's own EXTRACTED transactions, aggregated by direction,
 * match the PRINTED purchases and payments/credits totals from its billing-summary panel:
 *
 * <pre>
 *   sum(rows where type == EXPENSE) == summary.purchases()
 *   sum(rows where type == INCOME)  == summary.paymentsAndCredits()
 * </pre>
 *
 * <p><b>What this proves, and what it deliberately does not.</b> This is transaction
 * CLASSIFICATION consistency, not per-row direction correctness — it cannot say "row 14's
 * direction is wrong," only that the aggregate totals agree or disagree. Confirmed on a real
 * document before being built, not assumed: on AU's real statement, {@code sum(EXPENSE)} and
 * {@code sum(INCOME)} match {@code purchases}/{@code paymentsAndCredits} exactly, which is also
 * the first real evidence that AU's own per-row direction extraction is trustworthy — proven, not
 * asserted. A future, separate signal (a per-row Cr/Dr marker's own reliability) is exactly that:
 * separate, and deliberately not built here — see the Credit Card Direction Evidence Study's
 * finding that marker reliability varies by bank (AU strong, Kotak asymmetric, SBI unusable) and
 * needs its own study before being trusted, the same "evidence before capability" gate every other
 * validator in this package was built under.
 *
 * <p><b>Never says which side is wrong.</b> A mismatch could mean the extracted rows are wrong,
 * the summary panel was misread, or both — this validator has no way to tell, so it does not
 * guess. {@code WARNING}, never {@code FAILED}, matching every other credit-card validator's
 * posture in this package.
 *
 * <p><b>Facts, not a confidence score.</b> {@code details()} carries the expected and observed
 * amount on each side, plus their signed difference ({@code expectedExpenseAmount},
 * {@code observedExpenseAmount}, {@code differenceExpenseAmount}, and the income-side counterparts).
 * That evidence is deliberately left uncombined into a single number: a fabricated
 * {@code DirectionConfidenceScore} would imply a reliability this validator does not have, since
 * per-row direction evidence quality still varies sharply by bank (AU: type column matches the
 * summary; Kotak: markers are asymmetric; SBI: extraction is broken; HDFC: unassessed) — see the
 * Credit Card Direction Evidence Study. A future signal built on that per-row evidence is exactly
 * that: a future, separate signal, gated the same "evidence before capability" way as everything
 * else in this package.
 */
@Component
public class CreditCardFlowReconciliationValidator {

    /** Stable machine identifier — clients group and explain by it, so it must not track wording. */
    public static final String RULE = "CREDIT_CARD_FLOW_RECONCILIATION";

    /**
     * How much of the billing-summary panel and the extracted rows this check actually had to work
     * with — recorded even though {@link #NO_SUMMARY}, {@link #PARTIAL_SUMMARY_ONLY}, and
     * {@link #NO_CLASSIFIED_TRANSACTIONS} currently all produce the same {@code NOT_APPLICABLE}
     * outcome, because they are three different claims, not one: Axis (PARTIAL) has a readable panel
     * that simply doesn't print a purchases/payments split; HDFC (NO_SUMMARY) has no readable panel
     * at all; a statement with a full panel but zero EXPENSE/INCOME rows (NO_CLASSIFIED_TRANSACTIONS)
     * has neither of those problems — its transaction table failed to extract or classify anything.
     * Collapsing these into one {@code NOT_APPLICABLE} count would hide that the first two are a
     * statement-format gap and the third is an extraction failure — a future failure-rate breakdown
     * needs this distinction to tell "the bank doesn't print this" from "our own extraction broke"
     * apart. Not surfaced in the UI yet — kept for when it is.
     */
    public enum CreditCardFlowEvidenceLevel {
        FULL_SUMMARY_RECONCILIATION,
        PARTIAL_SUMMARY_ONLY,
        NO_CLASSIFIED_TRANSACTIONS,
        NO_SUMMARY
    }

    public ImportDto.VerificationFinding check(List<StagedRow> rows, CreditCardSummaryEvidence summary) {
        Map<String, Object> details = new LinkedHashMap<>();

        boolean hasPurchases = summary != null && summary.purchases() != null;
        boolean hasPayments = summary != null && summary.paymentsAndCredits() != null;
        boolean hasClassifiedRows = rows != null && rows.stream()
                .anyMatch(r -> "EXPENSE".equals(r.type()) || "INCOME".equals(r.type()));

        CreditCardFlowEvidenceLevel evidenceLevel;
        if (summary == null || summary.equals(CreditCardSummaryEvidence.NONE)) {
            evidenceLevel = CreditCardFlowEvidenceLevel.NO_SUMMARY;
        } else if (!hasPurchases || !hasPayments) {
            evidenceLevel = CreditCardFlowEvidenceLevel.PARTIAL_SUMMARY_ONLY;
        } else if (!hasClassifiedRows) {
            evidenceLevel = CreditCardFlowEvidenceLevel.NO_CLASSIFIED_TRANSACTIONS;
        } else {
            evidenceLevel = CreditCardFlowEvidenceLevel.FULL_SUMMARY_RECONCILIATION;
        }
        details.put("evidenceLevel", evidenceLevel.name());

        if (evidenceLevel != CreditCardFlowEvidenceLevel.FULL_SUMMARY_RECONCILIATION) {
            String reason;
            switch (evidenceLevel) {
                case NO_SUMMARY -> reason = "No billing-summary panel evidence was extracted for "
                        + "this statement.";
                case PARTIAL_SUMMARY_ONLY -> reason = "The billing-summary panel did not print both "
                        + "purchases and payments/credits, so the extracted transactions could not "
                        + "be reconciled against it.";
                default -> reason = "This statement's billing-summary panel printed both purchases "
                        + "and payments/credits, but no extracted row was classified as either an "
                        + "expense or an income -- there is nothing to reconcile the panel against.";
            }
            details.put("reason", reason);
            return new ImportDto.VerificationFinding(RULE, "NOT_APPLICABLE", details);
        }

        BigDecimal observedExpenseAmount = sumOf(rows, "EXPENSE");
        BigDecimal observedIncomeAmount = sumOf(rows, "INCOME");
        BigDecimal expectedExpenseAmount = summary.purchases();
        BigDecimal expectedIncomeAmount = summary.paymentsAndCredits();

        details.put("expectedExpenseAmount", expectedExpenseAmount);
        details.put("observedExpenseAmount", observedExpenseAmount);
        details.put("differenceExpenseAmount", observedExpenseAmount.subtract(expectedExpenseAmount));
        details.put("expectedIncomeAmount", expectedIncomeAmount);
        details.put("observedIncomeAmount", observedIncomeAmount);
        details.put("differenceIncomeAmount", observedIncomeAmount.subtract(expectedIncomeAmount));

        boolean purchasesMatch = observedExpenseAmount.compareTo(expectedExpenseAmount) == 0;
        boolean paymentsMatch = observedIncomeAmount.compareTo(expectedIncomeAmount) == 0;

        if (purchasesMatch && paymentsMatch) {
            return new ImportDto.VerificationFinding(RULE, "VERIFIED", details);
        }

        details.put("explanation", "The extracted transactions, summed by direction, do not match "
                + "this statement's own printed purchases and/or payments/credits totals. This does "
                + "not identify which side is wrong -- only that they disagree.");
        return new ImportDto.VerificationFinding(RULE, "WARNING", details);
    }

    /** {@code StagedRow.amount()} is documented as absolute by the time it reaches here; {@code abs()}
     *  applied anyway, matching every other validator's own {@code sumOf} in this package, so a
     *  future change to that guarantee cannot silently flip a sign into this total. */
    private static BigDecimal sumOf(List<StagedRow> rows, String type) {
        return rows.stream()
                .filter(r -> r.amount() != null)
                .filter(r -> type.equals(r.type()))
                .map(r -> r.amount().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
