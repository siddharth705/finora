# Held-Import UX & Triage-Parity Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the confirmed, bounded bugs found while live-testing the held-import-statement feature: a web upload silently reverts to the blank dropzone the instant a statement is held for review instead of showing the "we're checking this" message; the parser-gap ("Held Imports") admin queue has no way to download the statement it's asking an operator to fix; a real backend failure message (`IMPORT_015`, trust-review rejection) never reaches the user because the frontend has no curated copy for it; the Held Statements download button is missing the "this is logged" disclosure its sibling page already shows; and the held-item email alert feature (PR #990) silently logs at INFO when no admin holds the relevant permission, giving an unstaffed-permission blind spot no operational visibility.

**Architecture:** Six small, independent fixes across the existing web frontend, admin portal, and backend — no new subsystems. Each task mirrors an existing, working pattern elsewhere in the same codebase (the FAILED-status handling this bug sits next to; the Held Statements download endpoint this mirrors) rather than inventing a new one.

**Tech Stack:** React + TypeScript + Vitest/Testing Library (frontend, admin-portal), Spring Boot + JUnit/Mockito/TestContainers (backend).

**Spec:** None — bounded fixes scoped directly from live investigation findings in this conversation, not a separate design doc. See "Investigation evidence" under each task for the file:line citations the fix is based on.

## Explicitly out of scope (do not implement as part of this plan)

- **Mobile import flow.** Confirmed during investigation: `mobile/src/screens/import/ImportScreen.tsx` calls `stagePdf`/`stageCsv` synchronously and has no `ImportJob`, no polling, and no `HELD_FOR_REVIEW`/`HELD_FOR_TRUST_REVIEW` concept anywhere in `mobile/src`. Extending mobile to share the async triage pipeline is a real feature build (new API integration, polling, new UI states), not a bug fix, and needs its own brainstorm.
- **Account-deletion purge "orphaning" a held statement's R2 object.** Investigated and found to be an already-known, already-documented, deliberate trade-off — `StatementStorageSweepService`'s own class doc (`backend/src/main/java/com/finora/imports/storage/StatementStorageSweepService.java:83-88`) explicitly names this exact scenario ("content whose ONLY reference was ever ... a since-deleted user's rows, leaves nothing this query — or any DB query — can find") as "A known, deliberate gap," not a bug nobody noticed. Building a real fix (soft-deleting `ImportJob`/`ImportSession`, or an immediate synchronous object-delete during purge) is an architectural decision this codebase's own history (the "Reference counting, not delete-on-row-expiry" section of the same doc) shows was already made deliberately once — reopening it needs its own conversation, not a silent change bundled into this plan. The one real defect found — `ImportJobRepository.deleteByUserId`'s doc comment (`backend/src/main/java/com/finora/repository/ImportJobRepository.java:185-187`) claiming the freed object gets "eventually reclaimed," which the sweep service's own doc says is false for this case — is a one-line comment correction with zero behavior change; fix it opportunistically while touching this file for Task 2 below if convenient, but it is not its own task.

## Global Constraints

- No `Co-Authored-By: Claude` trailer in any commit message (repo CLAUDE.md, absolute rule).
- Work happens only in this worktree (`.claude/worktrees/held-import-ux-fixes`, branch `worktree-held-import-ux-fixes`) — the primary checkout is shared and read-only for writes.
- Every new backend endpoint that reads a real customer's statement bytes must be audited (matches every existing endpoint of this shape in the codebase) and must not weaken any existing permission gate.
- Frontend/admin-portal fixes must reuse existing shared helpers and styling conventions (`importJob.ts`, `design-system`, Tailwind utility classes already used on the same page) rather than introducing new ones.

---

### Task 1: Web — a held-for-review job stays visible instead of resetting to the blank dropzone

**Investigation evidence:** `frontend/src/pages/Import.tsx:918-928` — `ImportProgress`'s `onGaveUp` callback only excludes `job.status !== 'FAILED'` from the reset-to-dropzone branch. `ImportProgress.tsx`'s polling `tick()` (`frontend/src/components/ImportProgress.tsx:92-116`) calls `onGaveUp(next)` for every settled status that isn't `COMPLETED`-with-a-session — which includes `HELD_FOR_REVIEW` and `HELD_FOR_TRUST_REVIEW`. Because `setJob(next)` (child) and `onGaveUp` → `setJobId(null)` (parent) both fire from the same polling tick, React batches them into one render: `ImportProgress` unmounts in the same commit that would have shown it the held status, so the "We need to run some additional checks..." message (`frontend/src/lib/importJob.ts:166-169`, already correct) never has a chance to paint. The page instead reverts to the plain upload dropzone with no explanation.

Note what this fix does *not* need to touch: `ImportProgress.tsx` already renders the held state correctly (Clock icon, "Running additional checks" label, the full explanatory detail text) once it's allowed to stay mounted — confirmed by reading `frontend/src/components/ImportProgress.tsx:144-169`. The bug is entirely in the parent's premature unmount, not in what gets rendered.

**Files:**
- Modify: `frontend/src/lib/importJob.ts` — add one exported helper.
- Modify: `frontend/src/components/ImportProgress.tsx:152` — reuse the new helper instead of its inline duplicate.
- Modify: `frontend/src/pages/Import.tsx:918-928` — the actual fix.
- Test: `frontend/src/pages/Import.test.tsx`.

