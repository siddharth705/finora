# Transaction Intelligence Phase A (Smart Review Experience) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce manual per-transaction categorization effort by (A1) showing the user Finora's already-detected merchant identity during import review instead of only raw bank text, and (A2+A3) letting the user bulk-recategorize every transaction sharing a merchant in one action, reusing the backend bulk-recategorize logic that already exists and is already tested but currently has no caller anywhere in the frontend.

**Architecture:** No new categorization intelligence is built in this phase — every finding in the Phase 0 audit confirmed the merchant-normalization engine (`MerchantNormalizationEngine`), the rule/learning categorization pipeline (`CategorizationService`), and the bulk-recategorize endpoint (`TransactionService.bulkRecategorize`) already exist and are already tested. This plan only wires those three things to the surfaces users actually see: (1) `StagedRow` gains a `merchant`/`merchantConfidence` pair, populated read-only during staging via the existing `MerchantNormalizationEngine.resolveReadOnly`, so the import review table can show "Detected: SWIGGY" next to the raw bank text; (2) a new, small `TransactionGroupingService` groups a user's already-persisted `needsCategoryReview` transactions by merchant (reusing the existing `findByUserIdAndNeedsCategoryReviewTrueOrderByTxnDateDesc` query and re-grouping in Java, the same pattern `AdminPlatformAnalyticsService` already uses because `Transaction` has no JPA association to `Merchant`, just a plain UUID column); (3) a new `MerchantGroupReviewCard` frontend component, built on the same load/select/confirm shape as the existing `AskOnceCard`, calls the bulk-recategorize endpoint for a whole group at once.

**Scope decision, stated explicitly so it isn't re-litigated mid-implementation:** Phase A's grouping/bulk-apply UI targets the **Ledger** (already-persisted transactions with `needsCategoryReview = true`), not the import-review staging screen. The staging screen (`Import.tsx`) only gets the merchant-visibility change (A1) in this phase — grouping staged, not-yet-persisted rows would need either a new staging-time bulk-apply mechanism or a second grouping code path, and the existing `bulk-category` endpoint operates on persisted transaction ids that don't exist yet at staging time. Grouping already-imported "Other"/needs-review transactions in the Ledger is the lower-risk slice that reuses 100% existing, tested backend write logic, and is exactly the audit's #1 recommendation. Groups of size 1 stay in the existing `AskOnceCard` flow — the new card only shows groups of 2 or more, so nothing regresses for users with no repeat-merchant backlog.

**Tech Stack:** Java 21 / Spring Boot / JPA (backend, Maven, JUnit 5 + Mockito + AssertJ), React 19 + TypeScript + TanStack Query + Vitest + Testing Library (frontend, no Redux/Zustand — component state + query cache).

**Spec:** [docs/proposals/transaction-intelligence-engine-phase0-audit.md](../../proposals/transaction-intelligence-engine-phase0-audit.md) — this plan implements items 1–3 of that document's §3 recommended order ("frontend bulk-categorization UI wired to the existing bulk-category endpoint", "merchant-grouping", "show merchant identity in review").

**Task ordering note:** Tasks 1–3 (merchant identity in review) and Tasks 4–6 (grouping + bulk UI) are independent slices — neither depends on the other's output, since Task 2's staging-time merchant resolution and Task 4's Ledger-side grouping query hit different data (in-flight `StagedRow`s vs. already-persisted `Transaction`s). They're numbered in this order for narrative flow; an executor following the roadmap's stated ROI priority (grouping and bulk UI first) may do Tasks 4–6 before Tasks 1–3 with no rework required either way.

## Global Constraints

