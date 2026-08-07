# Testing & Quality Tooling Review

Evaluation of the 15 proposed tools against what Finora actually has today, at `7dfd997`.

**Recommendation in one line: adopt 2 now, revisit 3 later, decline 10 — and the single highest-value
action on the list is not installing anything, it is wiring the Playwright suite we already own into
CI.**

---

## First, what already exists

The proposal reads as though we are starting from a bare repository. We are not, and four of the
fifteen items are already running:

| Proposed tool | Actual status |
|---|---|
| **1. Playwright** | **Installed and written.** `e2e/` has 9 spec files across user-portal, admin-portal and cross-app workflow — including authenticated journeys, duplicate review, merchant lifecycle and learning-queue — plus 8 configured projects (Chromium, Firefox, Edge, tablet, mobile viewports) with its own dev-server orchestration. **Not wired into CI** — so none of it runs automatically. |
| **2. JUnit** | In use. Well over 1200 tests across 160+ classes, including 30 Testcontainers `*IT` integration classes. Runs in CI with a published unit/IT breakdown on the run page. (Counts move daily; the run page is the live number.) |
| **3. Vitest** | In use. Frontend (160 tests) and admin portal, both gated in CI. Mobile uses Jest (256 tests) — same role, different runner, because Expo ships jest-expo. |
| **12. JaCoCo** | Installed (0.8.12), runs on every `mvn test`. **Deliberately a report, not a gate** — see below. |

Beyond the list, we already run a security and structure layer that overlaps several proposals:

- **ArchUnit** structural rules, each with a self-test proving it can fail, now being formalised into
  a Guardian registry with IDs and ownership.
- **Seven custom static checks** in CI and pre-commit: customer-PII hygiene, client auth-policy
  drift, import cross-reference, XML comment validity, executable bits, contact addresses, and
  dependency advisories.
- **`check-dependency-advisories.py`**, which runs `npm audit --omit=dev` across all three JS apps
  and fails unless every advisory is either fixed or explicitly allowlisted with a reason.

That last one matters for judging items 5 and 6, and it covers **npm only — not Maven**.

---

## The principle this review applies

Finora's recurring quality failure is not too few tools. It is **tools whose output nobody reads.**
This is documented, repeatedly, in our own history:

- `check-xml-comments.py` existed for months, was wired to nothing, and always exited 0 — the exact
  shape that let the original `pom.xml` incident happen.
- `backend/mvnw` was committed non-executable, so **the backend test suite never ran in CI at all**
  from the workflow's creation until 2026-08-05. Three green jobs beside one red one read as flaky.
- 29 `*IT` classes never executed for months because surefire's includes did not match `*IT.java`.
  The suite was green at 941 tests the whole time, with `com.finora.controller` at 0% coverage.
- `npm audit` reports 18 advisories, all already judged not to apply. A command whose output is
  always non-empty is one people learn to skip — and a genuine open redirect sat in that noise.

Every one of those is a **signal-to-noise failure, not a coverage failure.** Adding thirteen tools
that each emit findings would reproduce it at scale. So the bar applied below is: *does this tool
produce a signal someone will act on, that we do not already get?*

---

## Summary table

| # | Tool | Recommended | Phase | Install effort | Maintenance | Value to Finora |
|---|---|---|---|---|---|---|
| 1 | Playwright | **Already owned — wire to CI** | **Now** | S (CI job only) | Low–Med | **High** |
| 2 | JUnit | Yes — in use | — | — | Low | High |
| 3 | Vitest | Yes — in use | — | — | Low | High |
| 4 | OWASP ZAP | Later | 3 | L | High | Medium |
| 5 | Dependabot | **Yes** | **Now** | **S** | **Very low** | **High (Java gap)** |
| 6 | Snyk | No | — | M | Med | Low (overlap) |
| 7 | SpotBugs | Later | 2 | M | Med | Medium |
| 8 | PMD | No | — | M | High | Low |
| 9 | Checkstyle | No | — | M | High | Low |
| 10 | Trivy | Later | 2 | S–M | Low | Medium |
| 11 | Grafana k6 | No (not yet) | — | L | Med | Low *today* |
| 12 | JaCoCo | Yes — in use, keep ungated | — | — | Low | Medium |
| 13 | SonarQube/Cloud | No | — | L | High | Low–Med (overlap + cost) |
| 14 | OpenRewrite | No — use as a tool, do not adopt | — | S per use | None | Medium, episodic |
| 15 | Semgrep | Later | 2 | M | Med | Medium–High |

