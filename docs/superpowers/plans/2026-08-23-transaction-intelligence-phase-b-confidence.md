# Transaction Intelligence Phase B — Confidence Threading Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Thread the confidence percentage `ConfidenceEngine` already computes through `CategorizationService.Suggestion` → `Transaction`/`StagedRow` → the existing "Why this category?" explanation panel, and wire the already-persisted-but-inert auto-apply-confidence-threshold setting into the `needsCategoryReview` flag.

**Architecture:** No new tables or services. `Suggestion` gains a `confidence: Integer` field populated from logic that already exists (`ConfidenceEngine.recomputeDistribution`, `INITIAL_RULE_CONFIDENCE`/`INITIAL_DEFAULT_CONFIDENCE`); that value is carried the same way `decisionSource`/`categorySource` already travel — through `Transaction` (new nullable column), through staging (`StagedRow`/`ConfirmedRow`, new nullable field) — and a new `CategorizationService.needsCategoryReview(...)` method centralizes the "does this need review" decision so both write paths (`TransactionService.create`, `ImportService.persistSection`) call one place instead of duplicating logic. The existing `TransactionExplanationService`/`ExplanationModal` gain one more field to render.

**Tech Stack:** Spring Boot / JPA / Flyway (backend), React / TypeScript / Vitest (frontend), JUnit 5 + Mockito (backend tests).

**Spec:** `docs/proposals/transaction-intelligence-engine-phase-b-audit.md` — this plan implements exactly the "Implementation proposal" section of that audit, items 1–4 (item 5, whether meeting the threshold should skip review entirely rather than just clear the flag, is an explicit product decision the audit deferred — **do not implement that in this plan**; the design below only ever clears an existing flag, never suppresses the write path).

## Global Constraints

