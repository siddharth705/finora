# Search Platform — Design Proposal

**Status:** Proposal only. Design after GA blockers, production-safety work, and the current bug hunt
are closed. Same sequencing as every other document in this directory.

**Major correction to the originating draft's premise:** it proposes a universal search layer as if
starting from nothing. **Transaction search already exists, is reasonably sophisticated, and is
already wired end to end**: `TransactionRepository.search()` — a case-insensitive, special-char-escaped
LIKE query (`LikePatterns.escape`, avoiding "2.5% CASHBACK"-style narrations breaking the match) over
`description`, `merchant`, and (via subquery) the linked account's name/holder/branch/IFSC and
resolved bank name — exposed via `TransactionController` and consumed by `Ledger.tsx`'s `?q=` param
(the exact parameter the observability doc documents as scrubbed from monitoring). This is a working,
production-hardened literal search, not a gap.

**What's genuinely missing, confirmed by investigation:** no natural-language parsing (no "March
expenses" → date-range logic anywhere), no full-text/ranked search index (everything is relational
`LIKE`, no `tsvector`, no external search engine), and admin-side search **deliberately** excludes
transactions today — `AdminSearchService`'s own class doc states this explicitly, because neither
transactions nor statement imports has a standalone admin page to link results to. That's a design
constraint to respect, not an oversight to silently override.

## 1. Objective

Extend the existing, working transaction search rather than replacing it: add lightweight
date/amount-phrase recognition for common query shapes, and — only if evidence shows literal LIKE
search is actually insufficient at real usage volume — consider a real search index. Admin-side
transaction search is deferred, not built here, because the reason it's absent today (no admin
transactions page to search into) hasn't changed.

## 2. What exists today (baseline — see correction above for full detail)

- User-side transaction search: literal, multi-field, escaped, production-hardened, already scrubbed
  from monitoring correctly.
- No NLP/date-phrase parsing anywhere — `dateFrom`/`dateTo`/`amountMin`/`amountMax` are explicit UI
  filter fields set via date pickers, entirely separate from the keyword search box.
- Admin search (`AdminSearchController`/`AdminSearchService`): fans across Users, Merchants, Banks,
  Global Rules only — capped at 5 results each, LIKE-based, explicitly and deliberately excludes
  Transactions and Statement Imports.
- No Tickets entity exists anywhere (confirmed again here, consistent with the Support proposal in
  this directory) — the original draft's "Search: Ticket" admin example has nothing to search yet.
- No full-text infrastructure (`tsvector`, Elasticsearch, Meilisearch) anywhere in the stack.

## 3. Proposed scope (v1 — the only thing being designed here)

### 3.1 Lightweight query-phrase parsing (new — thin layer, not a rewrite)

For patterns like "March expenses" or "last month," a small pre-processing step (frontend or a thin
backend layer) that recognizes a bounded set of date-phrase patterns and translates them into the
*existing* `dateFrom`/`dateTo` filter parameters — not a new search backend, a translator in front of
filters that already work. Scope narrowly: relative month names, "last N days," "this month" — not
open-ended NLP. If the recognized pattern doesn't match, fall through to the existing literal keyword
search unchanged, so this is additive and can't make search worse than it is today.

### 3.2 Admin transactions search — only alongside an admin transactions page

The reason admin search excludes transactions today (no page to link results to) is a real constraint,
not a gap to route around. If/when an admin transactions page is built (out of scope here — no such
proposal exists yet), extending `AdminSearchService` to include transactions is straightforward reuse
of the existing user-side query. Not designed further here because the precondition doesn't exist.

### 3.3 Full-text search index — explicitly evidence-gated, not built now

`tsvector`/Postgres full-text or an external engine is real added complexity (index maintenance,
ranking tuning, a new failure mode). The literal LIKE search today is already reasonably capable
(multi-field, escaped, bank-name resolution) and there's no evidence it's insufficient at Finora's
current transaction volume per user. Don't build this speculatively — the trigger to revisit is
measured query latency or user complaints about missed matches, not "search platforms usually have
this."

## 4. Explicitly out of scope

- Rebuilding transaction search — it already works and is reasonably sophisticated.
- Admin transaction/statement-import search — blocked on an admin transactions page existing first,
  which isn't proposed here.
- Ticket search — blocked on the Support proposal's `SupportTicket` entity actually being built.
- Full-text search infrastructure — evidence-gated (§3.3), not built speculatively.
- Fino natural-language transaction lookup ("find my Swiggy payments") — this is exactly what §3.1's
  existing literal search plus (eventually) §3.1's phrase parsing already supports; Fino would call
  the existing search endpoint, not a new one. No Fino work here.

## 5. Estimated effort

| Component | Effort |
|---|---|
| ~~Transaction search~~ | Already built |
| Date/amount phrase parsing (bounded pattern set) | S–M |
| Admin transaction search | Not sized — blocked on an admin transactions page |
| Full-text search index | Not sized — evidence-gated, revisit only if literal search proves
insufficient |

## 6. Open questions for whoever implements this

- Where should phrase parsing live — frontend (translate before the API call, simplest) or backend
  (reusable by mobile too, more consistent)? Given mobile also calls the same API, backend is likely
  right, but this is a real tradeoff to weigh at implementation time, not decided here.
