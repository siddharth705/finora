# Held Statement Review — Plan 2 of 4: Operator Portal

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this
> plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give an operator somewhere to actually work a held statement — a filterable queue, a
detail view showing why it fired and what was extracted, the document itself, an audit history, and
the actions to assign it, investigate it, and resolve it.

**Architecture:** Additive over Plan 1. `held_statements` gains a snapshotted `bank_name` and the
assignment columns already present get their first writer. A new audited endpoint streams the
statement PDF, gated harder than the rest of the queue. The admin portal gets a list page and a
detail page modelled directly on `HeldImports.tsx`, which is the same shape of tool.

**Tech Stack:** Spring Boot / JPA / Flyway, Postgres, React + TypeScript (admin-portal),
Cloudflare R2 (unchanged).

**Spec:** The repository owner's approved brief, Phase 5 (Admin Review Portal) plus the assignment
half of Phase 6, recovered verbatim from the session transcript. Plan 1 is
`docs/superpowers/plans/2026-09-03-trust-review-held-statements.md`.

---

## Decisions taken by the repository owner, 2026-09-04

These were asked and answered before this plan was written. They are not defaults and must not be
quietly revisited during implementation.

| Question | Decision | Consequence for this plan |
|---|---|---|
| Can staff download the customer's PDF? | Yes, but **only ADMIN and SUPER_ADMIN** | Task 4 gates on the roles, not merely on `TRUST_REVIEW_MANAGE`, because that permission could later be granted to a support role who must not get statements |
| Show the Held ID to the customer? | **No, keep it internal** | Brief Phase 9's `Reference: HLD-...` line is deliberately NOT built. Sequential IDs disclose held volume |
| Distinct "Rejected" status for the customer? | **No, the normal failed status** | Already shipped (#857): rejection lands in FAILED carrying `IMPORT_TRUST_REVIEW_REJECTED` |
| How much does Plan 2 build? | Queue + detail + resolve **and** engineer assignment | Phase 6's `Assign To Me` / `Start Investigation` / `Add Notes` are pulled forward into Tasks 6–7. Phase 7 (parser re-run) stays in Plan 3 |
| Time estimate in the held message? | **No estimate** | Contradicts the brief's "usually completes within 24 hours" on purpose. Review is human and volume-dependent; a promise that breaks costs more than an honest open-ended wait |

## Brief Phase 9 is already satisfied — do not rebuild it

Verified against shipped code before writing this plan, not assumed:

- Both holds collapse to one user-facing status (`UserFacingImportStatus:66`), so the customer sees
  "Running additional checks" for a trust hold exactly as for a parser hold.
- The user frontend handles `HELD_FOR_TRUST_REVIEW` in its label map, `isSettled`, the detail
  message and the progress icon (#846).
- No Held ID reaches any user-facing surface — `grep heldId frontend/src` returns nothing, which
  matches the decision above.
- Rejection lands in FAILED with a reason the customer can read (#857).
- Polling stops on a held job, so there is no silent stall — the brief's actual Phase 9 requirement.

**Nothing in this plan touches `frontend/`.** If a task here starts editing the user app, that is a
scope error.

## Global Constraints

- **Storage is immutable.** No copy, no move, no second bucket. The download streams the same R2
  object `import_jobs.object_key` names.
- **Browsing a queue must not put customer content on screen.** `HeldStatementDto` carries no rows,
  no statement text and no object key. That is why listing is unaudited and opening the document is
  audited — the same split `HeldImportDto` already makes.
- **Every action that touches a real statement is audited with an actor.** An audited mutation
  reachable from an admin controller must name its actor parameter `actingAdminId`, or
  `AuditActorAttributionTest` fails the build. This caught Plan 1 Task 7.
- **Snapshot, never read live.** Anything shown about the extraction is what was true at hold time.
  The import session it came from can be swept; the hold outlives it.
- Before writing the migration: `git fetch origin`, list
  `backend/src/main/resources/db/migration`, and take the next free version. **V149 was the highest
  at the time of writing and the support-ticket track is actively adding more.** A lower version
  arriving later fails the backend's boot outright — this repository has hit that three times. Do
  not hardcode a number from this plan.

---

## Why `bank_name` must be snapshotted rather than joined

The brief's list view wants a Bank column. `ImportJob` never learns the bank — `StagedForJob`'s own
doc explains why: the account is chosen at confirm time, after the job is already finished. The name
exists only in `import_sessions.detected_account_json`.

Joining to it would be wrong twice over. It is a JSON blob to deserialize per row in a list view,
and — decisively — **that row can be deleted while the hold is open**. PR #869 exempts a held
session from the sweep, but the session still disappears the moment the hold resolves and its TTL is
renewed and elapses. A queue column sourced from it would blank out on exactly the old holds an
operator is most likely to be reviewing.

The worker already has the answer in hand: `staged.bankName()` at hold time. Snapshot it, for the
same reason `parser_version` and `reliability_status` are snapshotted.

## File Structure

**Create:**
- `backend/src/main/resources/db/migration/V<NEXT>__held_statement_bank_name.sql`
- `backend/src/main/java/com/finora/dto/HeldStatementDetailDto.java`
- `backend/src/test/java/com/finora/repository/HeldStatementQueryIT.java`
- `backend/src/test/java/com/finora/controller/AdminHeldStatementDownloadIT.java`
- `backend/src/test/java/com/finora/controller/AdminHeldStatementAssignmentIT.java`
- `admin-portal/src/pages/HeldStatements.tsx` + `.test.tsx`
- `admin-portal/src/pages/HeldStatementDetail.tsx` + `.test.tsx`

**Modify:**
- `HeldStatement.java` (bank name field), `HeldStatementService.java`, `HeldStatementRepository.java`
- `AdminHeldStatementController.java`, `HeldStatementDto.java`
- `ImportJobWorker.java` (pass the bank through to `createHold`)
- `admin-portal/src/api/endpoints.ts`, `admin-portal/src/types/index.ts`, `admin-portal/src/App.tsx`

---

### Task 1: Snapshot the bank name onto the hold

**Files:** migration; `HeldStatement.java`; `HeldStatementService.java`; `ImportJobWorker.java`;
`HeldStatementTest.java`; `HeldStatementRepositoryIT.java`

**Interfaces:** Produces `HeldStatement.getBankName()`; `createHold(job, staged, decision,
parserVersion)` reads `staged.bankName()` internally — signature unchanged.

- [ ] **Step 1: Confirm the next free migration version**

```bash
git fetch origin && ls backend/src/main/resources/db/migration | sort -V | tail -5
```

Use the next free number. Do not reuse a number from this document.

- [ ] **Step 2: Write the failing test** in `HeldStatementRepositoryIT`

```java
@Test
void theBankNameIsSnapshottedOnTheHold() {
    HeldStatement held = seed("HLD-2026-200001");
    held.recordBank("HDFC Bank");
    repository.saveAndFlush(held);

    assertThat(repository.findByHeldId("HLD-2026-200001").orElseThrow().getBankName())
            .isEqualTo("HDFC Bank");
}
```

- [ ] **Step 3: Run to verify failure**

```bash
cd backend && ./mvnw -o verify -Dtest=HeldStatementRepositoryIT -Dit.test=HeldStatementRepositoryIT -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compile error — `recordBank` does not exist.

- [ ] **Step 4: Write the migration**

```sql
-- The bank an operator sees in the queue, captured at hold time.
--
-- Not joined from import_sessions.detected_account_json, which is where the name actually lives:
-- that row is deleted once its TTL elapses (see ImportSessionService.sweepExpiredSessions and the
-- held-session exemption added alongside it), so a queue column sourced from it would blank out on
-- exactly the oldest holds. Same snapshot rule as parser_version and reliability_status.
--
-- Nullable: the parser cannot always name a bank, and a hold with no bank is still a valid hold.
ALTER TABLE held_statements ADD COLUMN bank_name VARCHAR(120);

COMMENT ON COLUMN held_statements.bank_name IS
    'Detected bank at hold time. Snapshotted because import_sessions, the only other source, is '
    'swept on a TTL while a hold can outlive it.';
```

- [ ] **Step 5: Add the field and setter**

```java
@Column(name = "bank_name")
private String bankName;

/** Set once, when the hold is opened. See the column comment for why this is a snapshot. */
public void recordBank(String bankName) { this.bankName = bankName; }

public String getBankName() { return bankName; }
```

- [ ] **Step 6: Populate it in `HeldStatementService.openHold`**

Immediately after `held.recordSnapshot(...)`:

```java
held.recordBank(staged.bankName());
```

`StagedForJob.bankName()` is already carried for the completion notification and is null when the
parser could not name one — which the column allows.

- [ ] **Step 7: Run to verify pass, then run the whole backend suite**

```bash
cd backend && ./mvnw -o test
```

The migration changes the schema, so `ddl-auto: validate` makes every IT a check on it.

- [ ] **Step 8: Self-review, then commit**

Re-read the diff for bugs and gaps before committing. Commit message states what was snapshotted
and why a join was rejected.

---

### Task 2: Queue filters

**Files:** `HeldStatementRepository.java`, `HeldStatementService.java`,
`AdminHeldStatementController.java`; `HeldStatementQueryIT.java`

**Interfaces:** Produces `HeldStatementService.list(page, size, HeldStatementFilter)` where
`HeldStatementFilter` is a record of `(HeldStatement.Status status, String bankName, Integer
olderThanHours, UUID assignedEngineerId)`, every field nullable meaning "no filter".

- [ ] **Step 1: Write the failing IT**

```java
@Test
void filtersByStatusBankAgeAndEngineer() {
    // seed: one HELD/"HDFC Bank" created now, one ASSIGNED/"ICICI" created 5 days ago
    // assert each filter returns only its own row, and that no filter returns both
}

/** Age is "older than", not "newer than" -- an operator triaging asks what has been waiting. */
@Test
void ageFilterSelectsTheOldest() { ... }

/** An unknown bank filters to nothing rather than falling back to everything, which would be a
 *  silent, dangerous default in a queue used to decide what reaches a ledger. */
@Test
void anUnmatchedFilterReturnsNothingRatherThanEverything() { ... }
```

- [ ] **Step 2: Run to verify failure**

- [ ] **Step 3: Implement with a Specification or an explicit `@Query`**

Follow whichever the repository already uses elsewhere; do not introduce a second idiom. Filters
combine with AND. Sort stays `createdAt ASC` — oldest first, the same ordering every other operator
queue in this codebase uses, because the longest-waiting user is the one to look at.

- [ ] **Step 4: Expose as query parameters**

```java
@GetMapping
public ApiResponse<PagedResponse<HeldStatementDto>> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size,
        @RequestParam(required = false) HeldStatement.Status status,
        @RequestParam(required = false) String bank,
        @RequestParam(required = false) Integer olderThanHours,
        @RequestParam(required = false) UUID engineerId) { ... }
```

- [ ] **Step 5: Run, self-review, commit**

---

### Task 3: Detail view — evidence, snapshot, timeline

**Files:** `HeldStatementDetailDto.java`; `HeldStatementService.java`;
`AdminHeldStatementController.java`; `AdminHeldStatementControllerIT.java`

**Interfaces:** Produces `HeldStatementDetailDto(HeldStatementDto summary, List<FindingView>
findings, List<EventView> timeline)` returned from `GET /{heldId}`.

The brief asks for exact numbers — *"Printed credits: 80 / Parsed credits: 79"* — not a sentence.
Those live in the verification finding's `details` map, which is already persisted per import by
`ImportVerificationRecorder`.

- [ ] **Step 1: Find where the findings are stored and read them**

```bash
grep -rn "class ImportVerificationRecorder" -A30 backend/src/main/java/com/finora/imports/analysis/
```

Read that class and its repository before designing the DTO. **Do not invent a shape** — the
findings table already exists and the trigger evidence must come from it rather than be
re-derived, or the detail view will disagree with what the gate actually saw.

- [ ] **Step 2: Write the failing IT**

```java
/** The operator has to see the numbers, not our sentence about them: "the counts disagree" is not
 *  enough to judge whether the extraction is wrong. */
@Test
void detailCarriesTheFindingDetailsBehindTheTriggerSummary() { ... }

/** The timeline is the audit history, oldest first -- it is read as a narrative. */
@Test
void detailCarriesTheEventTimelineOldestFirst() { ... }

/** Still no statement content: the detail view explains a decision, it does not display the
 *  document. That is what the download endpoint is for, and it is gated differently. */
@Test
void detailCarriesNoStatementContentOrObjectKey() { ... }
```

- [ ] **Step 3: Implement, run, self-review, commit**

---

### Task 4: The statement download — the one that needs the most care

**Files:** `AdminHeldStatementController.java`, `HeldStatementService.java`;
`AdminHeldStatementDownloadIT.java`

**Interfaces:** Produces `GET /api/v1/admin/held-statements/{heldId}/document` returning the PDF
bytes.

> **This is the only endpoint in the product that hands a customer's bank statement to a member of
> staff.** Treat it accordingly. Nothing else in this plan carries comparable risk.

- [ ] **Step 1: Write the failing IT, security cases first**

```java
/** The repository owner's decision, 2026-09-04: the download is pinned to ADMIN and SUPER_ADMIN,
 *  NOT merely to TRUST_REVIEW_MANAGE. That permission is grantable to other roles later, and a
 *  support role that can work the queue must still not be able to take the document. */
@Test
void downloadRefusesAnyRoleBelowAdmin() { ... }

@Test
void downloadRefusesAnUnauthenticatedCaller() { ... }

/** Every download writes an audit row naming who took it and whose statement it was. A download
 *  nobody can attribute is the failure mode this endpoint's whole risk profile rests on. */
@Test
void everyDownloadIsAudited() { ... }

@Test
void anAdminGetsThePdfBytes() { ... }
```

- [ ] **Step 2: Run to verify failure**

- [ ] **Step 3: Implement**

Method-level `@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")` on top of the class-level
permission gate — deliberately both, so widening the permission later cannot widen this. Read the
bytes through `StatementContentService.read(job)`, the same verified path every other statement read
uses (BH-045); never touch `StatementStorage` directly. Audit **before** streaming, so a failed
transfer still records the attempt.

- [ ] **Step 4: Run, self-review, commit**

---

### Task 5: Admin portal — the queue page

**Files:** `admin-portal/src/pages/HeldStatements.tsx` + test;
`admin-portal/src/api/endpoints.ts`; `admin-portal/src/types/index.ts`;
`admin-portal/src/App.tsx`

Model on `HeldImports.tsx` exactly — same page skeleton, same table idiom, same paging controls,
same empty state. Route `/held-statements`, lazy-loaded and wrapped in `ProtectedRoute`, matching
`App.tsx:97`.

Columns, from the brief: Held ID, User, Bank, Created At, Reliability Status, Trigger Reasons,
Parser Version, Current Status, Assigned Engineer. Filters: Status, Bank, Age, Assigned Engineer.

- [ ] **Step 1: Write the failing test**

```tsx
it('shows the Held ID, not a raw UUID — it is the reference an operator quotes', ...)
it('shows the oldest hold first', ...)
it('renders an empty state rather than a blank table when nothing is held', ...)
it('never renders statement content in the list', ...)
```

- [ ] **Step 2–4: Implement, run `npx vitest run`, self-review, commit**

---

### Task 6: Assignment actions (pulled forward from brief Phase 6)

**Files:** `HeldStatement.java` (already has `assign` / `startInvestigation` / `addNotes` from Plan
1 Task 4 — this task gives them their first caller), `HeldStatementService.java`,
`AdminHeldStatementController.java`; `AdminHeldStatementAssignmentIT.java`

**Interfaces:** Produces `POST /{heldId}/assign`, `POST /{heldId}/investigate`,
`POST /{heldId}/notes`.

The entity transitions and their guards already exist and are tested. This task is the service,
endpoints and audit around them — do not re-implement the state machine.

- [ ] **Step 1: Write the failing IT**

```java
/** Assign To Me is the common case and must not require typing an id. */
@Test
void assignDefaultsToTheCallingAdmin() { ... }

/** Reassignment before resolution is legitimate -- HeldStatementTest already pins that on the
 *  entity; this pins that the endpoint allows it. */
@Test
void anUnresolvedHoldCanBeReassigned() { ... }

/** The guards Plan 1 added must survive being reached over HTTP, as a 409 rather than a 500. */
@Test
void aResolvedHoldRefusesAssignmentWithAConflict() { ... }

/** Notes replace the engineer's own write-up; the history of what it said lives in the events. */
@Test
void addingNotesRecordsAnEventCarryingThem() { ... }
```

- [ ] **Step 2–4: Implement, run, self-review, commit**

---

### Task 7: Admin portal — the detail page

**Files:** `admin-portal/src/pages/HeldStatementDetail.tsx` + test; endpoints; types; route

Renders the four sections the brief names: trigger evidence with the actual numbers, the extraction
snapshot, the audit timeline, and the document download. Plus the Task 6 actions.

- [ ] **Step 1: Write the failing test**

```tsx
it('shows the printed and parsed numbers, not just the summary sentence', ...)
it('renders the audit timeline oldest first', ...)
it('hides the download control from a non-admin role', ...)
it('disables approve and reject once the hold is resolved, naming the state', ...)
```

- [ ] **Step 2–4: Implement, run, self-review, commit**

---

### Task 8: Full verification

- [ ] **Step 1:** `cd backend && ./mvnw clean verify` — run alone, never concurrently with another
      Maven invocation against the same `target/`.
- [ ] **Step 2:** `cd admin-portal && npx vitest run && npm run -s typecheck`
- [ ] **Step 3:** Re-run the corpus calibration and confirm the distribution is unchanged. This
      plan adds no gating condition, so any movement means something was altered by accident.
- [ ] **Step 4:** Commit and open the PR.

---

## Self-Review

**Spec coverage:** Brief Phase 5's list view → Tasks 2, 5. Its detail view → Tasks 3, 4, 7. Phase
6's assignment actions → Task 6, pulled forward by the owner's decision; Phase 6's *notes taxonomy*
(root cause, PR number, retest results) is deliberately left as free text here rather than
structured fields, because structuring it before anyone has written any would be inventing a schema
for work nobody has done. Phase 7 (parser re-run) stays in Plan 3. Phase 9 → already satisfied, with
the evidence recorded above rather than rebuilt.

**Placeholder scan:** Task 3 Step 1 deliberately names a `grep` instead of a DTO shape, because the
findings storage already exists and inventing its shape here would be guessing at a real schema —
the same reason Plan 1 refused to hardcode a migration version. Every other step carries the code
it needs.

**Type consistency:** `HeldStatement.Status` is Plan 1's six-value enum, used unchanged.
`HeldStatementDto` is extended, not replaced, and `HeldStatementDetailDto` wraps it rather than
duplicating its fields. `recordBank`/`getBankName` are defined once in Task 1 and consumed in Tasks
2 and 5.

**Known risk this plan does not close:** the queue shows a hold's snapshot, but nothing yet re-runs
the parser against a fixed build — so an operator can approve rows produced by a parser known to be
wrong. That is Plan 3's entire subject, and until it lands, approving is a judgement about the rows
as they are.
