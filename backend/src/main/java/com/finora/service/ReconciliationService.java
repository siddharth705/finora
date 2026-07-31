package com.finora.service;

import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import com.finora.util.CategoryRules;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ReconciliationService {

    // Plain amount+date transfer matches need to fall within this many days of each other.
    // Widened (not replaced) when a known OWN_ACCOUNT relationship identifier is found on either
    // side of the pair -- see the relationshipMatch check below and
    // docs/rule-engine-relationship-engine-eds.md §4 ("raise transfer-match confidence... not
    // replace the heuristic").
    private static final long DEFAULT_TRANSFER_DAY_WINDOW = 4;
    private static final long OWN_ACCOUNT_MATCH_DAY_WINDOW = 10;

    // How far apart a refund can land from the purchase it reverses. Wider than the transfer
    // windows above on purpose -- transfers between the user's own accounts happen within days;
    // merchant refunds (a return, a cancelled order, a billing dispute) routinely take weeks.
    private static final long REFUND_WINDOW_DAYS = 180;

    // Keyword signal for "this INCOME row is a refund," independent of merchant matching -- see
    // the refund pass below. Deliberately not folded into CategoryRules (util package): that
    // table drives the CATEGORY suggestion ("Fees/Interest", "Transfer", ...), this drives
    // RECONCILIATION status, a different concern evaluated at a different point in the pipeline.
    private static final Set<String> REFUND_KEYWORDS = Set.of(
            "refund", "reversal", "returned", "chargeback", "credit adjustment", "cancelled", "canceled");

    private final TransactionRepository transactionRepository;
    private final RelationshipService relationshipService;
    private final AuditService auditService;

    public ReconciliationService(TransactionRepository transactionRepository, RelationshipService relationshipService,
                                  AuditService auditService) {
        this.transactionRepository = transactionRepository;
        this.relationshipService = relationshipService;
        this.auditService = auditService;
    }

    /**
     * Runs after an import batch or manual edit. Same logic as the browser prototype, now with
     * a third pass added:
     * 1) Flag exact duplicates (same account+date+amount+description already present).
     * 2) Match internal transfers — an expense-side "payment" transaction on one account that
     *    lines up in amount and falls within 4 days of an income-side transaction on a
     *    different account is almost certainly money moving between the user's own accounts,
     *    not real income/spend, so both get tagged TRANSFER and excluded from totals.
     * 3) Match refunds — a same-account INCOME that reverses a specific prior EXPENSE (a
     *    return, a cancelled order, a billing dispute). Distinct from both of the above:
     *    same account (never different, unlike a transfer) and not an exact-field match
     *    (unlike a duplicate) -- the amount can be a partial refund, and the description
     *    routinely differs entirely from the original purchase's.
     */
    public void reconcileForUser(UUID userId) {
        List<Transaction> all = transactionRepository.findByUserId(userId);

        // 1) Duplicates -- grouped in-memory over the already-fetched `all` list rather than one
        // findPotentialDuplicates() query per transaction (the original shape of this pass, and
        // a real N+1: a user re-importing statements ends up re-running this after every import,
        // against their entire transaction history each time).
        //
        // stripTrailingZeros().toPlainString() on the amount, not amount.toString(), because the
        // original query used numeric SQL equality (100 = 100.00 is true) -- a naive string key
        // would treat differently-scaled-but-equal amounts as distinct groups, a real regression.
        // Null descriptions get a per-transaction-unique key component rather than colliding on
        // the literal string "null" -- NULL never equals NULL in the original query's SQL
        // semantics (`t.description = :description` with a null bind parameter is never true,
        // not even against another null), so two no-description transactions must never be
        // grouped as duplicates of each other either.
        // Counters for the Financial Intelligence Workspace's Reconciliation Monitor page --
        // "Matches created" per the kickoff memo's resolved scope (deterministic-only, no
        // confidence/near-miss logging) means new matches THIS run, not the lifetime total
        // already sitting on the transactions table (that lifetime total is what Workspace
        // Dashboard's duplicateMatches/transferMatches/refundMatches already show, computed
        // fresh on every read -- this is a different, complementary number: how much did the
        // most recent run actually do).
        int newDuplicates = 0, newTransfers = 0, newRefunds = 0;

        Map<String, List<Transaction>> byDuplicateKey = new HashMap<>();
        for (Transaction t : all) {
            if (t.getIsDuplicateOf() != null) continue; // already resolved by a prior run
            byDuplicateKey.computeIfAbsent(duplicateKey(t), k -> new java.util.ArrayList<>()).add(t);
        }
        for (List<Transaction> group : byDuplicateKey.values()) {
            if (group.size() < 2) continue;
            Transaction earliest = group.stream().min(Comparator.comparing(Transaction::getCreatedAt)).orElseThrow();
            for (Transaction t : group) {
                if (t == earliest || t.getIsDuplicateOf() != null) continue;
                t.setIsDuplicateOf(earliest.getId());
                t.setReconciliationStatus(Transaction.ReconciliationStatus.DUPLICATE);
                transactionRepository.save(t);
                newDuplicates++;
            }
        }

        // 2) Transfers
        List<Transaction> candidates = all.stream().filter(t -> t.getIsDuplicateOf() == null && !t.isTransfer()).toList();

        // Fetched ONCE per reconcileForUser() call (not once per candidate, and not once per
        // pair inside the O(n^2) loop below) -- within a single run this is always the same
        // answer for the same user, so re-querying it per transaction would be a pure N+1 that
        // multiplies with every transaction on the books, on every import/create/edit/delete.
        List<String> ownAccountIdentifiers = relationshipService.ownAccountIdentifierValues(userId);
        Map<UUID, Boolean> ownAccountMatch = new HashMap<>();
        // Same reasoning as ownAccountMatch above -- CategoryRules.suggestCategory() walks every
        // compiled keyword pattern, so this is computed once per candidate rather than once per
        // pair-check inside the O(n^2) loop.
        Map<UUID, Boolean> looksLikeSalary = new HashMap<>();
        for (Transaction t : candidates) {
            String normalizedDescription = CategoryRules.normalize(t.getDescription());
            ownAccountMatch.put(t.getId(), ownAccountIdentifiers.stream().anyMatch(normalizedDescription::contains));
            looksLikeSalary.put(t.getId(), "Salary".equals(CategoryRules.suggestCategory(t.getDescription())));
        }

        for (Transaction a : candidates) {
            if (a.isTransfer()) continue;
            // Salary is external income, never money moving between the user's own accounts --
            // without this guard, a salary credit whose description happens to contain the word
            // "payment" (e.g. "NEFT SALARY PAYMENT XYZ CORP", a real-world pattern) could
            // false-positive-trigger this pass and, on an amount/date coincidence, get paired
            // against an unrelated expense as if it were a transfer.
            if (looksLikeSalary.getOrDefault(a.getId(), false)) continue;
            boolean aOwnAccountMatch = ownAccountMatch.getOrDefault(a.getId(), false);
            // A relationship identifier match is independent evidence of a known own-account
            // transfer -- it can trigger this candidate pair's evaluation on its own, without
            // also needing the "payment"-in-description heuristic.
            boolean looksLikeTransfer = aOwnAccountMatch || CategoryRules.normalize(a.getDescription()).contains("payment");
            if (!looksLikeTransfer) continue;

            for (Transaction b : candidates) {
                if (a.getId().equals(b.getId()) || b.isTransfer()) continue;
                if (looksLikeSalary.getOrDefault(b.getId(), false)) continue; // same guard, other side of the pair
                if (a.getAccountId().equals(b.getAccountId()) || a.getTxnType() == b.getTxnType()) continue;

                boolean sameAmount = a.getAmount().subtract(b.getAmount()).abs().compareTo(BigDecimal.ONE) < 0;
                long daysApart = Math.abs(ChronoUnit.DAYS.between(a.getTxnDate(), b.getTxnDate()));

                boolean relationshipMatch = aOwnAccountMatch || ownAccountMatch.getOrDefault(b.getId(), false);
                long dayWindow = relationshipMatch ? OWN_ACCOUNT_MATCH_DAY_WINDOW : DEFAULT_TRANSFER_DAY_WINDOW;

                if (sameAmount && daysApart <= dayWindow) {
                    a.setTransfer(true); b.setTransfer(true);
                    a.setTransferPairId(b.getId()); b.setTransferPairId(a.getId());
                    a.setReconciliationStatus(Transaction.ReconciliationStatus.TRANSFER);
                    b.setReconciliationStatus(Transaction.ReconciliationStatus.TRANSFER);
                    transactionRepository.save(a);
                    transactionRepository.save(b);
                    newTransfers++; // one pair = one match, not two (see WorkspaceSummaryDto's own note on this asymmetry)
                    break;
                }
            }
        }

        // 3) Refunds -- re-filters candidates fresh (not reusing the list above) because step 2
        // just mutated some of these same objects' isTransfer() in place; a transaction that
        // just became a transfer must not also be considered for a refund pairing.
        List<Transaction> refundCandidates = candidates.stream().filter(t -> !t.isTransfer()).toList();
        for (Transaction income : refundCandidates) {
            if (income.getTxnType() != Transaction.Type.INCOME) continue;
            if (income.getReconciliationStatus() != Transaction.ReconciliationStatus.OK) continue;

            boolean refundKeyword = looksLikeRefund(income.getDescription());
            Transaction bestMatch = null;
            for (Transaction expense : refundCandidates) {
                if (expense.getTxnType() != Transaction.Type.EXPENSE) continue;
                // Same account only -- a refund lands back where the original purchase was made.
                // (This is exactly what distinguishes a refund from a transfer, which above
                // requires DIFFERENT accounts -- the two passes can never claim the same pair.)
                if (!expense.getAccountId().equals(income.getAccountId())) continue;
                if (income.getTxnDate().isBefore(expense.getTxnDate())) continue; // a refund can't predate its own purchase
                long daysBetween = ChronoUnit.DAYS.between(expense.getTxnDate(), income.getTxnDate());
                if (daysBetween > REFUND_WINDOW_DAYS) continue;

                // Need at least one real signal -- either the description says "refund"/
                // "reversal"/etc, or the (already-resolved, see Transaction.merchant) merchant
                // token matches the original purchase's. Neither alone is required everywhere;
                // both being absent means there's no actual evidence this is a refund at all.
                boolean sameMerchant = expense.getMerchant() != null && !expense.getMerchant().isBlank()
                        && expense.getMerchant().equalsIgnoreCase(income.getMerchant());
                if (!refundKeyword && !sameMerchant) continue;

                if (income.getAmount().compareTo(expense.getAmount()) > 0) continue; // can't refund more than was spent

                if (bestMatch == null || isCloserRefundMatch(expense, bestMatch, income)) {
                    bestMatch = expense;
                }
            }

            if (bestMatch != null) {
                income.setReconciliationStatus(Transaction.ReconciliationStatus.REFUND);
                income.setRefundOfTransactionId(bestMatch.getId());
                transactionRepository.save(income);
                newRefunds++;
            }
        }

        // Financial Intelligence Workspace, Reconciliation Monitor -- one summary entry per run,
        // not one per match (that would flood the activity feed with as many rows as duplicates
        // found in a single large import). Recorded even when every counter is 0: "ran and found
        // nothing new" is itself the answer to "when did this last run" and belongs in the
        // history, not just a silent no-op.
        auditService.record(userId, "RECONCILIATION_RUN", "Transaction", null, Map.of(
                "transactionsProcessed", all.size(),
                "duplicatesFound", newDuplicates,
                "transfersMatched", newTransfers,
                "refundsMatched", newRefunds));
    }

    private String duplicateKey(Transaction t) {
        if (t.getDescription() == null) return "no-desc-" + t.getId();
        return t.getAccountId() + "|" + t.getTxnDate() + "|"
                + t.getAmount().stripTrailingZeros().toPlainString() + "|" + t.getDescription();
    }

    private boolean looksLikeRefund(String description) {
        String normalized = CategoryRules.normalize(description);
        return REFUND_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    /** Exact-amount matches always outrank partial-amount matches; among equally-good matches,
     *  the temporally closer original purchase wins -- a simple, explainable tie-break rather
     *  than a scored heuristic, consistent with how this whole engine favors deterministic rules
     *  over ML-style scoring wherever a deterministic rule is adequate. */
    private boolean isCloserRefundMatch(Transaction candidate, Transaction currentBest, Transaction income) {
        boolean candidateExact = candidate.getAmount().compareTo(income.getAmount()) == 0;
        boolean currentExact = currentBest.getAmount().compareTo(income.getAmount()) == 0;
        if (candidateExact != currentExact) return candidateExact;

        long candidateDays = ChronoUnit.DAYS.between(candidate.getTxnDate(), income.getTxnDate());
        long currentDays = ChronoUnit.DAYS.between(currentBest.getTxnDate(), income.getTxnDate());
        return candidateDays < currentDays;
    }
}
