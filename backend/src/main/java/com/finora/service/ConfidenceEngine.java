package com.finora.service;

import com.finora.entity.MerchantCategoryLearning;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Confidence is derived entirely from real evidence — a category's share of a merchant's total
 * confirmations — not an arbitrarily incremented score. Amazon confirmed 147 times as Shopping,
 * 34 as Electronics, 12 as Books is genuinely 147/193 = 76% confident it's Shopping, not "some
 * formula that goes up when you confirm it enough times."
 *
 * This is a single-signal model (confirmation history only) by design for now — see the
 * project README for why layering in additional signals (description similarity, amount
 * patterns, etc.) is deferred until there's evidence this signal alone is insufficient.
 */
@Service
public class ConfidenceEngine {

    public static final int INITIAL_RULE_CONFIDENCE = 70;
    public static final int INITIAL_DEFAULT_CONFIDENCE = 20;

    /**
     * A structurally-detected person-to-person transfer (see
     * {@link com.finora.util.PersonToPersonTransferDetector}).
     *
     * <p>Its own value rather than {@link #INITIAL_RULE_CONFIDENCE}, and deliberately below any
     * plausible {@code autoApplyConfidenceThreshold}. Emitting it at 70 -- the same number a
     * verified keyword match earns -- made the row assert rule-grade certainty while
     * {@code CategorizationService.isUnconfirmedGuess} simultaneously declared it unconfirmed, and
     * the contradiction was load-bearing: {@code needsCategoryReview} clears the review flag as
     * soon as confidence meets the user's threshold, so any user who lowered theirs to 70 or below
     * silently auto-applied every structural guess unreviewed -- strictly weaker than the
     * pre-detector behaviour, where the same row was a 20-confidence "default" and got flagged.
     *
     * <p>Above {@link #INITIAL_DEFAULT_CONFIDENCE} because this is a real positive signal rather
     * than "nothing matched", but low enough that the review flag is unconditional in practice.
     */
    public static final int INITIAL_STRUCTURAL_CONFIDENCE = 40;
    // Schema default for WorkspaceSettings.autoApplyConfidenceThreshold (see V22 migration and
    // WorkspaceSettingsService) -- kept in sync manually since that table's default is duplicated
    // in SQL, not read from this constant, to avoid a migration-time dependency on service code.
    public static final int DEFAULT_AUTO_APPLY_THRESHOLD = 90;

    /** Confidence for a brand-new merchant with exactly one confirmation and no history yet. */
    public int initialConfidence(String categorizationSource) {
        return switch (categorizationSource) {
            case "rule", "learned" -> INITIAL_RULE_CONFIDENCE;
            default -> INITIAL_DEFAULT_CONFIDENCE;
        };
    }

    /** Recomputes every category's confidence for a merchant as its share of the total
     *  confirmations across all categories that merchant has ever been assigned. */
    public Map<java.util.UUID, Integer> recomputeDistribution(List<MerchantCategoryLearning> allPairsForMerchant) {
        int total = allPairsForMerchant.stream().mapToInt(MerchantCategoryLearning::getConfirmationCount).sum();
        if (total == 0) return Map.of();
        return allPairsForMerchant.stream().collect(Collectors.toMap(
                MerchantCategoryLearning::getCategoryId,
                p -> (int) Math.round((p.getConfirmationCount() * 100.0) / total)
        ));
    }

    /** The category with the most confirmations for this merchant — "what would we auto-apply."
     *
     *  Bug fix: an exact confirmationCount tie (e.g. one confirmation each for two categories --
     *  a real, reachable case, not an edge case: it's exactly what a single conflicting
     *  confirmation produces, see MerchantLearningService.confirm()'s CORRECTED-audit path) used
     *  to resolve arbitrarily -- Stream.max() keeps the FIRST element it sees on a tie, so the
     *  answer silently depended on list/DB retrieval order rather than any real signal.
     *
     *  Breaking ties by lastConfirmedAt alone isn't quite enough either: two confirmations made
     *  microseconds apart can land in the same Instant.now() tick (a real, observed gap on
     *  Windows' coarser clock resolution -- not a hypothetical), which just moves the same
     *  "arbitrary tie" problem one level down. Sorting ascending (stable -- ties keep the list's
     *  own order, which is confirmation/insertion order both here and for a plain
     *  findByUserIdAndMerchantId query with no ORDER BY) and taking the last element instead means
     *  a genuine timestamp difference decides it when the clock can tell them apart, and "most
     *  recently confirmed" (by list position) decides it when the clock can't -- never an
     *  unexplained pick. Matches the same "the newest confirmation is authoritative" reasoning
     *  confirm() already applies when deciding LEARNED vs CORRECTED, and the same stable-sort-
     *  then-take-the-recent-end technique MerchantLearningService.timeline() uses for its own
     *  createdAt-tie problem. */
    public MerchantCategoryLearning topCategory(List<MerchantCategoryLearning> allPairsForMerchant) {
        if (allPairsForMerchant.isEmpty()) return null;
        List<MerchantCategoryLearning> byRecency = new ArrayList<>(allPairsForMerchant);
        byRecency.sort(Comparator.comparingInt(MerchantCategoryLearning::getConfirmationCount)
                .thenComparing(MerchantCategoryLearning::getLastConfirmedAt));
        return byRecency.get(byRecency.size() - 1);
    }

    public boolean meetsAutoApplyThreshold(int confidence, int threshold) {
        return confidence >= threshold;
    }
}