**Interfaces:**
- Produces: `isHeld(job: { status: ImportJobProgress['status'] }): boolean` from `frontend/src/lib/importJob.ts`, exported alongside the existing `isSettled`/`isCancellable`/`isReviewable` helpers in that module.

- [x] **Step 1: Write the failing test**

Add this test to `frontend/src/pages/Import.test.tsx`, directly below the existing `it('lets the user try a different file after a queued import fails', ...)` test (around line 1874), using the same `queuedJob` helper already defined in that `describe` block (line 1636):

```typescript
  /**
   * The bug found during live testing (2026-09-05): a held job settles in the same polling tick
   * that would show it, so ImportProgress used to unmount itself before the "additional checks"
   * message could ever paint -- the screen just reverted to the blank dropzone with no
   * explanation. Only FAILED was ever exempted from the reset; the two HELD_* statuses were not.
   */
  it('keeps the progress panel on screen (not the blank dropzone) when a job is held for review', async () => {
    vi.mocked(importJobsApi.progress).mockResolvedValue(queuedJob({ status: 'HELD_FOR_REVIEW' }));
    const user = userEvent.setup();
    renderImport();
    await waitFor(() => expect(importJobsApi.availability).toHaveBeenCalled());

    await user.upload(screen.getByTestId('statement-file-input'), csvFile());

    expect(await screen.findByText('Running additional checks')).toBeInTheDocument();
    expect(await screen.findByText(/We'll notify you once it's ready/)).toBeInTheDocument();
    // Actually still there, not just rendered once before an immediate unmount.
    expect(screen.getByTestId('import-progress')).toBeInTheDocument();
    expect(screen.queryByTestId('statement-file-input')).not.toBeInTheDocument();
  });
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/Import.test.tsx -t "keeps the progress panel on screen"`
Expected: FAIL — `screen.findByText('Running additional checks')` either times out or the assertion after it (`import-progress` still in the document) fails because the page has already reset to the dropzone (`statement-file-input` reappears).

- [x] **Step 3: Add the shared `isHeld` helper**

In `frontend/src/lib/importJob.ts`, immediately after the existing `isSettled` function (after line 71), add:

```typescript
/**
 * Whether a job ended by being handed to a person instead of finishing on its own — the two
 * triage holds, `HELD_FOR_REVIEW` (a parser gap) and `HELD_FOR_TRUST_REVIEW` (the extraction's own
 * evidence didn't add up). Both are terminal per {@link isSettled}, but unlike a genuine FAILED or
 * CANCELLED outcome, a held job is not actually over — the person is owed the same "stay on screen
 * and read why" treatment a FAILED job already gets, not a silent reset to the empty dropzone.
 */
export function isHeld(job: { status: ImportJobProgress['status'] }): boolean {
  return job.status === 'HELD_FOR_REVIEW' || job.status === 'HELD_FOR_TRUST_REVIEW';
}
```

- [x] **Step 4: Reuse it in `ImportProgress.tsx`**

In `frontend/src/components/ImportProgress.tsx`, change the import on line 4:

```typescript
import { detail, isCancellable, isHeld, isSettled, label, percent } from '../lib/importJob';
```

And replace the inline duplicate at line 152:

```typescript
  const held = job ? isHeld(job) : false;
```

(Replacing `const held = job?.status === 'HELD_FOR_REVIEW' || job?.status === 'HELD_FOR_TRUST_REVIEW';`.)

- [x] **Step 5: Fix the actual bug in `Import.tsx`**

