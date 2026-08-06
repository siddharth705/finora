package com.finora.service;

import com.finora.dto.RecurringDto;
import com.finora.entity.CategoryRule;
import com.finora.entity.Transaction;
import com.finora.repository.FeatureFlagRepository;
import com.finora.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Groups active expense transactions by merchant and flags ones with a regular interval
 * (weekly/biweekly/monthly/quarterly) and consistent amount as recurring — subscriptions,
 * rent, EMIs, etc. Ported from the browser prototype's detectRecurring(), same thresholds:
 * amount within +-20% (+Rs1 floor) of the group average, and every gap between consecutive
 * occurrences within +-35% (minimum +-5 days) of the average gap.
 *
 * Persists the result onto Transaction.recurring so the Ledger/Reports pages can show a badge
 * without recomputing this on every page load — recomputed here and written back each call,
 * which is fine at personal-finance transaction volumes; would move to a scheduled job only if
 * this became a measured cost at much larger volumes.
 *
 * Admin Portal Phase 8 -- gated behind the RECURRING_DETECTION_ENABLED feature flag (V32). This
 * is the one real call site the flags framework is proven against: when disabled, this method is
 * a no-op (returns the empty list, does NOT touch any transaction's recurring flag or write an
 * audit entry) rather than clearing every existing recurring badge, since "pause new detection"
 * and "wipe every previously-detected badge" are different operations and only the former is what
 * an admin flipping this off during an incident actually wants.
 */
@Service
public class RecurringService {

    private final TransactionRepository transactionRepository;
    private final RuleEngineService ruleEngineService;
    private final AuditService auditService;
    private final FeatureFlagRepository featureFlagRepository;

    public RecurringService(TransactionRepository transactionRepository, RuleEngineService ruleEngineService,
                             AuditService auditService, FeatureFlagRepository featureFlagRepository) {
        this.transactionRepository = transactionRepository;
        this.ruleEngineService = ruleEngineService;
        this.auditService = auditService;
        this.featureFlagRepository = featureFlagRepository;
    }

    @Transactional
    public List<RecurringDto> detectForUser(UUID userId) {
        if (!featureFlagRepository.isEnabled("RECURRING_DETECTION_ENABLED")) {
            return List.of();
        }

        List<Transaction> active = transactionRepository.findByUserId(userId).stream()
                .filter(t -> t.getIsDuplicateOf() == null && !t.isTransfer() && t.getTxnType() == Transaction.Type.EXPENSE)
                .toList();

        Map<String, List<Transaction>> byMerchant = new HashMap<>();
        active.stream()
                .filter(t -> t.getMerchant() != null && !t.getMerchant().isBlank())
                .forEach(t -> byMerchant.computeIfAbsent(t.getMerchant(), k -> new ArrayList<>()).add(t));

        // Reset first — a merchant that used to look recurring but no longer does (e.g. a
        // cancelled subscription with no new charges) shouldn't keep a stale badge forever.
        active.forEach(t -> t.setRecurring(false));

        List<RecurringDto> results = new ArrayList<>();
        for (var entry : byMerchant.entrySet()) {
            List<Transaction> group = entry.getValue();
            if (group.size() < 2) continue;
            group.sort(Comparator.comparing(Transaction::getTxnDate));

            List<Long> gaps = new ArrayList<>();
            for (int i = 1; i < group.size(); i++) {
                gaps.add(ChronoUnit.DAYS.between(group.get(i - 1).getTxnDate(), group.get(i).getTxnDate()));
            }
            double avgGap = gaps.stream().mapToLong(Long::longValue).average().orElse(0);
            boolean gapRegular = gaps.stream().allMatch(g -> Math.abs(g - avgGap) <= Math.max(5, avgGap * 0.35));

            BigDecimal avgAmount = group.stream().map(Transaction::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(group.size()), 4, RoundingMode.HALF_UP);
            boolean amountConsistent = group.stream().allMatch(t ->
                    t.getAmount().subtract(avgAmount).abs()
                            .compareTo(avgAmount.multiply(BigDecimal.valueOf(0.2)).add(BigDecimal.ONE)) <= 0);

            boolean intervalIsRegular = avgGap >= 5 && avgGap <= 95;

            if (gapRegular && amountConsistent && intervalIsRegular) {
                group.forEach(t -> t.setRecurring(true));
                String label = avgGap < 10 ? "Weekly" : avgGap < 20 ? "Biweekly" : avgGap < 40 ? "Monthly"
                        : avgGap < 100 ? "Quarterly" : "Periodic";
                LocalDate lastDate = group.get(group.size() - 1).getTxnDate();
                results.add(new RecurringDto(entry.getKey(), label, avgAmount, group.size(),
                        lastDate, lastDate.plusDays(Math.round(avgGap))));
            }
        }

        // MARK_SUBSCRIPTION rules -- a second signal alongside the pattern detection above. See
        // CategorizationService.applySideEffectRules's doc comment for why this one action type
        // is evaluated here rather than at write time: this method already resets-then-recomputes
        // every active transaction's `recurring` flag on every call, so a write-time flag would
        // just get silently wiped out by the reset pass above the next time this runs. Only
        // checked for transactions the pattern pass didn't already flag -- a rule match doesn't
        // need to wait for 2+ occurrences with a regular gap the way organic pattern detection
        // does; the rule author already knows this pattern is a subscription.
        //
        // Uses t.getMerchant() (the plain extracted string already on the transaction) rather
        // than resolving the full canonical Merchant per transaction -- this loop can run over
        // every active transaction a user has, and re-resolving each one would be a real N+1
        // against the merchant/alias tables for no benefit, since MARK_SUBSCRIPTION rules in
        // practice target DESCRIPTION ("netflix", "spotify"), not MERCHANT.
        // Fetched ONCE, outside the loop. Each evaluateSideEffectRules(userId, ...) call issued
        // two repository queries, and both result sets are identical on every iteration -- the
        // same user and the same global scope -- so this loop was 2N queries for N transactions,
        // on every transaction create, edit and delete. Same N+1 the comment above says it avoided
        // for merchants, two lines further down and missed. See sideEffectRuleSet.
        List<CategoryRule> sideEffectRules = ruleEngineService.sideEffectRuleSet(userId);
        for (Transaction t : active) {
            if (t.isRecurring()) continue;
            boolean subscriptionRuleMatch = ruleEngineService.evaluateSideEffectRules(
                            sideEffectRules, t.getDescription(), t.getAmount(), t.getMerchant(), null).stream()
                    .anyMatch(m -> m.rule().getActionType() == CategoryRule.ActionType.MARK_SUBSCRIPTION);
            if (subscriptionRuleMatch) t.setRecurring(true);
        }

        transactionRepository.saveAll(active);
        results.sort(Comparator.comparing(RecurringDto::nextEstimate));

        // Financial Intelligence Workspace, Reconciliation Monitor -- same one-summary-per-run
        // convention as ReconciliationService.reconcileForUser(), see that method's own doc
        // comment. recurringGroupsFound is results.size() (pattern-detected groups only, not
        // rule-matched singles -- a MARK_SUBSCRIPTION match on one transaction isn't a "group"
        // the way 2+ regularly-spaced charges are); recurringTransactionsFlagged counts every
        // transaction with recurring=true after this run, from either signal.
        long recurringTransactionsFlagged = active.stream().filter(Transaction::isRecurring).count();
        auditService.record(userId, "RECURRING_DETECTION_RUN", "Transaction", null, Map.of(
                "transactionsProcessed", active.size(),
                "recurringGroupsFound", results.size(),
                "recurringTransactionsFlagged", recurringTransactionsFlagged));

        return results;
    }
}
