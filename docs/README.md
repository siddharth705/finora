# Finora — Documentation Index

This is a map of `docs/`, not a document itself. It describes where things live and why; it does not
summarize, evaluate, or update the content it points to. If a folder's purpose seems to conflict with
something inside it, trust the document — this index can go stale, the documents are the source of
truth.

**Organization only.** This structure groups existing documentation by purpose and lifecycle. No
document's content was rewritten, summarized, or edited as part of organizing it — see the note at
the bottom of this file for what did change (a small number of relative links, adjusted so they still
resolve after files moved).

---

## Where things live

### `product/`
What Finora is and why, independent of any particular release. `philosophy/` holds the founding
product philosophy; `specs/` holds the early technical specifications for the financial-intelligence
engine (evidence engine, rule engine, statement engine) written before the current import-pipeline
work superseded parts of that design — read them as the product's technical starting point, not as
the current architecture. `brand-assets.md` covers logo and identity usage.

### `project-management/`
Where a PM or the project owner would look first.

- **`plans/`** — the living project plan (`project-plan-v1.0.md`) and the original Phase 1 engineering
  roadmap.
- **`milestones/`** — milestone charters and backlogs: Milestone 2 (Import at Scale) and its backlog,
  the Import Reliability and Enterprise-Scale milestone designs, the financial-document-intelligence
  changelog, and the earliest bootstrap-phase deferred-work list.
- **`decisions/`** — background research gathered ahead of a decision, e.g. the privacy-policy
  research done ahead of a professional consultation. Decisions themselves are recorded in the plan
  (`plans/project-plan-v1.0.md`); this folder holds the supporting material.
- **`releases/`** — what shipped and when (`v1.0-import-reliability.md`).
- **`communications/`** — team-facing kickoff and closeout messages, kept as a record of when phases
  started and ended.
- **`standards/`** — coding standards, the API compatibility policy, and the marketing claims
  checklist — governance documents everyone is expected to follow, not proposals.

### `architecture/`
How the system is built, and the record of why.

- **`adr/`** — the numbered Architecture Decision Records (ADR-001 through ADR-006) and the map of how
  ADR-004/005/006 relate to each other.
- **`system-design/`** — design documents for specific subsystems: the financial-document-intelligence
  principles (the largest single document in this tree), the ground-truth model, persistence
  boundary, import execution model and scaling design, layout intelligence, OCR document intelligence,
  the evidence registry, refund ranking, scaling triggers, and the import architecture review.
- **`infrastructure/`** — the self-hosted CI runner.
- **`data/`** — the statement storage migration (Postgres BYTEA → Cloudflare R2).

### `engineering/`
Implementation-level references, organized by subsystem.

- **`import/`** — the import flow, verification framework, idempotency audit, trace lifecycle, and OCR
  engine evaluation.
- **`mobile/`** — mobile architecture and setup/device-validation.
- **`observability.md`** — logging, metrics and monitoring practice; sits at the top level since it
  spans every subsystem rather than belonging to one.

### `investigations/`
Closed investigations, organized by what was investigated rather than by an active/completed split —
every investigation currently on record has reached a conclusion.

- **`incidents/`** — postmortems: PII in git history, real statement data in test fixtures, and the
  stale-chunk login failure.
- **`performance/`** — performance investigations (reconciliation CPU profile, import pipeline
  profile, queue overhead, the reconciliation investigation's closure) and the methodology they
  followed.

### `quality/`
Findings about the state of the codebase.

- **`bug-reports/`** — the repo-wide bug hunt and its two closure reports, the original full bug
  review, and the repository/structure audit findings.
- **`test-reports/`** — the end-to-end test report.
- **`tooling/`** — the engineering tooling roadmap and the testing/quality tooling review.

### `security/`
Security and access-control audits, the IAM implementation notes, and the Repository Guardian tool
(both its rule registry and its own description) — grouped here because its actual purpose is
detecting PII and secret leakage, not general repository hygiene.

### `operations/`
Running the system in production.

- **`deployment/`** — the deployment guide.
- **`runbooks/`** — operational scripts referenced by an engineering process, e.g. the BH-046
  pre-flight read-only check.

### `proposals/`
Proposals not (yet) folded into an ADR or a milestone: the repository-wide improvement proposal, the
engineering controls proposal, and the import engine improvement proposal. A proposal that was fully
implemented and superseded by a system-design document stays here unless and until it's formally
retired — none currently are.

### `archive/`
Documents explicitly marked by their own text as historical record rather than current information.
Today that's one document: the 2026-08-07 full repository review, which opens with its own note that
it is "retained as a record, not as a current worklist" and is stale by at least one commit. Nothing
was moved here on the basis of *this reorganization's* judgment that it looked outdated — only
documents that say so themselves.

---

## Quick answers

- **Project plans** → `project-management/plans/`
- **Architecture decisions (ADRs)** → `architecture/adr/`
- **Investigation reports** → `investigations/` (incidents or performance)
- **Active proposals** → `proposals/`
- **Historical/archived documents** → `archive/`
- **Release and operational documentation** → `project-management/releases/` and `operations/`

---

## What changed when this was organized (2026-08-10)

Every file kept its original content. The only content-level edits were mechanical: relative links
between documents were updated so they still resolve after the files they point to moved — a link
that said `../foo.md` before still points at the same document after the move, wherever its new path
requires. No prose, finding, decision, or number was altered.

Two links could not be corrected because they were already broken before this reorganization: both
`milestone-2-backlog.md` and `milestone-2-import-at-scale.md` (now under `project-management/milestones/`)
link to a repo-root `CLAUDE.md` that does not exist in this repository (only `mobile/CLAUDE.md` does).
Their relative depth was adjusted to preserve the same intended target through the move, but the
underlying reference was not guessed at or redirected, since that would be a content decision rather
than an organizational one.