In `frontend/src/pages/Import.tsx`, add the import (alongside the existing named imports already pulled from `../lib/importReview` etc. — add a new import line near the top, after line 34's `ImportNavState` import):

```typescript
import { isHeld } from '../lib/importJob';
```

Then change lines 918-928 from:

```typescript
                onGaveUp={(job) => {
                  // Cancelling is the user's own decision and needs no explanation, so that path
                  // returns straight to the dropzone. A failure does NOT reset here -- ImportTimeline
                  // (below) is about to show the curated reason and the way back to the dropzone; an
                  // immediate reset would unmount it before anyone could read either.
                  if (job.status !== 'FAILED') {
                    setJobId(null);
                    setUploadProgress(null);
                    clearArrivalState();
                  }
                }}
```

to:

```typescript
                onGaveUp={(job) => {
                  // Cancelling is the user's own decision and needs no explanation, so that path
                  // returns straight to the dropzone. A failure does NOT reset here -- ImportTimeline
                  // (below) is about to show the curated reason and the way back to the dropzone; an
                  // immediate reset would unmount it before anyone could read either. A held job does
                  // not reset either, for the same reason: ImportProgress is about to show the "we're
                  // running additional checks" message, and it can only do that if it stays mounted.
                  // Bug fix, caught live: this used to only exempt FAILED, so a held job settled and
                  // unmounted in the same polling tick, before its own message ever painted.
                  if (job.status !== 'FAILED' && !isHeld(job)) {
                    setJobId(null);
                    setUploadProgress(null);
                    clearArrivalState();
                  }
                }}
```

- [x] **Step 6: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/Import.test.tsx -t "keeps the progress panel on screen"`
Expected: PASS

- [x] **Step 7: Run the full frontend test files touched, to check for regressions**

Run: `cd frontend && npx vitest run src/pages/Import.test.tsx src/components/ImportProgress.test.tsx`
Expected: PASS, including the pre-existing FAILED-status and cancel tests (they must still behave identically — this change only widens the exemption, it doesn't touch the FAILED or cancel paths).

- [x] **Step 8: Commit**

```bash
git add frontend/src/lib/importJob.ts frontend/src/components/ImportProgress.tsx frontend/src/pages/Import.tsx frontend/src/pages/Import.test.tsx
git commit -m "fix(imports): keep the held-for-review message on screen instead of resetting to the dropzone"
```

---

### Task 2: Backend — download endpoint for the parser-gap "Held Imports" queue

**Investigation evidence:** `admin-portal/src/pages/HeldImports.tsx`'s detail panel shows a `Stored object` field (`detail.objectKey`, line 275) that plainly implies a downloadable file exists, but there is no way to get it — no download endpoint on `AdminHeldImportController.java`, no button in `HeldImportDetailPanel`. The sibling trust-review queue already solved exactly this: `AdminHeldStatementController.java:94-103` (`GET /{heldId}/document`) and `HeldStatementService.java:551-563` (`download(actingAdminId, heldId)` — audits *before* reading, then calls `statementContentService.read(job)` on the `ImportJob` it already has). `AdminHeldImportService` already loads the exact same `ImportJob` via its existing `require(jobId)` helper (`backend/src/main/java/com/finora/service/AdminHeldImportService.java:221-224`), so this mirrors an established pattern rather than building a new one.

**Files:**
- Modify: `backend/src/main/java/com/finora/service/AdminHeldImportService.java` — add `download(UUID, UUID)`.
- Modify: `backend/src/main/java/com/finora/controller/AdminHeldImportController.java` — add the `/{jobId}/document` endpoint.
- Test: `backend/src/test/java/com/finora/service/AdminHeldImportServiceTest.java` — unit coverage for the new method.
- Test: Create `backend/src/test/java/com/finora/controller/AdminHeldImportDownloadIT.java` — real-Postgres + real-storage integration coverage, mirroring `AdminHeldStatementDownloadIT.java`.

**Interfaces:**
- Produces: `AdminHeldImportService.DownloadedStatement` (record: `fileName`, `content`, `contentType`) and `AdminHeldImportService.download(UUID actingAdminId, UUID jobId): DownloadedStatement`, for the controller to turn into an HTTP response.

- [x] **Step 1: Write the failing service-level unit test**

In `backend/src/test/java/com/finora/service/AdminHeldImportServiceTest.java`, add `StatementContentService` to the fixture. Change the field list (after line 40) to add:

```java
    private com.finora.imports.storage.StatementContentService statementContentService;
```

Change `setUp()` (lines 45-52) to:

```java
    @BeforeEach
    void setUp() {
        repository = mock(ImportJobRepository.class);
        worker = mock(ImportJobWorker.class);
        auditService = mock(AuditService.class);
        statementContentService = mock(com.finora.imports.storage.StatementContentService.class);
        service = new AdminHeldImportService(repository, worker, auditService, statementContentService);
        when(repository.save(any(ImportJob.class))).thenAnswer(inv -> inv.getArgument(0));
    }
```

Then add this test near `detail_auditsEveryViewOfAHeldStatement` (after its closing brace, in the `// audit` section):

```java
    @Test
    void download_auditsAndReturnsTheStatementBytes() {
        ImportJob job = heldJob();
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        byte[] bytes = "%PDF-1.4 fixture".getBytes();
        when(statementContentService.read(job)).thenReturn(bytes);

        AdminHeldImportService.DownloadedStatement result = service.download(adminUserId, job.getId());

        assertThat(result.content()).isEqualTo(bytes);
        assertThat(result.fileName()).isEqualTo(job.getFileName());
        assertThat(result.contentType()).isEqualTo("application/pdf");
        verify(auditService).record(eq(adminUserId), eq("HELD_IMPORT_DOWNLOADED"), eq("ImportJob"),
                eq(job.getId()), any());
    }

    @Test
    void download_throwsNotFoundForAnUnknownJob() {
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.download(adminUserId, missing))
                .isInstanceOf(ApiException.class);
        verify(auditService, never()).record(any(), anyString(), anyString(), any(), any());
    }
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw -q -o test -Dtest=AdminHeldImportServiceTest`
Expected: FAIL to compile — `AdminHeldImportService` has no 4-arg constructor and no `download` method yet.

- [x] **Step 3: Implement `download` in `AdminHeldImportService.java`**

Add the import (alongside the existing `com.finora.imports.jobs.ImportJobWorker` import, after line 7):

```java
import com.finora.imports.storage.StatementContentService;
```

Add the field (after `private final AuditService auditService;`, line 56):

```java
    private final StatementContentService statementContentService;
```

Update the constructor (lines 58-64) to:

```java
    public AdminHeldImportService(ImportJobRepository repository,
                                  ImportJobWorker worker,
                                  AuditService auditService,
                                  StatementContentService statementContentService) {
        this.repository = repository;
        this.worker = worker;
        this.auditService = auditService;
        this.statementContentService = statementContentService;
    }
```

Add the record and method — insert directly after the `detail(...)` method (after line 102, before the `reprocess` doc comment):

```java
    /** What the download endpoint hands back -- everything the controller needs to set the
     *  response headers, in one value. Same shape as {@code HeldStatementService.DownloadedStatement},
     *  for the same reason: a byte array plus the two headers a browser download needs. */
    public record DownloadedStatement(String fileName, byte[] content, String contentType) {}

    /**
     * The one place in this queue that hands a customer's bank statement to a member of staff --
     * mirrors {@code HeldStatementService.download}'s reasoning exactly. Audited BEFORE the bytes
     * are read, not after, for the identical reason that doc gives: a failed read must still leave
     * a record that the attempt was made.
     *
     * <p>Not {@code readOnly}: it writes the audit entry. See {@code HeldStatementService.download}'s
     * own doc for the read-only-swallows-writes bug this avoids by omission.
     */
    @Transactional
    public DownloadedStatement download(UUID actingAdminId, UUID jobId) {
        ImportJob job = require(jobId);
        auditService.record(actingAdminId, "HELD_IMPORT_DOWNLOADED", "ImportJob", jobId,
                Map.of("actorId", actingAdminId.toString(),
                        "subjectUserId", job.getUserId().toString()));
        byte[] content = statementContentService.read(job);
        return new DownloadedStatement(job.getFileName(), content, contentTypeFor(job.getSourceFormat()));
    }

    /** Same switch {@code HeldStatementService.contentTypeFor} makes, over the formats this system
     *  actually stores -- not a filename-extension lookup, which is attacker-influenced. */
    private static String contentTypeFor(String sourceFormat) {
        if (sourceFormat == null) return "application/octet-stream";
        return switch (sourceFormat.toUpperCase()) {
            case "CSV" -> "text/csv";
            case "PDF" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }
```

- [x] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw -q -o test -Dtest=AdminHeldImportServiceTest`
Expected: PASS, all tests in the file (the constructor change affects every existing test via `setUp()`, so this run also proves nothing else broke).

- [x] **Step 5: Add the controller endpoint**

In `backend/src/main/java/com/finora/controller/AdminHeldImportController.java`, add imports (after the existing `com.finora.service.AdminHeldImportService` import, line 7):

```java
import com.finora.service.AdminHeldImportService.DownloadedStatement;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
```

Add the endpoint directly after `detail(...)` (after line 65, before the `reprocess` doc comment). Gated the same way the trust-review document endpoint is (`IMPORT_TRIAGE_MANAGE` alone is grantable to a future support/triage role per the same reasoning `AdminHeldStatementController`'s own doc already gives for `TRUST_REVIEW_MANAGE`; pinning to `ADMIN`/`SUPER_ADMIN` too keeps both endpoints that hand out raw statement bytes at the same security posture):

```java
    /**
     * Hands the raw statement bytes to a member of staff -- the one action in this queue with more
     * risk than the rest of the controller combined. Gated the same way {@code
     * AdminHeldStatementController.document} is: {@code IMPORT_TRIAGE_MANAGE} is grantable to a
     * future support/triage role, and that role must never be able to take the document itself, so
     * both conditions are restated in this one expression rather than layered as class-level plus
     * method-level -- a method-level {@code @PreAuthorize} REPLACES the class-level rule for Spring
     * Security, it does not add to it, which would silently drop the class's permission check for
     * this one endpoint alone.
     */
    @PreAuthorize("hasAuthority('IMPORT_TRIAGE_MANAGE') and hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{jobId}/document")
    public ResponseEntity<byte[]> document(@PathVariable UUID jobId) {
        DownloadedStatement file = heldImportService.download(currentUser.id(), jobId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .body(file.content());
    }
```

- [x] **Step 6: Write the failing integration test**

Create `backend/src/test/java/com/finora/controller/AdminHeldImportDownloadIT.java`, mirroring `AdminHeldStatementDownloadIT.java` exactly (same `@TestPropertySource` filesystem-storage setup, same `bearerFor` helper) but seeding a plain parser-gap hold instead of a trust-review one:

```java
package com.finora.controller;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.AuditLog;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.exception.ErrorCode;
import com.finora.imports.storage.ContentAddress;
import com.finora.imports.storage.StatementStorage;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /{jobId}/document} on the parser-gap queue -- mirrors {@code
 * AdminHeldStatementDownloadIT} exactly, one queue over. See that class's own doc for why this
 * needs a real, configured {@link StatementStorage} rather than {@code ImportJob.getFileContent()}
 * (BH-045: a job carries an address, never the bytes).
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-held-import-download-it"
})
class AdminHeldImportDownloadIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private StatementStorage storage;

    private static final byte[] PDF_BYTES = "%PDF-1.4 held-import fixture bytes".getBytes(StandardCharsets.UTF_8);

    private User createUser(String role) {
        User user = new User();
        user.setEmail("held-import-dl-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Held Import Download IT User");
        user.setRole(role);
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ImportJob heldJob() {
        User owner = createUser("USER");
        ContentAddress address = storage.store(PDF_BYTES);
        ImportJob job = new ImportJob(owner.getId(), "hdfc-june.pdf", address.hash(), address.key(), "PDF");
        job.markClaimed("worker", Instant.now());
        job.markClaimed("worker", Instant.now());
        job.recordFailure("IllegalStateException: no header row", "IllegalStateException",
                ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT, Instant.now());
        job.holdForReview("IllegalStateException", Instant.now());
        return importJobRepository.save(job);
    }

    private ResponseEntity<byte[]> download(UUID jobId, User admin) {
        return restTemplate.exchange("/api/v1/admin/held-imports/" + jobId + "/document",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), byte[].class);
    }

    @Test
    void downloadRefusesAnUnauthenticatedCaller() {
        ImportJob job = heldJob();

        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/api/v1/admin/held-imports/" + job.getId() + "/document",
                HttpMethod.GET, HttpEntity.EMPTY, byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void downloadRefusesAPlainUser() {
        ImportJob job = heldJob();
        User user = createUser("USER");

        ResponseEntity<byte[]> response = download(job.getId(), user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void everyDownloadIsAudited() {
        ImportJob job = heldJob();
        User admin = createUser("ADMIN");

        download(job.getId(), admin);

        List<AuditLog> entries = auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId());
        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry.getAction()).isEqualTo("HELD_IMPORT_DOWNLOADED");
            assertThat(entry.getUserId()).isEqualTo(admin.getId());
        });
    }

    @Test
    void anAdminGetsThePdfBytes() {
        ImportJob job = heldJob();
        User admin = createUser("ADMIN");

        ResponseEntity<byte[]> response = download(job.getId(), admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(PDF_BYTES);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
    }

    @Test
    void anUnknownJobIdIs404NotAnAttributeError() {
        User admin = createUser("ADMIN");

        ResponseEntity<byte[]> response = download(UUID.randomUUID(), admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
```

- [x] **Step 7: Run test to verify it fails, then passes**

Run: `cd backend && ./mvnw -q -o verify -Dit.test=AdminHeldImportDownloadIT -DfailIfNoTests=false`

(Expect it to fail to compile/run before Step 5's controller change is in place if run first; after Step 5 it should pass. Run it fresh now that Step 5 is done.)
Expected: PASS — 5 tests green.

- [x] **Step 8: Run the full backend suite**

Run: `cd backend && ./mvnw -q -o verify`
Expected: PASS, 0 failures — this constructor change touches a widely-mocked service, so the full suite (not just the two files above) is the real check that nothing else constructs `AdminHeldImportService` in a way this signature change breaks.

- [x] **Step 9: Commit**

```bash
git add backend/src/main/java/com/finora/service/AdminHeldImportService.java backend/src/main/java/com/finora/controller/AdminHeldImportController.java backend/src/test/java/com/finora/service/AdminHeldImportServiceTest.java backend/src/test/java/com/finora/controller/AdminHeldImportDownloadIT.java
git commit -m "feat(imports): add a download endpoint to the held-imports triage queue"
```

---

### Task 3: Admin-portal — "Download statement" button on the Held Imports detail panel

**Investigation evidence:** Same as Task 2 — `HeldImportDetailPanel` (`admin-portal/src/pages/HeldImports.tsx:217-327`) has Reprocess and Resolve buttons but nothing to view the statement the operator is being asked to fix. `HeldStatementDetail.tsx:164-202` already has the working client-side pattern to mirror: a `useMutation` calling the API client's `download` method (which streams a blob and triggers a client-side save via `downloadBlob`, since a plain `<a href>` can't carry the Bearer token — `admin-portal/src/api/endpoints.ts:411-418`), gated behind `canDownload` from `useAdminAuth().roles`.

**Files:**
- Modify: `admin-portal/src/api/endpoints.ts` — add `adminHeldImportApi.download`.
- Modify: `admin-portal/src/pages/HeldImports.tsx` — add the button and its mutation.
- Test: `admin-portal/src/pages/HeldImports.test.tsx`.

**Interfaces:**
- Consumes: `downloadBlob` and `withBlobErrorMessage` (already imported into `endpoints.ts`, `admin-portal/src/api/endpoints.ts:2, :54-64`).
- Produces: `adminHeldImportApi.download(jobId: string, fileName: string): Promise<void>`.

**Read first (already confirmed):** `admin-portal/src/pages/HeldImports.test.tsx` mocks `adminHeldImportApi` as `{ list, summary, get, reprocess, reprocessAll, resolve }` (no `download` yet — Step 1 adds it) and gates auth through a local `mockAuth(permissions: string[])` helper (lines 61-67) that does **not** currently pass `roles` to `mockAdminAuthState`, unlike `HeldStatementDetail.test.tsx`'s equivalent helper (which takes a second `roles` param). Since `mockAdminAuthState` defaults `roles` to `[]` (`admin-portal/src/test/mockAdminAuth.ts:25`), every existing test in this file currently resolves to `canDownload === false` once that check exists — so `mockAuth` itself needs the same second parameter its sibling file already has, not just a new test.

- [x] **Step 1: Write the failing test, updating the file's shared auth helper**

In `admin-portal/src/pages/HeldImports.test.tsx`, first add `download: vi.fn()` to the mocked module (line 20-29):

```typescript
vi.mock('../api/endpoints', () => ({
  adminHeldImportApi: {
    list: vi.fn(),
    summary: vi.fn(),
    get: vi.fn(),
    reprocess: vi.fn(),
    reprocessAll: vi.fn(),
    resolve: vi.fn(),
    download: vi.fn(),
  },
}));
```

Then update `mockAuth` (lines 61-67) to accept roles, matching `HeldStatementDetail.test.tsx`'s identical helper exactly:

```typescript
function mockAuth(permissions: string[], roles: string[] = []) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    roles,
    fullName: 'Ops Admin',
  }));
}
```

This is a safe widening (a third-parameter-less call keeps behaving exactly as before, `roles` defaulting to `[]`) — every existing call site in this file (`mockAuth(['IMPORT_TRIAGE_MANAGE'])`, appearing in every test) keeps compiling and keeps meaning what it meant before.

Then add this test, reusing the file's existing `heldRow`/`heldDetail` fixtures (lines 31-48) and the `Details` button pattern the `'never shows the raw parser error...'` test already establishes (lines 104-116):

```typescript
  it('lets an operator with an admin role download the held statement', async () => {
    vi.mocked(adminHeldImportApi.download).mockResolvedValue(undefined);
    mockAuth(['IMPORT_TRIAGE_MANAGE'], ['ADMIN']);
    renderPage();
    await screen.findByText('hdfc-june.pdf');
    await userEvent.click(screen.getByRole('button', { name: /details/i }));
    await screen.findByText(/no header row found/);

    await userEvent.click(screen.getByRole('button', { name: /download statement/i }));

    await waitFor(() => expect(adminHeldImportApi.download)
      .toHaveBeenCalledWith(heldRow.id, heldRow.fileName));
  });

  /** Mirrors HeldStatementDetail's identical rule and identical reasoning: a role that can work
   *  the queue must not see a control for an action the backend would refuse anyway. */
  it('hides the download control from a non-admin role', async () => {
    mockAuth(['IMPORT_TRIAGE_MANAGE'], ['SUPPORT']);
    renderPage();
    await screen.findByText('hdfc-june.pdf');
    await userEvent.click(screen.getByRole('button', { name: /details/i }));
    await screen.findByText(/no header row found/);

    expect(screen.queryByRole('button', { name: /download statement/i })).not.toBeInTheDocument();
  });
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd admin-portal && npx vitest run src/pages/HeldImports.test.tsx -t "download"`
Expected: FAIL — no "Download statement" button exists yet, and `adminHeldImportApi.download` isn't called by anything.

- [x] **Step 3: Add the API client method**

In `admin-portal/src/api/endpoints.ts`, add to `adminHeldImportApi` (after the existing `resolve` entry, before the closing brace at line 379):

```typescript
  // Same pattern as adminHeldStatementApi.download -- a plain <a href> can't carry the Bearer
  // token, so this rides the authenticated axios instance and triggers the browser download
  // client-side. Uses the statement's real fileName (available from the already-loaded detail),
  // unlike the trust-review sibling's hardcoded ".pdf" -- CSV imports can be held too.
  download: async (jobId: string, fileName: string) => {
    try {
      const res = await api.get(`/admin/held-imports/${jobId}/document`, { responseType: 'blob' });
      downloadBlob(res.data as Blob, fileName);
    } catch (err) {
      throw await withBlobErrorMessage(err);
    }
  },
