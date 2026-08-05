# Repository Guardian

**Status:** Initiative — Phase 1 §3.1 shipped, §3.2 next; Phase 2 scoped and triaged
**Goal:** one authoritative, repository-wide validation layer for structure, architecture,
engineering standards and hygiene — built by orchestrating and extending what exists, never by
replacing it.

Not another linter. An engineering safety net that protects Finora's architecture and long-term
maintainability as the codebase and team grow.

**The enforced rules are listed in
[docs/architecture/repository-guardian-rules.md](../architecture/repository-guardian-rules.md).**
That registry is generated from the code and verified against it on every build — this document is
the initiative and its reasoning; that one is the current, guaranteed-accurate list.

---

## 0. Roadmap

The end state is a single architectural governance layer covering the **whole** repository, not
the backend. Backend went first because ArchUnit already existed there and the standards were
already written down, not because it is the only place that needs this.

| Phase | Scope | State |
|---|---|---|
| **1** | Backend architecture — ArchUnit structural rules (§3.1); `check-imports.py` earns its CI gate (§3.2) | §3.1 ✅ shipped · §3.2 next |
| **2** | Repository-wide deterministic validation (§4.1) — frontend, admin portal, mobile, docs, infrastructure, cross-module | Scoped and triaged |
| **3** | Frontend / admin / mobile structure — folder shape, feature boundaries, shared-component placement, API-layer organisation, screen and navigation organisation | Not started |
| **4** | Documentation, infrastructure, scripts, CI/CD, repository hygiene | Not started |
| **5** | Auto-fix for safe structural issues · drift over time · dependency visualisation · metrics and trends | Deferred until the foundation is trusted |

Phases 3 and 4 are called out separately from Phase 2 because they need a **mechanism that does
not exist yet**. ArchUnit is a JVM tool; it cannot see `frontend/`, `mobile/` or `docs/`. The three
TypeScript apps have ESLint, which can express import-boundary and folder rules through
`eslint-plugin-boundaries` or `no-restricted-imports`, and that is the natural place for their
half — extending what exists, per §2. Documentation and infrastructure rules have no natural home
and will need a script in `scripts/`, following the tiered BLOCK/WARN precedent in §2.1.

Sequencing principle for every phase: **the rule set is only as valuable as its trustworthiness.**
One reliable rule beats ten noisy ones, and the fastest way to kill the initiative is a phase that
ships rules people learn to skip.

---

## 1. What already exists

Established by a repository-wide search before any of this was proposed. **Nothing named
"Guardian" or equivalent exists**, but the foundations are further along than expected.

| Piece | Where | Enforces | Wired in? |
|---|---|---|---|
| **ArchUnit** (10 classes, 39 rules) | `backend/src/test/java/com/finora/architecture/` | Behaviour and security — admin `@PreAuthorize`, audit actor attribution, filter path parsing, no `Optional` beans, scoped identity lookups, `@Valid` request bodies — **plus structure, as of §3.1 below** | Yes, `mvn test` |
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

### 3.1 ArchUnit — structural rules ✅ **Done**

23 rules across four new classes, all green against `main`, each citing the `CODING_STANDARDS.md`
section it enforces:

| Class | Rules | Enforces |
|---|---|---|
| `LayerDependencyDirectionTest` | 8 | Controllers never return an entity (at any generic depth) · no direct repository access · no `@Transactional` on the web layer · entities depend on nothing above them · DTOs carry data and do not fetch it · repositories do not call back up · controllers do not call controllers |
| `FeatureModuleBoundaryTest` | 4 | Migrated features do not depend on the legacy `controller` package · `com.finora.imports` stays acyclic · only `imports.storage` names a concrete storage provider · no production dependency on a test fixture |
| `StereotypeNamingConventionTest` | 5 | `*Controller` ↔ `@RestController` (both directions) · `*Service` is a Spring bean · `*Repository` is a Spring Data interface · `*Config` is `@Configuration` |
| `ProductionCodeHygieneTest` | 6 | `java.time` over `java.util.Date` · constructor injection only · no mutable global state · no `System.out`/`System.err` · no `printStackTrace()` |

**Direction, not placement — because the tree is mid-migration.** The obvious first rule, "a
controller lives in its feature package", would have failed 43 times on day one: 43 of 48
controllers are still in `com.finora.controller`, which is exactly what `CODING_STANDARDS.md`
means by "existing code moves toward it incrementally rather than in one pass". Shipping that rule
red would have made the whole suite skippable. Dependency *direction* is already true nearly
everywhere, is what the migration is trying to preserve, and holds regardless of which package a
class lands in. Package placement is deferred until the migration is far enough along to land
green.

