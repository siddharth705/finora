package com.finora.service;

import com.finora.entity.Transaction;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the record of <i>why</i> a transaction was classified the way it was.
 *
 * <p><b>The problem this solves.</b> Reconciliation wrote a verdict and nothing else. A transaction
 * came out marked TRANSFER, with a {@code transferPairId} pointing at its partner, and that was the
 * entire account of a decision made by three separate passes weighing five or six signals. When a
 * user asks why their salary was excluded from income, or a support engineer needs to know whether
 * a match was driven by a relationship identifier or by the word "payment" appearing in a
 * narration, the only way to answer was to re-derive it by hand from the transaction pair — and
 * that only works if the data has not changed since, which after an edit it has.
 *
 * <p><b>This changes no decision.</b> Every field here is read from values the passes had already
 * computed in order to reach their verdict; nothing is recomputed and no new predicate is
 * introduced. Removing this class entirely would leave every classification identical. It records
 * reasoning that was previously discarded the moment it was used.
 *
 * <p><b>Why the signals are stored individually rather than as a sentence.</b> A rendered string
 * ("matched on amount and date") is written for whoever is reading today. Discrete booleans and
 * numbers survive a UI that has not been designed yet, can be aggregated ("how many transfers rely
 * on the description heuristic alone?" — which is the question that would tell you whether that
 * heuristic is earning its keep), and make a threshold change auditable: with
 * {@code dateDifferenceDays} on every match, the effect of narrowing a window is a query rather
 * than a guess.
 *
 * <p>Returned as a {@code LinkedHashMap} so key order in the stored JSON is stable and diffable —
 * {@code Map.of()} would neither preserve order nor permit the null a {@code matchedTransaction}
 * legitimately has for a verdict with no counterpart.
 */
final class ReconciliationExplanation {

    private ReconciliationExplanation() {}

    /**
     * Why this transaction was flagged as a duplicate of {@code originalId}.
     *
     * <p>The duplicate pass groups on an exact composite key, so its signals are all necessarily
     * true — they are recorded anyway rather than left implied, because "which fields had to match"
     * is exactly what someone disputing a duplicate needs to see, and because the key's definition
     * has changed before (see {@code duplicateKey}'s own comment on scale and null handling).
     *
     * @param sameBalance         whether this row's running balance also matched the original's --
     *                            only true when {@code ReconciliationService.splitByDiscriminator}
     *                            needed the account+date+amount+description group split by balance
     *                            to reach this pairing (most same-day duplicates never need it, so
     *                            this is {@code false} far more often than not) — omitted from the
     *                            evidence entirely rather than recorded {@code false}, since "not
     *                            checked" and "checked and different" are not the same fact and a
     *                            reviewer should not read the absence of one signal as the other
     * @param sameReferenceNumber same reasoning, for the reference-number fallback discriminator
     */
    static Map<String, Object> duplicate(UUID originalId, boolean sameBalance, boolean sameReferenceNumber) {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("sameAccount", true);
        reason.put("sameDate", true);
        reason.put("sameAmount", true);
        reason.put("sameDescription", true);
        if (sameBalance) reason.put("sameBalance", true);
        if (sameReferenceNumber) reason.put("sameReferenceNumber", true);
        return envelope("DUPLICATE", originalId, reason);
    }

    /**
     * Why these two transactions were paired as an internal transfer.
     *
     * <p>{@code dayWindowApplied} is recorded alongside {@code dateDifferenceDays} on purpose: the
     * window is not fixed, it widens when a relationship identifier is present, so the gap alone
     * does not tell you whether a match was comfortable or marginal. Both together do.
     */
    static Map<String, Object> transfer(Transaction self, Transaction counterpart,
                                        long dayWindowApplied, boolean relationshipIdentifierMatched) {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("differentAccount", !self.getAccountId().equals(counterpart.getAccountId()));
        reason.put("oppositeDirection", self.getTxnType() != counterpart.getTxnType());
        reason.put("amountDifference",
                self.getAmount().subtract(counterpart.getAmount()).abs().toPlainString());
        reason.put("dateDifferenceDays", daysBetween(self.getTxnDate(), counterpart.getTxnDate()));
        reason.put("dayWindowApplied", dayWindowApplied);
        reason.put("relationshipIdentifierMatched", relationshipIdentifierMatched);
        return envelope("TRANSFER", counterpart.getId(), reason);
    }

