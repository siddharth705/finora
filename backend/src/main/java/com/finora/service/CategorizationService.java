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
import com.finora.util.PersonToPersonTransferDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(CategorizationService.class);

    /** The {@code Suggestion.source()} string for a structurally-detected person-to-person
     *  transfer -- see {@link com.finora.util.PersonToPersonTransferDetector}. */
    public static final String STRUCTURAL_P2P_SOURCE = "structural_p2p";

    /**
     * Where a detected person-to-person payment lands. A system category in every user's default
     * taxonomy (see {@code AuthService.DEFAULT_CATEGORIES}), seeded at registration and backfilled
     * for existing users by {@code V123__paid_a_person_category.sql}.
     *
     * <p><b>This was "Transfer" when the detector first shipped, and that was wrong for what the
     * detector now actually matches.</b> Before merchant-rail detection existed, a narration naming
     * an individual mostly did mean money moved between two people's own accounts. Once the
     * merchant-acquiring rails (PhonePe Q-VPAs, {@code PAYTM.S}, GPay for Business, BharatPe, the
     * merchant-UPI pseudo-branch IFSCs) were split off, what is LEFT is deliberately the residue:
     * genuine transfers mixed with everyday payments to individuals who are being paid for
     * something -- a driver taken directly rather than through the app, a maid, a landlord, a
     * tutor, a vegetable seller with no merchant account. "Transfer" asserts of every one of those
     * that no spending occurred, which is a confident claim this code has no evidence for, and the
     * one thing the design review names as worse than an honest unknown.
     *
     * <p>"Paid a Person" claims only what the detector actually established: money left the
     * account, it went to a named individual, and the purpose is unknown. It is a weaker claim than
     * "Transfer" and than "Friend Repayment" (which additionally asserts a debt being settled), and
     * weaker is the point.
     *
     * <p>Being a distinct category does NOT change how the money is counted. Nothing in this
     * codebase excludes spend by category NAME -- {@code RefundNetting.reportable} excludes by
     * {@code ReconciliationStatus}, and a full grep of the category-name literals finds no
     * dashboard, budget or analytics path keying off one. So these rows counted as spend under
     * "Transfer" and still count as spend here; only the label the user reads changed.
     */
    public static final String P2P_CATEGORY = "Paid a Person";

    private final MerchantNormalizationEngine merchantNormalizationEngine;
    private final MerchantLearningService merchantLearningService;
    private final MerchantLearningEventPublisher learningEventPublisher;
    private final MerchantCategoryLearningRepository learningRepository;
    private final ConfidenceEngine confidenceEngine;
    private final CategoryRepository categoryRepository;
    private final RuleEngineService ruleEngineService;
    private final WorkspaceSettingsService workspaceSettingsService;

    public CategorizationService(MerchantNormalizationEngine merchantNormalizationEngine,
                                  MerchantLearningService merchantLearningService,
                                  MerchantLearningEventPublisher learningEventPublisher,
                                  MerchantCategoryLearningRepository learningRepository,
                                  ConfidenceEngine confidenceEngine,
                                  CategoryRepository categoryRepository,
                                  RuleEngineService ruleEngineService,
                                  WorkspaceSettingsService workspaceSettingsService) {
        this.merchantNormalizationEngine = merchantNormalizationEngine;
        this.merchantLearningService = merchantLearningService;
        this.learningEventPublisher = learningEventPublisher;
        this.learningRepository = learningRepository;
        this.confidenceEngine = confidenceEngine;
        this.categoryRepository = categoryRepository;
        this.ruleEngineService = ruleEngineService;
        this.workspaceSettingsService = workspaceSettingsService;
    }

    // source() keeps emitting the pre-existing string contract ("learned" | "rule" | "default" |
    // "file") that TransactionService/ImportService already branch on via equals("default") --
    // see decisionSourceFor() below for the full mapping to the richer, persisted enum.
    // "user_rule"/"global_rule" are new values nothing existing branches on (safe to add).
    // decisionSource/ruleId are the new fields: decisionSource is always set; ruleId is non-null
    // only when a category_rules row (not the static keyword table) produced this suggestion.
    public record Suggestion(String category, String source, UUID merchantId,
                              Transaction.DecisionSource decisionSource, UUID ruleId, Integer confidence) {
        /** Pre-confidence arity (Transaction Intelligence Phase B). Kept so every existing call site
         *  that constructs a Suggestion directly -- production and test alike -- keeps compiling
         *  unchanged. Defaults confidence to null, which is correct for any caller from before this
         *  field existed. */
        public Suggestion(String category, String source, UUID merchantId,
                           Transaction.DecisionSource decisionSource, UUID ruleId) {
            this(category, source, merchantId, decisionSource, ruleId, null);
        }
    }

    /**
     * The USER-then-GLOBAL rule set for one user, loaded once -- a thin passthrough to
     * {@code RuleEngineService.ruleSet}, so a per-row confirm loop (see
     * {@code ImportService.persistSection}) can hoist it without holding {@code RuleEngineService}
     * directly. This class is already the categorization facade every import/transaction caller
     * goes through (see this class's own doc comment); exposing this here keeps that true rather
     * than leaking a second categorization-adjacent dependency into callers that only need one
     * value out of it.
     */
    public List<CategoryRule> ruleSetFor(UUID userId) {
        return ruleEngineService.ruleSet(userId);
    }

    /**
     * Whether a category decision still needs a human's attention.
     *
     * <p>Before this method existed, both write paths (TransactionService.create,
     * ImportService.persistSection) flagged a transaction for review purely on suggestion SOURCE --
     * {@code sourceIsDefault}, true only when nothing matched (rule, learning, or keyword) and the
     * suggestion fell all the way to "Other". That is still the starting point: a non-default
     * suggestion (a rule fired, a keyword matched, a learned pattern won) is never flagged here,
     * unconditionally, matching that exact pre-existing behaviour.
     *
     * <p>What is new: a default guess is no longer flagged UNCONDITIONALLY. {@code confidence} (see
     * {@link Suggestion#confidence()}) is checked against the user's own
     * {@code WorkspaceSettings.autoApplyConfidenceThreshold} (default 90) via
     * {@link ConfidenceEngine#meetsAutoApplyThreshold} -- a user who has told Finora they trust even
     * low-confidence guesses (a low threshold) stops seeing every "Other" default in their review
     * queue. This CLEARS the flag; it never skips the write path or auto-assigns a different category
     * -- see docs/proposals/transaction-intelligence-engine-phase-b-audit.md's "Implementation
     * proposal" step 3 for why that stronger behaviour is a separate, undecided product question.
     *
     * <p>A null confidence (should not happen for any caller built on {@link Suggestion} after
     * Transaction Intelligence Phase B, but not assumed) fails safe to the pre-existing
     * always-flag-a-default-guess behaviour, without reading the threshold at all.
     */
    public boolean needsCategoryReview(UUID userId, boolean sourceIsDefault, Integer confidence) {
        if (!sourceIsDefault) return false;
        if (confidence == null) return true;
        int threshold = workspaceSettingsService.get(userId).autoApplyConfidenceThreshold();
        return !confidenceEngine.meetsAutoApplyThreshold(confidence, threshold);
    }

    /** Rule engine (user rules, then global rules) > learned distribution (real evidence) >
     *  keyword rules (including a merchant-canonical-name retry) > structural person-to-person
     *  transfer detection > "Other". See docs/rule-engine-relationship-engine-eds.md §4 and
     *  docs/superpowers/specs/2026-09-01-transaction-categorization-design.md §2. */
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
                    isUserRule ? Transaction.DecisionSource.USER_RULE : Transaction.DecisionSource.GLOBAL_RULE, rule.getId(),
                    ConfidenceEngine.INITIAL_RULE_CONFIDENCE);
        }

        List<MerchantCategoryLearning> distribution = learningRepository.findByUserIdAndMerchantId(userId, merchant.getId());
        if (!distribution.isEmpty()) {
            MerchantCategoryLearning top = confidenceEngine.topCategory(distribution);
            if (top != null) {
                Category cat = categoryRepository.findById(top.getCategoryId()).orElse(null);
                if (cat != null) {
                    Integer confidence = confidenceEngine.recomputeDistribution(distribution).get(top.getCategoryId());
                    return new Suggestion(cat.getName(), "learned", merchant.getId(), Transaction.DecisionSource.LEARNED_PATTERN, null, confidence);
                }
            }
        }

        String ruleCat = suggestCategoryWithMerchantFallback(description, trustedNameOf(merchant));
        if (!ruleCat.equals("Other")) {
            return new Suggestion(ruleCat, "rule", merchant.getId(), Transaction.DecisionSource.KEYWORD_MATCH, null,
                    ConfidenceEngine.INITIAL_RULE_CONFIDENCE);
        }
        if (PersonToPersonTransferDetector.isNamedIndividualTransfer(description)) {
            return new Suggestion(P2P_CATEGORY, STRUCTURAL_P2P_SOURCE, merchant.getId(),
                    Transaction.DecisionSource.STRUCTURAL_P2P, null, ConfidenceEngine.INITIAL_STRUCTURAL_CONFIDENCE);
        }
        return new Suggestion("Other", "default", merchant.getId(), Transaction.DecisionSource.MERCHANT_DEFAULT, null,
                ConfidenceEngine.INITIAL_DEFAULT_CONFIDENCE);
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
     * <p>Delegates to {@link #suggestReadOnly(List, UUID, String, BigDecimal, String,
     * com.finora.imports.MerchantIndex)} with a null index -- correct for any caller that hasn't
     * hoisted one (a handful of direct calls, diagnostics), wrong for a real per-row staging loop.
     * {@code TransactionNormalizer.normalize} -- the only per-row caller -- always passes a real
     * index instead; see that overload's own doc comment for why.
     */
    public Suggestion suggestReadOnly(List<CategoryRule> rules, UUID userId, String description,
                                       BigDecimal amount, String accountType) {
        return suggestReadOnly(rules, userId, description, amount, accountType, null);
    }

    /**
     * Same again, against a {@link com.finora.imports.MerchantIndex} the caller built once for the
     * whole statement.
     *
     * <p>{@code TransactionNormalizer.normalize} already hoists a {@code MerchantIndex} for its own
     * {@code StagedRow.merchant}/{@code merchantConfidence} resolution (Transaction Intelligence
     * Phase A, Task 2) -- before this overload existed, this method still resolved the merchant
     * itself via the live, un-indexed {@code resolveReadOnly(UUID, String)}, so every row paid a
     * full merchant-table load for CATEGORIZATION purposes even after Task 2's index made the
     * DISPLAY resolution free. That is the cost {@code ImportQueryCountIT} was still measuring at
     * 2.00 queries/row after Task 2 landed: two independent, un-batched merchant resolutions per
     * row instead of one. Passing the same index in here removes the second one.
     *
     * <p>A null {@code merchantIndex} falls back to the live lookup, matching
     * {@link #suggestReadOnly(List, UUID, String, BigDecimal, String)}'s pre-existing behavior for
     * any caller that hasn't hoisted one.
     */
    public Suggestion suggestReadOnly(List<CategoryRule> rules, UUID userId, String description,
                                       BigDecimal amount, String accountType,
                                       com.finora.imports.MerchantIndex merchantIndex) {
        var merchant = merchantIndex != null
                ? merchantNormalizationEngine.resolveReadOnly(userId, description, merchantIndex)
                : merchantNormalizationEngine.resolveReadOnly(userId, description);
        String merchantName = merchant.map(Merchant::getCanonicalName).orElse(null);
        UUID merchantId = merchant.map(Merchant::getId).orElse(null);

        var ruleMatch = ruleEngineService.evaluateCategoryRule(rules, description, amount, merchantName, accountType);
        if (ruleMatch.isPresent()) {
            CategoryRule rule = ruleMatch.get().rule();
            boolean isUserRule = ruleMatch.get().isUserScope();
            return new Suggestion(rule.getActionValue(), isUserRule ? "user_rule" : "global_rule", merchantId,
                    isUserRule ? Transaction.DecisionSource.USER_RULE : Transaction.DecisionSource.GLOBAL_RULE, rule.getId(),
                    ConfidenceEngine.INITIAL_RULE_CONFIDENCE);
        }

        if (merchantId != null) {
            List<MerchantCategoryLearning> distribution = learningRepository.findByUserIdAndMerchantId(userId, merchantId);
            if (!distribution.isEmpty()) {
                MerchantCategoryLearning top = confidenceEngine.topCategory(distribution);
                if (top != null) {
                    Category cat = categoryRepository.findById(top.getCategoryId()).orElse(null);
                    if (cat != null) {
                        Integer confidence = confidenceEngine.recomputeDistribution(distribution).get(top.getCategoryId());
                        return new Suggestion(cat.getName(), "learned", merchantId,
                                Transaction.DecisionSource.LEARNED_PATTERN, null, confidence);
                    }
                }
            }
        }

        String ruleCat = suggestCategoryWithMerchantFallback(description, trustedNameOf(merchant.orElse(null)));
        if (!ruleCat.equals("Other")) {
            return new Suggestion(ruleCat, "rule", merchantId, Transaction.DecisionSource.KEYWORD_MATCH, null,
                    ConfidenceEngine.INITIAL_RULE_CONFIDENCE);
        }
        if (PersonToPersonTransferDetector.isNamedIndividualTransfer(description)) {
            return new Suggestion(P2P_CATEGORY, STRUCTURAL_P2P_SOURCE, merchantId,
                    Transaction.DecisionSource.STRUCTURAL_P2P, null, ConfidenceEngine.INITIAL_STRUCTURAL_CONFIDENCE);
        }
        return new Suggestion("Other", "default", merchantId, Transaction.DecisionSource.MERCHANT_DEFAULT, null,
                ConfidenceEngine.INITIAL_DEFAULT_CONFIDENCE);
    }

    /**
     * Retries the static keyword table against the resolved merchant's canonical name when the
     * raw description alone doesn't match anything.
     *
     * <p>A canonical name generalizes across every raw narration variant that has resolved to that
     * merchant -- not just the current transaction's own text -- so a merchant identified once
     * helps every later transaction for it hit the keyword table, including ones whose own raw text
     * carries no recognizable brand token at all.
     *
     * <p>Never used to REPLACE the raw-description attempt, only to extend it: a raw match always
     * wins first, so this cannot change the category for any narration that already matched on its
     * own text.
     *
     * <p><b>{@code trustedMerchantName} is APPROVED-only, and that restriction is load-bearing
     * twice over</b> (both caught by adversarial review before this shipped, neither hypothetical):
     *
     * <ol>
     *   <li><b>It is what keeps {@link #suggest} and {@link #suggestReadOnly} identical.</b>
     *       {@code suggest} resolves through {@code MerchantNormalizationEngine.resolve}, which
     *       CREATES a merchant on a miss -- so a first-sighting description always had a canonical
     *       name to retry against, derived from that very description. {@code suggestReadOnly}
     *       resolves read-only and gets nothing on a miss. Retrying against any name therefore made
     *       staging and confirm disagree on a first sighting, which both methods' doc comments
     *       explicitly forbid ("a preview that used different logic from the confirm behind it
     *       would show the user a category they are not going to get"). It was a real divergence,
     *       not a theoretical one: {@code CategoryRules.extractMerchant} strips reference tokens,
     *       making previously separated words adjacent, so e.g. an IMPS transfer to a person could
     *       match the {@code "imps to"} Transfer keyword on the confirm path while the preview
     *       showed Other. A freshly created merchant is {@code TEMPORARY} and an unresolved one is
     *       absent, so gating on APPROVED makes both paths skip the retry in exactly the same
     *       cases.</li>
     *   <li><b>It stops a merchant-grouping guess from silently choosing a category.</b>
     *       {@code MerchantNormalizationEngine} groups an unseen description onto an existing
     *       merchant by first significant token -- "a deliberately simple heuristic, not fuzzy
     *       matching or NLP", by its own class doc, whose misses "are exactly what the manual
     *       'merge merchants' feature exists to fix by hand". Until this method existed, that
     *       over-grouping only cost display and learning quality. Retrying keywords against a
     *       TEMPORARY (engine-guessed) canonical name would have promoted it into the category
     *       decision itself: a merchant created from "UPI/RAJESH TEA CAFE/..." groups a later
     *       "UPI/RAJESH KUMAR/..." onto itself by the token {@code rajesh}, and the retry would
     *       then file a person-to-person transfer as Dining at rule-grade confidence, unflagged.
     *       {@code Merchant.Lifecycle.APPROVED} means "confirmed by a person" -- the only state in
     *       which the canonical name is evidence rather than a guess.</li>
     * </ol>
     *
     * <h2>How often this actually fires: operator-gated, and knowingly so</h2>
     *
     * <p>Only two things ever reach {@code APPROVED}. {@code MerchantSeedService} seeds ~37 curated
     * brands that way for every user at registration, and {@code MerchantReviewService} (the admin
     * Merchant Review Center) promotes the rest. So this retry is live for the curated brands and
     * <b>inert for every merchant discovered from a user's own statements</b> until an operator
     * curates it. Treat it as operator-gated capability, not as shipped per-user value.
     *
     * <p><b>The obvious way to widen it does not work</b>, and the reason is structural rather than
     * a missing feature. Promoting a merchant when the user corrects a category would promote
     * exactly the merchants whose retry can never fire again: {@code suggest} consults the learned
     * merchant distribution BEFORE reaching this retry, and every user correction routes through
     * {@code learn} / {@code queueLearning} → {@code MerchantLearningService.confirm}, which writes
     * that very distribution row. The promoted merchant would thereafter always be answered by the
     * learned layer.
     *
     * <p>Nor can the gate simply be dropped, because this retry's value and its risk are the same
     * mechanism: it only helps when a narration lacking a brand token was grouped onto a merchant
     * whose canonical name has one, and that grouping is {@code MerchantNormalizationEngine}'s
     * first-significant-token heuristic -- which is also exactly how it mis-groups. {@code APPROVED}
     * is the only thing separating the win case from the failure case.
     *
     * <p>Widening it therefore needs a confirmation of merchant <b>identity</b> -- a user-facing
     * rename or merge -- and those exist today only on {@code AdminUserMerchantController}, taking
     * an {@code actingAdminId}. Note also that {@code MerchantService.rename}/{@code merge} do NOT
     * touch {@code lifecycleStatus} at all; only {@code MerchantReviewService}'s equivalents do. A
     * user-facing surface has to mirror the review service, not the management one.
     */
    private static String suggestCategoryWithMerchantFallback(String description, String trustedMerchantName) {
        String ruleCat = CategoryRules.suggestCategory(description);
        if (!ruleCat.equals("Other")) return ruleCat;
        if (trustedMerchantName == null || trustedMerchantName.isBlank()) return ruleCat;
        return CategoryRules.suggestCategory(trustedMerchantName);
    }

    /** The canonical name only if a person has confirmed this merchant's identity -- see
     *  {@link #suggestCategoryWithMerchantFallback} for why an engine-guessed (TEMPORARY) name
     *  must never reach the keyword table. */
    private static String trustedNameOf(Merchant merchant) {
        if (merchant == null || merchant.getLifecycleStatus() != Merchant.Lifecycle.APPROVED) return null;
        return merchant.getCanonicalName();
    }

    /**
     * Whether a suggestion is an engine guess no human has confirmed -- the single definition of
     * that question, shared by the two things that must agree on it: whether to flag the row for
     * the Ask Once review queue, and whether it is safe to teach the merchant's learned
     * distribution from it.
     *
     * <p>Before this existed, both callers tested {@code "default".equals(source)} inline. That was
     * correct while "default" was the only unconfirmed outcome, and became wrong the moment
     * {@code structural_p2p} was added: a structural person-to-person guess is not "default", so it
     * was flagged for review by neither caller AND queued as a real learning confirmation by
     * {@code ImportRuleLearningService} -- meaning a misfire (the detector discloses an 8-12% error
     * bound) was invisible to the user and simultaneously taught to a merchant's distribution,
     * where it outranks the keyword table permanently. That is strictly worse than the "Other"
     * these rows used to get, which at least asked for help and taught nothing.
     *
     * <p>Pairs source WITH category deliberately, preserving the existing "did review change it"
     * test: a row still carrying the category its unconfirmed source produced is still unconfirmed,
     * while one the user re-categorized during import review is a real decision worth learning.
     */
    public static boolean isUnconfirmedGuess(String categorySource, String category) {
        if ("default".equals(categorySource)) return "Other".equals(category);
        if (STRUCTURAL_P2P_SOURCE.equals(categorySource)) return P2P_CATEGORY.equals(category);
        return false;
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
            case STRUCTURAL_P2P_SOURCE -> Transaction.DecisionSource.STRUCTURAL_P2P;
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
        return applySideEffectRules(userId, t, null);
    }

    /**
     * Same as {@link #applySideEffectRules(UUID, Transaction)}, against a rule set the caller
     * already loaded once.
     *
     * <p>Exists for {@code ImportService.persistSection}'s per-confirmed-row loop, which used to
     * call the loading overload once per row: {@code RuleEngineService.evaluateSideEffectRules
     * (UUID, ...)} re-queries {@code category_rules} (2 statements) on every call, and a user's
     * rules cannot change partway through confirming one import, so hoisting is equivalent by
     * construction -- same reasoning, and the same bug, as {@code CategorizationService
     * .suggestReadOnly}'s rule-set hoist on the staging side (see that method's own doc comment).
     * {@code RuleEngineService.ruleSet}'s own doc comment records the identical shape of bug found
     * and fixed in {@code RecurringService.detectForUser}; this was the last remaining un-hoisted
     * call site of the same pattern.
     *
     * <p>A null {@code rules} falls back to the loading overload, which is what
     * {@link #applySideEffectRules(UUID, Transaction)} passes -- correct for
     * {@code TransactionService.create()}'s single-transaction call, wrong for a real per-row
     * confirm loop.
     */
    public Category applySideEffectRules(UUID userId, Transaction t, List<CategoryRule> rules) {
        Merchant merchant = merchantNormalizationEngine.resolve(userId, t.getDescription());
        List<RuleEngineService.RuleMatch> matches = rules != null
                ? ruleEngineService.evaluateSideEffectRules(rules, t.getDescription(), t.getAmount(),
                        merchant.getCanonicalName(), null)
                : ruleEngineService.evaluateSideEffectRules(
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

    // categories.name is VARCHAR(80) NOT NULL (V1__init_schema.sql). ImportService's confirm path
    // feeds this an unbounded raw CSV/PDF cell with no upstream length check -- unlike the manual
    // creation path in TransactionService, which only ever reaches here with an explicit non-null
    // category or an engine suggestion. A too-long name previously hit the column constraint on
    // INSERT, which marks the whole confirm transaction rollback-only: same failure mode
    // MerchantNormalizationEngine.fitToColumn already guards against for merchant names, on the
    // same parser-output code path. By the time the constraint fires the transaction is already
    // poisoned, and no handling un-poisons it -- the write simply must not be attempted.
    private static final int MAX_CATEGORY_NAME_LENGTH = 80;

    /**
     * Bug 16: trims and matches case-insensitively so "dining", "Dining" and "Dining " all
     * resolve to one category instead of splitting into siblings that fragment a budget and
     * double-count in reports -- see
     * {@link CategoryRepository#findByUserIdAndNameIgnoreCaseOrderByIdAsc} for what this does
     * and does not close, including why it returns a list rather than a single result.
     *
     * <p>Bug 04: also caps the name at {@code categories.name}'s {@code VARCHAR(80)} limit (see
     * {@link #MAX_CATEGORY_NAME_LENGTH}'s own comment). Applied here rather than only at the
     * import call site so every caller gets the same defense, even though the import path is the
     * only one actually reachable with a name this long.
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
     *
     * <p>Deliberately does NOT default null/blank to "Other" the way {@code ImportService.confirm}
     * needs to for the exact same {@code VARCHAR(80)} exposure (Bug 04's own reproduction: "A
     * null/blank {@code row.category()} hits the same path against {@code NOT NULL}") -- that
     * degradation belongs at ImportService's own call site (see its comment there), where the
     * reasoning is "an unparseable category cell should not fail the whole import." A caller
     * reaching here with a null/blank name that was NOT already sanitized for that reason is a
     * genuinely malformed request, not a parser artifact -- throwing keeps that distinction
     * visible instead of silently reinterpreting every blank name as "Other," which would mask
     * real client bugs (a dropped form field, an `undefined` making it into a request body) behind
     * a default value.
     */
    public Category resolveOrCreateCategory(UUID userId, String name) {
        if (name == null || name.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Category name can't be blank.");
        }
        String trimmed = name.trim();
        String safeName = trimmed.length() <= MAX_CATEGORY_NAME_LENGTH ? trimmed : truncateForColumn(trimmed);
        List<Category> matches = categoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc(userId, safeName);
        if (!matches.isEmpty()) {
            return matches.get(0);
        }
        Category c = new Category();
        c.setUserId(userId);
        c.setName(safeName);
        c.setSystem(false);
        return categoryRepository.save(c);
    }

    private static String truncateForColumn(String trimmed) {
        log.warn("Truncating a {}-character category name to {} for import confirm. This is a "
                + "PARSER fault, not a data fault: a category cell that long means column "
                + "segmentation absorbed surrounding page text.",
                trimmed.length(), MAX_CATEGORY_NAME_LENGTH);
        return trimmed.substring(0, MAX_CATEGORY_NAME_LENGTH);
    }
}
