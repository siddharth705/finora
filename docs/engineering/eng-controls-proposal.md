# Engineering Controls Proposal — ENG-23, ENG-24

**Status: proposal. Nothing here is implemented, and nothing here should be implemented as part of
a bug-fix change.**

Two items arrived in a bug audit as BUG-23 and BUG-24. They are renumbered here because calling
them bugs was a category error, and the label matters: a bug is something behaving wrongly today,
and neither of these is. They are **absences of control** — classes of problem that would currently
go undetected. The audit itself says so: *"No bug is proven to exist today, only that a class of bug
would not be detected."*

That distinction decides how they get handled. A bug gets fixed in the change that finds it. A
missing control is infrastructure: it needs configuration, a triage pass, and somebody willing to
own its output. Wiring either of these into CI on the same afternoon they were reported is how a
pipeline acquires a step everybody has learned to skip — which this repository has already
diagnosed once, in `.github/workflows/ci.yml`'s own comment about a script nobody ran, and again in
`scripts/check-dependency-advisories.py`'s reasoning about `npm audit` output that is never empty.

---

## ENG-23 — No dependency-advisory scanning for the backend

**Priority: higher of the two. Recommended to do first.**

### What is missing

`scripts/check-dependency-advisories.py` runs for `frontend`, `admin-portal` and `mobile`. The
backend has no equivalent: no OWASP Dependency-Check, no Snyk, no Sonar. The module that handles
authentication, JWT signing and verification, and **PDFBox parsing of files uploaded by users** has
zero automated CVE detection.

### Why this is the higher priority

The asymmetry is the argument. The three JS apps — which render already-authenticated data — are
covered. The backend, which is the only component that parses untrusted binary input from the
public internet, is not. PDFBox in particular has a genuine history of parser CVEs, and a statement
upload is an anonymous-ish, file-shaped attack surface reachable by any registered user.

### Why not just add it now

- **It needs network access to the NVD.** OWASP Dependency-Check downloads and caches a
  vulnerability database. On a cold CI runner that is a slow first run and a real failure mode of
  its own (NVD rate-limiting is common enough to have its own FAQ entry). It wants an API key and a
  cached data directory before it is trustworthy in CI.
- **It needs a triage pass before it can gate.** The first run against any real Spring Boot
  dependency tree returns findings. Until somebody has been through them, a failing build carries
  no information.
- **The good pattern already exists and should be matched.** `check-dependency-advisories.py`'s
  design is the right one: every advisory is *"either allowlisted here, with a reason and a name for
  what would change the answer, or it fails the build. There is no third state."* A backend scanner
  should adopt exactly that, not a bare `mvn dependency-check:check` whose output nobody has
  agreed to act on.

### Proposed shape

1. Add the OWASP Dependency-Check Maven plugin, bound to no phase by default so a normal build is
   unaffected.
2. Run it once locally. Triage every finding into an `ACCEPTED`-style list mirroring the JS
   checker's, each entry carrying a reason that could turn out to be wrong and a named trigger for
   re-checking.
3. Only then add the CI step, with the NVD cache keyed like the Maven cache already is.
4. Fail on anything not in the list.

**Success criterion:** a new backend CVE in shipped code fails a build, and the accepted list never
contains an entry without a reason.

**Prerequisite:** an NVD API key in repository secrets.

---

## ENG-24 — No static analysis on the backend

**Priority: lower. Recommended to defer until after ENG-23 and until the conditions below hold.**

### What is missing

Each JS app runs `npm run lint --max-warnings 0`. The backend has no SpotBugs, Checkstyle, PMD or
Error Prone. The audit notes that several of its own findings — the unlogged catch-all in
`JwtAuthFilter`, the dead unscoped repository method, part of the check-then-act race in
`addAlias` — are categories a standard analyser flags by default.

That observation is fair and worth taking seriously. The 29 ArchUnit rules under
`com.finora.architecture` enforce this repository's *house conventions* — one route tree, scoped
identity lookups, audit-actor attribution — and are excellent at that. They are not, and were never
meant to be, the general defect catalogue.

### Why this should wait

- **The first run produces a large finding set.** That is not a reason never to do it; it is a
  reason not to do it in a change that is about something else. SpotBugs on an established codebase
  needs a baseline (an exclusion file capturing what exists today) so that new findings are
  visible, and producing that baseline honestly means reading them, not generating and committing
  the file unread.
- **Its value depends on somebody owning the output.** A report that nobody triages is the exact
  failure mode `guardian-report.py` was just rescued from, and re-creating it deliberately would be
  perverse.
- **The Guardian rule set is still growing.** Adding a second, differently-shaped source of
  automated findings before that stabilises makes both harder to read.

### Proposed shape, when the time comes

1. SpotBugs first, alone. It has the best signal-to-noise of the four for genuine defects, and
   Checkstyle/PMD are largely style, which this codebase already handles by convention and review.
2. Run locally, read every finding, fix the true positives as their own change.
3. Commit a baseline for whatever remains, with the reasoning recorded — same standard as the
   advisory allowlist.
4. Add the CI step, failing on new findings only.

**Do not** add it as a non-failing "report" step. This repository has written down, twice, what
happens to a check that reports without gating.

---

## Recommendation

Do **ENG-23** when there is appetite for the NVD setup and one triage pass — it closes a real
asymmetry on the component with the only untrusted-input parser in the system.

Leave **ENG-24** in the backlog until: ENG-23 has shipped, the Guardian rule set has stopped
growing weekly, and somebody has time budgeted for the baseline pass rather than intending to find
it.

Neither is a reason to hold up any bug fix.