```

- [x] **Step 4: Add the button to `HeldImportDetailPanel`**

In `admin-portal/src/pages/HeldImports.tsx`, add imports: change line 3 to include `Download`:

```typescript
import { Clock, Download, PlayCircle, RefreshCw } from 'lucide-react';
```

Add the auth hook import (matching `HeldStatementDetail.tsx:7`):

```typescript
import { useAdminAuth } from '../context/AdminAuthContext';
```

Inside `HeldImportDetailPanel` (after the `const [reason, setReason] = useState('');` line, currently line 232), add:

```typescript
  const { roles } = useAdminAuth();
  const canDownload = roles.includes('ADMIN') || roles.includes('SUPER_ADMIN');
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  async function handleDownload() {
    if (!detail) return;
    setDownloading(true);
    setDownloadError(null);
    try {
      await adminHeldImportApi.download(detail.job.id, detail.job.fileName);
    } catch {
      setDownloadError('Could not download this statement.');
    } finally {
      setDownloading(false);
    }
  }
```

Add the button next to the "Held import" heading (replacing the header block at lines 236-241):

```typescript
      <div className="flex items-start justify-between">
        <h2 className="text-lg font-semibold text-ink">Held import</h2>
        <div className="flex items-center gap-3">
          {detail && canDownload && (
            <button
              type="button"
              onClick={() => void handleDownload()}
              disabled={downloading}
              className="inline-flex items-center gap-2 rounded-lg border border-border px-3 py-1.5 text-xs text-ink hover:bg-card disabled:opacity-50"
            >
              <Download className="h-3.5 w-3.5" />
              {downloading ? 'Downloading…' : 'Download statement'}
            </button>
          )}
          <button className="text-muted hover:text-ink text-sm" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
      {downloadError && <p className="text-xs text-red-400">{downloadError}</p>}
