# Statement Storage Migration — PostgreSQL BYTEA → Cloudflare R2

**Status:** Proposal — decisions recorded, not yet built
**Scope:** Where uploaded statement files live. No parser, workflow or user-visible change.

---

## 1. Objective

Move uploaded statement files out of PostgreSQL `BYTEA` and into Cloudflare R2, preserving existing
import behaviour exactly.

The justification is **architectural, not a size threshold**. A relational database is not the
long-term home for uploaded documents; this is a financial system, not a document store. Measuring
current usage is worth doing, but the result decides *prioritisation*, not *whether to do it*.

## 2. Where files live today

Two tables hold raw bytes, both `NOT NULL`:

```
statement_imports.file_content   BYTEA   -- kept indefinitely (re-import and download need it)
import_sessions.file_content     BYTEA   -- kept 48h, then the session expires
```

`UPLOAD_MAX_FILE_SIZE` is 10 MB, so the database grows by the full size of every statement ever
uploaded, permanently.

Credit where due: the column is already `FetchType.LAZY` with `@JdbcTypeCode(VARBINARY)`, so it is
not dragged into ordinary queries. That limits the runtime cost. It does nothing for storage,
backups or restore.

### 2.1 The bytes are duplicated on write — twice over

This is the part that changes the priority, and it is not obvious from the schema.

`ImportService.confirm()` writes `setFileContent(fileContent)` onto every `StatementImport` row it
creates. Two callers make that a multiplier:

**Multi-account statements store one copy per section.** `confirmMultiSection()` loops over the
detected sections and calls `confirm(...)` once per section, passing the same
`session.getFileContent()` every time. A composite statement with a savings account and a credit
card is stored **twice**; a three-product HDFC combined statement, **three times**.

**Every re-import stores another copy.** `confirmReimport()` passes `original.getFileContent()`
into `confirm(...)`, which creates a *new* `StatementImport` row carrying the same bytes. Re-import
a 10 MB statement three times and the database holds four copies of it. Re-import now exists on
both web and mobile, so this is reachable from everywhere.

Neither is a bug in the current design — a `StatementImport` is meant to be self-contained. But it
means database growth tracks *confirmations*, not *uploads*, and the gap widens as sections and
re-imports accumulate.

**Consequence for the target design:** storage should be **content-addressed** — see §3. Then N
sections and M re-imports of the same file reference one object instead of storing N+M copies, and
the duplication is eliminated rather than relocated to cheaper storage.

### 2.2 Who actually reads the bytes

Five call sites, and it matters for retention:

| Caller | Purpose |
|---|---|
| `ImportService.confirmSession` / `confirmMultiSection` | Copy session bytes onto the new import row |
| `StatementImportService.getFile` | User downloads the original (web download, mobile share sheet) |
| `StatementImportService.reimport` | **Re-parses the original** |
| `StatementImportService.confirmReimport` | Copies bytes onto the new row |

So originals serve exactly two user-facing purposes: **download** and **re-import**.

### 2.3 Three paths delete stored bytes today

Recorded because content-addressing changes what each one means (§3.2):

| Path | Trigger |
|---|---|
| `StatementImportService.delete()` | User deletes an imported statement |
| `ImportSessionService` opportunistic cleanup | An expired (>48h) session swept on the next stage |
| `ImportSessionService.deleteSession()` | User abandons a staged session |

All three are safe today for exactly one reason: **the bytes are a column, so deleting a row
deletes that row's copy and nothing else's.**

## 3. Target model — content-addressed, immutable objects

A statement file becomes an immutable object identified by its content, referenced by any number of
rows.

```
PDF ──SHA-256──► a8d34f9…  (identity)
                     │
                     └──► statements/a8/d3/a8d34f9….pdf   (storage key — internal)
                                     │
         ┌───────────────┬───────────┴───────┬───────────────┐
    Import A        Import B            Import C        Import D
   (section 1)     (section 2)        (re-import)     (re-import)
```

instead of four rows each owning their own 10 MB.

### 3.1 Identity is the hash; the key is an implementation detail

**The SHA-256 is the document's identity. The object key is a private layout decision.** They are
stored separately and must not be conflated: the application looks documents up by hash, and
`StatementStorageService` alone knows a hash maps to `statements/a8/d3/a8d34f9….pdf`.

Why the separation earns its extra column: bucket layout is the thing most likely to change later —
prefix sharding, a different extension convention, a move between buckets or providers. If the key
*is* the identity, none of that is possible without rewriting how every row identifies its
document. Keeping them apart makes a re-layout a background rewrite of keys while identity holds
still.

