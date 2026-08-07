package com.finora.imports;

import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.entity.Category;
import com.finora.service.CategorizationService;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Decides, for each confirmed row, whether the category assignment was a real decision worth
 * teaching the merchant-learning map, or an unresolved guess the user just left alone. Named
 * distinctly from the top-level RuleEngineService (global/user category_rules matching) — this
 * class is specifically about the import pipeline's confirm-time learning step.
 *
 * <p><b>WI1 changed what this class does with that decision.</b> It used to apply the learning
 * itself, calling {@code CategorizationService.learn} inline, which put a synchronous merchant
 * confirmation inside the import's transaction once per confirmed row — the direct cause of Bug 02.
 * It now only DECIDES, and returns that decision; {@code ImportService} queues the learning after
 * the statement import row exists, and a worker applies it after the transaction commits. Deciding
 * and applying were never the same job; they only looked like it while both were synchronous.
 */
@Component
public class ImportRuleLearningService {

    private final CategorizationService categorizationService;

    public ImportRuleLearningService(CategorizationService categorizationService) {
        this.categorizationService = categorizationService;
    }

    /**
     * What this row taught, if anything.
     *
     * @param unresolvedGuess the row's category was never actually resolved — the caller sets the
     *                        transaction's {@code needsCategoryReview} flag from this
     * @param worthLearning   whether a merchant-learning confirmation should be queued for this row
     */
    public record Decision(boolean unresolvedGuess, boolean worthLearning) {}

    /**
     * Records the rule match for a confirmed row and reports whether its category assignment is
     * worth learning from.
     *
     * <p>An unresolved guess (the engine's original guess was "default" — no rule or learned match,
     * so it fell through to Other — and review left it as Other) teaches nothing. Learning from one
     * would poison the merchant map with "this merchant = Other" for no reason.
     *
     * <p>No longer applies the learning itself: see this class's doc comment.
     */
    public Decision recordDecision(UUID userId, ConfirmedRow row, Category category) {
        boolean isUnresolvedGuess = "default".equals(row.categorySource()) && "Other".equals(row.category());

        // This IS the actual write, unlike the suggest() call at staging time (preview, possibly
        // never confirmed) -- row.ruleId() is the same ASSIGN_CATEGORY rule id resolved there and
        // carried through review unchanged, so it's recorded here rather than by re-evaluating
        // rules against the confirmed row.
        categorizationService.recordRuleMatch(row.ruleId());

        return new Decision(isUnresolvedGuess, !isUnresolvedGuess);
    }
}