```

- [x] **Step 5: Run test to verify it passes**

Run: `cd admin-portal && npx vitest run src/pages/HeldImports.test.tsx`
Expected: PASS, all tests in the file.

- [x] **Step 6: Commit**

```bash
git add admin-portal/src/api/endpoints.ts admin-portal/src/pages/HeldImports.tsx admin-portal/src/pages/HeldImports.test.tsx
git commit -m "feat(imports): add a download button to the held-imports detail panel"
```

---

### Task 4: Web — curated frontend message for `IMPORT_015` (trust-review rejection)

**Investigation evidence:** The backend already writes a real, deliberated message for this code — `ErrorCode.java:151-153` (`IMPORT_TRUST_REVIEW_REJECTED`, `IMPORT_015`): *"We checked this statement and could not read it accurately enough to import it. Nothing was added to your accounts."* But `frontend/src/api/importFailureMessages.ts`'s `IMPORT_FAILURE_MESSAGES` table has no entry for it, so both `ImportTimeline.tsx:173` and `StatementHistory.tsx:23` fall through to the generic `"Fynora couldn't complete this import. Please try again."` — a user who waited through a multi-day human review never actually learns why it didn't work.

**Files:**
- Modify: `frontend/src/api/errorCodes.ts` — add the constant.
- Modify: `frontend/src/api/importFailureMessages.ts` — add the table entry.
- Test: `frontend/src/api/importFailureMessages.test.ts` (create if it doesn't already exist — check first).

**Interfaces:**
- Produces: `TRUST_REVIEW_REJECTED = 'IMPORT_015'` from `frontend/src/api/errorCodes.ts`.

- [x] **Step 1: Check for an existing test file**

Run: `ls frontend/src/api/importFailureMessages.test.ts 2>/dev/null || echo "no existing test file"`

If it exists, read it to match its exact style before writing Step 2's test. If not, Step 2 creates it.

- [x] **Step 2: Write the failing test**

Add to `frontend/src/api/importFailureMessages.test.ts` (creating the file with this content plus a standard `import { describe, it, expect } from 'vitest';` header if it doesn't already exist):

```typescript
import { describe, it, expect } from 'vitest';
import { importFailureMessage } from './importFailureMessages';
import { TRUST_REVIEW_REJECTED } from './errorCodes';

