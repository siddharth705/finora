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

**Consequence for the target design:** object keys should be **content-addressed** (hash the bytes,
key on the digest). Then N sections and M re-imports of the same file reference one object instead
of storing N+M copies, and the duplication problem disappears rather than moving to R2.

### 2.2 Who actually reads the bytes

Five call sites, and it matters for retention:

| Caller | Purpose |
|---|---|
| `ImportService.confirmSession` / `confirmMultiSection` | Copy session bytes onto the new import row |
| `StatementImportService.getFile` | User downloads the original (web download, mobile share sheet) |
| `StatementImportService.reimport` | **Re-parses the original** |
| `StatementImportService.confirmReimport` | Copies bytes onto the new row |

So originals serve exactly two user-facing purposes: **download** and **re-import**.

## 3. Why R2 specifically

- **Zero egress fees** — statement downloads cost nothing, which is the whole point versus S3.
- **S3-compatible API.** The client talks S3, so moving later to AWS S3, MinIO, Wasabi or Spaces is
  configuration, not business logic. Worth preserving deliberately: keep the S3 vocabulary at the
  boundary and do not leak Cloudflare specifics past `StatementStorageService`.
- **Lifecycle rules** make retention policy a configuration decision rather than application code —
  particularly for the 48-hour `import_sessions` objects.

## 4. Scope

- Introduce a `StatementStorageService` abstraction.
- Store all new uploads in R2; persist only the object key in PostgreSQL.
- Content-address the keys so sections and re-imports share one object (§2.1).
- Migrate existing `BYTEA` rows into R2.
- Remove database file storage **only after** the migration is verified.
- Lifecycle rules for temporary import-session objects.

### Explicitly out of scope

- Parser changes
- Import workflow changes
- User-visible behaviour changes
- Layout intelligence
- Retention policy changes (see §6 — a separate decision)

## 5. Phasing

Deliberately additive first, destructive last, so every step has a rollback.

**Phase 1 — dual write.** New uploads go to R2 *and* keep the `BYTEA` column. Reads still come from
the database. Nothing depends on R2 yet, so an R2 outage or misconfiguration cannot break imports.

**Phase 2 — backfill and switch reads.** A background migration uploads existing rows and records
their keys. Once a row has a key, reads come from R2 with the column as fallback. Progress is
measurable: rows with a key versus rows without.

**Phase 3 — drop the column.** Only after Phase 2 reports complete. This is the irreversible step
and it should be its own change, its own migration, its own deploy.

### Failure semantics to settle before Phase 1

The one genuinely hard part. Writing to two systems in one request has no free lunch:

- R2 write succeeds, DB insert fails → orphaned object. Tolerable; a lifecycle rule or sweeper
  reclaims unreferenced keys.
- DB insert succeeds, R2 write failed → **row pointing at nothing**. Not tolerable. Write to R2
  first and only persist the key after it is durable.

Content-addressing helps here too: re-uploading identical bytes after a partial failure is
idempotent rather than duplicating.

## 6. Open question — how long do we keep originals?

Raised and not answered. It is a product decision, not an engineering one, and it should be settled
separately rather than smuggled into a storage migration.

What engineering can contribute:

- **Deleting originals kills re-import.** `reimport()` re-parses the stored bytes; there is no other
  source. Any retention shorter than "forever" means re-import silently stops working for older
  statements, and the UI would need to say so rather than fail.
- **Password-protected PDFs are stored still encrypted** and the password is deliberately never
  persisted, so re-importing one already requires the user to supply it again. Retention interacts
  with that: an old statement nobody remembers the password for is already effectively
  un-re-importable.
- R2 lifecycle rules make any of "forever / 2 years / user chooses / delete after import" cheap to
  implement once decided.

## 7. Decisions recorded

**PostgreSQL stays on Railway.** The backend runs there, and the import pipeline does many
round-trips inside one transaction, so co-location is worth more than what a separate provider
offers today.

Neon is reconsidered when — and only when — one of these becomes real:

- Point-in-time recovery is needed
- Per-branch databases for development or testing
- An operational reason to separate database hosting from application hosting

A specific caution if that day comes: Neon's scale-to-zero suspends compute after inactivity, which
conflicts with a long-lived HikariCP pool. Survivable (`maxLifetime` below the suspend window,
keepalives, or disabling autosuspend) but it is real friction for a JVM app in a way it is not for
serverless JS.

**Resulting division of responsibility:**

| Service | Owns |
|---|---|
| Railway PostgreSQL | Relational data |
| Cloudflare R2 | Original uploaded documents |
| Cloudflare Pages | Frontends |
| Railway | Backend services |

## 8. Success criteria

New uploads land in R2 with only the key in PostgreSQL; existing files migrate without loss;
download and re-import behave exactly as before; `file_content` is dropped only after the migration
is verified complete; and identical bytes across sections and re-imports occupy one object rather
than many.