    /**
     * Why this income was read as a refund of {@code purchase}, as opposed to a reversal (see
     * {@link #reversal} below) or nothing at all.
     *
     * <p>{@code refundKeyword} and {@code sameMerchant} are two of the three independent signals
     * the pass requires at least one of (the third, a reversal keyword, routes to
     * {@link #reversal} instead of here) — recording both distinguishes a match carried by strong
     * merchant evidence from one carried by refund-flavored wording in a narration, a distinction
     * that matters when the classification turns out to be wrong.
     */
    static Map<String, Object> refund(Transaction income, Transaction purchase,
                                      boolean refundKeyword, boolean sameMerchant) {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("sameAccount", income.getAccountId().equals(purchase.getAccountId()));
        reason.put("dateDifferenceDays", daysBetween(purchase.getTxnDate(), income.getTxnDate()));
        reason.put("refundKeyword", refundKeyword);
        reason.put("sameMerchant", sameMerchant);
        reason.put("refundAmount", income.getAmount().toPlainString());
        reason.put("purchaseAmount", purchase.getAmount().toPlainString());
        reason.put("partialRefund", income.getAmount().compareTo(purchase.getAmount()) < 0);
        return envelope("REFUND", purchase.getId(), reason);
    }

    /**
     * Why this income was read as a bank-side reversal of {@code purchase}, rather than a
     * merchant refund. Same matching pass, same window and capacity rules as {@link #refund} --
     * the only difference is which real-world event the description's wording claims this is.
     * {@code reversalKeyword} is always {@code true} when this is called (it is the signal that
     * decided the classification, see {@code ReconciliationService}'s refund pass); recorded
     * anyway, alongside {@code sameMerchant}, for the same reason every other explanation here
     * records signals that were necessarily true -- it is what a reader disputing the
     * classification needs to see without re-deriving it.
     */
    static Map<String, Object> reversal(Transaction income, Transaction purchase, boolean sameMerchant) {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("sameAccount", income.getAccountId().equals(purchase.getAccountId()));
        reason.put("dateDifferenceDays", daysBetween(purchase.getTxnDate(), income.getTxnDate()));
        reason.put("reversalKeyword", true);
        reason.put("sameMerchant", sameMerchant);
        reason.put("reversalAmount", income.getAmount().toPlainString());
        reason.put("purchaseAmount", purchase.getAmount().toPlainString());
        reason.put("partialReversal", income.getAmount().compareTo(purchase.getAmount()) < 0);
        return envelope("REVERSAL", purchase.getId(), reason);
    }

    /**
     * Why this expense was excluded from spend totals as money moving into savings/investment
     * rather than consumption. {@link CategoryRules}'s existing "Investments" category (Groww,
     * Zerodha, mutual fund, SIP, ...) is the only signal here -- unlike every other explanation in
     * this class, there is no counterpart transaction to name: the real-world pattern this covers
     * (a UPI payment to an external broker) has no matching "money arrived" row anywhere in Finora,
     * since the user never imports the broker's own statement. No confidence to score either, for
     * the same reason -- there is nothing to score a pairing against, the same "deterministic, no
     * scored confidence" precedent {@code CategoryRules}-based merchant normalization already set.
     */
    static Map<String, Object> investmentTransfer(Transaction t) {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("category", "Investments");
        reason.put("amount", t.getAmount().toPlainString());
        return envelope("INVESTMENT_TRANSFER", null, reason);
    }

    private static Map<String, Object> envelope(String type, UUID matchedTransaction,
                                                Map<String, Object> reason) {
        Map<String, Object> explanation = new LinkedHashMap<>();
        explanation.put("type", type);
        // Stored as a string, not a UUID: this lands in a jsonb column, and a plain string is what
        // every reader of that JSON -- Jackson, psql, the admin UI -- handles without a converter.
        explanation.put("matchedTransaction", matchedTransaction == null ? null : matchedTransaction.toString());
        explanation.put("reason", reason);
        return explanation;
    }

    private static long daysBetween(LocalDate from, LocalDate to) {
        return Math.abs(ChronoUnit.DAYS.between(from, to));
    }
}