describe('importFailureMessage', () => {
  it('has a curated message for a trust-review rejection, not the generic fallback', () => {
    const message = importFailureMessage(TRUST_REVIEW_REJECTED);
    expect(message).toBeDefined();
    expect(message).toMatch(/could not read it accurately enough/);
  });
});
```

- [x] **Step 3: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/api/importFailureMessages.test.ts`
Expected: FAIL — `TRUST_REVIEW_REJECTED` doesn't exist yet (compile error) or `message` is `undefined`.

- [x] **Step 4: Add the error code constant**

In `frontend/src/api/errorCodes.ts`, add after `NO_ACTIVITY_IN_PERIOD` (after line 31):

```typescript
// The honest explanation for a multi-day trust-review hold that didn't clear -- without this
// curated entry, the person who waited through that review sees the same generic "couldn't
// complete this import" every other unrecognised failure gets, with no account of why.
export const TRUST_REVIEW_REJECTED = 'IMPORT_015';
```

- [x] **Step 5: Add the curated message**

In `frontend/src/api/importFailureMessages.ts`, update the import (line 21-27) to add `TRUST_REVIEW_REJECTED`:

```typescript
import {
  NO_HEADER_DETECTED,
  NO_TRANSACTIONS_FOUND,
  NO_ACTIVITY_IN_PERIOD,
  SCANNED_OCR_REQUIRED,
  CORRUPT_PDF,
  TRUST_REVIEW_REJECTED,
} from './errorCodes';
```

