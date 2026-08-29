package com.finora.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DashboardSummaryDto(
        BigDecimal currentBalance,
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        BigDecimal netWorth,
        BigDecimal monthlyIncome,
        BigDecimal monthlyExpense,
        BigDecimal netCashFlow,
        BigDecimal savingsRatePct,
        Double incomeDeltaPct,
        Double expenseDeltaPct,
        Double netDeltaPct,
        Integer healthScore,
        String healthLabel,
        Map<String, Double> healthBreakdown,
        // D-25 PR3-A: a score computed from a handful of transactions is a harsh first impression
        // that isn't actually wrong data, just too little of it. Below MIN_TRANSACTIONS_FOR_HEALTH_SCORE
        // (DashboardService), healthScore/healthLabel are null and healthBreakdown is empty -- the
        // client shows a "Getting Started X/N transactions" progress state instead of guessing what
        // an incomplete score means. Deliberately a real transactionCount + minTransactions pair
        // rather than a bare boolean, so the client can render "7 / 10" without hardcoding the floor.
        boolean healthScoreAvailable,
        int healthScoreTransactionCount,
        int healthScoreMinTransactions,
        Map<String, BigDecimal> spendByCategory,
        List<String> notifications,

        /*
         * Which month monthlyIncome/monthlyExpense/netCashFlow/savingsRatePct/spendByCategory and
         * the three delta percentages actually describe -- "2026-07", or null for an account with
         * no transactions at all.
         *
         * Bug 05: these figures are the newest month the user has DATA for, which is the right
         * reporting period for a product built around importing statements in arrears, but the
         * response never said so. The client had nothing to label them with and labelled them
         * "this month" / "vs last month", so a user who had not yet transacted in August read
         * July's figures as August's. Reporting on July is fine; claiming July is August is not.
         *
         * Deliberately NOT the period the budget notifications use -- those are measured against a
         * monthly allowance and follow the calendar month instead. See DashboardService.summarize
         * and ReportingPeriod for why the two differ.
         */
        String reportingMonth,
        boolean reportingMonthIsCurrent,

        /*
         * True when the user has fewer than DashboardService.LIMITED_HISTORY_MONTH_FLOOR distinct
         * calendar months of transaction data. Trend deltas (incomeDeltaPct etc.) and the health
         * score are both still COMPUTED at this point -- neither is hidden -- but both are prone to
         * thin-data artifacts this far below that floor (a near-empty prior-month denominator for
         * the deltas; a health score built from too few comparable months). historyMonthCount and
         * the floor itself are included so the client can render "X / N months" without
         * hardcoding the threshold, mirroring how healthScoreTransactionCount/minTransactions
         * already work.
         */
        boolean limitedHistory,
        int historyMonthCount,
        int limitedHistoryMonthFloor,
        int statementCount,
        int accountCount,

        /*
         * True when categoryReviewSpendPct of this month's spend -- transactions Transaction.
         * needsCategoryReview flags, the same signal Ledger's "needs review" badge already uses --
         * is at or above categoryReviewSpendWarningThresholdPct (DashboardService's
         * CATEGORY_REVIEW_SPEND_WARNING_THRESHOLD_PCT). Deliberately NOT "Uncategorized" nor
         * "Other" by category name: "Other" is a real, resolvable category (CategoryRules'
         * fallback when nothing matched), so a transaction landing there isn't necessarily
         * uncategorized -- it just means the categorization engine's own confidence check flagged
         * it. categoryReviewSpendAmount/categoryReviewTransactionCount are the raw numbers behind
         * the flag; categoryReviewSpendWarningThresholdPct is included so the client never
         * hardcodes the cutoff, mirroring how limitedHistoryMonthFloor already works.
         */
        boolean categoryReviewWarning,
        double categoryReviewSpendPct,
        BigDecimal categoryReviewSpendAmount,
        int categoryReviewTransactionCount,
        double categoryReviewSpendWarningThresholdPct,

        /*
         * Why incomeDeltaPct/expenseDeltaPct/netDeltaPct came back null when the user might expect
         * a number -- one of "PARTIAL_PRIOR_MONTH" (the prior calendar month is really just the
         * ragged edge of the same continuous statement window the current month came from, not a
         * genuine separate month -- see DashboardService.isPartialBoundaryMonth) or
         * "TOO_FEW_PRIOR_TRANSACTIONS" (a real, full prior month, but with fewer than
         * comparisonGateMinTransactions transactions of its own, so a stray row or two could still
         * dominate the ratio). Null whenever the three deltas are either real numbers or null for an
         * unrelated, self-explanatory reason (no prior period at all, or a genuinely zero prior
         * amount) that doesn't need a "Why?" disclosure. All three deltas share one gate/reason:
         * DashboardService computes a single priorMonthReliable boolean and applies it to all three,
         * so there's nothing to say per-metric that isn't already said once here. Mirrors how
         * limitedHistoryMonthFloor/categoryReviewSpendWarningThresholdPct already avoid the client
         * hardcoding a threshold.
         */
        String comparisonGateReason,
        int comparisonGateMinTransactions,

        /*
         * The categories behind a real (non-null) expenseDeltaPct -- e.g. "Dining ₹8,000 vs ₹5,000
         * (+60%)" instead of leaving "expenses are up 12%" with no explanation of which categories
         * moved. Built from the SAME currentMonth/priorMonth spendByCategory comparison
         * expenseDeltaPct itself comes from (DashboardService.categoryMovers), NOT
         * InsightsService's rolling 3-month-average movers -- a different prior-period definition
         * that would make this list disagree with the number it's meant to explain. Always empty
         * when expenseDeltaPct is null: there's nothing to explain about a number that isn't being
         * shown (comparisonGateReason above already covers why not). Ranked by rupee contribution
         * to the delta and capped at 3, largest first.
         */
        List<CategoryMover> expenseCategoryMovers,

        /*
         * Detected Issues. ReconciliationService's own duplicate pass (see Transaction.
         * isDuplicateOf/ReconciliationStatus.DUPLICATE) already silently excludes a row from every
         * total above the moment it runs -- RefundNetting.reportable() drops anything with
         * isDuplicateOf set -- and until now nothing told the user it happened.
         * TransactionService.confirmNotDuplicate (BH-027, "no, these really are two separate
         * transactions") already existed to let a human overrule that guess; it simply had no
         * caller anywhere in the product. This doesn't compute a new verdict -- it surfaces the one
         * already sitting on the row, the same "thin, presentation-only read" reasoning
         * TransactionExplanationService's own doc comment gives for "Why this category?".
         * duplicateTransactionCount is the TRUE, uncapped total so the client can say "N found"
         * without hardcoding DashboardService.DETECTED_DUPLICATES_DISPLAY_LIMIT, mirroring how
         * limitedHistoryMonthFloor already avoids a hardcoded threshold; detectedDuplicates is the
         * capped, newest-first list the card actually renders.
         */
        int duplicateTransactionCount,
        List<DetectedDuplicate> detectedDuplicates,

        /*
         * Categorization Confidence. How sure the categorization ENGINE was, on average (0-100,
         * same scale as Transaction.decisionConfidence), about the categories it assigned THIS
         * MONTH -- a positive, ongoing data-quality signal, distinct from categoryReviewWarning
         * above (which only fires when spend is badly miscategorized). Null below
         * categorizationConfidenceMinTransactions decisioned transactions this month (an average of
         * one or two decisions reads as confident or shaky by chance, not by anything real about
         * the engine) -- mirrors how healthScoreAvailable gates the health score below its own
         * floor. categorizationConfidenceTransactionCount/categorizationConfidenceMinTransactions
         * are included so the client never hardcodes the floor, same as
         * healthScoreTransactionCount/healthScoreMinTransactions already do.
         */
        Integer categorizationConfidenceScore,
        int categorizationConfidenceTransactionCount,
        int categorizationConfidenceMinTransactions
) {
    public record CategoryMover(String category, BigDecimal currentAmount, BigDecimal priorAmount, Double pctChange) {}

    public record DetectedDuplicate(UUID transactionId, LocalDate date, String merchant, BigDecimal amount) {}
}
