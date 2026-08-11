# Incident: real statement data in test fixtures, PDF builders and documentation

**Date:** 2026-08-08 · **Severity:** high (customer financial data in a tracked repository)
**Exposure:** private repository; no evidence of external access. Not a breach; a control failure.
**Related:** [2026-08-03-customer-pii-in-git-history.md](2026-08-03-customer-pii-in-git-history.md),
[security-control-audit.md](../../security/security-control-audit.md),
[ADR-004](../../architecture/adr/adr-004-document-pipeline-scope.md)

**No value from this incident is reproduced in this document.** Categories and locations only. That
is not squeamishness — see §4, where explaining the leak was itself one of the ways it recurred.

---

## Root cause

Real customer financial-document content was copied into repository test fixtures, PDF builders and
documentation because the development workflow lacked a mandatory synthetic-data and provenance
boundary. Existing hygiene controls were primarily pattern- and diff-based, were not consistently
enforced at commit time, and could not reliably detect contextual data such as names, locations,
short identifier fragments, or separator-formatted identifiers. Consequently repository content could
contain real data while every automated check stayed green.

**Contributing factors:** no whole-tree scanning; no commit-by-commit inspection; no pre-commit
enforcement actually installed; warning-only handling of long digit sequences; no separator-aware
matching; duplicate fixture occurrences across four codebases; and reliance on developer judgement
when creating realistic financial fixtures.

## 1. The mechanism

```
real bank statement → developer needs a realistic case → copies the narration verbatim
                    → Java fixture / PDF builder / test / doc → committed
```

Nobody tried to leak anything. The workflow simply permitted real data to become test data, and
offered no easier path to a realistic fixture. Provenance was established for one fixture narration
by finding the **identical string in a real statement**, including a real phone number as its
suffix — verbatim transcription, not coincidence.

## 2. Pattern detection is not provenance detection

This single distinction explains almost everything that followed. The controls asked *"does this
newly changed line look like an account number?"* They never asked *"did this value come from a real
customer statement?"*

| Class | Why patterns could not see it |
|---|---|
| Separator-formatted card and account numbers | Every digit rule matched contiguous runs, on **both** sides of the comparison |
| Personal names | Match no pattern at any threshold |
| Branch and place names | Indistinguishable from ordinary words |
| Short fragments | A five-digit tail of a real phone number cannot be told from a legitimate quantity — and the threshold cannot be lowered: at five characters, prefixes of real identifiers collide with dates, amounts and row counts, turning 31 real findings into 619 lines of noise |
| Merchant identity | Meaningful only alongside the surrounding narration |

## 3. The scanner was not running at the right points

Three independent enforcement failures, each of which the other two would have covered:

- **No pre-commit hook existed.** The script described itself as "the pre-commit hook" for months
  while nothing installed it. There was no `.githooks`, no `core.hooksPath`, no install step.
- **CI scanned the net diff.** A value added in one commit and removed in a later one was invisible
  to `BASE..HEAD` — and add-then-remove is the shape of every leak noticed late.
- **Long digit sequences only warned.** A 10+ digit run printed a warning and exited **0**. IFSC
  codes and email addresses blocked; account numbers did not.

## 4. Documentation is repository content

The most instructive part of the incident, because it recurred **four times during the cleanup
itself** — each time under the reasoning *"I am only mentioning the value to explain the problem."*
A design note, a scanner's own docstring, the audit document, and the scanner's self-test fixtures.
The last one was caught by the new scanner flagging its own source file on its first real run.

The repository does not distinguish fixture from Javadoc from design note. **Explaining a leak does
not license repeating it, and the guard grants no exemption for prose about the guard.**

## 5. Two blind spots in verification

**Duplicates.** Sanitizing the occurrence that was reported left copies elsewhere — five separate
times. Every value eventually turned up in two to four files, across backend, frontend, mobile, e2e
and documentation. The rule that follows: **search the entire tracked repository, never the file
currently open.**

**Green tests prove less than they appear to.** At one point the suite was 1745/1745 with all
affected classes at their per-class baselines, and nine real identifiers were still present. Worse in
the other direction: fixture edits broke consumers through three distinct mechanisms — tests locating
rows by description *substring*, a test locating a row by a *fragment* of a name it never mentioned,
and the whole-line rescan that exposed pre-existing values once their line was touched. Only the full
suite saw all three. So replacements preserved character length, separators, embedded commas,
narration grammar and PDF column positions, because a shorter or restructured value can leave a test
green while it silently exercises a different document.

## 6. Corrective action: five permanent controls

No single control is the authority.

1. **Real corpus never enters Git.** Never committed, never copied by hand, never used directly as a
   fixture. Enforced by refusal in `corpus-run.py`, `check-corpus-leakage.py` and `trace-capture.sh`.
2. **Synthetic fixture generation** — the largest outstanding item. Generate fixtures that preserve
   field lengths, separators, column geometry, narration patterns and edge cases *without* retaining
   customer content, so the easiest path to a realistic fixture stops being "copy a real one."
3. **Provenance on every fixture**, and the rule that makes it work: `CONFIRMED_SYNTHETIC` allowed,
   `CONFIRMED_REAL` blocked, `UNKNOWN_PROVENANCE` reviewed, `APPROVED_INCIDENT_RECORD` an explicit
   exception. **Unknown is not synthetic.**
4. **Independent gates, each answering a different question.** Pattern scan: *does this look
   suspicious?* Corpus scan: *did this come from our statements?* Full suite: *did sanitization
   change behaviour?* Human review: *is there contextual information automation cannot classify?*
5. **Provenance as the release gate**, not scanner exit status: *no confirmed real customer-derived
   data may exist in repository content.*

## 7. Status

Implemented: pre-commit hook with an install path; commit-by-commit scanning; whole-tree scanning
gated on a ratchet that can only fall; blocking instead of warning on long digit runs;
separator-aware matching normalised on both the repository and corpus sides; and a corpus-provenance
comparison that is the only check with ground truth — and therefore the only one that cannot run in
CI, since the corpus lives outside the tree by policy.

Outstanding: the synthetic fixture generator, and fixture provenance classification.

## 8. The lesson, stated so it survives the details

This was not a regex problem. The regex problems were symptoms.

> Finora had no enforced boundary between real financial evidence and synthetic development data.

Fix only the scanner and someone eventually finds a way around it. Fix the data-generation and
provenance workflow and the scanner becomes the backup rather than the primary defence.