- Every new field on a type with existing callers outside the file being edited (`Suggestion`, `StagedRow`, `ConfirmedRow`) MUST be added via a secondary compat constructor that defaults the new field to `null` — never a breaking signature change. This codebase has ~170 direct `Suggestion` construction call sites and ~40 direct `StagedRow`/`ConfirmedRow` construction call sites across tests; the compat-constructor idiom is how every prior field addition to these exact types avoided touching them.
- `TransactionExplanationDto` is the one exception: every one of its 8 construction sites lives inside `TransactionExplanationService.java`, which Task 4 edits directly — add the field straight to the record, no compat constructor needed there.
- Confidence is an `Integer` (nullable, 0–100 scale) everywhere it travels — matching `MerchantCategoryLearning.confidence`'s existing scale (`UserSettings.autoApplyConfidenceThreshold`'s own doc comment: "same scale as ConfidenceEngine's existing int confidence values"). Never a `Double`/0.0–1.0 float — that scale is already used by unrelated fields (`StagedRow.confidence` is Gmail-receipt extraction confidence, `StagedRow.merchantConfidence` is merchant-identity confidence) and mixing scales on the same class would be a real, easy-to-miss bug.
- Do not touch `StagedRow.confidence` or `StagedRow.merchantConfidence` — the new field is named `categoryConfidence` throughout (Java and TypeScript) to stay unambiguous alongside those two.
- MANUAL and FILE_PROVIDED decision sources never get a computed confidence (a human's or a source file's explicit choice isn't a probabilistic guess) — `Transaction.decisionConfidence` stays `null` on those paths, by construction (nothing sets it) or by explicit clearing (see Task 2).
- Flyway: the next available version is **V111** (confirmed via `ls backend/src/main/resources/db/migration`, sorted numerically, on `origin/main` as of this plan). Re-check this immediately before writing the migration file — other in-flight branches may have taken it first (see project CLAUDE.md's Flyway section).

---

### Task 1: `CategorizationService.Suggestion` gains a `confidence` field

**Files:**
- Modify: `backend/src/main/java/com/finora/service/CategorizationService.java:69-70` (the `Suggestion` record), and its 6 construction sites at lines 107-108, 117, 124-125, 200-201, 211-212, 220-221
- Test: `backend/src/test/java/com/finora/service/CategorizationServiceTest.java`

**Interfaces:**
- Consumes: `ConfidenceEngine.INITIAL_RULE_CONFIDENCE` (70), `ConfidenceEngine.INITIAL_DEFAULT_CONFIDENCE` (20) — both already-existing `public static final int` constants on `ConfidenceEngine` (`backend/src/main/java/com/finora/service/ConfidenceEngine.java:25-26`). `ConfidenceEngine.recomputeDistribution(List<MerchantCategoryLearning>): Map<UUID, Integer>` — already exists (`ConfidenceEngine.java:42-49`), returns each category's percentage share.
- Produces: `Suggestion.confidence(): Integer` — read by Task 2 (`TransactionService`), Task 3 (`TransactionNormalizer`).

- [ ] **Step 1: Write the failing tests**

Add these four tests to `backend/src/test/java/com/finora/service/CategorizationServiceTest.java`, right after `suggest_prefersLearnedDistribution_overRuleEngine` (after line 117):

```java
@Test
void suggest_userRuleMatch_reportsInitialRuleConfidence() {
    UUID merchantId = UUID.randomUUID();
    UUID ruleId = UUID.randomUUID();
    CategoryRule rule = new CategoryRule();
    ReflectionTestUtils.setField(rule, "id", ruleId);
    rule.setActionValue("Dining");
    rule.setScope(CategoryRule.Scope.USER);

    when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(merchantId));
    when(ruleEngineService.evaluateCategoryRule(eq(userId), anyString(), any(), anyString(), any()))
            .thenReturn(Optional.of(new RuleEngineService.RuleMatch(rule)));

    var suggestion = categorizationService.suggest(userId, "AMAZON PAY");

    assertThat(suggestion.confidence()).isEqualTo(ConfidenceEngine.INITIAL_RULE_CONFIDENCE);
}

@Test
void suggest_learnedPattern_reportsRealConfidencePercentage_notJustHighestCount() {
    // Amazon-shaped distribution: 3 Shopping confirmations, 1 Electronics -- a genuine 75%,
    // not the flat 70 a rule match gets and not the highest-count category's raw count.
    UUID merchantId = UUID.randomUUID();
    UUID shoppingId = UUID.randomUUID();
    UUID electronicsId = UUID.randomUUID();

    MerchantCategoryLearning shopping = new MerchantCategoryLearning();
    shopping.setMerchantId(merchantId);
    shopping.setUserId(userId);
    shopping.setCategoryId(shoppingId);
    shopping.setConfirmationCount(3);
    MerchantCategoryLearning electronics = new MerchantCategoryLearning();
    electronics.setMerchantId(merchantId);
    electronics.setUserId(userId);
    electronics.setCategoryId(electronicsId);
    electronics.setConfirmationCount(1);

    Category shoppingCategory = new Category();
    shoppingCategory.setUserId(userId);
    shoppingCategory.setName("Shopping");

    when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(merchantId));
    when(learningRepository.findByUserIdAndMerchantId(userId, merchantId))
            .thenReturn(List.of(shopping, electronics));
    when(categoryRepository.findById(shoppingId)).thenReturn(Optional.of(shoppingCategory));

    var suggestion = categorizationService.suggest(userId, "AMAZON PAY");

    assertThat(suggestion.category()).isEqualTo("Shopping");
    assertThat(suggestion.confidence()).isEqualTo(75); // round(3 * 100.0 / 4)
}

@Test
void suggest_keywordFallbackMatch_reportsInitialRuleConfidence() {
    UUID merchantId = UUID.randomUUID();
    when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(merchantId));
    when(learningRepository.findByUserIdAndMerchantId(userId, merchantId)).thenReturn(List.of());

    // "SWIGGY" hits the static keyword table's Dining rule -- see
    // suggest_fallsBackToRuleEngine_whenMerchantHasNoLearnedDistribution above for the same setup.
    var suggestion = categorizationService.suggest(userId, "SWIGGY*ORDR9182 BLR");

    assertThat(suggestion.source()).isEqualTo("rule");
    assertThat(suggestion.confidence()).isEqualTo(ConfidenceEngine.INITIAL_RULE_CONFIDENCE);
}

@Test
void suggest_defaultFallback_reportsInitialDefaultConfidence() {
    UUID merchantId = UUID.randomUUID();
    when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(merchantId));
    when(learningRepository.findByUserIdAndMerchantId(userId, merchantId)).thenReturn(List.of());

    var suggestion = categorizationService.suggest(userId, "SOME COMPLETELY UNKNOWN VENDOR");

    assertThat(suggestion.source()).isEqualTo("default");
    assertThat(suggestion.confidence()).isEqualTo(ConfidenceEngine.INITIAL_DEFAULT_CONFIDENCE);
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest=CategorizationServiceTest`
Expected: compile error (`confidence()` doesn't exist on `Suggestion` yet) or, if you comment out the assertions temporarily to check plumbing first, a `NullPointerException`/wrong-value failure. Confirm it fails before continuing.

- [ ] **Step 3: Add the `confidence` field with a compat constructor**

In `backend/src/main/java/com/finora/service/CategorizationService.java`, replace lines 69-70:

```java
public record Suggestion(String category, String source, UUID merchantId,
                          Transaction.DecisionSource decisionSource, UUID ruleId) {}
```

with:

```java
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
```

- [ ] **Step 4: Populate confidence at all 6 internal construction sites**

In the same file, `suggest(UUID, String, BigDecimal, String)` (around lines 100-126):

```java
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

    String ruleCat = CategoryRules.suggestCategory(description);
    boolean matchedKeyword = !ruleCat.equals("Other");
    return new Suggestion(ruleCat, matchedKeyword ? "rule" : "default", merchant.getId(),
            matchedKeyword ? Transaction.DecisionSource.KEYWORD_MATCH : Transaction.DecisionSource.MERCHANT_DEFAULT, null,
            matchedKeyword ? ConfidenceEngine.INITIAL_RULE_CONFIDENCE : ConfidenceEngine.INITIAL_DEFAULT_CONFIDENCE);
}
```

Then apply the identical three edits to `suggestReadOnly(List<CategoryRule>, UUID, String, BigDecimal, String, MerchantIndex)` (around lines 187-222) — same three `return new Suggestion(...)` sites, same confidence expressions, only `merchantId` (a `UUID`, already resolved via `merchant.map(Merchant::getId).orElse(null)`) in place of `merchant.getId()`:

```java
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

    String ruleCat = CategoryRules.suggestCategory(description);
    boolean matchedKeyword = !ruleCat.equals("Other");
    return new Suggestion(ruleCat, matchedKeyword ? "rule" : "default", merchantId,
            matchedKeyword ? Transaction.DecisionSource.KEYWORD_MATCH : Transaction.DecisionSource.MERCHANT_DEFAULT, null,
            matchedKeyword ? ConfidenceEngine.INITIAL_RULE_CONFIDENCE : ConfidenceEngine.INITIAL_DEFAULT_CONFIDENCE);
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=CategorizationServiceTest`
Expected: PASS, all tests including the 4 new ones.

- [ ] **Step 6: Run the full backend suite to confirm no compat-constructor regressions**

Run: `cd backend && ./mvnw -q test`
Expected: PASS. This is the check that the compat constructor actually kept every one of the ~170 existing `new Suggestion(...)`/`new CategorizationService.Suggestion(...)` call sites compiling — if any test file fails to compile, re-check Step 3's constructor delegation.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/service/CategorizationService.java backend/src/test/java/com/finora/service/CategorizationServiceTest.java
git commit -m "feat(rules): report a real confidence percentage from category suggestions"
```

---

### Task 2: `Transaction.decisionConfidence` + `CategorizationService.needsCategoryReview` + `TransactionService` wiring

**Files:**
- Create: `backend/src/main/resources/db/migration/V111__transaction_decision_confidence.sql`
- Modify: `backend/src/main/java/com/finora/entity/Transaction.java:142-148` (new field, getter/setter)
- Modify: `backend/src/main/java/com/finora/service/CategorizationService.java` (new `WorkspaceSettingsService` dependency, new `needsCategoryReview` method)
- Modify: `backend/src/main/java/com/finora/transactions/TransactionService.java:226-249` (`create`), `:407-416` (`update`), `:437-452` (`updateCategory`), `:538-561` (`confirmMerchantCategory`), `:690-704` (`bulkRecategorize`)
- Test: `backend/src/test/java/com/finora/service/CategorizationServiceTest.java`, `backend/src/test/java/com/finora/transactions/TransactionServiceTest.java`

**Interfaces:**
- Consumes: `Suggestion.confidence()` (Task 1). `WorkspaceSettingsService.get(UUID userId): WorkspaceSettingsDto` — already exists (`backend/src/main/java/com/finora/service/WorkspaceSettingsService.java:41-49`), read-only, defaults to threshold 90 with no row. `ConfidenceEngine.meetsAutoApplyThreshold(int, int): boolean` — already exists (`ConfidenceEngine.java:79-81`).
- Produces: `Transaction.getDecisionConfidence(): Integer` — read by Task 4 (`TransactionExplanationService`). `CategorizationService.needsCategoryReview(UUID, boolean, Integer): boolean` — called by Task 3 (`ImportService`) too.

- [ ] **Step 1: Write the failing test for `needsCategoryReview`**

Add to `backend/src/test/java/com/finora/service/CategorizationServiceTest.java` (needs a new mocked collaborator — see Step 3 for the constructor change this depends on; write the test now, it will fail to compile until Step 3 lands, which is expected for this step):

```java
@Test
void needsCategoryReview_flagsADefaultSuggestion_whenConfidenceIsBelowTheUsersThreshold() {
    when(workspaceSettingsService.get(userId))
            .thenReturn(new WorkspaceSettingsDto(90, null));

    boolean result = categorizationService.needsCategoryReview(userId, true, ConfidenceEngine.INITIAL_DEFAULT_CONFIDENCE);

    assertThat(result).isTrue(); // 20 < 90
}

@Test
void needsCategoryReview_clearsTheFlag_whenConfidenceMeetsALowerUserThreshold() {
    when(workspaceSettingsService.get(userId))
            .thenReturn(new WorkspaceSettingsDto(10, null));

    boolean result = categorizationService.needsCategoryReview(userId, true, ConfidenceEngine.INITIAL_DEFAULT_CONFIDENCE);

    assertThat(result).isFalse(); // 20 >= 10 -- a permissive threshold clears the default-source flag
}

@Test
void needsCategoryReview_isFalse_wheneverTheSourceWasNotADefaultGuess() {
    // A rule/learned match is never flagged for review regardless of confidence or threshold --
    // this mirrors the exact pre-existing behaviour (source.equals("default")) this method replaces.
    boolean result = categorizationService.needsCategoryReview(userId, false, 20);

    assertThat(result).isFalse();
    verifyNoInteractions(workspaceSettingsService);
}

@Test
void needsCategoryReview_staysTrue_whenConfidenceIsNull() {
    // A caller with no confidence to report (shouldn't happen post-Task-1, but must fail safe)
    // keeps the pre-existing "always flag a default guess" behaviour rather than silently clearing it.
    boolean result = categorizationService.needsCategoryReview(userId, true, null);

    assertThat(result).isTrue();
    verifyNoInteractions(workspaceSettingsService);
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=CategorizationServiceTest`
Expected: compile error (`workspaceSettingsService` field and `needsCategoryReview` method don't exist yet).

- [ ] **Step 3: Add `WorkspaceSettingsService` dependency and the `needsCategoryReview` method**

In `backend/src/main/java/com/finora/service/CategorizationService.java`, add the import and field/constructor changes:

```java
import com.finora.dto.WorkspaceSettingsDto;
```

Change lines 39-61 from:

```java
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
```

to:

```java
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
```

Only one test file constructs `CategorizationService` directly (`CategorizationServiceTest.java:54-57`) — no compat constructor needed here (contrast with `Suggestion`, which has ~170 call sites). Update that one call site:

```java
private WorkspaceSettingsService workspaceSettingsService;
```

add this field declaration alongside the other mocked collaborators (near line 35), and in `setUp()` add:

```java
workspaceSettingsService = mock(WorkspaceSettingsService.class);
```

then change the constructor call (lines 54-57) to:

```java
categorizationService = new CategorizationService(
        merchantNormalizationEngine, merchantLearningService, learningEventPublisher,
        learningRepository,
        new ConfidenceEngine(), categoryRepository, ruleEngineService, workspaceSettingsService);
```

Now add the new method, right after `ruleSetFor` (after line 83):

```java
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./mvnw -q test -Dtest=CategorizationServiceTest`
Expected: PASS, all tests including the 4 new ones from Step 1.

- [ ] **Step 5: Add the `decisionConfidence` column**

Create `backend/src/main/resources/db/migration/V111__transaction_decision_confidence.sql` (re-check V111 is still free first — see Global Constraints):

```sql
-- Transaction Intelligence Phase B: the confidence percentage behind a category decision,
-- alongside decision_source/decision_rule_id (V17). Nullable and only ever set for a
-- SUGGESTED category (USER_RULE/GLOBAL_RULE/LEARNED_PATTERN/KEYWORD_MATCH/MERCHANT_DEFAULT) --
-- MANUAL and FILE_PROVIDED never populate this, because a human's or a source file's explicit
-- choice isn't a probabilistic guess with a confidence to report.
ALTER TABLE transactions ADD COLUMN decision_confidence INTEGER;
```

In `backend/src/main/java/com/finora/entity/Transaction.java`, change lines 146-148 from:

```java
    // Only set when decisionSource is GLOBAL_RULE or USER_RULE -- see CategoryRule.
    @Column(name = "decision_rule_id")
    private UUID decisionRuleId;
```

to:

```java
    // Only set when decisionSource is GLOBAL_RULE or USER_RULE -- see CategoryRule.
    @Column(name = "decision_rule_id")
    private UUID decisionRuleId;

    // The confidence percentage (0-100, same scale as MerchantCategoryLearning.confidence and
    // UserSettings.autoApplyConfidenceThreshold) behind this decision -- see
    // CategorizationService.Suggestion#confidence's own doc comment for how each DecisionSource
    // computes it. Null for MANUAL and FILE_PROVIDED (an explicit choice, not a guess) and for
    // every transaction that predates Transaction Intelligence Phase B.
    @Column(name = "decision_confidence")
    private Integer decisionConfidence;
```

And add the getter/setter alongside `getDecisionRuleId`/`setDecisionRuleId` (near line 236-237):

```java
    public Integer getDecisionConfidence() { return decisionConfidence; }
    public void setDecisionConfidence(Integer decisionConfidence) { this.decisionConfidence = decisionConfidence; }
```

- [ ] **Step 6: Write the failing `TransactionService` tests**

Add to `backend/src/test/java/com/finora/transactions/TransactionServiceTest.java`. First, in `setUp()`, add a default lenient stub right after `categorizationService = mock(CategorizationService.class);` so every EXISTING test (which asserts `needsCategoryReview` behavior based purely on suggestion source, the old contract) keeps passing unchanged:

```java
// Preserves every existing test's expectation (needsCategoryReview mirrors suggestion source
// alone) by default; tests that specifically exercise the confidence-threshold behaviour below
// override this per-test.
when(categorizationService.needsCategoryReview(any(), anyBoolean(), any()))
        .thenAnswer(inv -> inv.getArgument(1));
```

(add `import static org.mockito.ArgumentMatchers.anyBoolean;` if not already present in this file's imports.)

Then add two new tests, near the existing `suggest`-based tests (e.g. after the test around line 291-292 that uses a `MERCHANT_DEFAULT` suggestion):

```java
@Test
void create_setsDecisionConfidence_fromTheSuggestion() {
    var suggestion = new CategorizationService.Suggestion("Dining", "rule", UUID.randomUUID(),
            Transaction.DecisionSource.KEYWORD_MATCH, null, 70);
    when(categorizationService.suggest(eq(userId), anyString(), any(), any())).thenReturn(suggestion);

    var result = transactionService.create(userId, createRequest("SWIGGY", null), idempotencyKey());

    ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
    verify(transactionRepository).save(captor.capture());
    assertThat(captor.getValue().getDecisionConfidence()).isEqualTo(70);
}

@Test
void create_honorsCategorizationServicesNeedsCategoryReviewDecision_notJustSourceEqualsDefault() {
    var suggestion = new CategorizationService.Suggestion("Other", "default", UUID.randomUUID(),
            Transaction.DecisionSource.MERCHANT_DEFAULT, null, 20);
    when(categorizationService.suggest(eq(userId), anyString(), any(), any())).thenReturn(suggestion);
    // Overrides the setUp() default: this user's threshold is permissive enough that a 20%
    // default guess should NOT be flagged.
    when(categorizationService.needsCategoryReview(userId, true, 20)).thenReturn(false);

    var result = transactionService.create(userId, createRequest("UNKNOWN VENDOR", null), idempotencyKey());

    ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
    verify(transactionRepository).save(captor.capture());
    assertThat(captor.getValue().isNeedsCategoryReview()).isFalse();
}
```

Adjust `createRequest(...)`/`idempotencyKey()` to whichever helper methods this test file already uses to build a create request — check the existing tests immediately above/below your insertion point (e.g. the test using `new CategorizationService.Suggestion("Other", "default", merchantId, ...)` around line 291) and copy its exact request-building call rather than inventing a new helper signature.

- [ ] **Step 7: Run the tests to verify the new ones fail and the old ones still pass their compile step**

Run: `cd backend && ./mvnw -q test -Dtest=TransactionServiceTest`
Expected: the two new tests FAIL (production code doesn't call `needsCategoryReview` or set `decisionConfidence` yet); every pre-existing test in the file still PASSES (the `setUp()` default stub is why).

- [ ] **Step 8: Wire `TransactionService` to use `needsCategoryReview` and set `decisionConfidence`**

In `backend/src/main/java/com/finora/transactions/TransactionService.java`, change the `create()` method's else-branch (lines 236-248) from:

```java
        } else {
            // No explicit category given — ask the engine. A "default" (no rule/learned match)
            // suggestion isn't a real decision, so file it under Other but flag it for the
            // "Ask Once" review queue instead of silently learning a non-decision.
            var suggestion = categorizationService.suggest(userId, req.description(), req.amount(), null);
            t.setMerchantId(suggestion.merchantId()); // already resolved as part of suggest() — no need to resolve twice
            category = categorizationService.resolveOrCreateCategory(userId, suggestion.category());
            t.setNeedsCategoryReview(suggestion.source().equals("default"));
            t.setDecisionSource(suggestion.decisionSource());
            t.setDecisionRuleId(suggestion.ruleId());
            // create() is always a real write (unlike CsvImportService, there's no staging/
            // preview step in between) -- safe to record the match right here.
            categorizationService.recordRuleMatch(suggestion.ruleId());
        }
```

to:

```java
        } else {
            // No explicit category given — ask the engine. A "default" (no rule/learned match)
            // suggestion isn't a real decision, so file it under Other but flag it for the
            // "Ask Once" review queue instead of silently learning a non-decision -- unless the
            // user's own auto-apply confidence threshold says otherwise; see
            // CategorizationService.needsCategoryReview's own doc comment.
            var suggestion = categorizationService.suggest(userId, req.description(), req.amount(), null);
            t.setMerchantId(suggestion.merchantId()); // already resolved as part of suggest() — no need to resolve twice
            category = categorizationService.resolveOrCreateCategory(userId, suggestion.category());
            t.setNeedsCategoryReview(categorizationService.needsCategoryReview(
                    userId, suggestion.source().equals("default"), suggestion.confidence()));
            t.setDecisionSource(suggestion.decisionSource());
            t.setDecisionRuleId(suggestion.ruleId());
            t.setDecisionConfidence(suggestion.confidence());
            // create() is always a real write (unlike CsvImportService, there's no staging/
            // preview step in between) -- safe to record the match right here.
            categorizationService.recordRuleMatch(suggestion.ruleId());
        }
```

Then, in each of the four places a transaction's category is set MANUALLY (an explicit human choice, never a probabilistic guess), clear `decisionConfidence` to `null` alongside the existing `t.setDecisionRuleId(null)` call:

`update()` (line 414, inside the `if (req.categoryName() != null)` block):
```java
            t.setDecisionRuleId(null);
            t.setDecisionConfidence(null);
```

`updateCategory()` (line 446):
```java
        t.setDecisionRuleId(null);
        t.setDecisionConfidence(null);
```

`confirmMerchantCategory()` (line 554):
```java
        t.setDecisionRuleId(null);
        t.setDecisionConfidence(null);
```

`bulkRecategorize()` (line 698):
```java
            t.setDecisionRuleId(null);
            t.setDecisionConfidence(null);
```

- [ ] **Step 9: Run the tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=TransactionServiceTest,CategorizationServiceTest`
Expected: PASS, all tests.

- [ ] **Step 10: Run the full backend suite**

Run: `cd backend && ./mvnw -q test`
Expected: PASS. If any other test file constructs `CategorizationService` directly (Step 3 said only one does — re-verify with `grep -rn "new CategorizationService(" backend/src` if this fails unexpectedly) or asserts on `needsCategoryReview`/`decisionConfidence` behavior elsewhere, fix those call sites the same way.

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/resources/db/migration/V111__transaction_decision_confidence.sql \
        backend/src/main/java/com/finora/entity/Transaction.java \
        backend/src/main/java/com/finora/service/CategorizationService.java \
        backend/src/main/java/com/finora/transactions/TransactionService.java \
        backend/src/test/java/com/finora/service/CategorizationServiceTest.java \
        backend/src/test/java/com/finora/transactions/TransactionServiceTest.java
git commit -m "feat(transactions): wire the auto-apply confidence threshold into needsCategoryReview"
```

---

### Task 3: Thread confidence through import staging and confirm

**Files:**
- Modify: `backend/src/main/java/com/finora/dto/ImportDto.java` (`StagedRow` gains `categoryConfidence`, `ConfirmedRow` gains `categoryConfidence`)
- Modify: `backend/src/main/java/com/finora/imports/TransactionNormalizer.java:515-542,626-628`
- Modify: `backend/src/main/java/com/finora/imports/ImportService.java:855-902`
- Modify: `frontend/src/types/index.ts:154-194` (`StagedRow` interface)
- Modify: `frontend/src/api/endpoints.ts:235-...` (`ConfirmedRowPayload` interface)
- Modify: `frontend/src/lib/importReview.ts:136-162` (`toConfirmedRows`)
- Test: `backend/src/test/java/com/finora/imports/TransactionNormalizerTest.java`, `backend/src/test/java/com/finora/imports/ImportServiceAskOnceTest.java`, `frontend/src/lib/importReview.test.ts`

**Interfaces:**
- Consumes: `Suggestion.confidence()` (Task 1), `CategorizationService.needsCategoryReview(UUID, boolean, Integer)` (Task 2).
- Produces: `StagedRow.categoryConfidence(): Integer`, `ConfirmedRow.categoryConfidence(): Integer` — read by the frontend review screen echo (this task) and available for a future staging-time confidence badge (not built in this plan — see the audit doc's step 5, an explicit product decision).

- [ ] **Step 1: Write the failing backend test**

Add to `backend/src/test/java/com/finora/imports/TransactionNormalizerTest.java`, near the existing tests that assert on `suggestedCategory()`/`categorySource()` (search the file for `.categorySource()` to find a good neighbor):

```java
@Test
void normalize_populatesCategoryConfidence_fromTheSuggestion() {
    when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any(), any()))
            .thenReturn(new CategorizationService.Suggestion("Dining", "rule", null,
                    com.finora.entity.Transaction.DecisionSource.KEYWORD_MATCH, null, 70));

    Map<String, String> row = rowOf(
            "Date", "10/07/2026", "Description", "SWIGGY ORDER", "Amount", "486.00", "Type", "DR");
    StagedRow result = normalizer.normalize(userId, row);

    assertThat(result.categoryConfidence()).isEqualTo(70);
}
```

Check this file's existing `setUp()` stub for the 6-arg `suggestReadOnly` overload (added earlier this session — search for `.suggestReadOnly(any(), any(), any(), any(), any(), any())`) and make sure the `when(...)` above doesn't conflict with it; if `setUp()` already stubs this exact call generically, override it inside this specific test instead (Mockito allows a per-test `when()` to take precedence over a `@BeforeEach` one for the same mock).

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=TransactionNormalizerTest -Dtest=TransactionNormalizerTest#normalize_populatesCategoryConfidence_fromTheSuggestion`
Expected: compile error (`categoryConfidence()` doesn't exist on `StagedRow` yet).

- [ ] **Step 3: Add `categoryConfidence` to `StagedRow` with a compat constructor**

In `backend/src/main/java/com/finora/dto/ImportDto.java`, the `StagedRow` record currently ends with (`merchant`, `merchantConfidence`) as its last two components, with a 15-arg canonical constructor and four secondary (compat) constructors delegating up to it. Add `categoryConfidence` as a new 16th, final component:

```java
public record StagedRow(
        LocalDate date,
        String description,
        BigDecimal amount,
        String type,
        String suggestedCategory,
        String categorySource,
        UUID ruleId,
        boolean likelyDuplicate,
        String referenceNumber,
        BigDecimal balanceAfter,
        DuplicateMatch duplicateMatch,
        RowKind kind,
        Double confidence,
        String merchant,
        Double merchantConfidence,
        /**
         * The category decision's confidence percentage (0-100), from
         * {@link com.finora.service.CategorizationService.Suggestion#confidence()} -- NOT the
         * same field as {@code confidence} above (that one is Gmail-receipt extraction
         * reliability) or {@code merchantConfidence} (merchant-identity resolution). Null when
         * the category came directly from the source file ({@code categorySource == "file"}),
         * which is a fact, not a guess.
         */
        Integer categoryConfidence
) {
    /** Pre-categoryConfidence arity (Transaction Intelligence Phase B). Kept so every existing
     *  construction of this 15-component shape -- production and test -- keeps compiling
     *  unchanged. Defaults categoryConfidence to null. */
    public StagedRow(LocalDate date, String description, BigDecimal amount, String type,
                      String suggestedCategory, String categorySource, UUID ruleId,
                      boolean likelyDuplicate, String referenceNumber, BigDecimal balanceAfter,
                      DuplicateMatch duplicateMatch, RowKind kind, Double confidence,
                      String merchant, Double merchantConfidence) {
        this(date, description, amount, type, suggestedCategory, categorySource, ruleId,
                likelyDuplicate, referenceNumber, balanceAfter, duplicateMatch, kind, confidence,
                merchant, merchantConfidence, null);
    }

    // --- Every constructor below this point already existed before this field; each now
    // delegates one level up with one additional trailing null, same as it always delegated to
    // the previous canonical shape. No behavioral change for any existing caller. ---

    public StagedRow(LocalDate date, String description, BigDecimal amount, String type,
                      String suggestedCategory, String categorySource, UUID ruleId,
                      boolean likelyDuplicate, String referenceNumber, BigDecimal balanceAfter,
                      DuplicateMatch duplicateMatch) {
        this(date, description, amount, type, suggestedCategory, categorySource, ruleId,
                likelyDuplicate, referenceNumber, balanceAfter, duplicateMatch, RowKind.TRANSACTION,
                null, null, null, null);
    }

    public StagedRow(LocalDate date, String description, BigDecimal amount, String type,
                      String suggestedCategory, String categorySource, UUID ruleId,
                      boolean likelyDuplicate, String referenceNumber, BigDecimal balanceAfter,
                      DuplicateMatch duplicateMatch, RowKind kind) {
        this(date, description, amount, type, suggestedCategory, categorySource, ruleId,
                likelyDuplicate, referenceNumber, balanceAfter, duplicateMatch, kind, null, null,
                null, null);
    }

    public StagedRow(LocalDate date, String description, BigDecimal amount, String type,
                      String suggestedCategory, String categorySource, UUID ruleId,
                      boolean likelyDuplicate, String referenceNumber, BigDecimal balanceAfter,
                      DuplicateMatch duplicateMatch, RowKind kind, Double confidence) {
        this(date, description, amount, type, suggestedCategory, categorySource, ruleId,
                likelyDuplicate, referenceNumber, balanceAfter, duplicateMatch, kind, confidence,
                null, null, null);
    }

    public StagedRow(LocalDate date, String description, BigDecimal amount, String type,
                      String suggestedCategory, String categorySource, UUID ruleId,
                      boolean likelyDuplicate, String referenceNumber, BigDecimal balanceAfter) {
        this(date, description, amount, type, suggestedCategory, categorySource, ruleId,
                likelyDuplicate, referenceNumber, balanceAfter, null, RowKind.TRANSACTION, null,
                null, null, null);
    }
}
```

**Before applying this edit, re-read the actual current file** (`backend/src/main/java/com/finora/dto/ImportDto.java`) to get the exact current body of each of the four pre-existing secondary constructors verbatim — this plan's description of them (from the audit's own investigation) may not byte-for-byte match if another change has landed since. The rule that must hold regardless of exact current wording: **every existing secondary constructor keeps its own parameter list unchanged, and every one of its delegating calls gains exactly one more trailing `null` argument.** Do not change which constructor calls which — only append the new trailing argument at each existing delegation point.

