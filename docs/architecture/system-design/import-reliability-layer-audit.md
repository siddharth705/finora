# Import reliability layer audit

Read-only audit, 2026-08-12. Scope: the **product/lifecycle layer around imports** — an import session
as an object moving through the system. Parser/extraction correctness (P-001, P-002, C-8, OCR,
evidence engine) is explicitly out of scope and was not assessed here.

Every claim below is anchored to code that was read. Paths are relative to the repository root;
`.claude/worktrees/**` copies were excluded.

---

## 1. Import lifecycle traceability

### The data model — there are three separate records, not one

| Entity | Table | Written when | File | Status vocabulary |
|---|---|---|---|---|
| `ImportSession` | `import_sessions` | after a **successful** parse, before confirm | `backend/src/main/java/com/finora/entity/ImportSession.java` | `STAGED`, `CONFIRMED` only (`:33-34`) |
| `ImportJob` | `import_jobs` | at upload, **async path only** | `backend/src/main/java/com/finora/entity/ImportJob.java` | `QUEUED, PARSING, ANALYZING, DEDUPING, IMPORTING, LEARNING, COMPLETED, FAILED, CANCELLED` (`:51`) |
| `StatementImport` | `statement_imports` | at confirm | `backend/src/main/java/com/finora/entity/StatementImport.java` | field exists (`:104-105`) but is **hardcoded `"COMPLETED"` and never assigned** |
| `StatementAnalysisSession` | `statement_analysis_sessions` | after every parse attempt, success or failure | `backend/src/main/java/com/finora/imports/analysis/StatementAnalysisSession.java` | `PARSED`, `FAILED` (`:51`) |

`StatementImport.status` is dead: a repo-wide grep for `setStatus` in `backend/src/main` returns only
the setter declaration (`StatementImport.java:181`) and unrelated entities. Nothing in the codebase
ever writes anything but the constructor default. So the PM's proposed vocabulary maps as follows:

| PM's proposed status | Actually exists today? |
|---|---|
| `UPLOADED` | Only on the async path (`ImportJob.Status.QUEUED`), which is off by default |
| `PROCESSING` | Only on the async path (`PARSING`/`ANALYZING`) |
| `COMPLETED` | Yes — `ImportSession.STATUS_CONFIRMED` / `StatementImport` default |
| `PARTIAL` | ❌ No such state. `transactionsSkipped` is a count, not a status |
| `FAILED` | Only on the async path (`ImportJob.Status.FAILED`). On the default sync path a failure produces **no import record at all** |
| `NEEDS_REVIEW` | ❌ No such state. `STAGED` is the closest, but it means "awaiting confirm", not "flagged" |

### Is there a persisted record before the user confirms?

**Yes, on the happy path.** `ImportSession` is created inside `ImportService.parseAndStageWithSession`
(`backend/src/main/java/com/finora/imports/ImportService.java:190`) and
`parseAndStagePdfWithSession` (`:325`, `:333`), each of which persists the staged rows **and the
original file bytes** (`ImportSessionService.createSession:152-167`, `storeContent:217-225`). TTL is
48 hours (`ImportSessionService.java:41`), swept by a scheduled job (`:129-138`).

**No, on any failing path.** Both staging methods parse first and create the session only after
`rejectIfNothingWasExtracted` passes — the PDF method says so explicitly at `ImportService.java:286-288`
("Parsing happens BEFORE createSession … so no `ImportSession` row exists to orphan"). A password
failure, a scanned PDF, an unrecognised layout, a zero-transaction extraction, or a parser crash all
throw before line 190/325. **No `ImportSession`, no `StatementImport`, and — on the default synchronous
path — no `ImportJob` either.**

### What *is* recorded on failure

