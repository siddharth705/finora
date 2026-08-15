# Gmail Intelligence Platform (C6) — Design Proposal

**Status:** Design only. **Nothing here is implemented.** D-15/D-16 (`docs/project-management/plans/project-plan-v1.0.md`)
hold all of C6 for post-GA — this document is the scoping work product D-16 asked for, produced
*because* implementation is deliberately not starting yet, not a signal that it's about to.

**What C6 originally was.** After C5.4 shipped (Gmail connection UI + review queue, PR #122), a
seven-part "Gmail Intelligence Platform" was proposed: connection/review UX, a merchant monitoring
dashboard, unknown-merchant learning, cross-source reconciliation, notifications, Google OAuth app
verification, and premium positioning. Audited against what already exists in this repo before
accepting any of it as new scope (§8a's "audit before vision" rule) — three of the seven pieces
turned out to already have a home:

| # | Original C6 piece | Disposition |
|---|---|---|
| C6.1 | Connection UI, review queue | ✅ **Done.** Shipped as C5.4 (PR #122) before this document existed. |
| C6.2 | Merchant monitoring dashboard | Designed here — §1. Genuinely new, not an extension (see correction below). |
| C6.3 | Unknown-merchant learning | Designed here — §2. Genuinely new. |
| C6.4 | Cross-source reconciliation | Designed here — §3. Genuinely new. |
| C6.5 | Notifications | Already fully designed: `notification-communication-platform-proposal.md`. Not touched here. |
| C6.6 | Google OAuth app verification | **Reclassified.** Not a premium feature — a launch requirement the moment Gmail sync has real users (Google requires app verification for `gmail.readonly` beyond a small test-user allowlist). Belongs on the launch-blocker critical path (§5's Phase 5 / store-review track), not the C6 backlog. |
| C6.7 | Premium positioning / billing | Already fully designed: `billing-subscription-entitlements-proposal.md`. Not touched here. |

**Correction to D-15's own note.** D-15 (2026-08-15) guessed C6.2 "likely extends the existing
`AdminStatementAnalysisController.summary()` pattern rather than needing new architecture." Checked
before designing it (not assumed): that's not quite right. `AdminStatementAnalysisController` is
scoped to CSV/PDF statement analysis specifically, and its summary is computed **in memory** over
the last 500 rows because its reason histogram is stored as JSON per row, not a database column —
by the class's own comment, deliberately, since "no `GROUP BY` can add it up in the database." A
Gmail-merchant breakdown needs its own grouped query and its own small controller. Not a large
build (§1 below is short), but it's new code, not a config change to existing code. Recorded here
so whoever builds this doesn't start from a wrong premise.

---

## 1. C6.2 — Merchant Parser Monitoring

**The problem, in the original framing:** a merchant changes their email template, a parser's
success rate silently collapses, and Finora finds out when a user complains rather than before.

**What exists today.** `GmailProcessedMessageRepository` has `countByConnectionId` and
`countByConnectionIdAndOutcome` — both scoped to one connection (one user's mailbox), because that's
all C5.4's connection-status panel needed. Nothing aggregates *across* connections by merchant
domain. The one existing precedent for "count grouped by X" in this codebase is
`StatementAnalysisSessionRepository.failureCodeLayoutCounts(since)` — a real `GROUP BY` query
returning `(code, fingerprint, count, lastSeen)` rows, aggregated further in Java. That's the
pattern to copy, not `AdminStatementAnalysisController`'s own in-memory approach.

**Design:**
- A new repository method, `GmailProcessedMessageRepository.outcomeCountsByDomain(since)`, doing
  `GROUP BY authenticated_domain, outcome` — the same shape as `failureCodeLayoutCounts`, over
  `gmail_processed_messages` instead of `statement_analysis_sessions`.
- A new admin controller (`AdminGmailMerchantStatsController` or similar — doesn't belong on
  `AdminStatementAnalysisController`, wrong domain) exposing per-merchant totals: processed, parsed,
  malformed, not-a-receipt, success rate, trend over a window (day/week).
- The alerting half of the original pitch ("Finora should know before users complain") is a second,
  separable decision: a threshold-crossing check (e.g. a merchant's 7-day success rate drops below
  some floor) feeding into whatever alerting channel Phase 5 (production monitoring hardening)
  ends up choosing. Not designed further here — it depends on infrastructure decisions (Sentry?
  something else?) that Phase 5 owns, not C6.

**Scope check:** this is the smallest of the three genuinely-new pieces, has no open product
questions, and is a pure extension pattern already proven elsewhere in the codebase. Recommended
first when C6 unfreezes (§6).

---

## 2. C6.3 — Unknown-Merchant Learning

**The problem, in the original framing:** a trusted-domain sender Finora doesn't have a parser for
today produces nothing — no receipt, no prompt, no signal to the user or to Finora that support is
missing.

**The key finding, checked before designing anything:** this state already exists in the data
model and has been accumulating, inertly, since C4. `GmailProcessedMessage.Outcome.DETECTED_NOT_STAGED`
is *exactly* "trusted sender, no `MerchantEmailParser` claims this domain" — its own doc comment
says so. `GmailReceiptExtractionService.extractFor()` already surfaces this as `noParser` in its
`ExtractionResult` and deliberately leaves the row at `DETECTED_NOT_STAGED` rather than advancing
it, precisely so that a parser shipped later can retroactively pick these messages up. **C6.3 does
not need a new state or a new detection mechanism — every trusted-but-unsupported message Finora
has ever seen is already sitting there, identifiable by domain, waiting for a consumer.** The work
is building that consumer.

**What C6.3 is NOT, and why.** The original pitch's own example —

```
User receives: invoice@localgym.com, ₹2,000 membership
Finora asks: "Is this a transaction?"
```

— implies Finora reads and offers up *any* incoming email for confirmation, sender unknown or not.
That contradicts C3's own design by construction: `GmailMessageDiscoveryService` fetches **headers
only**, runs the sender-trust gate on those headers alone, and never calls `gmail.getMessageBody()`
for a sender that fails the gate — confirmed by reading `GmailMessageDiscoveryService.examine()` and
`GmailReceiptExtractionService.processOne()` directly, not assumed. A message from `localgym.com`
never reaches this pipeline at all unless `localgym.com` is *already* in the admin-curated trusted-
sender registry (C3.3's own management endpoint). Widening that — reading body content from
arbitrary, non-vetted senders to decide "is this a transaction" — is the exact spoofing-resistance
property C3 exists to prevent, and reopening it for C6.3 is not in scope of anything decided so far.
**C6.3, as designed here, only ever surfaces messages from senders an admin has already trusted.**
The real gap it closes is narrower than the original pitch: "a domain we already trust doesn't have
a parser yet" (Finora's own coverage gap), not "an arbitrary sender might be a receipt" (a different,
much bigger feature nobody has asked for).

**Design:**
1. **Surface the backlog.** A new admin view listing `DETECTED_NOT_STAGED` messages grouped by
   domain — volume first (which unsupported domains generate the most trusted mail), not
   alphabetical. This alone is useful: it tells Finora which of the ~dozens of trusted domains in
   the registry are worth building a parser for, replacing guesswork with actual demand data.
2. **Template creation stays admin-reviewed, not self-service, and this is a deliberate security
   call, not an oversight.** `MerchantTemplate` rows are global — one template serves every user
   whose mailbox has that domain trusted, seeded today only via Flyway migration. A self-service
   "user confirms → template goes live immediately" flow would let one user's careless or malicious
   confirmation mis-parse (or, worse, plausibly-mis-parse) every other user's mail from that domain.
   The right shape: a user (or admin, reviewing the domain-grouped backlog from step 1) proposes a
   template from a real sample message; it goes into a **pending** state; an admin approves before
   it's live. This mirrors, structurally, the existing `MerchantLearningEvent` /
   `MerchantLearningEventWorker` shape (`entity/MerchantLearningEvent.java` +
   `service/MerchantLearningEventWorker`) — a durable queue row written in the triggering
   transaction, applied asynchronously with retry/backoff, an admin-visible resolve/retry surface —
   which is the closest existing precedent for "someone proposes something, the system acts on it
   later, admin can intervene." **Reused: the queue/worker/retry infrastructure shape. Not reused:**
   the actual template-synthesis logic (turning one email's marker text and amount/date positions
   into a `MerchantTemplate.amountPattern`/`datePattern` pair) doesn't exist anywhere and has to be
   designed as its own piece of work. `V85__merchant_templates.sql`'s own comment describes the
   current row-per-merchant, edited-by-hand-via-INSERT/UPDATE shape as deliberately minimal, "until
   there is evidence templating is worth an admin surface" — C6.3's admin-approval queue (step 2) is
   that evidence-gathering surface. Whether the admin fills in the pattern by hand from a rendered
   sample, or the system proposes one for the admin to confirm, is an implementation choice for
   whoever builds this, not resolved here.
3. **What the user actually sees**, distinct from the admin side: a `DETECTED_NOT_STAGED` message
   from a domain with no template yet gets no prompt in v1 of this design — there's nothing useful
   to ask them ("is this a receipt?" when Finora already knows it's from a trusted financial sender
   and just can't extract amount/date yet answers a question the user didn't have). The user-facing
   payoff arrives once an admin ships the template: the backlog of already-`DETECTED_NOT_STAGED`
   messages for that domain becomes extractable **retroactively**, the same mechanism that already
   lets Uber's C5.2 template pick up mail discovered before the template existed.

**Open product question, not decided here:** should step 3 eventually invite the user into the loop
directly (e.g. "we don't recognize this yet — want to help us add support?") once there's a trust
story for what a user-submitted sample can and can't do? Left open deliberately — designing that
UX now, before any real backlog data exists to say which domains are worth it, would be designing
against a guess.

---

## 3. C6.4 — Cross-Source Reconciliation

**The problem, in the original framing:** an Amazon Gmail receipt and its corresponding
`AMZN MKTPLACE` bank-statement line are the same purchase, described two different ways, and today
nothing connects them — both can land in the ledger as separate transactions.

**What exists today, and why none of it is a drop-in fix.** Two separate mechanisms already handle
"duplicate," for two different meanings of the word, and this codebase already had to learn the
difference the hard way once:

- **`ReconciliationService`** (`service/ReconciliationService.java`) runs *after* transactions are
  already confirmed, over already-persisted `Transaction` rows. Its `duplicateKey` is exact-match on
  account + date + amount + description. It compares confirmed rows to other confirmed rows — it
  has no concept of a candidate staged row from another source at all.
- **`DuplicateDetector`** (`imports/DuplicateDetector.java`) runs at staging time, *before* confirm,
  and is the one the user actually sees decisions for — `StagedRow.duplicateMatch`/`likelyDuplicate`
  drive `DuplicateReview.tsx`'s existing "Import anyway / Skip / Apply to similar" UI. It's already
  cross-account-capable at the query level (`findPotentialDuplicatesByUser` isn't scoped to one
  statement), which sounds like exactly what C6.4 needs.

**The actual gap:** `GmailStagingBridge.stage()` hardcodes `likelyDuplicate=false`,
`duplicateMatch=null` — it never calls `DuplicateDetector` at all, so a Gmail receipt gets zero
duplicate checking against the bank ledger today, full stop. But wiring it in as-is wouldn't work
either: `DuplicateDetector`'s matching requires **exact description equality**, and a Gmail row's
description is the merchant domain (`"amazon.in"`), while a bank statement line reads something like
`"AMZN MKTPLACE 4521"`. Exact string match between those two will essentially never fire. **C6.4
needs new matching logic — amount plus a date window plus merchant-name normalization/fuzzy
matching — not new UI.** The existing `DuplicateMatch` type and `DuplicateReview.tsx` component are
real reuse: users already know this exact decision pattern from CSV/PDF import.

**Two directions, and this is a real design fork worth naming rather than picking silently:**

1. **Staging-time (Gmail-side).** When a Gmail receipt is about to be staged, check it against
   already-confirmed bank `Transaction`s. This is the natural extension of `DuplicateDetector`'s
   existing job — same moment, same UI, new matching signal. Handles "bank statement imported
   first, Gmail receipt discovered later" — the common case, since bank statements are typically
   imported in batches well after the fact and Gmail discovery runs continuously.
2. **Post-confirm (ledger-wide).** The reverse order — a Gmail receipt gets approved first (nothing
   stops a user from approving a Gmail-derived transaction the same day it arrives), and the
   matching bank-statement line shows up in a CSV/PDF import weeks later. `DuplicateDetector`'s
   staging-time check would need the CSV/PDF side to also check against Gmail-sourced transactions
   — which it already does today, incidentally, since `findPotentialDuplicatesByUser` isn't scoped
   by `Transaction.Source`. What's missing there is only the fuzzy matching signal, not new
   plumbing. A genuinely separate case is **both already confirmed** (user approved the Gmail
   receipt *and* separately confirmed the bank import before either matching pass ever ran) — that
   needs `ReconciliationService`'s own post-hoc pass extended to compare across `Transaction.Source`
   values with the same fuzzy signal, plus a "Merge" action (the original mockup's own wording) that
   neither `DuplicateDetector` nor `ReconciliationService` has today — both currently only support
   discard-one-or-keep-both, not merging two already-real ledger entries into one.

Recommend building (1) first — it's the smaller piece, reuses the existing per-row review UI
unchanged, and covers the more common ordering. (2)'s "both already landed, now merge" case is real
but adds a genuinely new ledger operation (merging two committed `Transaction`s) that deserves its
own scoping pass once (1) is live and there's real data on how often it actually happens.

---

## 4. What this document deliberately does not do

No effort estimates, no timeline, no sequencing against the launch-blocker critical path beyond
"after it." Per D-16: this is the shape of the work, not a commitment to build it on any schedule.
Estimating effort for work that won't start for an unknown number of weeks, gated on GA blockers
whose own timeline this plan explicitly refuses to invent dates for (§5/§9's own stated discipline),
would be exactly the kind of asserted-not-derived number this project's plan already disclaims.

---

## 5. Recommended order, once C6 actually starts

1. **C6.2 (merchant monitoring)** — smallest, no open product questions, pure extension of a proven
   pattern (`failureCodeLayoutCounts`). Also produces the domain-volume data C6.3 needs to
   prioritize, so it's a genuine prerequisite, not just "do the easy one first."
2. **C6.4 direction (1) (staging-time reconciliation)** — real user-trust value (the original
   pitch's own framing, "probably the biggest financial trust improvement," holds up under this
   audit), bounded scope, reuses existing UI.
3. **C6.3 (unknown-merchant learning)** — depends on real backlog/volume data from C6.2 to be worth
   prioritizing correctly, and has the template-approval security question (§2.2) to resolve with
   real stakes once there are real users and a real trusted-sender registry size to reason about.

C6.4 direction (2) (post-confirm merge) and the alerting half of C6.2 are follow-on work once their
respective first pieces are live and show real usage, not part of this initial sequencing.