- [ ] **Step 4: Add `categoryConfidence` to `ConfirmedRow` with a compat constructor**

In the same file, `ConfirmedRow`'s canonical constructor currently has 12 components ending in `confirmedNotDuplicate`, with one secondary (pre-WI5) constructor of 11 components. Add `categoryConfidence` as a new 13th, final component:

```java
public record ConfirmedRow(
        LocalDate date, String description, BigDecimal amount, String type,
        String category, boolean include,
        String categorySource,
        UUID ruleId,
        boolean likelyDuplicate,
        @Size(max = 64) String referenceNumber,
        BigDecimal balanceAfter,
        boolean confirmedNotDuplicate,
        /** Echoed from {@code StagedRow.categoryConfidence} unchanged by review -- see that
         *  field's own doc comment. Lands on {@code Transaction.decisionConfidence} at confirm
         *  time. */
        Integer categoryConfidence
) {
    /** Pre-categoryConfidence arity (Transaction Intelligence Phase B). */
    public ConfirmedRow(LocalDate date, String description, BigDecimal amount, String type,
                        String category, boolean include, String categorySource, UUID ruleId,
                        boolean likelyDuplicate, String referenceNumber, BigDecimal balanceAfter,
                        boolean confirmedNotDuplicate) {
        this(date, description, amount, type, category, include, categorySource, ruleId,
                likelyDuplicate, referenceNumber, balanceAfter, confirmedNotDuplicate, null);
    }

    /** Pre-WI5 arity -- already existed before this field. Delegation gains one trailing null,
     *  same rule as StagedRow's own compat constructors above. */
    public ConfirmedRow(LocalDate date, String description, BigDecimal amount, String type,
                        String category, boolean include, String categorySource, UUID ruleId,
                        boolean likelyDuplicate, String referenceNumber, BigDecimal balanceAfter) {
        this(date, description, amount, type, category, include, categorySource, ruleId,
                likelyDuplicate, referenceNumber, balanceAfter, false, null);
    }
}
```

