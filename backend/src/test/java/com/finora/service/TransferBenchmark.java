package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.Transaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reconciliation accuracy benchmark, category 2 of 6: transfers between the user's own accounts.
 * See ReconciliationBenchmarkSupport's own doc comment for how to run this and what a red
 * assertion means.
 *
 * <p>The load-bearing fact behind most of the GAP tests below, read directly off {@code
 * ReconciliationService}'s transfer pass: a candidate pair is only ever evaluated at all if AT
 * LEAST ONE side's normalized description literally contains the substring {@code "payment"}, OR
 * one side matches a user-configured OWN_ACCOUNT relationship identifier. This is a hardcoded
 * literal, not {@code CategoryRules.RULES.get("Transfer")}'s own keyword list ("neft to", "imps
 * to", "autopay", "billdesk", "cc payment", "card bill payment") -- those keywords drive
 * CATEGORY suggestion and are never consulted here. A real UPI/IMPS/NEFT/RTGS self-transfer
 * narrated the way Indian banks actually print it very often contains none of "payment",
 * "neft to", etc. as a literal transfer verb, and most users never configure an OWN_ACCOUNT
 * relationship at all -- which is what most of the GAP scenarios below demonstrate.
 */
class TransferBenchmark extends ReconciliationBenchmarkSupport {

    @Test
    @DisplayName("BASELINE (known-good): same-day credit-card payment, \"payment\" in narration, is matched")
    void sameDayTransfer_withPaymentKeyword_isMatched() {
        Account savings = account();
        Account card = cardAccount("5001");
        Transaction out = txn(savings, LocalDate.of(2026, 7, 10), "15000.00", Transaction.Type.EXPENSE, "CREDIT CARD PAYMENT");
        Transaction in = txn(card, LocalDate.of(2026, 7, 10), "15000.00", Transaction.Type.INCOME, "PAYMENT RECEIVED THANK YOU");
        loadTransactions(out, in);

        run();

        assertThat(out.isTransfer()).isTrue();
        assertThat(in.isTransfer()).isTrue();
        assertThat(out.getTransferPairId()).isEqualTo(in.getId());
        assertThat(in.getTransferPairId()).isEqualTo(out.getId());
    }

    @Test
    @DisplayName("BASELINE (known-good): a 3-day-late settlement, within the default 4-day window, is matched")
    void multiDayTransfer_withinDefaultWindow_isMatched() {
        Account savings = account();
        Account card = cardAccount("5001");
        Transaction out = txn(savings, LocalDate.of(2026, 7, 10), "15000.00", Transaction.Type.EXPENSE, "CREDIT CARD PAYMENT");
        Transaction in = txn(card, LocalDate.of(2026, 7, 13), "15000.00", Transaction.Type.INCOME, "PAYMENT RECEIVED THANK YOU");
        loadTransactions(out, in);

        run();

        assertThat(out.isTransfer()).isTrue();
        assertThat(in.isTransfer()).isTrue();
    }

    @Test
    @DisplayName("BY DESIGN (real settlement delay, no relationship configured): a 6-day settlement is correctly left unmatched")
    void transferBeyondDefaultWindow_noRelationshipConfigured_correctlyNotMatched() {
        // Not a code defect -- ReconciliationPolicy.DEFAULT_TRANSFER_DAY_WINDOW is deliberately 4
        // days precisely to avoid colliding with unrelated activity, and OWN_ACCOUNT_MATCH_DAY_WINDOW
        // (10 days) exists for exactly this case -- but only once the user has configured the
        // relationship. See the roadmap note this benchmark's report attaches to this test: most
        // users never do, so a real 6-day settlement (a long weekend, an inter-bank clearing delay)
        // is invisible without it.
        Account savings = account();
        Account card = cardAccount("5001");
        Transaction out = txn(savings, LocalDate.of(2026, 7, 10), "8000.00", Transaction.Type.EXPENSE, "CREDIT CARD PAYMENT");
        Transaction in = txn(card, LocalDate.of(2026, 7, 16), "8000.00", Transaction.Type.INCOME, "PAYMENT RECEIVED THANK YOU");
        loadTransactions(out, in);

        run();

        assertThat(out.isTransfer()).isFalse();
        assertThat(in.isTransfer()).isFalse();
    }

