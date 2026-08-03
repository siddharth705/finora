# Incident: customer PII committed to Git history

**Date identified:** 2026-08-03
**Status:** Contained in current code. **Open — remediation decision DEFERRED 2026-08-03 by Siddharth, to be revisited (see §6).**
**Severity:** To be confirmed by the exposure assessment below (§3). Provisionally Medium; becomes
High if the repository is public, has ever been public, has forks, or has been cloned outside the
core team.

**Next action:** complete §3, choose an option in §4, record it in §6. If Option B, §5 must be
confirmed first.

This is recorded as a security incident rather than as an engineering to-do. Customer personal data
reaching a version-controlled repository is a data-handling failure with a decision trail worth
keeping, regardless of how small the blast radius turns out to be. The point of this document is
that the decision — including a decision to accept the risk — is written down with its reasoning.

---

## 1. What happened

A real customer's name and their 14-digit bank account number were committed inside a Java source
comment in `PdfTableLocator.java`. The comment documented what a real Bank of Baroda statement's
repeated page banner looked like, quoting the banner verbatim:

```
// printed at the top of EVERY page ("<HOLDER NAME> SAVINGS ACCOUNT  - <14 digits>"),
```

(shown here in its redacted form)

The data was **not** in a fixture, a test resource, or a database — it was in main source, which is
why it went unnoticed.

## 2. Timeline

| When | What |
|---|---|
| commit `6a188da` | Introduced, while documenting a real-document bug fix. |
| — | `scripts/check-fixture-hygiene.sh` did not scan it: its target filter was `test/`, `fixtures/`, `resources/`, `*Test.java` and `*.md`. A main source file matched none of those. |
| commit `2231b2f` (attempt) | The same number was copied *out* of that comment into a new synthetic fixture. The hook fired on the copy, blocking the commit. |
| commit `74a3d76` | Comment redacted to describe the shape (`<HOLDER NAME>`, `<14 digits>`) rather than the values. Hygiene scan widened from location-based to extension-based, so every `.java`/`.ts`/`.sql`/`.md` file is now scanned. Verified to fire by staging a main-source file containing an email and an IFSC. |
| 2026-08-03 | This record created. |

## 3. Exposure assessment — **complete before deciding**

None of these should be assumed. Record the answer next to each, including "none found" — an
unanswered box and a box answered "no" are not the same evidence.

| # | Question | Answer | How to check |
|---|---|---|---|
| 1 | Repository visibility — private or public? | | `gh repo view --json visibility`, or the GitHub UI |
| 2 | Number of collaborators with access | | Settings → Collaborators and teams |
| 3 | **Has the repository ever been public?** | | Repo audit log; a repo made private later still leaves earlier commits reachable |
| 4 | Do any forks exist? | | Insights → Forks. A fork retains the original history even after an upstream rewrite |
| 5 | Has anyone outside the core team cloned or downloaded it? | | Insights → Traffic → Clones (14-day window only — absence here is weak evidence) |
| 6 | **Could CI/CD logs, build artifacts, or backups contain the exposed data?** | | CI job logs, cached workspaces, artifact retention, any repo backup or mirror |

Item 6 matters as much as the repository itself and is the one most often missed: a history rewrite
removes the data from Git and leaves it untouched in a CI log, a cached build workspace, or a
nightly backup. A rewrite that stops there is incomplete, and believing it was complete is worse
than knowing it was not.

**If the repository is or ever was public**, treat Option B as the default and separately consider
whether the affected customer requires notification under the DPDP Act 2023 — that is a question
for whoever owns that obligation, not one to settle in this document.

Note for context: an account number is **not a credential**. It cannot be rotated. Removal and
access control are the only mitigations available, which is why the exposure assessment carries more
weight here than it would for a leaked key.

## 4. Decision

Choose **one**. Record it in §5 either way.

### Option A — Accept the risk

Leave Git history unchanged. Requires all four:

- [ ] Rationale recorded in §5, referencing the §3 findings that support it
- [ ] Current codebase confirmed clean (done — `74a3d76`)
- [ ] Automated PII scanning in place to prevent recurrence (done — extension-based hygiene scan,
      plus trace-capture validation and `TraceCorpusHealthTest`)
- [ ] Exposure assessment complete, with no finding that contradicts acceptance

Defensible when the repository is private, access is limited to the core team, and no public
exposure, fork or external clone was found. Not defensible on the basis that a rewrite is
inconvenient.

### Option B — Rewrite history

Remove the exposed values from the entire history. Requires all five, **in order**:

- [ ] §5 pre-rewrite confirmations obtained from every developer
- [ ] Remove the values across all commits with a history-rewrite tool (`git filter-repo`)
- [ ] Force-push the rewritten history
- [ ] Recreate affected branches and worktrees (currently `claude/competent-bell-f17420`,
      `claude/silly-colden-4cf80e`)
