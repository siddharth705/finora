# Statement Import — the flow

How a bank statement gets from a file on someone's phone into the ledger, and what every step is
allowed to assume about the one before it.

This is the **pipeline and its contract** — endpoints, states, error codes, what each client does.
It is deliberately not about *parsing*: which document layouts the engine understands, and how that
set grows, is
[financial-document-intelligence-principles.md](../../architecture/system-design/financial-document-intelligence-principles.md), with
the running record in
[financial-document-intelligence-changelog.md](../../project-management/milestones/financial-document-intelligence-changelog.md).

---

## The shape

Every import, whatever the format, is three steps:

```
  stage  ──────────►  review  ──────────►  confirm
  (parse, don't       (user edits,         (write transactions,
   write anything)     picks an account)    record a StatementImport)
```

**Staging never writes to the ledger.** It parses, persists what it parsed as an `ImportSession`
(rows, the original file bytes, the detected account, the document context), and returns a
`sessionId`. Nothing a user sees during review exists in their accounts yet.

**Confirm carries the session id, not the file.** The bytes are already server-side from staging
(ADR-0002) — re-uploading them at confirm was the earlier design and is gone. `sessionId` is what
ties the two halves together.

Sessions live **48 hours** (`ImportSessionService.SESSION_TTL`). Expired ones are cleaned up
opportunistically the next time the same user stages something, and confirming against one fails
with a clear "upload the statement again" rather than a generic error. `GET /import/sessions` is
what lets a client offer to resume an unfinished review after a reload.

---

## Endpoints

| | |
|---|---|
| `POST /api/v1/import/csv/stage` | multipart. `file`. |
| `POST /api/v1/import/pdf/stage` | multipart. `file`, optional `password`. |
| `POST /api/v1/import/csv/confirm` | JSON. Confirms **any** single-account session, CSV or PDF. |
| `POST /api/v1/import/pdf/confirm-multi` | JSON. Only for a session where staging returned `multiAccount: true`. |
| `GET /api/v1/import/sessions` | Unfinished sessions, for "resume where you left off". |
| `GET /api/v1/import/sessions/{id}` | One session's staged rows. |
| `DELETE /api/v1/import/sessions/{id}` | Abandon a staged session. |
| `POST /api/v1/statement-imports/{id}/reimport` | JSON, body optional. `{ password? }`. Replays a past import. |
| `POST /api/v1/statement-imports/{id}/reimport/confirm` | JSON. |

`/csv/confirm` confirming a PDF session is not a naming slip — the confirm contract is identical
regardless of which staging path produced the session, and one endpoint is the point.

**Limits.** Staging is rate limited to **10 requests per 10 minutes per IP**
(`RateLimitFilter.importStageLimiter`, covering both stage endpoints) and gated to **6 concurrent
parses** (`ImportConcurrencyLimiter`, configurable via `app.import.max-concurrent`); BH-043:
exceeding the limit is rejected immediately (no wait) with `IMPORT_006`. Uploads are capped at
**10 MB** (`UPLOAD_MAX_FILE_SIZE`).

---

## Password-protected PDFs

Most Indian banks e-mail statements protected by default, so this is a normal path, not an edge
case.

`PdfTextExtractor` is the only place that learns a document is encrypted, so it is where the two
outcomes are distinguished. PDFBox cannot do it: opening an encrypted PDF with **no** password and
with the **wrong** password both raise `InvalidPasswordException` carrying the identical message.
The only thing that separates them is whether the call was given a password.

| Code | Means | What the UI does |
|---|---|---|
| `IMPORT_008` | Encrypted, no password supplied | Ask for one |
| `IMPORT_009` | Encrypted, supplied password rejected | Keep the prompt open, inline error, **keep what was typed** |

Both are `422`, not `500` — a locked file is the user's to fix, not a server fault. They stay
separate because the response differs: clearing the field on a rejected password reads as though
the app lost the file, and a one-character typo should be a correction rather than a retype.

**Rules the implementation holds to:**

- The password is **never persisted** — not on `ImportSession`, not on `StatementImport`.
- It is **never logged**, and never appears in an exception message or cause chain.
- It travels in the **multipart body**, never a query string. A password in a URL is captured by
  access logs, proxy logs, browser history and `Referer` headers.
- Passing one to an **unencrypted** document is harmless — PDFBox ignores it. Clients never have to
  work out whether a file needs one before deciding what to send.
