# Categorization Vocabulary Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close a small, verified slice of the missing-business-vocabulary gap in `CategoryRules` — the largest remaining lever against "Other" per the design spec's own effort/risk ranking (§8, item #3) — and set up the repeatable procedure for finding the next slice.

**Architecture:** No architecture change. `CategoryRules.RULES` is a static, in-code `Map<String, List<String>>` matched via word-boundary regex (`RULE_PATTERNS`) inside `suggestCategory()`. This plan only appends new keyword entries to existing category lists, exactly as the 2026-09-01 plan's Task 2 (`asspl`/`cinnabon`/`cc payment`) and the later `gokhana`/`nwd` additions already did. No new category, no new Flyway migration, no change to match order between existing categories.

**Tech Stack:** Java 21, JUnit 5, AssertJ (`backend/src/test/java/com/finora/util/CategoryRulesTest.java`), `pdftotext` (already-established manual-investigation tool per this project's own history) for Task 1's evidence gathering.

**Spec:** `docs/superpowers/specs/2026-09-01-transaction-categorization-design.md` — §1 ("Real, legitimate merchants are missing from the vocabulary in ways that are hard to predict", citing `ASSPL`, `Cinnabon`, `Housingcom Gurgaon`, `Pureplay Skin Sciences`, `JNS-PMJJBY`, `NET PAYIN TO NSE MF A/C` as real corpus findings) and §8 (item #3, "Expanded deterministic rules... diminishing returns after the first pass").

## Global Constraints

- **Word-boundary matching only.** Every new keyword is added to `CategoryRules.RULES` and picked up automatically by the existing `RULE_PATTERNS` compilation (`WORD_BOUNDARY + Pattern.quote(w) + WORD_BOUNDARY`). Never add a keyword expecting `String.contains`-style substring behavior.
- **Append, never interleave.** Insertion order inside a category's list, and category order inside `RULES`, is match priority (first-match-wins in `suggestCategory`'s loop). New keywords go at the end of their category's existing list, matching the established comment convention ("Appended after the original set... insertion order is match priority").
- **Collision-check every new keyword before adding it**, against the full existing vocabulary (`CategoryRules.allKeywords()`): does the new keyword appear as a bounded whole-word substring of any existing keyword, or vice versa? `\b<word>\b` only matches a token bounded by non-alphanumeric characters (or string edges) — it cannot match inside a longer contiguous alphanumeric run — so the real risk is two keywords that are the *same standalone word/phrase* in different categories, not one being visually "inside" another.
- **No new category, no Flyway migration.** Matches the 2026-09-01 plan's Task 2 precedent — this is a static in-code table, not a schema change. A category used by a new keyword must already exist in `AuthService.DEFAULT_CATEGORIES`.
- **No PII in code, comments, or tests.** Business/brand/government-scheme names already documented in the committed design spec (`ASSPL`, `Cinnabon`, `Housingcom`, `Pureplay Skin Sciences`, `JNS-PMJJBY`, `NSE MF`) are public entity names, not personal data, and are safe to use verbatim — this is the same class of value already committed in `CategoryRules.java`'s existing comments. Account numbers, IFSC codes, phone numbers, or personal names must never be copied from a real document into a comment or test fixture, even as an aid to "being specific" — invent a placeholder instead (this project's own recorded history has this mistake happening five times).
- **Investigation output stays out of the repo.** Any raw narration text surfaced while hunting for new vocabulary (Task 1) is read directly by a human/session and never committed, logged persistently, or pasted into a comment beyond the single verified business-name token it yields.

---

### Task 1: Mine the current real corpus for additional verified vocabulary misses

**Files:** None committed. This task produces a candidate list (business name → category → justification), not a code change.

**Context:** The design spec's original "Other" analysis is from 2026-09-01/02 — before the extraction-quality fixes in PRs #793 (wrapped narration), #871 (column-spill containment), and #930 (section-splitting/header-bleed, merged 2026-09-05) landed. Those fixes changed what real narration text now reaches categorization at all, so today's actual unresolved vocabulary may differ from the stratified sample the spec used. This task refreshes that evidence before Task 2/3 lock in specific keywords, and produces the input for whatever follow-up plan continues this pass beyond the 4 items Task 2/3 already cover.

Real per-transaction narration text for the corpus is deliberately NOT exposed by the committed test-tree tooling (`CorpusProbe` only reveals `descriptionHashes` for real documents, gated behind `--synthetic` for raw content — see its class javadoc). Getting real text for direct human reading therefore has to go around that path, the same way this project's own prior investigations did (`pdftotext` directly on the source PDF).

- [ ] **Step 1: Extract raw text from the real corpus**

