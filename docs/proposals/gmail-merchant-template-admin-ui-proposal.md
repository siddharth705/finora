# Admin UI for Gmail Merchant Templates

**Status:** Implemented (branch `feat/admin-merchant-templates`). Related to, but not part of,
the `gmail-intelligence-platform-proposal.md` C6 scope (that document's C6.2, "Merchant Parser
Monitoring," was already built as `GmailMerchantStatsService`/`MerchantIntelligence.tsx` before
this work started — see below).

**2026-08-22 update — readiness seed.** V103 trusted 50 more merchant domains (ACTIVE) and
scaffolded 50 matching `MerchantTemplate` rows, spanning food delivery, e-commerce, travel,
payments, OTT, telecom, and insurance. Every pattern is a **best-guess based on common receipt
conventions, not verified against a real sample email** — this is exactly the "explicitly out of
scope" coverage-expansion follow-up this document called out below, done now that the admin
tooling exists to do it safely. All 50 templates are seeded `enabled = false`; none can stage
anything until an admin runs each one through the test panel and activates it individually. See
`V103__merchant_readiness_seed.sql`'s own comment for the full reasoning, including why trusting
the domain and enabling the template were deliberately kept as two separate decisions.

## Context

Finora's Gmail receipt-sync pipeline supports 6 merchants: 4 hand-written Java parsers (Amazon,
Myntra, Ola, Booking.com) and 2 declarative "template" parsers stored as rows in
`merchant_templates` (Uber, Zomato) — an experiment (V85/V86) to see whether a DB-editable
amount/date pattern extracts as reliably as hand-written code. It does. But `merchant_templates`
had **zero admin API or UI** — every template was added via a Flyway migration + backend deploy.
Meanwhile, `MerchantIntelligence.tsx` already shows admins exactly which trusted domains have no
parser and how many emails are piling up unparsed (`noParserYet`) — the signal for what to add next
existed, but there was no way to act on it without an engineering release.