Two rules were measured as red and handled rather than dropped:

- **4 controllers reach into a repository** (`AdminController`, `AdminBankController`,
  `CategoryController`, `UserController`). Frozen in an explicit accept-list.
- **`JwtService` uses `java.util.Date`**, because JJWT's `issuedAt(Date)` / `Claims::getExpiration`
  give it no choice. Frozen the same way.

Both lists follow the `check-dependency-advisories.py` template named in §3.2, for the property
that matters most: **they fail on a stale entry too**, so paying down the debt tightens the rule
automatically instead of waiting for someone to remember. Each list gets its own test method —
as two assertions in one method, a new violation fails first and the stale check never runs.

**Every rule was verified to fail.** A temporary violating class was added to `main`'s sources,
the suite run, and each rule confirmed red before the class was deleted. This was not ceremony:
`noClasses().should().callMethod(Throwable.class, "printStackTrace")` — the obvious spelling — is
**silently vacuous**, because javac emits the call against the receiver's static type, so
`catch (RuntimeException e) { e.printStackTrace(); }` is owned by `RuntimeException` and never
matches. It shipped green, caught nothing, and was only found by trying to break it. It now matches
on method name instead.

**Prefer ArchUnit whenever it can express a rule cleanly.** It runs in the existing suite, fails
with a precise message, and needs no new mechanism.

Sequencing note: introduce rules against the tree as it is. A rule that fails on existing code
either gets the code fixed in the same change or is not ready — a permanently-failing rule teaches
people to ignore the suite. **And a rule that has never been observed failing is not yet known to
be a rule.**

#### The registry, and why it cannot drift

Every rule carries a permanent `FG-NNN` id and lifecycle metadata — intent, source, introduction
date, owning area, verification level, accepted exceptions — as a `@GuardianRule` annotation on the
rule itself. `GuardianRegistryTest` derives
[the published registry](../architecture/repository-guardian-rules.md) from those annotations and
fails the build when the document and the code disagree **in either direction**: a rule added
without a row, a row left behind by a retired rule, a reworded intent, a wrong count.

A hand-maintained list of architecture rules is worth exactly as much as the last person's
discipline in updating it, which over a few years is nothing. The registry publishes "the rules
advertised here are the rules enforced here" as a build-breaking guarantee rather than an
intention. It also enforces the taxonomy — every `@Test` in the package is either a
`@GuardianRule` or a `@GuardianSelfTest(rule = "FG-NNN")` — which is what makes the rule count a
fact rather than a claim, and stops a new rule being added without an id.

Read the report with:

```bash
python3 scripts/guardian-report.py
```

It joins the registry against the last run's surefire results and prints pass/fail per category. It
reports a **rule pass rate**, not a repository-health percentage, for the reason in §4.3 — the
denominator is the rules that exist, which is honest, and adding a rule that finds real problems
then reads as new information rather than as the repository getting worse.

#### Open: 23 rules are verified by hand, not by self-test

The six older rules each ship two self-tests — one proving the rule fires against a broken fixture,
one proving its subject set is non-empty. The 23 added on 2026-08-05 do not; they were falsified
once, by hand, which says they worked the day they were written and nothing more. The
`verification` field records this honestly rather than hiding it, and `GuardianRegistryTest` fails
any rule that overstates its level.

Worth closing, and cheap for most of them: the subject-set half needs no fixture at all, just an
assertion that the rule matches something. Not folded into this change because 23 fixtures is a
larger piece of work than the rules themselves, and shipping it as unmeasured scope creep is the
opposite of the discipline the rest of this section argues for.

#### Not enforced yet, and why

- **Package placement.** 43 controllers away from green. Revisit when the migration lands.
- **Top-level cycles.** `slices().matching("com.finora.(*)..")` reports 100 violations — `dto` ↔
  `entity` and friends are inherent to the layer-based half. Scoped to `com.finora.imports` for
  now, which is acyclic and is the module others copy.
- **Service size / naming judgement.** `CODING_STANDARDS.md`'s "~400 lines is worth a second look,
  not an automatic split" and "name a collaborator for what it does, not `-Service`" are review
  matters, not mechanical ones.

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