Again, re-read the actual current file first to confirm the pre-WI5 constructor's exact existing body before editing.

- [ ] **Step 5: Populate `categoryConfidence` in `TransactionNormalizer.normalize`**

In `backend/src/main/java/com/finora/imports/TransactionNormalizer.java`, change lines 515-542 from:

```java
        String suggestedCategory;
        String source;
        UUID ruleId = null;
        if (fileCategory != null) {
            suggestedCategory = fileCategory;
            source = "file";
        } else {
            // ... (existing comment unchanged) ...
            var suggestion = categorizationService.suggestReadOnly(rules, userId, description, amount, null,
                    merchantIndex);
            suggestedCategory = suggestion.category();
            source = suggestion.source();
            ruleId = suggestion.ruleId();
        }
```

to:

```java
        String suggestedCategory;
        String source;
        UUID ruleId = null;
        Integer categoryConfidence = null;
        if (fileCategory != null) {
            suggestedCategory = fileCategory;
            source = "file";
        } else {
            // ... (existing comment unchanged) ...
            var suggestion = categorizationService.suggestReadOnly(rules, userId, description, amount, null,
                    merchantIndex);
            suggestedCategory = suggestion.category();
            source = suggestion.source();
            ruleId = suggestion.ruleId();
            categoryConfidence = suggestion.confidence();
        }
```

