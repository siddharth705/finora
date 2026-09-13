# Account Aggregator sync — design (v0.2)

Status: proposed, not yet implemented. Premium-gated, post-launch scope (same bucket as Gmail Sync).

v0.2 revises v0.1 after a critical architecture/product audit found the account-attachment step
regressed an existing safety mechanism (`ProductIdentityResolver`) and that the design had no
answer for cost control, consent-state completeness, outage recovery, or its interaction with
Gmail Sync. Every change below is grounded in reading the actual code that already exists for the
adjacent problem, not invented from scratch. See "Audit history" at the end for what changed and why.

## Problem

Bank statement import today is entirely manual (PDF/CSV upload, OCR/parse, user confirms). No
mechanism auto-imports a bank statement every month. This spec adds one, via India's RBI-regulated
Account Aggregator (AA) framework, for Premium users.

## Regulatory approach

Fynora does not become a licensed FIU (Financial Information User) directly — that needs its own
RBI/Sahamati registration, months of compliance work, wrong call pre-launch. Instead, integrate
with **Setu**, a TSP (Technical Service Provider) already registered as an FIU-for-hire: Fynora
calls Setu's REST API, Setu holds the regulatory relationship, Fynora pays per data-pull.

**Roles:** FIP (bank, holds the data) → AA (consent broker the user already has, e.g. Finvu/OneMoney
app) → FIU (Setu, on Fynora's behalf).

## Scope

- Gated behind a new `FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC`, same pattern as `GMAIL_SYNC`.
  Free/Plus users stay manual-import-only.
- FI types: `DEPOSIT` (savings/current) and `CREDIT_CARD`. Coverage for `CREDIT_CARD` is
  issuer-by-issuer and not verified yet — needs checking against Setu's live FIP list before build.
- Historical backfill on first link: last 3 months.
- Fetch cadence: recurring (daily), not one-time or manual-trigger-only. **Fixed at consent-grant
  time** — the AA consent artifact bakes in frequency/data-range when the user approves; Fynora
  cannot change a link's cadence later without a full new consent flow. Any future cost lever that
  assumes cadence is an operational dial (e.g. "throttle low-engagement users to weekly") is
  therefore a product feature of its own, not a config change — out of scope for this version.
- Runs **alongside** manual import, but per-account, not per-user: an account only has one active
  source at a time (see Data model). Accounts not on the AA network, or not linked, stay on manual
  import as today.

## Architecture

New package `integrations/setu/` (mirrors `integrations/google/`):

- `AccountAggregatorLink` — entity, one row per linked FI account. Fields: id, userId, linked
  `Account` id (nullable until identity resolution completes — see below), consent handle id, FI
  type (`DEPOSIT`/`CREDIT_CARD`), status (`CONSENT_PENDING`/`ACTIVE`/`PAUSED`/`REVOKED`/`EXPIRED`/
  `REJECTED`/`LINK_FAILED`), consent expiry, `lastSyncedAt`, `lastSyncStatus`, `linkIdempotencyKey`.
- `SetuConsentService` — creates a consent request, returns the AA-app redirect URL. Consent
  creation is idempotent on a client-supplied key (see Link idempotency below), mirroring
  `ReimportConfirmationClaim`'s existing "unique index is the guarantee" pattern for exactly this
  class of problem (concurrent double-submission of one logical attempt).
- `AccountAggregatorWebhookController` — receives Setu webhooks (`consent.approved`,
  `consent.rejected`, `consent.revoked`, `data.ready`), signature-verified (same shape as
  `RazorpayWebhookDispatcher`).
- `SetuDataFetchService` — on `data.ready`, calls Setu's fetch endpoint, decrypts the FI data
  payload (AA spec's ECDH key exchange).
- `AccountAggregatorTransactionMapper` — maps decrypted `DEPOSIT`/`CREDIT_CARD` JSON into
  `Transaction` rows, `Source.ACCOUNT_AGGREGATOR`, computing both identifiers described below.
- `AccountAggregatorReconciliationSweepService` — scheduled safety net (mirrors
  `SubscriptionReconciliationSweepService`): finds links with no sync past their expected cadence +
  grace window, force-fetches directly rather than waiting on a webhook that may never arrive. Also
  reaps stale `CONSENT_PENDING` rows past a timeout, mirroring `ImportSessionService.scheduledSweep`'s
  existing 48-hour TTL pattern for staged imports.
- `AccountAggregatorLinkController` — REST API: initiate link, list linked accounts, revoke.

**Webhook-driven + reconciliation sweep, not poll-only.** This codebase already has a proven
pattern for exactly this shape (Razorpay billing: webhook + `SubscriptionReconciliationSweepService`),
and reusing it keeps one mental model for "external push, verified by a periodic sweep" rather than
introducing a second, poll-based one for AA alongside it. Revisit only if Setu's webhook reliability
proves poor in practice — not a decision to make speculatively now.

## Data model

- New `Transaction.Source.ACCOUNT_AGGREGATOR` value, alongside `MANUAL`/`CSV_IMPORT`/`GMAIL_IMPORT`.
- New `Account.primarySource`: `MANUAL` (default, current behavior) or `ACCOUNT_AGGREGATOR`.
- `SourceTrust.of()` gets an `ACCOUNT_AGGREGATOR` case, set to **70** — above `GMAIL_IMPORT` (60,
  a self-reported receipt) because AA is a live bank feed, but below `CSV_IMPORT` (95, a validated
  pipeline with months of real-corpus tuning) because AA's decrypt/map layer ships with zero
  production mileage. Revisit upward once validated. (No `default` branch in that switch today — the
  compiler forces this case the moment the enum value exists.)
- New `Transaction.externalTxnId` (Setu's txn id, nullable — not every FIP is confirmed to populate
  it reliably) and `Transaction.transactionFingerprint` (always computed for an AA-sourced row) —
  see Transaction identity below.
- `AccountAggregatorLink.linkIdempotencyKey` — see Link idempotency below.

## Transaction identity and idempotency

**Do not trust Setu's `txnId` as the sole key.** Not established: whether every FIP populates it
consistently (known data-quality variance across the AA ecosystem bank-to-bank), or whether it's
stable across a pending→posted transition for the same transaction. Treat it as a hint/optimization,
not a guarantee.

**Primary + fallback:**
- Primary: match on `externalTxnId` when present and previously seen.
- Fallback, always computed: `transactionFingerprint = hash(accountId, amount, direction, valueDate,
  normalize(narration))` — same discipline `ReconciliationService`'s existing exact-duplicate pass
  already applies via its own composite key, just persisted explicitly here rather than computed
  ad hoc at reconcile time, because this fingerprint also has to survive a webhook redelivering the
  same fetch window.
- On ingest: look up by `externalTxnId` first; if absent or unseen, look up by fingerprint; if
  neither matches, insert new. A `txnId` that turns out to change across fetches for the same real
  transaction degrades gracefully to a fingerprint match instead of a silent duplicate.

## Account identity resolution

**v0.1 proposed silent auto-attach on a masked-number match. This is wrong and is removed.**
`ProductIdentityResolver` already exists in this codebase to solve exactly this class of problem for
manual re-import, with a deliberate three-way result — `NEW` / `MATCHED` (exact, safe to auto-apply)
/ `PROBABLE` (plausible but unproven, **never auto-merged**, always surfaced to a human) — because
(its own doc comment) a wrong merge corrupts two products at once, worse than a duplicate the user
can see and resolve. A manually-imported account only ever holds a *masked* number (from a printed
PDF); comparing that against anything AA supplies can, by this resolver's own existing rules, only
ever reach `PROBABLE`, never `MATCHED`. Silently attaching on it would bypass a safety mechanism this
codebase already learned it needed.

**Revised flow:**
1. Build the AA-side identity the same shape `ImportService.resolveTargetAccount` already does for
   manual imports (bankId, strong key when available, IFSC + holder name as weak-signal fallback).
2. Run it through the same resolution semantics as `ProductIdentityResolver`:
   - `MATCHED` (exact strong-key equality — realistically rare here, since the manual side is
     masked) → attach automatically, no prompt.
   - `PROBABLE` (the expected common case) → **do not attach automatically.** Surface a confirmation
     step: *"Is this your [Bank] account ending ****1234?"* User confirms the existing account, or
     says it's a different/new one.
   - `NEW` → create a new `Account`, `primarySource = ACCOUNT_AGGREGATOR`.
3. Only after resolution (auto or user-confirmed) does the account's `primarySource` flip and manual
   upload get blocked for it — both UI (hide control) and API (reject server-side; a hidden button is
   not enforcement).

## Consent lifecycle

States: `CONSENT_PENDING` → `ACTIVE` → (`PAUSED` | `REVOKED` | `EXPIRED`), plus two terminal states
missing from v0.1: `REJECTED` (user declines inside their AA app — a normal, common outcome, not an
error) and `LINK_FAILED` (Setu's own consent-creation call fails before the user ever reaches the AA
app). Both need a defined UI ("declined — try again" / "couldn't create the request — try again"),
not a silently-stuck row.

**Link idempotency.** Two concurrent "link this account" taps (web + mobile, or a retried request)
must not create two Setu consent requests for the same account. Mirrors
`ReimportConfirmationClaim`: a client-minted idempotency key, enforced by a unique index, not a
select-then-insert check — the second concurrent attempt blocks on the first's commit and then fails
cleanly.

**State transitions on entitlement/consent change:**
- Downgrade to Free/Plus → link `PAUSED` (not deleted — consent may still be valid), linked
  `Account.primarySource` reverts to `MANUAL`, manual upload unblocks.
- Revoked in the user's AA app (out-of-band, Fynora only observes it) → webhook `consent.revoked` →
  `REVOKED`, same unblock.
- Consent expiry (typically max ~1yr) → `EXPIRED`, same unblock.
- Both the webhook handler and the sweep re-check entitlement before processing a tick, mirroring
  `GmailDiscoveryWorker`'s existing downgrade check.

## Outage escape hatch

A design that blocks manual upload the moment an account is `ACTIVE`-linked has no answer for a
Setu/AA-side outage (real, has happened to AA ecosystem participants) — the reconciliation sweep
only helps a *missed webhook*, not a *down provider*. v0.2 adds an explicit fallback:

- If `lastSyncedAt` for an `ACTIVE` link exceeds **3× the expected cadence** (reusing the same
  constant as the sweep's own staleness check and the alerting threshold below — one number, not
  three independently invented ones), manual upload unblocks again for that account, rows tagged as
  a temporary-outage import.
- When AA eventually catches up for that period, the same overlap-reconciliation logic used for the
  initial 3-month backfill (exact-match pass + the new fuzzy near-duplicate pass below) runs again
  against the temporary-outage rows — this generalizes the "backfill dedup runs once at link time"
  framing in v0.1 to "overlap reconciliation runs whenever AA data lands over a period manual data
  also covers," since the outage hatch reintroduces manual import as a recurring possibility, not a
  one-time event.

## Reconciliation

**1. AA vs. manually-imported history (the initial-backfill and outage-hatch overlap case).**
- Existing exact-match duplicate pass (composite key: account+date+amount+description) already
  catches identical-narration overlaps, `SourceTrust` deciding canonical.
- New fuzzy near-duplicate pass, same shape as the existing Gmail cross-source pass but simpler (no
  merchant-brand-token reduction — both sides are bank narration, not domain-vs-description): same
  account, same amount, tight date window, normalized-description similarity. Needs its own
  threshold tuning against real data — the Gmail matcher's tuned constants (0.6 similarity, 3-day
  window) are not known to transfer, since generic bank narrations ("UPI-REFCODE-PAYMENT") repeat
  across many unrelated same-amount transactions in a way a merchant-domain token doesn't.
- Ambiguous ties never auto-resolve — land both, flagged for review.

**2. AA vs. Gmail Sync — a dedicated rule, not inherited from the existing Gmail pass as-is.**

`ReconciliationService`'s existing Gmail cross-source pass is deliberately conservative: a match
becomes a `FUZZY`-confidence graph edge only, **never** auto-excluded from spend totals — correct
when the collision is rare (a user occasionally imports last month's PDF). AA changes the frequency,
not just the source count: a bank feed lands daily, so for an AA-linked account with active Gmail
Sync, every matching receipt collides with reconciliation continuously. Leaving the existing
never-auto-exclude policy in place turns an occasional UX nuisance into **permanently inflated
dashboard totals** — a correctness issue, not a review-queue backlog.

New, narrower rule, scoped specifically to the `(ACCOUNT_AGGREGATOR, GMAIL_IMPORT)` pair — not a
blanket change to the existing CSV-vs-Gmail behavior, which still deals with OCR uncertainty on the
bank side and should stay conservative:
- Trigger: same account, same amount, same direction, tight date window, **high**-confidence
  description similarity (a stricter threshold than the review-only Gmail pass uses — exact value to
  be tuned against real data, not guessed here).
- Action: mark the `GMAIL_IMPORT` row `reconciliationStatus = DUPLICATE`, `isDuplicateOf` the AA row
  — reusing the exact-match pass's existing status/legacy-pointer mechanism (so it's excluded from
  totals through the same plumbing every other duplicate already uses), not a new status or a new
  totals-exclusion mechanism.
- Justification for the narrower exception: the Gmail pass's conservatism exists because *neither*
  side of a Gmail-vs-CSV/PDF match is fully trusted (receipt text vs. OCR/CSV-derived text). AA
  changes one side of that comparison to a live bank transaction — the authoritative ledger, not a
  parsed document — which is what justifies treating a high-confidence match here differently from
  every other Gmail match.

**3. Gmail confirm-time UX for an AA-linked account.**

Gmail receipts carry no account signal until a human picks one at confirm time (`GmailStagingBridge`'s
own doc comment: *"a receipt carries no bank/account signal at all... the user picks or creates the
real account at confirm time"*) — so filtering at *discovery* time, as v0.1's open question
considered, is not just undesirable, it's structurally impossible; there is no account to filter on
yet. The only real intervention point is confirm time: when the user is about to confirm a staged
Gmail batch against an account that is AA-linked, show:

> "This account already syncs directly with your bank. Most receipts imported here will duplicate
> transactions that arrive automatically."

with actions **Skip these receipts (default)** / Review duplicates later / Continue anyway — default
action is the safe one, not "continue."

## Cost control

Setu bills per data pull; nothing in v0.1 bounded this. v0.2 adds:
- A hard cap on linked accounts per user (config value).
- Relink throttling: at most one consent-creation attempt per specific account per rolling 24h
  window (on top of the idempotency key above, which only prevents *concurrent* duplicates, not
  repeated sequential relink loops).
- Rate limiting on the link-initiation endpoint itself, independent of the per-account throttle.
- Entitlement re-check on every scheduled tick (already speced above) closes the "still pulling after
  downgrade" leak, but does not by itself cap a still-Premium, paying-but-inactive user linking many
  accounts and never opening the app again. This is a business decision, not an engineering one —
  **requires explicit product sign-off** before implementation that the tier's economics accept an
  active-Premium user's AA cost floor as-is, rather than something this design further restricts.

## Data corrections and mutations

AA has no native amend/delete event for a previously-fetched transaction (a declined pre-auth that
should vanish, a pending amount that changes on posting). v0.1/early v0.2 left this as an
acknowledged-but-unsolved gap; v0.2 closes it with a concrete strategy rather than a promise to
figure it out later:

- **Sliding-window re-fetch, not point-fetch.** Every scheduled pull re-requests a trailing window
  (e.g. 7–14 days), not just "since last sync" — a correction or a disappearing pre-auth must still
  be inside the fetched range to be detectable at all. Whether Setu's fetch API supports re-requesting
  an arbitrary overlapping past range needs sandbox verification before this is implementation-final;
  not assumed here.
- **Three-way diff per fetch** over `{new, changed, missing}` within that window, not a pure upsert:
  - *New* (fingerprint/txnId unseen) → insert (already covered above).
  - *Changed* (same txnId/fingerprint, different amount/narration) → update the existing row in
    place rather than inserting a second one, and record that a correction occurred — if the user
    already categorized or reconciled that row against its old values, a silent overwrite would
    invalidate a decision they made without telling them.
  - *Missing* (a row a prior fetch of this same window returned, absent from this one) → never
    hard-delete on a single absence. Mark for review and resolve only after a grace period or a
    second confirming fetch — a transaction vanishing from a user's ledger without a trace is a
    worse failure than a stale row surviving one extra day.
- The "missing" rule only ever applies inside the window actually re-fetched — absence outside it is
  not evidence of anything, just a period that wasn't re-checked.

This is a defined diff strategy sufficient to design and build against, not a full guarantee of
correctness — final field-level behavior (what "changed" means per FI type, exact window size) still
needs validation against real Setu sandbox responses before implementation locks it in.

## Missing requirements (added in v0.2)

- **Consent-management UX:** linked accounts list, per-account status, last-synced timestamp,
  revoke/relink controls, and an explicit note that the user's AA app — not Fynora — is the system of
  record for revoking consent.
- **Audit logging:** every consent grant/revoke/webhook-driven fetch recorded via the existing
  `AuditService`/`AuditLog`, same as other sensitive flows in this codebase — not optional for a
  regulated data-sharing feature.
- **Sync monitoring:** same instrumentation shape `GmailDiscoveryWorker` (via `WorkerObservability`)
  and `ReconciliationService` (via `ReconciliationMetrics`) already use — links created/revoked,
  consent rejections, fetches attempted/succeeded/failed, transactions ingested per tick, sweep-
  triggered force-fetches.
- **Incident alerting:** alert when any `ACTIVE` link's `lastSyncedAt` exceeds the same 3×-cadence
  threshold used for the outage escape hatch — one constant serving both purposes, not two
  independently chosen numbers.
- **Webhook secret storage/rotation** — needs a concrete answer (likely reusing `EncryptionService`,
  used elsewhere for secrets) before implementation, not specified yet.
- **Data retention/erasure for AA-fetched payloads** — AA consent artifacts carry a `dataLife`;
  whether Fynora's retention policy needs a distinct rule for AA data (versus a PDF the user
  themselves chose to keep) is unresolved.
- **Legal/ToS/privacy-policy update** — bringing in Setu/AA as a data processor is a real
  compliance-surface change independent of code; flagged for product/legal, not resolved here.

## Testing

- Unit: consent state machine (including `REJECTED`/`LINK_FAILED`), transaction mapper and fingerprint
  computation against Setu sandbox fixtures for both FI types, webhook signature verification, the
  new AA-vs-manual fuzzy pass, the new AA-vs-Gmail canonicalization rule.
- Integration: full consent → webhook → fetch → `Transaction` round trip against Setu's sandbox
  AA+FIP; the outage-hatch → later-overlap-reconciliation path; the confirm-time Gmail warning.
- No real-bank testing is possible before a real user links a real account — sandbox is the ceiling
  until then.

## Explicitly out of scope / open items for a follow-up

- **Named pre-implementation task, not a footnote:** validate `CREDIT_CARD` support against Setu's
  real sandbox for at least a few issuers before implementation starts — current list of
  participating issuers, sample FI-data payloads, whether `txnId` is actually populated for card
  transactions, and posted-date vs. transaction-date behavior (cards are expected to drift further
  than savings/current, per the reconciliation-window note above). This blocks the `CREDIT_CARD`
  half of scope specifically, not `DEPOSIT`.
- Exact similarity/confidence thresholds for the new AA-vs-manual and AA-vs-Gmail passes — tune
  against real data, not guessed in this document.
- Whether webhook-vs-poll is the right long-term shape stays decided as webhook+sweep (see
  Architecture) — not reopened without evidence Setu's webhooks are unreliable in practice.
- A full solution for bank-side data corrections (see Data corrections and mutations) — flagged as a
  gap, not solved here.

## Audit history

- **v0.1 → v0.2**, after a critical architecture/product audit: replaced silent account
  auto-attachment with `ProductIdentityResolver`-equivalent semantics (the single most important
  finding — v0.1 bypassed an existing, deliberately-built safety mechanism); added transaction
  fingerprinting as a fallback to Setu's txn id; added the outage escape hatch; added `REJECTED`/
  `LINK_FAILED` consent states and link-creation idempotency; added cost controls (link caps, relink
  throttling, rate limiting); added a dedicated AA-vs-Gmail canonicalization rule after recognizing
  AA turns Gmail's existing rare reconciliation edge case into a continuous one; added the
  confirm-time Gmail warning UX; added consent-management UX, audit logging, sync monitoring and
  incident alerting as explicit requirements; decided webhook+sweep over polling (reusing the
  Razorpay-proven pattern) rather than reopening the architecture without evidence.