`StatementAnalysisSession` with `outcome = FAILED`, written from `ImportService.recordParseFailure`
(`:236-250`) via `StatementAnalysisRecorder.recordFailed` (`StatementAnalysisRecorder.java:99-115`).
This is `REQUIRES_NEW` (`:99`) specifically so it survives the caller's rollback, and the catch was
widened from `ApiException` to `RuntimeException` (BH-028, `ImportService.java:204-217`, `:341-353`)
so parser crashes are recorded too. The row holds: reference, user id, file name, source format, byte
size, layout fingerprint, failure code, truncated failure detail, duration, row count, unanchored-reason
histogram, correlation id (`StatementAnalysisSession.java:57-138`).

### The genuinely untrackable state

A **synchronous upload that fails** leaves nothing that the product model recognises as an import.
It leaves only a telemetry row in `statement_analysis_sessions`, which:

- has no user-facing endpoint at all;
- is exposed to admins only as `AnalysisView`, which **deliberately omits `userId` and `fileName`**
  (`AdminStatementAnalysisController.java:33-36`, `ImportTraceDto.java:27-32`,
  `StatementAnalysisReportService.AnalysisView:62-78`);
- has no repository query by user id, file name, or correlation id
  (`StatementAnalysisSessionRepository.java` — the only lookups are by reference, by import-session id,
  and `findAllByOrderByCreatedAtDesc`).

The lifecycle diagram the PM asked about therefore holds only for the async path, and the async path is
opt-in and off by default (`app.import.queue.enabled:false`, `ImportJobWorker.java:@Value` /
`ImportJobService.java:69`), additionally requiring object storage to be configured
(`ImportJobService.accept:129-133`).

---

## 2. Original document retention

### Storage mechanism

`StatementContentService` is the single seam (`backend/src/main/java/com/finora/imports/storage/StatementContentService.java`).
Providers: `FilesystemStatementStorage` (dev), `R2StatementStorage` (Cloudflare R2, prod), selected by
`app.statement-storage.provider` = `${STATEMENT_STORAGE_PROVIDER:}` (`application.yml:309`) — **unset by
default**. With no provider the bytes go into a Postgres `bytea` column instead
(`ImportSession.fileContent:67-69`, `StatementImport.fileContent:81-84`), never both
(`StatementContentService.java:24-47`, BH-025/BH-046). A misspelled provider name refuses startup
(`:88-109`).

### Retention

| Case | Bytes retained? | For how long |
|---|---|---|
| Confirmed import | Yes | Indefinitely, until the user deletes the statement. `StatementImport` is soft-deleted (`@SQLDelete`, `StatementImport.java:21-22`); the object is reclaimed 90 days later if no other row references it (`StatementStorageSweepService.java:96`, floor of 24h at `:87`) |
| Staged but never confirmed | Yes | 48h (`ImportSessionService.SESSION_TTL:41`), then hard-deleted by `sweepExpiredSessions` (`:111-118`) |
| **Failed parse** | **No** | The bytes are never written anywhere — `storeContent` is only reached from `createSession`/`createMultiSection`, which the failure paths never reach |
| Async job (any outcome) | Yes | Stored before the job row exists (`ImportJobService.accept:151`), so a FAILED job still has its bytes |

### Can support retrieve a specific failed upload?

**No.** Beyond the fact that failed sync uploads have no bytes at all, there is no admin endpoint that
serves statement bytes. The only download route is user-scoped:
`GET /api/v1/statement-imports/{id}/file` → `StatementImportController.downloadFile:49-63` →
`StatementImportService.getFile:162-171`, guarded by `OwnershipGuard.requireOwned` (`:344-347`).
Retrieval by a support engineer requires direct database or R2 bucket access. Grepped the whole
`controller/` package for an admin file/content route — not found.

---

## 3. Import history (user-facing)

### Backend

`GET /api/v1/statement-imports` → `StatementImportController.list:34-37` →
`StatementImportService.listGroupedByAccount:72-110`. Returns confirmed imports grouped by account, each
carrying file name, period, opening/closing balance, transactions imported/skipped, `importedAt`, and a
duplicate count. Because it reads `statement_imports`, and that table is written only at confirm, **the
list is structurally incapable of containing a failed import.**

