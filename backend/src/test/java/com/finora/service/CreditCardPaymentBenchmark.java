package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.entity.TransactionRelationship;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reconciliation accuracy benchmark, category 6 of 6: credit card payment matching. See
 * ReconciliationBenchmarkSupport's own doc comment for how to run this and what a red assertion
 * means.
 *
 * <p>Every edge this pass writes is {@code CANDIDATE} status unconditionally (see
 * ReconciliationService's own comment on {@code matchCcStatement} for why AUTO_CONFIRMED is out of
 * scope for this pass) -- these tests check WHICH payment gets linked to WHICH charges, not the
 * edge's status.
 */
class CreditCardPaymentBenchmark extends ReconciliationBenchmarkSupport {

    @Test
    @DisplayName("BASELINE (known-good): an exact-amount payment on the due date is linked to every settled charge")
    void exactPayment_isMatchedToEveryCharge() {
        Account savings = account();
        Account card = cardAccount("5001");
        Transaction charge1 = txn(card, LocalDate.of(2026, 6, 20), "1500.00", Transaction.Type.EXPENSE, "AMAZON");
        Transaction charge2 = txn(card, LocalDate.of(2026, 6, 25), "500.00", Transaction.Type.EXPENSE, "SWIGGY");
        Transaction payment = txn(savings, LocalDate.of(2026, 7, 15), "2000.00", Transaction.Type.EXPENSE, "AUTOPAY CARD BILL");
        StatementImport statement = ccStatement(card, "2000.00", LocalDate.of(2026, 7, 15), charge1, charge2);
        loadTransactions(charge1, charge2, payment);

        run();

        assertThat(edgeBetween(payment, charge1, TransactionRelationship.RelationshipType.CC_PAYMENT)).isNotNull();
        assertThat(edgeBetween(payment, charge2, TransactionRelationship.RelationshipType.CC_PAYMENT)).isNotNull();
    }

    @Test
    @DisplayName("BASELINE (known-good): a minimum-due-only payment (5% of the statement) is matched as a partial settlement")
    void underpayment_minimumDueRatio_isMatchedAsPartial() {
        Account savings = account();
        Account card = cardAccount("5001");
        Transaction charge = txn(card, LocalDate.of(2026, 6, 20), "8000.00", Transaction.Type.EXPENSE, "FLIGHT BOOKING");
        Transaction minimumDuePayment = txn(savings, LocalDate.of(2026, 7, 15), "400.00", Transaction.Type.EXPENSE, "AUTOPAY CARD BILL");
        ccStatement(card, "8000.00", LocalDate.of(2026, 7, 15), charge);
        loadTransactions(charge, minimumDuePayment);

        run();

        var edge = edgeBetween(minimumDuePayment, charge, TransactionRelationship.RelationshipType.CC_PAYMENT);
        assertThat(edge.explanation()).containsEntry("paymentStatus", "PARTIAL");
    }

    @Test
    @DisplayName("BASELINE (known-good): a 2x overpayment (clearing an old balance too) is matched as an overpayment")
    void overpayment_withinCeiling_isMatchedAsOverpaid() {
        Account savings = account();
        Account card = cardAccount("5001");
        Transaction charge = txn(card, LocalDate.of(2026, 6, 20), "5000.00", Transaction.Type.EXPENSE, "ELECTRONICS STORE");
        Transaction overpayment = txn(savings, LocalDate.of(2026, 7, 15), "10000.00", Transaction.Type.EXPENSE, "AUTOPAY CARD BILL");
        ccStatement(card, "5000.00", LocalDate.of(2026, 7, 15), charge);
        loadTransactions(charge, overpayment);

        run();

        var edge = edgeBetween(overpayment, charge, TransactionRelationship.RelationshipType.CC_PAYMENT);
        assertThat(edge.explanation()).containsEntry("paymentStatus", "OVERPAID");
    }

