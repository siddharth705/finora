package com.finora.imports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real PNB statement pair prints periods that share their boundary date with the adjacent
 * statement -- "31-05-2026 to 30-06-2026" then "30-06-2026 to 31-07-2026" -- so the later
 * statement's own earliest printed row is not the true start of its period, it is the tail end of
 * the previous one. {@link BalanceSequenceResolver} derives a statement's opening balance purely
 * from that statement's own rows, with no knowledge of the account's prior import history, so it
 * lands on the balance before that overlapping row rather than after it -- wrong by exactly the
 * overlapping day's net effect. This is the confirmed repro this class was built to fix; the gap
 * itself is general (see this class's own doc comment), since no statement format anywhere in
 * the pipeline carries the account's prior import history.
 *
 * <p>This class does not touch parsing. It answers one question at confirm time, once the target
 * account's own import history is known: does a prior statement's closing balance disagree with
 * what this statement claims (or derived) as its own opening balance, and if so, which one should
 * actually be stored and shown. The prior statement's closing balance is the ledger's own record
 * of where the account stood -- it wins.
 */
class OpeningBalanceCarryForwardTest {

    private static BigDecimal money(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("no prior statement for this account -- nothing to carry forward, stated value kept")
    void noPriorStatement_keepsStatedOpeningBalance() {
        var decision = OpeningBalanceCarryForward.resolve(money("32013.97"), null);

        assertThat(decision.openingBalance()).isEqualByComparingTo(money("32013.97"));
        assertThat(decision.carriedForward()).isFalse();
    }

    @Test
    @DisplayName("stated opening balance already agrees with the prior statement's close -- unchanged")
    void agreesWithPriorClose_keepsStatedOpeningBalance() {
        var decision = OpeningBalanceCarryForward.resolve(money("35354.97"), money("35354.97"));

        assertThat(decision.openingBalance()).isEqualByComparingTo(money("35354.97"));
        assertThat(decision.carriedForward()).isFalse();
    }

    @Test
    @DisplayName("BUG: PNB repro -- derived opening balance disagrees with the prior statement's close")
    void disagreesWithPriorClose_carriesForwardThePriorClose() {
        // The exact reproduction: July statement's own rows derive 32,013.97 (the balance before
        // the overlapping 30/06 row), but June's statement already closed at 35,354.97.
        var decision = OpeningBalanceCarryForward.resolve(money("32013.97"), money("35354.97"));

        assertThat(decision.openingBalance()).isEqualByComparingTo(money("35354.97"));
        assertThat(decision.carriedForward()).isTrue();
        assertThat(decision.reason()).isNotBlank();
    }

    @Test
    @DisplayName("statement printed no opening balance at all -- prior close is still used")
    void noStatedOpeningBalance_usesPriorClose() {
        var decision = OpeningBalanceCarryForward.resolve(null, money("35354.97"));

        assertThat(decision.openingBalance()).isEqualByComparingTo(money("35354.97"));
        assertThat(decision.carriedForward()).isTrue();
    }

    @Test
    @DisplayName("scale differences are not disagreements -- 1500 and 1500.00 do not trigger carry-forward")
    void scaleDifferenceIsNotADisagreement() {
        var decision = OpeningBalanceCarryForward.resolve(money("1500"), money("1500.00"));

        assertThat(decision.carriedForward()).isFalse();
    }
}