`GET /api/v1/import/jobs?limit=N` → `ImportJobController.recent:119-123` *would* return failed and
cancelled jobs — but only for the async path, and the frontend never calls it (see below).

### Frontend

`frontend/src/pages/StatementHistory.tsx` renders exactly the above: file name (`:190`), period (`:192`),
imported date (`:193`), counts (`:194-195`), balances and the opaque `status` string (`:197-199`),
duplicate badge (`:200-204`). No verification/validation results are surfaced — the `StatementSummary`
type has no findings field (`frontend/src/types/index.ts:307-322`).

Three defined API functions have **no UI calling them**: `importApi.listSessions`,
`importApi.discardSession`, `importJobsApi.recent` (`frontend/src/api/endpoints.ts:374`, `:377`, `:429`).
So a user with an abandoned staged session cannot see or resume it, despite ADR-0002 having built the
capability for exactly that.

**Verdict:** history exists for successes only. There is no "import events" view; failures, cancellations,
and abandoned sessions are invisible to the user.

---

## 4. Failure handling, per failure type

Error codes are well-differentiated **server-side** (`backend/src/main/java/com/finora/exception/ErrorCode.java:26-70`),
and the reasoning for keeping them separate is documented in that file. The gap is on the client.

| Failure mode | Where it is raised | Code / HTTP | Recorded server-side | What the user actually sees |
|---|---|---|---|---|
| Password-protected PDF, no password given | `PdfTextExtractor.loadOrExplain:90-95` | `IMPORT_008` / 422 | `statement_analysis_sessions` FAILED, `failureCode=IMPORT_PDF_PASSWORD_REQUIRED`, no fingerprint | Correct, specific — the UI branches on it and opens a password panel (`frontend/src/pages/Import.tsx:362-388`, `:548-620`) |
| Wrong password | same, `:93-94` | `IMPORT_009` / 422 | same | Correct, specific — inline "that password did not open this statement" (`Import.tsx:586-590`) |
| Scanned / image-only PDF | `ExtractionCheck.java:102-107` | `IMPORT_010` / 422 | FAILED, `failureCode=IMPORT_SCANNED_OCR_REQUIRED` | Server message is good and carefully worded, but the frontend does **not** branch on the code — it falls into the generic `else` and prints `e.response.data.message` (`Import.tsx:381-386`) |
| Unrecognised layout (no table found) | `ExtractionCheck.java:111-118` | `IMPORT_001` / 422 | FAILED, fingerprint recorded | Same — generic branch, server message printed verbatim |
| Table found, zero transactions | `ExtractionCheck.java:111-118` (`locatedATable` true) | `IMPORT_007` / 422 | FAILED, fingerprint + unanchored-reason histogram recorded | Same — generic branch |
| Corrupt / truncated PDF | `PdfTextExtractor.java:115-117` | **no `ErrorCode`**, 422 with a message | FAILED with `failureCode = null` (`recordParseFailure:239-241` maps a null code to null) — indistinguishable in the failure histogram from any other codeless `ApiException` | Reasonable message, generic branch |
| Parser crash mid-processing | any unchecked throw, caught at `ImportService.java:204` / `:341` | 500, `INTERNAL_ERROR` | FAILED with `failureCode = <ExceptionClassName>` (BH-028) | `"Unexpected error"` in prod (`GlobalExceptionHandler.java:301-303`) — no distinguishing information |
| DB/storage failure during confirm | `confirmSession` is `@Transactional` (`ImportService.java:588`) | 409 for a constraint violation (`GlobalExceptionHandler.java:136-142`), else 500 | **Nothing.** `recordParseFailure` wraps staging only, not confirm | Generic conflict or "Unexpected error". The whole transaction including the `CONFIRMED` claim rolls back, so the session stays `STAGED` and the user can retry — that part is sound |

