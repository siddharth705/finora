package com.finora.service;

import com.finora.entity.Category;
import com.finora.entity.CategoryRule;
import com.finora.entity.Merchant;
import com.finora.entity.MerchantCategoryLearning;
import com.finora.entity.Transaction;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.util.CategoryRules;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The public categorization contract used by TransactionService/CsvImportService. Internally
 * backed by the Merchant Intelligence system (MerchantNormalizationEngine, MerchantCategoryLearning
 * distribution, MerchantLearningService, ConfidenceEngine) rather than the older flat
 * merchant_category_map table — that older system is no longer written to by this class, though
 * its table/entity/repository are left in place rather than dropped (see V7 migration comment).
 *
 * The external contract (Suggestion's category/source values: "learned" | "rule" | "default")
 * is deliberately unchanged from before this rewiring, so TransactionService and CsvImportService
 * — which both branch on those exact string values to decide review-flagging — didn't need to
 * change their decision logic, only start reading the new merchantId field this adds.
 */
@Service
public class CategorizationService {

    private final MerchantNormalizationEngine merchantNormalizationEngine;
    private final MerchantLearningService merchantLearningService;
    private final MerchantCategoryLearningRepository learningRepository;
    private final ConfidenceEngine confidenceEngine;
    private final CategoryRepository categoryRepository;
    private final RuleEngineService ruleEngineService;

    public CategorizationService(MerchantNormalizationEngine merchantNormalizationEngine,
                                  MerchantLearningService merchantLearningService,
                                  MerchantCategoryLearningRepository learningRepository,
                                  ConfidenceEngine confidenceEngine,
                                  CategoryRepository categoryRepository,
                                  RuleEngineService ruleEngineService) {
        this.merchantNormalizationEngine = merchantNormalizationEngine;
        this.merchantLearningService = merchantLearningService;
        this.learningRepository = learningRepository;
        this.confidenceEngine = confidenceEngine;
        this.categoryRepository = categoryRepository;
        this.ruleEngineService = ruleEngineService;
    }

    // source() keeps emitting the pre-existing string contract ("learned" | "rule" | "default" |
    // "file") that TransactionService/ImportService already branch on via equals("default") --
    // see decisionSourceFor() below for the full mapping to the richer, persisted enum.
    // "user_rule"/"global_rule" are new values nothing existing branches on (safe to add).
    // decisionSource/ruleId are the new fields: decisionSource is always set; ruleId is non-null
    // only when a category_rules row (not the static keyword table) produced this suggestion.
    public record Suggestion(String category, String source, UUID merchantId,
                              Transaction.DecisionSource decisionSource, UUID ruleId) {}

    /** Rule engine (user rules, then global rules) > learned distribution (real evidence) >
     *  keyword rules > "Other". See docs/rule-engine-relationship-engine-eds.md §4. */
    public Suggestion suggest(UUID userId, String description) {
        return suggest(userId, description, null, null);
    }

    /** amount/accountType are optional rule-evaluation context a caller may not have yet (e.g. a
     *  bare description-only preview) -- null is handled safely by RuleEngineService (a rule
     *  whose field it can't be given simply never matches). */
    public Suggestion suggest(UUID userId, String description, BigDecimal amount, String accountType) {
        Merchant merchant = merchantNormalizationEngine.resolve(userId, description);

        var ruleMatch = ruleEngineService.evaluateCategoryRule(userId, description, amount, merchant.getCanonicalName(), accountType);
        if (ruleMatch.isPresent()) {
            CategoryRule rule = ruleMatch.get().rule();
            boolean isUserRule = ruleMatch.get().isUserScope();
            return new Suggestion(rule.getActionValue(), isUserRule ? "user_rule" : "global_rule", merchant.getId(),
                    isUserRule ? Transaction.DecisionSource.USER_RULE : Transaction.DecisionSource.GLOBAL_RULE, rule.getId());
        }

        List<MerchantCategoryLearning> distribution = learningRepository.findByUserIdAndMerchantId(userId, merchant.getId());
        if (!distribution.isEmpty()) {
            MerchantCategoryLearning top = confidenceEngine.topCategory(distribution);
            if (top != null) {
                Category cat = categoryRepository.findById(top.getCategoryId()).orElse(null);
                if (cat != null) {
                    return new Suggestion(cat.getName(), "learned", merchant.getId(), Transaction.DecisionSource.LEARNED_PATTERN, null);
                }
            }
        }

        String ruleCat = CategoryRules.suggestCategory(description);
        boolean matchedKeyword = !ruleCat.equals("Other");
        return new Suggestion(ruleCat, matchedKeyword ? "rule" : "default", merchant.getId(),
                matchedKeyword ? Transaction.DecisionSource.KEYWORD_MATCH : Transaction.DecisionSource.MERCHANT_DEFAULT, null);
    }

    /** Maps the persisted-through-review categorySource string (StagedRow/ConfirmedRow,
     *  ImportDto) back to the richer decision_source enum at confirm time -- see EDS §3.2. Also
     *  used anywhere else a categorySource string needs the same translation. Defensive default
     *  (MERCHANT_DEFAULT) for any unrecognized/legacy value rather than throwing mid-import. */
    public static Transaction.DecisionSource decisionSourceFor(String categorySource) {
        if (categorySource == null) return Transaction.DecisionSource.MERCHANT_DEFAULT;
        return switch (categorySource) {
            case "user_rule" -> Transaction.DecisionSource.USER_RULE;
            case "global_rule" -> Transaction.DecisionSource.GLOBAL_RULE;
            case "learned" -> Transaction.DecisionSource.LEARNED_PATTERN;
            case "rule" -> Transaction.DecisionSource.KEYWORD_MATCH;
            case "file" -> Transaction.DecisionSource.FILE_PROVIDED;
            default -> Transaction.DecisionSource.MERCHANT_DEFAULT;
        };
    }

    /** Called whenever a user sets/corrects a transaction's category — records a real
     *  confirmation against the merchant's distribution (with audit trail + undo support),
     *  not a flat overwrite of a single "last category" value.
     *
     *  <p>Note this path does NOT yet satisfy spec Section 10 ("Learning update fails after a
     *  transaction has been categorized" -- do NOT rollback the category). A failure inside
     *  confirm() still propagates and still takes the caller's transaction with it; a try/catch
     *  here would not change that, because a constraint violation has already marked the shared
     *  transaction rollback-only by the time it could be caught. See
     *  {@link MerchantLearningService#confirm}'s doc comment for why the obvious
     *  {@code REQUIRES_NEW} fix is unavailable and what closing it actually takes. */
    public void learn(UUID userId, String description, UUID categoryId) {
        Merchant merchant = merchantNormalizationEngine.resolve(userId, description);
        merchantLearningService.confirm(userId, merchant.getId(), categoryId);
    }

    /** Records that an ASSIGN_CATEGORY rule match actually reached a persisted transaction --
     *  see RuleEngineService.recordMatch's doc comment for why this is a separate explicit call
     *  from TransactionService.create()/CsvImportService.confirm() rather than being folded into
     *  suggest() itself (suggest() also runs at staging/preview time, before anything is
     *  persisted). No-ops when ruleId is null (suggest() returns null whenever the category came
     *  from learning or the keyword table, not a rule). */
    public void recordRuleMatch(UUID ruleId) {
        ruleEngineService.recordMatch(ruleId);
    }

    /** Resolves a transaction's merchant identity without touching categorization — used by
     *  callers (TransactionService, CsvImportService) so every transaction gets a merchant_id
     *  regardless of whether its category came from a suggestion or an explicit user choice. */
    public UUID resolveMerchantId(UUID userId, String description) {
        return merchantNormalizationEngine.resolve(userId, description).getId();
    }

    /**
     * Applies every matching non-ASSIGN_CATEGORY rule to a transaction in place -- see
     * CategoryRule.ActionType's doc comment. Callers (TransactionService.create(),
     * CsvImportService.confirm()) run this AFTER the transaction's primary category is already
     * set (from suggest() or an explicit user choice) and every other field (description,
     * amount, merchant, txnType) is populated, since rule matching needs all of it.
     *
     * A matching MARK_INVESTMENT rule deliberately overrides whatever category suggest() already
     * picked -- that's the point of a MARK_INVESTMENT rule existing at all (a known pattern like
     * "SIP" should always land in Investments, not whatever the keyword table's Other/default
     * fallback would have guessed).
     *
     * MARK_SUBSCRIPTION is intentionally NOT applied here. RecurringService fully recomputes
     * every active transaction's `recurring` flag from scratch (reset-then-recompute) on every
     * call -- see that class's own doc comment -- so a write-time isRecurring=true set here would
     * just get silently wiped out the next time anyone loads the Recurring page. RecurringService
     * itself consults RuleEngineService for MARK_SUBSCRIPTION matches as a second signal alongside
     * its own pattern detection, which is the correct place for that one action type to live.
     */
    public Category applySideEffectRules(UUID userId, Transaction t) {
        Merchant merchant = merchantNormalizationEngine.resolve(userId, t.getDescription());
        List<RuleEngineService.RuleMatch> matches = ruleEngineService.evaluateSideEffectRules(
                userId, t.getDescription(), t.getAmount(), merchant.getCanonicalName(), null);

        // Non-null only when a MARK_INVESTMENT rule actually changed the category -- callers
        // (TransactionService.create(), CsvImportService.confirm()) use this to keep their own
        // already-resolved `category` variable (used for tally/response display) in sync with
        // what actually landed on the transaction, rather than a repeat categoryId->name lookup.
        Category newCategory = null;

        for (RuleEngineService.RuleMatch match : matches) {
            CategoryRule rule = match.rule();
            // Safe to record here unconditionally (unlike ASSIGN_CATEGORY, see recordRuleMatch's
            // doc comment) -- applySideEffectRules() has exactly two callers, both at actual
            // write time (TransactionService.create(), CsvImportService.confirm()), never at
            // staging/preview.
            ruleEngineService.recordMatch(rule.getId());
            switch (rule.getActionType()) {
                case MARK_TRANSFER -> {
                    t.setTransfer(true);
                    t.setReconciliationStatus(Transaction.ReconciliationStatus.TRANSFER);
                }
                case MARK_INVESTMENT -> {
                    String categoryName = (rule.getActionValue() != null && !rule.getActionValue().isBlank())
                            ? rule.getActionValue() : "Investments";
                    newCategory = resolveOrCreateCategory(userId, categoryName);
                    t.setCategoryId(newCategory.getId());
                }
                case ADD_TAG -> {
                    if (rule.getActionValue() != null && !rule.getActionValue().isBlank()) {
                        List<String> tags = t.getTags() != null ? new ArrayList<>(t.getTags()) : new ArrayList<>();
                        if (!tags.contains(rule.getActionValue())) {
                            tags.add(rule.getActionValue());
                            t.setTags(tags);
                        }
                    }
                }
                case MARK_SUBSCRIPTION, ASSIGN_CATEGORY -> {
                    // MARK_SUBSCRIPTION: handled by RecurringService, see this method's doc comment.
                    // ASSIGN_CATEGORY: never reaches here -- evaluateSideEffectRules() excludes it.
                }
            }
        }
        return newCategory;
    }

    public Category resolveOrCreateCategory(UUID userId, String name) {
        return categoryRepository.findByUserIdAndName(userId, name).orElseGet(() -> {
            Category c = new Category();
            c.setUserId(userId);
            c.setName(name);
            c.setSystem(false);
            return categoryRepository.save(c);
        });
    }
}
