# Gmail Transaction Sync — Design Proposal

**Status:** **Approved for implementation planning** (review of 2026-08-14). Still design only —
no code, migrations, dependencies, or OAuth endpoints exist yet, and none should until the five
decisions in §19 are made. Not scheduled against v1; parallel planning.

**Revision 1 (2026-08-14):** stable Google `sub` identifier instead of email as the account key
(§6), encryption promoted to a platform capability (§12.1), sender authentication and HTML
sanitization as hard security requirements (§12.2, §12.3), transaction-ownership confidence model
(§10), bounded initial sync window (§4), per-message checkpointing (§7), parser versioning and
fixture testing (§8.1), non-blocking duplicate warning UX (§11), activation metrics (§16.3),
user-facing connection health (§16.2), reauth notifications (§15.1), implementation-dependencies
list (§19).

**Revision 2 (2026-08-14):** merchant event lifecycle — refunds and cancellations (§10),
merchant account identity (§10.2), parser-correction learning (§9.1), operational sync limits
(§14.1), audit logging (§12.5), trusted sender registry as data (§12.2), and bulk review UX to
avoid review-fatigue abandonment (§13.1). **Two corrections to that feedback, both in the
"already exists, don't rebuild it" category** — see §10.1 and §12.5.

**Scope note up front:** this is a *data-integration* OAuth grant (Finora reads Gmail to find
receipt/order-confirmation emails and turn them into candidate transactions). It is a different
feature from "Sign in with Google" (an alternate login method). `AuthController.java:115-116`'s
`// TODO Phase 2: /oauth/google callback endpoint` is about the latter — login, not data sync. They
would request different scopes and present different consent copy to the user, even if some OAuth2
client plumbing ends up shared later. Nothing below assumes or blocks on that TODO.

## 1. Feature overview and business purpose

Bank statement import (CSV/PDF) captures what a bank or card issuer settles. It systematically
misses two categories Finora users clearly care about: UPI/wallet spend that never appears as a
clean card line, and the *intent* layer — "you ordered this on Amazon" arrives in Gmail well before
(sometimes instead of, e.g. a cancelled or refunded order) it would ever show up on a statement.
Gmail sync closes that gap by turning transactional emails (order confirmations, ride receipts,
payment confirmations) from known merchants into staged transactions, using the same review-before-
confirm flow users already know from CSV/PDF import.

Business case: faster time-to-value (a new user sees real spending data before their first
statement cycle even closes), and a differentiator few personal-finance tools in this market do
well. It is also, by a wide margin, the most operationally fragile data source Finora will have
ingested — see §8 and §12. That tradeoff is made explicit throughout this document rather than
glossed over.

## 2. User flow

```
1. User opens Settings → Connect Gmail
2. Finora explains, in plain language, exactly what will be read (receipt/order emails only,
   never sent, modified, or deleted) and redirects to Google's consent screen
3. User reviews Google's own consent screen (scope: gmail.readonly) and approves
4. Google redirects back to Finora's callback with an authorization code
5. Backend exchanges the code for tokens server-side, persists an encrypted refresh token,
   connection status -> CONNECTED
6. Background sync worker polls periodically, finds new receipt-shaped emails, parses them
7. Parsed transactions land in the SAME staging review Finora already has for CSV/PDF import —
   nothing is written to the ledger without the user confirming it
8. User reviews staged Gmail-sourced rows in the existing import review UI, confirms or discards
9. User can disconnect at any time; disconnect asks explicitly whether to also remove already-
   created transactions that originated from Gmail sync, or just stop future syncing (§12.4)
```

Step 7 is the load-bearing design choice in this whole proposal: Gmail-sourced rows get **zero**
special-cased auto-write path. They go through `ImportSessionService`/`StagedRow` exactly like a
CSV upload does. This is not a simplification for the sake of it — it's the only way to inherit
review-before-write, duplicate flagging, and the existing admin/trace tooling for free instead of
building a parallel "trust this source automatically" path on day one for the least reliable source
Finora will have.

## 3. Google OAuth architecture

Standard OAuth 2.0 authorization-code flow, server-side (confidential client — `client_secret`
never reaches the frontend):

- **Scope: `https://www.googleapis.com/auth/gmail.readonly` only.** Never `gmail.modify`,
  `gmail.send`, or full mailbox scopes. Finora has no reason to write to a user's mailbox, and
  requesting less scope is both the correct security posture and lowers the bar for Google's own
  verification review (see below).
- `state` parameter carries CSRF protection and the Finora `userId`, verified on callback before
  any token exchange.
- Callback exchanges the authorization code for an access token + refresh token via Google's token
  endpoint, in the same request-response, server-to-server — the frontend never sees either token.
- Only the refresh token is persisted. Access tokens are short-lived (~1 hour) and cheaper to mint
  fresh from the refresh token at sync time than to persist and track expiry for.
- **This does not reuse `TokenHasher`/the existing `RefreshToken` pattern.** That pattern
  (`backend/src/main/java/com/finora/util/TokenHasher.java`, one-way SHA-256) works for Finora's own
  session tokens because the server only ever needs to *compare* a presented token, never present it
  back to anyone. A Gmail refresh token must be sent to Google in plaintext on every sync — hashing
  it would make it permanently unusable. This needs real, reversible encryption at rest, which does
  not exist anywhere in this codebase today (confirmed: no `Cipher`/KMS/Vault usage outside PDF
  password-unlock, which is an unrelated concept). Building that encryption primitive is new
  platform work this feature depends on, not a detail to wave past — see §12.1.
- **Google's own review requirement, not an engineering estimate:** `gmail.readonly` is on Google's
  Restricted Scopes list. Production access beyond 100 test users requires Google's OAuth app
  verification plus an annual third-party CASA security assessment — a process Finora does not
  control the timeline of (historically multi-week, sometimes longer). This should be started in
  parallel with engineering work, not after, or it becomes the rollout's actual critical path
  regardless of how fast the code is ready.

## 4. Gmail API integration approach

Two ways to discover new mail: `users.messages.list` with a search query (`q=`), or
`users.watch()` + Cloud Pub/Sub push notifications for near-real-time delivery.

**MVP: polling, not push.** This repo has zero existing Pub/Sub or public-webhook infrastructure,
and `users.watch()` subscriptions expire every 7 days and need active renewal — real new
operational surface for a `v1` of a feature already carrying the encryption and duplicate-detection
gaps below. A scheduled poll, using Gmail's `historyId` cursor (`users.history.list`) rather than
re-querying by date window every run, is a strict subset of infrastructure already proven in this
codebase (see §14) and is the right MVP tradeoff. Push notifications are a Phase 2+ item once sync
volume or latency actually demands it (§18).

Search query scoped to a known-sender allowlist plus a receipt-shaped subject/label heuristic
(`from:(amazon.in OR uber.com OR zomato.com OR ...) OR subject:(order confirmation OR receipt OR
invoice OR trip receipt)`) — narrower than "scan everything," both for API quota reasons and
because it bounds what a parsing bug could ever be exposed to. Note the allowlist here is a *search
filter*, not a trust boundary — `from:` in a Gmail query matches the display header, which is
forgeable. Actual sender authentication is a separate, mandatory check at parse time (§12.2).

**Initial sync window: last 90 days, bounded.** An unbounded first sync against a ten-year-old
mailbox is the worst possible first-run experience — enormous API quota burn, a staging review with
thousands of rows nobody will ever work through, and the slowest possible time-to-first-value for a
feature whose entire business case is speed to value. 90 days is enough to populate a meaningful
spending picture (roughly three statement cycles) while keeping the first review session
completable in one sitting. Configurable, but bounded by default — never "all history." Subsequent
syncs are incremental via `historyId`.

**Quota planning.** Gmail API enforces a per-project quota (per-user rate limits and a daily
project ceiling). At small scale this is a non-issue; at 10k+ connected accounts polling daily it
becomes a real capacity-planning question — sync frequency, batched requests
(`users.messages.batchGet` rather than N individual fetches), and per-user throttling all have to
be sized against that ceiling rather than discovered when Google starts returning 429s. The
backoff/retry handling in §15 covers the tactical response; the strategic sizing is an open
question (§22) rather than something this document can answer without real usage numbers.

## 5. Recommended package/module structure

Building on the split you proposed (`GoogleOAuthController`/`GmailService`/`GmailTokenService`/
`GmailTransactionImporter`), filling in the pieces the research surfaced as necessary — a poller
matching this codebase's existing worker shape, and a merchant-parser layer, since neither has an
equivalent to reuse:

