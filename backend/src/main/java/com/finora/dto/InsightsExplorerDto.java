package com.finora.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A user's dashboard insights, traced back to the transaction set and formula that produced each
 * number -- Phase 2's Founder Operations Dashboard, Insight Explorer (docs/proposals/
 * reconciliation-evolution-roadmap-proposal.md, Part 9): "since InsightsService computes
 * everything on the fly with no persistence, this explorer's job is to re-run that computation in
 * a debug mode that logs its inputs instead of just returning the final number." Assembled, not
 * aggregated -- same position {@code ReconciliationExplorerDto} and {@code ImportTraceDto} both
 * take.
 */
public final class InsightsExplorerDto {

    private InsightsExplorerDto() {
        // Namespace for the nested records, per CODING_STANDARDS' DTO convention.
    }

    /**
     * {@code reportingMonth} is null and every number is null when the user has no reportable
     * expense transactions at all -- the same state {@code InsightsService.build()} answers with
     * its own "upload or add transactions" sentence, not a lookup failure.
     */
    public record Trace(UUID userId, String reportingMonth, boolean reportingMonthIsCurrent,
                         TotalSpend totalSpend, TopCategory topCategory, TopMerchant topMerchant) {}

    /** The exact figure {@code InsightsService}'s "total spend" sentence reports, with the
     *  transactions that were summed to produce it. */
    public record TotalSpend(BigDecimal amount, int categoryCount, List<TracedTransaction> transactions) {}

    /** The exact category {@code InsightsService}'s "biggest category" sentence names. */
    public record TopCategory(String category, BigDecimal amount, List<TracedTransaction> transactions) {}

    /** The exact merchant {@code InsightsService}'s "top merchant" sentence names. */
    public record TopMerchant(String merchant, BigDecimal amount, List<TracedTransaction> transactions) {}

    /** One transaction that fed into a traced number. {@code reportableAmount} is what actually
     *  counted -- see {@code RefundNetting} -- and can differ from {@code rawAmount} when a refund
     *  was netted off this expense; the gap between the two IS the trace for that transaction. */
    public record TracedTransaction(UUID transactionId, String description, BigDecimal rawAmount,
                                     BigDecimal reportableAmount, LocalDate txnDate) {}
}
