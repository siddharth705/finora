package com.finora.imports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    private static BigDecimal money(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("a closing balance the imported rows actually reach is applied")
    void corroboratedBalanceIsApplied() {
        var decision = ClosingBalanceGuard.assess(
                money("1000.00"), money("1500.00"), money("800.00"), money("300.00"), 5, 0);

        assertThat(decision.verdict()).isEqualTo(ClosingBalanceGuard.Verdict.CORROBORATED);
        assertThat(decision.mayOverwriteAccountBalance()).isTrue();
    }

    @Test
    @DisplayName("BUG 02: a closing balance the rows do not reach is refused, not written")
    void uncorroboratedBalanceIsRefused() {
        // The reported reproduction: a client posts a balance unrelated to its own transactions.
        var decision = ClosingBalanceGuard.assess(
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
        var decision = ClosingBalanceGuard.assess(
                money("1000"), money("1500.00"), money("500.0"), money("0"), 3, 0);

        assertThat(decision.verdict()).isEqualTo(ClosingBalanceGuard.Verdict.CORROBORATED);
    }

    @Test
    @DisplayName("excluding rows during review makes the stated balance uncheckable, so it is refused")
    void skippedRowsBlockTheWrite() {
        // Arithmetic that would otherwise pass -- the point is that a skipped row means Finora's
        // ledger will not reach the statement's balance even when the statement itself is correct.
        var decision = ClosingBalanceGuard.assess(
                money("1000.00"), money("1500.00"), money("800.00"), money("300.00"), 4, 1);

        assertThat(decision.verdict()).isEqualTo(ClosingBalanceGuard.Verdict.UNCORROBORATED);
        assertThat(decision.reason()).contains("excluded during review");
    }

    @Test
    @DisplayName("no stated opening balance means the claim cannot be tested, so it is refused")
    void missingOpeningBalanceIsRefused() {
        var decision = ClosingBalanceGuard.assess(
                null, money("1500.00"), money("800.00"), money("300.00"), 5, 0);

        assertThat(decision.verdict()).isEqualTo(ClosingBalanceGuard.Verdict.UNCORROBORATED);
        assertThat(decision.mayOverwriteAccountBalance()).isFalse();
    }

    @Test
    @DisplayName("no stated closing balance is nothing to apply -- unchanged from before the guard")
    void absentClosingBalanceIsNotApplicable() {
        var decision = ClosingBalanceGuard.assess(
                money("1000.00"), null, money("800.00"), money("300.00"), 5, 0);

        assertThat(decision.verdict()).isEqualTo(ClosingBalanceGuard.Verdict.NOT_APPLICABLE);
        assertThat(decision.mayOverwriteAccountBalance()).isFalse();
    }

    @Test
    @DisplayName("an import that wrote no rows corroborates nothing")
    void noImportedRowsIsNotApplicable() {
        var decision = ClosingBalanceGuard.assess(
                money("1000.00"), money("1500.00"), BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);

        assertThat(decision.verdict()).isEqualTo(ClosingBalanceGuard.Verdict.NOT_APPLICABLE);
    }

    @Test
    @DisplayName("a credit-only statement corroborates without a debit total")
    void nullTotalsAreTreatedAsZero() {
        var decision = ClosingBalanceGuard.assess(
                money("1000.00"), money("1800.00"), money("800.00"), null, 2, 0);

        assertThat(decision.verdict()).isEqualTo(ClosingBalanceGuard.Verdict.CORROBORATED);
    }
}