`statement_imports` and `import_sessions` therefore carry content hash, object key, and existing
metadata — and no bytes.

### 3.2 Deletion becomes reference-aware — the one thing that can lose data

The consequence that does not survive a naive port, and why §2.3 exists.

Once objects are shared, **deleting a row must not delete its object**, because another row may
still reference the same content. Two cases are not hypothetical — they are the normal path:

- **A session and the import it confirms hold identical bytes**, so they resolve to the same
  object. Expiring the session must not remove the object the confirmed import depends on.
- **Multi-section and re-imported statements share one object by design** (§2.1). Deleting one must
  leave the others readable.

Get this wrong and the failure is silent and delayed: the delete succeeds, and some *other*
statement's download or re-import breaks days later.

**Recommended resolution: deletion never touches R2.** All three paths in §2.3 drop the row and
nothing else; a separate sweeper reclaims objects no row references.

That follows directly from the failure semantics in §5.1 — an unreferenced object is a tolerable,
reclaimable cost, while a row pointing at a missing object is unrecoverable. It also avoids
reference counting, which would have to stay exactly correct across concurrent confirms,
re-imports and session expiry to avoid causing the very data loss it was introduced to prevent.

## 4. Provider must stay replaceable

Business logic must never know whether a file came from R2, S3 or a local directory.
`StatementStorageService` is the only boundary that knows, with at minimum:

- **R2** — production
- **Local filesystem** — development and tests

The local implementation is not a nicety: it is what makes this testable without credentials, and
what keeps the suite offline and deterministic.

R2 speaks the S3 API, so the client is S3-shaped and a move to AWS S3, MinIO, Wasabi or Spaces is
configuration rather than business logic. Worth protecting deliberately — keep S3 vocabulary at the
boundary and let no Cloudflare specific leak past it.

## 5. Phasing

Additive first, destructive last; every step reversible until the final one.

### 5.0 The backfill was removed, because there was nothing to back fill

Phase 3 shipped and was then deleted. It existed to move historical `BYTEA` rows into object
storage, and there are none: the development database has no schema at all, and the
`finora-statements` R2 bucket reports 0 objects. No production statements have ever been imported.

A migration with nothing to migrate is not harmless. It was ~600 lines — a service, a worker, an
admin endpoint and three test classes — sitting on the path that handles people's bank statements,
permanently untested against real input because real input never existed. Dead code there is worse
than dead code anywhere else in the repository.

What survives is the part that was never about migration: **every read re-derives the SHA-256 and
compares it to the hash the row recorded** (`ContentAddress.requireMatches`, called from
`StatementContentService`). That was introduced alongside the backfill's read-back check and is
deliberately placed at the single read choke point rather than inside a provider, so an R2
implementation inherits it instead of having to remember it. Cost is one hash over bytes already
fetched over the network and about to be PDF-parsed — invisible next to either.

So the model is now simply:

```
upload ──► SHA-256 ──► store object ──► persist hash + key
read   ──► fetch ────► SHA-256 ──────► compare ──► mismatch = StatementIntegrityException
```

**Why `file_content` still exists.** Not redundancy for its own sake. Railway's container
filesystem is **ephemeral**, and `FilesystemStatementStorage` is currently the only provider — so
the BYTEA column is the only durable copy, and dropping it now would lose every statement on each
deploy. Phase 4's precondition is therefore no longer "the backfill reports complete"; it is **a
durable provider is configured and in use**. `V55`'s self-guard, which refuses to drop the column
while any row lacks a content address, stays as the mechanical check.