- Reuse `MerchantNormalizationEngine`, `CategorizationService`, `RecurringService`, and `TransactionService.bulkRecategorize` exactly as they exist today — do not add a parallel merchant-resolution or bulk-write mechanism.
- `StagedRow` changes must use this codebase's existing compat-constructor idiom (add a new canonical constructor; keep every existing constructor as a secondary constructor delegating to it) so none of the ~40 existing test files that construct `StagedRow`/`TransactionNormalizer` directly need to change.
- `MAX_BULK_IDS = 500` (`TransactionDto.java:103`) already bounds the existing bulk-category endpoint — the new grouping service does not need its own cap beyond what a `needsCategoryReview` backlog realistically produces, but any group larger than 500 must be truncated with a visible note, not silently sent in full (the endpoint would reject it outright otherwise).
- No new merchant-confidence *scoring* is introduced in this phase (that's Phase B, "confidence explanations" — see the audit). `merchantConfidence` in this phase is a simple binary signal (`1.0` when `MerchantNormalizationEngine.resolveReadOnly` finds an existing merchant, `null`/absent otherwise) — do not build a richer score here.
- Every new backend class follows this codebase's existing test convention: JUnit 5 + Mockito (`mock()`/`when()`) + AssertJ (`assertThat`), no Spring context unless the class under test needs one (none in this plan do).
- Every new frontend component follows the existing convention: Vitest + `@testing-library/react` + `userEvent`, `vi.mock('../api/endpoints', ...)` for API mocking, `QueryClientProvider` wrapper for anything using TanStack Query.

---

### Task 1: `StagedRow` gains `merchant` and `merchantConfidence`

**Files:**
- Modify: `backend/src/main/java/com/finora/dto/ImportDto.java:41-133`
- Test: `backend/src/test/java/com/finora/dto/ImportDtoStagedRowTest.java` (new file)

**Interfaces:**
- Produces: `StagedRow` record gains two new trailing components, `String merchant` and `Double merchantConfidence`, both nullable. The canonical (all-argument) constructor now takes 15 arguments. All three existing secondary constructors keep their original argument lists and default the two new fields to `null`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/finora/dto/ImportDtoStagedRowTest.java`:

```java
package com.finora.dto;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.RowKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ImportDtoStagedRowTest {

    @Test
    void canonicalConstructor_carriesMerchantAndMerchantConfidence() {
        StagedRow row = new StagedRow(
                LocalDate.of(2026, 1, 1), "UPI-SWIGGY-12345", BigDecimal.TEN, "EXPENSE",
                "Food", "learned", null, false, null, null, null, RowKind.TRANSACTION,
                null, "SWIGGY", 1.0);

        assertThat(row.merchant()).isEqualTo("SWIGGY");
        assertThat(row.merchantConfidence()).isEqualTo(1.0);
    }

    @Test
    void preExistingConstructor_defaultsMerchantFieldsToNull() {
        StagedRow row = new StagedRow(
                LocalDate.of(2026, 1, 1), "UPI-SWIGGY-12345", BigDecimal.TEN, "EXPENSE",
                "Food", "learned", null, false, null, null);

        assertThat(row.merchant()).isNull();
        assertThat(row.merchantConfidence()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw -q -o test -Dtest=ImportDtoStagedRowTest`
Expected: FAIL — compile error, `StagedRow` has no 15-argument constructor and no `merchant()`/`merchantConfidence()` accessors.

- [ ] **Step 3: Write minimal implementation**

In `backend/src/main/java/com/finora/dto/ImportDto.java`, change the `StagedRow` record header (currently lines 41-88) to add the two new components after `confidence`:

```java
    public record StagedRow(
            LocalDate date,
            String description,
            BigDecimal amount,
            String type,
            String suggestedCategory,
            String categorySource,   // "learned" | "rule" | "user_rule" | "global_rule" | "default" | "file"
            UUID ruleId,             // set only when categorySource is "user_rule" or "global_rule"
            boolean likelyDuplicate,
            String referenceNumber,  // best-effort, null when the source had no recognizable reference/cheque column
            BigDecimal balanceAfter, // best-effort, null when the source had no recognizable running-balance column
            DuplicateMatch duplicateMatch,
            RowKind kind,
            Double confidence,
            /**
             * The canonical merchant name {@link com.finora.service.MerchantNormalizationEngine#resolveReadOnly}
             * found for this row's description, or null when no existing merchant matched. Read-only
             * resolution — staging never creates a Merchant/MerchantAlias row (that still only happens at
             * confirm time; see resolveReadOnly's own doc comment for why). Never guessed: a raw description
             * that resolves to no existing merchant leaves this null, it does not fall back to the raw text.
             */
            String merchant,
            /**
             * 1.0 when {@code merchant} was resolved, null otherwise. Deliberately not a richer score in
             * this phase — see this plan's Global Constraints: a real confidence model is Phase B's
             * "confidence explanations" work, not this one.
             */
            Double merchantConfidence
    ) {
```

Then update each of the three existing secondary constructors (currently lines 101-132) to append `, null, null` to their `this(...)` delegating call, so they keep compiling against the now-15-argument canonical constructor:

```java
        public StagedRow(LocalDate date, String description, BigDecimal amount, String type,
                          String suggestedCategory, String categorySource, UUID ruleId,
                          boolean likelyDuplicate, String referenceNumber, BigDecimal balanceAfter,
                          DuplicateMatch duplicateMatch) {
            this(date, description, amount, type, suggestedCategory, categorySource, ruleId,
                    likelyDuplicate, referenceNumber, balanceAfter, duplicateMatch, RowKind.TRANSACTION,
                    null, null, null);
        }

        public StagedRow(LocalDate date, String description, BigDecimal amount, String type,
                          String suggestedCategory, String categorySource, UUID ruleId,
                          boolean likelyDuplicate, String referenceNumber, BigDecimal balanceAfter,
                          DuplicateMatch duplicateMatch, RowKind kind) {
            this(date, description, amount, type, suggestedCategory, categorySource, ruleId,
                    likelyDuplicate, referenceNumber, balanceAfter, duplicateMatch, kind, null, null, null);
        }

        public StagedRow(LocalDate date, String description, BigDecimal amount, String type,
                          String suggestedCategory, String categorySource, UUID ruleId,
                          boolean likelyDuplicate, String referenceNumber, BigDecimal balanceAfter) {
            this(date, description, amount, type, suggestedCategory, categorySource, ruleId,
                    likelyDuplicate, referenceNumber, balanceAfter, null, RowKind.TRANSACTION, null, null, null);
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw -q -o test -Dtest=ImportDtoStagedRowTest`
Expected: PASS

- [ ] **Step 5: Run the full backend test suite to confirm no existing `StagedRow` construction broke**

Run: `cd backend && ./mvnw -q -o test`
Expected: PASS, same total test count as before this task plus the 2 new tests (this is the check that the compat-constructor approach actually protected the ~40 existing call sites — if any fail to compile, the delegating `this(...)` calls above were not updated correctly).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/dto/ImportDto.java backend/src/test/java/com/finora/dto/ImportDtoStagedRowTest.java
git commit -m "feat(imports): add merchant + merchantConfidence to StagedRow"
```

---

### Task 2: `TransactionNormalizer` resolves merchant identity during staging

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/TransactionNormalizer.java:1-30,155-160,563-565`
- Test: `backend/src/test/java/com/finora/imports/TransactionNormalizerTest.java` (add tests to existing file)

**Interfaces:**
- Consumes: `MerchantNormalizationEngine.resolveReadOnly(UUID userId, String description): Optional<Merchant>` (`backend/src/main/java/com/finora/service/MerchantNormalizationEngine.java:276`); `Merchant.getCanonicalName(): String` (`backend/src/main/java/com/finora/entity/Merchant.java:65`).
- Produces: `TransactionNormalizer` gains a new 4-argument canonical constructor `TransactionNormalizer(CategorizationService, DuplicateDetector, RuleEngineService, MerchantNormalizationEngine)`, marked `@Autowired` (required once a second constructor exists, since Spring only auto-detects a single constructor without one). The existing 3-argument constructor becomes a secondary constructor delegating with `null` for the new dependency, so every existing test call site (`new TransactionNormalizer(a, b, c)`) keeps compiling and behaves exactly as before (merchant fields simply stay null when the 3-arg constructor is used, which is correct — those tests don't care about merchant resolution).

- [ ] **Step 1: Write the failing test**

Add to `backend/src/test/java/com/finora/imports/TransactionNormalizerTest.java` (new imports needed: `com.finora.entity.Merchant`, `com.finora.service.MerchantNormalizationEngine`, `java.util.Optional`):

```java
    @Test
    void normalize_populatesMerchantFields_whenMerchantNormalizationEngineResolvesAMatch() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Food", "learned", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Food", "learned", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);

        Merchant swiggy = new Merchant();
        swiggy.setCanonicalName("SWIGGY");
        MerchantNormalizationEngine merchantNormalizationEngine = mock(MerchantNormalizationEngine.class);
        when(merchantNormalizationEngine.resolveReadOnly(any(), any())).thenReturn(java.util.Optional.of(swiggy));

        TransactionNormalizer withMerchantResolution = new TransactionNormalizer(
                categorizationService, duplicateDetector, TestRuleEngines.empty(), merchantNormalizationEngine);

        StagedRow row = withMerchantResolution.normalize(userId,
                rowOf("Date", "01-01-2026", "Description", "UPI-SWIGGY-12345", "Amount", "350", "Type", "Dr"));

        assertThat(row.merchant()).isEqualTo("SWIGGY");
        assertThat(row.merchantConfidence()).isEqualTo(1.0);
    }

    @Test
    void normalize_leavesMerchantFieldsNull_whenNoMerchantNormalizationEngineIsWired() {
        // The existing 3-arg constructor (every other test in this class uses it) must keep behaving
        // exactly as before -- merchant resolution is additive, never required.
        StagedRow row = normalizer.normalize(userId,
                rowOf("Date", "01-01-2026", "Description", "UPI-SWIGGY-12345", "Amount", "350", "Type", "Dr"));

        assertThat(row.merchant()).isNull();
        assertThat(row.merchantConfidence()).isNull();
    }

    @Test
    void normalize_leavesMerchantFieldsNull_whenEngineFindsNoExistingMerchant() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);

        MerchantNormalizationEngine merchantNormalizationEngine = mock(MerchantNormalizationEngine.class);
        when(merchantNormalizationEngine.resolveReadOnly(any(), any())).thenReturn(java.util.Optional.empty());

        TransactionNormalizer withMerchantResolution = new TransactionNormalizer(
                categorizationService, duplicateDetector, TestRuleEngines.empty(), merchantNormalizationEngine);

        StagedRow row = withMerchantResolution.normalize(userId,
                rowOf("Date", "01-01-2026", "Description", "SOME BRAND NEW MERCHANT", "Amount", "350", "Type", "Dr"));

        assertThat(row.merchant()).isNull();
        assertThat(row.merchantConfidence()).isNull();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw -q -o test -Dtest=TransactionNormalizerTest`
Expected: FAIL — compile error, no 4-argument `TransactionNormalizer` constructor exists yet.

- [ ] **Step 3: Write minimal implementation**

In `backend/src/main/java/com/finora/imports/TransactionNormalizer.java`, add the import (near the existing `com.finora.service.CategorizationService` import at the top):

```java
import com.finora.service.MerchantNormalizationEngine;
import org.springframework.beans.factory.annotation.Autowired;
```

Change the field declarations and constructors (currently the single 3-arg constructor at line 155):

```java
    private final CategorizationService categorizationService;
    private final DuplicateDetector duplicateDetector;
    private final RuleEngineService ruleEngineService;
    private final MerchantNormalizationEngine merchantNormalizationEngine;

    @Autowired
    public TransactionNormalizer(CategorizationService categorizationService, DuplicateDetector duplicateDetector,
                                  RuleEngineService ruleEngineService,
                                  MerchantNormalizationEngine merchantNormalizationEngine) {
        this.categorizationService = categorizationService;
        this.duplicateDetector = duplicateDetector;
        this.ruleEngineService = ruleEngineService;
        this.merchantNormalizationEngine = merchantNormalizationEngine;
    }

    /**
     * Pre-Phase-A shape, kept so the ~40 existing tests constructing this directly don't need to
     * change. Merchant resolution is additive: a normalizer built this way simply never populates
     * StagedRow.merchant/merchantConfidence, which is correct for every caller that doesn't pass one.
     */
    public TransactionNormalizer(CategorizationService categorizationService, DuplicateDetector duplicateDetector,
                                  RuleEngineService ruleEngineService) {
        this(categorizationService, duplicateDetector, ruleEngineService, null);
    }
```

Then in `normalize()`, immediately before the final `return new StagedRow(...)` (currently lines 563-564), add:

```java
        String merchant = null;
        Double merchantConfidence = null;
        if (merchantNormalizationEngine != null) {
            var resolved = merchantNormalizationEngine.resolveReadOnly(userId, description);
            if (resolved.isPresent()) {
                merchant = resolved.get().getCanonicalName();
                merchantConfidence = 1.0;
            }
        }

        return new StagedRow(date, description, amount, type, suggestedCategory, source, ruleId,
                likelyDuplicate, referenceNumber, balanceAfter, duplicateMatch, kind, null, merchant,
                merchantConfidence);
```

(Note the added `null` before `merchant, merchantConfidence`: that positional argument is `confidence`, the pre-existing Gmail-only field from Task 1's record layout — this call site never populated it before either, so passing `null` here preserves existing behavior exactly.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw -q -o test -Dtest=TransactionNormalizerTest`
Expected: PASS, all tests in the file including the 3 new ones.

- [ ] **Step 5: Run the full backend suite**

Run: `cd backend && ./mvnw -q -o test`
Expected: PASS. This is the check that Spring's context can still autowire `TransactionNormalizer` in production (the `@Autowired` 4-arg constructor) and that no other test broke.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/imports/TransactionNormalizer.java backend/src/test/java/com/finora/imports/TransactionNormalizerTest.java
git commit -m "feat(imports): resolve merchant identity during staging via MerchantNormalizationEngine"
```

---

### Task 3: Import review table shows the detected merchant

**Files:**
- Modify: `frontend/src/types/index.ts:154-188`
- Modify: `frontend/src/pages/Import.tsx:1505-1522`
- Test: `frontend/src/pages/Import.test.tsx` (add a new `describe` block)

**Interfaces:**
- Consumes: `StagedRow.merchant`/`StagedRow.merchantConfidence` from Task 2's backend response.
- Produces: `StagedRow` TypeScript interface gains `merchant: string | null` and `merchantConfidence: number | null`; `TransactionPreviewTable` renders a "Detected: X" line under the raw description when `r.merchant` is present.

- [ ] **Step 1: Write the failing test**

Add to `frontend/src/pages/Import.test.tsx`, near the other review-screen `describe` blocks (e.g. after the "total amount due" block around line 336):

```typescript
describe('Import — detected merchant on the review screen', () => {
  beforeEach(() => {
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
  });

  it('shows the detected merchant name under the raw description', async () => {
    vi.mocked(importApi.stagePdf).mockReset().mockResolvedValue({
      sessionId: 'session-1', multiAccount: false, sections: null,
      staging: {
        rows: [{
          date: '2026-07-10', description: 'UPI-SWIGGY-12345', amount: 350, type: 'EXPENSE',
          suggestedCategory: 'Food', categorySource: 'learned', ruleId: null, likelyDuplicate: false,
          referenceNumber: null, balanceAfter: null, duplicateMatch: null,
          merchant: 'SWIGGY', merchantConfidence: 1.0,
        }],
        totalParsed: 1, flaggedDuplicates: 0, unparseableRows: [], detectedAccount,
      },
    } as never);
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);

    expect(await screen.findByText('UPI-SWIGGY-12345')).toBeInTheDocument();
    expect(screen.getByText('Detected: SWIGGY')).toBeInTheDocument();
  });

  it('shows nothing extra when no merchant was resolved', async () => {
    vi.mocked(importApi.stagePdf).mockReset().mockResolvedValue({
      sessionId: 'session-1', multiAccount: false, sections: null,
      staging: {
        rows: [{
          date: '2026-07-10', description: 'SOME BRAND NEW SHOP', amount: 350, type: 'EXPENSE',
          suggestedCategory: 'Other', categorySource: 'default', ruleId: null, likelyDuplicate: false,
          referenceNumber: null, balanceAfter: null, duplicateMatch: null,
          merchant: null, merchantConfidence: null,
        }],
        totalParsed: 1, flaggedDuplicates: 0, unparseableRows: [], detectedAccount,
      },
    } as never);
    const user = userEvent.setup();
    renderImport();

    await pickAndUploadPdf(user);

    expect(await screen.findByText('SOME BRAND NEW SHOP')).toBeInTheDocument();
    expect(screen.queryByText(/^Detected:/)).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/Import.test.tsx -t "detected merchant"`
Expected: FAIL — TypeScript compile error (`merchant`/`merchantConfidence` don't exist on the `StagedRow` type used in the test's mock object) and/or the "Detected: SWIGGY" text is never rendered.

- [ ] **Step 3: Write minimal implementation**

In `frontend/src/types/index.ts`, add two fields to the `StagedRow` interface right after `confidence` (currently ending at line 187):

```typescript
  confidence: number | null;
  // The canonical merchant name resolved during staging (read-only — staging never creates a new
  // Merchant/MerchantAlias row), or null when nothing matched an existing merchant. See the backend's
  // MerchantNormalizationEngine.resolveReadOnly for what "matched" means.
  merchant: string | null;
  // 1.0 when `merchant` was resolved, null otherwise. Not a rich confidence score in this phase.
  merchantConfidence: number | null;
}
```

In `frontend/src/pages/Import.tsx`, in `TransactionPreviewTable`'s row rendering (currently lines 1516-1522), add the detected-merchant line right after the description:

```typescript
            <td className="p-1">
              {r.description}
              {r.merchant && (
                <div className="text-[10px] text-muted">Detected: {r.merchant}</div>
              )}
              {r.likelyDuplicate && <span className="text-danger text-[10px] uppercase ml-1">duplicate</span>}
              {r.categorySource === 'default' && (
                <span className="text-[10px] uppercase ml-1" style={{ color: '#d97706' }}>low confidence</span>
              )}
            </td>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/Import.test.tsx -t "detected merchant"`
Expected: PASS

- [ ] **Step 5: Run the full frontend suite**

Run: `cd frontend && npx vitest run`
Expected: PASS, no regressions in the other `Import.test.tsx` blocks (they don't set `merchant`/`merchantConfidence` on their row fixtures, which is fine — TypeScript allows omitting fields only if the interface marks them optional; since this task defines them as `string | null` / `number | null`, not optional, **every existing test fixture object literal that builds a `StagedRow` inline will fail to compile** unless it already spreads a base object or the fields are added there too — see Step 5a below).

- [ ] **Step 5a: Fix pre-existing inline `StagedRow` fixtures broken by the new required fields**

The `stagedRow()` helper in the "Import — duplicate review gates the import" `describe` block (`frontend/src/pages/Import.test.tsx`, currently returning the object literal ending `duplicateMatch: duplicate ? { ...duplicateMatch, existingDescription: description } : null,`) constructs a `StagedRow`-shaped object without the two new fields. Add them to its return value:

```typescript
  function stagedRow(description: string, duplicate: boolean) {
    return {
      date: '2026-07-10',
      description,
      amount: 486,
      type: 'EXPENSE' as const,
      suggestedCategory: 'Dining',
      categorySource: 'rule' as const,
      ruleId: null,
      likelyDuplicate: duplicate,
      referenceNumber: null,
      balanceAfter: null,
      duplicateMatch: duplicate ? { ...duplicateMatch, existingDescription: description } : null,
      merchant: null,
      merchantConfidence: null,
    };
  }
```

Then search for any other file constructing a `StagedRow`-shaped object literal directly: `grep -rln "categorySource: 'rule'\|categorySource: \"rule\"\|suggestedCategory:" frontend/src --include=*.test.tsx`. For each match found, add `merchant: null, merchantConfidence: null,` to that literal. Re-run Step 5 until the full suite passes.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/types/index.ts frontend/src/pages/Import.tsx frontend/src/pages/Import.test.tsx
git commit -m "feat(import): show detected merchant identity on the review screen"
```

---

### Task 4: `TransactionGroupingService` + `GET /transactions/groups/needs-review`

**Files:**
- Create: `backend/src/main/java/com/finora/service/TransactionGroupingService.java`
- Modify: `backend/src/main/java/com/finora/transactions/TransactionController.java:16-30` (constructor), add new endpoint after `needsReview()` (currently lines 54-57)
- Test: `backend/src/test/java/com/finora/service/TransactionGroupingServiceTest.java` (new file)

**Interfaces:**
- Consumes: `TransactionRepository.findByUserIdAndNeedsCategoryReviewTrueOrderByTxnDateDesc(UUID userId): List<Transaction>` (`backend/src/main/java/com/finora/repository/TransactionRepository.java:69`); `Transaction.getId(): UUID` (inherited from `BaseEntity`); `Transaction.getMerchantId(): UUID` (`Transaction.java:198`); `MerchantRepository.findByIdAndUserId(UUID id, UUID userId): Optional<Merchant>` (`backend/src/main/java/com/finora/repository/MerchantRepository.java:49`); `Merchant.getCanonicalName(): String`.
- Produces: `TransactionGroupingService.MerchantGroup` record `(UUID merchantId, String merchantName, List<UUID> transactionIds)`; `TransactionGroupingService.groupNeedsReviewByMerchant(UUID userId): List<MerchantGroup>`, sorted largest group first, excluding groups smaller than 2 transactions. `GET /api/v1/transactions/groups/needs-review` returns `ApiResponse<List<TransactionGroupingService.MerchantGroup>>`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/finora/service/TransactionGroupingServiceTest.java`:

```java
package com.finora.service;

import com.finora.entity.Merchant;
import com.finora.entity.Transaction;
import com.finora.repository.MerchantRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransactionGroupingServiceTest {

    private final UUID userId = UUID.randomUUID();

    private Transaction txnFor(UUID merchantId) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setMerchantId(merchantId);
        t.setTxnDate(LocalDate.of(2026, 1, 1));
        t.setAmount(BigDecimal.TEN);
        return t;
    }

    @Test
    void groupsTransactionsByMerchant_excludingGroupsOfOne() {
        UUID swiggyId = UUID.randomUUID();
        UUID uniqueShopId = UUID.randomUUID();

        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findByUserIdAndNeedsCategoryReviewTrueOrderByTxnDateDesc(userId))
                .thenReturn(List.of(txnFor(swiggyId), txnFor(swiggyId), txnFor(uniqueShopId)));

        Merchant swiggy = new Merchant();
        swiggy.setCanonicalName("SWIGGY");
        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        when(merchantRepository.findByIdAndUserId(swiggyId, userId)).thenReturn(Optional.of(swiggy));

        TransactionGroupingService service = new TransactionGroupingService(transactionRepository, merchantRepository);
        List<TransactionGroupingService.MerchantGroup> groups = service.groupNeedsReviewByMerchant(userId);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).merchantName()).isEqualTo("SWIGGY");
        assertThat(groups.get(0).transactionIds()).hasSize(2);
    }

    @Test
    void excludesTransactionsWithNoMerchantIdentity() {
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        Transaction noMerchant = txnFor(null);
        when(transactionRepository.findByUserIdAndNeedsCategoryReviewTrueOrderByTxnDateDesc(userId))
                .thenReturn(List.of(noMerchant, noMerchant));
        MerchantRepository merchantRepository = mock(MerchantRepository.class);

        TransactionGroupingService service = new TransactionGroupingService(transactionRepository, merchantRepository);
        List<TransactionGroupingService.MerchantGroup> groups = service.groupNeedsReviewByMerchant(userId);

        assertThat(groups).isEmpty();
    }

    @Test
    void sortsLargestGroupFirst() {
        UUID swiggyId = UUID.randomUUID();
        UUID uberId = UUID.randomUUID();

        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findByUserIdAndNeedsCategoryReviewTrueOrderByTxnDateDesc(userId))
                .thenReturn(List.of(txnFor(uberId), txnFor(uberId), txnFor(swiggyId), txnFor(swiggyId), txnFor(swiggyId)));

        Merchant swiggy = new Merchant();
        swiggy.setCanonicalName("SWIGGY");
        Merchant uber = new Merchant();
        uber.setCanonicalName("UBER");
        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        when(merchantRepository.findByIdAndUserId(swiggyId, userId)).thenReturn(Optional.of(swiggy));
        when(merchantRepository.findByIdAndUserId(uberId, userId)).thenReturn(Optional.of(uber));

        TransactionGroupingService service = new TransactionGroupingService(transactionRepository, merchantRepository);
        List<TransactionGroupingService.MerchantGroup> groups = service.groupNeedsReviewByMerchant(userId);

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).merchantName()).isEqualTo("SWIGGY");
        assertThat(groups.get(1).merchantName()).isEqualTo("UBER");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw -q -o test -Dtest=TransactionGroupingServiceTest`
Expected: FAIL — compile error, `TransactionGroupingService` doesn't exist yet.

- [ ] **Step 3: Write minimal implementation**

Create `backend/src/main/java/com/finora/service/TransactionGroupingService.java`:

```java
package com.finora.service;

