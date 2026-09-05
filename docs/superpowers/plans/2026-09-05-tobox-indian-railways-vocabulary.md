# Tobox & Indian Railways Vocabulary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two real, multi-document-verified vocabulary misses to `CategoryRules` — `Tobox Ventures` (Dining) and `Indian Railways` (Transport) — found by the mining pass in the 2026-09-05 categorization-vocabulary-expansion plan's Task 1, and explicitly deferred out of that plan's scope (PR #987) for their own follow-up.

**Architecture:** No architecture change, same as the plan this follows from. Pure appends to the existing `CategoryRules.RULES` static map, picked up automatically by the existing word-boundary `RULE_PATTERNS` compilation. No new category, no Flyway migration.

**Tech Stack:** Java 21, JUnit 5, AssertJ (`backend/src/test/java/com/finora/util/CategoryRulesTest.java`).

**Spec:** `docs/superpowers/specs/2026-09-01-transaction-categorization-design.md` — §8 item #3 ("Expanded deterministic rules... diminishing returns after the first pass"). This plan's own evidence comes from a fresh mining pass (`pdftotext` over the real 29-document corpus, real narration text read directly in-session and never committed), not from the spec's original 2026-09-01 sample.

## Global Constraints

- **Word-boundary matching only**, via the existing `WORD_BOUNDARY + Pattern.quote(w) + WORD_BOUNDARY` compilation. Never assume `String.contains` semantics.
- **Append, never interleave.** New keywords go at the end of their category's existing list (Dining, Transport), matching this file's established convention.
- **Collision-checked before writing.** Both `tobox` and `indian railways` were checked against the full current keyword table (~92 entries) and against each other — no whole-word collision either direction. Verified by running the actual regex logic, not by inspection alone.
- **No new category, no Flyway migration.** Both categories (`Dining`, `Transport`) already exist in `AuthService.DEFAULT_CATEGORIES`.
- **No PII in code, comments, or tests.** `Tobox Ventures` and `Indian Railways` are public business/institution names — safe to use verbatim, the same class of value already committed for `gokhana`, `asspl`, etc. The raw narration lines that surfaced them (reference numbers, exact statement dates) were read directly during mining and are not reproduced here or in any test — only the entity name and category are carried forward, per this repo's own "describe, don't quote" discipline.

---

### Task 1: Add "tobox" — the corporate name behind an already-shipped brand

**Files:**
- Modify: `backend/src/main/java/com/finora/util/CategoryRules.java:44` (Dining)
- Test: `backend/src/test/java/com/finora/util/CategoryRulesTest.java`

**Interfaces:**
- Consumes/produces nothing new — pure data addition to the existing `CategoryRules.RULES` map.

**Context:** `Tobox Ventures Private Limited` is the registered corporate name behind "Gokhana" (the workplace-cafeteria ordering platform already in `RULES.put("Dining", ...)`) — one real narration explicitly links them (`TOBOX VENTURES PRIVATE LIMITED/GOKHANA.`). Some statements print the merchant under this corporate name instead of the "Gokhana" brand name, so the existing `gokhana` keyword misses them. Verified across **6 of the 29 corpus documents** (79 rows total) — multiple distinct payers, not one person's recurring vendor, which is the same bar `gokhana` itself was held to when it was added.

Kept as the bare single word `tobox` rather than the two-word `tobox ventures`: one real narration form is truncated to `TOBOX VENT` (a column-width truncation on at least one statement), and a two-word phrase keyword would miss that truncated form entirely. `tobox` alone catches both the truncated and full forms and is not a substring of, or a container of, any other keyword in the table.

- [ ] **Step 1: Write the failing tests**

Add to `backend/src/test/java/com/finora/util/CategoryRulesTest.java`, before the final closing brace:

```java
    /**
     * Real corpus finding: "Tobox Ventures" is the registered corporate name behind "Gokhana"
     * (a real narration reads "TOBOX VENTURES PRIVATE LIMITED/GOKHANA."), appearing as the
     * merchant name on statements that print the corporate entity rather than the brand.
     */
    @Test
    void suggestCategory_matchesTobox_corporateNameBehindGokhana() {
        assertThat(CategoryRules.suggestCategory("UPI-TOBOX VENTURES-REF551209")).isEqualTo("Dining");
    }

    /** Some real statements truncate this narration to "TOBOX VENT" (a column-width truncation) --
     *  the keyword must still match on that shortened form, which is why "tobox" is kept as a bare
     *  single word rather than the two-word "tobox ventures". */
    @Test
    void suggestCategory_matchesTobox_evenWhenNarrationIsTruncated() {
        assertThat(CategoryRules.suggestCategory("UPI/TOBOX VENT/REF88213")).isEqualTo("Dining");
    }

    /** Word-boundary collision guard: "tobox" must not match as a prefix inside a longer,
     *  unrelated word. */
    @Test
    void suggestCategory_toboxicIsNotMisclassifiedAsDining() {
        assertThat(CategoryRules.suggestCategory("TOBOXIC LEATHERWORKS FEE")).isNotEqualTo("Dining");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=CategoryRulesTest`
Expected: `suggestCategory_matchesTobox_corporateNameBehindGokhana` and `suggestCategory_matchesTobox_evenWhenNarrationIsTruncated` FAIL, both returning `"Other"`. `suggestCategory_toboxicIsNotMisclassifiedAsDining` already PASSES (nothing matches `"TOBOXIC"` yet) — expected, it's a regression guard for the next step. All pre-existing tests still PASS.

- [ ] **Step 3: Add the keyword**

In `backend/src/main/java/com/finora/util/CategoryRules.java`, replace:

```java
        RULES.put("Dining", List.of("swiggy", "zomato", "restaurant", "cafe", "starbucks", "dominos", "mcdonald", "kfc", "cinnabon", "gokhana"));
```

with:

```java
        // "tobox" (Tobox Ventures Private Limited, the registered corporate name behind
        // "Gokhana" -- a real narration links them directly: "TOBOX VENTURES PRIVATE LIMITED/
        // GOKHANA.") added after re-checking this project's own real bank-statement corpus for
        // additional vocabulary beyond the 2026-09-01 review (docs/superpowers/plans/2026-09-05-
        // categorization-vocabulary-expansion.md Task 1). Kept as a bare word rather than "tobox
        // ventures" because one real statement truncates the narration to "TOBOX VENT" -- a
        // two-word phrase keyword would miss that form. Safe as a bare keyword: not a substring of,
        // or a container of, any other keyword in this table.
        RULES.put("Dining", List.of("swiggy", "zomato", "restaurant", "cafe", "starbucks", "dominos", "mcdonald", "kfc", "cinnabon", "gokhana", "tobox"));
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=CategoryRulesTest`
Expected: PASS — all three new tests, and every pre-existing test in this file.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/util/CategoryRules.java backend/src/test/java/com/finora/util/CategoryRulesTest.java
git commit -m "feat(transactions): add tobox keyword, the corporate name behind gokhana"
```

---

### Task 2: Add "indian railways"

**Files:**
- Modify: `backend/src/main/java/com/finora/util/CategoryRules.java:45` (Transport)
- Test: `backend/src/test/java/com/finora/util/CategoryRulesTest.java`

**Interfaces:**
- Consumes/produces nothing new — same as Task 1.

**Context:** `Indian Railways` is a distinct real narration form for the national railway institution, already partially covered by the existing `irctc` keyword (IRCTC is Indian Railways' ticketing subsidiary, but statements can name the institution directly rather than the booking portal). Verified across **4 of the 29 corpus documents**.

Kept as the two-word phrase `indian railways` rather than a bare `indian` or bare `railways`: a bare `indian` would collide with unrelated narrations (e.g. "INDIAN CLEARING CORP" settlement lines, seen in the same corpus, which must NOT categorize as Transport) — matching the same reasoning already applied to `nse mf` and `cc payment` in the prior plan.

- [ ] **Step 1: Write the failing tests**

Add to `backend/src/test/java/com/finora/util/CategoryRulesTest.java`, before the final closing brace:

```java
    /**
     * Real corpus finding: "Indian Railways" is a distinct real narration form for the national
     * railway institution, naming it directly rather than through the "irctc" booking portal
     * already in this table.
     */
    @Test
    void suggestCategory_matchesIndianRailways_asDistinctFromIrctc() {
        assertThat(CategoryRules.suggestCategory("UPI-INDIAN RAILWAYS-REF662140")).isEqualTo("Transport");
    }

    /** Word-boundary/phrase collision guard: a bare "indian" would misfire on this real corpus
     *  narration ("INDIAN CLEARING CORP" settlement lines) -- kept as the full two-word phrase
     *  specifically to avoid it, the same choice already made for "nse mf" and "cc payment". */
    @Test
    void suggestCategory_indianClearingCorpIsNotMisclassifiedAsTransport() {
        assertThat(CategoryRules.suggestCategory("INDIAN CLEARING CORP SETTLEMENT")).isNotEqualTo("Transport");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=CategoryRulesTest`
Expected: `suggestCategory_matchesIndianRailways_asDistinctFromIrctc` FAILS, returning `"Other"`. `suggestCategory_indianClearingCorpIsNotMisclassifiedAsTransport` already PASSES (nothing matches "INDIAN CLEARING CORP" yet) — expected, it's a regression guard for the next step. All pre-existing tests still PASS.

- [ ] **Step 3: Add the keyword**

In `backend/src/main/java/com/finora/util/CategoryRules.java`, replace:

```java
        RULES.put("Transport", List.of("uber", "ola", "rapido", "irctc", "petrol", "fuel", "metro", "fastag", "parking"));
```

with:

```java
        // "indian railways" (the national railway institution, named directly rather than
        // through its "irctc" booking portal already above) added after re-checking this
        // project's own real bank-statement corpus for additional vocabulary beyond the
        // 2026-09-01 review (docs/superpowers/plans/2026-09-05-categorization-vocabulary-
        // expansion.md Task 1). Kept as the full two-word phrase, not a bare "indian": a bare
        // keyword would misfire on real "INDIAN CLEARING CORP" settlement narrations seen in the
        // same corpus (guarded by suggestCategory_indianClearingCorpIsNotMisclassifiedAsTransport).
        RULES.put("Transport", List.of("uber", "ola", "rapido", "irctc", "petrol", "fuel", "metro", "fastag", "parking", "indian railways"));
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=CategoryRulesTest`
Expected: PASS — both new tests, and every pre-existing test in this file.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/util/CategoryRules.java backend/src/test/java/com/finora/util/CategoryRulesTest.java
git commit -m "feat(transactions): add indian railways keyword, distinct from irctc"
```

---

## Self-Review

**1. Spec coverage.** Both keywords trace to the 2026-09-05 categorization-vocabulary-expansion plan's Task 1 mining output (`Tobox Ventures`, `Indian Railways`), which this plan was explicitly written to pick up. `Ekart` (the third candidate from that mining pass) is intentionally NOT included here — it's a genuinely separate case (a logistics/delivery arm, not a name-collision with an already-shipped brand or an institution-vs-subsidiary split) and can be its own follow-up if wanted, rather than being folded in just because it was found at the same time.

**2. Placeholder scan.** No "TBD"/deferred content. Both tasks have complete code for every step.

**3. Type consistency.** Both tasks touch only `CategoryRules.RULES` (existing `Map<String, List<String>>`) and `CategoryRulesTest` (existing JUnit 5 + AssertJ conventions) — no new types or signatures.

**Regex behavior independently verified** (not just reasoned about) before writing every test expectation in this plan, including both collision guards — same discipline as the prior plan.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-09-05-tobox-indian-railways-vocabulary.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
