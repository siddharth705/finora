# Import Session Freshness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this
> plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the import pipeline from silently replaying a stale, possibly pre-bugfix staged
result when a user re-uploads a statement whose bytes were already staged earlier, and give users
a direct way to discard a review they don't trust and force a fresh parse.

**Architecture:** `ImportSessionService.findLiveSessionByContentHash` currently treats "a STAGED
session exists for this exact file, and is within its 48h TTL" as sufficient grounds to skip
re-parsing and hand back the old staged rows. That was built purely to dedupe a double-clicked
upload or a retried HTTP request (see the method's own doc comment) — not to guarantee freshness
across a much longer window. This plan narrows that check to a short trust window (minutes) so
it still protects against the double-click/retry case it was built for, while any later
re-upload attempt gets a genuinely fresh parse. It also adds a "discard and start over" control
directly on the review screen (today it only exists on the separate "unfinished imports" list),
so a user who's unsure about what they're looking at has an immediate way to force a re-parse
without needing to know that list exists.

**Tech Stack:** Java 21 / Spring Boot (backend), JUnit 5 + Mockito + AssertJ (backend tests),
React + TypeScript (frontend), Vitest + Testing Library (frontend tests).

**Spec:** [import-pipeline-bug-hunt-2026-08-31.md](../../architecture/system-design/../../../../../../private/tmp/claude-501/-Users-sid-Downloads-finora/dd5c6810-4fe7-4a04-9a27-a25303bdd40e/scratchpad/import-pipeline-bug-hunt-2026-08-31.md)
(Finding 1) and [import-pipeline-fix-roadmap.md](../../../../../../private/tmp/claude-501/-Users-sid-Downloads-finora/dd5c6810-4fe7-4a04-9a27-a25303bdd40e/scratchpad/import-pipeline-fix-roadmap.md)
(Phase 1) — both delivered to the user directly since they live outside this repo's tracked
history (real-corpus investigation artifacts stay out of the repo).

## Global Constraints

- No schema/migration changes: `ImportSession.createdAt` already exists and is already indexed
  via `findByUserIdAndStatusOrderByCreatedAtDesc`'s usage; no new column needed.
- Do not change the 48-hour `SESSION_TTL` — that governs how long a user can come back and
  *resume reviewing* an abandoned import, which is a different, correct product behavior. Only
  the *automatic replay* behavior in `findLiveSessionByContentHash` changes.
- Do not touch `idx_import_sessions_live_content` (V79) or its uniqueness guarantee — the fix
  works entirely at the application layer, by deleting a stale match before the caller re-parses
  and creates a fresh session, exactly the same pattern the method already uses for an expired
  match.
- Follow this codebase's existing doc-comment density and reasoning style (see any method in
  `ImportSessionService.java` for the convention) — explain *why*, not just *what*.

---