import com.finora.entity.Merchant;
import com.finora.entity.Transaction;
import com.finora.repository.MerchantRepository;
import com.finora.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Groups a user's already-persisted needs-review transactions by merchant, so the Ledger can offer
 * "5 Swiggy transactions found" instead of 5 separate one-by-one corrections. Reuses the existing
 * needs-review query and re-groups in Java rather than a GROUP BY query, the same choice
 * AdminPlatformAnalyticsService already made for the identical reason: Transaction has no JPA
 * association to Merchant, only a plain UUID column, so there's no JPQL join path to the name.
 *
 * <p>Groups of exactly one transaction are deliberately excluded — those stay in the existing
 * AskOnceCard one-by-one flow (see docs/proposals/transaction-intelligence-engine-phase0-audit.md),
 * so nothing changes for a user with no repeat-merchant backlog.
 */
@Service
public class TransactionGroupingService {

    private static final int MIN_GROUP_SIZE = 2;

    private final TransactionRepository transactionRepository;
    private final MerchantRepository merchantRepository;

    public TransactionGroupingService(TransactionRepository transactionRepository,
                                       MerchantRepository merchantRepository) {
        this.transactionRepository = transactionRepository;
        this.merchantRepository = merchantRepository;
    }

    public record MerchantGroup(UUID merchantId, String merchantName, List<UUID> transactionIds) {}