    @Test
    @DisplayName("BASELINE (known-good): with an OWN_ACCOUNT relationship configured, a 7-day settlement matches via the widened window")
    void ownAccountTransfer_withConfiguredRelationship_widenedWindowMatches() {
        Account savings = account();
        Account current = account();
        ownAccountIdentifiers("9876543210"); // synthetic-ok
        Transaction out = txn(savings, LocalDate.of(2026, 7, 10), "8000.00", Transaction.Type.EXPENSE, "NEFT TO 9876543210 SELF"); // synthetic-ok
        Transaction in = txn(current, LocalDate.of(2026, 7, 17), "8000.00", Transaction.Type.INCOME, "NEFT CR FROM SAVINGS");
        loadTransactions(out, in);

        run();

        assertThat(out.isTransfer())
                .as("7 days is beyond the 4-day default but inside the 10-day OWN_ACCOUNT_MATCH_DAY_WINDOW")
                .isTrue();
        assertThat(in.isTransfer()).isTrue();
    }

    @Test
    @DisplayName("GAP: a UPI self-transfer with no \"payment\" wording and no relationship configured is missed")
    void upiSelfTransfer_noPaymentKeyword_noRelationshipConfigured_missed() {
        Account savings = account();
        Account wallet = account();
        Transaction out = txn(savings, LocalDate.of(2026, 7, 14), "2000.00", Transaction.Type.EXPENSE, "UPI-SELF TRANSFER-SBIN0001234@ybl"); // synthetic-ok
        Transaction in = txn(wallet, LocalDate.of(2026, 7, 14), "2000.00", Transaction.Type.INCOME, "UPI/CR/SELF TRANSFER");
        loadTransactions(out, in);

        run();

        assertThat(out.isTransfer())
                .as("GAP: this is a real same-day self-transfer, but neither narration contains "
                        + "\"payment\" and no OWN_ACCOUNT relationship is configured -- the transfer "
                        + "pass never even evaluates the pair. Both legs count as real spend and real "
                        + "income today.")
                .isTrue();
    }

    @Test
    @DisplayName("GAP: an IMPS transfer narrated without \"payment\" is missed")
    void impsTransfer_noPaymentKeyword_missed() {
        Account savings = account();
        Account current = account();
        Transaction out = txn(savings, LocalDate.of(2026, 7, 8), "12000.00", Transaction.Type.EXPENSE, "IMPS TO 9876 SAVINGS AC");
        Transaction in = txn(current, LocalDate.of(2026, 7, 8), "12000.00", Transaction.Type.INCOME, "IMPS FROM CURRENT AC");
        loadTransactions(out, in);

        run();

        assertThat(out.isTransfer())
                .as("GAP: real IMPS self-transfer, same day, same amount -- missed because neither "
                        + "narration contains \"payment\" and no relationship identifier is configured")
                .isTrue();
    }

    @Test
    @DisplayName("GAP: a NEFT transfer narrated without \"payment\" is missed")
    void neftTransfer_noPaymentKeyword_missed() {
        Account savings = account();
        Account current = account();
        Transaction out = txn(savings, LocalDate.of(2026, 7, 9), "25000.00", Transaction.Type.EXPENSE, "NEFT TO SELF AC XX1234");
        Transaction in = txn(current, LocalDate.of(2026, 7, 10), "25000.00", Transaction.Type.INCOME, "NEFT CR SELF");
        loadTransactions(out, in);

        run();

        assertThat(out.isTransfer())
                .as("GAP: NEFT is one of CategoryRules' own Transfer-category keywords (\"neft to\") "
                        + "for CATEGORIZATION, but ReconciliationService's transfer pass never reads "
                        + "that list -- it only ever checks the literal substring \"payment\"")
                .isTrue();
    }