**Frontend error-code awareness is limited to two codes.** `frontend/src/api/errorCodes.ts:16-17`
defines only `IMPORT_008` and `IMPORT_009`; `Import.tsx:362-388` branches on those two and nothing else.
Async job failures collapse further: `setError(job.error ?? 'That import could not be completed.')`
(`Import.tsx:540-543`).

So: **distinguishable server-side, mostly distinguishable to the user only because the backend writes
good prose into `message`** — there is no client-side handling, no differentiated recovery affordance,
and a parser crash is indistinguishable from any other 500.

---

## 5. Retry capability

| Path | Bytes available server-side? | Retry action exposed? |
|---|---|---|
| Confirmed import → re-import | Yes | **Yes.** `POST /statement-imports/{id}/reimport` (`StatementImportController.java:70-75` → `StatementImportService.reimport:185-201`), then `POST /statement-imports/{id}/reimport/confirm` (`:79-82`). Frontend button + handler at `frontend/src/pages/StatementHistory.tsx:79-104`, `:214-221`. Password-protected PDFs re-prompt (`:91-100`, modal `:259-332`) because the password is deliberately never stored (`StatementImportService.java:180-183`) |
| Staged session, browser lost | Yes (48h) | **Backend yes** (`GET /import/sessions`, `GET /import/sessions/{id}` — `ImportController.java:94-114`); **frontend no** — `importApi.listSessions` is defined at `endpoints.ts:374` and called by nothing |
| Async job that FAILED | Yes (object storage) | Automatic worker retry with backoff, 5 attempts (`ImportJob.MAX_ATTEMPTS:67`, `recordFailure:269-286`). No user-facing retry button; `importJobsApi.recent` is unused |
| **Synchronous upload that failed to parse** | **No — bytes were never stored** | **Must re-upload from scratch.** This is the common case, because the async queue is off by default |

---

## 6. Observability for support

The infrastructure here is genuinely strong; the gap is the **entry point**.

### What exists

`ImportTraceService` (`backend/src/main/java/com/finora/imports/trace/ImportTraceService.java`) joins
`statement_analysis_sessions`, `import_jobs`, `import_job_stages`, `import_verification_findings`,
`merchant_learning_events` and `statement_imports` into one `ImportTraceDto.Trace`. Exposed at
`GET /api/v1/admin/imports/traces/by-analysis/{reference}` and `/by-job/{jobId}`
(`AdminImportTraceController.java:60-71`), gated on `PLATFORM_DIAGNOSTICS_VIEW`. There is a real admin
UI: `admin-portal/src/pages/ImportTrace.tsx`, routed at `admin-portal/src/App.tsx:37, :91`, rendering
handles, queue state, parse outcome, layout fingerprint, failure code, per-stage timings, verification
findings, learning events, completion.

Verification findings are persisted and queryable per import
(`ImportVerificationFinding.java:48-75`, `ImportVerificationFindingRepository`).

Every API response, success or error, carries `requestId` from MDC (`dto/ApiResponse.java:27-40`), and
the same correlation id is stored on the analysis row (`StatementAnalysisSession.java:127-135`) and the
job row (`ImportJob.correlationId:125-126`).

### Answering "my HDFC statement failed yesterday"

| Question | Answerable? | Why |
|---|---|---|
| Which file was it? | ⚠️ Recorded, not reachable | `statement_analysis_sessions.file_name` exists (`:72-73`) but is **deliberately excluded** from every admin DTO (`ImportTraceDto.java:27-32`, `AnalysisView:62-78`) |
| Which bank/format? | ⚠️ Partial | `sourceFormat` (CSV/PDF) and `layoutFingerprint` are on the trace. A fingerprint→bank-name resolution is explicitly *not* wired in (`ImportTraceService.java:60-66`) |
| Extraction outcome (rows found/staged/validated)? | ✅ Yes | `rowCount`, `unanchoredReasons`, per-rule `Finding` list, `Completion` block |
| Where and why it failed? | ✅ Yes for the parse stage | `failureCode` + `failureDetail` (truncated to 500 chars, `StatementAnalysisRecorder.truncate:182-185`) |
| **Can you find the record at all, starting from a user?** | ❌ **No** | Lookup keys are the analysis reference (`SA-YYYYMMDD-NNNN`) and the job id. The reference is **never returned to the user** — it is generated inside `recordParsed`/`recordFailed` and discarded on the failure path. There is no query by user id, email, file name, or correlation id anywhere in `StatementAnalysisSessionRepository` |
| Parser version, so "fixed in version X" is sayable? | ❌ **No such concept** | Grepped `backend/src/main` for `parserVersion`, `parser_version`, `engineVersion`, `engine_version`, `PARSER_VERSION`, `schemaVersion` — zero hits. `layoutFingerprint` identifies the *document shape*, not the code that read it |