Then change the final `return new StagedRow(...)` (lines 626-628) from:

```java
        return new StagedRow(date, description, amount, type, suggestedCategory, source, ruleId,
                likelyDuplicate, referenceNumber, balanceAfter, duplicateMatch, kind, null, merchant,
                merchantConfidence);
```

to:

```java
        return new StagedRow(date, description, amount, type, suggestedCategory, source, ruleId,
                likelyDuplicate, referenceNumber, balanceAfter, duplicateMatch, kind, null, merchant,
                merchantConfidence, categoryConfidence);
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd backend && ./mvnw -q test -Dtest=TransactionNormalizerTest`
Expected: PASS, all tests including the new one.

- [ ] **Step 7: Write the failing confirm-time test**

Add to `backend/src/test/java/com/finora/imports/ImportServiceAskOnceTest.java`, in `setUp()`, add the same default lenient stub pattern as Task 2 Step 6, right after wherever `categorizationService = mock(CategorizationService.class);` is (this file already mocks it — see this file's existing setup):

```java
when(categorizationService.needsCategoryReview(any(), anyBoolean(), any()))
        .thenAnswer(inv -> inv.getArgument(1));
```

Then add a new test, near `parseAndStage_asksTheSuggestionEngine_forIncomeRowsToo_insteadOfHardcodingSalary` (around line 770):

```java
@Test
void parseAndStage_persistsDecisionConfidence_fromTheConfirmedRowsCategoryConfidence() throws Exception {
    var row = new ConfirmedRow(LocalDate.of(2026, 7, 10), "SWIGGY ORDER", BigDecimal.valueOf(486),
            "EXPENSE", "Dining", true, "rule", null, false, null, null, false, 70);
    var request = new ConfirmRequest(null, List.of(row), accountId, null, null, null, null);

    var response = importService.confirm(userId, dummyFile(), request);

    ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
    verify(transactionRepository, atLeastOnce()).save(captor.capture());
    assertThat(captor.getValue().getDecisionConfidence()).isEqualTo(70);
}
```

Check this file's existing imports for `ArgumentCaptor`/`atLeastOnce` (both are standard Mockito; add static imports if missing) and adjust `dummyFile()`/`accountId`/`ConfirmRequest` construction to match whatever helper this file's neighboring tests already use — copy the exact pattern from `parseAndStage_classifiesDebitCreditRowAsExpense_whenCreditColumnIsBlank` (this file, near the top of the class) rather than inventing new helper calls.

- [ ] **Step 8: Run the test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=ImportServiceAskOnceTest -Dtest=ImportServiceAskOnceTest#parseAndStage_persistsDecisionConfidence_fromTheConfirmedRowsCategoryConfidence`
Expected: compile error (`ConfirmedRow`'s 13-arg shape and `getDecisionConfidence()` don't exist together yet, or the assertion fails with `null` if it happens to compile against a stale constructor shape).

- [ ] **Step 9: Wire `ImportService.persistSection` to use `needsCategoryReview` and carry `categoryConfidence`**

In `backend/src/main/java/com/finora/imports/ImportService.java`, change line 888 from:

```java
            t.setNeedsCategoryReview(isUnresolvedGuess);
```

to:

```java
            t.setNeedsCategoryReview(categorizationService.needsCategoryReview(userId, isUnresolvedGuess, row.categoryConfidence()));
```

And change lines 901-902 from:

```java
            t.setDecisionSource(CategorizationService.decisionSourceFor(row.categorySource()));
            t.setDecisionRuleId(row.ruleId());
```

to:

```java
            t.setDecisionSource(CategorizationService.decisionSourceFor(row.categorySource()));
            t.setDecisionRuleId(row.ruleId());
            t.setDecisionConfidence(row.categoryConfidence());
```

- [ ] **Step 10: Run the test to verify it passes**

Run: `cd backend && ./mvnw -q test -Dtest=ImportServiceAskOnceTest`
Expected: PASS, all tests including the new one.

- [ ] **Step 11: Run the full backend suite**

Run: `cd backend && ./mvnw -q test`
Expected: PASS. If any other test file directly constructs a 15-arg `StagedRow` or 12-arg `ConfirmedRow` and fails to compile, that means the compat constructor chain in Steps 3-4 has a gap — check that every PRE-EXISTING arity still has a matching constructor.

- [ ] **Step 12: Thread `categoryConfidence` through the frontend echo**

In `frontend/src/types/index.ts`, add to the `StagedRow` interface (after `merchantConfidence`, around line 193):

```typescript
  // The category decision's confidence percentage (0-100), from the backend's
  // CategorizationService.Suggestion#confidence(). Null when categorySource is 'file' (a fact
  // from the source document, not a guess). Distinct from `confidence` above (Gmail-receipt
  // extraction reliability) and `merchantConfidence` (merchant-identity resolution).
  categoryConfidence: number | null;
```

In `frontend/src/api/endpoints.ts`, add to the `ConfirmedRowPayload` interface (after `ruleId`, around line 243):

```typescript
  categoryConfidence: number | null;
```

In `frontend/src/lib/importReview.ts`, add to `toConfirmedRows`'s mapped object (after the `ruleId: r.ruleId,` line, around line 152):

```typescript
    categoryConfidence: r.categoryConfidence,
```

- [ ] **Step 13: Write and run the frontend test**

Check `frontend/src/lib/importReview.test.ts` for the existing test(s) covering `toConfirmedRows` (search for `toConfirmedRows` or `ruleId`). Add a fixture row with `categoryConfidence: 70` and assert the mapped output carries `categoryConfidence: 70` through, following the exact same pattern that file already uses to assert `ruleId` (or `categorySource`) passes through unchanged.

Run: `cd frontend && npx vitest run src/lib/importReview.test.ts`
Expected: PASS.

- [ ] **Step 14: Run the full frontend suite and typecheck**

Run: `cd frontend && npx vitest run && npx tsc -b`
Expected: PASS, no type errors.

- [ ] **Step 15: Commit**

```bash
git add backend/src/main/java/com/finora/dto/ImportDto.java \
        backend/src/main/java/com/finora/imports/TransactionNormalizer.java \
        backend/src/main/java/com/finora/imports/ImportService.java \
        backend/src/test/java/com/finora/imports/TransactionNormalizerTest.java \
        backend/src/test/java/com/finora/imports/ImportServiceAskOnceTest.java \
        frontend/src/types/index.ts frontend/src/api/endpoints.ts frontend/src/lib/importReview.ts \
        frontend/src/lib/importReview.test.ts
git commit -m "feat(imports): thread category confidence through staging and confirm"
```

---

### Task 4: Surface confidence in the "Why this category?" panel

**Files:**
- Modify: `backend/src/main/java/com/finora/transactions/TransactionExplanationDto.java`
- Modify: `backend/src/main/java/com/finora/transactions/TransactionExplanationService.java`
- Modify: `frontend/src/api/endpoints.ts:205-212` (`TransactionExplanation` interface)
- Modify: `frontend/src/pages/Ledger.tsx:291-334` (`ExplanationModal`)
- Test: `backend/src/test/java/com/finora/transactions/TransactionExplanationServiceTest.java`, `frontend/src/pages/Ledger.test.tsx`

**Interfaces:**
- Consumes: `Transaction.getDecisionConfidence(): Integer` (Task 2).
- Produces: `TransactionExplanationDto.confidence(): Integer` — rendered by the frontend, nothing downstream depends on it in this codebase.

- [ ] **Step 1: Write the failing backend test**

Add to `backend/src/test/java/com/finora/transactions/TransactionExplanationServiceTest.java`. First read the file's existing test for the `LEARNED_PATTERN` branch (search for `LEARNED_PATTERN`) to copy its exact transaction-building setup, then add:

```java
@Test
void explain_includesTheDecisionConfidence_whenPresent() {
    Transaction t = new Transaction();
    ReflectionTestUtils.setField(t, "id", txnId);
    t.setUserId(userId);
    t.setDecisionSource(Transaction.DecisionSource.LEARNED_PATTERN);
    t.setDecisionConfidence(82);
    when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));

    TransactionExplanationDto result = service.explain(userId, txnId);

    assertThat(result.confidence()).isEqualTo(82);
}

@Test
void explain_omitsConfidence_forAManualDecision() {
    Transaction t = new Transaction();
    ReflectionTestUtils.setField(t, "id", txnId);
    t.setUserId(userId);
    t.setDecisionSource(Transaction.DecisionSource.MANUAL);
    // decisionConfidence deliberately left null -- TransactionService never sets it for MANUAL.
    when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));

    TransactionExplanationDto result = service.explain(userId, txnId);

    assertThat(result.confidence()).isNull();
}
```

Adjust `userId`/`txnId` field names and the exact `ReflectionTestUtils.setField` usage to match this test file's existing conventions (check its `setUp()`/existing tests first).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest=TransactionExplanationServiceTest`
Expected: compile error (`confidence()` doesn't exist on `TransactionExplanationDto` yet).

- [ ] **Step 3: Add `confidence` to `TransactionExplanationDto`**

In `backend/src/main/java/com/finora/transactions/TransactionExplanationDto.java`, change:

```java
public record TransactionExplanationDto(
        String decisionSource,
        String summary,
        List<String> evidence
) {}
```

to:

```java
public record TransactionExplanationDto(
        String decisionSource,
        String summary,
        List<String> evidence,
        /** 0-100, or null -- {@link com.finora.entity.Transaction#getDecisionConfidence()} read
         *  straight through, same "surfacing, not a new intelligence layer" contract as every
         *  other field on this record. Null for MANUAL/FILE_PROVIDED (see that field's own doc
         *  comment) and for any transaction that predates Transaction Intelligence Phase B. */
        Integer confidence
) {}
```

No compat constructor needed — every construction site of this record lives inside `TransactionExplanationService.java`, edited in the next step.

- [ ] **Step 4: Thread `decisionConfidence` through `TransactionExplanationService.explain`**

In `backend/src/main/java/com/finora/transactions/TransactionExplanationService.java`, change the `explain` method (lines 46-71) from:

```java
    public TransactionExplanationDto explain(UUID userId, UUID transactionId) {
        Transaction t = OwnershipGuard.requireOwned(
                transactionRepository.findById(transactionId), Transaction::getUserId, userId, "Transaction");

        return switch (t.getDecisionSource()) {
            case MANUAL -> new TransactionExplanationDto(
                    "MANUAL", "You set this category yourself.", List.of());
            case USER_RULE -> ruleExplanation(t, "USER_RULE",
                    "Matched a rule you created.");
            case GLOBAL_RULE -> ruleExplanation(t, "GLOBAL_RULE",
                    "Matched one of Finora's built-in rules.");
            case LEARNED_PATTERN -> new TransactionExplanationDto(
                    "LEARNED_PATTERN",
                    "Categorized based on how you've categorized " + merchantPhrase(t) + " before.",
                    List.of("Every time you confirm or correct a category, Finora remembers it for that merchant."));
            case KEYWORD_MATCH -> new TransactionExplanationDto(
                    "KEYWORD_MATCH",
                    "Matched a keyword Finora recognizes in the description.",
                    List.of());
            case FILE_PROVIDED -> new TransactionExplanationDto(
                    "FILE_PROVIDED",
                    "The imported file specified this category directly.",
                    List.of());
            case MERCHANT_DEFAULT -> defaultExplanation(t);
        };
    }
```

to:

```java
    public TransactionExplanationDto explain(UUID userId, UUID transactionId) {
        Transaction t = OwnershipGuard.requireOwned(
                transactionRepository.findById(transactionId), Transaction::getUserId, userId, "Transaction");
        Integer confidence = t.getDecisionConfidence();

        return switch (t.getDecisionSource()) {
            case MANUAL -> new TransactionExplanationDto(
                    "MANUAL", "You set this category yourself.", List.of(), confidence);
            case USER_RULE -> ruleExplanation(t, "USER_RULE",
                    "Matched a rule you created.", confidence);
            case GLOBAL_RULE -> ruleExplanation(t, "GLOBAL_RULE",
                    "Matched one of Finora's built-in rules.", confidence);
            case LEARNED_PATTERN -> new TransactionExplanationDto(
                    "LEARNED_PATTERN",
                    "Categorized based on how you've categorized " + merchantPhrase(t) + " before.",
                    List.of("Every time you confirm or correct a category, Finora remembers it for that merchant."),
                    confidence);
            case KEYWORD_MATCH -> new TransactionExplanationDto(
                    "KEYWORD_MATCH",
                    "Matched a keyword Finora recognizes in the description.",
                    List.of(), confidence);
            case FILE_PROVIDED -> new TransactionExplanationDto(
                    "FILE_PROVIDED",
                    "The imported file specified this category directly.",
                    List.of(), confidence);
            case MERCHANT_DEFAULT -> defaultExplanation(t, confidence);
        };
    }
```

Then change `ruleExplanation` (lines 73-89) from:

```java
    private TransactionExplanationDto ruleExplanation(Transaction t, String source, String fallbackSummary) {
        CategoryRule rule = t.getDecisionRuleId() == null
                ? null : categoryRuleRepository.findById(t.getDecisionRuleId()).orElse(null);
        if (rule == null) {
            return new TransactionExplanationDto(source, fallbackSummary,
                    List.of("The specific rule is no longer available (it may have been edited or removed since)."));
        }
        String condition = fieldLabel(rule.getField()) + " " + operatorLabel(rule.getOperator())
                + " " + comparisonValueLabel(rule);
        String summary = fallbackSummary + " " + condition + " → " + rule.getActionValue() + ".";
        return new TransactionExplanationDto(source, summary,
                List.of("Rule condition: " + condition, "Assigns category: " + rule.getActionValue()));
    }
```

to:

```java
    private TransactionExplanationDto ruleExplanation(Transaction t, String source, String fallbackSummary,
                                                        Integer confidence) {
        CategoryRule rule = t.getDecisionRuleId() == null
                ? null : categoryRuleRepository.findById(t.getDecisionRuleId()).orElse(null);
        if (rule == null) {
            return new TransactionExplanationDto(source, fallbackSummary,
                    List.of("The specific rule is no longer available (it may have been edited or removed since)."),
                    confidence);
        }
        String condition = fieldLabel(rule.getField()) + " " + operatorLabel(rule.getOperator())
                + " " + comparisonValueLabel(rule);
        String summary = fallbackSummary + " " + condition + " → " + rule.getActionValue() + ".";
        return new TransactionExplanationDto(source, summary,
                List.of("Rule condition: " + condition, "Assigns category: " + rule.getActionValue()), confidence);
    }
```

And `defaultExplanation` (lines 94-107) from:

```java
    private TransactionExplanationDto defaultExplanation(Transaction t) {
        String categoryName = t.getCategoryId() == null ? "this category"
                : categoryRepository.findById(t.getCategoryId()).map(Category::getName).orElse("this category");
        if (t.getSource() == Transaction.Source.GMAIL_IMPORT) {
            return new TransactionExplanationDto("MERCHANT_DEFAULT",
                    "Imported from a Gmail receipt (" + merchantPhrase(t)
                            + "). Finora doesn't auto-detect a category for this merchant yet, so it defaulted to \""
                            + categoryName + "\".",
                    List.of("No rule, learned pattern, or keyword matched this transaction."));
        }
        return new TransactionExplanationDto("MERCHANT_DEFAULT",
                "No rule, learned pattern, or keyword matched, so this defaulted to \"" + categoryName + "\".",
                List.of());
    }
```

to:

```java
    private TransactionExplanationDto defaultExplanation(Transaction t, Integer confidence) {
        String categoryName = t.getCategoryId() == null ? "this category"
                : categoryRepository.findById(t.getCategoryId()).map(Category::getName).orElse("this category");
        if (t.getSource() == Transaction.Source.GMAIL_IMPORT) {
            return new TransactionExplanationDto("MERCHANT_DEFAULT",
                    "Imported from a Gmail receipt (" + merchantPhrase(t)
                            + "). Finora doesn't auto-detect a category for this merchant yet, so it defaulted to \""
                            + categoryName + "\".",
                    List.of("No rule, learned pattern, or keyword matched this transaction."), confidence);
        }
        return new TransactionExplanationDto("MERCHANT_DEFAULT",
                "No rule, learned pattern, or keyword matched, so this defaulted to \"" + categoryName + "\".",
                List.of(), confidence);
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=TransactionExplanationServiceTest`
Expected: PASS, all tests including the two new ones.

- [ ] **Step 6: Run the full backend suite**

Run: `cd backend && ./mvnw -q test`
Expected: PASS.

- [ ] **Step 7: Write the failing frontend test**

In `frontend/src/pages/Ledger.test.tsx`, add a test in the `describe('Ledger — Why this category?', ...)` block (after the existing `'opens the explanation panel and shows the summary and evidence'` test, around line 86):

```typescript
it('shows the confidence percentage when the explanation includes one', async () => {
  const user = userEvent.setup();
  vi.mocked(transactionsApi.explanation).mockResolvedValue({
    decisionSource: 'LEARNED_PATTERN',
    summary: 'Categorized based on how you\'ve categorized "SWIGGY" before.',
    evidence: ['Every time you confirm or correct a category, Finora remembers it for that merchant.'],
    confidence: 82,
  });
  renderLedger();

  await user.click(await screen.findByTitle('Why this category?'));

  expect(await screen.findByText(/82% confidence/i)).toBeInTheDocument();
});

it('shows no confidence line when the explanation has none', async () => {
  const user = userEvent.setup();
  vi.mocked(transactionsApi.explanation).mockResolvedValue({
    decisionSource: 'MANUAL',
    summary: 'You set this category yourself.',
    evidence: [],
  });
  renderLedger();

  await user.click(await screen.findByTitle('Why this category?'));

  await screen.findByText(/you set this category yourself/i);
  expect(screen.queryByText(/% confidence/i)).not.toBeInTheDocument();
});
```

- [ ] **Step 8: Run the tests to verify they fail**

Run: `cd frontend && npx vitest run src/pages/Ledger.test.tsx`
Expected: the two new tests FAIL (the modal doesn't render a confidence line yet); every pre-existing test in the describe block still PASSES (the `confidence` field is optional, so mocks that omit it are still valid).

- [ ] **Step 9: Add `confidence` to the `TransactionExplanation` type and render it**

In `frontend/src/api/endpoints.ts`, change lines 208-212 from:

```typescript
export interface TransactionExplanation {
  decisionSource: string;
  summary: string;
  evidence: string[];
}
```

to:

```typescript
export interface TransactionExplanation {
  decisionSource: string;
  summary: string;
  evidence: string[];
  // 0-100, or absent -- see TransactionExplanationDto's own doc comment for which decision
  // sources populate this (never MANUAL/FILE_PROVIDED).
  confidence?: number;
}
```

In `frontend/src/pages/Ledger.tsx`, change the `ExplanationModal`'s render block (lines 323-333) from:

```typescript
            <div className="space-y-2">
              <p className="text-ink text-sm">{explanation.summary}</p>
              {explanation.evidence.length > 0 && (
                <ul className="list-disc list-inside space-y-1">
                  {explanation.evidence.map((line, i) => (
                    <li key={i} className="text-xs text-muted">{line}</li>
                  ))}
                </ul>
              )}
            </div>
```

to:

```typescript
            <div className="space-y-2">
              <p className="text-ink text-sm">{explanation.summary}</p>
              {explanation.confidence != null && (
                <p className="text-xs text-muted">{explanation.confidence}% confidence</p>
              )}
              {explanation.evidence.length > 0 && (
                <ul className="list-disc list-inside space-y-1">
                  {explanation.evidence.map((line, i) => (
                    <li key={i} className="text-xs text-muted">{line}</li>
                  ))}
                </ul>
              )}
            </div>
```

- [ ] **Step 10: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/pages/Ledger.test.tsx`
Expected: PASS, all tests.

- [ ] **Step 11: Run the full frontend suite and typecheck**

Run: `cd frontend && npx vitest run && npx tsc -b`
Expected: PASS, no type errors.

- [ ] **Step 12: Commit**

```bash
git add backend/src/main/java/com/finora/transactions/TransactionExplanationDto.java \
        backend/src/main/java/com/finora/transactions/TransactionExplanationService.java \
        backend/src/test/java/com/finora/transactions/TransactionExplanationServiceTest.java \
        frontend/src/api/endpoints.ts frontend/src/pages/Ledger.tsx frontend/src/pages/Ledger.test.tsx
git commit -m "feat(transactions): show confidence percentage in the Why this category panel"
```

---

## Self-Review

**Spec coverage** (against the audit doc's "Implementation proposal" steps 1-4):
1. `Suggestion.confidence` populated from `recomputeDistribution`/`INITIAL_RULE_CONFIDENCE`/`INITIAL_DEFAULT_CONFIDENCE` — Task 1. ✅
2. Threaded into `Transaction` (new column) and `StagedRow` (new field) the same way `decisionSource`/`categorySource` travel — Tasks 2-3. ✅
3. `meetsAutoApplyThreshold` wired into `needsCategoryReview`, additive/reversible (clears an existing flag, never skips review) — Task 2. ✅
4. Added to `TransactionExplanationDto`, rendered in the existing `ExplanationModal`, no new frontend component — Task 4. ✅
5. (Staging/review-time visibility) — explicitly NOT built, per the audit's own framing as an undecided product call the plan should not make unilaterally.

**Placeholder scan:** no TBD/TODO, every step has real code, every test has real assertions and real setup values (concrete confidence numbers, concrete thresholds) rather than placeholder variables.

**Type consistency:** `Suggestion.confidence(): Integer` (Task 1) → `TransactionService`/`TransactionNormalizer` read it as `Integer` and pass it straight to `Transaction.setDecisionConfidence(Integer)`/`StagedRow`'s `Integer categoryConfidence` component (Tasks 2-3) → `ConfirmedRow.categoryConfidence(): Integer` → `Transaction.setDecisionConfidence` again at confirm time (Task 3) → `TransactionExplanationDto.confidence(): Integer` (Task 4). One type, `Integer`, 0-100 scale, end to end — verified against `Global Constraints`. `CategorizationService.needsCategoryReview(UUID, boolean, Integer): boolean` is the one method name used consistently across Task 2 (definition) and Task 3 (`ImportService`'s call site) — no drift.