- A blank password is treated as **no password**, not a wrong one.
- Brute force is bounded by the existing staging rate limit.
- Staging parses **before** creating the session, so a failed attempt leaves no orphaned row and
  the retry is simply the same request again.

### Where the field appears, and why it differs by flow

Both flows reach the same two error codes; they differ only in whether they ask up front.

| | Behaviour | Why |
|---|---|---|
| **Upload** (`Import.tsx`, `ImportScreen.tsx`) | Selecting a PDF opens a panel with the filename and an **optional** password field before anything is sent | A failed attempt means pushing the whole file over the network for nothing. Asking first is cheaper than a guaranteed wasted upload. |
| **Re-import** (`StatementHistory.tsx`, `StatementHistoryScreen.tsx`) | Tries **without** a password; prompts only on `IMPORT_008` | The bytes are already server-side, so "just try it" is one small request. Every statement that never needed a password — most of them — keeps its single-click re-import. |

The panel is **PDF-only**. A CSV has nothing to unlock, so it still uploads on selection in one
action; adding a step there would be pure cost.

---

## Re-import

"Re-import Statement" replays the **original stored bytes** back through staging, already scoped to
the account the statement belongs to (no "create new account" choice). Duplicate detection then runs
against everything on the books — including this same statement's own earlier transactions, which
is the point: it is how a re-parse after an engine improvement gets reviewed rather than blindly
re-applied.

Two things worth knowing:

- **Only staging re-parses.** `ImportService.confirm()` builds transactions from the rows in the
  request and never touches the file bytes. So a password stops being needed the moment staging
  returns — it does not have to survive to the confirm step, and nothing holds it in between.
- **Routing follows `sourceFormat`, not the filename.** A statement recorded as PDF re-parses
  through the PDF path even if its name says otherwise. `sourceSectionIndex` does the same job for
  a composite statement: section N re-parses as section N, so re-import cannot silently replay a
  different account's transactions against this one.

Available on **both clients**. On mobile the review happens in the Import *tab* rather than a
second copy of the review UI: `StatementHistoryScreen` stages the rows, then navigates to `Import`
with a `ReimportParams` payload. That payload carries a **nonce**, because a tab's params outlive a
visit — without one, a later tap on the Import tab would re-enter the same re-import, and the
statement id alone cannot distinguish that from a genuine second re-import of the same statement.

---

## Duplicate review

A staged row that looks like something already on the books does **not** get quietly dropped. It
arrives carrying `duplicateMatch` — the existing transaction's id, date, description, amount, when
it was imported, how many existing transactions match, and the reason — and the user decides.

The gate: `Confirm Import` stays disabled while any flagged row is still unresolved. That is the
whole point. Duplicate detection used to be a filter, which was wrong in both directions — a genuine
re-import got skipped without anyone confirming it should be, and two identical coffees bought on
the same day got skipped too. Neither outcome was ever a decision anyone made.

Three things the shape of the data enforces:

- **`confidence` is always `EXACT`, and is not rendered as a score.** The detector matches on date
  **and** amount **and** description being identical. A percentage would imply a spectrum it cannot
  produce.
- **`matchCount > 1` argues *for* importing, not against.** Several identical existing transactions
  usually means the user genuinely transacts this repeatedly — a daily fare, a split bill — which is
  exactly the case where skipping is wrong. The review screen says so.
- **The reason comes from the detector, not the UI**, so the explanation shown is the one the system
  actually used.

`apply to similar` is bounded to rows still unresolved: a bulk action must never overwrite a choice
already made by hand.

**The decision travels with the row.** `ConfirmedRow.confirmedNotDuplicate` carries the user's
*answer* alongside `likelyDuplicate`, which is only the engine's *guess*, and confirming an
"Import anyway" row stamps `transactions.not_duplicate_confirmed_at`. `ReconciliationService`'s
duplicate pass skips rows carrying that stamp.

This is not belt-and-braces. Without it the milestone gate measured a ledger holding ₹1,618.50 while
the dashboard reported ₹1,528.50 — the ₹90 difference being exactly the two fares the user had
explicitly asked to import. Reconciliation runs after every import, create, edit *and* delete, and
it cannot tell "the same statement uploaded twice" from "two metro fares on one day" without being
told; every spend calculation filters `is_duplicate_of IS NULL`, so the decision was honoured in the
ledger and reversed in the numbers. The stamp is persisted rather than applied once so the ruling
survives the user's next unrelated action too.

