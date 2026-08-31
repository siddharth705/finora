package com.finora.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record InsightsDto(
        List<String> sentences,
        List<CategoryMover> movers,
        CoverageCaveat coverageCaveat
) {
    public record CategoryMover(String category, BigDecimal current, BigDecimal priorAverage, Double pctChange) {}

    /**
     * Phase 3 of docs/proposals/statement-continuity-and-coverage-integrity-proposal.md (§0.5/§8)
     * -- populated only when the current reporting month intersects a known coverage gap on any of
     * the user's live accounts, null otherwise. Internal/API-only for now, per §0.5: no UI reads
     * this field yet, but the shape exists now rather than being added later as a breaking change,
     * so a future feature never has to reinvent "does Insights know this month might be incomplete."
     */
    public record CoverageCaveat(String month, List<GapWindow> gaps) {
        public record GapWindow(LocalDate gapStart, LocalDate gapEnd) {}
    }
}