The one place that does join a user to an import is
`GET /api/v1/admin/system/recent-imports` (`AdminSystemController.java:42-45` →
`AdminSystemService.recentImports:74-95`), which returns `userEmail` + `fileName`. But it reads
`statement_imports`, so it lists the **last 20 successful imports only**, with no search, no filter, and
no failures.

---

## Gap table

| Area | Status | Evidence |
|---|---|---|
| **PDF/CSV retention — successful import** | ✅ | `StatementContentService.java:126-157`; bytes on `StatementImport.fileContent:81-84` or R2 object; 90-day post-deletion reclaim `StatementStorageSweepService.java:96` |
| **PDF/CSV retention — staged, unconfirmed** | ✅ | `ImportSessionService.storeContent:217-225`; 48h TTL `:41`, swept `:111-138` |
| **PDF/CSV retention — failed parse** | ❌ | Bytes never written. `storeContent` is reachable only via `createSession`/`createMultiSection`, which failure paths never reach (`ImportService.java:190`, `:286-288`, `:325`) |
| **Retention policy documented & enforced** | ✅ | 48h session TTL + 90d object retention, both scheduled and bounded (`ImportSessionService.java:129-138`, `StatementStorageSweepService.java:127-136`) |
| **Admin can retrieve a user's failed upload** | ❌ | No admin file endpoint anywhere in `controller/`; user download is ownership-gated (`StatementImportService.getFile:162-171`). And for sync failures there are no bytes to retrieve |
| **Import status tracking — async path** | ✅ | `ImportJob.Status` 9-state machine with guarded transitions (`ImportJob.java:50-63`, `183-194`), per-stage rows (`ImportJobStage`), retry/dead-letter (`:269-286`) |
| **Import status tracking — sync path (the default)** | ⚠️ partial | Only `STAGED` → `CONFIRMED` (`ImportSession.java:33-34`). No `PROCESSING`, no `PARTIAL`, no `NEEDS_REVIEW` |
| **`StatementImport.status` field** | ❌ dead | Declared `StatementImport.java:104-105`, never assigned anywhere in `backend/src/main` |
| **Failure produces a durable import record** | ⚠️ partial | A telemetry row only (`StatementAnalysisRecorder.recordFailed:99-115`), reached via `recordParseFailure:236-250`. It is not an import object and has no user-facing or user-keyed surface |
| **Import history (user-facing) — successes** | ✅ | `GET /statement-imports` → `StatementImportService.listGroupedByAccount:72-110`; UI `frontend/src/pages/StatementHistory.tsx:190-204` |
| **Import history (user-facing) — failures** | ❌ | `statement_imports` is written only at confirm; no endpoint or screen shows a failed import |
| **Import history — validation/verification results shown** | ❌ | Findings are persisted (`ImportVerificationFinding.java:48-75`) but appear only in the admin trace; `StatementSummary` has no findings field (`frontend/src/types/index.ts:307-322`) |
| **Resume abandoned staged session (user-facing)** | ⚠️ partial | Backend built (`ImportController.java:94-114`); frontend never calls `importApi.listSessions` (`endpoints.ts:374`) |
| **Failure message clarity — PDF password (008/009)** | ✅ | `PdfTextExtractor.java:90-95`; UI branches, prompts, distinguishes required vs invalid (`Import.tsx:362-388`, `:586-590`) |
| **Failure message clarity — scanned PDF (010)** | ⚠️ partial | Precise server message (`ExtractionCheck.java:102-107`) but no client branch — generic `else` (`Import.tsx:381-386`) |
| **Failure message clarity — no table / zero rows (001 vs 007)** | ⚠️ partial | Correctly separated server-side (`ExtractionCheck.java:111-118`, `ErrorCode.java:36-42`); frontend treats both identically |
| **Failure message clarity — corrupt PDF** | ⚠️ partial | Good user message but **no `ErrorCode`** (`PdfTextExtractor.java:115-117`), so it lands as `failureCode = null` in the evidence table (`ImportService.java:239-241`) |
| **Failure message clarity — parser crash** | ❌ | `"Unexpected error"` in prod (`GlobalExceptionHandler.java:294-303`). Recorded server-side as the exception class name (`ImportService.java:239-241`), so support can tell it apart; the user cannot |
| **Failure message clarity — DB/storage failure at confirm** | ❌ | No evidence row is written on the confirm path at all; user gets a generic 409 or 500 (`GlobalExceptionHandler.java:136-142`, `:294-303`) |
| **Server-side failure distinguishability** | ✅ | `statement_analysis_sessions.failure_code` + fingerprint + reason histogram, on every path including crashes (BH-028, `ImportService.java:204-217`, `:341-353`) |
| **Retry without re-upload — confirmed import** | ✅ | `StatementImportController.java:70-82`; UI `StatementHistory.tsx:79-104`, `:214-221` |
| **Retry without re-upload — failed sync import** | ❌ | Bytes were never stored; the user must upload again |
| **Retry without re-upload — failed async job** | ✅ backend / ❌ UI | 5 automatic attempts with backoff (`ImportJob.java:67`, `269-291`); no user-facing retry, `importJobsApi.recent` unused (`endpoints.ts:429`) |
| **Admin trace exists and is reachable** | ✅ | `AdminImportTraceController.java:60-71`; UI `admin-portal/src/pages/ImportTrace.tsx`, route `admin-portal/src/App.tsx:37, :91` |
| **Admin trace findable from a real user + real complaint** | ❌ | Lookup only by `SA-` reference or job id; reference is never shown to the user; DTOs deliberately omit `userId`/`fileName` (`ImportTraceDto.java:27-32`); no repository query by user, filename, or correlation id (`StatementAnalysisSessionRepository.java`) |
| **Admin view joining user → import** | ⚠️ partial | `GET /admin/system/recent-imports` carries `userEmail` + `fileName` (`AdminSystemService.java:74-95`) but covers only the last 20 **successful** imports, unfiltered and unsearchable |
| **Structured logging / correlation id** | ✅ | `requestId` on every response (`dto/ApiResponse.java:27-40`), persisted on analysis and job rows (`StatementAnalysisSession.java:127-135`, `ImportJob.java:125-126`) |
| **Findings persisted & queryable** | ✅ | `ImportVerificationFinding.java:48-75`, `ImportVerificationFindingRepository` |
| **Parser version concept** | ❌ | Zero hits for `parserVersion` / `parser_version` / `engineVersion` / `engine_version` / `PARSER_VERSION` across `backend/src/main` |

---

## Cross-cutting note

Most of the async-path capability listed as ✅ above — job state machine, stage timings, automatic
retry, cancellation, byte retention on failure — is behind `app.import.queue.enabled` (default
`false`, `ImportJobService.java:69`) **and** requires `app.statement-storage.provider` to be set
(default unset, `application.yml:309`; hard-gated at `ImportJobService.accept:129-133`). Unless both
are configured in production, the effective behaviour is the synchronous path, which is where every
❌ in the failure/retry rows lives.

Whether those two variables are set in the production Railway environment could not be determined from
the repository and should be confirmed directly before relying on any async-path capability.
