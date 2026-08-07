package com.finora.accounts;

import com.finora.entity.Account;
import com.finora.entity.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccountBalanceConventionTest {

    @Test
    void anAssetContributesItsBalanceAndALiabilityContributesTheNegation() {
        assertThat(AccountBalanceConvention.netWorthContribution(Account.Type.SAVINGS, new BigDecimal("1000")))
                .isEqualByComparingTo("1000");
        assertThat(AccountBalanceConvention.netWorthContribution(Account.Type.INVESTMENT, new BigDecimal("500")))
                .isEqualByComparingTo("500");
        assertThat(AccountBalanceConvention.netWorthContribution(Account.Type.CREDIT_CARD, new BigDecimal("2000")))
                .as("money owed reduces net worth")
                .isEqualByComparingTo("-2000");
    }

    @Test
    void anOverdrawnAccountReducesNetWorthWithNoSpecialHandling() {
        // Why overdrafts need no rule of their own despite being borrowing: a negative asset
        // balance already subtracts.
        assertThat(AccountBalanceConvention.netWorthContribution(Account.Type.SAVINGS, new BigDecimal("-3500")))
                .isEqualByComparingTo("-3500");
    }

    @Test
    void aCreditBalanceOnACardIncreasesNetWorth() {
        // An overpayment or a late refund leaves the issuer owing the customer. The old
        // assets-minus-liabilities form got this right only by coincidence; stating it as a
        // contribution makes it deliberate.
        assertThat(AccountBalanceConvention.netWorthContribution(Account.Type.CREDIT_CARD, new BigDecimal("-1200")))
                .isEqualByComparingTo("1200");
    }

    @Test
    void aNullBalanceContributesNothingRatherThanThrowing() {
        assertThat(AccountBalanceConvention.netWorthContribution(Account.Type.SAVINGS, null))
                .isEqualByComparingTo("0");
    }

    @Test
    void onlyCreditCardsAreLiabilities() {
        assertThat(AccountBalanceConvention.isLiability(Account.Type.CREDIT_CARD)).isTrue();
        for (Account.Type type : Account.Type.values()) {
            if (type == Account.Type.CREDIT_CARD) continue;
            assertThat(AccountBalanceConvention.isLiability(type))
                    .as(type + " holds money rather than owing it")
                    .isFalse();
        }
    }

    @Test
    void balanceDeltaFollowsTheTypeNotTheAmountSign() {
        // Bug 17's core rule, now shared rather than private to TransactionService. An asset
        // account gains on INCOME and loses on EXPENSE.
        assertThat(AccountBalanceConvention.balanceDelta(
                Account.Type.SAVINGS, Transaction.Type.INCOME, new BigDecimal("500")))
                .isEqualByComparingTo("500");
        assertThat(AccountBalanceConvention.balanceDelta(
                Account.Type.SAVINGS, Transaction.Type.EXPENSE, new BigDecimal("500")))
                .isEqualByComparingTo("-500");
    }

    @Test
    void aCardPurchaseIncreasesWhatIsOwedAndAPaymentReducesIt() {
        // The inversion that must never be re-derived by hand: getting it backwards on the import
        // path would corrupt a card's balance by twice the statement total, silently.
        assertThat(AccountBalanceConvention.balanceDelta(
                Account.Type.CREDIT_CARD, Transaction.Type.EXPENSE, new BigDecimal("300")))
                .isEqualByComparingTo("300");
        assertThat(AccountBalanceConvention.balanceDelta(
                Account.Type.CREDIT_CARD, Transaction.Type.INCOME, new BigDecimal("300")))
                .isEqualByComparingTo("-300");
    }

    @Test
    void aNegativelySignedAmountCannotDoubleInvertTheConvention() {
        // Direction is carried by the transaction TYPE alone. An amount that arrives signed must
        // not flip it a second time -- that is how an EXPENSE of -500 would ADD 500 to a balance.
        assertThat(AccountBalanceConvention.balanceDelta(
                Account.Type.SAVINGS, Transaction.Type.EXPENSE, new BigDecimal("-500")))
                .isEqualByComparingTo("-500");
    }

    @Test
    void netDeltaSumsABatchAndIsExactlyReversibleByNegation() {
        Transaction fare = new Transaction();
        fare.setTxnType(Transaction.Type.EXPENSE);
        fare.setAmount(new BigDecimal("45.00"));
        Transaction salary = new Transaction();
        salary.setTxnType(Transaction.Type.INCOME);
        salary.setAmount(new BigDecimal("500.00"));

        BigDecimal net = AccountBalanceConvention.netDelta(Account.Type.SAVINGS, List.of(fare, salary));

        assertThat(net).isEqualByComparingTo("455.00");
        // The property StatementImportService.delete depends on: applying then reversing a batch
        // returns to the starting point, so an import/delete cycle cannot drift.
        assertThat(net.add(net.negate())).isEqualByComparingTo("0");
    }

    @Test
    void netDeltaOfNothingIsZeroRatherThanNull() {
        assertThat(AccountBalanceConvention.netDelta(Account.Type.SAVINGS, List.of()))
                .isEqualByComparingTo("0");
        assertThat(AccountBalanceConvention.netDelta(Account.Type.SAVINGS, null))
                .isEqualByComparingTo("0");
    }

    @Test
    void aNullAmountOrTypeContributesNothingRatherThanThrowing() {
        assertThat(AccountBalanceConvention.balanceDelta(
                Account.Type.SAVINGS, Transaction.Type.EXPENSE, null)).isEqualByComparingTo("0");
        assertThat(AccountBalanceConvention.balanceDelta(
                Account.Type.SAVINGS, null, new BigDecimal("10"))).isEqualByComparingTo("0");
    }

    @Test
    void aNegativeCardBalanceIsFlaggedRatherThanCorrected() {
        // Flagged, not flipped: whether a statement's balance column expresses a card's outstanding
        // as positive or negative varies by issuer, and no fixture in the corpus exercises it.
        // Correcting a number whose provenance is unknown is how a wrong figure becomes an
        // authoritative one.
        assertThat(AccountBalanceConvention.looksLikeASignError(Account.Type.CREDIT_CARD, new BigDecimal("-2000")))
                .isTrue();
        assertThat(AccountBalanceConvention.looksLikeASignError(Account.Type.CREDIT_CARD, new BigDecimal("2000")))
                .isFalse();
        assertThat(AccountBalanceConvention.looksLikeASignError(Account.Type.SAVINGS, new BigDecimal("-2000")))
                .as("an overdrawn savings account is ordinary, not a sign error")
                .isFalse();
    }
}