    @Test
    @DisplayName("BASELINE (known-good): with two cards due close together, an exact match for one is never stolen by the other's wider partial/overpayment search")
    void twoCardsWithSimilarDues_exactMatchResolvedGloballyFirst_noCrossCardCollision() {
        Account savings = account();
        Account cardA = cardAccount("1111");
        Account cardB = cardAccount("2222");
        Transaction chargeA = txn(cardA, LocalDate.of(2026, 6, 20), "1000.00", Transaction.Type.EXPENSE, "CARD A PURCHASE");
        Transaction chargeB = txn(cardB, LocalDate.of(2026, 6, 21), "1050.00", Transaction.Type.EXPENSE, "CARD B PURCHASE");
        // Exactly settles cardA; also happens to sit inside cardB's 0.05-2.5x ratio window
        // (1000/1050 ≈ 0.95) -- the two-phase design (exact resolved globally before any partial/
        // overpayment search runs) exists precisely so cardB's wider search can't claim it first.
        Transaction payment = txn(savings, LocalDate.of(2026, 7, 10), "1000.00", Transaction.Type.EXPENSE, "NEFT TRANSFER");
        ccStatement(cardA, "1000.00", LocalDate.of(2026, 7, 10), chargeA);
        ccStatement(cardB, "1050.00", LocalDate.of(2026, 7, 12), chargeB);
        loadTransactions(chargeA, chargeB, payment);

        run();

        assertThat(edgeBetween(payment, chargeA, TransactionRelationship.RelationshipType.CC_PAYMENT)).isNotNull();
        assertThat(capturedEdges())
                .as("cardB must find nothing -- its only possible candidate was already claimed by cardA's exact match")
                .noneMatch(e -> e.toTransactionId().equals(chargeB.getId()) || e.fromTransactionId().equals(chargeB.getId()));
    }

    @Test
    @DisplayName("BASELINE (known-good): a payment made 5 days before the due date (early auto-pay) is matched")
    void earlyPayment_fiveDaysBeforeDue_isMatched() {
        Account savings = account();
        Account card = cardAccount("5001");
        Transaction charge = txn(card, LocalDate.of(2026, 6, 20), "3000.00", Transaction.Type.EXPENSE, "GROCERY STORE");
        Transaction payment = txn(savings, LocalDate.of(2026, 7, 10), "3000.00", Transaction.Type.EXPENSE, "AUTOPAY CARD BILL");
        ccStatement(card, "3000.00", LocalDate.of(2026, 7, 15), charge);
        loadTransactions(charge, payment);

        run();

        assertThat(edgeBetween(payment, charge, TransactionRelationship.RelationshipType.CC_PAYMENT)).isNotNull();
    }

    @Test
    @DisplayName("BASELINE (known-good): a payment made 8 days after the due date is matched")
    void latePayment_eightDaysAfterDue_isMatched() {
        Account savings = account();
        Account card = cardAccount("5001");
        Transaction charge = txn(card, LocalDate.of(2026, 6, 20), "3000.00", Transaction.Type.EXPENSE, "GROCERY STORE");
        Transaction payment = txn(savings, LocalDate.of(2026, 7, 23), "3000.00", Transaction.Type.EXPENSE, "AUTOPAY CARD BILL");
        ccStatement(card, "3000.00", LocalDate.of(2026, 7, 15), charge);
        loadTransactions(charge, payment);

        run();

        assertThat(edgeBetween(payment, charge, TransactionRelationship.RelationshipType.CC_PAYMENT)).isNotNull();
    }