    @Test
    @DisplayName("GAP: an RTGS transfer narrated without \"payment\" is missed")
    void rtgsTransfer_noPaymentKeyword_missed() {
        Account savings = account();
        Account current = account();
        Transaction out = txn(savings, LocalDate.of(2026, 7, 11), "500000.00", Transaction.Type.EXPENSE, "RTGS OUT TO OWN AC");
        Transaction in = txn(current, LocalDate.of(2026, 7, 11), "500000.00", Transaction.Type.INCOME, "RTGS CREDIT");
        loadTransactions(out, in);

        run();

        assertThat(out.isTransfer())
                .as("GAP: a large RTGS self-transfer (RTGS is typically used for high-value transfers) "
                        + "counted as real spend AND real income -- the false positive on both totals is "
                        + "proportionally larger here than a small UPI miss")
                .isTrue();
    }

    @Test
    @DisplayName("BASELINE (known-good): savings-to-current transfer whose narration happens to say \"payment\" is matched")
    void savingsToCurrentTransfer_withPaymentKeyword_isMatched() {
        Account savings = account();
        Account current = account();
        Transaction out = txn(savings, LocalDate.of(2026, 7, 12), "30000.00", Transaction.Type.EXPENSE, "FUNDS TRANSFER PAYMENT TO CURRENT AC");
        Transaction in = txn(current, LocalDate.of(2026, 7, 12), "30000.00", Transaction.Type.INCOME, "FUNDS TRANSFER PAYMENT RECEIVED");
        loadTransactions(out, in);

        run();

        assertThat(out.isTransfer()).isTrue();
        assertThat(in.isTransfer()).isTrue();
    }

    @Test
    @DisplayName("GAP: a savings-to-credit-card auto-debit narrated without \"payment\" is missed by the transfer pass")
    void savingsToCreditCardAutoDebit_noPaymentKeyword_missed() {
        Account savings = account();
        Account card = cardAccount("5001");
        Transaction out = txn(savings, LocalDate.of(2026, 7, 15), "18000.00", Transaction.Type.EXPENSE, "ECS AUTO DEBIT-CARD BILL");
        Transaction in = txn(card, LocalDate.of(2026, 7, 15), "18000.00", Transaction.Type.INCOME, "AUTO DEBIT SETTLED");
        loadTransactions(out, in);

        run();

        assertThat(out.isTransfer())
                .as("GAP: same shape as every other missed transfer above, on a card-payment auto-debit "
                        + "specifically -- worth flagging separately because this account pair is also "
                        + "in scope for the dedicated CC_PAYMENT pass, which the credit-card-payments "
                        + "benchmark tests independently")
                .isTrue();
    }

    @Test
    @DisplayName("GAP: wallet funding narrated without \"payment\" is missed")
    void walletFunding_noPaymentKeyword_missed() {
        Account savings = account();
        Account wallet = account();
        Transaction out = txn(savings, LocalDate.of(2026, 7, 5), "1000.00", Transaction.Type.EXPENSE, "ADDED MONEY TO PAYTM WALLET");
        Transaction in = txn(wallet, LocalDate.of(2026, 7, 5), "1000.00", Transaction.Type.INCOME, "PAYTM WALLET LOAD");
        loadTransactions(out, in);

        run();

        assertThat(out.isTransfer())
                .as("GAP: wallet top-ups are a very common own-money movement with no \"payment\" wording")
                .isTrue();
    }

