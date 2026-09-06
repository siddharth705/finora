package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.Transaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reconciliation accuracy benchmark, category 4 of 6: refunds and reversals. See
 * ReconciliationBenchmarkSupport's own doc comment for how to run this and what a red assertion
 * means.
 *
 * <p>Two of the scenarios below (chargeback classification, ambiguous-merchant attribution) are
 * deliberately kept as BASELINE, not GAP, even though they raise a real question -- the current
 * behavior is a considered, documented design choice or a defensible tiebreak, not a financial-
 * correctness defect (no money is double-counted or dropped either way). This benchmark reserves
 * the GAP label for cases with a measurable impact on totals or on which rows get excluded from
 * cash flow, per this project's own evidence rule: a debatable classification is not the same
 * finding as a wrong number, and conflating them would overstate this report's case.
 */
class RefundReversalBenchmark extends ReconciliationBenchmarkSupport {

    @Test
    @DisplayName("BASELINE (known-good): a full refund at the same merchant, 10 days later, is matched")
    void fullRefund_sameMerchant_isMatched() {
        Account card = cardAccount("5001");
        Transaction purchase = txn(card, LocalDate.of(2026, 7, 5), "2000.00", Transaction.Type.EXPENSE, "MYNTRA ORDER 771");
        purchase.setMerchant("Myntra");
        Transaction refund = txn(card, LocalDate.of(2026, 7, 15), "2000.00", Transaction.Type.INCOME, "MYNTRA REFUND FOR ORDER");
        refund.setMerchant("Myntra");
        loadTransactions(purchase, refund);

        run();

        assertThat(refund.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.REFUND);
        assertThat(refund.getRefundOfTransactionId()).isEqualTo(purchase.getId());
    }

    @Test
    @DisplayName("BASELINE (known-good): a partial refund is matched and does not require the full purchase amount")
    void partialRefund_isMatched() {
        Account card = cardAccount("5001");
        Transaction purchase = txn(card, LocalDate.of(2026, 7, 5), "3200.00", Transaction.Type.EXPENSE, "RETAILER RETAIL");
        purchase.setMerchant("Myntra");
        Transaction refund = txn(card, LocalDate.of(2026, 7, 26), "1600.00", Transaction.Type.INCOME, "RETAILER REFUND FOR ORDER");
        refund.setMerchant("Myntra");
        loadTransactions(purchase, refund);

        run();

        assertThat(refund.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.REFUND);
        assertThat(refund.getRefundOfTransactionId()).isEqualTo(purchase.getId());
    }

    @Test
    @DisplayName("BASELINE (known-good): two partial refunds are both matched against remaining capacity; a third that would overflow it is correctly left unmatched")
    void multipleRefundsAgainstOnePurchase_capacityTracked() {
        Account card = cardAccount("5001");
        Transaction purchase = txn(card, LocalDate.of(2026, 7, 1), "3000.00", Transaction.Type.EXPENSE, "MYNTRA ORDER 9001");
        purchase.setMerchant("Myntra");
        Transaction refund1 = txn(card, LocalDate.of(2026, 7, 6), "1200.00", Transaction.Type.INCOME, "MYNTRA REFUND ITEM A");
        refund1.setMerchant("Myntra");
        Transaction refund2 = txn(card, LocalDate.of(2026, 7, 11), "1200.00", Transaction.Type.INCOME, "MYNTRA REFUND ITEM B");
        refund2.setMerchant("Myntra");
        // Remaining capacity after refund1+refund2 is ₹600 -- this ₹800 claim must NOT be granted.
        Transaction refund3 = txn(card, LocalDate.of(2026, 7, 16), "800.00", Transaction.Type.INCOME, "MYNTRA REFUND ITEM C");
        refund3.setMerchant("Myntra");
        loadTransactions(purchase, refund1, refund2, refund3);

        run();

        assertThat(refund1.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.REFUND);
        assertThat(refund2.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.REFUND);
        assertThat(refund3.getReconciliationStatus())
                .as("BH-007's capacity fix: a purchase can't be over-claimed by more refunds than it was worth")
                .isEqualTo(Transaction.ReconciliationStatus.OK);
    }

