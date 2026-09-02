# Categorization P2P Detection, Normalization Fix, and Rule Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut Finora's ~83% "Other" categorization rate by closing the three highest-evidence gaps found in the corpus review: person-to-person transfers being asked a merchant-shaped question they can't answer, a normalization layer that's built but disconnected from the path that decides most outcomes, and a handful of real, verified missing keywords.

**Architecture:** All three changes live inside the existing `CategorizationService` waterfall (rules → learned distribution → keyword table → **Other**) as pure, deterministic, additive layers — no new services, no schema migration, no external calls. A new `PersonToPersonTransferDetector` utility (mirroring the existing `CategoryRules`/`PaymentRailTokens` static-utility pattern) is inserted as the last resort before "Other"; the keyword table gains a merchant-canonical-name retry and three corpus-verified keywords.

**Tech Stack:** Java 21 / Spring Boot backend, Maven (`./mvnw`), JUnit 5 + AssertJ + Mockito for tests (existing conventions in `backend/src/test/java/com/finora`).

**Spec:** `docs/superpowers/specs/2026-09-01-transaction-categorization-design.md` — this plan implements Section 8's #1 (P2P/Transfer structural detection), #2 (normalization wiring fix), and #3 (expanded deterministic rules). #5 (merchant review UX) is a separate follow-on plan; #7 (shared corpus) and #8 (LLM fallback) are explicitly out of scope per the spec's own sequencing.

## Global Constraints