    public List<MerchantGroup> groupNeedsReviewByMerchant(UUID userId) {
        List<Transaction> candidates =
                transactionRepository.findByUserIdAndNeedsCategoryReviewTrueOrderByTxnDateDesc(userId);

        Map<UUID, List<UUID>> idsByMerchant = new LinkedHashMap<>();
        for (Transaction t : candidates) {
            if (t.getMerchantId() == null) continue;
            idsByMerchant.computeIfAbsent(t.getMerchantId(), k -> new ArrayList<>()).add(t.getId());
        }

        List<MerchantGroup> groups = new ArrayList<>();
        for (var entry : idsByMerchant.entrySet()) {
            if (entry.getValue().size() < MIN_GROUP_SIZE) continue;
            Merchant merchant = merchantRepository.findByIdAndUserId(entry.getKey(), userId).orElse(null);
            if (merchant == null) continue;
            groups.add(new MerchantGroup(entry.getKey(), merchant.getCanonicalName(), entry.getValue()));
        }

        groups.sort((a, b) -> b.transactionIds().size() - a.transactionIds().size());
        return groups;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw -q -o test -Dtest=TransactionGroupingServiceTest`
Expected: PASS

- [ ] **Step 5: Wire the controller endpoint**

In `backend/src/main/java/com/finora/transactions/TransactionController.java`, add the new dependency to the constructor (currently lines 20-30):

```java
    private final TransactionService transactionService;
    private final TransactionExplanationService explanationService;
    private final TransactionGroupingService transactionGroupingService;
    private final CurrentUser currentUser;

    public TransactionController(TransactionService transactionService,
                                  TransactionExplanationService explanationService,
                                  TransactionGroupingService transactionGroupingService,
                                  CurrentUser currentUser) {
        this.transactionService = transactionService;
        this.explanationService = explanationService;
        this.transactionGroupingService = transactionGroupingService;
        this.currentUser = currentUser;
    }
```

Add the import `import com.finora.service.TransactionGroupingService;` near the top, and add the new endpoint right after `needsReview()` (currently ending line 57):

```java
    /** Backs the Ledger's bulk "N similar transactions found" review card (Phase A). */
    @GetMapping("/groups/needs-review")
    public ApiResponse<List<TransactionGroupingService.MerchantGroup>> needsReviewGroups() {
        return ApiResponse.ok(transactionGroupingService.groupNeedsReviewByMerchant(currentUser.id()));
    }
```

- [ ] **Step 6: Run the full backend suite**

Run: `cd backend && ./mvnw -q -o test`
Expected: PASS. There is no pre-existing `TransactionControllerTest` in this codebase (controllers here are thin pass-throughs; logic is tested at the service level, which Step 4 already covers) — this step's purpose is confirming the constructor change compiles and every existing test that builds a `TransactionController` (if any do so directly rather than via Spring context) still does.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/service/TransactionGroupingService.java backend/src/test/java/com/finora/service/TransactionGroupingServiceTest.java backend/src/main/java/com/finora/transactions/TransactionController.java
git commit -m "feat(transactions): group needs-review transactions by merchant"
```

---

### Task 5: `MerchantGroupReviewCard` frontend component

**Files:**
- Create: `frontend/src/components/MerchantGroupReviewCard.tsx`
- Create: `frontend/src/components/MerchantGroupReviewCard.test.tsx`
- Modify: `frontend/src/types/index.ts` (add `MerchantGroup` interface)
- Modify: `frontend/src/api/endpoints.ts:208-224` (add `groupsNeedsReview` and reuse existing `bulkRecategorize`)

**Interfaces:**
- Consumes: `transactionsApi.bulkRecategorize(ids: string[], category: string)` (`frontend/src/api/endpoints.ts:223-224`, already exists); `categoriesApi.list()` (already exists, used identically in `AskOnceCard.tsx:33`).
- Produces: `transactionsApi.groupsNeedsReview(): Promise<MerchantGroup[]>`; `MerchantGroupReviewCard` component, rendering nothing when there are no groups (same convention as `AskOnceCard`'s `if (loading || items.length === 0) return null;`).

- [ ] **Step 1: Write the failing test**

Create `frontend/src/components/MerchantGroupReviewCard.test.tsx`:

```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MerchantGroupReviewCard } from './MerchantGroupReviewCard';
import { transactionsApi, categoriesApi } from '../api/endpoints';
import type { MerchantGroup } from '../types';

vi.mock('../api/endpoints', () => ({
  transactionsApi: { groupsNeedsReview: vi.fn(), bulkRecategorize: vi.fn() },
  categoriesApi: { list: vi.fn() },
}));

function group(merchantName: string, count: number): MerchantGroup {
  return {
    merchantId: `m-${merchantName}`,
    merchantName,
    transactionIds: Array.from({ length: count }, (_, i) => `${merchantName}-${i}`),
  };
}

function renderCard() {
  const queryClient = new QueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <MerchantGroupReviewCard />
    </QueryClientProvider>
  );
}

describe('MerchantGroupReviewCard', () => {
  beforeEach(() => {
    vi.mocked(categoriesApi.list).mockResolvedValue([{ name: 'Food' }, { name: 'Transport' }] as any);
  });

  it('renders nothing when there are no groups', async () => {
    vi.mocked(transactionsApi.groupsNeedsReview).mockResolvedValue([]);
    const { container } = renderCard();

    await waitFor(() => expect(transactionsApi.groupsNeedsReview).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it('shows each merchant group with its transaction count', async () => {
    vi.mocked(transactionsApi.groupsNeedsReview).mockResolvedValue([group('SWIGGY', 5), group('UBER', 3)]);
    renderCard();

    expect(await screen.findByText('SWIGGY')).toBeInTheDocument();
    expect(screen.getByText('5 transactions')).toBeInTheDocument();
    expect(screen.getByText('UBER')).toBeInTheDocument();
    expect(screen.getByText('3 transactions')).toBeInTheDocument();
  });

  it('bulk-applies the chosen category to every transaction in the group', async () => {
    vi.mocked(transactionsApi.groupsNeedsReview).mockResolvedValue([group('SWIGGY', 5)]);
    vi.mocked(transactionsApi.bulkRecategorize).mockResolvedValue(undefined as never);
    const user = userEvent.setup();
    renderCard();

    await screen.findByText('SWIGGY');
    await user.selectOptions(screen.getByRole('combobox'), 'Food');
    await user.click(screen.getByRole('button', { name: /apply to 5 transactions/i }));

    await waitFor(() =>
      expect(transactionsApi.bulkRecategorize).toHaveBeenCalledWith(
        ['SWIGGY-0', 'SWIGGY-1', 'SWIGGY-2', 'SWIGGY-3', 'SWIGGY-4'], 'Food'));
  });

  it('removes the group from the list once applied', async () => {
    vi.mocked(transactionsApi.groupsNeedsReview).mockResolvedValue([group('SWIGGY', 5)]);
    vi.mocked(transactionsApi.bulkRecategorize).mockResolvedValue(undefined as never);
    const user = userEvent.setup();
    renderCard();

    await screen.findByText('SWIGGY');
    await user.selectOptions(screen.getByRole('combobox'), 'Food');
    await user.click(screen.getByRole('button', { name: /apply to 5 transactions/i }));

    await waitFor(() => expect(screen.queryByText('SWIGGY')).not.toBeInTheDocument());
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/components/MerchantGroupReviewCard.test.tsx`
Expected: FAIL — `MerchantGroupReviewCard` module doesn't exist yet.

- [ ] **Step 3: Write minimal implementation**

In `frontend/src/types/index.ts`, add near the other transaction-related interfaces:

```typescript
export interface MerchantGroup {
  merchantId: string;
  merchantName: string;
  transactionIds: string[];
}
```

In `frontend/src/api/endpoints.ts`, inside the existing `transactionsApi` object (currently lines 208-224), add:

```typescript
  groupsNeedsReview: () =>
    api.get<MerchantGroup[]>('/transactions/groups/needs-review').then((r) => r.data),
```

(add `MerchantGroup` to the existing `import type { ... } from '../types'` at the top of the file, alongside whatever is already imported there).

Create `frontend/src/components/MerchantGroupReviewCard.tsx`:

```typescript
import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Users, Check } from 'lucide-react';
import { transactionsApi, categoriesApi } from '../api/endpoints';
import type { MerchantGroup } from '../types';

/**
 * "5 Swiggy transactions found" — bulk-apply a category to every needs-review transaction sharing
 * a merchant, in one action. Same load/select/confirm shape as AskOnceCard, and calls the same
 * category-write path (bulkRecategorize, which itself queues the identical merchant-learning event
 * updateCategory does) — the two cards split the needs-review backlog by group size, they don't
 * duplicate each other's job. Groups of one stay in AskOnceCard; this only ever shows groups of 2+
 * (TransactionGroupingService.groupNeedsReviewByMerchant already filters that server-side).
 */
export function MerchantGroupReviewCard() {
  const queryClient = useQueryClient();
  const [groups, setGroups] = useState<MerchantGroup[]>([]);
  const [categories, setCategories] = useState<string[]>([]);
  const [picks, setPicks] = useState<Record<string, string>>({});
  const [applying, setApplying] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  function load() {
    setLoading(true);
    Promise.all([transactionsApi.groupsNeedsReview(), categoriesApi.list()])
      .then(([g, cats]) => {
        setGroups(g);
        setCategories(cats.map((c) => c.name));
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }
  useEffect(load, []);

  async function apply(group: MerchantGroup) {
    const category = picks[group.merchantId];
    if (!category) return;
    setApplying(group.merchantId);
    setError(null);
    try {
      await transactionsApi.bulkRecategorize(group.transactionIds, category);
      setGroups((prev) => prev.filter((g) => g.merchantId !== group.merchantId));
      void queryClient.invalidateQueries({ queryKey: ['transactions'] });
      void queryClient.invalidateQueries({ queryKey: ['recent-transactions'] });
      void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
      void queryClient.invalidateQueries({ queryKey: ['insights'] });
      void queryClient.invalidateQueries({ queryKey: ['budgets'] });
    } catch {
      setError("Couldn't apply that category — please try again.");
    } finally {
      setApplying(null);
    }
  }

  if (loading || groups.length === 0) return null;

  return (
    <div className="bg-card rounded-xl2 p-5 shadow-card border border-border mb-6">
      <div className="flex items-center gap-2 mb-1">
        <Users size={17} className="text-primary" />
        <h2 className="font-semibold text-ink text-sm">Categorize a whole merchant at once</h2>
      </div>
      <p className="text-xs text-muted mb-4">
        These merchants have multiple transactions needing a category — apply one to all of them.
      </p>
      {error && <p className="text-xs text-danger mb-3">{error}</p>}
      <div className="space-y-3">
        {groups.map((g) => (
          <div key={g.merchantId} className="flex items-center gap-3 flex-wrap sm:flex-nowrap">
            <div className="min-w-0 flex-1">
              <p className="text-sm font-medium text-ink truncate">{g.merchantName}</p>
              <p className="text-[11px] text-muted">{g.transactionIds.length} transactions</p>
            </div>
            <select
              value={picks[g.merchantId] ?? ''}
              onChange={(e) => setPicks((p) => ({ ...p, [g.merchantId]: e.target.value }))}
              className="bg-card text-ink border border-border rounded-lg px-2.5 py-1.5 text-xs flex-shrink-0"
            >
              <option value="" disabled>Choose category…</option>
              {categories.map((c) => <option key={c} value={c}>{c}</option>)}
            </select>
            <button
              onClick={() => apply(g)}
              disabled={!picks[g.merchantId] || applying === g.merchantId}
              className="bg-primary text-on-primary text-xs font-medium rounded-lg px-3 py-1.5 flex items-center gap-1 flex-shrink-0 disabled:opacity-40"
            >
              <Check size={13} />
              {applying === g.merchantId ? 'Applying…' : `Apply to ${g.transactionIds.length} transactions`}
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/components/MerchantGroupReviewCard.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/types/index.ts frontend/src/api/endpoints.ts frontend/src/components/MerchantGroupReviewCard.tsx frontend/src/components/MerchantGroupReviewCard.test.tsx
git commit -m "feat(transactions): add merchant-group bulk categorization card"
```

---

### Task 6: Mount `MerchantGroupReviewCard` on the Ledger page

**Files:**
- Modify: `frontend/src/pages/Ledger.tsx:6,114` (or wherever `AskOnceCard` is imported/rendered — confirmed at these two lines during Phase 0 audit)
- Test: `frontend/src/pages/Ledger.test.tsx` (add one assertion)

**Interfaces:**
- Consumes: `MerchantGroupReviewCard` from Task 5.

- [ ] **Step 1: Write the failing test**

Add to `frontend/src/pages/Ledger.test.tsx` (mirroring however that file already mocks `AskOnceCard`/`MerchantGroupReviewCard`-shaped dependencies — check the file's existing top-level `vi.mock` calls first; if it already mocks the whole `AskOnceCard` module to avoid firing its real network calls during Ledger tests, mock `MerchantGroupReviewCard` the same way):

```typescript
vi.mock('../components/MerchantGroupReviewCard', () => ({
  MerchantGroupReviewCard: () => <div data-testid="merchant-group-review-card" />,
}));
```

Then add a test:

```typescript
it('renders the merchant group review card above the transaction list', async () => {
  renderLedger(); // reuse whatever render helper this file already has
  expect(await screen.findByTestId('merchant-group-review-card')).toBeInTheDocument();
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/Ledger.test.tsx -t "merchant group review card"`
Expected: FAIL — `MerchantGroupReviewCard` is not rendered anywhere in `Ledger.tsx` yet.

- [ ] **Step 3: Write minimal implementation**

In `frontend/src/pages/Ledger.tsx`, add the import next to the existing `AskOnceCard` import (line 6):

```typescript
import { AskOnceCard } from '../components/AskOnceCard';
import { MerchantGroupReviewCard } from '../components/MerchantGroupReviewCard';
```

Render it immediately above `<AskOnceCard />` (currently line 114) — groups (bigger wins) before individual items:

```typescript
      <MerchantGroupReviewCard />
      <AskOnceCard />
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/Ledger.test.tsx`
Expected: PASS, no regressions in the rest of `Ledger.test.tsx`.

- [ ] **Step 5: Run the full frontend suite one final time**

Run: `cd frontend && npx vitest run`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/Ledger.tsx frontend/src/pages/Ledger.test.tsx
git commit -m "feat(ledger): surface merchant group review card"
```

---

## Explicitly out of scope for this plan (deferred to later Phase A/B items per the audit)

- Mobile app parity (`mobile/src/screens/import/ImportScreen.tsx`, `StagedRowCard.tsx`) — the audit flagged frontend/mobile duplication as a standing risk; replicating this plan's UI changes there is a follow-up, not part of this plan.
- The "apply to future transactions from this merchant" explicit consent UI (audit item 4 / roadmap item 4) — `bulkRecategorize` already queues the same merchant-learning event `updateCategory` does (per `BulkRecategorizeLearningIT`), so future transactions already benefit automatically; making that consequence visible to the user in the UI is separate follow-up work.
- Any confidence-score richer than the binary "resolved or not" signal in this plan (audit item 5 / roadmap item 5 — the initiative's own "Confidence Engine" phase, deliberately sequenced after this one).
- Grouping/bulk-apply at import-review (staging) time, as opposed to the Ledger — see this plan's Architecture section for why that's a separate, larger piece of work.