A client that sends no decision — the mobile app, which has no duplicate review screen — behaves
exactly as before.

**Multi-account PDFs get the same review, one per detected account** (Milestone 2 item 4). This was
the last path still auto-unticking flagged rows, and the fix was not a second copy of the component:
the include flags and the decisions that gate them are now one value, built together by
`beginReview()` in `frontend/src/lib/importReview.ts`, so a path cannot untick a row without also
producing the unresolved decision that blocks the import until the user answers it. See
**Multi-account PDFs** below for what that looks like on screen.

---

## Multi-account PDFs

One file can describe more than one account — a composite statement bundling a savings account and
a credit card. Staging returns `multiAccount: true` with a `sections` array instead of `staging`,
and each section is reviewed and assigned independently, then confirmed together via
`/pdf/confirm-multi`.

**Each section carries its own review**, not a share of one. A decision is about a row in a specific
account's ledger, and two sections routinely flag the same description against different existing
transactions — merging them into one list would present two questions as one. So each section card
renders its own duplicate review, `apply to similar` reaches only inside the section it was used in,
and `confirmedNotDuplicate` is built per section by the same `toConfirmedRows()` the single-account
confirm uses.

**The gate is shared, because the confirm is.** `/pdf/confirm-multi` posts every section together —
this is not N imports the user can partially approve — so one unanswered row anywhere disables
`Confirm All N Accounts`, exactly as one unanswered row disables `Confirm Import`. The blocking
message names the account still outstanding: one button over N sections, with the reason several
screens up, is otherwise a dead end.

**Mobile does not support this.** `ImportScreen` discards the session and says so plainly rather
than importing only the first section, because assigning a section to the wrong account files
someone's transactions against the wrong balance.

---

## Error codes

From `com.finora.exception.ErrorCode`, arriving as `errorCode` on the standard `ApiResponse`
envelope. The two the clients **branch on** rather than merely display are `IMPORT_008` /
`IMPORT_009`; they are mirrored in `frontend/src/api/errorCodes.ts` and `mobile/src/api/errorCodes.ts`.

| Code | Status | Meaning |
|---|---|---|
| `IMPORT_001` | 422 | No transaction table found — the document's layout defeated detection |
| `IMPORT_002` | 400 | No account chosen and no new-account details given |
| `IMPORT_003` | 400 | New account has no name |
| `IMPORT_004` | 403 | That account belongs to someone else |
| `IMPORT_005` | 404 | Account not found |
| `IMPORT_006` | 503 | Too many imports in flight |
| `IMPORT_007` | 422 | A table **was** found, but every row in it was rejected |
| `IMPORT_008` | 422 | Password-protected, none supplied |
| `IMPORT_009` | 422 | Password supplied and rejected |

`001` and `007` are separate on purpose, for the same reason `008` and `009` are: the follow-up
differs. Folding `007` into `001` is what once let a real statement import as a silent, confirmable
no-op.

---

## Client differences

| | Web (`frontend`) | Mobile |
|---|---|---|
| CSV upload | On selection | On pick |
| PDF upload | Password panel, then upload | Password card, then upload |
| Multi-account PDF | Full per-section review | Refused with an explanation |
| Re-import | Yes, with password prompt | Yes, with password prompt |
| Statement history | `/app/statements` | More ▸ Statement History |
| Original file | Browser download | Written to the cache directory and handed to the native share sheet |
| Resume a session | Yes | No — `importApi.listSessions()`/`getSession()` exist in `mobile/src/api/endpoints.ts` for API parity, but no mobile screen calls them; closing the app mid-review loses the in-progress staging state |

"Download" is the one place the two genuinely cannot match. The web app streams the file into a
Blob and clicks a synthetic `<a download>`; native has neither, and a file written into an app's
sandbox is invisible to the user anyway. Mobile therefore writes the bytes to `Paths.cache` and
opens the share sheet, which is where "save to Files" and every other real destination lives.

The admin portal has no import surface at all — importing is a user action, and admins do not act
on a user's ledger.

---

## What is deliberately not here

- **OCR / scanned PDFs.** Text-based documents only. A scanned statement has no selectable text, and
  the UI says so and points at a CSV export instead.
- **Bank-specific password hints** ("HDFC uses the first 4 letters of your name plus your DOB"). The
  field carries a generic hint only.
- **Remembering a statement password.** Deliberate: see the rules above.
- **Multi-account statements on mobile.** Refused with an explanation rather than partially
  imported — see above.