- [ ] Purge any CI logs, artifacts, or backups identified in §3 item 6 — the rewrite does not
      touch them

## 5. Pre-rewrite confirmations — **required before Option B is executed**

A history rewrite is destructive to every existing clone. Do not begin until each is confirmed:

- [ ] **No developer has unpushed commits.** Anything not pushed is orphaned by the rewrite and
      cannot be recovered from the remote.
- [ ] **Every developer understands their clone must be re-synced or re-cloned**, and knows which
      they are doing.
- [ ] **Active feature branches and worktrees are backed up**, since their commit hashes all change.
- [ ] **A window is agreed** in which nobody is pushing.

Only once all four are confirmed should the rewrite be executed.

## 6. Decision record

> **DEFERRED — 2026-08-03, by Siddharth.** Consciously postponed, not overlooked. The exposure
> assessment (§3) and the decision below are to be revisited; until then this incident stays open.
>
> Recorded because a deferral and an oversight leave identical blank fields, and the difference
> matters when this is read later. Nothing here is blocking: the code is sanitised (`74a3d76`), the
> preventive measures in §7 are live, and the residual exposure is historical only.

**Decision:** *(Option A — Accept the risk / Option B — Rewrite history)*
**Decided by:**
**Date:**
**Rationale:**

*Example of a completed record:*

> **Decision:** Option A — Accept, no history rewrite.
> **Decided by:** Siddharth Tiwari
> **Date:** 2026-08-04
> **Rationale:** Repository is private; access limited to the core development team; no public
> exposure, forks or external clones identified (§3 items 1–5); CI logs reviewed and do not contain
> the affected source comment (§3 item 6). The operational cost of rewriting history across a
> multi-developer team outweighs the residual risk. Current code is sanitised (`74a3d76`) and
> automated PII scanning now covers every source file, so recurrence is guarded rather than trusted.

## 7. Preventive measures already taken

- Hygiene scan is **extension-based, not location-based** (`74a3d76`) — main source, migrations and
  docs are now scanned, not just tests and fixtures. Verified to actually block.
- Trace capture now **validates before writing** and refuses on unmasked PII, rather than relying on
  the capturer reading the file (see [`trace-lifecycle.md`](../trace-lifecycle.md)).
- `TraceCorpusHealthTest` fails the build on unmasked PII in any committed trace.

## 8. Remaining items to close this incident

**Engineering — one task**

- [ ] Re-capture the three `.trace` fixtures from the original PDFs, now that the redactor allowlist
      includes deposit vocabulary. **Use `./scripts/trace-capture.sh`** — see the note below on why
      this is not "capture, then review carefully".
- [ ] Replace the synthetic composite fixture with the regenerated real traces, and point
      `CompositeMultiProductClassificationTest`'s trace half back at asserting
      `FIXED_DEPOSIT`/`RECURRING_DEPOSIT` rather than "not accounts".
- [ ] Delete `buildCompositeMultiProductStatementSample` once the real traces cover it.

> **On reviewing regenerated traces.** The natural instruction here is "carefully review each trace
> before committing to ensure no PII is exposed". That was the control in force when this incident
> happened, and it is the reason it happened — a trace is thousands of coordinate lines, nobody
> reads one, and asking people to is how a customer's account number reaches a repository while
> everyone believes it was reviewed.
>
> That control is now automated. `./scripts/trace-capture.sh` **refuses to write** a trace
> containing an unmasked email, phone number or IFSC branch code, and equally refuses one that lost
> the structural evidence it was captured for. It prints a summary with a verdict. Human review is
> approving that verdict — a decision that takes seconds — not scanning the file.
>
> Please point the team at the script rather than at manual review. Re-establishing manual review as
> the primary control would undo the main preventive measure taken here.

**Governance — must be complete before the incident is closed**

- [ ] §3 exposure assessment answered in full, including CI/CD logs, artifacts and backups
- [ ] §4 option chosen
- [ ] §5 pre-rewrite confirmations obtained (Option B only)
- [ ] §6 decision and rationale recorded

**Standing constraint.** No history rewrite or force-push to `main` may be performed without
Siddharth's explicit approval, given after confirming every developer has pushed their work. This is
a repository-level decision, not an engineering one — a rewrite orphans unpushed commits
irrecoverably, because the remote no longer contains what they were based on.

## 9. What this says about the control that failed

The hygiene hook was scoped by *directory*, which encodes an assumption that customer data only
arrives through fixtures. It doesn't. It arrives wherever someone is documenting what a real
document looked like — which is disproportionately main source, because that is where the
explanatory comments live.

The wider lesson, and the reason the redaction workflow was rebuilt alongside this: **"the author
will check before committing" is not a control.** It was the stated control in `PdfTraceRedactor`'s
own doc comment ("the person capturing the trace is the last reviewer standing between a customer's
statement and the repository") and it failed here in both directions — PII got in, and separately,
evidence got silently stripped out of three traces without anyone noticing. Both are now automated
checks.
