package com.finora.service;

import com.finora.dto.RecurringDto;
import com.finora.entity.CategoryRule;
import com.finora.entity.Transaction;
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
 * without recomputing this on every page load — recomputed here on every call (this is the one
 * real call site reached from a plain GET, Dashboard.tsx and Insights.tsx both fetch it on
 * ordinary page load), but only WRITTEN when a transaction's flag actually changed from this run
 * (see detectForUser). Bug fix (review): a blind saveAll(active) here wrote and version-bumped
 * every active transaction on every call, GET included -- two concurrent page loads (a browser
 * prefetch, React StrictMode's double-render, or genuinely two open tabs) racing the same
 * unchanged result could throw OptimisticLockingFailureException on what was, from the caller's
 * perspective, a read. It also wrote one RECURRING_DETECTION_RUN audit row per page view, with
 * nothing having actually happened. Diffing against the flag's current value before writing makes
 * a call that changes nothing genuinely side-effect-free, without changing this method's
 * contract or requiring a separate write endpoint.
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

    /**
     * How many charges from one merchant it takes before an interval is evidence of a pattern.
     *
     * <p>BH-026. This was 2, and at 2 the regularity check cannot fail. With two transactions
     * {@code gaps} holds a single element, so {@code avgGap} IS that element and
     * {@code Math.abs(g - avgGap)} is exactly zero for it -- {@code gapRegular} is unconditionally
     * true, whatever the spacing. The only surviving filters were the amount tolerance and the
     * 5-95 day window, so any two similar charges from one merchant a few weeks apart became a
     * "Monthly" subscription, complete with a predicted next date the user could plan around.
     * Two coffees three weeks apart is the everyday version.
     *
     * <p>Three is the smallest number at which the check means anything: two points define an
     * interval, three are the first that can agree or disagree about one. The
     * {@code MARK_SUBSCRIPTION} rule path is unaffected and still fires on a single occurrence --
     * there the rule's author has asserted the pattern rather than the engine inferring it, which
     * is exactly the distinction this constant draws.
     */
    private static final int MIN_OCCURRENCES_FOR_A_PATTERN = 3;

    private final TransactionRepository transactionRepository;
    private final RuleEngineService ruleEngineService;
    private final AuditService auditService;
    private final FeatureFlagService featureFlagService;

    public RecurringService(TransactionRepository transactionRepository, RuleEngineService ruleEngineService,
                             AuditService auditService, FeatureFlagService featureFlagService) {
        this.transactionRepository = transactionRepository;
        this.ruleEngineService = ruleEngineService;
        this.auditService = auditService;
        this.featureFlagService = featureFlagService;
    }

    @Transactional
    public List<RecurringDto> detectForUser(UUID userId) {
        // Was featureFlagRepository.isEnabled(...) directly -- bypassed FeatureFlagService, which
        // FeatureFlagRepository's own doc comment says is the intended call path, and meant this,
        // the one real call site the flags framework is proven against (8 callers: every import
        // confirm and every transaction mutation), never benefited from FeatureFlagService.isEnabled
        // being cached (see that class's own doc comment).
        if (!featureFlagService.isEnabled("RECURRING_DETECTION_ENABLED")) {
            return List.of();
        }

        List<Transaction> active = transactionRepository.findByUserId(userId).stream()
                .filter(t -> t.getIsDuplicateOf() == null && !t.isTransfer() && t.getTxnType() == Transaction.Type.EXPENSE)
                .toList();

        Map<String, List<Transaction>> byMerchant = new HashMap<>();
        active.stream()
                .filter(t -> t.getMerchant() != null && !t.getMerchant().isBlank())
                .forEach(t -> byMerchant.computeIfAbsent(t.getMerchant(), k -> new ArrayList<>()).add(t));

        // Computed into a side map rather than mutating `t.setRecurring` directly, so the diff
        // against each transaction's CURRENT flag below (built after this map is complete) can
        // tell "this run agrees with what's already persisted" apart from "this run changed
        // something" -- see this class's own doc comment on why only the latter should ever be
        // written. Defaults every active transaction to false first, same reasoning the old
        // eager reset had: a merchant that used to look recurring but no longer does (e.g. a
        // cancelled subscription with no new charges) shouldn't keep a stale badge forever.
        Map<UUID, Boolean> desiredRecurring = new HashMap<>();
        active.forEach(t -> desiredRecurring.put(t.getId(), false));

        List<RecurringDto> results = new ArrayList<>();
        for (var entry : byMerchant.entrySet()) {
            List<Transaction> group = entry.getValue();
            if (group.size() < MIN_OCCURRENCES_FOR_A_PATTERN) continue;
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
                group.forEach(t -> desiredRecurring.put(t.getId(), true));
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
        // for merchants, two lines further down and missed. See ruleSet.
        List<CategoryRule> sideEffectRules = ruleEngineService.ruleSet(userId);
        for (Transaction t : active) {
            // desiredRecurring, not t.isRecurring() -- the latter is still whatever was loaded
            // from the DB (last run's result) at this point, since nothing has been mutated yet.
            // "Already flagged recurring by the pattern pass above, this run" is the actual
            // condition this skip means to express.
            if (desiredRecurring.get(t.getId())) continue;
            boolean subscriptionRuleMatch = ruleEngineService.evaluateSideEffectRules(
                            sideEffectRules, t.getDescription(), t.getAmount(), t.getMerchant(), null).stream()
                    .anyMatch(m -> m.rule().getActionType() == CategoryRule.ActionType.MARK_SUBSCRIPTION);
            if (subscriptionRuleMatch) desiredRecurring.put(t.getId(), true);
        }

        // Bug 10 (docs/quality/bug-reports/BUG_REVIEW_REPORT.md) / see this class's own doc
        // comment. Mutate and persist ONLY the transactions whose flag this run actually changes
        // -- an unchanged transaction is never touched, so it never version-bumps and can never
        // produce an OptimisticLockingFailureException against a concurrent identical run, and a
        // run that changes nothing writes no audit row either.
        List<Transaction> changed = active.stream()
                .filter(t -> t.isRecurring() != desiredRecurring.get(t.getId()))
                .toList();
        changed.forEach(t -> t.setRecurring(desiredRecurring.get(t.getId())));
        if (!changed.isEmpty()) {
            transactionRepository.saveAll(changed);
        }
        results.sort(Comparator.comparing(RecurringDto::nextEstimate));

        // Financial Intelligence Workspace, Reconciliation Monitor -- same one-summary-per-run
        // convention as ReconciliationService.reconcileForUser(), see that method's own doc
        // comment. recurringGroupsFound is results.size() (pattern-detected groups only, not
        // rule-matched singles -- a MARK_SUBSCRIPTION match on one transaction isn't a "group"
        // the way 2+ regularly-spaced charges are); recurringTransactionsFlagged counts every
        // transaction this run considers recurring, from either signal -- desiredRecurring's
        // values, not active's in-memory isRecurring(), since an unchanged transaction was
        // deliberately left unmutated above.
        if (!changed.isEmpty()) {
            long recurringTransactionsFlagged = desiredRecurring.values().stream().filter(Boolean::booleanValue).count();
            auditService.record(userId, "RECURRING_DETECTION_RUN", "Transaction", null, Map.of(
                    "transactionsProcessed", active.size(),
                    "recurringGroupsFound", results.size(),
                    "recurringTransactionsFlagged", recurringTransactionsFlagged));
        }

        return results;
    }
}
