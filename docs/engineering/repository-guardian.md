# Repository Guardian

**Status:** Initiative — Phase 1 approved, Phase 2 scoped and triaged
**Goal:** one authoritative, repository-wide validation layer for structure, architecture,
engineering standards and hygiene — built by orchestrating and extending what exists, never by
replacing it.

Not another linter. An engineering safety net that protects Finora's architecture and long-term
maintainability as the codebase and team grow.

---

## 1. What already exists

Established by a repository-wide search before any of this was proposed. **Nothing named
"Guardian" or equivalent exists**, but the foundations are further along than expected.

| Piece | Where | Enforces | Wired in? |
|---|---|---|---|
| **ArchUnit** (6 rules) | `backend/src/test/java/com/finora/architecture/` | Behaviour and security only — admin `@PreAuthorize`, audit actor attribution, filter path parsing, no `Optional` beans, scoped identity lookups, `@Valid` request bodies | Yes, `mvn test` |
| `check-imports.py` | `scripts/` | Static cross-reference — a type used without a valid import. Written for the v56 module migration, for the "same-package-before-move, broken-after-move" bug | **No** — see §3.2 |
| `check-fixture-hygiene.sh` | `scripts/` | Real customer data entering the repo. **Tiered BLOCK/WARN** | Yes, pre-commit |
| `check-client-auth-policy.py` | `scripts/` | The three API clients agree on unauthenticated endpoints | Yes, pre-commit |
| `check-contact-addresses.py`, `check-executable-bits.py`, `check-xml-comments.py`, `check-dependency-advisories.py` | `scripts/` | Content and policy checks | Yes, hooks + CI |
| `CODING_STANDARDS.md` | `docs/engineering/` | Feature-based backend packages, feature-first frontend folders | **No — documented, unenforced** |
| `structure-audit-findings.md` | `docs/engineering/` | A manual structural audit of `main`, done once | N/A — human pass |

**Two facts shape everything below.**

**ArchUnit is present and structurally unused.** `archunit-junit5` is already a dependency and the
package convention is established, but all six rules are behavioural. Placement, layering, cycles
and dependency direction are exactly what ArchUnit is best at, and none of it is written yet.

**The rules already exist in prose.** `CODING_STANDARDS.md` defines the target package shape.
`structure-audit-findings.md` is effectively a hand-run specification of what to automate. The
Guardian's job is enforcement, not authorship — it must reference those documents, never restate
them into a competing definition.

## 2. Principles

- Do not duplicate existing capability. Extend first.
- Prefer deterministic rules over heuristics.
- Keep false positives extremely low.
- Rules configurable where necessary.
- Educate developers; do not become an obstacle.
- Reliability over number of checks.

### 2.1 The precedent that makes "low false positives" concrete

`check-fixture-hygiene.sh` already solved this problem in this repo, and its reasoning is the model:

> TIERED, because precision differs enormously between these heuristics and a blanket block on a
> noisy one just trains people to reach for `--no-verify`.
>
> **BLOCK** — patterns specific enough that a hit is almost certainly real. **WARN** — long digit
> sequences, because real account numbers look exactly like timestamps, ids and coordinates.

It also shows the right escape hatch: not `--no-verify`, which disables every check in the repo for
that commit, but an inline `synthetic-ok` marker that stays visible in the diff and reviewable.

**Every Guardian rule adopts this.** A rule is BLOCK only if a hit is almost certainly real.
Everything else WARNs, with a documented, in-diff escape hatch.

## 3. Phase 1 — extend what exists

### 3.1 ArchUnit — structural rules

Add placement and boundary rules alongside the existing behavioural ones, each referencing the
`CODING_STANDARDS.md` section it enforces. ArchUnit handles all of these natively and
deterministically:

- Package placement (`*Controller` in its feature package, etc.)
- Layer boundaries — controller → service → repository, and never the reverse
- Module and feature boundaries; no cross-feature reach-in
- Dependency direction
- Forbidden imports
- Package naming conventions
- Circular dependencies (`SlicesRuleDefinition`)

**Prefer ArchUnit whenever it can express a rule cleanly.** It runs in the existing suite, fails
with a precise message, and needs no new mechanism.

Sequencing note: introduce rules against the tree as it is. A rule that fails on existing code
either gets the code fixed in the same change or is not ready — a permanently-failing rule teaches
people to ignore the suite.

### 3.2 `check-imports.py` — earn the CI gate

Closest existing thing to structural validation, and deliberately **not** wired in. From
`repository-audit-findings.md` §6:

> It has three documented, hand-verified false positives (FP-01/02/03) still active. Making it
> `exit(1)` unconditionally would fail every future clean run.
>
> **Recommended:** give it an `ACCEPTED_FALSE_POSITIVES` list of (file, type, package) tuples — the
> same shape `check-dependency-advisories.py` already uses.

Before promotion: resolve or accept the known false positives, add the accept-list, make output
deterministic and developer-friendly. **Only then** make it a gate. `check-dependency-advisories.py`
is the working template, including the part that matters most — it also fails on a *stale* accept
entry, so the list cannot quietly rot.

### 3.3 Standards stay in one place

`CODING_STANDARDS.md` is the single source of truth. A Guardian rule cites the standard it enforces;
it does not define one. Where a rule and the document disagree, the document wins until deliberately
changed.

## 4. Phase 2 — triaged, not accepted wholesale

The Phase 2 list is the right destination. But several items are heuristic, and shipping them as
gates would violate §2 directly. Triaged by what the rule can actually know:

### 4.1 Deterministic — safe to build and gate

| Area | Checks |
|---|---|
| **Backend** | Package placement · layer violations · module and feature boundaries · circular dependencies · naming conventions · configuration placement · migration organisation (Flyway numbering, no gaps or duplicates) · security architectural rules · `@Transactional` placement |
| **Frontend / Admin / Mobile** | Folder structure · feature boundaries · shared-component placement · API-layer organisation · hooks, context, utility, type, asset and route placement · screen and navigation organisation (mobile) |
| **Documentation** | Doc lives in the right folder for its kind (ADR / RFC / architecture / deployment / engineering) · no duplicate filenames across doc folders |
| **Infrastructure** | Presence and placement of Docker, CI workflow, scripts, env templates |
| **Hygiene** | Merge and cherry-pick leftovers (`.orig`/`.rej`/conflict markers) · build artefacts · IDE and OS files · large binaries (size threshold) |
| **Cross-module** | Cross-app imports · relative imports escaping a module · cross-feature contamination |

These are all facts about the tree. A rule either matches or it does not.

### 4.2 Heuristic — WARN only, or not yet

| Item | Why it cannot gate |
|---|---|
| **Dead code** | A Spring app wires beans by reflection, Flyway loads SQL by filename, Jackson binds DTOs it never sees referenced. Static reachability is wrong here far too often to block on. |
| **Duplicate implementations** | Requires a similarity judgement. Two `format(BigDecimal)` helpers may be a genuine duplicate or a deliberate module boundary. |
| **Debug code** | `console.log` is detectable; a debug branch behind a flag is not. |
| **Accidentally committed secrets** | Partly covered already by `check-fixture-hygiene.sh`. Extending it beats a second scanner — but entropy-based detection is exactly the noisy tier that needs WARN. |
| **Orphaned files** | "Referenced by nothing" and "entry point" are indistinguishable without a manifest of intended entry points. |

**Recommendation:** build §4.1 first and completely. Add §4.2 only as WARN, and only once the
deterministic set has proven its signal.

### 4.3 The health score needs care

A single `Repository Health: 97%` is the one part of the proposal this codebase has already argued
against. From `CapabilityCoverageService`'s own class doc:

> the sequencing this document insists on is collect, store, VALIDATE, then dashboard, then decide
> … a dashboard on unvalidated metrics looks authoritative and is not, which is worse than no
> dashboard

It follows that rule deliberately, producing "numbers and nothing else -- no scoring, no thresholds,
no auto-review decisions", and `financial-document-intelligence-principles.md` gates `Confidence` as
a live metric behind the same reasoning. A percentage invites the same failure: it implies a denominator nobody
agreed, moves when rules are added rather than when the repo changes, and turns "add a rule" into
"lower the score", which is a disincentive to add rules.

**Recommendation:** keep the per-area ✓/⚠ report, which is genuinely useful and is what a developer
acts on. Drop the single percentage, or defer it until the rule set is stable enough for the
denominator to mean something.

## 5. Automation

| Where | What runs |
|---|---|
| Local, on demand | Everything |
| Pre-commit | Fast, deterministic checks only |
| CI | Everything |
| Pre-release | Everything, plus WARN tier reviewed |

The pre-commit boundary matters. `.husky/pre-commit` already runs the backend suite and four
scripts; anything slow added there gets bypassed, and a bypassed hook protects nothing.

## 6. Future — after the foundation is reliable

Automatic fixes for safe structural issues · architectural drift over time · dependency
visualisation · repository metrics and trends · duplicate-code detection · dead-code analysis ·
build-performance analysis · dashboards.

Valuable, and all secondary to a validation layer people trust.

---

**End goal.** Not another linting tool — an engineering safety net that continuously protects
Finora's architecture, structure, standards and maintainability, built on what already exists.