```
com.finora.integrations.google
├── GoogleOAuthController        REST: POST /connect, GET /callback, DELETE /disconnect, GET /status
├── GoogleOAuthService           authorization-code exchange, refresh-token minting
├── GmailConnectionService       owns GmailConnection lifecycle: connect / disconnect / reauth
├── GmailSyncWorker              @Scheduled poller -- same shape as ImportJobWorker (see §14)
├── GmailApiClient                thin Gmail API wrapper; owns Google-specific rate-limit/backoff
├── email/
│   ├── SenderAuthenticator       DKIM/SPF + domain allowlist -- the trust gate (§12.2)
│   └── EmailContentSanitizer     HTML -> safe text before any parser sees it (§12.3)
├── merchant/
│   ├── MerchantEmailParser       interface: canParse(SanitizedEmail) / parse(...) -> ParsedReceipt
│   │                             each implementation declares a parserVersion (§8)
│   ├── AmazonEmailParser
│   ├── MyntraEmailParser
│   ├── UberEmailParser
│   ├── OlaEmailParser
│   ├── ZomatoEmailParser
│   ├── BookingComEmailParser
│   └── GenericReceiptParser      trusted domain, no specific parser -> DETECTED only, never
│                                 a staged transaction in MVP (§10.3)
├── GmailStagingBridge            ParsedReceipt -> StagedRow, hands off into ImportSessionService
│                                 exactly like ImportJobWorker.stage() already does
└── dto/
    └── GmailConnectionStatusDto, ParsedReceipt, SanitizedEmail, ...
```

Matches `com.finora.imports.pdf.acquisition`'s existing precedent for a domain getting its own
subtree rather than living inside a shared package. `MerchantEmailParser` is deliberately an
interface with one implementation per merchant, not one parser with a growing if/else chain —
adding Flipkart later should mean adding a class, not editing an existing one.

### 5.1 Gmail as the first *connector*, not a bespoke pipeline

Gmail will not be the last external source Finora ingests — SMS/UPI notifications, card feeds, and
account-aggregator APIs are all plausible successors, and each would face the same problems this
document solves: a connection with credentials and a lifecycle, incremental sync with a cursor,
extraction of variable confidence, source attribution, and review-before-ledger.

**The cheap version of that foresight, worth doing now:** keep the *seam* generic even though only
one connector exists. Concretely, the boundary between "fetch and extract from somewhere" and
"stage for review" (`GmailStagingBridge` above) should be expressed in source-neutral terms —
a normalized candidate transaction plus its provenance and confidence — rather than in
Gmail-specific ones. Then a future SMS connector implements the same hand-off instead of growing a
second parallel path into staging.

**The expensive version, explicitly not proposed here:** building a generalized ingestion framework,
connector registry, or plugin architecture before a second connector exists. That is speculative
generality — the abstraction would be designed against exactly one real example, which is how
frameworks end up fitting nothing. Build Gmail concretely, keep the hand-off boundary clean, and
extract the abstraction when connector #2 arrives and its actual requirements are known.

See §23 for how this relates to the broader platform ambition, and why most of it is deliberately
*not* in this document.

**Note what is deliberately NOT in this package:** encryption. `EncryptionService` and friends
belong in `com.finora.security.crypto` as a platform capability (§12.1), not here — Gmail is simply
its first caller.

## 6. Database schema changes required

```sql
gmail_connections
  id, user_id (FK, unique per user for v1 -- one Gmail account),
  google_user_id,           -- Google's stable `sub` claim; THE account key (see below)
  google_account_email,     -- display/reference only, never an identity key
  encrypted_refresh_token, scopes_granted,
  status (CONNECTED | REAUTH_REQUIRED | DISCONNECTED | REVOKED),
  mailbox_kind (PERSONAL | SHARED | BUSINESS),   -- user-declared at connect time (§10)
  history_cursor,           -- Gmail historyId, for incremental sync
  initial_sync_from,        -- lower bound of the first sync window (§4)
  connected_at, last_synced_at, last_successful_sync_at, created_at, updated_at

  UNIQUE (google_user_id)   -- one Google account connects to at most one Finora user

gmail_sync_jobs          -- own table, NOT a bolt-on to import_jobs (see open question below)
  id, connection_id (FK), status, attempts, last_error, failure_code,
  import_session_id (nullable FK -- same bridge convention as ImportJob.importSessionId),
  last_processed_message_id,   -- checkpoint, so a crash resumes mid-batch (§7)
  messages_seen, messages_parsed,
  created_at, started_at, completed_at

gmail_processed_messages  -- idempotency + provenance; one row per message ever processed
  id, connection_id (FK), gmail_message_id, parser_name, parser_version,
  outcome (PARSED | DETECTED_NOT_STAGED | SKIPPED_NOT_RECEIPT
           | SKIPPED_UNTRUSTED_SENDER | PARSE_FAILED),
  detected_merchant_domain,   -- set on DETECTED_NOT_STAGED: the "write a parser for this" signal
  staged_row_ref (nullable), processed_at

  UNIQUE (connection_id, gmail_message_id)

trusted_email_domains     -- the parse-trust registry (§12.2); admin-managed, audited
  id, domain (unique), merchant_name, status (ACTIVE | DISABLED),
  added_by_user_id, created_at, updated_at
```

Parsed-receipt fields carried through staging (on the staged row / its evidence, not new tables):
`merchant_order_id`, `merchant_event_type`, `external_transaction_reference` (§10.1),
`merchant_account_identifier` (§10.2), `currency` (§9), `extraction_confidence`,
`ownership_confidence` (§10), `parser_name`, `parser_version` (§8.1).