```bash
mkdir -p /tmp/vocab-mining
find ~/"Downloads/Bank statement" -iname '*.pdf' | while read -r f; do
  out="/tmp/vocab-mining/$(basename "${f%.pdf}" | tr ' /' '__').txt"
  pdftotext -layout "$f" "$out"
done
```

- [ ] **Step 2: Read each dump and cross-reference against the existing keyword table**

For each `.txt` file in `/tmp/vocab-mining`, read it directly (this is a live session reading its own scratch output, not a persisted artifact) and look at transaction-line narrations. For each one, check whether any token in it is already covered by `CategoryRules.allKeywords()` (the full flattened keyword set — print it once via a throwaway `jshell` snippet or by reading `CategoryRules.java`'s `RULES` block directly, since the table is under 90 entries). A narration whose merchant/entity token matches nothing in that set, and which names a real, identifiable business, government scheme, or institution (not a person's name — that's the P2P detector's territory, not this table's) is a candidate.

- [ ] **Step 3: Record candidates**

For each candidate found, write down: the business/entity name (generalized — e.g. "a workplace-cafeteria platform brand", not the raw narration line with its reference numbers), the best-fitting existing category from `AuthService.DEFAULT_CATEGORIES`, and a one-line justification (what the entity actually is, and why that category fits). Task 2 and Task 3 below already cover four such candidates found in the original spec review (`Pureplay Skin Sciences`, `JNS-PMJJBY`, `NET PAYIN TO NSE MF A/C`, `Housingcom`); anything new this task finds is out of this plan's scope — write a follow-up plan for it using Task 2/3 as the template, rather than appending open-ended, not-yet-verified keywords here.

- [ ] **Step 4: Delete the scratch dumps**

```bash
rm -rf /tmp/vocab-mining
```

Nothing from this task is committed. If Step 3 found zero new candidates beyond the four below, that itself is worth recording in the follow-up conversation — it means this lever is closer to exhausted than the spec's ~36%/~400-row projection assumed.

---

### Task 2: Add three verified, unambiguous vocabulary misses

**Files:**
- Modify: `backend/src/main/java/com/finora/util/CategoryRules.java:47` (Shopping), `:73` (Insurance), `:55` (Investments)
- Test: `backend/src/test/java/com/finora/util/CategoryRulesTest.java`

**Interfaces:**
- Consumes/produces nothing new — pure data addition to the existing `CategoryRules.RULES` map, same as Task 2 of the 2026-09-01 plan.

**Context:** Three real, verified misses from the design spec's §1 corpus review, each with an unambiguous category fit:
- `Pureplay Skin Sciences` — a real Indian D2C skincare/personal-care e-commerce brand, sold online alongside brands like Nykaa (already a Shopping keyword). Maps to **Shopping**.
- `JNS-PMJJBY` — PMJJBY (Pradhan Mantri Jeevan Jyoti Bima Yojana) is a real Government of India life-insurance scheme; the `JNS-` prefix is a bank-specific narration code. Maps to **Insurance**, alongside the existing `insurance`/`lic premium` keywords.
- `NET PAYIN TO NSE MF A/C` — NSE MF is the National Stock Exchange's mutual-fund investment platform. Maps to **Investments**, alongside the existing `mutual fund`/`sip`/`zerodha` keywords.

All three are safe as word-boundary-bounded additions: none is a substring of, or contains as a substring, any of the other ~90 keywords already in `CategoryRules.RULES` (checked against the current table as of this plan).

- [ ] **Step 1: Write the failing tests**

Add to `backend/src/test/java/com/finora/util/CategoryRulesTest.java`, before the final closing brace:

```java
    /**
     * Real corpus finding (docs/superpowers/specs/2026-09-01-transaction-categorization-design.md
     * §1): "Pureplay Skin Sciences" is a real D2C skincare/personal-care brand sold via
     * e-commerce, missing from the vocabulary the same way "asspl" and "cinnabon" were.
     */
    @Test
    void suggestCategory_matchesPureplay_skincareEcommerceBrand() {
        assertThat(CategoryRules.suggestCategory("UPI-PUREPLAY SKIN SCIENCES-REF881234")).isEqualTo("Shopping");
    }

    /**
     * Real corpus finding: "PMJJBY" is the Government of India's Pradhan Mantri Jeevan Jyoti
     * Bima Yojana life-insurance scheme, appearing on real statements with a bank-specific
     * "JNS-" narration prefix.
     */
    @Test
    void suggestCategory_matchesPmjjby_governmentInsuranceScheme() {
        assertThat(CategoryRules.suggestCategory("JNS-PMJJBY PREMIUM DEDUCTION")).isEqualTo("Insurance");
    }

    /**
     * Real corpus finding: "NSE MF" is the National Stock Exchange's mutual-fund investment
     * platform -- a real narration uses "MF" rather than the already-seeded "mutual fund"/
     * "mutualfunds" spellings.
     */
    @Test
    void suggestCategory_matchesNseMf_mutualFundPlatformAbbreviation() {
        assertThat(CategoryRules.suggestCategory("NET PAYIN TO NSE MF A/C 9182736")).isEqualTo("Investments");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=CategoryRulesTest`
Expected: the three new tests FAIL, each returning `"Other"` instead of the expected category. All pre-existing tests in the file still PASS.

- [ ] **Step 3: Add the three keywords**

In `backend/src/main/java/com/finora/util/CategoryRules.java`, replace:

```java
        RULES.put("Shopping", List.of("amazon", "flipkart", "myntra", "ajio", "nykaa", "decathlon", "asspl"));
```

with:

```java
        // "pureplay" (Pureplay Skin Sciences, a real D2C skincare/personal-care e-commerce brand)
        // added after checking this project's own real bank-statement corpus (docs/superpowers/
        // specs/2026-09-01-transaction-categorization-design.md §1) -- a real, verified miss, safe
        // as a bare keyword: not a substring of any other keyword or common English/Indian-banking-
        // narration word, so word-boundary matching has nothing plausible to misfire against.
        RULES.put("Shopping", List.of("amazon", "flipkart", "myntra", "ajio", "nykaa", "decathlon", "asspl", "pureplay"));
```

Replace:

```java
        RULES.put("Insurance", List.of("insurance", "lic premium", "policybazaar", "premium payment"));
```

with:

```java
        // "pmjjby" (Pradhan Mantri Jeevan Jyoti Bima Yojana, a real Government of India life-
        // insurance scheme) added after checking this project's own real bank-statement corpus --
        // safe as a bare keyword for the same reason "pureplay" above is: a distinctive acronym,
        // not a substring of any other keyword or common narration word.
        RULES.put("Insurance", List.of("insurance", "lic premium", "policybazaar", "premium payment", "pmjjby"));
```

Replace:

```java
        RULES.put("Investments", List.of("mutual fund", "mutualfunds", "sip", "zerodha", "groww", "upstox", "nps", "ppf", "demat"));
```

with:

```java
        // "nse mf" (National Stock Exchange's mutual-fund investment platform) added after
        // checking this project's own real bank-statement corpus -- kept as the exact two-word
        // phrase seen on the real narration ("NSE MF"), the same choice already made for
        // "cc payment": matching the full phrase rather than a bare "mf" avoids the false-positive
        // risk a 2-letter fragment would carry.
        RULES.put("Investments", List.of("mutual fund", "mutualfunds", "sip", "zerodha", "groww", "upstox", "nps", "ppf", "demat", "nse mf"));
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=CategoryRulesTest`
Expected: PASS — all three new tests, and every pre-existing test in this file.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/util/CategoryRules.java backend/src/test/java/com/finora/util/CategoryRulesTest.java
git commit -m "feat(transactions): add pureplay, pmjjby, and nse mf keywords from corpus review"
```

---

### Task 3: Add "housingcom" — flagged for category confirmation

**Files:**
- Modify: `backend/src/main/java/com/finora/util/CategoryRules.java:33` (Rent)
- Test: `backend/src/test/java/com/finora/util/CategoryRulesTest.java`

**Interfaces:**
- Consumes/produces nothing new — same as Task 2.

**Context:** `Housingcom Gurgaon` is a real corpus finding (design spec §1) — the narration is the real-estate platform Housing.com, printed as one contiguous word on the source statement (no `.`/space between "Housing" and "com", confirmed by the spec's direct quote, so `normalize()` — which only turns non-alphanumerics into spaces — leaves it as a single token `housingcom`, not two).

This one is split into its own task, separate from Task 2, because the category fit is less certain than the other three. Housing.com's own consumer-facing products include rent-payment facilitation (paying a landlord via card/UPI through the platform for a processing fee — a real, common pattern among products like Housing.com, NoBroker, and CRED RentPay in India), which is why **Rent** is the best-supported guess here, next to the existing `landlord`/`housing society` keywords — but unlike `pureplay`/`pmjjby`/`nse mf`, the narration alone doesn't establish *which* Housing.com product this is. Confirm this mapping (or pick a different one) before running Step 3 — this task is written assuming Rent is confirmed; if a reviewer picks a different category, only Step 3's target list changes.

- [ ] **Step 1: Write the failing test**

Add to `backend/src/test/java/com/finora/util/CategoryRulesTest.java`, before the final closing brace:

```java
    /**
     * Real corpus finding (docs/superpowers/specs/2026-09-01-transaction-categorization-design.md
     * §1): "Housingcom Gurgaon" -- Housing.com printed as one contiguous word on the real
     * statement. Mapped to Rent on the assumption this is a rent-payment-facilitator narration
     * (Housing.com/NoBroker/CRED-RentPay-style products let a tenant pay a landlord through the
     * platform for a fee); confirm this category before relying on it (see Task 3's Context).
     */
    @Test
    void suggestCategory_matchesHousingcom_rentPaymentFacilitator() {
        assertThat(CategoryRules.suggestCategory("UPI-HOUSINGCOM GURGAON-REF773311")).isEqualTo("Rent");
    }

    /** Guards the same class of bug the file's other word-boundary tests describe (see
     *  theNewKeywordsAreWordBoundedLikeEveryOther): "housingcom" must not match inside a longer
     *  word it happens to be a prefix of. */
    @Test
    void suggestCategory_housingCommunityIsNotMisclassifiedAsRent() {
        assertThat(CategoryRules.suggestCategory("HOUSINGCOMMUNITY CENTRE FEE")).isNotEqualTo("Rent");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=CategoryRulesTest`
Expected: `suggestCategory_matchesHousingcom_rentPaymentFacilitator` FAILS, returning `"Other"`. `suggestCategory_housingCommunityIsNotMisclassifiedAsRent` already PASSES (nothing matches `"HOUSINGCOMMUNITY"` yet) — that's expected; it's a regression guard for the next step, not a red/green pair on its own.

- [ ] **Step 3: Add the keyword**

In `backend/src/main/java/com/finora/util/CategoryRules.java`, replace:

```java
        RULES.put("Rent", List.of("house rent", "rent paid", "rent payment", "monthly rent", "rent due", "landlord", "housing society", "maintenance chg"));
```

with:

```java
        // "housingcom" (Housing.com, printed as one contiguous word on the real statement) added
        // after checking this project's own real bank-statement corpus (docs/superpowers/specs/
        // 2026-09-01-transaction-categorization-design.md §1) -- mapped to Rent on the assumption
        // this is a rent-payment-facilitator narration (see CategoryRulesTest's Housingcom test
        // comment for the reasoning and its caveat). Safe as a bare keyword: word-boundary matching
        // only matches the exact bounded token "housingcom", never as a prefix inside a longer run
        // like "housingcommunity" (guarded by suggestCategory_housingCommunityIsNotMisclassifiedAsRent).
        RULES.put("Rent", List.of("house rent", "rent paid", "rent payment", "monthly rent", "rent due", "landlord", "housing society", "maintenance chg", "housingcom"));
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=CategoryRulesTest`
Expected: PASS — both new tests, and every pre-existing test in this file.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/util/CategoryRules.java backend/src/test/java/com/finora/util/CategoryRulesTest.java
git commit -m "feat(transactions): add housingcom keyword from corpus review"
```

---

## Self-Review

**1. Spec coverage.** The design spec's §8 item #3 ("Expanded deterministic rules... using the real corpus findings directly") names six real misses: `ASSPL`, `BPPY`/`CC PAYMENT`, `Cinnabon` (already shipped, per the 2026-09-01 plan's own Task 2), `Housingcom Gurgaon`, `Pureplay Skin Sciences`, `JNS-PMJJBY`, and `NET PAYIN TO NSE MF A/C`. Task 2 and Task 3 cover the four not yet shipped. Task 1 covers the spec's own caveat that the bucket breakdown is "a projection... not a measurement" and should be re-checked with current extraction behavior — it's the mechanism for finding whatever comes after these four, without pretending to already know what that is.

**2. Placeholder scan.** No "TBD"/"implement later" text. Task 1 is intentionally open-ended in *outcome* (how many candidates it finds), but every step in it is a concrete, runnable action with real commands — it does not defer any of ITS OWN steps. Tasks 2 and 3 have complete code for every step, no references to an undefined type or method.

**3. Type consistency.** All three tasks touch only `CategoryRules.RULES` (an existing `Map<String, List<String>>`) and `CategoryRulesTest` (existing JUnit 5 + AssertJ conventions already in the file) — no new types, no signature changes anywhere in this plan.

**Assumption flagged for the executor:** Task 3's Rent mapping for `housingcom` is a documented best-guess, not a confirmed fact about what that narration represents — call it out explicitly in code review rather than treating it as settled the way Task 2's three keywords are.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-09-05-categorization-vocabulary-expansion.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