    @Test
    @DisplayName("GAP (first-match-wins): a coincidental same-amount credit earlier in the window is matched over the real, later transfer leg")
    void multipleCandidateMatches_firstMatchWins_picksTheWrongCandidate() {
        Account savings = account();
        Account wallet = account();
        Transaction debit = txn(savings, LocalDate.of(2026, 7, 15), "5000.00", Transaction.Type.EXPENSE, "FUNDS TRANSFER PAYMENT TO WALLET");
        // Coincidental, unrelated credit: a cashback that happens to land on the wallet account 3
        // days BEFORE the real debit, for the same round amount -- plausible, since ₹5,000 is a
        // common round transfer amount.
        Transaction coincidentalCashback = txn(wallet, LocalDate.of(2026, 7, 12), "5000.00", Transaction.Type.INCOME, "CASHBACK CREDIT ORDER 771");
        // The real transfer leg: the wallet credit that actually corresponds to the debit above,
        // landing the next day.
        Transaction realWalletCredit = txn(wallet, LocalDate.of(2026, 7, 16), "5000.00", Transaction.Type.INCOME, "WALLET LOAD FROM BANK");
        loadTransactions(debit, coincidentalCashback, realWalletCredit);

        run();

        assertThat(debit.getTransferPairId())
                .as("GAP: candidates are scanned in ascending (txnDate, id) order and the loop takes "
                        + "the FIRST amount/window match, not the closest or most plausible one -- the "
                        + "earlier-dated coincidental cashback (3 days before the debit) is examined "
                        + "before the real credit (1 day after) and wins purely by sort order. The real "
                        + "transfer leg (realWalletCredit) is left as ordinary, uncounted income, and an "
                        + "unrelated cashback is wrongly excluded from income totals as a \"transfer\".")
                .isEqualTo(realWalletCredit.getId());
    }

    @Test
    @DisplayName("GAP (ambiguous match): two possible expense legs for one income credit; the temporally closer, correct one is never reached")
    void ambiguousTransferSelection_pickCloserCandidateOverFartherOne() {
        Account savings = account();
        Account checking = account();
        Account card = cardAccount("5001");
        // Two unrelated-looking expenses of the same amount, neither one itself carrying "payment"
        // wording (so neither triggers the pass on its own as `a`) -- only the incoming card credit
        // does.
        Transaction coincidentalExpense = txn(checking, LocalDate.of(2026, 7, 12), "3000.00", Transaction.Type.EXPENSE, "ATM WITHDRAWAL");
        Transaction realExpense = txn(savings, LocalDate.of(2026, 7, 14), "3000.00", Transaction.Type.EXPENSE, "FUNDS MOVED TO CARD AC");
        Transaction cardCredit = txn(card, LocalDate.of(2026, 7, 15), "3000.00", Transaction.Type.INCOME, "PAYMENT RECEIVED THANK YOU");
        loadTransactions(coincidentalExpense, realExpense, cardCredit);

        run();

        assertThat(cardCredit.getTransferPairId())
                .as("GAP: the coincidental ATM withdrawal (3 days before the credit) is examined before "
                        + "the real source expense (1 day before) purely because it sorts earlier -- same "
                        + "root cause as the income-side ambiguity above, demonstrated from the expense side")
                .isEqualTo(realExpense.getId());
    }

    @Test
    @DisplayName("GAP: a transfer split into two partial debits against one lump-sum credit is not matched at all")
    void splitTransfer_twoPartialDebitsOneLumpCredit_notMatchedAtAll() {
        Account savings = account();
        Account wallet = account();
        Transaction debit1 = txn(savings, LocalDate.of(2026, 7, 20), "5000.00", Transaction.Type.EXPENSE, "FUNDS TRANSFER PAYMENT TO WALLET");
        Transaction debit2 = txn(savings, LocalDate.of(2026, 7, 20), "5000.00", Transaction.Type.EXPENSE, "FUNDS TRANSFER PAYMENT TO WALLET");
        Transaction lumpCredit = txn(wallet, LocalDate.of(2026, 7, 20), "10000.00", Transaction.Type.INCOME, "WALLET LOAD FROM BANK");
        loadTransactions(debit1, debit2, lumpCredit);

        run();

        assertThat(debit1.isTransfer())
                .as("GAP: the matching engine only ever compares one debit to one credit within a "
                        + "₹1 tolerance -- there is no many-to-one aggregation, so a transfer split "
                        + "across two app-side transactions but settled as one bank-side credit (or vice "
                        + "versa) is invisible however narrated. Both ₹5,000 debits count as real spend "
                        + "and the ₹10,000 credit counts as real, unexplained income.")
                .isTrue();
        assertThat(lumpCredit.isTransfer()).isTrue();
    }