    @Test
    @DisplayName("BASELINE (known-good): a refund 170 days later, just inside the 180-day window, is still matched")
    void delayedRefund_at170Days_stillMatched() {
        Account card = cardAccount("5001");
        Transaction purchase = txn(card, LocalDate.of(2026, 1, 10), "4500.00", Transaction.Type.EXPENSE, "MYNTRA ORDER 5521");
        purchase.setMerchant("Myntra");
        Transaction refund = txn(card, LocalDate.of(2026, 1, 10).plusDays(170), "4500.00", Transaction.Type.INCOME, "MYNTRA REFUND FOR ORDER");
        refund.setMerchant("Myntra");
        loadTransactions(purchase, refund);

        run();

        assertThat(refund.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.REFUND);
    }

    @Test
    @DisplayName("BY DESIGN (documented silent-failure risk): a refund 185 days later, just past the 180-day window, is left as ordinary income with no signal to the user")
    void refundBeyond180DayWindow_silentlyLeftAsOrdinaryIncome() {
        // ReconciliationPolicy.REFUND_WINDOW_DAYS's own doc comment names this exact risk: "this is
        // the one threshold where being too narrow fails silently: the refund simply stays
        // classified as ordinary income and nobody is told it was not matched." This test doesn't
        // assert a bug -- 185 days genuinely should not match a 180-day policy -- it exists to give
        // the roadmap a concrete, reproducible instance of a risk the code already flags in prose
        // but never surfaces to a user (no warning, no "possible unmatched refund" signal anywhere).
        Account card = cardAccount("5001");
        Transaction purchase = txn(card, LocalDate.of(2026, 1, 10), "4500.00", Transaction.Type.EXPENSE, "MYNTRA ORDER 5521");
        purchase.setMerchant("Myntra");
        Transaction refund = txn(card, LocalDate.of(2026, 1, 10).plusDays(185), "4500.00", Transaction.Type.INCOME, "MYNTRA REFUND FOR ORDER");
        refund.setMerchant("Myntra");
        loadTransactions(purchase, refund);

        run();

        assertThat(refund.getReconciliationStatus())
                .as("correctly NOT matched per policy -- the risk is the silence, not this outcome")
                .isEqualTo(Transaction.ReconciliationStatus.OK);
    }

    @Test
    @DisplayName("BASELINE (known-good): a bank-side payment reversal is classified REVERSAL, not REFUND")
    void merchantReversal_classifiedAsReversal() {
        Account savings = account();
        Transaction payment = txn(savings, LocalDate.of(2026, 7, 8), "999.00", Transaction.Type.EXPENSE, "UPI-ELECTRICITY BOARD-PAYMENT");
        Transaction reversal = txn(savings, LocalDate.of(2026, 7, 9), "999.00", Transaction.Type.INCOME, "PAYMENT REVERSED");
        loadTransactions(payment, reversal);

        run();

        assertThat(reversal.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.REVERSAL);
    }

    @Test
    @DisplayName("BASELINE (known-good): a failed-transaction reversal the next day is classified REVERSAL")
    void failedTransactionReversal_nextDay_classifiedAsReversal() {
        Account savings = account();
        Transaction failedDebit = txn(savings, LocalDate.of(2026, 7, 20), "15000.00", Transaction.Type.EXPENSE, "NEFT TO VENDOR XYZ");
        Transaction reversal = txn(savings, LocalDate.of(2026, 7, 21), "15000.00", Transaction.Type.INCOME, "TXN REVERSAL - INSUFFICIENT FUNDS AT BENEFICIARY");
        loadTransactions(failedDebit, reversal);

        run();

        assertThat(reversal.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.REVERSAL);
    }