**Still needed for R2**, once an API token exists (create it in the Cloudflare dashboard; the
secret goes into Railway's environment, never into the repository or a chat):

| Variable | Value |
|---|---|
| `STATEMENT_STORAGE_PROVIDER` | `r2` |
| `R2_ACCOUNT_ID` | Cloudflare account id |
| `R2_BUCKET` | `finora-statements` |
| `R2_ACCESS_KEY_ID` | from the R2 API token |
| `R2_SECRET_ACCESS_KEY` | from the R2 API token |

`R2StatementStorage` is not written yet — deliberately, so it can be integration-tested against the
real bucket as it is built rather than mocked and hoped for. `FG-009` keeps the swap honest: nothing
outside `com.finora.imports.storage` may name a concrete provider, so this stays a config change.

| Phase | Change | Reversible |
|---|---|---|
| **1 — BUILT** | `StatementStorage` + `ContentAddress` + `FilesystemStatementStorage`. Content-addressed, provider-selected, fully tested. | Yes — no storage change at all |
| **2 — BUILT** | New uploads go to storage. Persist object key + content hash. Dual-write: `file_content` still filled. | Yes — old rows untouched |
| ~~**3 — backfill**~~ | ~~Backfill existing `BYTEA` into storage~~ — **REMOVED 2026-08-05, see §5.0** | n/a |
| **4** | Drop `file_content`. Blocked on a durable provider — see §5.0. | **No** |

Phase 1 is deliberately behaviour-preserving. It is what makes every later phase small, and it can
ship and prove itself in production while nothing yet depends on R2.

**What Phase 1 shipped, and one deviation worth knowing.** The original wording said callers would
"stop knowing where bytes live" in Phase 1. They do not yet, and cannot: a caller can only hold a
*reference* to stored content once the row has somewhere to put one, and `content_hash` /
`object_key` are Phase 2 columns. Rewiring `ImportService` before those exist would mean either
writing statements to a local disk in production, or inventing a temporary reference the schema
cannot store. Both are worse than waiting.

So Phase 1 delivers the storage layer complete and proven, and Phase 2 becomes: add the columns,
call `store()` in `confirm()`, read through `retrieve()`. The interface is content-addressed from
the outset precisely so that step does not reshape it.

`app.statement-storage.provider` has **no default**. With nothing set, no bean is created and the
pipeline behaves exactly as before — asserted by `StatementStorageWiringTest`, so "Phase 1 changed
nothing" is checked rather than claimed.

Phase 4 is its own change, its own migration, its own deploy — and only once a durable provider
is configured and every row carries a key.

### 5.1 Dual-write semantics

The only acceptable ordering:

```
upload ──► write object to R2 ──► persist row ──► commit
```

- **R2 write succeeds, transaction fails** → orphaned object. **Acceptable** — the sweeper (§3.2)
  reclaims it.
- **Row persisted, object missing** → **not acceptable**. Unrecoverable, and precisely what
  writing R2-first prevents.

Content-addressing makes retries naturally idempotent: re-uploading identical bytes after a partial
failure resolves to the same object instead of creating a second.

## 6. Retention — out of scope

Retention behaviour is **preserved exactly as it is today**. Changing how long statements are kept
is a product decision, revisited separately once this migration is complete.

Engineering input for that later conversation, recorded now so it is not rediscovered:

- **Deleting originals kills re-import.** `reimport()` re-parses the stored bytes and has no other
  source. Any retention shorter than "forever" means re-import stops working for older statements,
  and the UI would have to say so rather than fail.
- **Protected PDFs are stored still encrypted**, password deliberately never persisted — so an old
  statement whose password nobody remembers is *already* effectively un-re-importable. The
  practical retention window for those is shorter than the storage window regardless.

## 7. Scope

- `StatementStorageService` abstraction, with R2 and local-filesystem implementations.
- New uploads to R2; PostgreSQL keeps object key + content hash + metadata only.
- Content-addressed identity so sections and re-imports share one object.
- Deduplicating backfill of existing rows.
- Reference-aware deletion (§3.2) — rows drop, objects are swept separately.
- Lifecycle rules for temporary import-session objects.
- Remove `BYTEA` only after the migration is verified.

### Explicitly out of scope

- Parser changes
- Import workflow changes
- User-visible behaviour changes
- Layout intelligence
- Retention policy changes (§6)

## 8. Decisions recorded

**PostgreSQL stays on Railway.** The backend runs there and the import pipeline makes many
round-trips inside one transaction, so co-location is worth more than what a separate provider
offers today.

Neon is reconsidered when — and only when — one of these becomes real:

- Point-in-time recovery is needed
- Per-branch databases for development or testing
- An operational reason to separate database hosting from application hosting

A caution for that day: Neon's scale-to-zero suspends compute after inactivity, which conflicts
with a long-lived HikariCP pool. Survivable (`maxLifetime` below the suspend window, keepalives, or
disabling autosuspend) but real friction for a JVM app in a way it is not for serverless JS.

| Service | Owns |
|---|---|
| Railway PostgreSQL | Relational data |
| Cloudflare R2 | Original uploaded documents |
| Cloudflare Pages | Frontends |
| Railway | Backend services |

## 9. Success criteria

New uploads land in R2 with only key, hash and metadata in PostgreSQL; existing files migrate with
identical content stored once; download and re-import behave exactly as before; deleting one
statement never breaks another's access to shared content; `file_content` is dropped only after the
migration is verified complete; and the storage provider is swappable by configuration.