    @Test
    @DisplayName("GAP: a realistic IMPS/NEFT transfer fee (₹20) exceeds the ₹1 amount tolerance and is not matched")
    void transferWithRealisticFee_exceedsOneRupeeTolerance_notMatched() {
        // ReconciliationPolicy.TRANSFER_AMOUNT_TOLERANCE's own doc comment justifies a non-zero
        // tolerance by name-checking exactly this case ("a small fee is deducted in transit"), but
        // sets the bound at ₹1 -- tighter than a real transfer-rail fee, which commonly runs several
        // rupees to tens of rupees (IMPS/NEFT fees plus GST).
        Account savings = account();
        Account current = account();
        Transaction out = txn(savings, LocalDate.of(2026, 7, 18), "10000.00", Transaction.Type.EXPENSE, "NEFT PAYMENT TO SELF AC");
        Transaction in = txn(current, LocalDate.of(2026, 7, 18), "9980.00", Transaction.Type.INCOME, "NEFT CREDIT PAYMENT");
        loadTransactions(out, in);

        run();

        assertThat(out.isTransfer())
                .as("GAP: ₹20 fee (a realistic NEFT/IMPS charge) is 20x the ₹1 tolerance -- the policy's "
                        + "own stated rationale for having a tolerance at all does not survive contact "
                        + "with a real transfer-rail fee amount")
                .isTrue();
    }

    @Test
    @DisplayName("BASELINE (known-good): a sub-rupee rounding difference is within tolerance and matches")
    void transferWithRoundingDifference_withinTolerance_isMatched() {
        Account savings = account();
        Account current = account();
        Transaction out = txn(savings, LocalDate.of(2026, 7, 19), "10000.00", Transaction.Type.EXPENSE, "FUNDS TRANSFER PAYMENT");
        Transaction in = txn(current, LocalDate.of(2026, 7, 19), "9999.60", Transaction.Type.INCOME, "FUNDS TRANSFER PAYMENT RECEIVED");
        loadTransactions(out, in);

        run();

        assertThat(out.isTransfer()).isTrue();
        assertThat(in.isTransfer()).isTrue();
    }

    @Test
    @DisplayName("BASELINE (known-good): two independent recurring monthly transfers stay correctly scoped to their own month")
    void recurringMonthlyTransfer_julyAndAugustPairsIndependentlyMatched() {
        Account savings = account();
        Account rd = account();
        Transaction julyOut = txn(savings, LocalDate.of(2026, 7, 5), "5000.00", Transaction.Type.EXPENSE, "RD INSTALLMENT PAYMENT");
        Transaction julyIn = txn(rd, LocalDate.of(2026, 7, 6), "5000.00", Transaction.Type.INCOME, "RD PAYMENT RECEIVED");
        Transaction augOut = txn(savings, LocalDate.of(2026, 8, 5), "5000.00", Transaction.Type.EXPENSE, "RD INSTALLMENT PAYMENT");
        Transaction augIn = txn(rd, LocalDate.of(2026, 8, 6), "5000.00", Transaction.Type.INCOME, "RD PAYMENT RECEIVED");
        loadTransactions(julyOut, julyIn, augOut, augIn);

        run();

        assertThat(julyOut.getTransferPairId()).isEqualTo(julyIn.getId());
        assertThat(augOut.getTransferPairId()).isEqualTo(augIn.getId());
    }
}
