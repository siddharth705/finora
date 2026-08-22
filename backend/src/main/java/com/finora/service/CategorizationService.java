package com.finora.service;

import com.finora.entity.Category;
import com.finora.entity.CategoryRule;
import com.finora.entity.Merchant;
import com.finora.entity.MerchantCategoryLearning;
import com.finora.entity.Transaction;
import com.finora.exception.ApiException;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.util.CategoryRules;
import org.springframework.http.HttpStatus;
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
    private final MerchantLearningEventPublisher learningEventPublisher;
    private final MerchantCategoryLearningRepository learningRepository;
    private final ConfidenceEngine confidenceEngine;
    private final CategoryRepository categoryRepository;
    private final RuleEngineService ruleEngineService;

    public CategorizationService(MerchantNormalizationEngine merchantNormalizationEngine,
                                  MerchantLearningService merchantLearningService,
                                  MerchantLearningEventPublisher learningEventPublisher,
                                  MerchantCategoryLearningRepository learningRepository,
                                  ConfidenceEngine confidenceEngine,
                                  CategoryRepository categoryRepository,
                                  RuleEngineService ruleEngineService) {
        this.merchantNormalizationEngine = merchantNormalizationEngine;
        this.merchantLearningService = merchantLearningService;
        this.learningEventPublisher = learningEventPublisher;
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

    /** The read-only counterpart of {@link #suggest(UUID, String)}, for the same description-only
     *  callers -- same matching, same precedence, no merchant/alias writes. */
    public Suggestion suggestReadOnly(UUID userId, String description) {
        return suggestReadOnly(userId, description, null, null);
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

    /**
     * The suggestion a PREVIEW gets: identical logic, zero writes (WI3).
     *
     * <p>{@link #suggest} resolves the merchant through
     * {@code MerchantNormalizationEngine.resolve}, which CREATES a merchant and an alias on a miss.
     * That is right at confirm time and wrong at staging time, where the user may never import the
     * file at all — and it is Bug 36: uploading a statement, seeing the parse is wrong and
     * abandoning it still left a merchant row for every distinct description in it.
     *
     * <p>Everything else is deliberately the same, in the same order: user rules, then global
     * rules, then the learned distribution, then keywords, then Other. A preview that used
     * different logic from the confirm behind it would show the user a category they are not going
     * to get, which is a worse failure than the writes this removes.
     *
     * <p>merchantId comes back null when no merchant exists yet. Nothing downstream needs it —
     * {@code StagedRow} carries no merchant field, because the merchant is created at confirm time
     * alongside the transaction it belongs to.
     */
    public Suggestion suggestReadOnly(UUID userId, String description, BigDecimal amount, String accountType) {
        return suggestReadOnly(ruleEngineService.ruleSet(userId), userId, description, amount, accountType);
    }

    /**
     * As {@link #suggestReadOnly(UUID, String, BigDecimal, String)}, against a rule set the caller
     * already loaded once.
     *
     * <p>Exists for the import staging loop, which calls this once per row: the loading overload
     * re-queried {@code category_rules} twice for every row of the statement, always with the same
     * two results. The rule set must come from {@code RuleEngineService.ruleSet(userId)} so
     * USER-before-GLOBAL precedence is preserved.
     *
     * <p>Only the rule lookup is hoisted. Merchant resolution and the learned-category
     * distribution below still run per row -- they genuinely depend on the row's description, and
     * batching them is a separate, larger change with its own design review.
     */
    public Suggestion suggestReadOnly(List<CategoryRule> rules, UUID userId, String description,
                                       BigDecimal amount, String accountType) {
        var merchant = merchantNormalizationEngine.resolveReadOnly(userId, description);
        String merchantName = merchant.map(Merchant::getCanonicalName).orElse(null);
        UUID merchantId = merchant.map(Merchant::getId).orElse(null);

        var ruleMatch = ruleEngineService.evaluateCategoryRule(rules, description, amount, merchantName, accountType);
        if (ruleMatch.isPresent()) {
            CategoryRule rule = ruleMatch.get().rule();
            boolean isUserRule = ruleMatch.get().isUserScope();
            return new Suggestion(rule.getActionValue(), isUserRule ? "user_rule" : "global_rule", merchantId,
                    isUserRule ? Transaction.DecisionSource.USER_RULE : Transaction.DecisionSource.GLOBAL_RULE, rule.getId());
        }

        if (merchantId != null) {
            List<MerchantCategoryLearning> distribution = learningRepository.findByUserIdAndMerchantId(userId, merchantId);
            if (!distribution.isEmpty()) {
                MerchantCategoryLearning top = confidenceEngine.topCategory(distribution);
                if (top != null) {
                    Category cat = categoryRepository.findById(top.getCategoryId()).orElse(null);
                    if (cat != null) {
                        return new Suggestion(cat.getName(), "learned", merchantId,
                                Transaction.DecisionSource.LEARNED_PATTERN, null);
                    }
                }
            }
        }

        String ruleCat = CategoryRules.suggestCategory(description);
        boolean matchedKeyword = !ruleCat.equals("Other");
        return new Suggestion(ruleCat, matchedKeyword ? "rule" : "default", merchantId,
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
     *  <p><b>Synchronous, and deliberately so — but only for SINGLE, INTERACTIVE actions.</b> This
     *  is now reached only by a user creating or editing one transaction, correcting one
     *  transaction's category, or an admin confirming a category for one merchant. There, applying
     *  the learning inline is the right behaviour — the caller is waiting for the result, the blast
     *  radius of a failure is the one change they just asked for, and an error they can see and
     *  retry beats a silent queue entry.
     *
     *  <p>The import path used to come through here too, once per confirmed row inside one
     *  transaction, and that was Bug 02: a single lost race against
     *  {@code UNIQUE(user_id, merchant_id, category_id)} rolled back every transaction in a
     *  statement the user had already reviewed. It now queues instead — see
     *  {@code ImportService.confirm} and {@code MerchantLearningEventPublisher}. Spec Section 10
     *  ("Learning update fails after a transaction has been categorized" — do NOT rollback the
     *  category) is therefore satisfied where it actually mattered.
     *
     *  <p><b>The last batch caller is gone too (WI1A).</b>
     *  {@code TransactionService.bulkRecategorize} used to call this in a loop, up to
     *  {@code TransactionDto.MAX_BULK_IDS} times inside one transaction — the import path's exact
     *  pre-WI1 shape, and the same single-lost-race-rolls-back-everything exposure at 500 rows
     *  instead of a statement's worth. It now calls {@link #queueLearning} instead. Every batch
     *  learning path in the product is asynchronous; every synchronous one is a single action a
     *  person is waiting on. That is the rule, and the two methods below are how it is expressed
     *  in code rather than in a comment. */
    public void learn(UUID userId, String description, UUID categoryId) {
        Merchant merchant = merchantNormalizationEngine.resolve(userId, description);
        merchantLearningService.confirm(userId, merchant.getId(), categoryId);
    }

    /**
     * The queued counterpart of {@link #learn}, for BATCH callers (WI1A).
     *
     * <p>Same confirmation, same distribution, same audit trail — applied by
     * {@code MerchantLearningEventWorker} after the caller's transaction commits rather than inside
     * it. Use this wherever learning happens N times in one unit of work; use {@link #learn} for a
     * single action whose caller is waiting on the answer. The choice is not about how important
     * the learning is, it is about blast radius: with N confirmations in one transaction, the
     * chance that at least one loses its race against
     * {@code UNIQUE(user_id, merchant_id, category_id)} scales with N, while the cost of losing
     * scales with N as well — every one of the user's N changes is discarded because of one of
     * them.
     *
     * <p><b>Both writes happen in the CALLER's transaction, and neither opens its own.</b> The
     * merchant resolution below is a write ({@code resolve} creates a merchant and an alias on a
     * miss) and the event row is a write, and both must roll back with the caller — a queued
     * confirmation for a recategorization that never committed is a worse failure than the one this
     * replaces. Only the APPLYING is deferred, by the {@code afterCommit} hook
     * {@code MerchantLearningEventPublisher.enqueue} registers. See that class for why those two
     * halves have to be separated rather than one of them chosen.
     *
     * <p>Both source ids are null, and that is honest rather than missing: this learning did not
     * come from a statement import and there was no staging session. The admin queue's projection
     * LEFT JOINs both, so the row renders with no statement rather than being hidden — and an
     * operator following a link to an import that never existed would reasonably conclude the row
     * is corrupt.
     *
     * <p>One event per call, with no de-duplication across a batch, deliberately. Each call is one
     * real confirmation and increments {@code confirmation_count} once, which is what
     * {@code ConfidenceEngine.topCategory} weighs; collapsing five rows for the same merchant into
     * one event would quietly change what the engine learns from a bulk action. {@code
     * ImportService.confirm} queues per row for the identical reason.
     */
    public void queueLearning(UUID userId, String description, UUID categoryId) {
        Merchant merchant = merchantNormalizationEngine.resolve(userId, description);
        learningEventPublisher.enqueue(userId, merchant.getId(), categoryId, null, null);
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

    /**
     * Bug 16: trims and matches case-insensitively so "dining", "Dining" and "Dining " all
     * resolve to one category instead of splitting into siblings that fragment a budget and
     * double-count in reports -- see {@link CategoryRepository#findByUserIdAndNameIgnoreCase}
     * for what this does and does not close.
     *
     * <p>The null/blank guard is a side effect of adding {@code .trim()} above, not incidental:
     * {@code TransactionService.updateCategory} passes an unvalidated {@code Map<String, String>}
     * value straight through with no upstream null check (unlike every other caller, which either
     * validates via {@code @NotBlank} or checks {@code != null} before calling this at all -- see
     * {@code TransactionController.updateCategory}'s own raw-map body). Before this method trimmed
     * its input, a null name reached {@code categoryRepository.save(c)} with a null
     * {@code NOT NULL} column and came back as a confusing 409 CONFLICT
     * ({@code DataIntegrityViolationException}, per {@code RuleService.validateRule}'s own doc
     * comment on this exact gap). Trimming a null would instead throw an unhandled
     * {@code NullPointerException} straight into the generic 500 handler -- worse than the bug
     * it replaced. This throws the correct 400 instead, closing both the confusing-409 case and
     * the potential NPE at the same time.
     */
    public Category resolveOrCreateCategory(UUID userId, String name) {
        if (name == null || name.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Category name can't be blank.");
        }
        String trimmed = name.trim();
        return categoryRepository.findByUserIdAndNameIgnoreCase(userId, trimmed).orElseGet(() -> {
            Category c = new Category();
            c.setUserId(userId);
            c.setName(trimmed);
            c.setSystem(false);
            return categoryRepository.save(c);
        });
    }
}
