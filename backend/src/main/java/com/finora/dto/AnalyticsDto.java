package com.finora.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Backs GET /api/v1/analytics/merchants (spec §5.7) and, as of the Financial Intelligence
 *  Workspace's Analytics module, the same endpoint's topCategories/importStatistics/
 *  learningGrowth views. See AnalyticsService's class comment. */
public class AnalyticsDto {

    public record TopMerchant(UUID merchantId, String merchantName, BigDecimal totalSpend, int transactionCount) {}

    /** month is "YYYY-MM" (matches YearMonth.toString()), oldest first -- a line chart's natural x-axis order. */
    public record TrendPoint(String month, BigDecimal totalSpend) {}

    /** avgConfidence: mean confidence across every merchant whose current top category is this
     *  one. merchantCount: how many merchants that average is over -- shown alongside the bar so
     *  a category backed by 1 merchant doesn't read as equally reliable as one backed by 40. */
    public record CategoryConfidencePoint(String category, int avgConfidence, int merchantCount) {}

    /** Same shape as TopMerchant, grouped by category instead of merchant -- Workspace Analytics'
     *  "Top Categories" view. */
    public record TopCategory(UUID categoryId, String categoryName, BigDecimal totalSpend, int transactionCount) {}

    /** Workspace Analytics' "Import Statistics" view -- aggregated over StatementImport, not a
     *  new table. lastImportedAt is null when the user has never imported a statement. */
    public record ImportStatistics(int totalStatements, int totalTransactionsImported,
                                    int totalTransactionsSkipped, Instant lastImportedAt) {}

    /** Workspace Analytics' "Learning Growth" view -- LEARNED vs CORRECTED MerchantLearningAudit
     *  entries per month, oldest first (same x-axis convention as TrendPoint). CORRECTED entries
     *  matter as their own series, not just noise folded into "activity": a rising CORRECTED
     *  count month over month is a real signal the engine's guesses are getting overridden more
     *  often, not less -- the opposite of what "learning growth" should look like if it's working. */
    public record LearningGrowthPoint(String month, long learnedCount, long correctedCount) {}
}