    @Test
    @DisplayName("GAP: a payment made 12 days after the due date (a genuinely delinquent, real-world late payment) is missed and the charge is double-counted")
    void latePayment_beyondTenDayWindow_missedAndDoubleCounted() {
        // CC_PAYMENT_DUE_DATE_WINDOW_DAYS is 10 (reusing OWN_ACCOUNT_MATCH_DAY_WINDOW's own
        // justification: "auto-pay early... or a few days late"). A person who is genuinely late --
        // exactly the situation where a late fee applies and accurate tracking matters most -- falls
        // outside it. Unlike the refund-window and ambiguous-attribution findings elsewhere in this
        // benchmark, this one has a real totals impact: the charge stays counted as ordinary card
        // spend AND the payment stays counted as ordinary savings-side spend -- the same real money
        // effectively counted twice.
        Account savings = account();
        Account card = cardAccount("5001");
        Transaction charge = txn(card, LocalDate.of(2026, 6, 20), "3000.00", Transaction.Type.EXPENSE, "GROCERY STORE");
        Transaction payment = txn(savings, LocalDate.of(2026, 7, 27), "3000.00", Transaction.Type.EXPENSE, "AUTOPAY CARD BILL");
        ccStatement(card, "3000.00", LocalDate.of(2026, 7, 15), charge);
        loadTransactions(charge, payment);

        run();

        assertThat(capturedEdges())
                .as("GAP: 12 days past due is outside CC_PAYMENT_DUE_DATE_WINDOW_DAYS (10) -- no edge "
                        + "links the payment to the charge it actually settled")
                .anyMatch(e -> e.relationshipType() == TransactionRelationship.RelationshipType.CC_PAYMENT
                        && (e.fromTransactionId().equals(payment.getId()) || e.toTransactionId().equals(payment.getId())));
    }

    @Test
    @DisplayName("BASELINE (known-good): three cards paid in the same run are each independently and correctly settled")
    void multipleCardAccounts_eachIndependentlySettled() {
        Account savings = account();
        Account cardA = cardAccount("1111");
        Account cardB = cardAccount("2222");
        Account cardC = cardAccount("3333");
        Transaction chargeA = txn(cardA, LocalDate.of(2026, 6, 20), "1000.00", Transaction.Type.EXPENSE, "CARD A PURCHASE");
        Transaction chargeB = txn(cardB, LocalDate.of(2026, 6, 21), "2000.00", Transaction.Type.EXPENSE, "CARD B PURCHASE");
        Transaction chargeC = txn(cardC, LocalDate.of(2026, 6, 22), "3000.00", Transaction.Type.EXPENSE, "CARD C PURCHASE");
        Transaction paymentA = txn(savings, LocalDate.of(2026, 7, 10), "1000.00", Transaction.Type.EXPENSE, "AUTOPAY CARD BILL A");
        Transaction paymentB = txn(savings, LocalDate.of(2026, 7, 11), "2000.00", Transaction.Type.EXPENSE, "AUTOPAY CARD BILL B");
        Transaction paymentC = txn(savings, LocalDate.of(2026, 7, 12), "3000.00", Transaction.Type.EXPENSE, "AUTOPAY CARD BILL C");
        ccStatement(cardA, "1000.00", LocalDate.of(2026, 7, 10), chargeA);
        ccStatement(cardB, "2000.00", LocalDate.of(2026, 7, 11), chargeB);
        ccStatement(cardC, "3000.00", LocalDate.of(2026, 7, 12), chargeC);
        loadTransactions(chargeA, chargeB, chargeC, paymentA, paymentB, paymentC);

        run();

        assertThat(edgeBetween(paymentA, chargeA, TransactionRelationship.RelationshipType.CC_PAYMENT)).isNotNull();
        assertThat(edgeBetween(paymentB, chargeB, TransactionRelationship.RelationshipType.CC_PAYMENT)).isNotNull();
        assertThat(edgeBetween(paymentC, chargeC, TransactionRelationship.RelationshipType.CC_PAYMENT)).isNotNull();
    }

