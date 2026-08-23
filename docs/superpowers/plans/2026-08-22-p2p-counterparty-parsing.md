# P2P/Payment-Relay Counterparty Parsing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Finora's Gmail receipt pipeline a counterparty-aware extraction path so PhonePe and CRED transactions are correctly attributed (not labeled "phonepe.com"/"cred.club"), and clean up the two now-dead V103 template rows that can never be safely activated as-is.

**Architecture:** Add one nullable `counterpartyName` field to the existing `ParsedReceipt` record (additive, every current parser passes `null`). Add two new hand-written, config-gated `MerchantEmailParser` implementations (`PhonePeEmailParser`, `CredEmailParser`) built against real Gmail data, plus one deliberately-unimplemented scaffold (`PaytmEmailParser`). `GmailStagingBridge` prefers the counterparty name for the staged transaction's description when one exists. A new migration removes the `phonepe.com`/`paytm.com`/`cred.club` rows V103 seeded into `merchant_templates`, since that declarative model cannot represent a counterparty distinct from the domain.

**Tech Stack:** Java 25 / Spring Boot (backend), JUnit 5 + AssertJ + Mockito, Flyway migrations, YAML config with `@Value` injection.

**Spec:** [docs/proposals/gmail-merchant-template-admin-ui-proposal.md](../../proposals/gmail-merchant-template-admin-ui-proposal.md) — see its "2026-08-22 update" section for the full design reasoning this plan implements.

## Global Constraints

- `ParsedReceipt.counterpartyName` is nullable and additive — no other change to the public shape of `MerchantEmailParser` or `ParserResult`.
- Every new parser (`PhonePeEmailParser`, `CredEmailParser`, `PaytmEmailParser`) MUST gate `canParse` on its own config property under `app.integrations.google.parsers.<merchant>.enabled`, default `false` — merging code must never make a parser live.
- No literal real Gmail evidence (real names, amounts, dates, transaction IDs, bank account numbers) may appear in code, fixtures, commit messages, or this plan — every example value in this plan is synthetic, chosen to be distinct from anything observed in real data.
- Re-verify the next free Flyway migration version against `origin/main` immediately before creating the migration file in Task 6 — do not assume the number below is still free.
- Every task ends with `cd backend && ./mvnw test -Dtest=<ClassName>` green for the classes it touched; Task 7 runs the full suite.

---

### Task 1: Extend `ParsedReceipt` with a nullable `counterpartyName`

**Files:**
- Modify: `backend/src/main/java/com/finora/integrations/google/merchant/ParsedReceipt.java`
- Modify: `backend/src/main/java/com/finora/integrations/google/merchant/AmazonEmailParser.java:118-119`
- Modify: `backend/src/main/java/com/finora/integrations/google/merchant/MyntraEmailParser.java` (its `new ParsedReceipt(...)` call)
- Modify: `backend/src/main/java/com/finora/integrations/google/merchant/OlaEmailParser.java` (its `new ParsedReceipt(...)` call)
- Modify: `backend/src/main/java/com/finora/integrations/google/merchant/BookingEmailParser.java` (its `new ParsedReceipt(...)` call)
- Modify: `backend/src/main/java/com/finora/integrations/google/merchant/TemplateEmailParser.java:119-120`
- Modify: `backend/src/test/java/com/finora/integrations/google/merchant/ParsedReceiptTest.java`
- Modify: `backend/src/test/java/com/finora/integrations/google/merchant/GmailStagingBridgeTest.java:248` (helper method)
- Modify: `backend/src/test/java/com/finora/integrations/google/merchant/GmailReceiptExtractionServiceTest.java:76,134-135,195-196`
- Modify: `backend/src/test/java/com/finora/integrations/google/merchant/GmailReceiptToTransactionIT.java:60-61,96-97,111-114`
- Modify: `backend/src/test/java/com/finora/integrations/google/merchant/ParsedReceiptValidatorTest.java:116` (helper method)
- Modify: `backend/src/test/java/com/finora/integrations/google/merchant/GmailReviewServiceIT.java:99-102` (helper method)

**Interfaces:**
- Consumes: nothing (foundation task).
- Produces: `new ParsedReceipt(String gmailMessageId, String merchantDomain, String counterpartyName, Money amount, LocalDate transactionDate, double confidence)` and `ParsedReceipt.counterpartyName()` — every later task in this plan constructs or reads through this exact 6-argument, this-exact-order constructor.

- [ ] **Step 1: Update the test that pins the record's own behavior**

Replace the full contents of `backend/src/test/java/com/finora/integrations/google/merchant/ParsedReceiptTest.java`:

```java
package com.finora.integrations.google.merchant;

import com.finora.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The structural half of C5's two-layer validation — see {@link ParsedReceiptValidatorTest} for the
 * business-rule half. A null field here is always a parser bug, never a legitimate "unknown" value,
 * so the constructor refuses it rather than letting it become a receipt that reaches the validator
 * (or worse, staging) missing something it needs. {@code counterpartyName} is the one exception —
 * see the two tests at the bottom.
 */
class ParsedReceiptTest {

    private static final Money AMOUNT = Money.of(new BigDecimal("100.00"));
    private static final LocalDate DATE = LocalDate.of(2026, 8, 10);

    @Test
    void nullGmailMessageIdIsRejected() {
        assertThatThrownBy(() -> new ParsedReceipt(null, "amazon.in", null, AMOUNT, DATE, 0.9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gmailMessageId");
    }

    @Test
    void blankMerchantDomainIsRejected() {
        assertThatThrownBy(() -> new ParsedReceipt("msg-1", "  ", null, AMOUNT, DATE, 0.9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("merchantDomain");
    }

    @Test
    void nullAmountIsRejected() {
        assertThatThrownBy(() -> new ParsedReceipt("msg-1", "amazon.in", null, null, DATE, 0.9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void nullTransactionDateIsRejected() {
        assertThatThrownBy(() -> new ParsedReceipt("msg-1", "amazon.in", null, AMOUNT, null, 0.9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transactionDate");
    }

    @Test
    void confidenceOutsideZeroToOneIsRejected() {
        assertThatThrownBy(() -> new ParsedReceipt("msg-1", "amazon.in", null, AMOUNT, DATE, 1.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");
    }

    @Test
    void counterpartyNameIsOptional() {
        ParsedReceipt withoutCounterparty =
                new ParsedReceipt("msg-1", "amazon.in", null, AMOUNT, DATE, 0.9);

        assertThat(withoutCounterparty.counterpartyName()).isNull();
    }

    @Test
    void counterpartyNameRoundTripsWhenProvided() {
        ParsedReceipt withCounterparty =
                new ParsedReceipt("msg-1", "phonepe.com", "Sunrise General Store", AMOUNT, DATE, 0.9);

        assertThat(withCounterparty.counterpartyName()).isEqualTo("Sunrise General Store");
    }
}
```

This step alone will NOT compile yet (the record still has the old 5-argument shape) — that is expected. A compile failure across the module is the strictest possible "red" for a record signature change; the next step makes it pass.

- [ ] **Step 2: Extend the record itself**