Add the table entry (after `CORRUPT_PDF`, before the closing `};` at line 52). Reusing the backend's own already-deliberated wording verbatim, not new copy — that message text was an explicit product decision per `ErrorCode.java`'s own comment ("Changing either half is a product call, not a tidy-up"), so this task only wires the existing approved text through, it doesn't author new customer-facing copy:

```typescript
  [TRUST_REVIEW_REJECTED]:
    'We checked this statement and could not read it accurately enough to import it. Nothing ' +
    'was added to your accounts.',
```

- [x] **Step 6: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/api/importFailureMessages.test.ts`
Expected: PASS

- [x] **Step 7: Commit**

```bash
git add frontend/src/api/errorCodes.ts frontend/src/api/importFailureMessages.ts frontend/src/api/importFailureMessages.test.ts
git commit -m "fix(imports): show the real reason a trust-reviewed statement was rejected"
```

---

### Task 5: Admin-portal — add the "this download is logged" disclosure to Held Statements

**Investigation evidence:** `HeldImports.tsx:247-250` shows operators a clear disclosure before they can act: *"Opening this record has been logged against your account, because it shows content from a customer's bank statement."* `HeldStatementDetail.tsx`'s download button (lines 192-202) triggers an equally-audited action (`HeldStatementService.download`, confirmed to write an audit entry before reading — `HeldStatementService.java:551-563`) but shows no equivalent notice anywhere on the page. The backend behavior is already correct and already audited; this is purely a missing disclosure on the one page that's missing it.

**Files:**
- Modify: `admin-portal/src/pages/HeldStatementDetail.tsx`.
- Test: `admin-portal/src/pages/HeldStatementDetail.test.tsx`.

**Read first (already confirmed):** `HeldStatementDetail.test.tsx` already has `mockAuth(['TRUST_REVIEW_MANAGE'], ['ADMIN'])` and `renderPage()` helpers (lines 71-82, 108-115), and an existing test right next to where this one belongs — `'shows the download control for an ADMIN role'` (lines 201-207) — that already establishes the exact `mockAuth`/`renderPage`/`screen.findByText('count disagree')` sequence to reuse verbatim.

- [x] **Step 1: Write the failing test**

In `admin-portal/src/pages/HeldStatementDetail.test.tsx`, add this test directly after `'shows the download control for an ADMIN role'` (after line 207, before the `'hides the download control...'` test):

```typescript
  it('discloses that downloading is logged, matching the held-imports queue', async () => {
    mockAuth(['TRUST_REVIEW_MANAGE'], ['ADMIN']);
    renderPage();
    await screen.findByText(/count disagree/i);

    expect(await screen.findByText(/downloading this statement has been logged against your account/i))
      .toBeInTheDocument();
  });

  it('does not show the download-is-logged disclosure to a role that cannot download', async () => {
    mockAuth(['TRUST_REVIEW_MANAGE'], ['SUPPORT']);
    renderPage();
    await screen.findByText(/count disagree/i);

    expect(screen.queryByText(/downloading this statement has been logged/i)).not.toBeInTheDocument();
  });
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd admin-portal && npx vitest run src/pages/HeldStatementDetail.test.tsx -t "discloses that downloading"`
Expected: FAIL — no such text on the page.

- [x] **Step 3: Add the disclosure**

In `admin-portal/src/pages/HeldStatementDetail.tsx`, add directly below the header block that contains the download button (after the closing `</div>` at line 203, before `{actionError && (` at line 205):

```typescript
      {canDownload && (
        <p className="text-xs text-muted">
          Downloading this statement has been logged against your account, because it shows
          content from a customer&apos;s bank statement.
        </p>
      )}
```

(Gated on `canDownload`, not on whether a download has actually happened yet — the disclosure is about what clicking the button *will* do, matching `HeldImports.tsx`'s own unconditional placement once its own gate — there, "the detail loaded at all" — is satisfied.)

- [x] **Step 4: Run test to verify it passes**

Run: `cd admin-portal && npx vitest run src/pages/HeldStatementDetail.test.tsx`
Expected: PASS, all tests in the file.

- [x] **Step 5: Commit**

```bash
git add admin-portal/src/pages/HeldStatementDetail.tsx admin-portal/src/pages/HeldStatementDetail.test.tsx
git commit -m "fix(imports): disclose that downloading a held statement is logged"
```

---

### Task 6: Backend — escalate the held-item alert's zero-recipient log to WARN

**Investigation evidence:** `HeldItemAdminAlertService.java:151-153` — if no admin currently holds `IMPORT_TRIAGE_MANAGE`/`TRUST_REVIEW_MANAGE`, the method silently returns after a `log.info(...)`. Confirmed intentional-but-invisible: the same class already treats a comparable deployment gap (`ADMIN_APP_BASE_URL` unset, line 133) as `log.warn`, not `log.info` — an unstaffed permission is a strictly worse gap (holds accumulate with zero notification at all, not just a degraded link), so it should carry at least the same visibility, not less.

**Files:**
- Modify: `backend/src/main/java/com/finora/service/HeldItemAdminAlertService.java:152`.

This is a one-line log-level change with no change to any return value, exception, or side effect a test can observe through mocks — this codebase has no existing pattern for asserting a specific SLF4J log level (no log-capture test infrastructure exists elsewhere in this service's own test file), so this task skips the red/green test cycle and instead relies on the full existing test suite to confirm no behavior regression.

- [x] **Step 1: Make the change**

In `backend/src/main/java/com/finora/service/HeldItemAdminAlertService.java`, change line 152 from:

```java
            log.info("No admin holds {} -- no held-item alert sent for \"{}\"", permissionName, subject);
```

to:

```java
            log.warn("No admin holds {} -- no held-item alert sent for \"{}\"", permissionName, subject);
```

- [x] **Step 2: Run the existing test file to confirm no behavior regression**

Run: `cd backend && ./mvnw -q -o test -Dtest=HeldItemAdminAlertServiceTest`
Expected: PASS, all existing tests including `alertParserGapHeld_sendsNothingWhenNoAdminHoldsThePermission` — the log-level change is invisible to that test's mock-based assertions, which is the point: behavior is identical, only operational visibility changed.

- [x] **Step 3: Commit**

```bash
git add backend/src/main/java/com/finora/service/HeldItemAdminAlertService.java
git commit -m "fix(imports): warn instead of merely logging when no admin can receive a held-item alert"
```

---

## After all six tasks

Run the full suites once more from a clean state before opening a PR:

```bash
cd backend && ./mvnw -q -o verify
cd ../frontend && npx vitest run
cd ../admin-portal && npx vitest run
```

Then proceed to `superpowers:finishing-a-development-branch` for the merge/PR menu, following this repo's `CLAUDE.md` git workflow (push, `gh pr create`, no `Co-Authored-By` trailer, verify the merge lands on `origin/main` by content once done).