    @Test
    @DisplayName("BASELINE (known-good): a payment naming a card's last-4 digits wins over a closer-to-due-date but unlabeled one, and never crosses over to the wrong card")
    void cardNumberFragmentMatching_correctlyDisambiguatesTwoCompetingCards() {
        Account savings = account();
        Account cardA = cardAccount("5001");
        Account cardB = cardAccount("7777");
        Transaction chargeA = txn(cardA, LocalDate.of(2026, 6, 20), "2000.00", Transaction.Type.EXPENSE, "SOME CARD A PURCHASE");
        Transaction chargeB = txn(cardB, LocalDate.of(2026, 6, 21), "999.00", Transaction.Type.EXPENSE, "SOME CARD B PURCHASE");
        // Closer to cardA's due date than paymentLabeled, but carries no card evidence at all.
        Transaction paymentCloserUnlabeled = txn(savings, LocalDate.of(2026, 7, 16), "2000.00", Transaction.Type.EXPENSE, null);
        // Explicitly names cardA's last-4 -- must win the tiebreak over paymentCloserUnlabeled
        // despite being 5 days further from the due date.
        Transaction paymentNamesCardA = txn(savings, LocalDate.of(2026, 7, 20), "2000.00", Transaction.Type.EXPENSE, "AUTOPAY CARD BILL XXXX5001");
        // Same amount as cardA's total, but explicitly names cardB -- must be excluded as a
        // candidate for cardA entirely, and instead settle cardB (as an overpayment there).
        Transaction paymentNamesCardB = txn(savings, LocalDate.of(2026, 7, 15), "2000.00", Transaction.Type.EXPENSE, "AUTOPAY CARD BILL XXXX7777");
        ccStatement(cardA, "2000.00", LocalDate.of(2026, 7, 15), chargeA);
        ccStatement(cardB, "999.00", LocalDate.of(2026, 7, 15), chargeB);
        loadTransactions(chargeA, chargeB, paymentCloserUnlabeled, paymentNamesCardA, paymentNamesCardB);

        run();

        assertThat(edgeBetween(paymentNamesCardA, chargeA, TransactionRelationship.RelationshipType.CC_PAYMENT)).isNotNull();
        assertThat(edgeBetween(paymentNamesCardB, chargeB, TransactionRelationship.RelationshipType.CC_PAYMENT)).isNotNull();
        assertThat(capturedEdges())
                .as("the unlabeled, merely-closer-to-due-date payment must never be chosen once real card evidence exists")
                .noneMatch(e -> e.fromTransactionId().equals(paymentCloserUnlabeled.getId())
                        || e.toTransactionId().equals(paymentCloserUnlabeled.getId()));
    }

    @Test
    @DisplayName("BASELINE (documents an attribution risk, not a totals defect): with no card reference in the description, a payment plausible for two cards is silently attributed to whichever card is processed first")
    void ambiguousPaymentDescription_noCardReference_attributionDecidedByProcessingOrderNotEvidence() {
        // Neither card's total is an exact match for this payment, and neither payment description
        // carries any last-4 evidence -- the two candidate cards are evaluated in due-date order and
        // the payment is claimed by whichever is tried first, with no comparison against the other
        // card's equally plausible claim. The payment IS correctly excluded from spend either way
        // (that part is right); WHICH card's balance is treated as settled is what's unverified.
        Account savings = account();
        Account cardA = cardAccount("1111");
        Account cardB = cardAccount("2222");
        Transaction chargeA = txn(cardA, LocalDate.of(2026, 6, 20), "2500.00", Transaction.Type.EXPENSE, "CARD A PURCHASE");
        Transaction chargeB = txn(cardB, LocalDate.of(2026, 6, 21), "2600.00", Transaction.Type.EXPENSE, "CARD B PURCHASE");
        Transaction ambiguousPayment = txn(savings, LocalDate.of(2026, 7, 13), "3000.00", Transaction.Type.EXPENSE, null);
        ccStatement(cardA, "2500.00", LocalDate.of(2026, 7, 10), chargeA);
        ccStatement(cardB, "2600.00", LocalDate.of(2026, 7, 16), chargeB);
        loadTransactions(chargeA, chargeB, ambiguousPayment);

        run();

        assertThat(edgeBetween(ambiguousPayment, chargeA, TransactionRelationship.RelationshipType.CC_PAYMENT))
                .as("cardA is processed first (earlier due date) and claims the only candidate payment "
                        + "outright -- cardB, an equally plausible match (both within ratio range, both "
                        + "3 days from their own due date), is never even considered")
                .isNotNull();
    }
}