---

## Adopt now

### 5. Dependabot — **yes, and it closes a real gap**

**Why.** Our dependency scanning covers npm and *not Maven*. `check-dependency-advisories.py` runs
`npm audit` over `frontend`, `admin-portal` and `mobile`. Nothing watches Spring Boot, Jackson,
PostgreSQL driver, Firebase Admin, or any other Java dependency for CVEs — on a financial backend
handling bank statements. That is the largest genuine hole on this list.

**Where.** `.github/dependabot.yml`, covering four ecosystems: `maven` (backend), `npm` × 4
(frontend, admin-portal, mobile, e2e), and `github-actions` — the last of which would have flagged
the `actions/setup-java@v4` deprecation currently warning on every run.

**Overlap.** Partial with the npm script, and the two are complementary rather than redundant: the
script *gates the build* on shipped-code advisories with a maintained allowlist; Dependabot *opens
PRs* to fix them. Neither replaces the other.

**Risks.** PR volume. Mitigated by grouping updates and limiting open PRs — configured below.
Security updates are separate from version bumps, so the noisy half can be tuned without muting the
half that matters.

**Effort.** ~30 minutes. **Maintenance:** near zero; it is a config file.

### 1. Playwright — **already owned; the gap is CI, not installation**

**Why this is the top item.** We have 9 spec files, 8 browser/viewport projects, authenticated
cross-app workflow tests, and dev-server orchestration — and **none of it runs unless someone
remembers to run it locally.** That is precisely the `check-xml-comments.py` shape: real work, zero
enforcement. The suite is also still growing (two admin-portal specs were added while this review
was being written), which makes the gap widen rather than close: writing more E2E tests before
wiring the existing ones in compounds the problem.

**Where.** A fifth CI job, after the frontend/admin-portal jobs succeed. Chromium-only on every
push (~2–3 min); the Firefox/Edge/responsive projects nightly or on a label, because cross-browser
runs are where E2E cost and flake concentrate.

**Not done in this pass, deliberately.** Another engineer is mid-change in `e2e/` right now —
`playwright.config.ts` and `package.json` are modified and `e2e/tests/workflow/` is brand-new and
untracked. Wiring CI against a config that is actively moving would break their work and mine.
**This needs a five-minute handoff with whoever owns that branch, then it is a small job.**

**Risks.** Flake is the real cost of E2E, and it is what makes teams disable the suite. Mitigate by
starting Chromium-only, requiring deterministic fixtures, and treating a flaky spec as a broken
test rather than a retry candidate.

**Effort.** ~2 hours once `e2e/` settles. **Maintenance:** medium and ongoing — this is the honest
cost of E2E, and it is worth paying for auth, import, and duplicate-review journeys.

---

## Revisit next (phase 2), in priority order

### 15. Semgrep — best of the remaining security tools

Catches what our guards do not: hardcoded secrets, unsafe API usage, injection patterns, OWASP Top
10 shapes. Genuinely complementary to ArchUnit (structure) and our PII checks (data hygiene). The
reason it is not "now": it needs a tuned ruleset and a triage pass before it can gate, or it becomes
the next `npm audit`. Adopt with `--severity ERROR` only, as a gate from day one, with any
exclusion carrying a written reason — the same discipline as `check-dependency-advisories.py`.

**Effort** ~4h including triage. **Maintenance** medium.

### 10. Trivy — worth it for the container, not for the dependencies

`backend/Dockerfile` exists and the backend deploys as a container on Railway. Trivy's unique value
here is **base-image OS CVEs**, which nothing else covers. Its dependency scanning would overlap
Dependabot. Scope it to image scanning only, in the deploy path.

**Effort** ~2h. **Maintenance** low.

### 7. SpotBugs — real value, real noise; needs a baseline

Finds null-dereference and concurrency mistakes that our tests and ArchUnit rules do not. The
catch on an existing 1200-test codebase is the first run: expect a large findings list, most of it
low-value. Only worth adopting **with a baseline file** that accepts today's findings and gates on
new ones. Without that it will be muted within a week.

**Effort** ~4h with baselining. **Maintenance** medium.

---

## Decline, with reasons

### 4. OWASP ZAP — right idea, wrong sequence

