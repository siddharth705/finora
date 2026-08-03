package com.finora.accounts;

import com.finora.entity.Account;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

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