**`google_user_id` (Google's `sub` claim), not email, is the account key.** A Gmail address can be
changed, and a freed-up address can even be reassigned; `sub` is stable and unique for the lifetime
of the Google account. Email is kept for display ("Connected: r****@gmail.com") but must never be
what a lookup or uniqueness constraint keys on. The `UNIQUE (google_user_id)` constraint prevents
the same Google account being connected to two different Finora users — worth having as a real DB
constraint rather than an application check, since the failure it prevents (two users' ledgers
being fed from one mailbox) is exactly the kind of data-integrity problem that is nearly impossible
to untangle after the fact.

**`gmail_processed_messages` earns its own table** rather than being implied by the history cursor:
the cursor alone answers "where did I get to," but not "did I already process this specific
message." Those diverge the moment a sync is retried mid-batch, or the cursor is reset for a
full resync. It also carries `parser_version` per message, which is what makes §8's re-parse story
possible.

Plus one small addition to the existing staging path: a `source` discriminator on
`statement_analysis_sessions`/wherever a session's origin is recorded (`CSV` | `PDF` | `GMAIL`),
so the admin Import Health dashboard already scoped in
`docs/proposals/data-import-intelligence-proposal.md` §3.1 can show Gmail-derived imports
distinctly rather than lumping them in as an unlabeled format. This same discriminator is what
makes source visible to the *user* on every transaction (§16.2) — one column, two consumers.

**DECISION (C5-A review, 2026-08-15): how a Gmail-derived session gets an identity in
`ImportSession.content_hash`.** `ImportSession` deduplicates on `content_hash` — SHA-256 of the
uploaded file's bytes, enforced by a partial unique index, one live session per user per hash. A
Gmail receipt has no file to hash. Two options were weighed:

- **A — synthetic hash** (`SHA-256("gmail:" + gmail_message_id)`): no schema change, reuses the
  existing dedup index and uniqueness guarantee as-is.
- **B — generalize identity** (`source_type` + `source_identifier` columns replacing
  `content_hash`): the cleaner long-term shape, but a schema migration touching every existing
  CSV/PDF import path for a benefit C5-B does not need yet.

**Decided: Option A for the C5-B MVP.** `content_hash` no longer means exclusively "hash of
uploaded file bytes" — document it as "source identity hash" wherever C5-B touches it, existing
CSV/PDF behavior unchanged. The column itself is not renamed now; a rename to
`source_identity_hash` is deferred to a broader import-table cleanup, not scoped into C5. Do not
expand C5 to do that rename.

**Open question for the implementer:** `gmail_sync_jobs` above is proposed as its own table with
the *same structural pattern* as `ImportJob` (status enum, attempt counting, a `recordFailure`-
shaped outcome, bridges to `ImportSession` via the identical `importSessionId` FK idea) rather than
literally extending `import_jobs` with a `source=GMAIL` row. Reuse would inherit `ImportJob`'s
existing retry/observability wiring for free but risks bloating a table whose other columns are
CSV/PDF-file-shaped; a separate table stays clean but duplicates some queue machinery. Worth a
real decision, not assumed here.

## 7. Email fetching and processing pipeline

```
GmailSyncWorker (scheduled)
  -> GmailApiClient.listNewMessages(connection, sinceHistoryId | initialSyncFrom)
  -> for each message:
       [1] already in gmail_processed_messages? -> skip (idempotency)
       [2] SenderAuthenticator: DKIM/SPF pass AND sender domain on the trusted registry?
           -> no: record SKIPPED_UNTRUSTED_SENDER, never parse it (§12.2)
       [3] EmailContentSanitizer: HTML -> safe plain text, scripts/pixels/remote refs stripped
       [4] MerchantEmailParser resolution
             a) a merchant-specific parser matched -> ParsedReceipt, continue to staging
             b) no specific parser (GenericReceiptParser) -> DETECTED_NOT_STAGED, stop here (§10.3)
           ParsedReceipt { merchant, merchantAccountIdentifier, amount, currency, date,
                           description, merchantOrderId, merchantEventType,
                           externalTransactionReference, extractionConfidence,
                           ownershipConfidence, parserName, parserVersion, sourceMessageId }
       [5] record outcome in gmail_processed_messages
       [6] checkpoint: gmail_sync_jobs.last_processed_message_id = this message
  -> GmailStagingBridge maps ParsedReceipt -> StagedRow, calls the SAME ImportSessionService
     staging path CSV/PDF already uses
  -> connection.history_cursor advances past this batch (only after the batch is fully staged)
```

Steps [2] and [3] are non-negotiable and come *before* any parsing — see §12 for why. Step [1] and
step [6] together are what make a crash mid-sync safe.

**Checkpointing, because "restart from the beginning" is not acceptable at this scale.** A first
sync over a 90-day window can be thousands of messages. If the worker dies at message 500, the next
run must resume near 500, not re-fetch and re-parse from zero — that wastes Gmail quota, and
without the `gmail_processed_messages` idempotency check it would also re-stage duplicates of
everything already staged. `last_processed_message_id` on the job row plus the unique
`(connection_id, gmail_message_id)` constraint make resumption both cheap and safe.

A single unparseable or malformed message must never fail the batch — this mirrors the existing,
deliberate "one bad row doesn't break the import" principle already in the CSV/PDF pipeline
(unparseable rows are preserved separately, not fatal). Per-message failures are recorded as
`PARSE_FAILED` and skipped; only a connection-level failure (token revoked, Gmail API down) is
worth alerting on.

## 8. Merchant parser architecture

One `MerchantEmailParser` implementation per merchant for the initial set named in scope (Amazon,
Myntra, Uber, Ola, Zomato, Booking.com), each responsible for: recognizing its own sender pattern,
locating the amount/date/order-reference in that merchant's current email template, and reporting
a confidence score rather than a bare boolean success.

**This is the single largest, most under-appreciated operational cost of the whole feature.** A
bank statement format changes rarely and is usually detectable by header signature. A merchant's
transactional email template can change without notice, with zero signal to Finora beyond "this
parser's success rate quietly dropped." §16.1 treats per-parser success rate as a first-class,
individually-alertable metric for exactly this reason — a generic "sync succeeded" health check
would not catch this failure mode at all.

### 8.1 Maintenance strategy

Three mechanisms, none optional, because "we'll notice when it breaks" is exactly the failure mode
that makes this the riskiest part of the feature:

**Parser versioning.** Each parser declares a version (`AmazonEmailParser` v1, v2, …), recorded per
message in `gmail_processed_messages.parser_version` (§6). This is the same reasoning
`docs/proposals/data-import-intelligence-proposal.md` §3.3 already applies to statement parsers —
"why did my old import look different from a new one" has no answer without it, and it is cheap now
and unreconstructable later. It also makes targeted re-parsing possible: when a v2 fixes a v1
extraction bug, the affected messages are identifiable by version rather than by guesswork.

**Fixture-based regression tests.** Real (sanitized, PII-scrubbed) sample emails committed as test
fixtures, one directory per merchant:

```
backend/src/test/resources/gmail/
  amazon/order-confirmation-v1.html, order-confirmation-v2.html, refund.html
  uber/trip-receipt.html
  zomato/order.html
```

Every parser change runs against every historical fixture for that merchant — the same regression-
gate philosophy the PDF corpus tests already apply to statement layouts. **Fixtures must be
sanitized before commit**: this repo already has automated PII guards in CI
(`scripts/check-fixture-hygiene.sh`, run as a blocking "Customer PII check" step, added after a real
incident where a customer's account number reached a committed file). Receipt emails contain
delivery addresses, phone numbers, and order references — exactly what that guard exists to catch.
Any fixture-capture process has to assume those guards will fire, and treat that as correct.

**Template-change detection.** A parser's own success rate is the signal (§16.1): a sustained drop in
`parse success / messages seen` for one merchant, while others hold steady, is a template change
until proven otherwise. Alert per-parser, not on an aggregate.

## 9. Transaction extraction strategy

**Rules-based first, for the reasons this codebase already applies the same philosophy elsewhere:**
`CategorizationService.suggest()`'s own cascade (user rule → learned distribution → keyword rule →
default) already establishes the pattern of "cheap, deterministic, auditable first; probabilistic
fallback last" for a different problem in this same pipeline. Extraction should follow the same
shape: hand-written parsers for named high-volume merchants (precise, free to run, easy to unit
test against real fixture emails) are the primary path.

**A future AI/LLM fallback**, explicitly scoped as future and not MVP: for the long tail of senders
that will never justify a hand-written parser, an LLM-based extractor reading raw email text and
returning a structured `(merchant, amount, date)` guess, tagged with a lower confidence tier than
any rules-based parser's output and routed through the same staging review either way. Not
designed further here beyond flagging it, matching how `docs/proposals/data-import-intelligence-
proposal.md` §3.5 treats its own explicitly-future item — noted so it isn't lost, not built now.

Once extracted, a `ParsedReceipt` becomes a `(description, amount, date)` triple that can call
`CategorizationService.suggest(userId, description, amount, accountType)` **directly, unmodified** —
confirmed by research that this method takes plain values, not a CSV/PDF-specific type. Nothing
downstream of extraction needs to change; only the extraction step itself is new.

**Multi-currency from day one.** `ParsedReceipt` carries an explicit `currency`, parsed from the
receipt rather than assumed. The initial merchant set is India-focused (₹), but Booking.com alone
routinely bills in USD/EUR, and "the amount was right but the currency was assumed" is a silent
financial-correctness bug, not a formatting one. Cheap to carry the field from the start;
expensive to retrofit across already-imported rows later.

### 9.1 Learning from user corrections — two different things, only one of them new

**Category corrections already work, for free.** Finora's learning pipeline
(`MerchantLearningService`, and the learned-distribution tier inside
`CategorizationService.suggest()`) keys off the resolved merchant and description — not off the
import's source format. A user recategorizing a Gmail-sourced Uber row from "Transport" to
"Business Travel" feeds the *existing* learning loop with no Gmail-specific code, exactly as it
does today for a CSV-sourced row. **No `GmailCorrectionLearningService` should be built for this
case** — it would be a second, competing learning path over the same signal.

**Extraction corrections are genuinely new, and are a different signal entirely.** When a user
edits the *amount*, *date*, or *merchant* of a staged Gmail row, they are not expressing a
preference — they are reporting that the parser was wrong. That is parser-quality telemetry, and it
belongs with the parser, not with categorization:

```
  User edits a staged Gmail row's amount/date/merchant
       -> record (parserName, parserVersion, field, wasValue, becameValue)
       -> aggregate per parser+version
```

This is the **earliest and highest-signal detector of a merchant template change** — considerably
faster than watching aggregate success rate (§8.1), because a template change often produces
confidently-extracted *wrong* values rather than outright parse failures. A parser that silently
starts reading the shipping charge instead of the order total looks perfectly healthy on a
success-rate metric and is caught immediately by "every user is correcting this parser's amounts."

Feeds the per-parser alerting in §16.1. MVP scope: record the corrections and surface the rate;
automatically adjusting extraction based on them is explicitly not proposed.

## 10. Transaction ownership and confidence

**A receipt in someone's mailbox is not proof that person paid for it.** This is a correctness
problem specific to email as a source, with no equivalent in bank-statement import — a statement
line is definitionally the account holder's transaction; an email is not. Real cases, all common:

- An Amazon order placed by the user but delivered to (and paid by) a family member
- An Uber ride the user booked for someone else, or someone booked for them
- A shared `family@example.com` or `office@example.com` mailbox mixing several people's spending
- Receipts auto-forwarded from another address, where the mailbox owner isn't the purchaser

Finora cannot resolve ownership from an email alone, and pretending otherwise produces confidently
wrong financial data — the worst possible outcome for a finance product.

**Two mitigations, both modest:**

**1. Mailbox kind, declared by the user at connect time** (`mailbox_kind` in §6):

```
Is this mailbox mainly...
  ( ) Personal  -- mostly my own purchases
  ( ) Shared    -- family or household mailbox
  ( ) Business  -- work expenses
```

A `SHARED` or `BUSINESS` mailbox lowers baseline ownership confidence for everything from it, and
should never be a candidate for any future auto-confirm behavior.

**2. An explicit `ownershipConfidence` on every `ParsedReceipt`,** distinct from
`extractionConfidence` (how sure we are we read the *numbers* right — a different question from
whose transaction it is):

| Ownership confidence | Example | Handling |
|---|---|---|
| High | Personal mailbox, merchant account matches the user's own email | Normal staging |
| Medium | Personal mailbox, but no ownership signal in the receipt | Staged, flagged for attention |
| Low | Shared/business mailbox, or forwarded mail, or generic parser | Staged, prominently flagged |

Everything still routes through the same staging review regardless (§2 step 7), so no confidence
tier can silently write to the ledger. What confidence changes is *prominence* in review — low
confidence rows should be visually distinct and never pre-selected for bulk-confirm (§13.1).
**Finora's stated position, worth writing into the user-facing copy: we trust the connected
mailbox, not the merchant-account ownership behind it — which is exactly why every row is
reviewed.**

### 10.1 Merchant event lifecycle — a purchase is not a single event

**This is a genuine correctness gap in Revision 1, and it is common enough to matter a lot in this
market.** An email stream is not a stream of settled transactions; it is a stream of *events about
orders*, and those events amend each other:

```
  Day 0   "Your Amazon order #12345 has been placed"      ₹5,000
  Day 2   "Your order #12345 has been cancelled"
  Day 4   "Refund of ₹5,000 issued for order #12345"
```

If a user confirms the Day-0 row and nothing else happens, Finora permanently reports ₹5,000 of
spending that never occurred. Indian e-commerce return/cancellation rates make this a routine case,
not an edge one — and "my spending total is wrong" is precisely the trust failure this entire
design is otherwise built to avoid.

**Correction to the proposed fields: Finora already has a refund model — use it.**
`Transaction.refundOfTransactionId` (`backend/src/main/java/com/finora/entity/Transaction.java:147-149`)
already links a reversing INCOME transaction to the expense it reverses, with an existing
`ReconciliationService` refund pass and `RefundNetting` service built on it. A new parallel
`merchant_event_type` model on the transaction itself would duplicate — and eventually contradict —
that. The Gmail-side addition should be the *correlation key* that lets an email event find the
transaction it amends, then defer to the existing refund linkage:

```
On the staged/parsed side (new):
  merchant_order_id        -- "12345"; the join key across events from one merchant
  merchant_event_type      -- PURCHASE | CANCELLATION | REFUND | AMENDMENT
  external_transaction_reference  -- merchant's own txn/payment ref where present

On the ledger side (existing, unchanged):
  Transaction.refundOfTransactionId  -- already links a reversal to its original
```

A `REFUND` event for `merchant_order_id = 12345` looks up whether a confirmed transaction from that
order exists, and if so stages a reversal *linked via the existing `refundOfTransactionId`* rather
than inventing a new reversal concept.

**MVP scope decision:** capture `merchant_order_id` and `merchant_event_type` from day one — they
are nearly free to extract while a parser is already reading the email, and unreconstructable
later — but **only act on `PURCHASE` events in MVP.** Cancellation/refund *linking* is Phase 2
(§18), for the same reason cross-source reconciliation is: automatically reversing a confirmed
financial transaction based on a parsed email is a high-consequence action that deserves its own
design, not a paragraph here. Capturing the keys now is what makes that Phase 2 work possible at
all; acting on them now is what would make it dangerous.

### 10.2 Merchant account identity

Receipts frequently name the account they belong to ("Your Amazon account: user@example.com", "Netflix
— Account: family@..."). Capturing it as `merchant_account_identifier` on the parsed receipt is
cheap and feeds three separate things:

- **Ownership confidence (§10)** — a merchant account matching the user's own known email is the
  strongest available "this really is your purchase" signal, and is what promotes a row to High.
- **Duplicate detection (§11)** — two receipts for the same merchant order under the same merchant
  account are far more confidently the same event.
- **Subscription discovery (Phase 2)** — distinguishing two Netflix subscriptions on two accounts
  from one subscription billed twice is only possible with this field.

Store it, use it for confidence in MVP, and let the Phase 2 features build on it.

### 10.3 The trust ladder — what is allowed to become a transaction

**Resolves an internal contradiction in Revision 1**, which described `GenericReceiptParser` as a
fallback "for unrecognized senders" while §12.2 simultaneously required every parsed message to come
from an authenticated, registry-listed domain. Both cannot hold. The conservative resolution, which
is the right one for a finance product:

```
  Known merchant domain
+ DKIM/SPF/DMARC pass
+ A merchant-specific parser succeeded
+ User confirmation in review
─────────────────────────────────────
= Transaction

  Anything else
─────────────────────────────────────
= Detected email. Not a transaction.
```

Concretely, three outcomes rather than two:

| Sender | Parser | MVP outcome |
|---|---|---|
| Untrusted domain, or auth fail | — | `SKIPPED_UNTRUSTED_SENDER`. Never parsed at all. |
| Trusted domain, specific parser matched | `AmazonEmailParser` etc. | Staged for review → can become a transaction |
| Trusted domain, no specific parser | `GenericReceiptParser` | **`DETECTED_NOT_STAGED`.** Recorded, never staged. |

The third row is the change. A generic parser's output is precisely the category where extraction
is least reliable *and* least verifiable, so in MVP it produces a record that something
transaction-shaped arrived — not a financial row a user could confirm by accident while
bulk-approving (§13.1). Those records are still valuable: they are the **demand signal for which
parser to write next** ("47 users received Flipkart receipts we couldn't parse"), which is a better
prioritization input than guesswork. Phase 2 revisits this once LLM-based extraction (§9) can
provide a defensible confidence score.

**The rationale, stated plainly for whoever revisits this later:** in a finance product, a missed
transaction is recoverable — the user adds it manually, or it appears on the next statement import.
A fabricated transaction is not: it corrupts totals, budgets, and reports, and the user's trust in
every other number goes with it. When the two error directions are this asymmetric, the design
should be too.

**Residual risk that no technical control closes.** A genuinely compromised merchant sending
domain would pass DKIM/SPF and pass the registry check. Nothing in this pipeline detects that, and
claiming otherwise would be false comfort. What remains is defense in depth — user review as the
terminal gate (§2 step 7), `source = GMAIL` attribution (§12.4), confidence scoring (§10), and
duplicate warnings (§11). This is the same reason banks still confirm transactions rather than
trusting their own upstream feeds unconditionally.

## 11. Duplicate detection strategy

**The single biggest technical risk in this proposal, and it does not have a solved answer today.**
`DuplicateDetector` (`backend/src/main/java/com/finora/imports/DuplicateDetector.java`) is exact-
match only — literal equality on date, amount, and description
(`TransactionRepository.findPotentialDuplicatesByUser`), explicitly documented as having "no weaker
tier to report." That is sufficient for its actual job today: catching the same CSV re-imported
twice, where descriptions really are byte-identical.

Gmail introduces a fundamentally different duplicate problem: the SAME real-world purchase
described two completely different ways by two different sources — an email says "Your Amazon.in
order has been placed, ₹1,299," a bank statement line says "AMAZON PAY INDIA 1299.00." No string
match will ever unify those. Two failure directions, both real: silently create a duplicate
transaction (erodes trust in the numbers), or silently suppress a real transaction because it
looked similar to something already recorded (loses data the "never lose information" principle
this codebase already holds elsewhere for CSV/PDF explicitly guards against).

**MVP position: no automatic cross-source reconciliation — but a non-blocking warning layer.** Full
reconciliation (deciding two differently-described rows are the same transaction and acting on it)
stays Phase 2. What ships in MVP is advisory only: a cheap similarity check (same amount, date
within a small window, merchant-normalized description overlap) that *surfaces* a possible match in
the review UI without blocking, pre-deciding, or auto-merging anything:

```
  Amazon Pay                                    ₹1,299    14 Aug
  ⚠ Possible existing transaction found
     Similar bank transaction on 14 Aug — ₹1,299
     [ Import anyway ]  [ Skip this one ]
```

This is deliberately the weakest possible intervention: it never prevents an import, never
silently drops a row, and leaves the decision entirely with the user. It reuses the exact mental
model `StagedRow.likelyDuplicate` already establishes for CSV import — a flag the user can
override, "never an auto-drop" — extended to a fuzzier signal, and directly honors the "never lose
information" principle this codebase already holds for CSV/PDF.

The reason this is worth building in MVP despite full reconciliation being deferred: without it, a
user who imports both a bank statement and Gmail sees ₹2,598 of "spending" for a single ₹1,299
purchase and has no indication why. That is a trust failure on the numbers, which for a finance
product is more damaging than the feature simply doing less. Proper reconciliation
(date-window + amount-tolerance + merchant-normalization *matching and linking*, not string
equality) remains real design work deserving its own follow-up — flagged for Phase 2 (§18), not
solved here.

## 12. Security considerations

### 12.1 Encryption as a platform capability, not a Gmail detail

Reversible encryption-at-rest is required (§3) and does not exist in this codebase today. It should
be built as a **general platform service, not inside the Gmail package**:

```
com.finora.security.crypto
├── EncryptionService     encrypt(plaintext, purpose) -> ciphertext
├── DecryptionService     decrypt(ciphertext, purpose) -> plaintext
└── KeyProvider           resolves the active key; the ONLY thing that changes
                          when key storage moves from env to KMS
```

Gmail refresh tokens are simply its first caller. Every plausible next integration needs the same
primitive — bank API credentials (account aggregator tokens), investment platform connections,
payment provider keys, any future third-party OAuth grant. Building it Gmail-shaped means building
it again, differently, for each of those, which is how a codebase ends up with three incompatible
encryption schemes and no clear answer to "where are our secrets."

**Key management — a real decision needed before any token is persisted, not after:**

| Stage | Approach | Notes |
|---|---|---|
| Initial | Key in Railway secrets, injected as env var, read by `KeyProvider` | Matches how every other secret in this deployment is already handled (e.g. `GOOGLE_APPLICATION_CREDENTIALS` in `FirebaseConfig`) |
| Later | Cloud KMS or Vault | `KeyProvider` is the seam; nothing else changes |

**Key rotation must be designed in from the start, even if unused initially.** Store a key
identifier alongside every ciphertext (`key_id` column or a versioned envelope prefix) so
re-encryption can be incremental rather than a flag-day migration. A rotation strategy retrofitted
after thousands of tokens are encrypted under an unversioned key is a genuinely painful migration —
and rotation is exactly what is needed in the one scenario that matters most (suspected key
compromise), when there is no time for a painful migration.

### 12.2 Sender authentication — the spoofing problem

**A parser must never trust the `From:` header.** Anyone can send an email claiming to be Amazon.
An attacker who knows a target uses Finora could send themselves-as-Amazon a fabricated ₹50,000
"order confirmation" to a victim's connected mailbox, and a naive parser would extract it as a real
transaction and stage it. Even routed through review, this is a fabricated financial record
entering a finance product's pipeline — and a user bulk-confirming a review queue may well not
catch it.

Mandatory gate before any parsing (§7 step [2]):

- **DKIM/SPF/DMARC verification** — Gmail already performs these checks and exposes the results in
  the message's `Authentication-Results` header. Finora should read that verdict rather than
  attempt its own crypto validation, then require a pass.
- **Trusted sender domain registry** — an explicit allowlist of verified merchant domains
  (`amazon.in`, `uber.com`, `zomato.com`, …), matched on the *authenticated* domain, never on
  display name or a lookalike.

```
  ACCEPT   amazon.in                  (DKIM pass + on registry)
  REJECT   amazon-support@example.com (display name says Amazon; domain is not amazon.in)
  REJECT   amazon.in.example.net      (lookalike; not an exact registry match)
  REJECT   amazon.in                  (on registry, but DKIM/SPF failed)
```

Rejections are recorded as `SKIPPED_UNTRUSTED_SENDER` (§6) — not silently dropped, since a rising
rejection rate is itself a signal worth seeing.

**Registry lives in the database, not in code** (resolving §22's open question). Merchant domains
change and get added far more often than a deploy cycle should gate — adding `booking.com` should
not require a release:

```sql
trusted_email_domains
  id, domain (unique), merchant_name, status (ACTIVE | DISABLED),
  added_by_user_id, created_at, updated_at
```

**With one important constraint that follows from what this table actually is:** adding a row here
grants parse-trust to a new sender, which makes it a *security-relevant* action, not routine
configuration. Therefore: admin-only (`@PreAuthorize`, matching how `AdminFeatureFlagController`
already gates admin mutations), every change written to the audit log (§12.5), and exact-match
only — never wildcards or suffix matching, since `amazon.in.example.net` must not match an
`amazon.in` entry. Cache it in memory with a short TTL rather than querying per message; a sync
batch processes thousands of messages and this table changes a few times a year.

### 12.3 HTML handling

Receipt emails are HTML, frequently containing tracking pixels, remote resource references, and
occasionally scripts. **Raw email HTML is never rendered anywhere — not in the frontend, not in an
admin tool, not in a debug view.** The pipeline sanitizes to plain text before any parser or human
sees it (§7 step [3]), using a strict allowlist-based sanitizer (JSoup's `Safelist` or equivalent),
stripping scripts, embedded objects, and remote references. Parsers operate on sanitized text, never
raw markup — which also makes them more robust to cosmetic template changes.

### 12.4 Other considerations

- **Least-privilege scope.** `gmail.readonly` only, stated plainly to the user before the Google
  redirect (§2 step 2) — never modify, send, or delete anything in a user's mailbox.
- **Fabricated-data resistance.** Beyond spoofing, a user could deliberately feed their own mailbox
  fake receipts to inflate reported spending. Since every Gmail row goes through review and is
  recorded with `source = GMAIL` and its originating message id (§6), such data is at least always
  attributable and distinguishable from bank-verified data. Worth stating plainly: **Gmail-sourced
  transactions are user-asserted, not bank-verified**, and any future feature that treats Finora
  data as financial evidence (lending, tax export, reporting to a third party) must be able to tell
  those apart. The `source` discriminator is what makes that possible later.
- **Minimal retention of source content.** This is a real tension with an existing principle:
  `docs/engineering/financial-document-intelligence-principles.md`'s "never lose information"
  stance (referenced in `ImportController`'s own doc comments) argues for keeping source material.
  For a bank statement, Finora keeping a copy is the *only* durable record, since the upload doesn't
  persist anywhere else once discarded. Gmail is the opposite case — the source email durably exists
  in the user's own mailbox regardless of what Finora does. Storing raw email bodies would also be
  meaningfully more sensitive than a bank PDF (delivery addresses, phone numbers, other people's
  names can appear in a receipt email). Recommendation: persist only the extracted fields plus the
  Gmail message ID as a reference (enables re-fetch for debugging without Finora ever holding a
  duplicate copy of the raw content at rest).
- **Disconnect is two questions, not one.** "Stop syncing" (revoke the token — including calling
  Google's own revoke endpoint, not just deleting it locally — and halt the worker for that
  connection) is cheap and should always be immediate. "Also delete transactions this connection
  already created" is a separate, explicit choice the user makes at disconnect time, structurally
  similar to `DeviceController`'s existing single-session revoke
  (`RefreshTokenService.revokeSession`/`revokeAllForUser`) but is not the same operation.
- **No general account/data-deletion capability exists anywhere in Finora yet** — confirmed, not
  assumed. A Gmail-specific "erase everything" endpoint would be built in isolation with nothing to
  hang off of. MVP disconnect should handle the two questions above (stop syncing; optionally
  remove Gmail-originated transactions), which is genuinely sufficient for this feature. But this
  gap is bigger than Gmail: a financial product will eventually need a real "delete my account and
  all my data" capability covering transactions, imports, statements, tokens, and audit records —
  and connecting a third-party mailbox makes that requirement considerably more visible and harder
  to defer. **Recommend raising general account deletion as its own platform work item**, informed
  by but not blocking this feature.

### 12.5 Audit logging — reuse the existing service, don't add a table

Auditing connection lifecycle and sync events is right, and this is exactly the case the existing
audit infrastructure was built for. **Correction to the proposed `gmail_connection_audit` table:
Finora already has one.** `AuditLog` (`backend/src/main/java/com/finora/entity/AuditLog.java`,
table `audit_logs`, created in `V1__init_schema.sql`) already carries `user_id`, `action`,
`entity_type`, `entity_id`, `request_id`, a JSONB `metadata` map, and `created_at` — with
`AuditService.record(userId, action, entityType, entityId, metadata)` as the write API and an
existing index on `(user_id, created_at DESC)`.

A parallel Gmail-only audit table would fragment the audit trail exactly when its value depends on
being one place to look — a security investigation asking "what did this user's account do" should
not have to know that Gmail events live somewhere else. Use the existing service:

```java
auditService.record(userId, "GMAIL_CONNECTED", "GmailConnection", connectionId,
                    Map.of("googleUserId", sub, "mailboxKind", kind, "scopes", scopes));
```

Events worth recording: `GMAIL_CONNECTED`, `GMAIL_DISCONNECTED`, `GMAIL_REAUTH_REQUIRED`,
`GMAIL_SYNC_FAILED`, `GMAIL_TRANSACTIONS_DELETED` (the destructive disconnect option), and
`TRUSTED_DOMAIN_ADDED`/`REMOVED` (§12.2). Note `request_id` is already populated from the existing
correlation-id filter, so IP/request attribution comes along without new plumbing.

**Deliberately NOT audited: routine per-sync success and token refresh.** A worker refreshing a
token every hour across thousands of users would write millions of rows a month into a table
whose value is that it's reviewable. Those belong in metrics (§16.1), not the audit log. The
distinction worth holding: audit logs record *decisions and state changes*, metrics record
*volume and health*.

### 12.6 Hostile input, blast radius, and what Gmail does *not* change

Design assumption: **every byte arriving from Gmail is hostile** — headers, body, and (in Phase 2)
attachments. Three separate concerns, and they are not equally new.

**1. Parser exploitation — a pre-existing platform question, not a Gmail one.** The concern is
real: a malformed document can exploit a parsing library, and a parser running in the main API
process shares that process's database credentials. But **Finora already does exactly this today** —
`com.finora.imports.pdf` runs PDFBox against arbitrary user-uploaded PDFs inside the main Spring
Boot process. Gmail MVP parses *sanitized HTML text* (§12.3), which is a strictly smaller attack
surface than the PDF path already in production.

So the honest framing: an isolated/sandboxed document-parsing worker is a **platform-level
improvement worth its own proposal**, benefiting the existing PDF import path first and Gmail
attachments later. Gmail MVP does not introduce this risk and should not be made to carry the cost
of solving it. What Gmail MVP *does* do is avoid enlarging it — which is precisely why attachments
are Phase 2 (§18) rather than MVP.

**2. Attachment handling gates (Phase 2, documented now so it isn't skipped later).** When invoice
PDFs are routed into the existing engine, this sequence is mandatory, not optional:

```
  Gmail attachment
    -> declared-size check (reject before download)
    -> content-type + magic-byte validation (not just the filename extension)
    -> decompression-ratio guard (zip/PDF bombs)
    -> malware scan
    -> parse in an isolated worker, resource- and time-capped
    -> extracted JSON only crosses back
    -> staging review
```

The property that matters: **only extracted structured data crosses back into the main system** —
never the file, never parser-internal state.

**3. Blast radius — what a database dump would actually yield.** Worth stating as a design target,
since it drives the storage decisions already made in §12.4:

| Should be in the DB | Must NOT be in the DB |
|---|---|
| Extracted fields (merchant, amount, date, currency) | Raw email bodies or HTML |
| Gmail `message_id` as a reference | Attachment bytes |
| `source = GMAIL`, parser name/version, confidence | Plaintext refresh tokens |
| Encrypted refresh token + `key_id` | Delivery addresses, phone numbers, other people's names |

The single highest-value asset here is the **refresh token** — it is a live credential to a user's
mailbox, not merely a record. Encryption (§12.1) with the key held outside the database is what
makes a dump non-catastrophic: ciphertext without the key is inert. This is the concrete reason
§12.1's key management is a blocking decision (§19) rather than an implementation detail.

**4. Row-level ownership — reuse the existing guard.** Every Gmail-created row inherits `user_id`
from the connection, and every read path must verify ownership rather than trusting an id in the
URL. Finora already has the mechanism:
`OwnershipGuard.requireOwned(...)` (`backend/src/main/java/com/finora/security/OwnershipGuard.java`),
used by `ImportSessionService.getOwnedSession` and others. Gmail endpoints use the same guard — no
new pattern, and specifically *not* a hand-rolled ownership check per endpoint, which is how the
"user A reads user B's data" class of bug gets introduced.

**5. Database-level privilege separation** (sensitive tables in a separate schema with distinct
grants) is a reasonable defense-in-depth idea but is a platform-wide database-architecture change
on a managed Railway Postgres, affecting migrations, connection pooling, and every existing table.
Flagged as worth considering platform-wide; explicitly **not** proposed as something Gmail sync
introduces unilaterally.

## 13. Privacy and consent UX

Google's own consent screen is necessary but not sufficient — it speaks in Google's vocabulary
(`gmail.readonly`), not in terms of what Finora actually does. A Finora-authored explanation screen
precedes the redirect (§2 step 2):

```
  Connect Gmail

  Finora looks for transaction emails — receipts, orders,
  bookings, and payment confirmations — and turns them into
  transactions you review before anything is saved.

  We never send, modify, or delete any of your email.

  What we store:  the amount, date, and merchant we extract,
                  plus a reference to the original message.
                  We do not store copies of your emails.

  You can disconnect at any time, and choose whether to keep
  or remove transactions that came from Gmail.

                                  [ Cancel ]  [ Continue ]
```

**Be precise about the scope-vs-practice gap, rather than papering over it.** `gmail.readonly`
technically grants read access to the entire mailbox — Google's consent screen will say so, and a
careful user will notice the mismatch if Finora's own copy claims narrower access than the grant
allows. Honest framing: *"Google's permission covers read access to your mail. Finora only fetches
and processes messages matching transaction patterns from known merchants."* Claiming Google is
only granting receipt access would be false, and getting caught in that costs more trust than the
broad scope does.

Also required, both for user trust and Google's own OAuth verification review:

- **Disconnect always available** in Settings, not buried
- **Plain-language "what we store"** (per the screen above) reachable after connecting, not just at
  consent time
- **Ability to remove Gmail-originated transactions** — the second disconnect question (§12.4)
- **Privacy Policy updated** before launch to cover Gmail data handling, and specifically to satisfy
  Google API Services User Data Policy (including its Limited Use requirements). This is a
  verification prerequisite, not a nice-to-have — see §3.

### 13.1 Review UX — the activation risk hiding inside the correctness design

Everything about this design routes through user review, which is correct for trust and is the
reason a spoofed or misparsed row cannot reach the ledger. But it creates a real product failure
mode: **a 90-day first sync (§4) can produce hundreds of staged rows, and a wall of 300 rows is
abandoned, not reviewed.** A user who abandons review gets zero value from the feature and has
handed over mailbox access for nothing — the worst possible outcome, and one that would show up in
§16.3's `first_import_review_completed` metric as a cliff.

Correctness and completion are not actually in tension here, as long as bulk approval is gated on
the confidence signals the design already computes (§10):

```
  Amazon                                    45 transactions
  High confidence · verified sender · AmazonParser v2
                                          [ Approve all 45 ]

  Uber                                      23 transactions
  High confidence · verified sender · UberParser v1
                                          [ Approve all 23 ]

  ⚠ Needs your attention                     6 transactions
  Possible duplicates of existing bank transactions
                                          [ Review individually ]

  ⚠ Lower confidence                         3 transactions
  Shared mailbox — we can't confirm these are yours
                                          [ Review individually ]
```

Rules, so "bulk approve" never becomes "rubber-stamp anything":

- **Grouped by merchant**, because that is the unit a user can actually judge at a glance ("yes, I
  shop at Amazon a lot") — 45 Amazon rows are one decision, not 45.
- **Bulk approval requires High extraction confidence AND High ownership confidence.** Anything
  medium/low is individually reviewed, by construction.
- **Never bulk-approvable:** rows with a duplicate warning (§11), rows from a `SHARED`/`BUSINESS`
  mailbox (§10), and anything a generic parser produced — which in MVP cannot reach review at all
  (§10.3).
- **Default to nothing pre-selected.** The user opts into a bulk action; it is never the passive
  outcome of clicking through.

This is what makes §10.3's conservative trust ladder affordable in product terms: because only
high-confidence, verified-sender, merchant-parser rows reach review at all, the bulk path is safe
for the large majority of them, and the individually-reviewed remainder stays small enough that
users actually work through it.

## 14. Background sync approach

`GmailSyncWorker` follows the exact `@Scheduled` shape already established twice in this codebase
(`ImportJobWorker.poll()`, `ImportSessionService.scheduledSweep()`): `fixedDelayString`/
`initialDelayString` (never `fixedRate` — a slow run must not overlap the next one), gated by an
`enabled` flag for deterministic tests, on its own dedicated executor bean in
`BackgroundWorkConfig` rather than sharing one of the existing small (`core=1, max=2`) pools —
Gmail API latency and existing import-worker latency have no reason to compete for the same two
threads.

### 14.1 Operational limits — one mailbox must not starve the platform

The 90-day initial window (§4) bounds the *time range*, not the *volume*: a heavy mailbox can hold
tens of thousands of matching messages inside 90 days. Without explicit caps, one such user
monopolizes the worker pool and every other user's sync waits behind it. Concrete, configurable
bounds:

| Limit | Starting value | Why |
|---|---|---|
| Messages processed per sync run | 5,000 | Bounds a single run's cost; the remainder resumes next run via the checkpoint (§7) |
| Wall-clock budget per connection per run | ~5 min | A slow mailbox yields the thread rather than holding it indefinitely |
| Sync runs per connection per day | Bounded | Prevents a pathological connection consuming a disproportionate share of Gmail API quota (§4) |
| Concurrent connections syncing | Pool-sized | The dedicated executor above is the natural limit |

The important property: **hitting a limit is a pause, not a failure.** The checkpoint and
idempotency model (§7) already make "stop here, resume next run" safe and correct, so these caps
need no special error handling — a capped run completes normally and the next run continues from
where it stopped. Worth surfacing in the user-facing panel (§16.2) when a first sync is still
catching up, so "we're still importing your history" reads as progress rather than as being stuck.

## 15. Error handling and retry strategy

Translate Gmail/Google-specific failures into the same three-tier vocabulary
`ErrorCode.RetryPolicy` already establishes (`FAIL_FAST` / `RETRY` / `RETRY_ONCE_THEN_ALERT`),
via a Gmail-specific classifier implementing the same contract `ExceptionClassifier` does, rather
than inventing a second retry vocabulary next to the one that already exists:

| Condition | Policy | Why |
|---|---|---|
| Token revoked/expired by user externally | `FAIL_FAST` (+ flip connection to `REAUTH_REQUIRED`) | Retrying against a dead token forever wastes quota and never succeeds; needs the user, not a backoff |
| Gmail API rate-limited (429) | `RETRY` | Expected, resolves on its own once the window passes |
| Single message fails to parse | Skip that message, continue the batch | Same "one bad row isn't a batch failure" stance the CSV/PDF pipeline already takes |
| Unrecognized/unexpected exception | `RETRY_ONCE_THEN_ALERT` | Matches `ImportJobWorker`'s own default for the unclassified case |

This also directly closes a real gap the research surfaced: **no outbound rate-limit/backoff
pattern exists anywhere in this codebase today** (`RateLimiter`/`RateLimitFilter` are inbound-only;
the closest existing outbound-API caller, `ResendEmailProvider`, has bounded timeouts but no retry
logic at all). Gmail sync would be the first caller to actually need this — worth building it as
something the next outbound integration can reuse, not something bespoke to Gmail.

### 15.1 Token invalidation is a user-facing event, not just a status flip

A Gmail connection dies for reasons entirely outside Finora's control and with no warning: the user
changes their Google password, removes Finora from their Google account permissions, Google flags
suspicious activity, or the refresh token is revoked or expires from disuse. All present the same
way — the next refresh attempt fails.

Flipping `status` to `REAUTH_REQUIRED` (§15's table) is correct but insufficient on its own: **the
silent-failure mode is the real risk.** Sync quietly stops, the user notices nothing, and weeks
later they have an incomplete financial picture they still believe is complete. That is worse than
the feature never having been connected.

Required on transition to `REAUTH_REQUIRED`:

- **In-app banner** on dashboard and Settings — persistent until resolved, with a one-click
  Reconnect
- **Email notification** — reusing the existing email infrastructure (`ResendEmailProvider`), since
  a user who has stopped opening the app is precisely the one who needs telling
- **Push notification** — later, once mobile notification infrastructure exists; not MVP

Copy should state the consequence, not just the state: *"Your Gmail connection expired. Reconnect to
continue automatic transaction detection — we haven't been able to check for new receipts since
[date]."*

## 16. Monitoring requirements

`GmailSyncWorker` plugging into `WorkerObservability`'s existing contract
(`observability.begin(...)` / `.completed()` / `.retryScheduled()` / `.deadLettered(...)`)
inherits the existing metric families (`finora.worker.executions/completed/retries/dead_letters/
failures/duration/queue_wait_time`), Sentry breadcrumbs, and MDC correlation for free — no new
observability plumbing needed for the worker-level view.

### 16.1 Admin-facing

Gmail-specific additions, because the worker-level view alone would miss the failure mode that
matters most here:

- `connections_total` by status (`CONNECTED`/`REAUTH_REQUIRED`/`REVOKED`) — how many users are
  actually mid-sync vs. silently stalled needing reauth.
- **Per-merchant-parser success rate, alertable individually.** As noted in §8, a merchant changing
  their email template is silent at the code level — nothing throws, the parser just starts
  matching nothing or extracting garbage. A single aggregate "Gmail sync health" number would not
  surface this; per-parser tracking is the only way this failure mode gets noticed before a user
  does.
- **Failed sync count** and dead-lettered sync jobs, by failure code.
- `untrusted_sender_rejection_rate` (§12.2) — a sharp rise means either a spoofing attempt or, more
  likely, a legitimate merchant domain that needs adding to the registry.
- `staged_duplicate_flag_rate` for Gmail-sourced rows specifically — an early proxy signal for how
  big §11's unsolved cross-source dedup gap actually is in practice, useful input for prioritizing
  the Phase 2 work.
- **Extraction-correction rate per parser+version (§9.1)** — the fastest detector of a merchant
  template change, because it catches confidently-wrong extraction that success rate cannot see.
- `detected_not_staged` counts by merchant domain (§10.3) — the prioritized list of which parser
  to write next, straight out of real user mailboxes rather than guesswork.

Surfaced on the admin Import Health dashboard already scoped in
`docs/proposals/data-import-intelligence-proposal.md` §3.1, filtered by the `source = GMAIL`
discriminator (§6) — extending an existing page rather than building a parallel one.

### 16.2 User-facing

Settings → Gmail Connection should answer "is this actually working?" without the user having to
infer it from whether transactions appeared:

```
  Gmail Connection                      ● Connected

  Account            r****@gmail.com
  Last synced        2 hours ago
  Discovered         47 transactions (12 awaiting review)

  [ Sync now ]   [ Disconnect ]
```

In `REAUTH_REQUIRED`, this same panel is the resolution point for §15.1's banner and email.

### 16.3 Product analytics — measuring the activation claim

The stated business case is faster time-to-value (§1). That is a testable claim, and worth
instrumenting to find out whether it holds rather than assuming it:

| Event | Answers |
|---|---|
| `gmail_connect_started` | Do users who see the entry point actually try it? |
| `gmail_connect_completed` | Where does the OAuth flow lose people — Finora's screen, or Google's consent? |
| `first_sync_completed` | Does the first sync actually finish, and how long does it take? |
| `first_import_review_completed` | Do users complete the review, or abandon a large queue? |
| `transactions_discovered_first_day` | Is the 90-day window (§4) yielding a useful volume? |
| `transactions_confirmed_first_day` | Discovered-vs-confirmed is the real extraction-quality signal |
| `parser_success_rate` | Shared with §16.1 — product and ops care about the same number |

The discovered-vs-confirmed gap is the most valuable of these: a large gap means Finora is
surfacing rows users don't consider real transactions, which is an extraction-quality or
ownership-confidence problem (§10) showing up as a product metric before it shows up as a support
ticket.

Note this repo has no analytics/event-tracking infrastructure today — worth confirming what these
events would be emitted *into* before committing to the list (§22).

## 17. Premium feature positioning considerations

Real entitlement/plan-based gating does not exist in this codebase — confirmed via
`docs/proposals/billing-subscription-entitlements-proposal.md`, itself still proposal-only with
zero `Plan`/`Subscription` entities. The one gating mechanism that does exist, `FeatureFlag`, is a
single global boolean with no per-user/plan dimension and **fails open** (`.orElse(true)` — an
unknown key defaults to enabled) — the opposite of what a paywall needs, and that proposal's own
"Correction #3" already flags this exact danger for future entitlement work.

Not deciding the product question here (whether/when this should be a paid feature is yours to
call), but flagging the mechanism constraint plainly: **don't repurpose `FeatureFlag` as the actual
paywall** — it is the wrong default-failure direction for gating a feature you intend to restrict.
If Gmail sync needs to ship ahead of real billing (e.g. as a manual beta), an explicit, fail-closed
allowlist is the safer interim mechanism than stretching a flag that was designed to default to "on."

### 17.1 Candidate positioning — "Smart Inbox"

Gmail sync is the anchor of a coherent premium tier rather than a standalone paid toggle. A
possible split:

| Free | Premium — "Smart Inbox" |
|---|---|
| Manual transaction entry | Gmail sync / automatic receipt detection |
| CSV & PDF statement import | Subscription & recurring-payment discovery |
| Core categorization | Renewal reminders before charges hit |
| | Improved categorization from receipt-level detail |

The strategic argument for grouping rather than selling Gmail sync alone: a receipt stream is
*qualitatively* richer than a bank line — it carries line items, merchant identity, and renewal
dates a statement never will. Subscription discovery ("Netflix renews tomorrow, ₹649") is the
clearest example of value that is only possible with this data source, and is a more compelling
premium hook than "we import your email" on its own.

**Design implication regardless of the pricing decision:** build the entitlement *seam* now even
though billing doesn't exist. A single check at the connect endpoint and the sync worker
(`canUseGmailSync(userId)`), backed by a fail-closed allowlist initially, means switching to real
plan-based entitlement later is one implementation change rather than an audit of every call site.
That is cheap now; retrofitting a paywall into a feature that assumed it was free is not.

## 18. Rollout plan

**The MVP rule, in one line:** *known merchant domain + authenticated sender + merchant-specific
parser + user confirmation = transaction. Anything else = detected email, not a transaction* (§10.3).

**Phase 1 (MVP)**
- Single Gmail account per user, `gmail.readonly` only, manual connect/disconnect
- Polling sync (no push), `historyId` incremental cursor, **90-day bounded initial window** (§4)
- **Email body only — no attachment processing** (§12.6); no raw HTML rendered anywhere (§12.3)
- Sender authentication (DKIM/SPF + registry) mandatory before any parse (§12.2)
- Hand-written parsers for the named merchant set (Amazon, Myntra, Uber, Ola, Zomato, Booking.com),
  each versioned and fixture-tested (§8.1); **generic parser output is `DETECTED_NOT_STAGED`, never
  a transaction** (§10.3)
- Ownership-confidence model + `mailbox_kind` declared at connect (§10)
- Merchant order/event keys captured but only `PURCHASE` acted on (§10.1)
- All output goes through the existing staging/review flow — no auto-write path
- Confidence-gated bulk review by merchant, nothing pre-selected (§13.1)
- Non-blocking duplicate warning in review (§11); no automatic cross-source reconciliation
- Per-message checkpointing, idempotency, and operational sync caps (§7, §14.1)
- Encrypted refresh tokens via the new `com.finora.security.crypto` platform service (§12.1)
- `source = GMAIL` visible on every transaction, user-facing (§16.2) and admin-facing (§16.1)
- Audit logging via the existing `AuditService` (§12.5)
- Reauth notification: in-app banner + email (§15.1)
- Gated behind a fail-closed allowlist via a `canUseGmailSync(userId)` seam (§17.1)

**Phase 2**
- Cross-source (bank statement vs. email) fuzzy duplicate reconciliation — §11's open problem
- Refund/cancellation linking, built on the existing `refundOfTransactionId` model (§10.1)
- Attachment handling: invoice PDFs routed into Finora's existing PDF engine — **gated on the
  security sequence in §12.6**, and ideally on the isolated-parser-worker platform improvement,
  since that benefits the existing PDF upload path too
- Subscription / recurring-payment discovery (the §17.1 premium hook), using
  `merchant_account_identifier` (§10.2)
- Generic/LLM extraction allowed to reach staging, once it can carry a defensible confidence score
- Real entitlement-based gating, once billing/subscriptions actually ships
- Whatever general account-data-deletion capability the platform builds, extended to cover this

**Phase 3+**
- Gmail push notifications (`users.watch()` + Pub/Sub) for near-real-time sync
- Multiple Gmail accounts per user
- Non-India merchant coverage (the currency field from §9 is the groundwork)
- LLM-based extraction fallback for long-tail merchants (§9)

## 19. Implementation dependencies — resolve before any code

Explicitly gating: none of these are engineering tasks, and all of them block or reshape
implementation if answered late. **The five marked ⚑ are the ones flagged in review as
must-decide-before-development.**

| Dependency | Needed before | Owner |
|---|---|---|
| ⚑ Encryption approach + key storage + rotation decision (§12.1) | The first token is ever persisted | Eng lead |
| ⚑ OAuth environments: production callback + Railway dev callback (see below) | Any integration testing | Ops |
| ⚑ Trusted sender registry design — confirmed as a DB table (§12.2); admin UI scope still open | Sender authentication is implemented | Eng + Product |
| ⚑ Analytics/event sink for §16.3 — none exists today | Instrumentation is written | Eng + Product |
| ⚑ Review UX incl. bulk approval (§13.1) — needs design, not just engineering | Review screen is built | Product/Design |
| Google Cloud OAuth verification + CASA assessment started (§3) | Any non-test-user rollout | Product/Legal |
| Privacy Policy updated for Google API Services User Data Policy (§13) | OAuth verification submission | Product/Legal |
| DB migration plan — where these tables sit relative to in-flight migrations | First migration written | Eng |
| Feature-flag / allowlist mechanism decided (§17.1) | Connect endpoint exists | Eng + Product |
| Production secrets provisioned (client id/secret, encryption key) | Any deployed environment | Ops |

**Note on the last two:** Finora's production backend is `https://api.finoratech.info`; no
staging/dev URL is documented anywhere in the repo today (Railway "Dev" and "Production"
environments exist per `docs/security/secrets-and-iam-audit.md`, but no dev domain is recorded).
Google OAuth requires exact redirect URIs registered per environment, so **a separate OAuth client
and a known dev callback URL are prerequisites, not details** — and the dev URL needs to be pulled
from the Railway dashboard and written down before this is configurable at all.

**Migration-collision warning:** this repo runs parallel development sessions and has already had a
real Flyway version collision (two `V75`s). Whoever writes these migrations should claim version
numbers immediately before merging, not at the start of the work.

## 20. Estimated effort

| Component | Effort |
|---|---|
| `com.finora.security.crypto` platform service + key management (§12.1) | M — new platform capability, benefits every future integration |
| OAuth connect/callback/disconnect flow + `GmailConnection` model | M |
| `GmailSyncWorker` + Gmail API client + backoff/retry classifier | M |
| Sender authentication + HTML sanitization (§12.2, §12.3) | S–M |
| Merchant parsers (6 merchants + generic fallback), versioned + fixtures | M–L, and ongoing (§8) |
| Ownership-confidence model + `mailbox_kind` onboarding (§10) | S |
| Merchant order/event + account identity capture (§10.1, §10.2) | S (fields only; no linking logic in MVP) |
| Staging bridge into existing `ImportSession`/`StagedRow` flow | S (mostly reuse) |
| Non-blocking duplicate warning UX (§11) | S–M |
| Bulk review UX, confidence-gated (§13.1) | M — needs design, and it is activation-critical |
| Trusted domain registry + admin management (§12.2) | S |
| Extraction-correction capture (§9.1) | S (category learning already exists, unchanged) |
| Audit logging via existing `AuditService` (§12.5) | XS (reuse) |
| Reauth notification (banner + email) (§15.1) | S |
| User-facing connection panel (§16.2) | S |
| Admin dashboard `source=GMAIL` labeling (§16.1) | S (extends existing dashboard) |
| Monitoring + product analytics events (§16) | S–M (assumes an analytics sink exists) |
| Cross-source duplicate reconciliation | Not estimated — Phase 2, genuinely unscoped |
| Refund/cancellation linking | Not estimated — Phase 2 (§10.1) |

Google's own OAuth verification/CASA review timeline (§3) runs in parallel to all of the above and
is not reflected in this table — it is a schedule dependency Finora does not control, not
engineering effort.

## 21. Explicitly out of scope (this document)

- Any code, migration, dependency, or endpoint — this is a design proposal only, per instruction.
- "Sign in with Google" login (see scope note at top) — a different feature.
- Cross-source fuzzy duplicate *reconciliation* — the advisory warning (§11) is MVP; automatic
  matching/linking is deliberately not designed here.
- Attachment/invoice-PDF handling and subscription discovery — Phase 2 (§18), flagged not designed.
- LLM-based extraction — flagged as future (§9), not designed further here.
- Multiple Gmail accounts, push notifications, non-India merchants — Phase 3+ (§18).
- The product decision of whether/when this is a paid feature (§17) — flagged, not decided here.
- General account-data-deletion capability — recommended as separate platform work (§12.4).

## 22. Open questions for whoever implements this

**Resolved since Revision 1:** the trusted sender registry is a database table, admin-managed and
audited, not code-defined (§12.2). `GenericReceiptParser` output does not reach staging at all in
MVP, which removes the "what confidence threshold justifies staging it" question entirely (§10.3).

Still open:

- `gmail_sync_jobs` as its own table vs. extending `import_jobs` (§6) — a real tradeoff, not
  resolved here.
- Where the encryption key lives, and the rotation mechanism's exact shape (§12.1) — needs an
  answer before the first token is persisted.
- Whether `history_cursor` per connection needs a periodic full-resync fallback (a `historyId` can
  expire/become invalid after ~7 days per Gmail API's own documented behavior).
- Gmail API quota sizing at 10k+ connected accounts (§4) — needs real usage numbers to answer;
  affects sync frequency and batching strategy.
- What analytics sink §16.3's events emit into — no event-tracking infrastructure exists today.
- Whether `DETECTED_NOT_STAGED` records (§10.3) are surfaced to the user at all ("we saw 12
  receipts we can't read yet") or kept purely as an internal prioritization signal. Showing them
  is honest but risks reading as a broken feature.

## 23. Relationship to the broader platform ambition

A reasonable strategic reading of this feature is that Gmail should not be a feature at all — it
should be the first connector in a financial-data-intelligence platform (SMS, UPI, card feeds,
investment and insurance mail), feeding a shared intelligence layer with source-trust metadata,
an evidence vault, subscription intelligence, anomaly detection, and eventually natural-language
financial Q&A. **That direction is sound, and this document is deliberately compatible with it**
(§5.1's connector seam; the source/confidence/evidence metadata in §6 and §10 generalizes beyond
Gmail unchanged).

Two things are worth stating plainly so that ambition sharpens rather than stalls the work:

**1. Much of the "intelligence engine" already exists — it is not greenfield.** Finora already
has an evidence engine (`com.finora.imports.evidence`: `EvidenceAssessor`, `MetadataEvidencePipeline`,
`FieldFact`, `Correlation`, governed by ADR-006), merchant intelligence (`Merchant`,
`MerchantAlias`, `MerchantCategoryMap`, `MerchantCategoryLearning`, `MerchantNormalizationEngine`),
categorization with a learning tier, duplicate detection, and a document-intelligence contract
(ADR-004, ADR-005). A platform design that draws these as new boxes to build would propose
rebuilding shipped, ADR-governed work. The right platform document starts from what exists and
names the genuine gaps.

**2. Platform scope belongs in a platform document, not this one.** Evidence vault, receipt
line-item understanding, subscription intelligence, fraud/anomaly detection, household multi-user,
and natural-language financial Q&A are each their own proposal with their own risk and sequencing —
several are larger than this entire feature. Folding them in would make this document unreviewable
and would blur the line between "approved for implementation planning" and a roadmap wishlist. It
would also put an unshipped MVP behind abstractions justified by connectors that do not exist yet.

**Recommended sequencing, for whoever owns the roadmap decision:** ship Gmail concretely against
this design, with the connector seam kept clean (§5.1). Let connector #2's real requirements —
not anticipated ones — drive extracting the ingestion abstraction. Track the platform vision as a
separate document (`docs/proposals/financial-data-intelligence-platform-proposal.md`) that starts
from the existing evidence/merchant/categorization engines and identifies actual gaps. That
sequencing gets the platform built on evidence rather than on speculation, and does not make v1
wait for it.