DAST is valuable, but it needs a running app with authenticated sessions, which means it depends on
the same stack orchestration Playwright already has. Doing ZAP *before* Playwright is in CI means
building that infrastructure twice. Revisit once the E2E job exists and can be reused. Also worth
noting our threat surface is already covered in part: `AdminEndpointAuthorizationTest` fails the
build on an unguarded admin endpoint, which is the access-control class ZAP would report.

### 6. Snyk — declines on overlap, not quality

Overlaps Dependabot (dependencies) and Semgrep (SAST) almost entirely. Its differentiators —
reachability analysis, licence compliance, container scanning — are either covered by Trivy or not
problems we have. Adding a third scanner for the same findings is how alert fatigue starts. Revisit
only if Dependabot proves insufficient in practice.

### 8. PMD and 9. Checkstyle — high noise, low signal here

These enforce *style and maintainability heuristics*, and we have no agreed style document to
enforce. Retrofitting either onto an existing codebase produces thousands of findings that must be
either bulk-suppressed (making the tool meaningless) or bulk-fixed (a large diff with no behaviour
change and real review cost). Neither has caught a class of bug we have actually hit. `CODING_STANDARDS.md`
plus review is currently doing this job adequately. Revisit if the team grows past the point where
review can carry it.

### 11. Grafana k6 — no evidence yet, and we have a policy about that

Load testing without a measured performance problem or a scaling trigger is speculative
infrastructure, and our own standing rule is to measure before optimising. We have
`docs/engineering/scaling-triggers.md` recording the thresholds that *would* justify this work.
**The right sequence is: instrument production, watch for a trigger, then load-test the specific
thing the trigger names.** k6 is the correct tool when that day comes — the objection is to the
timing, not the choice.

### 13. SonarQube / SonarCloud — cost and overlap

SonarCloud is free only for public repositories; ours is private, so this is a paid line item. What
it provides — code smells, bugs, vulnerabilities, duplication, coverage — is largely the union of
SpotBugs + Semgrep + JaCoCo, which we can adopt individually and tune per-tool. Self-hosting the
Community Edition adds a service to operate. The quality-gate concept is genuinely good; the
packaging is not worth it at our size.

### 14. OpenRewrite — a power tool, not a pipeline member

OpenRewrite is not something you *adopt*; it is something you *run* when doing a migration. It has
no continuous role, so it does not belong in CI. When we upgrade Spring Boot past 3.3.2 or move Java
versions, running the relevant recipe is the right call and takes an afternoon. Nothing to install
today.

### 12. JaCoCo — keep it, keep it ungated

Already installed and producing reports. The proposal implies adding coverage enforcement; **I
recommend against it**, and the reasoning is already recorded in `backend/pom.xml`: the rate-limit
bypass we shipped had five dedicated tests that all passed. Coverage measures whether a line
executed, not whether it was verified. A percentage gate converts a useful diagnostic into a target,
and the cheapest way to hit a target is low-value tests. Its current job — *finding blind spots*, as
it did when it exposed `com.finora.controller` at 0% — is the job worth keeping.

---

## Proposed roadmap

Adjusted from the suggested phasing, mainly to put the free win first and to defer anything
requiring a running app until the E2E job exists.

**Phase 1 — close the real gaps (this week)**
1. Dependabot for Maven, npm ×4, GitHub Actions. *Done in this change.*
2. Wire the existing Playwright suite into CI, Chromium-only. *Blocked on `e2e/` settling.*

**Phase 2 — security depth (next)**
3. Semgrep, gating on ERROR only.
4. Trivy, image scanning only.
5. SpotBugs, with an accepted baseline.

**Phase 3 — dynamic security (after Phase 1.2)**
6. OWASP ZAP, reusing the Playwright stack orchestration.

**Phase 4 — on evidence, not on schedule**
7. k6, when a scaling trigger fires.

**Not scheduled:** Snyk, PMD, Checkstyle, SonarQube. OpenRewrite as a one-off when a migration
needs it.

---

## The trade-off worth stating plainly

Adopting all fifteen would give Finora more findings and less signal. We have concrete, documented
evidence that this team's failure mode is guards nobody reads — a suite that never ran for weeks, a
script that always exited 0, an audit stream trained to be ignored. Every tool added to CI spends
some of a finite budget: the team's willingness to believe a red build.

The two recommended now are chosen because they spend almost none of it. Dependabot opens PRs
rather than failing builds, and Playwright's tests are already written and already trusted — they
simply are not running.