    @Test
    @DisplayName("BASELINE (documents a taxonomy question, not a defect): a chargeback is classified REFUND, not REVERSAL")
    void chargeback_isClassifiedAsRefund() {
        // "chargeback" sits in REFUND_KEYWORDS, not REVERSAL_KEYWORDS (ReconciliationService's own
        // constant sets). Worth a second look during the roadmap discussion -- a chargeback is a
        // bank/network-adjudicated dispute reversal, arguably closer in kind to "payment reversed"
        // than to a merchant-initiated "returned"/"cancelled" -- but the aggregate effect on totals
        // is identical either way (both REFUND and REVERSAL are excluded from cash flow the same
        // way), so this is a labeling/explainability question, not a correctness one, and is kept
        // here as a BASELINE documenting today's actual, deliberate behavior.
        Account card = cardAccount("5001");
        Transaction disputedCharge = txn(card, LocalDate.of(2026, 7, 1), "12000.00", Transaction.Type.EXPENSE, "SUSPICIOUS MERCHANT LTD");
        Transaction chargeback = txn(card, LocalDate.of(2026, 8, 15), "12000.00", Transaction.Type.INCOME, "CHARGEBACK CREDIT DISPUTE 445521");
        loadTransactions(disputedCharge, chargeback);

        run();

        assertThat(chargeback.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.REFUND);
    }

    @Test
    @DisplayName("BASELINE (known-good): a refund posted a full statement cycle after the purchase still matches")
    void refundAcrossStatementCycles_largeDateGap_stillMatched() {
        Account card = cardAccount("5001");
        // Purchase on the June statement; refund posted on the August statement -- two full billing
        // cycles later. reconcileForUser sees the entire transaction history at once (unlike
        // reconcileForImport's windowed candidate set, which is symmetric ±180 days for exactly
        // this reason -- see ReconciliationPolicy.CANDIDATE_WINDOW_DAYS's own doc comment), so an
        // out-of-cycle refund is not a special case for this pass.
        Transaction purchase = txn(card, LocalDate.of(2026, 6, 3), "7500.00", Transaction.Type.EXPENSE, "MYNTRA ORDER 6001");
        purchase.setMerchant("Myntra");
        Transaction refund = txn(card, LocalDate.of(2026, 8, 12), "7500.00", Transaction.Type.INCOME, "MYNTRA REFUND FOR ORDER");
        refund.setMerchant("Myntra");
        loadTransactions(purchase, refund);

        run();

        assertThat(refund.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.REFUND);
        assertThat(refund.getRefundOfTransactionId()).isEqualTo(purchase.getId());
    }

    @Test
    @DisplayName("BASELINE (documents an attribution risk, not a totals defect): with two same-merchant, same-amount purchases in the window, the refund is silently attributed to whichever is temporally closer")
    void ambiguousMerchant_multipleSameAmountCandidates_attributionIsUnverified() {
        // isCloserRefundMatch (ReconciliationService) ranks candidates by exact-amount-then-date-
        // proximity and never consults reference/order numbers. Both purchases below are equally
        // "exact", so the closer one wins by construction -- deterministic, but with no evidence
        // (order id, item-level detail) that it is the ORDER the refund actually corresponds to.
        // Totals are correct either way (the row is excluded from income regardless of which
        // purchase it's attributed to); what's unverified is the "this refund closes out THIS
        // purchase" explanation a user or support agent would read off the graph.
        Account card = cardAccount("5001");
        Transaction earlierOrder = txn(card, LocalDate.of(2026, 7, 1), "1500.00", Transaction.Type.EXPENSE, "MYNTRA ORDER 1001");
        earlierOrder.setMerchant("Myntra");
        Transaction laterOrder = txn(card, LocalDate.of(2026, 7, 12), "1500.00", Transaction.Type.EXPENSE, "MYNTRA ORDER 1002");
        laterOrder.setMerchant("Myntra");
        // No refund/reversal keyword -- this match is on merchant identity alone, so BOTH orders are
        // equally valid keyword-less candidates.
        Transaction refund = txn(card, LocalDate.of(2026, 7, 15), "1500.00", Transaction.Type.INCOME, "MYNTRA CREDIT NOTE");
        refund.setMerchant("Myntra");
        loadTransactions(earlierOrder, laterOrder, refund);

        run();

        assertThat(refund.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.REFUND);
        assertThat(refund.getRefundOfTransactionId())
                .as("picks the temporally closer order (1002) -- there is no way, from the data "
                        + "available to this pass, to know whether that's actually the order being refunded")
                .isEqualTo(laterOrder.getId());
    }
}
