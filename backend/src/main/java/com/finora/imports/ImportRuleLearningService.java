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
 */
@Component
public class ImportRuleLearningService {

    private final CategorizationService categorizationService;

    public ImportRuleLearningService(CategorizationService categorizationService) {
        this.categorizationService = categorizationService;
    }

    /**
     * Returns true when the row's category was never actually resolved (the engine's original
     * guess was "default" — no rule/learned match, i.e. it fell through to Other — and review
     * left it as Other). Only a real decision teaches the merchant map: learning from an
     * unresolved guess would poison the merchant map with "this merchant = Other" for no reason.
     * Callers should set the transaction's needsCategoryReview flag to the returned value.
     */
    public boolean recordDecision(UUID userId, ConfirmedRow row, Category category) {
        boolean isUnresolvedGuess = "default".equals(row.categorySource()) && "Other".equals(row.category());
        if (!isUnresolvedGuess) {
            categorizationService.learn(userId, row.description(), category.getId());
        }
        // This IS the actual write, unlike the suggest() call at staging time (preview, possibly
        // never confirmed) -- row.ruleId() is the same ASSIGN_CATEGORY rule id resolved there and
        // carried through review unchanged, so it's recorded here rather than by re-evaluating
        // rules against the confirmed row.
        categorizationService.recordRuleMatch(row.ruleId());
        return isUnresolvedGuess;
    }
}