- No new taxonomy category is introduced — the P2P detector routes into "Transfer," which already exists in every user's default taxonomy (`AuthService.DEFAULT_CATEGORIES`) and in `CategoryRules.RULES`.
- No schema migration — `Transaction.DecisionSource` has no DB CHECK constraint (verified against `V17__category_rules_decision_source.sql`), so adding `STRUCTURAL_P2P` needs no Flyway migration.
- A user-confirmed category (`categoryManuallySet=true`) is never touched by anything in this plan — these tasks only change how a NEW suggestion is computed, never re-evaluate existing transactions.
- The P2P detector never overrides a rule, learned-pattern, or keyword match — it is wired in strictly as the last resort before "Other," per the spec's "no layer silently overrides a higher-trust layer" principle.
- Every new/changed behavior needs a real, evidence-grounded test — no speculative keyword or pattern goes in without a corresponding test proving it fires (or doesn't) correctly.

---

### Task 1: Retry the keyword fallback against the merchant's canonical name

**Files:**
- Modify: `backend/src/main/java/com/finora/service/CategorizationService.java:166-170` (inside `suggest()`) and `:265-269` (inside the final `suggestReadOnly` overload)
- Test: `backend/src/test/java/com/finora/service/CategorizationServiceTest.java`

**Interfaces:**
- Produces: `private static String suggestCategoryWithMerchantFallback(String description, String merchantName)` — used only inside `CategorizationService`, not exposed elsewhere.

**Context:** `CategoryRules.suggestCategory(description)` — the static keyword table that decides most "Other" outcomes — is currently called only against the raw transaction description. `MerchantNormalizationEngine` already resolves every transaction to a `Merchant` with a `canonicalName` (derived once, the first time this merchant was ever seen, via `CategoryRules.extractMerchant`, and updatable later through the Merchant Review Center's rename/merge tooling) — but that canonical name currently reaches only `field=MERCHANT`-scoped `CategoryRule` rows, of which the seeded global set has zero. This task makes the keyword table itself retry against the canonical name whenever the raw description alone doesn't match, so a merchant's identity — once correctly identified anywhere — generalizes to every narration variant that resolves to it, not just narrations whose own text happens to spell out a recognizable brand.

- [ ] **Step 1: Write the failing tests**

Add to `backend/src/test/java/com/finora/service/CategorizationServiceTest.java`, alongside the existing `merchantWithId` helper (keep that helper as-is; add this one alongside it):

```java
    private Merchant merchantWith(UUID id, String canonicalName) {
        Merchant m = new Merchant();
        ReflectionTestUtils.setField(m, "id", id);
        m.setCanonicalName(canonicalName);
        return m;
    }
```

Add these two test methods:

```java
    @Test
    void suggest_fallsBackToMerchantCanonicalName_whenRawDescriptionHasNoKeyword() {
        UUID merchantId = UUID.randomUUID();
        // Simulates a merchant whose canonical name was correctly identified from an EARLIER
        // transaction's narration (or an admin/user rename) -- this transaction's own raw text
        // carries no recognizable brand token at all.
        Merchant merchant = merchantWith(merchantId, "Swiggy Bangalore");
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchant);
        when(learningRepository.findByUserIdAndMerchantId(userId, merchantId)).thenReturn(List.of());

        var suggestion = categorizationService.suggest(userId, "UPI/REF88213764/SETTLEMENT");

        assertThat(suggestion.category()).isEqualTo("Dining");
        assertThat(suggestion.source()).isEqualTo("rule");
        assertThat(suggestion.decisionSource()).isEqualTo(Transaction.DecisionSource.KEYWORD_MATCH);
    }

    @Test
    void suggestReadOnly_fallsBackToMerchantCanonicalName_whenRawDescriptionHasNoKeyword() {
        UUID merchantId = UUID.randomUUID();
        Merchant merchant = merchantWith(merchantId, "Swiggy Bangalore");
        when(merchantNormalizationEngine.resolveReadOnly(eq(userId), anyString()))
                .thenReturn(Optional.of(merchant));
        when(learningRepository.findByUserIdAndMerchantId(userId, merchantId)).thenReturn(List.of());

        var suggestion = categorizationService.suggestReadOnly(userId, "UPI/REF88213764/SETTLEMENT");

        assertThat(suggestion.category()).isEqualTo("Dining");
        assertThat(suggestion.decisionSource()).isEqualTo(Transaction.DecisionSource.KEYWORD_MATCH);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=CategorizationServiceTest`
Expected: both new tests FAIL — the suggestion falls through to `"Other"` / `MERCHANT_DEFAULT`, because `"UPI/REF88213764/SETTLEMENT"` contains no keyword and the merchant's canonical name is never consulted today.

- [ ] **Step 3: Add the helper and wire it into both call sites**

In `backend/src/main/java/com/finora/service/CategorizationService.java`, add this method immediately after the final `suggestReadOnly(List<CategoryRule> rules, ...)` overload ends (i.e. right before the `/** Maps the persisted-through-review categorySource string...` comment for `decisionSourceFor`):

```java
    /**
     * Retries the static keyword table against the resolved merchant's canonical name when the
     * raw description alone doesn't match anything.
     *
     * <p>{@code merchantName} generalizes across every raw narration variant that has ever
     * resolved to this merchant (exact alias match or first-significant-token match, see
     * {@link MerchantNormalizationEngine}) -- not just the current transaction's own text. A
     * merchant's canonical name is set once, from whichever description first created it (or from
     * an admin/user rename via the Merchant Review Center), so fixing it once retroactively helps
     * every future transaction for that merchant hit the keyword table, even ones whose own raw
     * text carries no recognizable brand token at all.
     *
     * <p>Never used to REPLACE the raw-description attempt, only to extend it: a raw match always
     * wins first, so this cannot change the category for any narration that already matched on its
     * own text.
     */
    private static String suggestCategoryWithMerchantFallback(String description, String merchantName) {
        String ruleCat = CategoryRules.suggestCategory(description);
        if (!ruleCat.equals("Other")) return ruleCat;
        if (merchantName == null || merchantName.isBlank()) return ruleCat;
        return CategoryRules.suggestCategory(merchantName);
    }
```

Then, inside `suggest(UUID userId, String description, BigDecimal amount, String accountType)`, replace:

```java
        String ruleCat = CategoryRules.suggestCategory(description);
        boolean matchedKeyword = !ruleCat.equals("Other");
        return new Suggestion(ruleCat, matchedKeyword ? "rule" : "default", merchant.getId(),
                matchedKeyword ? Transaction.DecisionSource.KEYWORD_MATCH : Transaction.DecisionSource.MERCHANT_DEFAULT, null,
                matchedKeyword ? ConfidenceEngine.INITIAL_RULE_CONFIDENCE : ConfidenceEngine.INITIAL_DEFAULT_CONFIDENCE);
    }
```

with:

```java
        String ruleCat = suggestCategoryWithMerchantFallback(description, merchant.getCanonicalName());
        boolean matchedKeyword = !ruleCat.equals("Other");
        return new Suggestion(ruleCat, matchedKeyword ? "rule" : "default", merchant.getId(),
                matchedKeyword ? Transaction.DecisionSource.KEYWORD_MATCH : Transaction.DecisionSource.MERCHANT_DEFAULT, null,
                matchedKeyword ? ConfidenceEngine.INITIAL_RULE_CONFIDENCE : ConfidenceEngine.INITIAL_DEFAULT_CONFIDENCE);
    }
```

And inside the final `suggestReadOnly(List<CategoryRule> rules, UUID userId, String description, BigDecimal amount, String accountType, MerchantIndex merchantIndex)` overload, replace:

```java
        String ruleCat = CategoryRules.suggestCategory(description);
        boolean matchedKeyword = !ruleCat.equals("Other");
        return new Suggestion(ruleCat, matchedKeyword ? "rule" : "default", merchantId,
                matchedKeyword ? Transaction.DecisionSource.KEYWORD_MATCH : Transaction.DecisionSource.MERCHANT_DEFAULT, null,
                matchedKeyword ? ConfidenceEngine.INITIAL_RULE_CONFIDENCE : ConfidenceEngine.INITIAL_DEFAULT_CONFIDENCE);
    }
```

with:

```java
        String ruleCat = suggestCategoryWithMerchantFallback(description, merchantName);
        boolean matchedKeyword = !ruleCat.equals("Other");
        return new Suggestion(ruleCat, matchedKeyword ? "rule" : "default", merchantId,
                matchedKeyword ? Transaction.DecisionSource.KEYWORD_MATCH : Transaction.DecisionSource.MERCHANT_DEFAULT, null,
                matchedKeyword ? ConfidenceEngine.INITIAL_RULE_CONFIDENCE : ConfidenceEngine.INITIAL_DEFAULT_CONFIDENCE);
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=CategorizationServiceTest`
Expected: PASS — both new tests, and every pre-existing test in this file (the raw-description path is unchanged for any description that already matched).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/service/CategorizationService.java backend/src/test/java/com/finora/service/CategorizationServiceTest.java
git commit -m "fix(imports): retry keyword categorization against merchant canonical name"
```

---

### Task 2: Expand the keyword table with three corpus-verified misses

**Files:**
- Modify: `backend/src/main/java/com/finora/util/CategoryRules.java:32-41`
- Test: `backend/src/test/java/com/finora/util/CategoryRulesTest.java`

**Interfaces:**
- Consumes/produces nothing new — pure data addition to the existing `CategoryRules.RULES` map.

**Context:** Three real, verified misses from the corpus root-cause review (`docs/superpowers/specs/2026-09-01-transaction-categorization-design.md` §1): `ASSPL` (how Amazon Seller Services actually appears on real Indian card statements — not "amazon"), `CC PAYMENT` (a real biller-abbreviation variant of the already-seeded "credit card payment"/"card bill payment" phrases), and `Cinnabon` (a real dining-chain brand absent from the vocabulary). All three are safe, word-boundary-bounded additions with no substring-collision risk against any existing keyword.

- [ ] **Step 1: Write the failing tests**

Add to `backend/src/test/java/com/finora/util/CategoryRulesTest.java`:

```java
    /**
     * Real corpus finding (docs/superpowers/specs/2026-09-01-transaction-categorization-design.md
     * §1): "ASSPL" is how Amazon Seller Services actually appears on real Indian card statements
     * -- never the word "amazon" itself.
     */
    @Test
    void suggestCategory_matchesAsspl_amazonSellerServicesAbbreviation() {
        assertThat(CategoryRules.suggestCategory("ASSPL PAYTM 4471829")).isEqualTo("Shopping");
    }

    /**
     * Real corpus finding: a BharatBillPay credit-card-bill narration abbreviated to "CC PAYMENT"
     * -- a near-miss of the already-seeded "credit card payment"/"card bill payment" phrases that
     * the existing CONTAINS-style keywords don't cover.
     */
    @Test
    void suggestCategory_matchesCcPayment_billerAbbreviation() {
        assertThat(CategoryRules.suggestCategory("BPPY CC PAYMENT REF882134")).isEqualTo("Transfer");
    }

    @Test
    void suggestCategory_matchesCinnabon() {
        assertThat(CategoryRules.suggestCategory("UPI-Cinnabon CP-REF7719923")).isEqualTo("Dining");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=CategoryRulesTest`
Expected: all three new tests FAIL, returning `"Other"` instead of the expected category.

- [ ] **Step 3: Add the three keywords**

In `backend/src/main/java/com/finora/util/CategoryRules.java`, replace:

```java
        RULES.put("Shopping", List.of("amazon", "flipkart", "myntra", "ajio", "nykaa", "decathlon"));
        RULES.put("Dining", List.of("swiggy", "zomato", "restaurant", "cafe", "starbucks", "dominos", "mcdonald", "kfc"));
        RULES.put("Transport", List.of("uber", "ola", "rapido", "irctc", "petrol", "fuel", "metro", "fastag", "parking"));
        RULES.put("Utilities", List.of("electricity", "power bill", "water bill", "gas bill", "broadband", "airtel", "jio", "recharge"));
```

with:

```java
        // "asspl" (Amazon Seller Services' actual card-statement abbreviation) and "cinnabon"
        // added after checking this project's own real bank-statement corpus (docs/superpowers/
        // specs/2026-09-01-transaction-categorization-design.md §1) -- both real, verified misses,
        // safe as bare keywords: neither is a substring of any other keyword or common English/
        // Indian-banking-narration word, so word-boundary matching has nothing plausible to
        // misfire against.
        RULES.put("Shopping", List.of("amazon", "flipkart", "myntra", "ajio", "nykaa", "decathlon", "asspl"));
        RULES.put("Dining", List.of("swiggy", "zomato", "restaurant", "cafe", "starbucks", "dominos", "mcdonald", "kfc", "cinnabon"));
        RULES.put("Transport", List.of("uber", "ola", "rapido", "irctc", "petrol", "fuel", "metro", "fastag", "parking"));
        RULES.put("Utilities", List.of("electricity", "power bill", "water bill", "gas bill", "broadband", "airtel", "jio", "recharge"));
```

And replace:

```java
        RULES.put("Transfer", List.of("credit card payment", "card bill payment", "autopay", "neft to", "imps to", "billdesk"));
```

with:

```java
        // "cc payment" added after checking this project's own real bank-statement corpus (see
        // Shopping/Dining comment above) -- a real BharatBillPay narration ("BPPY CC PAYMENT")
        // abbreviated past what "credit card payment"/"card bill payment" already catch. Safe as a
        // two-word phrase: word-boundary matching means "cc" alone is never checked in isolation.
        RULES.put("Transfer", List.of("credit card payment", "card bill payment", "cc payment", "autopay", "neft to", "imps to", "billdesk"));
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=CategoryRulesTest`
Expected: PASS — all three new tests, and every pre-existing test in this file.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/util/CategoryRules.java backend/src/test/java/com/finora/util/CategoryRulesTest.java
git commit -m "feat(imports): add ASSPL, cc payment, and Cinnabon keywords from corpus review"
```

---

### Task 3: Structural person-to-person transfer detection

**Files:**
- Create: `backend/src/main/java/com/finora/util/PersonToPersonTransferDetector.java`
- Test: `backend/src/test/java/com/finora/util/PersonToPersonTransferDetectorTest.java`
- Modify: `backend/src/main/java/com/finora/entity/Transaction.java:56` (add `STRUCTURAL_P2P` to `DecisionSource`)
- Modify: `backend/src/main/java/com/finora/service/CategorizationService.java` (wire the detector into `suggest()` and the final `suggestReadOnly` overload, on top of Task 1's change; add a case to `decisionSourceFor`)
- Modify: `backend/src/main/java/com/finora/dto/ImportDto.java:47,745` (documentation comments only — the known `categorySource` string values)
- Test: `backend/src/test/java/com/finora/service/CategorizationServiceTest.java`

**Interfaces:**
- Consumes: Task 1's `suggestCategoryWithMerchantFallback` (already wired into both call sites).
- Produces: `public static boolean PersonToPersonTransferDetector.isNamedIndividualTransfer(String description)` — used only by `CategorizationService`.

**Context:** The spec's root-cause measurement found that 42.2% of ALL "Other" transactions — the single largest bucket by a wide margin — are UPI/NEFT/IMPS/RTGS transfers naming an individual, not a business. No merchant lookup can ever resolve these correctly, because there is no merchant; the correct answer is a real, already-existing category ("Transfer"), reached by recognizing the *shape* of the narration, not its content. The detection logic below (business-signal gate, name-token shape, transfer-marker requirement) is a direct port of a heuristic already validated against the corpus's full 1,400-transaction "Other" population, hand-spot-checked for an 8–12% error rate. It is wired in as the LAST resort in the waterfall — after rules, learned patterns, and the keyword table (including Task 1's merchant-name retry) all miss — so it can never override a higher-trust signal.

- [ ] **Step 1: Write the failing tests for the detector itself**

Create `backend/src/test/java/com/finora/util/PersonToPersonTransferDetectorTest.java`:

```java
package com.finora.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersonToPersonTransferDetectorTest {

    @Test
    void detectsUpiTransferToNamedIndividual() {
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-RAJESH KUMAR-sampleuser@ybl-REF881234"))
                .isTrue();
    }

    @Test
    void detectsNeftTransferWithASingleLetterInitial() {
        // A real, common Indian-naming-convention shape ("R BAGAVATHI SHANKAR") -- a single-letter
        // initial does not by itself disqualify the segment as a name.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "NEFT-R BAGAVATHI SHANKAR-HDFC0XXXXXX-TRANSFER"))
                .isTrue();
    }

    @Test
    void doesNotFireWithoutATransferMarker() {
        // A person-shaped word with no UPI/NEFT/IMPS/RTGS context is too weak a signal on its
        // own -- it could just as easily be part of a merchant's trade name. Fails the gate
        // before any name-shape check even runs.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "RAJESH KUMAR ENTERPRISES INVOICE 4471"))
                .isFalse();
    }

    @Test
    void excludesBusinessSuffixToken() {
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-SHARMA TRADERS-sampleuser@ybl-REF881234"))
                .isFalse();
    }

    @Test
    void globalBusinessSignalExcludesEvenWhenAnotherSegmentLooksLikeAName() {
        // A business signal ANYWHERE in the narration disqualifies the whole line, even if an
        // unrelated segment elsewhere happens to look name-shaped -- a business's legal name can
        // itself be built from a person's name ("Rajesh Kumar Sharma Traders Pvt Ltd").
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-RAJESH KUMAR-SHARMA TRADERS PVT LTD-REF881234"))
                .isFalse();
    }

    @Test
    void excludesMerchantQrVpaHandle() {
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-PAYTMQR6NU5UR@PTYS-REF992817"))
                .isFalse();
    }

    @Test
    void excludesPspBrandTokenStandingAloneAsASegment() {
        // This corpus's narration grammar sometimes repeats a PSP brand as its own segment right
        // before its VPA -- a lone brand token must never be misread as a first name.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "UPI-PHONEPE-PHONEPE.PAYMENTS@ICICI-REF102938"))
                .isFalse();
    }

    @Test
    void stripsOwnBankPrefixBeforeCheckingForABusinessSignal() {
        // "HDFC BANK LIMITED" is the statement-owner's own institution name, not the counterparty
        // -- without stripping it, "BANK"/"LIMITED" would spuriously read as a business signal for
        // a line whose actual payee is a person.
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(
                "HDFC BANK LIMITED UPI-SUNITA RAO-sampleuser2@oksbi-REF773821"))
                .isTrue();
    }

    @Test
    void handlesNullAndBlankSafely() {
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(null)).isFalse();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer("")).isFalse();
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer("   ")).isFalse();
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=PersonToPersonTransferDetectorTest`
Expected: FAIL to compile — `PersonToPersonTransferDetector` does not exist yet.

- [ ] **Step 3: Create the detector**

Create `backend/src/main/java/com/finora/util/PersonToPersonTransferDetector.java`:

```java
package com.finora.util;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Detects a person-to-person transfer narration structurally -- no merchant lookup, no keyword
 * table, just the shape of the text -- for the single largest bucket found in a real-corpus
 * measurement of Finora's "Other" categorization outcomes (see
 * docs/superpowers/specs/2026-09-01-transaction-categorization-design.md §1): 42.2% of every
 * "Other" transaction is a UPI/NEFT/IMPS/RTGS transfer naming an individual, not a business. No
 * keyword list, no merchant corpus, no LLM can ever categorize these correctly by merchant lookup,
 * because there is no merchant -- this is a taxonomy answer ("Transfer"), not a smarter match.
 *
 * <p>The detection logic below (business-signal gate, name-token shape, transfer-marker gate) is
 * a direct port of a heuristic validated against the full 1,400-transaction real "Other"
 * population from Finora's bank-statement corpus, spot-checked by hand against 110 items (~8% of
 * the population) for an honest error bound of roughly 8-12%, split across two disclosed,
 * partially-offsetting biases: a lone first name is conservatively NOT counted as a person
 * (undercounts P2P) and a multi-word brand name with no recognizable business-descriptor word can
 * occasionally look like a person (overcounts P2P). See the spec's §1 "Validated at scale"
 * section for the full methodology.
 *
 * <p>Deliberately conservative: only fires when an explicit transfer-protocol marker (UPI/NEFT/
 * IMPS/RTGS) is present. A bare person-shaped word with no transfer context is too weak a signal
 * -- it could just as easily be part of a merchant's trade name -- so this never touches a
 * narration without one of those markers.
 */
public final class PersonToPersonTransferDetector {

    private PersonToPersonTransferDetector() {}

    // Interbank/UPI transfer-protocol markers -- the ONLY context this detector will ever fire in.
    // Deliberately narrower than PaymentRailTokens.RAIL_TOKENS (which also includes ATM/POS/CHQ,
    // none of which imply a person-to-person transfer -- an ATM withdrawal is never a P2P
    // transfer, so reusing that broader set here would be wrong, not just imprecise).
    private static final Pattern TRANSFER_MARKER = Pattern.compile("\\b(UPI|NEFT|IMPS|RTGS)\\b");

    // A statement's own bank sometimes prefixes its own institution name onto the line (e.g.
    // "HDFC BANK LIMITED UPI-<person name>-..."). Left in, "BANK"/"LIMITED" spuriously read as a
    // business signal for a line whose actual counterparty is a person. Stripped before any other
    // check runs.
    private static final Pattern OWN_BANK_PREFIX = Pattern.compile(
            "^[A-Za-z][A-Za-z]*\\s+BANK\\s+(LIMITED|LTD)\\.?\\s*", Pattern.CASE_INSENSITIVE);

    // Business-entity suffix/trade-name words -- checked GLOBALLY across the whole narration (not
    // per-segment), because a genuine business name can span multiple delimiter-separated segments
    // ("SHARMA TRADERS-PVT LTD") while still being one entity, not a person plus an unrelated
    // coincidence sitting in a neighbouring segment.
    private static final Set<String> BUSINESS_SUFFIX_TOKENS = Set.of(
            "PVT", "LTD", "LLP", "LIMITED", "PRIVATE", "ENTERPRISES", "STORES", "STORE",
            "SERVICES", "SERVICE", "SOLUTIONS", "TRADERS", "ASSOCIATES", "AGENCIES",
            "INDUSTRIES", "EXPORTS", "IMPORTS", "GROUP", "FOUNDATION", "TRUST", "SOCIETY",
            "HOSPITAL", "CLINIC", "PHARMACY", "SCHOOL", "COLLEGE", "UNIVERSITY", "FINANCE",
            "INSURANCE", "FUND", "COMPANY", "CORP", "CORPORATION", "MART", "SHOP",
            "MANAGEMENT", "ASSET", "NSE", "BSE", "SCHEME", "NGO", "PROJECTS", "SYSTEMS",
            "TECHNOLOGIES", "TECH", "LABS", "CONSULTANCY", "CONSULTING", "VENTURES",
            "CAPITAL", "HOLDINGS", "AND", "CO", "HOUSE", "FOODS", "FOOD", "KITCHEN", "CAFE",
            "HOTEL", "RESTAURANT", "SWEETS", "BAKERY", "DAIRY", "GENERAL", "MEDICAL",
            "WORLD", "ZONE", "CENTRE", "CENTER", "POINT", "CORNER", "PLAZA", "MALL",
            "BAZAAR", "BAZAR", "COLLECTIONS", "BOUTIQUE", "JEWELLERS", "ELECTRONICS",
            "MOBILES", "MOTORS", "AUTOMOBILES", "CONSTRUCTION", "BUILDERS", "PROPERTIES",
            "REALTY", "INFRA", "INFRASTRUCTURE", "ACADEMY", "INSTITUTE", "COACHING",
            "LAUNDRY", "SALON", "SPA", "GYM", "FITNESS", "STUDIO", "PRINTERS", "PRINTING",
            "STATIONERY", "HARDWARE", "ELECTRICALS", "TRAVELS", "TOURS", "CARGO",
            "LOGISTICS", "TRANSPORT", "COURIER", "BANK", "NBFC", "AMC", "MUTUAL"
    );

    // Banking/protocol/channel abbreviations and routine transaction-flow boilerplate -- never
    // part of a person's name, but expected in EVERY narration (every UPI transfer literally
    // contains "UPI"), so these are excluded only PER-WORD inside looksLikePersonName, never as a
    // global gate the way BUSINESS_SUFFIX_TOKENS is.
    private static final Set<String> PROTOCOL_AND_BOILERPLATE_TOKENS = Set.of(
            "UPI", "NEFT", "IMPS", "RTGS", "ATM", "POS", "ACH", "NACH", "ECS", "MOB", "INR",
            "INDIA", "PAYMENT", "PAYMENTS", "PYMT", "PYMNT", "TRANSFER", "TRF", "TXN",
            "REF", "RRN", "CHG", "CHGS", "BANKING", "BR", "BRANCH", "DEBIT", "CREDIT",
            "CR", "DR", "NO", "ACC", "ACCT", "THE", "FOR", "TO", "FROM", "WITH", "VIA",
            "PAID", "VALUE", "DT", "DATE", "CHQ", "IB", "INTENT", "ID", "PHONE"
    );

    // PSP/wallet/fintech brand tokens -- channels, not people, but likewise expected boilerplate
    // rather than a global business signal (a PERSON'S transfer routed via PhonePe still names
    // "PHONEPE" as a segment of its own).
    private static final Set<String> PSP_BRAND_TOKENS = Set.of(
            "PHONEPE", "RAZORPAY", "BHARATPE", "PAYTM", "PAYTMQR", "GPAY", "GOOGLEPAY",
            "CRED", "MOBIKWIK", "FREECHARGE", "WHATSAPP", "AMAZONPAY"
    );

    // The union of every token that can never be part of a person's given/family name -- used to
    // disqualify individual words when testing whether ONE segment looks like a name.
    private static final Set<String> NON_NAME_TOKENS = new HashSet<>();
    static {
        NON_NAME_TOKENS.addAll(BUSINESS_SUFFIX_TOKENS);
        NON_NAME_TOKENS.addAll(PROTOCOL_AND_BOILERPLATE_TOKENS);
        NON_NAME_TOKENS.addAll(PSP_BRAND_TOKENS);
    }

    // A VPA-shaped handle whose local part contains "qr" -- a merchant-QR handle (e.g.
    // "paytmqr6nu5ur@ptys"), never a person's own UPI handle. A structural business signal
    // independent of the word-token checks above.
    private static final Pattern VPA_BUSINESS_QR = Pattern.compile(
            "(?i)[a-z0-9._-]*qr[a-z0-9._-]*@[a-z0-9]+");

    private static final Pattern NAME_TOKEN = Pattern.compile("[A-Za-z]{2,15}");

    /**
     * True when {@code description} structurally looks like a UPI/NEFT/IMPS/RTGS transfer to a
     * named individual rather than a business -- see this class's own doc comment for the
     * evidence and the conservative gate this stays behind.
     */
    public static boolean isNamedIndividualTransfer(String description) {
        if (description == null || description.isBlank()) return false;
        if (!TRANSFER_MARKER.matcher(description.toUpperCase()).find()) return false;
        if (VPA_BUSINESS_QR.matcher(description).find()) return false;

        String body = OWN_BANK_PREFIX.matcher(description).replaceFirst("");
        if (containsBusinessSignal(body.toUpperCase())) return false;

        for (String segment : body.split("[\\-/_]+")) {
            if (looksLikePersonName(segment.trim())) return true;
        }
        return false;
    }

    /** True when a {@link #BUSINESS_SUFFIX_TOKENS} word appears anywhere in the text, as a whole
     *  word -- checked across the FULL narration, not per-segment (see that set's own comment). */
    private static boolean containsBusinessSignal(String textUpper) {
        for (String token : BUSINESS_SUFFIX_TOKENS) {
            if (Pattern.compile("\\b" + Pattern.quote(token) + "\\b").matcher(textUpper).find()) {
                return true;
            }
        }
        return false;
    }

    /** 2-4 real words (2-15 letters each), each not a business/protocol/brand token, with any
     *  additional single-letter tokens treated as name initials (a real, common Indian-naming
     *  convention, e.g. "R BAGAVATHI") rather than disqualifying the segment. */
    private static boolean looksLikePersonName(String segment) {
        if (segment.isEmpty()) return false;
        String[] words = segment.split("\\s+");
        int realWordCount = 0;
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (w.length() == 1 && Character.isLetter(w.charAt(0))) continue; // initial
            if (!NAME_TOKEN.matcher(w).matches()) return false;
            if (NON_NAME_TOKENS.contains(w.toUpperCase())) return false;
            realWordCount++;
        }
        return realWordCount >= 2 && realWordCount <= 4;
    }
}
```

- [ ] **Step 4: Run the detector tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=PersonToPersonTransferDetectorTest`
Expected: PASS — all 9 tests.

- [ ] **Step 5: Add `STRUCTURAL_P2P` to `Transaction.DecisionSource`**

In `backend/src/main/java/com/finora/entity/Transaction.java`, replace:

```java
    // Which mechanism produced this transaction's category -- explainability, not a decision
    // input (see docs/rule-engine-relationship-engine-eds.md §3.2). KEYWORD_MATCH is the static
    // CategoryRules table (util package); GLOBAL_RULE/USER_RULE are the new category_rules DB
    // table (RuleEngineService); MERCHANT_DEFAULT is "nothing matched, fell through to Other".
    public enum DecisionSource { GLOBAL_RULE, USER_RULE, LEARNED_PATTERN, KEYWORD_MATCH, MERCHANT_DEFAULT, MANUAL, FILE_PROVIDED }
```

with:

```java
    // Which mechanism produced this transaction's category -- explainability, not a decision
    // input (see docs/rule-engine-relationship-engine-eds.md §3.2). KEYWORD_MATCH is the static
    // CategoryRules table (util package); GLOBAL_RULE/USER_RULE are the new category_rules DB
    // table (RuleEngineService); MERCHANT_DEFAULT is "nothing matched, fell through to Other".
    // STRUCTURAL_P2P is PersonToPersonTransferDetector -- a person-to-person transfer recognized
    // by narration SHAPE (no merchant involved at all), the last resort tried before
    // MERCHANT_DEFAULT. No DB CHECK constrains this column (VARCHAR(20), V17), so this value
    // needed no migration.
    public enum DecisionSource { GLOBAL_RULE, USER_RULE, LEARNED_PATTERN, KEYWORD_MATCH, MERCHANT_DEFAULT, MANUAL, FILE_PROVIDED, STRUCTURAL_P2P }
```

- [ ] **Step 6: Write the failing `CategorizationService` integration tests**

Add to `backend/src/test/java/com/finora/service/CategorizationServiceTest.java`:

```java
    @Test
    void suggest_detectsStructuralP2pTransfer_whenNothingElseMatches() {
        UUID merchantId = UUID.randomUUID();
        Merchant merchant = merchantWith(merchantId, "Unknown Merchant");
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchant);
        when(learningRepository.findByUserIdAndMerchantId(userId, merchantId)).thenReturn(List.of());

        var suggestion = categorizationService.suggest(userId, "UPI-RAJESH KUMAR-sampleuser@ybl-REF881234");

        assertThat(suggestion.category()).isEqualTo("Transfer");
        assertThat(suggestion.source()).isEqualTo("structural_p2p");
        assertThat(suggestion.decisionSource()).isEqualTo(Transaction.DecisionSource.STRUCTURAL_P2P);
    }

    @Test
    void suggest_keywordMatchStillWinsOverStructuralP2pDetection() {
        // "UBER" is a Transport keyword; even though this narration also carries a UPI marker,
        // the keyword table -- which runs first -- must still win. The P2P detector is wired in
        // strictly as the last resort, never as an override.
        UUID merchantId = UUID.randomUUID();
        Merchant merchant = merchantWith(merchantId, "Uber");
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchant);
        when(learningRepository.findByUserIdAndMerchantId(userId, merchantId)).thenReturn(List.of());

        var suggestion = categorizationService.suggest(userId, "UPI-UBER TRIP-sampleuser@ybl-REF881234");

        assertThat(suggestion.category()).isEqualTo("Transport");
        assertThat(suggestion.decisionSource()).isEqualTo(Transaction.DecisionSource.KEYWORD_MATCH);
    }

    @Test
    void decisionSourceFor_mapsStructuralP2pString() {
        assertThat(CategorizationService.decisionSourceFor("structural_p2p"))
                .isEqualTo(Transaction.DecisionSource.STRUCTURAL_P2P);
    }
```

- [ ] **Step 7: Run the tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=CategorizationServiceTest`
Expected: `suggest_detectsStructuralP2pTransfer_whenNothingElseMatches` FAILs with category `"Other"` instead of `"Transfer"`; `decisionSourceFor_mapsStructuralP2pString` FAILs with `MERCHANT_DEFAULT` instead of `STRUCTURAL_P2P` (falls through the `default` case); `suggest_keywordMatchStillWinsOverStructuralP2pDetection` should already PASS (nothing changes the keyword path yet) — confirm it does, since it's the regression guard for the next step.

- [ ] **Step 8: Wire the detector into `CategorizationService`**

In `backend/src/main/java/com/finora/service/CategorizationService.java`, add the import alongside the existing `com.finora.util.CategoryRules` import:

```java
import com.finora.util.PersonToPersonTransferDetector;
```

Inside `suggest(UUID userId, String description, BigDecimal amount, String accountType)`, replace the block Task 1 left in place:

```java
        String ruleCat = suggestCategoryWithMerchantFallback(description, merchant.getCanonicalName());
        boolean matchedKeyword = !ruleCat.equals("Other");
        return new Suggestion(ruleCat, matchedKeyword ? "rule" : "default", merchant.getId(),
                matchedKeyword ? Transaction.DecisionSource.KEYWORD_MATCH : Transaction.DecisionSource.MERCHANT_DEFAULT, null,
                matchedKeyword ? ConfidenceEngine.INITIAL_RULE_CONFIDENCE : ConfidenceEngine.INITIAL_DEFAULT_CONFIDENCE);
    }
```

with:

```java
        String ruleCat = suggestCategoryWithMerchantFallback(description, merchant.getCanonicalName());
        if (!ruleCat.equals("Other")) {
            return new Suggestion(ruleCat, "rule", merchant.getId(), Transaction.DecisionSource.KEYWORD_MATCH, null,
                    ConfidenceEngine.INITIAL_RULE_CONFIDENCE);
        }
        if (PersonToPersonTransferDetector.isNamedIndividualTransfer(description)) {
            return new Suggestion("Transfer", "structural_p2p", merchant.getId(),
                    Transaction.DecisionSource.STRUCTURAL_P2P, null, ConfidenceEngine.INITIAL_RULE_CONFIDENCE);
        }
        return new Suggestion("Other", "default", merchant.getId(), Transaction.DecisionSource.MERCHANT_DEFAULT, null,
                ConfidenceEngine.INITIAL_DEFAULT_CONFIDENCE);
    }
```

And inside the final `suggestReadOnly(List<CategoryRule> rules, ...)` overload, replace:

```java
        String ruleCat = suggestCategoryWithMerchantFallback(description, merchantName);
        boolean matchedKeyword = !ruleCat.equals("Other");
        return new Suggestion(ruleCat, matchedKeyword ? "rule" : "default", merchantId,
                matchedKeyword ? Transaction.DecisionSource.KEYWORD_MATCH : Transaction.DecisionSource.MERCHANT_DEFAULT, null,
                matchedKeyword ? ConfidenceEngine.INITIAL_RULE_CONFIDENCE : ConfidenceEngine.INITIAL_DEFAULT_CONFIDENCE);
    }
```

with:

```java
        String ruleCat = suggestCategoryWithMerchantFallback(description, merchantName);
        if (!ruleCat.equals("Other")) {
            return new Suggestion(ruleCat, "rule", merchantId, Transaction.DecisionSource.KEYWORD_MATCH, null,
                    ConfidenceEngine.INITIAL_RULE_CONFIDENCE);
        }
        if (PersonToPersonTransferDetector.isNamedIndividualTransfer(description)) {
            return new Suggestion("Transfer", "structural_p2p", merchantId,
                    Transaction.DecisionSource.STRUCTURAL_P2P, null, ConfidenceEngine.INITIAL_RULE_CONFIDENCE);
        }
        return new Suggestion("Other", "default", merchantId, Transaction.DecisionSource.MERCHANT_DEFAULT, null,
                ConfidenceEngine.INITIAL_DEFAULT_CONFIDENCE);
    }
```

Finally, in the same file, add a case to `decisionSourceFor`. Replace:

```java
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
```

with:

```java
    public static Transaction.DecisionSource decisionSourceFor(String categorySource) {
        if (categorySource == null) return Transaction.DecisionSource.MERCHANT_DEFAULT;
        return switch (categorySource) {
            case "user_rule" -> Transaction.DecisionSource.USER_RULE;
            case "global_rule" -> Transaction.DecisionSource.GLOBAL_RULE;
            case "learned" -> Transaction.DecisionSource.LEARNED_PATTERN;
            case "rule" -> Transaction.DecisionSource.KEYWORD_MATCH;
            case "file" -> Transaction.DecisionSource.FILE_PROVIDED;
            case "structural_p2p" -> Transaction.DecisionSource.STRUCTURAL_P2P;
            default -> Transaction.DecisionSource.MERCHANT_DEFAULT;
        };
    }
```

This mapping matters beyond `suggest()`'s own return value: `GmailReviewService.java:241` and `ImportRuleLearningService.java:52` both re-derive a transaction's decision source from the persisted `categorySource` string at confirm time — without this case, a P2P-detected suggestion would silently round-trip back to `MERCHANT_DEFAULT` the moment it passes through statement-import staging, losing its provenance and, for `GmailReviewService`, being miscounted as an unresolved default guess.

- [ ] **Step 9: Update the stale waterfall-order doc comment on `suggest()`**

In `backend/src/main/java/com/finora/service/CategorizationService.java`, replace:

```java
    /** Rule engine (user rules, then global rules) > learned distribution (real evidence) >
     *  keyword rules > "Other". See docs/rule-engine-relationship-engine-eds.md §4. */
    public Suggestion suggest(UUID userId, String description) {
```

with:

```java
    /** Rule engine (user rules, then global rules) > learned distribution (real evidence) >
     *  keyword rules (including a merchant-canonical-name retry) > structural person-to-person
     *  transfer detection > "Other". See docs/rule-engine-relationship-engine-eds.md §4 and
     *  docs/superpowers/specs/2026-09-01-transaction-categorization-design.md §2. */
    public Suggestion suggest(UUID userId, String description) {
```

- [ ] **Step 10: Update the `categorySource` documentation comments in `ImportDto.java`**

In `backend/src/main/java/com/finora/dto/ImportDto.java`, replace (line 47):

```java
            String categorySource,   // "learned" | "rule" | "user_rule" | "global_rule" | "default" | "file"
```

with:

```java
            String categorySource,   // "learned" | "rule" | "user_rule" | "global_rule" | "structural_p2p" | "default" | "file"
```

And replace (line 745):

```java
            String categorySource,   // "learned" | "rule" | "user_rule" | "global_rule" | "default" | "file" — carried from staging
```

with:

```java
            String categorySource,   // "learned" | "rule" | "user_rule" | "global_rule" | "structural_p2p" | "default" | "file" — carried from staging
```

- [ ] **Step 11: Run the full test suite to verify everything passes**

Run: `cd backend && ./mvnw test -Dtest=CategorizationServiceTest,PersonToPersonTransferDetectorTest,CategoryRulesTest`
Expected: PASS — every test from all three tasks, plus every pre-existing test in these files (the keyword-first ordering regression test from Step 6 confirms no existing categorization outcome changed).

Then run the full backend suite to catch anything this plan's changes might affect elsewhere (e.g. `RuleEngineServiceTest`, any import-pipeline test asserting a specific category for a fixture that happens to be a P2P-shaped narration):

Run: `cd backend && ./mvnw test`
Expected: PASS. If an unrelated existing test fails because a fixture narration now resolves to `"Transfer"`/`STRUCTURAL_P2P` instead of `"Other"`/`MERCHANT_DEFAULT`, that is the fix working as intended — update the fixture's expected value rather than the detector, and note it in the commit message.

- [ ] **Step 12: Commit**

```bash
git add backend/src/main/java/com/finora/util/PersonToPersonTransferDetector.java backend/src/test/java/com/finora/util/PersonToPersonTransferDetectorTest.java backend/src/main/java/com/finora/entity/Transaction.java backend/src/main/java/com/finora/service/CategorizationService.java backend/src/main/java/com/finora/dto/ImportDto.java backend/src/test/java/com/finora/service/CategorizationServiceTest.java
git commit -m "feat(imports): detect person-to-person transfers structurally, route to Transfer category"
```
