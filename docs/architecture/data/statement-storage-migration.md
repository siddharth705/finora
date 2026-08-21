# Statement Storage Migration — PostgreSQL BYTEA → Cloudflare R2

**Status:** Built and live. R2 storage, content-addressing, reference-aware deletion (§6), and —
as of the 2026-08-16 storage review — compression and the confirm-time-only upload lifecycle are
all in production. See §0 for the current state in one place; the rest of this document is the
narrative of how it got here, kept because the reasoning it records (especially §2.1's duplication
math and §3.2's reference-counting hazard) still governs anything that touches this code.
**Scope:** Where uploaded statement files live. No parser or user-visible change — the stage →
review → confirm workflow a user sees is unchanged; what changed is when, during that workflow,
bytes reach R2.

---

## 0. Current lifecycle (storage review, 2026-08-16)

```
Upload statement
  ↓
Temporary staging only          (import_sessions.file_content — see §5.0a)
  ↓
User clicks Import
  ↓
Hash original bytes             (SHA-256, BEFORE compression — see §0.1)
  ↓
Compress                        (GZIP, deterministic — see §5.0a)
  ↓
Upload to R2
  ↓
Store metadata in PostgreSQL    (object_key, content_hash, original_size, stored_size,
                                  compression_type, original_mime_type — V92)
```

A file a user has merely uploaded — staged for review, not yet confirmed — never reaches R2.
`ImportSessionService.storeContent` writes it to `import_sessions.file_content`, which already IS
temporary storage: self-cleaning via the existing 48h TTL sweep
(`ImportSessionService.sweepExpiredSessions`), scoped to the owning user by the same
`OwnershipGuard` every other session read goes through. Nothing new was built for this — the
column and the sweep already existed; what changed is that staging stopped bypassing them.

Object storage is reached for the first time at CONFIRM (`ImportService.persistSection`), and only
then. This closes a real gap the storage review found: earlier code wrote every staged upload to R2
immediately, before any review or confirmation, so a session a user abandoned (or was still
reviewing) had already paid for an R2 write with nothing to show for it if it expired unconfirmed.

### 0.1 Why the hash is computed before compression, not after

`content_hash` is, and remains, the SHA-256 of the ORIGINAL uploaded bytes — never the compressed
representation. This is a correctness requirement, not a style choice:

- It is the document's **identity**. `ImportSessionService.findLiveSessionByContentHash` dedupes
  the staging path on it, and a session's `content_hash` and the `StatementImport` it later
  confirms into carry the identical value for exactly this reason.
- For a financial-document system, it is also the **audit anchor**: it is what a user or auditor
  would re-derive directly from the file the bank issued to confirm this is the same document.
  Hashing the compressed representation instead would make that identity depend on this system's
  own compression library and settings — a property neither dedup nor an audit trail can afford.
  The compressed R2 object is a storage representation; the statement's identity has to outlive
  whatever encoding happens to hold it at rest.

The object key R2 actually uses IS derived from the compressed bytes (a separate, internal
`ContentAddress` computed by `StatementStorage.store`) — that is fine, because the key was already
documented as "a private layout decision" independent of identity (§3.1) before compression
existed. `StatementContentService.store` computes both and reassembles the one that is persisted:
original hash, storage layer's key. See that class's own "Compression" doc section for the exact
mechanics.

### 0.2 The async job-queue path is a deliberate exception, not an oversight

`ImportJobService.accept` (`app.import.queue.enabled`, **off by default** — opt-in per
environment) writes directly to `StatementStorage`, bypassing `StatementContentService` and
therefore this compression layer entirely. Every object it creates is uncompressed;
`ImportJob.getCompressionType()` returns `NONE` unconditionally so `StatementContentService.read`
never tries to decompress bytes that were never compressed.

This is not the same lifecycle bug §0's fix closed, and is not accidental:

- **Why it writes early.** The async path exists specifically so a worker running later, in
  another thread and possibly another Railway container, can pick up the upload — see
  `ImportJobService`'s own class doc, "Order of operations, and why it is this order." Something
  durable and cross-instance-visible has to hold the bytes across that handoff, and Railway's
  container filesystem is ephemeral, so R2 is the only candidate. There is no staging/review step
  this path skips past the way the synchronous CSV/PDF flow's staging did — accept() effectively
  IS the earliest point at which durable storage becomes necessary for this path, not a step
  taken before one was needed.
- **Why it is safe to leave uncompressed for now.** `ImportJob` rows are storage-cost only, not a
  correctness or lifecycle-timing hazard — the fix this review made was about WHEN bytes reach R2
  relative to user confirmation, and the async path's timing was never the problem.
- **Why compressing it is not just unexplored, but in tension with BH-018 (verified 2026-08-16,
  not merely assumed).** `StatementContentService.store` takes `byte[] content` — it hashes and
  gzips the whole array in memory (`GzipCompression.compress` builds its output in a
  `ByteArrayOutputStream`). `ImportJobService.accept` deliberately does NOT do that: its own class
  doc ("BH-018's other half") explains that it switched from `file.getBytes()` to
  `file.getInputStream()`/`getSize()` specifically so a burst of concurrent uploads costs
  buffer-sized memory each, not file-sized, all the way up to the 10 MB cap. Wiring `accept()`
  through `StatementContentService` as it exists today would undo that fix, not just add
  compression alongside it. A compressing variant of the raw `StatementStorage.store` call (the
  other option below) does not avoid this either — GZIP's deterministic-output trick
  (`GzipCompression`, MTIME zeroed after the fact) and the pre-compression SHA-256 both currently
  operate on a fully-resident array; a genuinely streaming version of both would be new,
  currently-unbuilt infrastructure, not a rewire of what exists. (A streaming SHA-256 helper
  already exists — `ContentAddress.copyAndAddress` — for exactly the reason `accept()` needs one;
  no streaming-compatible GZIP helper does.)
- **Follow-up, not done here.** Routing this path through compression too remains a reasonable
  future improvement if a real need for it appears (this path is opt-in and disabled in every
  environment today, so the tradeoff is storage cost only, never correctness) — but it would need
  either accepting the BH-018 regression above, or building streaming-compatible hashing and
  compression this path does not have today. Deliberately out of scope for this review, and not
  undertaken speculatively while the path stays disabled everywhere — expanding its behavior was
  not what was asked for, and neither is inventing infrastructure nothing yet needs. Flagged here
  so it is a decision someone makes on purpose, not a gap nobody wrote down.
  `ImportJobTest.compressionTypeIsAlwaysNone_regardlessOfJobState` and
  `ImportJobEndpointIT.theStoredObjectIsUncompressed` guard the exemption itself, so a future
  change that silently compresses this path (or drifts `getCompressionType()` from what is
  actually written) fails a test rather than corrupting a read.

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
  object. Expiring the session must not remove the object the confirmed import depends on. **As of
  the 2026-08-16 storage review (§0), this case no longer arises for new rows** — a session never
  writes to object storage at all, so there is no session-held reference to share in the first
  place; the confirmed `StatementImport` is the object's only reference from the moment it is
  created. The reasoning stays documented (and `StatementStorageSweepService` keeps checking
  `import_sessions` for references) because rows created before this change may still be inside
  their 48h staging window and could still carry a real `object_key` under the old behaviour.
- **Multi-section and re-imported statements share one object by design** (§2.1). Deleting one must
  leave the others readable.

Get this wrong and the failure is silent and delayed: the delete succeeds, and some *other*
statement's download or re-import breaks days later.

**Resolution, built (BH-017): deletion never touches R2 directly.** All three paths in §2.3 drop
the row and nothing else, exactly as before this migration; `StatementStorageSweepService`
(`com.finora.imports.storage`) is the only caller of `StatementStorage.delete`, and only for an
object that has been unreferenced -- checked fresh, across `statement_imports`, `import_sessions`,
and `import_jobs` rows outside `{COMPLETED, CANCELLED}`, immediately before each delete -- for
longer than `app.statement-storage.sweep.retention-days` (90 by default, §6). `import_jobs` joined
the check after production evidence showed a FAILED async import has no row in either of the other
two tables at all -- and a CANCELLED-before-staging job has none either, for the same reason. Both
are excluded from protecting an object on their own, because unlike the other two tables an
`import_jobs` row never expires on its own: counting COMPLETED there would make a successfully
imported statement's object permanently unsweepable, and counting CANCELLED would retain an object
forever that never had a legitimate reference to begin with. FAILED and the in-flight statuses have
no such bound either, deliberately -- see the class's own "Accepted trade-off" doc section.

That follows directly from the failure semantics in §5.1 — an unreferenced object is a tolerable,
reclaimable cost, while a row pointing at a missing object is unrecoverable. Row-dropping itself
still does no reference counting and does not need to; the sweeper is where that reasoning lives,
and it stays correct across concurrent confirms, re-imports and session expiry the way §2.3's three
paths individually never could — see that class's doc comment for the exact query and the one
category of orphan it cannot discover (content whose only-ever reference was an `import_sessions`
row already hard-deleted by the 48h TTL sweep, which leaves no timestamp anywhere in the database
for a DB-only sweep to find).

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

**Why `file_content` still exists, and why it is no longer always filled.** The column is not
redundancy for its own sake — with nothing but object storage, Railway's container filesystem
being **ephemeral** meant `FilesystemStatementStorage` (the only provider proven in production at
the time) offered no durable copy at all, so the BYTEA column was the only durable copy and
dropping it would have lost every statement on each deploy. That is still true; what changed is
which cases actually write to it.

BH-025 and BH-046 (2026-08-08 repo-wide bug hunt) found that the dual write itself had become a
problem, not a stopgap: `confirmMultiSection()` persists one `StatementImport` row per detected
account section, all sharing the same uploaded bytes, so a 3-section 9 MB statement wrote 27 MB of
`BYTEA` on top of the one already-deduplicated content-addressed object (BH-025) — and there was no
longer any phase left to end that duplication (BH-046: Phase 3 was deleted for having nothing to
migrate, and Phase 4 never got a trigger, so "temporary until Phase 3/4" had quietly become
permanent).

As of **V76** (2026-08-09), `ImportService.persistSection` / `ImportSessionService.storeContent`
write `file_content` **only when `StatementContentService.store()` returned empty** — i.e. no
storage provider is configured, and the row is legacy exactly as it always was. When a provider
**is** configured — filesystem or R2 — the stored object is the only copy and `file_content` is
left `NULL`; `V76` relaxed both columns from `NOT NULL` to nullable and added a check constraint
requiring at least one of `file_content` / `object_key` to be set. This is a real trade-off, not
a free lunch: enabling `FilesystemStatementStorage` (documented above as a dev/test provider,
backed by Railway's ephemeral container disk) now means `file_content` is skipped for those rows
too, so **filesystem must never be the provider in an environment relying on statement durability**
— only R2 (or another provider backed by durable storage) satisfies that once this fix is live.
Phase 4's precondition is therefore unaffected by this change: it is still **a durable provider is
configured and in use**, and `V55`'s self-guard, which refuses to drop the column while any row
lacks a content address, stays as the mechanical check.

**R2 is implemented and live in production.** `R2StatementStorage` speaks the S3 API through the
AWS SDK, is selected by `app.statement-storage.provider=r2`, and `STATEMENT_STORAGE_PROVIDER=r2`
is the configured value in Railway today — every confirmed statement's bytes go to R2, compressed
(§0), with only metadata in PostgreSQL. With the provider unset there is still no storage bean and
statements would go to `BYTEA` exactly as before (a config-only rollback, not a deploy), which
`StatementStorageWiringTest` asserts rather than assumes — that path is what the table below
still documents, for a fresh environment or a deliberate rollback.

The table below is kept as the reference for what those variables mean and how to set them,
whether standing up a new environment or auditing the ones already configured:

| Variable | Value |
|---|---|
| `STATEMENT_STORAGE_PROVIDER` | `r2` |
| `R2_ACCOUNT_ID` | Cloudflare account id |
| `R2_BUCKET` | `finora-statements` |
| `R2_ACCESS_KEY_ID` | from the R2 API token |
| `R2_SECRET_ACCESS_KEY` | from the R2 API token |
| `R2_ENDPOINT` | **optional.** Leave unset for an ordinary bucket — see below |

`R2_ENDPOINT` is normally left unset: the endpoint is derived as
`https://<account-id>.r2.cloudflarestorage.com`, which is correct for an ordinary bucket. Set
it only when derivation is wrong — a bucket created with a **jurisdiction restriction** lives
at `<account-id>.eu.r2.cloudflarestorage.com` (or `.fedramp.`), and the derived URL then
addresses a bucket that does not exist. R2 reports that as an auth failure rather than a
missing bucket, so it presents as "wrong credentials" and sends whoever is debugging it to
re-issue a token that was never the problem. It is the **S3 API** URL on the R2 dashboard, and
is validated at startup (absolute, `https`, has a host).

Set the provider **and** the four required variables, or none. With `provider=r2` and any one missing the application
refuses to start and names the missing environment variable — deliberately, because the alternative
is a deploy that looks healthy and fails the first time a real user imports a statement, at which
point the only copy of their file is in a request that already returned 500.

Three things are R2-specific rather than generic S3, and each fails confusingly if missed: the
region is the literal string `auto`, addressing must be path-style, and the SDK's automatic
checksums (`x-amz-checksum-crc32`, on by default since AWS SDK 2.30) must be set to `WHEN_REQUIRED`
because R2 does not implement that flavour consistently — the symptom is a signature error that
reads like bad credentials. Disabling them costs nothing here: this system verifies SHA-256 over
the bytes on every read against a hash held in the database, which is a strictly stronger check
than a transport CRC and is the one that catches a provider returning the wrong object.

`FG-009` keeps the swap a config change: nothing outside `com.finora.imports.storage` may name a
concrete provider.

| Phase | Change | Reversible |
|---|---|---|
| **1 — BUILT** | `StatementStorage` + `ContentAddress` + `FilesystemStatementStorage`. Content-addressed, provider-selected, fully tested. | Yes — no storage change at all |
| **2 — BUILT, since revised** | Confirmed statements go to storage, at CONFIRM time. Persist object key + content hash. Originally dual-wrote `file_content` unconditionally; **V76 (2026-08-09, BH-025/BH-046) changed this to write `file_content` only when no provider is configured** — see the "Why `file_content` still exists" note above. | Yes — old rows untouched; toggling the provider still changes only new rows |
| ~~**3 — backfill**~~ | ~~Backfill existing `BYTEA` into storage~~ — **REMOVED 2026-08-05, see §5.0** | n/a |
| **4** | Drop `file_content`. Blocked on a durable provider — see §5.0. | **No** |
| **5 — BUILT (storage review, 2026-08-16)** | Compression (GZIP, V92) + the lifecycle correction: staging (`ImportSessionService`) stopped writing to object storage at all, closing a gap where an uploaded-but-unconfirmed file had already reached R2. See §0. | Yes — a config/code rollback; no schema change is destructive (V92's new columns are nullable except `compression_type`, which defaults safely — see §0's backward-compatibility note) |

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

### 5.0a Compression and backward compatibility (V92, storage review, 2026-08-16)

`StatementContentService.store` now runs bytes through GZIP (`GzipCompression`) before handing
them to `StatementStorage`, for every confirmed statement. Two properties make this safe to turn
on against a bucket that already has real, uncompressed objects in it — R2 is live in production
today, so this is not a hypothetical:

- **Deterministic compression.** Plain `GZIPOutputStream` embeds a wall-clock modification-time in
  its header, so compressing identical bytes twice at different moments produces two different
  compressed byte streams — which would silently defeat content-addressing's own dedup guarantee
  (identical content should resolve to one object, not a new one every time it happens to be
  re-uploaded). `GzipCompression` zeroes that header field unconditionally after compressing, the
  same convention `gzip -n` uses for reproducible output — not a workaround, a standard one.
- **Explicit metadata, not sniffing.** `V92` adds `original_size`, `stored_size`,
  `original_mime_type`, and `compression_type` (`NONE`/`GZIP`, `NOT NULL DEFAULT 'NONE'`, checked)
  to `statement_imports`. `StatementContentService.read` decompresses based on the ROW's own
  `compression_type` column — never by inspecting the retrieved bytes for a magic number. This is
  what makes the migration safe with **no data migration of existing R2 objects required**:

  - **Every row that existed before V92** — whether its bytes are already in R2 (uncompressed, by
    construction: compression did not exist when they were written) or still in `file_content` —
    gets `compression_type = 'NONE'` from the column's default. `StatementContentService.read`
    therefore does not attempt to decompress them, and reads them exactly as it always has.
  - **`original_size`/`stored_size`/`original_mime_type` are nullable**, matching V76's precedent
    (`file_content`/`object_key`) of not requiring a backfill: they are best-effort measurement
    columns, not invariants an existing row has to satisfy.
  - Verified directly, not just argued: `StatementContentServiceCompressionTest`'s
    `anObjectStoredBeforeCompressionExisted_stillReadsCorrectly_throughEitherBackend` stores raw,
    uncompressed bytes at a real address with `compression_type = NONE` and confirms
    `StatementContentService.read` returns them correctly, unmodified.

`content_hash` is unaffected by any of this — see §0.1 for why it is computed from the original
bytes before compression runs, and stays that way regardless of what `compression_type` a row ends
up recording.

### 5.1 Write ordering (not to be confused with the `file_content` dual write above)

This section is about the ordering between the object-storage write and the row write, which is
unchanged by V76 — it still always writes the object first. It is a separate question from
*whether* `file_content` also gets filled, which §5.0 above now answers with "only when no
provider is configured."

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

## 6. Retention — decided and built (BH-017)

The product decision this section used to defer has been made: once `app.statement-storage.provider`
is configured (PR #67 made this the production default), object storage is genuinely permanent
without a reclaim path — none of §2.3's three row-deletion paths ever touched the underlying
object, which made the "48h" TTL the docs described false for R2/filesystem bytes the moment a
provider was set. Sid decided explicitly: a reference-counted sweep (§3.2), not an R2 lifecycle
rule and not delete-on-row-expiry, with statements staying **re-importable for approximately 90
days** after every reference to them is gone. `StatementStorageSweepService` implements this;
`app.statement-storage.sweep.retention-days` (default 90) is the knob.

What "unreferenced" means, concretely, and its one known gap:

- **statement_imports rows are soft-deleted** (`@SQLDelete`), so a user deleting a statement leaves
  a `deleted_at`-stamped row behind forever — a durable, queryable trace of exactly when that
  reference ended. The 90-day window is measured from there.
- **import_sessions rows are hard-deleted** by the existing 48h TTL sweep
  (`ImportSessionService.sweepExpiredSessions`, deliberately untouched by this change — see that
  class's own reasoning for why it is a scheduled job and not opportunistic). A session that is
  never confirmed has, once its row is gone, left literally no trace anywhere in the database that
  its object ever existed. `StatementStorageSweepService` cannot discover — and therefore cannot
  reclaim — that category of orphan. It is flagged, not silently missed: closing it fully would need
  either object-listing/metadata support added to `StatementStorage` (a materially larger interface
  than BH-017 asked for) or a durable tombstone recorded at the moment such a row is hard-deleted (a
  behavioural change to that TTL sweep this change deliberately avoids).
- **`ON DELETE CASCADE` on user deletion** hard-deletes at the database level, bypassing Hibernate
  (and therefore the soft-delete) entirely — the same gap as above, for the same reason.

Engineering input recorded when this was still open, now the basis for what got built:

- **Deleting originals kills re-import.** `reimport()` re-parses the stored bytes and has no other
  source. That is exactly why the sweep waits 90 days rather than reclaiming on row-deletion — a
  user who deletes a statement by mistake, or wants to re-run an import with different category
  rules, keeps that window.
- **Protected PDFs are stored still encrypted**, password deliberately never persisted — so an old
  statement whose password nobody remembers is *already* effectively un-re-importable. The
  practical retention window for those is shorter than the storage window regardless.

## 7. Scope

- `StatementStorageService` abstraction, with R2 and local-filesystem implementations.
- Confirmed statements go to R2, compressed, at CONFIRM time — never at upload/staging time (§0);
  PostgreSQL keeps object key + content hash + compression metadata only.
- Content-addressed identity so sections and re-imports share one object.
- ~~Deduplicating backfill of existing rows.~~ **Removed — see §5.0.** There was nothing to back
  fill, and the code was deleted rather than left untested on the statement path.
- Reference-aware deletion (§3.2) — **BUILT, BH-017.** Rows drop, unchanged;
  `StatementStorageSweepService` sweeps objects separately once every reference to them, across
  `statement_imports`, `import_sessions`, and `import_jobs` outside `{COMPLETED, CANCELLED}`, has
  been gone for `app.statement-storage.sweep.retention-days` (90 default). See §6.
- **One object class, one cleanup mechanism.** This line previously read "lifecycle rules for
  temporary import-session objects", which contradicted §3.2 and the implementation. There is no
  separate session-object namespace: `ContentAddress.of()` is the only key scheme
  (`statements/<hh>/<hh>/<hash>.bin`), `ImportSessionService` stores through the same
  `StatementContentService.store()`, and both `import_sessions` and `statement_imports` carry the
  hash and object key (§3.1). A session and the import it confirms **resolve to the same object**.
  An age-based expiry applied to "session objects" would therefore delete objects a confirmed
  import still needs — the silent, delayed failure §3.2 exists to prevent. The sweeper is the only
  cleanup path, and it must count references from `statement_imports`, `import_sessions`, and
  `import_jobs` alike.
- Remove `BYTEA` only after the migration is verified.

### Explicitly out of scope

- Parser changes
- Import workflow changes
- User-visible behaviour changes
- Layout intelligence
- ~~Retention policy changes (§6)~~ **Decided and built, BH-017 — see §6.**

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

Confirmed statements land in R2, compressed, at confirm time only — never at upload/staging —
with only key, hash, and compression metadata in PostgreSQL; content_hash always identifies the
original uncompressed bytes, regardless of encoding at rest (§0.1); existing files migrate with
identical content stored once; download and re-import behave exactly as before, transparently
decompressing when needed; deleting one statement never breaks another's access to shared content;
`file_content` is dropped only after the migration is verified complete; and the storage provider
is swappable by configuration.

