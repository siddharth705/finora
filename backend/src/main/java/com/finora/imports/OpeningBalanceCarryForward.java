package com.finora.imports;

import java.math.BigDecimal;

/**
 * Decides which opening balance a statement should actually be stored and shown with, when the
 * target account already has an earlier statement on file.
 *
 * <p><b>Why this exists.</b> {@link BalanceSequenceResolver} derives a statement's opening
 * balance purely from that statement's own printed rows -- it has no notion of the account's
 * prior import history, by design (see its own class comment). That is correct in isolation, but
 * a real PNB statement pair (31-05-2026 to 30-06-2026, then 30-06-2026 to 31-07-2026) prints
 * consecutive statement periods that are inclusive on BOTH boundary dates -- so the
 * later statement's own earliest printed row is not the true start of ITS period, it is the tail
 * end of the previous one, re-printed. {@code BalanceSequenceResolver} then derives the balance
 * BEFORE that overlapping row, not after it -- wrong by exactly the overlapping day's net effect,
 * even though the statement's own arithmetic is internally consistent and its stated closing
 * balance is correct.
 *
 * <p>The account's own prior statement already recorded where the ledger stood at the end of its
 * period. When it exists, it is a stronger source of truth for "where did this new statement's
 * period begin" than a value re-derived from a single PDF's own rows THAT DOES NOT EVEN
 * RECONCILE AGAINST THAT SAME PDF'S OWN TOTALS -- the same "the ledger's own history outranks a
 * fresh guess" reasoning {@code ImportService.isMostRecentStatementForAccount} already applies on
 * the closing side.
 *
 * <p><b>This class does not decide WHEN it should be consulted -- its caller does, deliberately.</b>
 * {@link #resolve} always prefers the prior close whenever it disagrees with what it is handed; it
 * has no way to tell a provably-wrong derivation (PNB's case) apart from a statement whose own
 * opening balance is genuinely correct but differs from Finora's own history because of a real
 * gap -- a statement the user never imported, during which the account moved. That distinction
 * needs information this class does not have: whether the statement's OWN totals reconcile
 * against its OWN claimed closing balance. {@code ImportService.persistSection} makes that check
 * first (reusing {@link ClosingBalanceGuard}'s own arithmetic) and calls this class only when it
 * fails -- so a statement whose own numbers already check out is never second-guessed by this
 * class, no matter what it disagrees with.
 *
 * <p><b>Refusing to guess when there is nothing to carry forward.</b> With no prior statement (the
 * account's first import, or the target account has no earlier statement with a stated period and
 * closing balance), there is nothing to compare against -- the statement's own derived/stated
 * value is kept exactly as before. This never invents a number outside genuine carry-forward.
 */
public final class OpeningBalanceCarryForward {

    private OpeningBalanceCarryForward() {}

    public record Decision(BigDecimal openingBalance, boolean carriedForward, String reason) {}

    /**
     * @param statedOpeningBalance      what the statement itself claims or {@link
     *                                  BalanceSequenceResolver} derived -- null when the
     *                                  statement printed no period at all
     * @param priorStatementClosingBalance the target account's chronologically previous
     *                                  statement's closing balance, or null when there is none
     */
    public static Decision resolve(BigDecimal statedOpeningBalance, BigDecimal priorStatementClosingBalance) {
        if (priorStatementClosingBalance == null) {
            return new Decision(statedOpeningBalance, false, null);
        }
        if (statedOpeningBalance != null && statedOpeningBalance.compareTo(priorStatementClosingBalance) == 0) {
            return new Decision(statedOpeningBalance, false, null);
        }
        return new Decision(priorStatementClosingBalance, true,
                "Opening balance carried forward from your prior statement's closing balance ("
                        + priorStatementClosingBalance + ") instead of this statement's own "
                        + (statedOpeningBalance != null ? "figure (" + statedOpeningBalance + ")" : "unstated opening balance")
                        + ", because this account already has an earlier statement on file.");
    }
}
