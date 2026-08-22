# Admin UI for Gmail Merchant Templates

**Status:** Implemented (branch `feat/admin-merchant-templates`). Related to, but not part of,
the `gmail-intelligence-platform-proposal.md` C6 scope (that document's C6.2, "Merchant Parser
Monitoring," was already built as `GmailMerchantStatsService`/`MerchantIntelligence.tsx` before
this work started — see below).

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
