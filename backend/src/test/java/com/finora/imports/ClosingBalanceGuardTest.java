package com.finora.imports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.finora.entity.Account;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug 02. The guard that stops a request-body number being assigned to {@code Account.balance}
 * without the imported transactions agreeing with it.
 *
 * <p>The cases that matter are the two failure directions, not the happy path: refusing a
 * corroborated balance would break every ordinary import, and accepting an uncorroborated one is
 * the bug itself.
 */
class ClosingBalanceGuardTest {

    /** Money HELD. Named rather than inlined so the liability cases below read as the contrast
     *  they are, and so a reader can see at a glance which convention each case is asserting. */
    private static final Account.Type SAVINGS = Account.Type.SAVINGS;

    /** Money OWED — the convention this guard used to get backwards for every card statement. */
    private static final Account.Type CREDIT_CARD = Account.Type.CREDIT_CARD;

    private static BigDecimal money(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("a closing balance the imported rows actually reach is applied")
    void corroboratedBalanceIsApplied() {
        var decision = ClosingBalanceGuard.assess(SAVINGS,
                money("1000.00"), money("1500.00"), money("800.00"), money("300.00"), 5, 0);

        assertThat(decision.verdict()).isEqualTo(ClosingBalanceGuard.Verdict.CORROBORATED);
        assertThat(decision.mayOverwriteAccountBalance()).isTrue();
    }

    @Test
    @DisplayName("BUG 02: a closing balance the rows do not reach is refused, not written")
    void uncorroboratedBalanceIsRefused() {
        // The reported reproduction: a client posts a balance unrelated to its own transactions.
        var decision = ClosingBalanceGuard.assess(SAVINGS,
                money("1000.00"), money("99999999"), money("800.00"), money("300.00"), 5, 0);

        assertThat(decision.verdict()).isEqualTo(ClosingBalanceGuard.Verdict.UNCORROBORATED);
        assertThat(decision.mayOverwriteAccountBalance()).isFalse();
        assertThat(decision.details()).containsEntry("expectedClosingBalance", money("1500.00"));
        assertThat(decision.reason()).contains("off by");
    }

    @Test
    @DisplayName("scale differences are not disagreements -- 1500 and 1500.00 corroborate")
    void scaleDifferenceStillCorroborates() {
        // BigDecimal.equals would reject this; compareTo is what makes it pass. Getting this
        // wrong would refuse every statement whose parsed scale differs from its opening balance.
        var decision = ClosingBalanceGuard.assess(SAVINGS,
                money("1000"), money("1500.00"), money("500.0"), money("0"), 3, 0);

        assertThat(decision.verdict()).isEqualTo(ClosingBalanceGuard.Verdict.CORROBORATED);
    }

    @Test
    @DisplayName("excluding rows during review makes the stated balance uncheckable, so it is refused")
    void skippedRowsBlockTheWrite() {
        // Arithmetic that would otherwise pass -- the point is that a skipped row means Finora's
        // ledger will not reach the statement's balance even when the statement itself is correct.
        var decision = ClosingBalanceGuard.assess(SAVINGS,
                money("1000.00"), money("1500.00"), money("800.00"), money("300.00"), 4, 1);

        assertThat(decision.verdict()).isEqualTo(ClosingBalanceGuard.Verdict.UNCORROBORATED);
        assertThat(decision.reason()).contains("excluded during review");
    }

    @Test
    @DisplayName("no stated opening balance means the claim cannot be tested, so it is refused")
    void missingOpeningBalanceIsRefused() {
        var decision = ClosingBalanceGuard.assess(SAVINGS,
                null, money("1500.00"), money("800.00"), money("300.00"), 5, 0);

        assertThat(decision.verdict()).isEqualTo(ClosingBalanceGuard.Verdict.UNCORROBORATED);
        assertThat(decision.mayOverwriteAccountBalance()).isFalse();
    }

    @Test
    @DisplayName("no stated closing balance is nothing to apply -- unchanged from before the guard")
    void absentClosingBalanceIsNotApplicable() {
        var decision = ClosingBalanceGuard.assess(SAVINGS,
                money("1000.00"), null, money("800.00"), money("300.00"), 5, 0);

        assertThat(decision.verdict()).isEqualTo(ClosingBalanceGuard.Verdict.NOT_APPLICABLE);
        assertThat(decision.mayOverwriteAccountBalance()).isFalse();
    }

    @Test
    @DisplayName("an import that wrote no rows corroborates nothing")
    void noImportedRowsIsNotApplicable() {
        var decision = ClosingBalanceGuard.assess(SAVINGS,
                money("1000.00"), money("1500.00"), BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);

        assertThat(decision.verdict()).isEqualTo(ClosingBalanceGuard.Verdict.NOT_APPLICABLE);
    }

    @Test
    @DisplayName("a credit-only statement corroborates without a debit total")
    void nullTotalsAreTreatedAsZero() {
        var decision = ClosingBalanceGuard.assess(SAVINGS,
                money("1000.00"), money("1800.00"), money("800.00"), null, 2, 0);

        assertThat(decision.verdict()).isEqualTo(ClosingBalanceGuard.Verdict.CORROBORATED);
    }

    // ---------------------------------------------------------------- liability accounts (BH-004)

    @Test
    @DisplayName("BH-004: a credit-card statement whose rows reach its stated closing balance is corroborated")
    void creditCardStatementIsReadWithTheLiabilityConvention() {
        // A real card statement: 5,000 outstanding at the start, a 2,000 purchase (EXPENSE, a
        // debit), a 3,000 bill payment (INCOME, a credit). The issuer prints 4,000 outstanding.
        //
        // Under the asset formula this guard used to apply unconditionally, expectedClosing came
        // out at 6,000 and the user was told their statement was "off by 2000.00" -- on a
        // statement that adds up perfectly. Every card import hit this, and the consequence was
        // not just the wrong message: an UNCORROBORATED verdict means the stated closing balance
        // is never written, so no credit card in the product ever had an authoritative balance.
        var decision = ClosingBalanceGuard.assess(CREDIT_CARD,
                money("5000.00"), money("4000.00"), money("3000.00"), money("2000.00"), 2, 0);

        assertThat(decision.verdict()).isEqualTo(ClosingBalanceGuard.Verdict.CORROBORATED);
        assertThat(decision.mayOverwriteAccountBalance()).isTrue();
        assertThat(decision.details()).containsEntry("expectedClosingBalance", money("4000.00"));
        assertThat(decision.details())
                .as("the report has to say which convention it read the account under")
                .containsEntry("balanceConvention", "OWED");
    }

    @Test
    @DisplayName("BH-004: the two conventions genuinely disagree, so the guard cannot pick either by default")
    void theSameFiguresGiveOppositeVerdictsPerAccountType() {
        // The same numbers, read as an asset. This is what the guard used to compute for the card
        // above -- so it is not that one convention is stricter, it is that applying the wrong one
        // inverts the answer. A guard that defaulted would be wrong for half the product.
        var asAsset = ClosingBalanceGuard.assess(SAVINGS,
                money("5000.00"), money("4000.00"), money("3000.00"), money("2000.00"), 2, 0);

        assertThat(asAsset.verdict()).isEqualTo(ClosingBalanceGuard.Verdict.UNCORROBORATED);
        assertThat(asAsset.details()).containsEntry("expectedClosingBalance", money("6000.00"));
        assertThat(asAsset.details()).containsEntry("balanceConvention", "HELD");
    }

    @Test
    @DisplayName("a card whose rows do not reach its stated balance is still refused")
    void creditCardStatementThatDoesNotAddUpIsStillRefused() {
        // The fix must not turn the guard off for cards -- Bug 02's whole point is that an
        // uncorroborated balance is not written, and that has to keep holding on liabilities.
        var decision = ClosingBalanceGuard.assess(CREDIT_CARD,
                money("5000.00"), money("99999.00"), money("3000.00"), money("2000.00"), 2, 0);

        assertThat(decision.verdict()).isEqualTo(ClosingBalanceGuard.Verdict.UNCORROBORATED);
        assertThat(decision.mayOverwriteAccountBalance()).isFalse();
    }

    @Test
    @DisplayName("a card carrying a credit balance corroborates without special handling")
    void aCreditBalanceOnACardNeedsNoSpecialCase() {
        // An overpayment or a refund landing after the bill was settled leaves the issuer owing
        // the customer: outstanding 500, a 700 refund credit, closing -200. AccountBalanceConvention
        // deliberately does not clamp that (a card the issuer owes you does increase net worth),
        // and the corroboration arithmetic must not either.
        var decision = ClosingBalanceGuard.assess(CREDIT_CARD,
                money("500.00"), money("-200.00"), money("700.00"), money("0.00"), 1, 0);

        assertThat(decision.verdict()).isEqualTo(ClosingBalanceGuard.Verdict.CORROBORATED);
    }

    @Test
    @DisplayName("a vanished account falls back to the asset convention and changes nothing")
    void aNullAccountTypeIsTheOldBehaviourAndIsHarmless() {
        // null only when the account was deleted between resolution and this call. There is no row
        // left to write a balance onto, so the verdict is moot -- but it must not throw.
        var decision = ClosingBalanceGuard.assess(null,
                money("1000.00"), money("1500.00"), money("800.00"), money("300.00"), 5, 0);

        assertThat(decision.verdict()).isEqualTo(ClosingBalanceGuard.Verdict.CORROBORATED);
        assertThat(decision.details()).containsEntry("balanceConvention", "HELD");
    }
}