This closes that gap: an admin can add a new template through the UI, **test it against a pasted
sample email before it can go live** (a wrong template silently mis-stages a wrong amount into a
real user's financial ledger — this must never be a blind CRUD screen), then activate it. No
backend deploy needed for the "easy" merchant case (single amount, single date, stable format).
Merchants needing conditional logic (refunds, multiple amounts — like Myntra/Booking.com) still
need a hand-written parser; that split is unchanged.

Explicitly out of scope: adding any *specific* new merchants (Swiggy, Blinkit, etc.). This ships
the pipeline only — coverage expansion is separate follow-up work now that this exists.

## Design decisions

- **Reuse the real parsing code for the test sandbox, don't reimplement it.**
  `TemplateEmailParser.parse(SanitizedGmailMessage, MerchantTemplate)` (`private` → package-private)
  is the exact matching logic the real pipeline runs at 3am. `MerchantTemplateTestRunner` calls it
  directly against a throwaway, never-persisted `MerchantTemplate` — zero drift between "what the
  admin tested" and "what actually runs." Mirrors an existing precedent one feature over:
  `AdminRuleController.POST /rules/test` → `RuleEngineService.testMatch()` (a transient
  `CategoryRule probe` through the same private `matches()` real evaluation uses).

- **`MERCHANT_MANAGE`, not `SYSTEM_SETTINGS`.** `gmail_trusted_sender_domains` (who Finora trusts)
  is the real security boundary and stays `SYSTEM_SETTINGS`-gated (`AdminTrustedSenderController`).
  A template for a domain that was never trusted is simply unreachable —
  `GmailReceiptExtractionService` only ever runs parsers on messages already marked
  `DETECTED_NOT_STAGED`, which requires having already passed `SenderAuthenticationService`. So
  `merchant_templates` is a data-quality surface, not a trust surface, and
  `AdminMerchantStatsController` (the `noParserYet` dashboard this feature is the action-arm of)
  is already gated `MERCHANT_MANAGE`, held by `SUPPORT` too (V31, "fix their merchants/rules").
  Gating the new controller `SYSTEM_SETTINGS` would leave the role that can see the problem unable
  to fix it.

- **Domain immutable on update, but pattern fields are genuinely editable in place** — unlike
  `TrustedSenderDomainService.rename` (label-only), a merchant changing their email template and a
  parser's success rate silently collapsing is exactly the case this needs to fix without creating
  a new row.

- **Editing a live template's matching fields auto-disables it.** Otherwise an admin could `PUT` an
  untested fix onto an *active* template and have it go live immediately, defeating the whole
  "must be tested before it runs" premise. Editing only `merchantName` doesn't trigger this.

- **Reject on creation if a hand-written parser already claims the domain.** No `@Order` exists
  anywhere in the `merchant` package, and `GmailReceiptExtractionService.extractFor` picks the
  first parser whose `canParse()` returns true — a template for a domain like `amazon.in` would
  otherwise create undocumented nondeterminism between the two parsers.

- **The test-sandbox also runs `ParsedReceiptValidator`.** A syntactically-matched result can still
  carry an implausible amount or a future date — surfaced at test time, for free.

- **`enabled=false` forced on creation** (server ignores any client-sent value), with a separate,
  audited `POST /{id}/activate` — mirrors `TrustedSenderDomainService.setStatus`'s "re-enabling is
  a deliberate, separate, audited act." Never a hard delete — deactivate only, matching
  `TrustedSenderDomain`'s "who decided this and when must stay answerable" reasoning.

- **Informational `domainIsTrusted` flag** on the template DTO — since there is still no
  trusted-sender admin frontend page, the UI can warn that a correctly-tested template won't run in
  production until its domain is also in the trust registry.

- **Known follow-up, not blocking:** `GmailReviewService.DISPLAY_NAMES` is a separate hardcoded
  `Map.of(...)` for review-queue/stats display names. A template for a genuinely new (7th+) domain
  parses and stages correctly but shows as a raw domain string in the review queue until that map
  gets a one-line addition.

## What was built

### Backend

- `V102__merchant_template_admin_audit.sql` — adds `merchant_templates.created_by_user_id`,
  mirroring `gmail_trusted_sender_domains.added_by_user_id`.
- `MerchantTemplate.java` — `createdByUserId` field; setters now maintain `updatedAt` (`touch()`),
  matching `TrustedSenderDomain`'s pattern — this table had no admin mutation path before.
- `TemplateEmailParser.java` — `parse(SanitizedGmailMessage, MerchantTemplate)` dropped from
  `private` to package-private so the test sandbox can call it directly.
- `MerchantTemplateRepository.java` — `findByMerchantDomain` (any status, for conflict checks),
  `findAllByOrderByMerchantNameAscMerchantDomainAsc` (admin list view).
- `MerchantTemplateAdminService.java` — create/update/activate/deactivate, all audited via
  `AuditService`; eager pattern-compilation validation; hand-written-parser collision guard;
  auto-disable-on-live-edit.
- `MerchantTemplateTestRunner.java` — the dry-run sandbox; builds a throwaway `MerchantTemplate`,
  sanitizes the pasted sample via `MerchantEmailSanitizer` (the only way to produce a
  `SanitizedGmailMessage`), runs the real `TemplateEmailParser.parse`, and also runs
  `ParsedReceiptValidator` on a `PARSED` result.
- `AdminMerchantTemplateController.java` — `/api/v1/admin/merchant-templates`
  (`GET`/`POST`/`PUT /{id}`/`POST /{id}/activate`/`POST /{id}/deactivate`/`POST /test`), gated
  `MERCHANT_MANAGE`.
- Tests: `MerchantTemplateAdminServiceTest`, `MerchantTemplateTestRunnerTest` (the key correctness
  proof — cross-checked against the real Uber fixture and
  `TemplateEmailParserTest.shouldParseUberTripReceipt`'s own expected amount/date),
  `AdminMerchantTemplateEndpointIT` (auth boundary + a real create→test→activate pass against
  Postgres).

### Admin portal

- `types/index.ts`, `api/endpoints.ts` — new DTOs and `adminMerchantTemplatesApi`.
- `pages/MerchantTemplates.tsx` — list + create form + edit drawer, with a `TestTemplatePanel`
  (shaped after `GlobalRules.tsx`'s `TestRulePanel`) always testing whatever's currently typed,
  saved or not. Activate is only enabled in the UI once a test has succeeded against the current
  field values in that session.
- `App.tsx`, `Sidebar.tsx` — new route and nav entry (`/merchant-templates`, `MERCHANT_MANAGE`).

## Verification

- Backend: `MerchantTemplateAdminServiceTest`, `MerchantTemplateTestRunnerTest`,
  `AdminMerchantTemplateEndpointIT`, plus the existing `TemplateEmailParserTest`/`MerchantTemplateTest`
  — all green. Full `./mvnw test` run before merge.
- Admin portal: `MerchantTemplates.test.tsx`, full `npm test`, `tsc --noEmit`, `eslint --max-warnings 0`.
- Manual: create a template for a new domain → test against a real sample → confirm amount/date →
  confirm Activate is disabled until a test succeeds → activate → confirm it shows correctly.
- Confirmed the parser-collision guard: creating a template for `amazon.in` is rejected (409).

## 2026-08-22 update — P2P/payment-relay counterparty parsing (design approved, not yet implemented)

**Why:** verifying `phonepe.com`'s V103 template row against real Gmail data (read-only,
snippet-level, via Claude's Gmail connector) surfaced two problems. First, the guessed pattern
strings don't match — real PhonePe mail uses `Txn. status : Successful` as its marker and
`Paid to <name> ₹<amount>` for the amount, not the `'Payment Successful'` / `'Amount Paid: Rs.
{amount}'` guess V103 seeded. Second, and structurally bigger: PhonePe is a payment-relay app, not
a merchant in the sense every existing parser assumes — the real counterparty (who the money went
to) is embedded in the email body, not identifiable from the sender domain. Fixing only the
pattern strings would still label every parsed transaction as coming from "PhonePe" itself, a
wrong-merchant-attribution bug, not just a wrong-pattern one. `paytm.com`/`cred.club` got the same
guessed shape in V103 and were flagged for the same check.

**A pasted second-opinion proposal** suggested generalizing this into a much larger contract
redesign — rename `merchantDomain`→`sourceDomain`, add a `ReceiptSourceType` enum, rename
`MerchantEmailParser`→`FinancialEmailParser`, per-field confidence, a new `ExtractionStatus` with
partial-success states, and percentage-based rollout. Evaluated and declined: most of it
re-litigates decisions this codebase already made deliberately and documents in its own code —
`ParsedReceipt`'s class doc is explicit that confidence "exists to be displayed, never to gate
automatic creation," and nothing downstream auto-applies a receipt without human review regardless
of confidence, so field-level confidence and a partial-success status have no consumer. The
speculative taxonomy (`ReceiptSourceType`) turned out to be actively wrong once real data came in
(see below — CRED isn't "PAYMENT_AGGREGATOR" shaped at all), which is the risk of deciding a
taxonomy ahead of evidence rather than after it. One idea is worth recording as considered-and-
deferred: letting the declarative `merchant_templates` model capture a `counterpartyPattern` too,
so simple counterparty-in-body cases wouldn't need a hand-written parser. Not worth building for
1–2 merchants; revisit if templating counterparty extraction is ever needed at real scale.

**Real Gmail verification (2026-08-22), same connector, read-only, snippet-level only:**

- **`phonepe.com` — fully confirmed, 5 consistent real instances spanning 2019–2024.** Marker,
  counterparty+amount capture, and the date line (unlabeled, abbreviated-month format like "Dec 6,
  2024") are all now real-shape-verified. `ReceiptDateFormats` doesn't parse that abbreviated-month
  format yet (it has full-month `MMMM d, yyyy` but not `MMM d, yyyy`) — one line added there.
  Counterparty values seen include both individuals and small local businesses, confirming it's
  genuinely free text, not a closed set worth constraining.
- **`cred.club` — real shape found, and it is *not* a P2P transfer.** CRED's actual transactional
  email is a credit-card-bill-payment confirmation: bank name + masked card, an amount, a payment
  date — 3 consistent real instances across different banks. Its "counterparty" is a bounded string
  like `<Bank Name> •••• <last4>`, not an arbitrary name — structurally distinct from PhonePe, which
  is exactly why the pasted proposal's premature `ReceiptSourceType` taxonomy (which assumed CRED
  was "PAYMENT_AGGREGATOR"-shaped like PhonePe) would have been wrong. Most real `cred.club` mail in
  this inbox is two other recurring, receipt-adjacent shapes where no money has moved yet — a "bill
  generated" notice and a "payment due" reminder — both must resolve to `NOT_A_RECEIPT`; real
  examples of both exist to build fixtures from.
- **`paytm.com` — no evidence a per-transaction receipt email exists at all.** 30 real threads
  reviewed across broad queries (payment/successful/UPI/debited/wallet, plus `paytmbank.com`) turned
  up only marketing, gift-card fulfillment, monthly UPI/wallet statements (PDF attachment, not
  per-transaction), wallet-inactive nags, and Paytm's own direct bookings (movie/train tickets —
  which fit the *original* domain-is-merchant model fine). No "paid to X, successful" shape
  anywhere. Building a parser against zero evidence would repeat V103's original mistake in code
  instead of SQL — flagged explicitly to the project owner, who chose to keep a scaffold-only
  `PaytmEmailParser` anyway as a deliberate hedge in case such mail surfaces later for some user;
  every path fails closed and it stays config-gated off, so the cost of being wrong about it
  existing is zero.

**Design:**

1. **`ParsedReceipt` gets one new nullable field, `counterpartyName`.** Additive to the record —
   every existing parser (`AmazonEmailParser`, `MyntraEmailParser`, `OlaEmailParser`,
   `BookingEmailParser`, `TemplateEmailParser`) passes `null`, meaning "no counterparty distinct
   from the merchant," exactly what they mean today. No parallel `ParsedP2PReceipt` type — matches
   `GmailStagingBridge`'s own "reuse, not a parallel system" principle, and keeps
   `GmailReceiptExtractionService`'s single-dispatch loop and `ParsedReceiptValidator` unchanged.

2. **`PhonePeEmailParser` and `CredEmailParser`**, structured like `AmazonEmailParser`, both fully
   built against their real, verified shapes above — including CRED's two `NOT_A_RECEIPT` guard
   fixtures so a bill notice or due reminder is never mistaken for a completed payment.
   **`PaytmEmailParser`** is scaffolded the same way structurally but with no real pattern behind
   it — every case reports `MALFORMED`/not-yet-implemented, an explicit, deliberate exception to
   this codebase's usual "don't build ahead of evidence" discipline, made knowingly by the project
   owner rather than by guessing a shape.

3. **A rollout-safety gap this introduces, and its fix.** Unlike `merchant_templates`
   (`enabled=false` is a real per-row kill switch `TemplateEmailParser.canParse` checks), a
   hand-written parser has no equivalent — `canParse` is normally just a domain string match, live
   for every user the moment it deploys. Each of the three new parsers instead gates on a config
   property (`app.gmail-parsers.<merchant>.enabled`, default `false`, same pattern as
   `app.admin-mfa.enabled`), so merging the code doesn't make it live even though PhonePe and CRED
   are already real-shape-verified — activation stays a deliberate, separate flip, mirroring the
   admin Merchant Templates test-then-activate discipline without building a parallel admin surface.

4. **`GmailStagingBridge.descriptionFor`/`fileNameFor`** prefer `counterpartyName` when present
   (falls back to `merchantDomain` otherwise) — today they echo the raw domain string
   (`"phonepe.com"`) as the transaction description, which is wrong regardless of the
   attribution issue. `GmailReconciliationMatcher` keeps matching on `merchantDomain` — bank-line
   reconciliation isn't counterparty-aware for any merchant today, and extending that is out of
   scope here.

5. **A new migration deletes the 3 now-dead `merchant_templates` rows** for `phonepe.com`,
   `paytm.com`, `cred.club` seeded by V103 (V103 itself is never edited, per this repo's
   migration-history rule). `gmail_trusted_sender_domains` rows for all three stay untouched —
   domain trust is still correct, only the declarative-template model is wrong for them. This is
   hygiene, not the only safety net: `MerchantTemplateAdminService`'s existing hand-written-parser
   collision guard (409 on create) already blocks recreating a template for a domain a Java parser
   claims, once these parsers exist — but today, before they ship, nothing stops an admin from
   "fixing" `phonepe.com`'s pattern strings and activating the wrong model, so removing the rows
   closes that window rather than relying on it staying unnoticed.

**Scope of the implementation PR:** the `ParsedReceipt` contract change (mergeable immediately,
zero behavior change for existing parsers), `PhonePeEmailParser` and `CredEmailParser` fully built
against confirmed real shapes (config-gated off), `PaytmEmailParser` scaffolded-only per the
project owner's explicit call (config-gated off), the `ReceiptDateFormats` addition, the
staging-bridge description fix, and the cleanup migration. Flipping any of the three config
properties on in production is deliberately a separate, later step — not part of this PR.