## Task 1: Bound the duplicate-upload dedup window in `ImportSessionService`

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/ImportSessionService.java:42` (add constant
  near `SESSION_TTL`), `:420-433` (`findLiveSessionByContentHash`)
- Test: `backend/src/test/java/com/finora/imports/ImportSessionServiceTest.java` (extend the
  existing `findLiveSessionByContentHash_*` tests around line 294-333)

**Interfaces:**
- Consumes: `ImportSession.getCreatedAt(): Instant` (already exists, `ImportSession.java:163`),
  `ImportSession.getExpiresAt(): Instant` (already exists).
- Produces: `findLiveSessionByContentHash(UUID userId, String contentHash): Optional<ImportSession>`
  — same signature as today, callers (`ImportService.java:182-187,300-304`) are unaffected.

- [ ] **Step 1: Write the failing test for a stale-but-unexpired match**

Add to `ImportSessionServiceTest.java`, right after `findLiveSessionByContentHash_deletesAnExpiredMatch_andReportsNoneFound`
(after line 323):

```java
    /**
     * The actual bug this method exists to fix (2026-08-31): a session created minutes-to-hours
     * ago, still well within its 48h TTL, is NOT the double-click/retry case this dedup check was
     * built for -- it's a genuinely later re-upload, and by then the parser that produced its
     * staged rows may have been fixed. Confirmed against a real HDFC statement: a stale session
     * kept replaying a 12-row result on every re-upload even after the parser was fixed to
     * correctly extract all 243 rows. A session must be recent, not merely unexpired, to be
     * replayed automatically.
     */
    @Test
    void findLiveSessionByContentHash_deletesAStaleButUnexpiredMatch_andReportsNoneFound() {
        ImportSession stale = sessionOwnedBy(userId, Instant.now().plusSeconds(600), ImportSession.STATUS_STAGED);
        org.springframework.test.util.ReflectionTestUtils.setField(
                stale, "createdAt", Instant.now().minus(java.time.Duration.ofMinutes(10)));
        when(importSessionRepository.findFirstByUserIdAndContentHashAndStatusOrderByCreatedAtDesc(
                userId, "hash-d", ImportSession.STATUS_STAGED)).thenReturn(Optional.of(stale));

        Optional<ImportSession> found = service.findLiveSessionByContentHash(userId, "hash-d");

        assertThat(found).isEmpty();
        verify(importSessionRepository).delete(stale);
    }

    /**
     * The dedup protection this method exists FOR must still work: a session created moments ago
     * (a double-click, or a client retrying a request whose response was lost) is still returned
     * as a match, not treated as stale.
     */
    @Test
    void findLiveSessionByContentHash_stillReturnsAVeryRecentMatch_withinTheDedupWindow() {
        ImportSession justCreated = sessionOwnedBy(userId, Instant.now().plusSeconds(600), ImportSession.STATUS_STAGED);
        org.springframework.test.util.ReflectionTestUtils.setField(
                justCreated, "createdAt", Instant.now().minusSeconds(5));
        when(importSessionRepository.findFirstByUserIdAndContentHashAndStatusOrderByCreatedAtDesc(
                userId, "hash-e", ImportSession.STATUS_STAGED)).thenReturn(Optional.of(justCreated));

        Optional<ImportSession> found = service.findLiveSessionByContentHash(userId, "hash-e");

        assertThat(found).contains(justCreated);
        verify(importSessionRepository, never()).delete(any());
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw -o test -Dtest=ImportSessionServiceTest`
Expected: the two new tests FAIL — `findLiveSessionByContentHash_deletesAStaleButUnexpiredMatch_andReportsNoneFound`
fails because `found` is not empty (the current code returns the stale match); the "still returns
a very recent match" test should already PASS (it exercises existing, unchanged behavior) — that's
fine, it's there to pin the behavior the fix must not break, not to demonstrate a bug.

- [ ] **Step 3: Add the dedup-window constant**

In `ImportSessionService.java`, right after the `SESSION_TTL` declaration (line 42):

```java
    // How recent a staged session must be to count as "the same upload attempt" and get replayed
    // automatically, rather than triggering a fresh parse. This is deliberately much shorter than
    // SESSION_TTL: the two constants answer different questions. SESSION_TTL is "how long can a
    // user wait before resuming a review they started" (correctly measured in days). This is "how
    // long ago must this exact upload have happened for it to plausibly be a double-click or a
    // retried request rather than a genuinely new upload" (correctly measured in minutes) --
    // see findLiveSessionByContentHash's own doc comment for the incident that is why this exists:
    // a session staged under an old, buggy parser kept getting replayed on every later re-upload
    // of the same file, because "still within 48h" was being used as a proxy for "this is the same
    // upload attempt", which it never was.
    private static final Duration DUPLICATE_UPLOAD_DEDUP_WINDOW = Duration.ofMinutes(5);
```

- [ ] **Step 4: Update `findLiveSessionByContentHash` to enforce the window**

Replace the method body (lines 420-433) with:

```java
    @Transactional
    public Optional<ImportSession> findLiveSessionByContentHash(UUID userId, String contentHash) {
        Optional<ImportSession> match = importSessionRepository
                .findFirstByUserIdAndContentHashAndStatusOrderByCreatedAtDesc(
                        userId, contentHash, ImportSession.STATUS_STAGED);
        if (match.isEmpty()) return Optional.empty();

        ImportSession session = match.get();
        boolean expired = session.getExpiresAt().isBefore(Instant.now());
        // Not "and not expired" the way the class comment above for expiry describes -- a second,
        // independent reason to fall through to deletion. A session can be perfectly unexpired
        // (days left on its 48h TTL) and still be the wrong thing to replay, because "unexpired"
        // was never actually the question -- see DUPLICATE_UPLOAD_DEDUP_WINDOW's own doc comment.
        boolean staleForDedup = session.getCreatedAt()
                .isBefore(Instant.now().minus(DUPLICATE_UPLOAD_DEDUP_WINDOW));
        if (!expired && !staleForDedup) {
            return Optional.of(session);
        }
        importSessionRepository.delete(session);
        return Optional.empty();
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && ./mvnw -o test -Dtest=ImportSessionServiceTest`
Expected: PASS, all tests in the class including the two new ones and the pre-existing
`findLiveSessionByContentHash_returnsAnUnexpiredMatch` (which relies on `sessionOwnedBy`'s default
`createdAt`, effectively "now" — still well within the 5-minute window).

- [ ] **Step 6: Run the full backend test module to check for unrelated breakage**

Run: `cd backend && ./mvnw -o test -Dtest=ImportSessionServiceTest,ImportServiceSessionTest`
Expected: PASS. `ImportServiceSessionTest` exercises `ImportService`'s own use of this method;
confirms nothing upstream assumed the old, wider window.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/imports/ImportSessionService.java \
        backend/src/test/java/com/finora/imports/ImportSessionServiceTest.java
git commit -m "fix(imports): bound duplicate-upload dedup to a short window, not the 48h session TTL

A re-uploaded statement whose bytes matched an existing STAGED session was replayed verbatim
regardless of how long ago that session was created, as long as it hadn't hit its 48h TTL. That
window was built to dedupe a double-click or a retried request, not to guarantee freshness for two
days -- a session staged under a parser bug kept serving stale results (confirmed: 12 rows instead
of 243 on a real HDFC statement) on every later re-upload, even after the parser was fixed.
Narrows the automatic-replay window to 5 minutes; anything older now triggers a fresh parse."
```

---

## Task 2: Add a "discard and start over" control on the review screen

**Files:**
- Modify: `frontend/src/pages/Import.tsx` (`discardStagedSession` around line 443, new handler
  and state, the single-account review block around line 1216-1228)
- Test: `frontend/src/pages/Import.test.tsx` (new `describe` block)

**Interfaces:**
- Consumes: `importApi.discardSession(id: string): Promise<void>` (existing), `ConfirmDialog`
  component (existing, used identically elsewhere in this file at lines 906-919 — same props:
  `title`, `message`, `confirmLabel`, `danger`, `onConfirm`, `onCancel`), `startOver(): void`
  (existing, line 703).
- Produces: `discardStagedSession(id: string): Promise<boolean>` — return type changes from
  implicit `Promise<void>` to `Promise<boolean>` (true on success). The one existing caller
  (`Import.tsx:915`, `void discardStagedSession(id)`) discards the return value already and is
  unaffected.

- [ ] **Step 1: Write the failing test**

Add to `Import.test.tsx`, as a new `describe` block placed after the `'Import — file-type
routing'` block (after line 298):

```tsx
describe('Import — discarding the current review to force a fresh parse', () => {
  beforeEach(() => {
    vi.mocked(importApi.stageCsv).mockReset().mockResolvedValue(stagingResultWith({ sessionId: 'sess-9' }));
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
  });

  it('discards the staged session and returns to the upload step, after confirmation', async () => {
    vi.mocked(importApi.discardSession).mockReset().mockResolvedValue(undefined as never);
    const user = userEvent.setup();
    renderImport();

    await user.upload(screen.getByTestId('statement-file-input'), csvFile());
    await screen.findByText(/which account is this statement for/i);

    await user.click(screen.getByRole('button', { name: /discard and start over/i }));
    expect(await screen.findByText('Discard this import and start over?')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Discard' }));

    expect(importApi.discardSession).toHaveBeenCalledWith('sess-9');
    expect(await screen.findByTestId('statement-dropzone')).toBeInTheDocument();
  });

  it('does not discard without confirmation, and stays on the review screen', async () => {
    vi.mocked(importApi.discardSession).mockReset();
    const user = userEvent.setup();
    renderImport();

    await user.upload(screen.getByTestId('statement-file-input'), csvFile());
    await screen.findByText(/which account is this statement for/i);

    await user.click(screen.getByRole('button', { name: /discard and start over/i }));
    await screen.findByText('Discard this import and start over?');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(importApi.discardSession).not.toHaveBeenCalled();
    expect(screen.getByText(/which account is this statement for/i)).toBeInTheDocument();
  });

  it('stays on the review screen and shows an error if discarding fails', async () => {
    vi.mocked(importApi.discardSession).mockReset().mockRejectedValue(new Error('network'));
    const user = userEvent.setup();
    renderImport();

    await user.upload(screen.getByTestId('statement-file-input'), csvFile());
    await screen.findByText(/which account is this statement for/i);

    await user.click(screen.getByRole('button', { name: /discard and start over/i }));
    await screen.findByText('Discard this import and start over?');
    await user.click(screen.getByRole('button', { name: 'Discard' }));

    expect(await screen.findByText(/could not discard/i)).toBeInTheDocument();
    expect(screen.getByText(/which account is this statement for/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/Import.test.tsx -t "discarding the current review"`
Expected: FAIL — no button with accessible name matching `/discard and start over/i` exists yet.

- [ ] **Step 3: Make `discardStagedSession` report success/failure**

In `Import.tsx`, replace the existing `discardStagedSession` (lines 443-458) with:

```tsx
  async function discardStagedSession(id: string): Promise<boolean> {
    // Bug fix, caught by review: this function never cleared the banner on success, unlike every
    // other action on this page -- an unrelated error left showing (e.g. an ACTION_REQUIRED parse
    // failure, amber-colored) would sit there indefinitely, now misleadingly still reading as
    // actionable guidance about a file no longer relevant to what the user just did.
    clearError();
    setDiscardingSessionId(id);
    try {
      await importApi.discardSession(id);
      await queryClient.invalidateQueries({ queryKey: ['import-sessions'] });
      return true;
    } catch {
      showError('Could not discard this staged import.');
      return false;
    } finally {
      setDiscardingSessionId(null);
    }
  }
```

(Only the return type and the two `return` statements are new; the body is otherwise unchanged.)

- [ ] **Step 4: Add the review-screen discard handler and dialog state**

Near the other `useState` declarations for `confirmDiscardId` (around line 238), add:

```tsx
  const [confirmDiscardReviewOpen, setConfirmDiscardReviewOpen] = useState(false);
```

Near `discardStagedSession` (right after it), add:

```tsx
  /** The review screen's own discard action -- distinct from discardStagedSession's other caller
   *  (the "Continue previous import" list) because only this one needs to leave the review screen
   *  afterward. Only calls startOver() when the discard actually succeeded -- if it failed,
   *  discardStagedSession already showed the error, and resetting the review state on top of an
   *  unresolved failure would silently discard what the user was looking at for no reason. */
  async function discardReviewSessionAndStartOver() {
    if (!sessionId) return;
    const discarded = await discardStagedSession(sessionId);
    if (discarded) startOver();
  }
```

- [ ] **Step 5: Add the button and confirmation dialog to the single-account review screen**

In the transaction-preview `<p>` block (lines 1216-1228), add the button after the existing
`fileFormat` badge:

```tsx
          <div className="bg-card rounded shadow p-4 overflow-x-auto">
            <p className="text-sm mb-3 text-ink flex items-center gap-2 flex-wrap">
              <span>
                {rows.length} row(s) parsed and auto-categorized. Low-confidence guesses (marked below) will still
                import, but land on your Dashboard's review card afterward instead of being learned silently.
              </span>
              {fileFormat && (
                <span className="text-[10px] uppercase font-semibold text-muted border border-border rounded px-1.5 py-0.5 flex items-center gap-1 flex-shrink-0">
                  {fileFormat === 'PDF' ? <FileText size={11} /> : <FileSpreadsheet size={11} />} {fileFormat}
                </span>
              )}
              {sessionId && !reimportState && (
                <button
                  type="button"
                  onClick={() => setConfirmDiscardReviewOpen(true)}
                  className="text-xs text-muted underline flex-shrink-0"
                >
                  Not what you expected? Discard and start over
                </button>
              )}
            </p>
```

(Only the new `sessionId && !reimportState` block is added; everything else in this `<p>` stays
as-is. `reimportState` is excluded because a reimport has no `sessionId`-backed staged session to
discard — it replays a previously-confirmed `StatementImport`'s own stored bytes through a
different, always-fresh-parsing path, so this control doesn't apply to it.)

Immediately after the existing `{confirmDiscardId && (...)}` block (after line 919), add:

```tsx
      {confirmDiscardReviewOpen && (
        <ConfirmDialog
          title="Discard this import and start over?"
          message="This clears everything parsed from this file. You can upload the statement again right after."
          confirmLabel="Discard"
          danger
          onConfirm={() => {
            setConfirmDiscardReviewOpen(false);
            void discardReviewSessionAndStartOver();
          }}
          onCancel={() => setConfirmDiscardReviewOpen(false)}
        />
      )}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/Import.test.tsx -t "discarding the current review"`
Expected: PASS, all three new tests.

- [ ] **Step 7: Run the full Import.test.tsx suite to check for unrelated breakage**

Run: `cd frontend && npx vitest run src/pages/Import.test.tsx`
Expected: PASS — in particular the four pre-existing tests in `'Import — continuing an unfinished
import'` (lines 1612+), which exercise the OTHER `discardStagedSession` call site and must be
unaffected by the return-type change.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/pages/Import.tsx frontend/src/pages/Import.test.tsx
git commit -m "feat(imports): add a discard-and-start-over control on the review screen

The only existing way to discard a staged import lived on the separate 'unfinished imports' list
on the bare upload landing page -- the natural retry action (re-selecting/re-dropping the same
file) bypassed it entirely, and the review screen itself had no cancel/discard control at all. A
user looking at a review they don't trust now has a direct way to clear it and get a fresh parse,
without needing to know the other list exists."
```

---

## Self-check before opening the PR

- [ ] Run the full backend suite once: `cd backend && ./mvnw -o test`
- [ ] Run the full frontend suite once: `cd frontend && npx vitest run`
- [ ] Re-run the real-corpus check from the original investigation against `HDFC sav.pdf` to
      confirm a fresh upload now stages 243 rows (not applicable to re-verify via automated test —
      this is the same manual `scripts/corpus-run.py` check used during the original diagnosis;
      the fix here is about session replay, not extraction, so this step confirms the two together
      produce the right end-to-end result, not that it re-tests extraction itself).
- [ ] `git status --short` clean except the two commits above.