Replace the record declaration and constructor block in `ParsedReceipt.java` (keep the existing file's package, imports, and class-doc javadoc above it unchanged — only the `@param` list and the record/constructor below change):

```java
 * @param gmailMessageId      the provenance key — what C5-B's staged row and the {@code
 *                            gmail_processed_messages} row it originated from both key on.
 * @param merchantDomain      the authenticated domain the receipt came from (e.g. {@code
 *                            amazon.in}), not a display name a template happened to use.
 * @param counterpartyName    who the receipt says the money actually went to, when that is
 *                            knowable and distinct from {@link #merchantDomain} — e.g. a PhonePe
 *                            P2P payee, or CRED's "{@code <Bank> •••• <last4>}". Null for every
 *                            merchant where the domain already IS the counterparty; every parser
 *                            except a payment-relay one passes null, meaning exactly what it means
 *                            today: no counterparty distinct from the merchant.
 * @param amount              the transaction amount. {@link Money}, not a raw number, for the same
 *                            reason every new money-handling calculation in this codebase uses it —
 *                            see {@code Money}'s own class doc.
 * @param transactionDate     the date the receipt states the charge occurred, not the email's
 *                            {@code Date} header — a receipt can be forwarded, delayed, or dated
 *                            differently from its delivery.
 * @param confidence          0.0–1.0, this parser's own estimate of extraction reliability. Purely
 *                            informational; see the class doc above.
 *
 * <h2>Structural checks live here; business-rule checks live in {@link ParsedReceiptValidator}</h2>
 *
 * This constructor refuses a null or blank required field, on the same reasoning as every other
 * fail-fast validation in this codebase: a parser that produced a null date is a bug, and the bug
 * should surface at the exact call site that made the mistake, with a message naming the field,
 * rather than as an NPE three layers downstream with no indication which parser was responsible.
 * {@code counterpartyName} is deliberately not in that list — null is its legitimate, common value,
 * not a forgotten field.
 *
 * <p>Deliberately does NOT check that {@link #amount} is positive or that {@link #transactionDate}
 * is not absurdly far in the future — those are not "this cannot be a receipt" bugs, they are "is
 * this receipt plausible" judgments, and {@link ParsedReceiptValidator} is where that judgment
 * belongs. Because null/blank is already eliminated here, the validator does not re-check for it —
 * checking a condition this constructor has already made impossible would be validating a state
 * that cannot occur.
 */
public record ParsedReceipt(String gmailMessageId, String merchantDomain, String counterpartyName,
                            Money amount, LocalDate transactionDate, double confidence) {

    public ParsedReceipt {
        if (gmailMessageId == null || gmailMessageId.isBlank()) {
            throw new IllegalArgumentException("gmailMessageId is required");
        }
        if (merchantDomain == null || merchantDomain.isBlank()) {
            throw new IllegalArgumentException("merchantDomain is required");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount is required");
        }
        if (transactionDate == null) {
            throw new IllegalArgumentException("transactionDate is required");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0.0, 1.0], got " + confidence);
        }
    }
}
```

- [ ] **Step 3: Fix every production call site**

In `AmazonEmailParser.java`, `MyntraEmailParser.java`, `OlaEmailParser.java`, and `BookingEmailParser.java`, each has an identical two-line call shape. In each file, change:

```java
        return ParserResult.parsed(new ParsedReceipt(
                message.gmailMessageId(), DOMAIN, amount, date, FIXED_CONFIDENCE));
```

to:

```java
        return ParserResult.parsed(new ParsedReceipt(
                message.gmailMessageId(), DOMAIN, null, amount, date, FIXED_CONFIDENCE));
```

In `TemplateEmailParser.java:119-120`, change:

```java
        return ParserResult.parsed(new ParsedReceipt(
                message.gmailMessageId(), template.getMerchantDomain(), amount, date, FIXED_CONFIDENCE));
```

to:

```java
        return ParserResult.parsed(new ParsedReceipt(
                message.gmailMessageId(), template.getMerchantDomain(), null, amount, date, FIXED_CONFIDENCE));
```

- [ ] **Step 4: Fix every remaining test call site**

In `GmailStagingBridgeTest.java`, change the helper at the bottom of the file:

```java
    private static ParsedReceipt receipt(String gmailMessageId, String domain, Money amount,
                                         LocalDate date, double confidence) {
        return new ParsedReceipt(gmailMessageId, domain, amount, date, confidence);
    }
```

to:

```java
    private static ParsedReceipt receipt(String gmailMessageId, String domain, Money amount,
                                         LocalDate date, double confidence) {
        return new ParsedReceipt(gmailMessageId, domain, null, amount, date, confidence);
    }
```

In `GmailReceiptExtractionServiceTest.java`, there are three direct constructions. Change:

```java
        ParsedReceipt receipt = new ParsedReceipt("m1", "amazon.in",
                Money.of(new BigDecimal("500.00")), LocalDate.of(2026, 8, 10), 0.9);
```
to
```java
        ParsedReceipt receipt = new ParsedReceipt("m1", "amazon.in", null,
                Money.of(new BigDecimal("500.00")), LocalDate.of(2026, 8, 10), 0.9);
```

Change:
```java
        ParsedReceipt zeroReceipt = new ParsedReceipt("m1", "amazon.in", Money.ZERO,
                LocalDate.of(2026, 8, 10), 0.9);
```
to
```java
        ParsedReceipt zeroReceipt = new ParsedReceipt("m1", "amazon.in", null, Money.ZERO,
                LocalDate.of(2026, 8, 10), 0.9);
```

Change:
```java
        when(parser.parse(any())).thenReturn(ParserResult.parsed(new ParsedReceipt(
                "m2", "amazon.in", Money.of(new BigDecimal("500.00")), LocalDate.of(2026, 8, 10), 0.9)));
```
to
```java
        when(parser.parse(any())).thenReturn(ParserResult.parsed(new ParsedReceipt(
                "m2", "amazon.in", null, Money.of(new BigDecimal("500.00")), LocalDate.of(2026, 8, 10), 0.9)));
```

In `GmailReceiptToTransactionIT.java`, there are four direct constructions. Change:
```java
        ParsedReceipt receipt = new ParsedReceipt("18ab39xyz", "amazon.in",
                Money.of(new BigDecimal("1299.00")), LocalDate.of(2026, 8, 10), 0.9);
```
(the first occurrence, in `aReceiptBecomesATransactionOnConfirm`) to
```java
        ParsedReceipt receipt = new ParsedReceipt("18ab39xyz", "amazon.in", null,
                Money.of(new BigDecimal("1299.00")), LocalDate.of(2026, 8, 10), 0.9);
```

Change the second occurrence (in `stagingTheSameReceiptTwiceIsIdempotent`):
```java
        ParsedReceipt receipt = new ParsedReceipt("18ab39xyz", "amazon.in",
                Money.of(new BigDecimal("500.00")), LocalDate.of(2026, 8, 10), 0.9);
```
to
```java
        ParsedReceipt receipt = new ParsedReceipt("18ab39xyz", "amazon.in", null,
                Money.of(new BigDecimal("500.00")), LocalDate.of(2026, 8, 10), 0.9);
```

Change the two in `differentReceiptsForTheSameUserBothStage`:
```java
        ParsedReceipt first = new ParsedReceipt("msg-1", "amazon.in",
                Money.of(new BigDecimal("500.00")), LocalDate.of(2026, 8, 10), 0.9);
        ParsedReceipt second = new ParsedReceipt("msg-2", "amazon.in",
                Money.of(new BigDecimal("750.00")), LocalDate.of(2026, 8, 11), 0.9);
```
to
```java
        ParsedReceipt first = new ParsedReceipt("msg-1", "amazon.in", null,
                Money.of(new BigDecimal("500.00")), LocalDate.of(2026, 8, 10), 0.9);
        ParsedReceipt second = new ParsedReceipt("msg-2", "amazon.in", null,
                Money.of(new BigDecimal("750.00")), LocalDate.of(2026, 8, 11), 0.9);
```

In `ParsedReceiptValidatorTest.java`, change the helper:
```java
    private static ParsedReceipt receipt(Money amount, LocalDate date) {
        return new ParsedReceipt("msg-1", "amazon.in", amount, date, 0.9);
    }
```
to
```java
    private static ParsedReceipt receipt(Money amount, LocalDate date) {
        return new ParsedReceipt("msg-1", "amazon.in", null, amount, date, 0.9);
    }
```

In `GmailReviewServiceIT.java`, change the helper:
```java
    private ParsedReceipt receipt(String gmailMessageId, String domain, BigDecimal amount,
                                   LocalDate date, double confidence) {
        return new ParsedReceipt(gmailMessageId, domain, Money.of(amount), date, confidence);
    }
```
to
```java
    private ParsedReceipt receipt(String gmailMessageId, String domain, BigDecimal amount,
                                   LocalDate date, double confidence) {
        return new ParsedReceipt(gmailMessageId, domain, null, Money.of(amount), date, confidence);
    }
```

- [ ] **Step 5: Run the full merchant-package test suite to confirm green**

Run: `cd backend && ./mvnw test -Dtest=com.finora.integrations.google.merchant.*`
Expected: BUILD SUCCESS, `ParsedReceiptTest`'s 7 tests (5 existing + 2 new) all pass, every other test in the package compiles and passes unchanged.

- [ ] **Step 6: Commit**

```bash
cd backend && git add \
  src/main/java/com/finora/integrations/google/merchant/ParsedReceipt.java \
  src/main/java/com/finora/integrations/google/merchant/AmazonEmailParser.java \
  src/main/java/com/finora/integrations/google/merchant/MyntraEmailParser.java \
  src/main/java/com/finora/integrations/google/merchant/OlaEmailParser.java \
  src/main/java/com/finora/integrations/google/merchant/BookingEmailParser.java \
  src/main/java/com/finora/integrations/google/merchant/TemplateEmailParser.java \
  src/test/java/com/finora/integrations/google/merchant/ParsedReceiptTest.java \
  src/test/java/com/finora/integrations/google/merchant/GmailStagingBridgeTest.java \
  src/test/java/com/finora/integrations/google/merchant/GmailReceiptExtractionServiceTest.java \
  src/test/java/com/finora/integrations/google/merchant/GmailReceiptToTransactionIT.java \
  src/test/java/com/finora/integrations/google/merchant/ParsedReceiptValidatorTest.java \
  src/test/java/com/finora/integrations/google/merchant/GmailReviewServiceIT.java
git commit -m "feat(backend): add counterparty field to ParsedReceipt contract"
```

---

### Task 2: `GmailStagingBridge` prefers the counterparty name for the staged description

**Files:**
- Modify: `backend/src/main/java/com/finora/integrations/google/merchant/GmailStagingBridge.java:125-133`
- Modify: `backend/src/test/java/com/finora/integrations/google/merchant/GmailStagingBridgeTest.java`

**Interfaces:**
- Consumes: `ParsedReceipt.counterpartyName()` from Task 1.
- Produces: nothing later tasks depend on (leaf consumer of the contract).

- [ ] **Step 1: Write the failing tests**

Add these two tests to `GmailStagingBridgeTest.java` (anywhere among the existing `@Test` methods, e.g. right after `stagesTheReceiptAsOneRow`):

```java
    @Test
    @DisplayName("a receipt with a counterparty uses it as the description, not the raw domain")
    void counterpartyNamePreferredOverDomainForDescription() {
        ParsedReceipt receiptWithCounterparty = new ParsedReceipt("msg-1", "phonepe.com",
                "Sunrise General Store", Money.of(new BigDecimal("480.00")),
                LocalDate.of(2026, 7, 14), 0.9);

        bridge.stage(userId, receiptWithCounterparty);

        assertThat(capturedRows().get(0).description()).isEqualTo("Sunrise General Store");
    }

    @Test
    @DisplayName("a receipt with no counterparty falls back to the raw domain, same as before")
    void domainUsedWhenNoCounterparty() {
        bridge.stage(userId, receipt("msg-1", "amazon.in",
                Money.of(new BigDecimal("1299.00")), LocalDate.of(2026, 8, 10), 0.9));

        assertThat(capturedRows().get(0).description()).isEqualTo("amazon.in");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=GmailStagingBridgeTest`
Expected: `counterpartyNamePreferredOverDomainForDescription` FAILS — `description()` is currently always `"phonepe.com"` (the raw domain), not `"Sunrise General Store"`. `domainUsedWhenNoCounterparty` passes already (this is the pre-existing, unchanged behavior for the null-counterparty case) — that's fine, it exists to pin the fallback so Step 3 cannot regress it.

- [ ] **Step 3: Implement the preference**

In `GmailStagingBridge.java`, change:

```java
    private static String descriptionFor(ParsedReceipt receipt) {
        return receipt.merchantDomain();
    }

    /** Purely cosmetic — what the "Continue previous import" card list (file-upload-shaped UI,
     *  reused rather than replaced per §5.1) shows for a receipt instead of a filename. */
    private static String fileNameFor(ParsedReceipt receipt) {
        return receipt.merchantDomain() + " receipt — " + FILE_NAME_DATE.format(receipt.transactionDate());
    }
```

to:

```java
    private static String descriptionFor(ParsedReceipt receipt) {
        return receipt.counterpartyName() != null ? receipt.counterpartyName() : receipt.merchantDomain();
    }

    /** Purely cosmetic — what the "Continue previous import" card list (file-upload-shaped UI,
     *  reused rather than replaced per §5.1) shows for a receipt instead of a filename. Reuses
     *  {@link #descriptionFor} rather than re-deriving the counterparty-vs-domain choice a second
     *  time. */
    private static String fileNameFor(ParsedReceipt receipt) {
        return descriptionFor(receipt) + " receipt — " + FILE_NAME_DATE.format(receipt.transactionDate());
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=GmailStagingBridgeTest`
Expected: BUILD SUCCESS, all tests pass including both new ones.

- [ ] **Step 5: Commit**

```bash
cd backend && git add \
  src/main/java/com/finora/integrations/google/merchant/GmailStagingBridge.java \
  src/test/java/com/finora/integrations/google/merchant/GmailStagingBridgeTest.java
git commit -m "feat(backend): prefer counterparty name in Gmail receipt staging description"
```

---

### Task 3: `PhonePeEmailParser` — real-verified, config-gated

**Files:**
- Modify: `backend/src/main/java/com/finora/integrations/google/merchant/ReceiptDateFormats.java`
- Create: `backend/src/main/java/com/finora/integrations/google/merchant/PhonePeEmailParser.java`
- Create: `backend/src/main/resources/application.yml` (edit, not new file — see Step 2)
- Create: `backend/src/test/resources/gmail/phonepe/paid-to-successful.html`
- Create: `backend/src/test/resources/gmail/phonepe/cashback-offer.html`
- Create: `backend/src/test/resources/gmail/phonepe/missing-paid-to.html`
- Create: `backend/src/test/java/com/finora/integrations/google/merchant/PhonePeEmailParserTest.java`

**Interfaces:**
- Consumes: `ParsedReceipt`'s 6-arg constructor (Task 1).
- Produces: `ReceiptDateFormats.tryParse` now also accepts abbreviated-month text like `"Jul 14, 2026"` (pattern `MMM d, yyyy`) — Task 4's `CredEmailParser` reuses this same new format entry for its own date field, so this task must land first.

- [ ] **Step 1: Add the new date format (with its own direct check first)**

In `ReceiptDateFormats.java`, change:

```java
    private static final List<DateTimeFormatter> FORMATS = List.of(
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH));
```

to:

```java
    private static final List<DateTimeFormatter> FORMATS = List.of(
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH));
```

This new format (`MMM d, yyyy`, abbreviated month — e.g. "Jul 14, 2026") is verified against real PhonePe data: the date line in every real PhonePe transaction email seen sits unlabeled, right after the word "PhonePe", in this exact abbreviated-month shape. There is no dedicated `ReceiptDateFormatsTest` in this codebase (every format is exercised transitively through the parser that needs it, same as the five formats already here) — `PhonePeEmailParserTest`'s happy-path test in Step 5 below is what proves this line works, so no separate test file is added here.

- [ ] **Step 2: Add the config gate default**

In `backend/src/main/resources/application.yml`, find this block (the end of `app.integrations.google.discovery`):

```yaml
        # "Sync Now" (C5.4) -- a much shorter cooldown than minimum-interval-ms above, since this
        # gates a single user re-clicking a button, not the whole tick's cost across every mailbox.
        # See GmailManualSyncService's own doc comment for why it's a separate value.
        manual-sync-cooldown-ms: ${GMAIL_MANUAL_SYNC_COOLDOWN_MS:60000}
```

and add a new sibling block directly after it (before the blank line that precedes the `finora:` top-level section):

```yaml
        # "Sync Now" (C5.4) -- a much shorter cooldown than minimum-interval-ms above, since this
        # gates a single user re-clicking a button, not the whole tick's cost across every mailbox.
        # See GmailManualSyncService's own doc comment for why it's a separate value.
        manual-sync-cooldown-ms: ${GMAIL_MANUAL_SYNC_COOLDOWN_MS:60000}

      # Hand-written P2P/payment-relay parsers (PhonePe, CRED, Paytm) -- unlike merchant_templates,
      # a hand-written MerchantEmailParser.canParse has no per-row enabled column to gate it, so
      # each one gates on its own property here instead, off by default. See PhonePeEmailParser's
      # own class doc for why "verified against real data" is not the same thing as "safe to run
      # unconditionally the moment this deploys".
      parsers:
        phonepe:
          enabled: ${GMAIL_PARSER_PHONEPE_ENABLED:false}
```

- [ ] **Step 3: Write the three fixtures**

Create `backend/src/test/resources/gmail/phonepe/paid-to-successful.html` (a synthetic but real-shaped completed transfer):

```html
<p>PhonePe Jul 14, 2026 Paid to Sunrise General Store ₹ 480 Txn. ID : TXXXXXXXXXXXXXXXXXXXXXXX Txn. status : Successful Debited from : XXXXXX0000 State Bank of India Bank Ref. No. : XXXXXXXXXXXX</p>
```

Create `backend/src/test/resources/gmail/phonepe/cashback-offer.html` (ordinary PhonePe marketing mail — must be recognised as NOT_A_RECEIPT):

```html
<p>PhonePe Recharge your mobile and get up to 10% cashback! Offer valid till end of month. Open the app to recharge now.</p>
```

Create `backend/src/test/resources/gmail/phonepe/missing-paid-to.html` (receipt-shaped — has the success marker — but the counterparty/amount text is missing, e.g. a differently-templated notification this parser was never built to read):

```html
<p>PhonePe Jul 14, 2026 Txn. ID : TXXXXXXXXXXXXXXXXXXXXXXX Txn. status : Successful Debited from : XXXXXX0000 State Bank of India Bank Ref. No. : XXXXXXXXXXXX</p>
```

- [ ] **Step 4: Write the failing parser test**

Create `backend/src/test/java/com/finora/integrations/google/merchant/PhonePeEmailParserTest.java`:

```java
package com.finora.integrations.google.merchant;

import com.finora.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase C5 follow-up, 2026-08-22. Fixtures are a real PhonePe transaction-notification shape
 * (synthetic counterparty name, amount, transaction id, and bank reference — see
 * docs/proposals/gmail-merchant-template-admin-ui-proposal.md's 2026-08-22 update for how the real
 * shape was verified without ever putting real values in this codebase), run through
 * {@link MerchantEmailSanitizer} exactly as the pipeline will.
 */
class PhonePeEmailParserTest {

    private final MerchantEmailSanitizer sanitizer = new MerchantEmailSanitizer();
    private final PhonePeEmailParser parser = new PhonePeEmailParser();

    @BeforeEach
    void enableParser() {
        ReflectionTestUtils.setField(parser, "enabled", true);
    }

    @Test
    void canParseOnlyClaimsPhonePesAuthenticatedDomainWhenEnabled() {
        assertThat(parser.canParse("phonepe.com")).isTrue();
        assertThat(parser.canParse("phonepe.attacker.example")).isFalse();
        assertThat(parser.canParse("paytm.com")).isFalse();
    }

    @Test
    @DisplayName("disabled by default -- canParse is false until the config property is set")
    void canParseIsFalseWhenNotExplicitlyEnabled() {
        PhonePeEmailParser disabledParser = new PhonePeEmailParser();

        assertThat(disabledParser.canParse("phonepe.com")).isFalse();
    }

    @Test
    @DisplayName("a completed transfer is parsed with the payee as the counterparty")
    void shouldParseSuccessfulTransfer() {
        SanitizedGmailMessage message = load("paid-to-successful.html", "msg-1");

        ParserResult result = parser.parse(message);

        assertThat(result.isParsed()).isTrue();
        ParsedReceipt receipt = result.receipt();
        assertThat(receipt.gmailMessageId()).isEqualTo("msg-1");
        assertThat(receipt.merchantDomain()).isEqualTo("phonepe.com");
        assertThat(receipt.counterpartyName()).isEqualTo("Sunrise General Store");
        assertThat(receipt.amount()).isEqualTo(Money.of(new BigDecimal("480.00")));
        assertThat(receipt.transactionDate()).isEqualTo(LocalDate.of(2026, 7, 14));
        assertThat(receipt.confidence()).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("a cashback offer is recognised as not-a-receipt, not as a parse failure")
    void shouldIgnoreMarketingMail() {
        SanitizedGmailMessage message = load("cashback-offer.html", "msg-2");

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.NOT_A_RECEIPT);
        assertThat(result.receipt()).isNull();
    }

    @Test
    @DisplayName("a successful-transaction email with no extractable counterparty/amount is malformed, not ignored")
    void shouldRejectMalformedTransfer() {
        SanitizedGmailMessage message = load("missing-paid-to.html", "msg-3");

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.MALFORMED);
        assertThat(result.receipt()).isNull();
        assertThat(result.reason()).isNotBlank();
    }

    private SanitizedGmailMessage load(String fixture, String gmailMessageId) {
        String html = readFixture(fixture);
        return sanitizer.sanitize(gmailMessageId, "phonepe.com", html);
    }

    private static String readFixture(String name) {
        try {
            Path path = Path.of("src/test/resources/gmail/phonepe", name);
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=PhonePeEmailParserTest`
Expected: FAIL to compile — `PhonePeEmailParser` does not exist yet.

- [ ] **Step 6: Implement `PhonePeEmailParser`**

Create `backend/src/main/java/com/finora/integrations/google/merchant/PhonePeEmailParser.java`:

```java
package com.finora.integrations.google.merchant;

import com.finora.domain.Money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PhonePe's payment-notification email — a P2P/UPI transfer, not a merchant receipt in the sense
 * every other parser assumes. See docs/proposals/gmail-merchant-template-admin-ui-proposal.md's
 * 2026-08-22 update for the full reasoning: the domain is PhonePe, but the actual counterparty
 * (who the money went to) is a name embedded in the body, so this parser — unlike every other one
 * in this package — populates {@link ParsedReceipt#counterpartyName()} rather than leaving it
 * null.
 *
 * <h2>Config-gated, unlike the marker/pattern verification this parser already has</h2>
 *
 * Every field here is verified against real Gmail data (five consistent real messages spanning
 * 2019–2024). That is not the same thing as safe to run unconditionally the moment this deploys —
 * unlike {@code merchant_templates}' {@code enabled} column, a hand-written parser's {@code
 * canParse} has no per-row kill switch, so this one gates on {@code
 * app.integrations.google.parsers.phonepe.enabled} (default false) the same way {@code
 * AdminMfaService} gates on {@code app.admin-mfa.enabled} — merging this class must not make it
 * live; that is a separate, deliberate flip.
 */
@Component
public class PhonePeEmailParser implements MerchantEmailParser {

    private static final String DOMAIN = "phonepe.com";

    private static final double FIXED_CONFIDENCE = 0.9;

    /** Present on every completed PhonePe transfer seen so far; absent from PhonePe's other mail
     *  (offers, cashback promos, app notifications). */
    private static final Pattern SUCCESS_MARKER = Pattern.compile(
            "Txn\\.\\s*status\\s*:\\s*Successful", Pattern.CASE_INSENSITIVE);

    /**
     * Captures the counterparty and the amount together, since they sit adjacent in the body
     * ("Paid to &lt;name&gt; ₹ &lt;amount&gt;") — anchoring the name capture to stop at the
     * currency symbol is what keeps it from swallowing the rest of the line. The amount has no
     * mandatory decimal part: real PhonePe amounts seen so far are plain integers ("₹ 27000"),
     * unlike Amazon's always-two-decimal totals.
     */
    private static final Pattern PAID_TO = Pattern.compile(
            "Paid to\\s+(.+?)\\s*₹\\s*(?<!\\d)([\\d,]{1,18}(?:\\.\\d{2})?)(?!\\d)");

    /** The date line has no label at all — it sits right after "PhonePe" and right before "Paid
     *  to" ("PhonePe Jul 14, 2026 Paid to ..."), an abbreviated-month format {@link
     *  ReceiptDateFormats} did not parse until this class needed it. */
    private static final Pattern HEADER_DATE = Pattern.compile(
            "PhonePe\\s+([A-Za-z]{3}\\s+\\d{1,2},\\s+\\d{4})\\s+Paid to");

    @Value("${app.integrations.google.parsers.phonepe.enabled:false}")
    private boolean enabled;

    @Override
    public boolean canParse(String authenticatedDomain) {
        return enabled && DOMAIN.equals(authenticatedDomain);
    }

    @Override
    public ParserResult parse(SanitizedGmailMessage message) {
        String text = message.plainText();

        if (!SUCCESS_MARKER.matcher(text).find()) {
            return ParserResult.notAReceipt("no successful-transaction marker found");
        }

        Matcher paidTo = PAID_TO.matcher(text);
        if (!paidTo.find()) {
            return ParserResult.malformed("recognised as a successful transaction but no "
                    + "counterparty/amount could be extracted -- template may have changed");
        }

        String counterpartyName = paidTo.group(1).strip();

        Money amount;
        try {
            amount = Money.of(new BigDecimal(paidTo.group(2).replace(",", "")));
        } catch (NumberFormatException e) {
            return ParserResult.malformed("amount matched but did not parse as a number: "
                    + paidTo.group(2));
        }

        Matcher dateMatch = HEADER_DATE.matcher(text);
        if (!dateMatch.find()) {
            return ParserResult.malformed("recognised as a successful transaction but no date "
                    + "could be extracted -- template may have changed");
        }

        LocalDate date = ReceiptDateFormats.tryParse(dateMatch.group(1));
        if (date == null) {
            return ParserResult.malformed("date matched \"" + dateMatch.group(1)
                    + "\" but did not parse as a recognised date format");
        }

        return ParserResult.parsed(new ParsedReceipt(
                message.gmailMessageId(), DOMAIN, counterpartyName, amount, date, FIXED_CONFIDENCE));
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=PhonePeEmailParserTest`
Expected: BUILD SUCCESS, all 6 tests pass.

- [ ] **Step 8: Commit**

```bash
cd backend && git add \
  src/main/java/com/finora/integrations/google/merchant/ReceiptDateFormats.java \
  src/main/java/com/finora/integrations/google/merchant/PhonePeEmailParser.java \
  src/main/resources/application.yml \
  src/test/resources/gmail/phonepe/ \
  src/test/java/com/finora/integrations/google/merchant/PhonePeEmailParserTest.java
git commit -m "feat(backend): add PhonePeEmailParser, verified against real Gmail data"
```

---

### Task 4: `CredEmailParser` — real-verified, config-gated

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/com/finora/integrations/google/merchant/CredEmailParser.java`
- Create: `backend/src/test/resources/gmail/cred/payment-successful.html`
- Create: `backend/src/test/resources/gmail/cred/bill-generated.html`
- Create: `backend/src/test/resources/gmail/cred/payment-due.html`
- Create: `backend/src/test/resources/gmail/cred/missing-bank-card.html`
- Create: `backend/src/test/java/com/finora/integrations/google/merchant/CredEmailParserTest.java`

**Interfaces:**
- Consumes: `ParsedReceipt`'s 6-arg constructor (Task 1); `ReceiptDateFormats`'s `MMM d, yyyy` support (Task 3).
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Add the config gate default**

In `backend/src/main/resources/application.yml`, find the block Task 3 added and append a sibling under `parsers:`:

```yaml
      parsers:
        phonepe:
          enabled: ${GMAIL_PARSER_PHONEPE_ENABLED:false}
```

change to:

```yaml
      parsers:
        phonepe:
          enabled: ${GMAIL_PARSER_PHONEPE_ENABLED:false}
        cred:
          enabled: ${GMAIL_PARSER_CRED_ENABLED:false}
```

- [ ] **Step 2: Write the four fixtures**

Create `backend/src/test/resources/gmail/cred/payment-successful.html` (synthetic bank/card/amount/date, distinct from anything observed in real data):

```html
<p>Siddharth, here is your payment confirmation Your credit card payment was successful in 9 seconds Yes Bank •••• 9042 payment details amount paid ₹3,450.00 payment date Jul 14, 2026 credited to card Jul 14, 2026</p>
```

Create `backend/src/test/resources/gmail/cred/bill-generated.html` (a real, recurring CRED shape where no money has moved yet — must be NOT_A_RECEIPT):

```html
<p>Siddharth, your credit card bill for July has been generated Yes Bank •••• 9042 bill summary total amount due ₹5,200.00 minimum due ₹500.00 due date August 02, 2026 bill generated on Jul 20, 2026</p>
```

Create `backend/src/test/resources/gmail/cred/payment-due.html` (the other real, recurring not-yet-paid shape — must also be NOT_A_RECEIPT):

```html
<p>hi, your credit card payment is due payment due by 2026-08-02 Yes Bank •••• 9042 total due ₹5,200.00 Pay now we recommend to pay atleast 2 days before to avoid any late fee</p>
```

Create `backend/src/test/resources/gmail/cred/missing-bank-card.html` (marker present, but the bank/card text this parser needs is absent — must be MALFORMED):

```html
<p>Siddharth, here is your payment confirmation Your credit card payment was successful in 9 seconds payment details amount paid ₹3,450.00 payment date Jul 14, 2026</p>
```

- [ ] **Step 3: Write the failing parser test**

Create `backend/src/test/java/com/finora/integrations/google/merchant/CredEmailParserTest.java`:

```java
package com.finora.integrations.google.merchant;

import com.finora.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase C5 follow-up, 2026-08-22. Fixtures are a real CRED credit-card-bill-payment shape
 * (synthetic bank, card, amount, date — see
 * docs/proposals/gmail-merchant-template-admin-ui-proposal.md's 2026-08-22 update), run through
 * {@link MerchantEmailSanitizer} exactly as the pipeline will. The two NOT_A_RECEIPT fixtures
 * (bill-generated, payment-due) are the two other real, recurring cred.club email shapes that
 * look receipt-adjacent but represent no completed payment — this parser must never mistake
 * either for a successful transaction.
 */
class CredEmailParserTest {

    private final MerchantEmailSanitizer sanitizer = new MerchantEmailSanitizer();
    private final CredEmailParser parser = new CredEmailParser();

    @BeforeEach
    void enableParser() {
        ReflectionTestUtils.setField(parser, "enabled", true);
    }

    @Test
    void canParseOnlyClaimsCredsAuthenticatedDomainWhenEnabled() {
        assertThat(parser.canParse("cred.club")).isTrue();
        assertThat(parser.canParse("cred.attacker.example")).isFalse();
        assertThat(parser.canParse("phonepe.com")).isFalse();
    }

    @Test
    @DisplayName("disabled by default -- canParse is false until the config property is set")
    void canParseIsFalseWhenNotExplicitlyEnabled() {
        CredEmailParser disabledParser = new CredEmailParser();

        assertThat(disabledParser.canParse("cred.club")).isFalse();
    }

    @Test
    @DisplayName("a successful bill payment is parsed with the bank+card as the counterparty")
    void shouldParseSuccessfulPayment() {
        SanitizedGmailMessage message = load("payment-successful.html", "msg-1");

        ParserResult result = parser.parse(message);

        assertThat(result.isParsed()).isTrue();
        ParsedReceipt receipt = result.receipt();
        assertThat(receipt.gmailMessageId()).isEqualTo("msg-1");
        assertThat(receipt.merchantDomain()).isEqualTo("cred.club");
        assertThat(receipt.counterpartyName()).isEqualTo("Yes Bank •••• 9042");
        assertThat(receipt.amount()).isEqualTo(Money.of(new BigDecimal("3450.00")));
        assertThat(receipt.transactionDate()).isEqualTo(LocalDate.of(2026, 7, 14));
        assertThat(receipt.confidence()).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("a bill-generated notice is not-a-receipt -- no money has moved yet")
    void shouldIgnoreBillGeneratedNotice() {
        SanitizedGmailMessage message = load("bill-generated.html", "msg-2");

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.NOT_A_RECEIPT);
        assertThat(result.receipt()).isNull();
    }

    @Test
    @DisplayName("a payment-due reminder is not-a-receipt -- no money has moved yet")
    void shouldIgnorePaymentDueReminder() {
        SanitizedGmailMessage message = load("payment-due.html", "msg-3");

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.NOT_A_RECEIPT);
        assertThat(result.receipt()).isNull();
    }

    @Test
    @DisplayName("a payment confirmation with no extractable bank/card is malformed, not ignored")
    void shouldRejectMalformedPayment() {
        SanitizedGmailMessage message = load("missing-bank-card.html", "msg-4");

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.MALFORMED);
        assertThat(result.receipt()).isNull();
        assertThat(result.reason()).isNotBlank();
    }

    private SanitizedGmailMessage load(String fixture, String gmailMessageId) {
        String html = readFixture(fixture);
        return sanitizer.sanitize(gmailMessageId, "cred.club", html);
    }

    private static String readFixture(String name) {
        try {
            Path path = Path.of("src/test/resources/gmail/cred", name);
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=CredEmailParserTest`
Expected: FAIL to compile — `CredEmailParser` does not exist yet.

- [ ] **Step 5: Implement `CredEmailParser`**

Create `backend/src/main/java/com/finora/integrations/google/merchant/CredEmailParser.java`:

```java
package com.finora.integrations.google.merchant;

import com.finora.domain.Money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CRED's credit-card-bill-payment confirmation — a different shape from PhonePe's P2P transfer
 * despite both being "payment-relay" domains flagged together in V103. See
 * docs/proposals/gmail-merchant-template-admin-ui-proposal.md's 2026-08-22 update: CRED's real
 * counterparty is a bounded "{@code <Bank> •••• <last4>}" string (which bank's card bill was
 * paid), not an arbitrary payee name — structurally closer to "which account" than "who got paid".
 *
 * <h2>Two other real, recurring CRED email shapes this must NOT match</h2>
 *
 * Most real {@code cred.club} mail in a live inbox is not a completed payment at all: a "your
 * credit card bill ... has been generated" notice and a "your credit card payment is due"
 * reminder, both sent before any money moves. Neither contains this parser's marker text, so both
 * correctly fall through to {@link ParserResult.Status#NOT_A_RECEIPT} — see {@code
 * CredEmailParserTest} for real-shaped fixtures of both.
 *
 * <h2>Config-gated</h2>
 *
 * Same reasoning as {@link PhonePeEmailParser}: verified against real data does not mean safe to
 * run unconditionally the moment this deploys. Gated on {@code
 * app.integrations.google.parsers.cred.enabled} (default false).
 */
@Component
public class CredEmailParser implements MerchantEmailParser {

    private static final String DOMAIN = "cred.club";

    private static final double FIXED_CONFIDENCE = 0.9;

    /** Present only on a completed-payment confirmation; absent from CRED's bill-generated and
     *  payment-due mail, both ordinary, expected traffic through this trusted domain — not a
     *  receipt this parser should stage. */
    private static final Pattern MARKER = Pattern.compile(
            "payment confirmation", Pattern.CASE_INSENSITIVE);

    /** The bank name and masked card sit right after "successful in N seconds", e.g. "successful
     *  in 9 seconds Yes Bank •••• 9042" — captured together since that anchor is what keeps the
     *  bank-name group from matching the wrong "Bank" occurrence elsewhere in the message. */
    private static final Pattern BANK_AND_CARD = Pattern.compile(
            "successful in \\d+ seconds\\s+(.+?\\s+Bank)\\s*•{4}\\s*(\\d{4})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern AMOUNT = Pattern.compile(
            "amount paid\\s*₹\\s*(?<!\\d)([\\d,]{1,18}\\.\\d{2})(?!\\d)", Pattern.CASE_INSENSITIVE);

    private static final Pattern DATE_TEXT = Pattern.compile(
            "payment date\\s*:?\\s*([A-Za-z]{3}\\s+\\d{1,2},\\s+\\d{4})", Pattern.CASE_INSENSITIVE);

    @Value("${app.integrations.google.parsers.cred.enabled:false}")
    private boolean enabled;

    @Override
    public boolean canParse(String authenticatedDomain) {
        return enabled && DOMAIN.equals(authenticatedDomain);
    }

    @Override
    public ParserResult parse(SanitizedGmailMessage message) {
        String text = message.plainText();

        if (!MARKER.matcher(text).find()) {
            return ParserResult.notAReceipt("no payment-confirmation marker found");
        }

        Matcher bankMatch = BANK_AND_CARD.matcher(text);
        if (!bankMatch.find()) {
            return ParserResult.malformed("recognised as a payment confirmation but no bank/card "
                    + "could be extracted -- template may have changed");
        }
        String counterpartyName = bankMatch.group(1).strip() + " •••• " + bankMatch.group(2);

        Matcher amountMatch = AMOUNT.matcher(text);
        if (!amountMatch.find()) {
            return ParserResult.malformed("recognised as a payment confirmation but no amount "
                    + "could be extracted -- template may have changed");
        }

        Money amount;
        try {
            amount = Money.of(new BigDecimal(amountMatch.group(1).replace(",", "")));
        } catch (NumberFormatException e) {
            return ParserResult.malformed("amount matched but did not parse as a number: "
                    + amountMatch.group(1));
        }

        Matcher dateMatch = DATE_TEXT.matcher(text);
        if (!dateMatch.find()) {
            return ParserResult.malformed("recognised as a payment confirmation but no date "
                    + "could be extracted -- template may have changed");
        }

        LocalDate date = ReceiptDateFormats.tryParse(dateMatch.group(1));
        if (date == null) {
            return ParserResult.malformed("date matched \"" + dateMatch.group(1)
                    + "\" but did not parse as a recognised date format");
        }

        return ParserResult.parsed(new ParsedReceipt(
                message.gmailMessageId(), DOMAIN, counterpartyName, amount, date, FIXED_CONFIDENCE));
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=CredEmailParserTest`
Expected: BUILD SUCCESS, all 7 tests pass.

- [ ] **Step 7: Commit**

```bash
cd backend && git add \
  src/main/java/com/finora/integrations/google/merchant/CredEmailParser.java \
  src/main/resources/application.yml \
  src/test/resources/gmail/cred/ \
  src/test/java/com/finora/integrations/google/merchant/CredEmailParserTest.java
git commit -m "feat(backend): add CredEmailParser, verified against real Gmail data"
```

---

### Task 5: `PaytmEmailParser` — deliberate scaffold, no verified pattern

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/com/finora/integrations/google/merchant/PaytmEmailParser.java`
- Create: `backend/src/test/java/com/finora/integrations/google/merchant/PaytmEmailParserTest.java`

**Interfaces:**
- Consumes: `MerchantEmailParser`/`ParserResult` (existing, unchanged).
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Add the config gate default**

In `backend/src/main/resources/application.yml`, extend the block from Task 4:

```yaml
      parsers:
        phonepe:
          enabled: ${GMAIL_PARSER_PHONEPE_ENABLED:false}
        cred:
          enabled: ${GMAIL_PARSER_CRED_ENABLED:false}
```

change to:

```yaml
      parsers:
        phonepe:
          enabled: ${GMAIL_PARSER_PHONEPE_ENABLED:false}
        cred:
          enabled: ${GMAIL_PARSER_CRED_ENABLED:false}
        paytm:
          enabled: ${GMAIL_PARSER_PAYTM_ENABLED:false}
```

- [ ] **Step 2: Write the failing test**

Create `backend/src/test/java/com/finora/integrations/google/merchant/PaytmEmailParserTest.java`:

```java
package com.finora.integrations.google.merchant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase C5 follow-up, 2026-08-22. Unlike {@link PhonePeEmailParserTest}/{@link CredEmailParserTest},
 * there is no real-shaped fixture here — no per-transaction Paytm receipt email was found across
 * 30 real threads reviewed (see docs/proposals/gmail-merchant-template-admin-ui-proposal.md's
 * 2026-08-22 update). This class exists only because the project owner explicitly chose to keep a
 * scaffold in case such mail surfaces later; every path fails closed, proven here with an
 * arbitrary body rather than a claimed-real one.
 */
class PaytmEmailParserTest {

    private final MerchantEmailSanitizer sanitizer = new MerchantEmailSanitizer();
    private final PaytmEmailParser parser = new PaytmEmailParser();

    @BeforeEach
    void enableParser() {
        ReflectionTestUtils.setField(parser, "enabled", true);
    }

    @Test
    void canParseOnlyClaimsPaytmsAuthenticatedDomainWhenEnabled() {
        assertThat(parser.canParse("paytm.com")).isTrue();
        assertThat(parser.canParse("paytm.attacker.example")).isFalse();
        assertThat(parser.canParse("phonepe.com")).isFalse();
    }

    @Test
    @DisplayName("disabled by default -- canParse is false until the config property is set")
    void canParseIsFalseWhenNotExplicitlyEnabled() {
        PaytmEmailParser disabledParser = new PaytmEmailParser();

        assertThat(disabledParser.canParse("paytm.com")).isFalse();
    }

    @Test
    @DisplayName("every message is malformed, not implemented -- no real pattern exists yet")
    void everyMessageIsReportedMalformed() {
        SanitizedGmailMessage anyMessage = sanitizer.sanitize(
                "msg-1", "paytm.com", "<p>Any content at all.</p>");

        ParserResult result = parser.parse(anyMessage);

        assertThat(result.status()).isEqualTo(ParserResult.Status.MALFORMED);
        assertThat(result.receipt()).isNull();
        assertThat(result.reason()).contains("no verified extraction pattern");
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=PaytmEmailParserTest`
Expected: FAIL to compile — `PaytmEmailParser` does not exist yet.

- [ ] **Step 4: Implement `PaytmEmailParser`**

Create `backend/src/main/java/com/finora/integrations/google/merchant/PaytmEmailParser.java`:

```java
package com.finora.integrations.google.merchant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A deliberate exception to this codebase's usual "don't build ahead of evidence" rule —
 * scaffolded per the project owner's explicit decision despite zero real Paytm transactional
 * email found across 30 real threads reviewed (marketing, gift cards, monthly statements,
 * wallet-inactive nags, Paytm's own direct bookings — no "paid to X, successful" shape anywhere).
 * See docs/proposals/gmail-merchant-template-admin-ui-proposal.md's 2026-08-22 update.
 *
 * <p>Every message is reported {@link ParserResult.Status#MALFORMED} rather than guessing a
 * pattern the way V103's SQL guess did — there is nothing to extract correctly yet, and a wrong
 * guess that happened to match something would be a worse outcome than an honest "not implemented"
 * signal. Config-gated the same as {@link PhonePeEmailParser}/{@link CredEmailParser} on {@code
 * app.integrations.google.parsers.paytm.enabled} (default false); off by default means this never
 * runs in production regardless.
 */
@Component
public class PaytmEmailParser implements MerchantEmailParser {

    private static final String DOMAIN = "paytm.com";

    @Value("${app.integrations.google.parsers.paytm.enabled:false}")
    private boolean enabled;

    @Override
    public boolean canParse(String authenticatedDomain) {
        return enabled && DOMAIN.equals(authenticatedDomain);
    }

    @Override
    public ParserResult parse(SanitizedGmailMessage message) {
        return ParserResult.malformed("PaytmEmailParser has no verified extraction pattern yet -- "
                + "no real Paytm transactional email has been confirmed to exist; see "
                + "docs/proposals/gmail-merchant-template-admin-ui-proposal.md's 2026-08-22 update");
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=PaytmEmailParserTest`
Expected: BUILD SUCCESS, all 3 tests pass.

- [ ] **Step 6: Commit**

```bash
cd backend && git add \
  src/main/java/com/finora/integrations/google/merchant/PaytmEmailParser.java \
  src/main/resources/application.yml \
  src/test/java/com/finora/integrations/google/merchant/PaytmEmailParserTest.java
git commit -m "feat(backend): scaffold PaytmEmailParser pending real sample verification"
```

---

### Task 6: Drop the wrongly-modeled `merchant_templates` rows

**Files:**
- Create: `backend/src/main/resources/db/migration/V<N>__drop_p2p_merchant_templates.sql` (see Step 1 for how `<N>` is determined)
- Modify: `backend/src/test/java/com/finora/integrations/google/merchant/AdminMerchantTemplateEndpointIT.java:124-131`

**Interfaces:**
- Consumes: nothing from earlier tasks (independent data cleanup) — sequenced after Tasks 3–5 only because it is the natural "so the old dead rows can go" follow-on once real parsers exist for two of the three domains.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Re-verify the next free migration version against `origin/main`**

Run:
```bash
git fetch origin
git log --oneline HEAD..origin/main -- backend/src/main/resources/db/migration
find backend/src/main/resources/db/migration -iname "V*.sql" | grep -oE 'V[0-9]+' | sed 's/V//' | sort -n | tail -3
```

If `origin/main` has new commits touching the migration directory, merge them into this branch first (`git merge origin/main`) and re-run the version check. As of this plan being written, the next free version is **V105** — but treat that as provisional; use whatever number the check above actually shows is free at execution time, and use that number consistently for the filename and every reference to it in this task.

- [ ] **Step 2: Write the migration**

Create `backend/src/main/resources/db/migration/V105__drop_p2p_merchant_templates.sql` (rename if Step 1 found a different free number):

```sql
-- V103 seeded phonepe.com/paytm.com/cred.club as declarative merchant_templates rows (guessed
-- patterns, enabled=false) alongside 47 legitimate single-shape merchants. Real Gmail
-- verification (2026-08-22, see docs/proposals/gmail-merchant-template-admin-ui-proposal.md's
-- dated update) found the guessed patterns wrong AND, more fundamentally, that the declarative
-- template model itself does not fit these three domains: TemplateEmailParser.canParse/parse
-- assumes the sender domain IS the merchant, but PhonePe/Paytm/CRED are payment-relay or
-- bill-payment notifications where the real counterparty is embedded in the body (or, for
-- paytm.com, no per-transaction receipt email was found to exist at all). PhonePeEmailParser and
-- CredEmailParser (hand-written, config-gated) now cover the first two; paytm.com is intentionally
-- left with no verified parser.
--
-- Deleting these 3 rows rather than leaving them disabled: MerchantTemplateAdminService's
-- hand-written-parser collision guard (409 on create) only protects a domain a Java parser
-- already claims -- it does not retroactively guard rows seeded before that parser existed. An
-- admin working the V103 readiness-seed backlog through the Merchant Templates admin UI could
-- otherwise "fix" phonepe.com's pattern strings and activate it, silently reproducing the exact
-- wrong-merchant-attribution bug this migration exists to prevent. gmail_trusted_sender_domains
-- rows for all three are untouched -- domain trust is still correct, only the declarative-template
-- model was wrong for them.
DELETE FROM merchant_templates
WHERE merchant_domain IN ('phonepe.com', 'paytm.com', 'cred.club');
```

- [ ] **Step 3: Update the test that currently expects `phonepe.com` to still have a template row**

In `AdminMerchantTemplateEndpointIT.java`, change:

```java
    @Test
    void theReadinessSeedTemplatesAreDisabledByDefault() {
        for (String domain : new String[]{"swiggy.com", "flipkart.com", "irctc.co.in",
                                          "phonepe.com", "netflix.com", "airtel.in", "hdfcergo.com"}) {
            assertThat(templates.findByMerchantDomain(domain))
                    .as("%s should be seeded by V103, disabled pending a real test", domain)
                    .isPresent()
                    .get()
                    .matches(t -> !t.isEnabled(), "disabled");
        }
    }
```

to:

```java
    @Test
    void theReadinessSeedTemplatesAreDisabledByDefault() {
        for (String domain : new String[]{"swiggy.com", "flipkart.com", "irctc.co.in",
                                          "netflix.com", "airtel.in", "hdfcergo.com"}) {
            assertThat(templates.findByMerchantDomain(domain))
                    .as("%s should be seeded by V103, disabled pending a real test", domain)
                    .isPresent()
                    .get()
                    .matches(t -> !t.isEnabled(), "disabled");
        }
    }

    /** V105 removed phonepe.com/paytm.com/cred.club from this table entirely -- the declarative
     *  template model cannot represent a counterparty distinct from the domain, so these three now
     *  have no merchant_templates row at all (PhonePeEmailParser/CredEmailParser cover two of them
     *  as hand-written, config-gated parsers instead; paytm.com is intentionally unparsed). Their
     *  gmail_trusted_sender_domains rows are untouched -- this checks only the template half. */
    @Test
    @DisplayName("phonepe/paytm/cred have no merchant_templates row -- the declarative model was wrong for them")
    void theP2PDomainsHaveNoTemplateRow() {
        for (String domain : new String[]{"phonepe.com", "paytm.com", "cred.club"}) {
            assertThat(templates.findByMerchantDomain(domain))
                    .as("%s should have been removed by V105", domain)
                    .isEmpty();
        }
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=AdminMerchantTemplateEndpointIT`
Expected: BUILD SUCCESS. (This is an integration test against a real database per this class's own existing convention — same as every other test in this file.)

- [ ] **Step 5: Commit**

```bash
cd backend && git add \
  src/main/resources/db/migration/V105__drop_p2p_merchant_templates.sql \
  src/test/java/com/finora/integrations/google/merchant/AdminMerchantTemplateEndpointIT.java
git commit -m "fix(db): drop wrongly-modeled phonepe/paytm/cred merchant_templates rows"
```

(Adjust the filename in the `git add` above if Step 1 found a version other than V105.)

---

### Task 7: Full-suite verification

**Files:** none (verification only, no commit).

**Interfaces:**
- Consumes: everything from Tasks 1–6.
- Produces: nothing (terminal task).

- [ ] **Step 1: Run the full backend test suite**

Run: `cd backend && ./mvnw test`
Expected: BUILD SUCCESS — every test in the module passes, including the ones this plan didn't directly touch (confirming the `ParsedReceipt` signature change didn't silently break something outside the `merchant` package, e.g. any other place a Gmail receipt might be constructed).

- [ ] **Step 2: Confirm nothing is unintentionally live**

Run:
```bash
grep -rn "GMAIL_PARSER_PHONEPE_ENABLED\|GMAIL_PARSER_CRED_ENABLED\|GMAIL_PARSER_PAYTM_ENABLED" backend/src/main/resources/application-prod.yml backend/src/main/resources/application-dev.yml backend/src/main/resources/application-test.yml
```
Expected: no output — none of the three profile-specific config files should set any of these env vars, confirming all three parsers stay off (default `false`) in every environment. If any output appears, stop and check with the project owner before proceeding — that would mean a parser is being turned on as an unplanned side effect of this change, not a deliberate later step.

- [ ] **Step 3: Report status**

No commit for this task. Report to the project owner: full suite green, all three parsers confirmed off in every profile, ready for a PR covering Tasks 1–6's six commits.
