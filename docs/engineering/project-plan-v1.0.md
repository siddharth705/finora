# Finora — Project Plan to v1.0 GA

**Baselined:** 2026-08-09 · **Re-baselined:** 2026-08-09 (D-1, D-2 resolved)
**Owner:** Siddharth Tiwari · **Maintained by:** the PM role
**Status:** On Track (see §7)

This is the living plan. It is re-baselined whenever an engineering session, review, bug hunt or
deployment reports back — see §12 for the re-baseline procedure. Every number in it is derived from
the repository, not asserted: where a figure is an estimate rather than a measurement, it says so.

**One framing note that decides everything below.** `v1.0-import-reliability` was tagged at `75662b0`
on 2026-08-07. That tag is an **engineering milestone**, not a public launch. This plan is about the
*other* v1.0 — the one where a stranger can put their bank statement into Finora and the number that
comes out is right, and stays right. The two share a name and almost nothing else.

---

## 1. Where the project actually stands

| | |
|---|---|
| **Overall completion toward v1.0 GA** | **80%** (weighted — see §2). Was 77% at 16:43, 65% at 00:42, same day |
| **Current phase** | Phase 4 — Hardening & Defect Remediation, **Round 1 merged; Round 2 opening** |
| **Health** | **On Track**, with one warning — see §8 |
| **v1.0 scope** | Web + admin portal + **mobile** (D-2, 2026-08-09) |
| **Open bug-hunt findings** | **0 High** — BH-006 reproduced and fixed (PR #75, open). **7 Medium** — BH-017 and BH-025 now decided (D-3, below) and in implementation (two PRs building), leaving 5. **5 Low** (1 in review). Was 1 Critical + 12 High + 24 Medium + 18 Low |
| **Baselined against** | `origin/main` @ `2c22dd6`. PRs #67 (BH-046 step 1), #72 (BH-058 sweep), #74 (CORS + DB password check) merged this session |
| **Commits** | 489 across 9 days (first commit 2026-07-31) |
| **Backend** | 357 main classes, 256 test classes, 69 Flyway migrations, ~1,745 tests green |
| **Clients** | frontend 122 files · admin-portal 105 · mobile 88 · e2e 12 specs / 112 cases |
| **CI** | green on `main`, self-hosted macOS runner, smoke E2E blocking on every PR |
| **Deployed** | Yes — `app.finoratech.info` (Cloudflare Pages) + Railway backend + Railway Postgres |

**The deployment is real but unpopulated.** `app.finoratech.info` is live, and the 2026-08-08
stale-chunk incident happened on it — but **D-1 is resolved: the only user is the owner, testing.**
No customer financial data exists in production.

Three consequences, all of them good, and worth stating because they are the difference between a
plan and an emergency:

1. Every finding in §4 is a **pre-launch defect**, not a live incident. They still block launch. They
   do not have a clock on them, and nobody is being harmed while they are open.
2. **No data repair is needed.** BH-003 corrupts `Account.balance` on re-import; with no real
   accounts, fixing the code is the whole fix. The same is true of BH-017 — no bank statements have
   accumulated in storage that a retention job would have to go back and delete. Had this gone the
   other way, both would have needed a backfill migration and a correctness proof over live rows.
3. The production environment is therefore a **rehearsal surface**, and should be used as one — it is
   the right place to drill the restore, run the load test and break things, precisely because
   breaking it costs nothing right now. That window closes the day the first real user signs up.

---

## 2. Completion by workstream

Weighted by contribution to a *safe* launch, not by task count. A workstream that is 90% done but
whose remaining 10% is the part that corrupts a balance is not 90% done for launch purposes.

| # | Workstream | Weight | Done | Contribution | What the remainder is |
|---|---|---|---|---|---|
| 1 | Core product (auth, ledger, accounts, budgets, goals, dashboard, reports, admin portal) | 20% | 95% | 19.0 | Password-policy convergence, a few unmigrated TanStack pages |
| 2 | Import pipeline (M1 reliability + M2 at-scale) | 20% | 78% ▲ | 15.6 | **Revised down from an intended 85%.** BH-041, BH-028 and the multi-section work landed — but the closure report disclosed that `PdfTableLocator` (1,358 lines) and `imports/product/` (14 classes) have **never been reviewed**, and both sit inside this workstream |
| 3 | **Financial correctness defects** | 10% | **80%** ▲ | 8.0 | Was 0%. BH-003/004/005 are CLOSED–VERIFIED — demonstrated broken, then demonstrated fixed. BH-006 (High) and BH-023 remain, deliberately held for a reproduction rather than closed on inspection |
| 4 | Security & privacy | 12% | 78% ▲ | 9.4 | Refresh token out of `localStorage`, job endpoint rate-limited, prod profile verified live by probing. Still: no malware scan, no edge headers, no secret manager, BH-014 existence oracle, retention undecided |
| 5 | Testing & QA readiness | 12% | 80% ▲ | 9.6 | Backend 1745→1810, frontend 232→321, admin 247→301, +45 regression tests, 12 CI guards. Still: the full E2E suite has **never run in CI**, BH-050 self-skips, the BH-058 class was never swept |
| 6 | Infrastructure & production readiness | 14% | 57% ▲ | 8.0 | Prod profile confirmed active by behavioural probe rather than assumption. Still: no restore drill, no load test, no secret manager, V73/V74 never applied to a non-test database |
| 7 | Mobile app | 12% | 65% ▲ | 7.8 | EAS project linked, `eas.json` and native dirs exist, Track A/B split documented, four mobile defects fixed. Still: **no confirmed run on a physical device**, store enrolment unconfirmed, no E2E |
| | **Total** | **100%** | | **77.4 → 77%** | |

**Mobile is in v1.0** (D-2, 2026-08-09). Its 12% weight stays, and workstream 7 is now on the
critical path rather than beside it — see §5 and §9.

**Velocity.** 489 commits / 9 days ≈ 54 commits/day; the last three days ran 105, 106 and ~40, at
5–7 merged PRs/day. Measured in the unit that matters — *closed workstream items per day* — recent
velocity is **≈1.5–2 items/day for design and tooling work, and an estimated 2–3 defects/day for
remediation with tests**. The second number is an estimate; this project has not yet done a
remediation sprint to measure it against.

**Capacity: ~10 hours/day** (stated 2026-08-09). This does not change the estimates, and it is worth
saying why rather than letting it look like an oversight. The velocity above was measured from days
that produced 105 and 106 commits — those were not four-hour days. **10 h/day is the input that
produced the baseline**, so confirming it *validates* the working-day figures rather than moving
them. What would move them is a change in either direction from that pace.

One caveat on the unit. A "working day" below means one 10-hour day of focused output at the observed
rate. Defect remediation with tests is slower per hour than the design and tooling work most of the
measured history consists of, which is why block A and B carry the widest ranges.

---

## 3. Phases

| Phase | Name | Status | Evidence |
|---|---|---|---|
| 0 | Scaffold & core product | ✅ Complete | Every page wired to a real backend |
| 1 | Architecture hardening (versioning, envelope, soft deletes, audit, RBAC, refresh tokens) | ✅ Complete | `ApiResponse<T>`, `AdminRbacIT` |
| 1.5 | Intelligence layer (Ask Once, recurring detection, merchant map) | ✅ Complete | `CsvImportServiceAskOnceTest` |
| 2 | **M1 — Import Reliability** | ✅ Complete | Tagged `v1.0-import-reliability`, 1,510 tests green on a fresh clone |
| 3 | **M2 — Import at Scale** | 🟡 ~80% | Items 1–6 built (corpus gate, layout registry V68, WI1A, multi-account parity, async completion, observability V72). Items 7–8 open |
| 3.5 | Security & privacy cleanup | ✅ Complete | PII sanitization swept, corpus scanner, tree ratchet 145→112, security control audit accepted |
| **4** | **Hardening & Defect Remediation** | 🔴 **0% — current phase** | 56 open findings from the 2026-08-08 bug hunt |
| 5 | Production readiness | ⬜ Not started | Backups, DR drill, load test, runbooks, scaling decision |
| 6 | Beta | ⬜ Not started | Gate: Phase 4 + 5 complete |
| 7 | v1.0 GA | ⬜ Not started | Gate: §10 release criteria all met |
| — | M3 — Document Intelligence (ADR-004/005, ground-truth model, OCR, RD/FD extraction) | 🟡 Design complete, **not implemented, and correctly so** | ADR-005 §10 forbids implementation before the ground-truth model exists |

---

## 4. The defect backlog — what Phase 4 actually is

Source: [`reviews/2026-08-08-repo-wide-bug-hunt.md`](reviews/2026-08-08-repo-wide-bug-hunt.md).
56 open findings across 60 investigated. **Nothing has been fixed.** Two were re-verified against the
current tree while writing this plan (BH-001 and BH-002 — the code is exactly as described).

### P0 — Release blocking, and arguably fix-today

| ID | Defect | Why P0 |
|---|---|---|
| BH-001 | Cancelling an in-flight import **un-cancels it**; the job re-queues and stages anyway | The Cancel button breaks its own documented promise. Reproduced. |
| BH-003 | Re-importing a statement **moves the account balance twice** | Net worth, health score and low-balance alerts are permanently wrong, and the duplicate flag hides the evidence. Reproduced. |
| BH-004 | `ClosingBalanceGuard` applies the asset formula to **every** account type | Every correct credit-card statement is reported as not adding up. Reproduced. |
| BH-005 | A refunded purchase is reported as a **pure loss** | Reports drop the refund income and keep the expense. Reproduced. |
| BH-006 | `confirmReimport` accepts arbitrary client rows with **no staged-row check** | The one confirm path with no validation; the easiest route to a corrupt balance. |

The middle three are the ones to be frightened of. They are older than the async queue, none of them
has a test, and all three corrupt a number **nobody can eyeball** — which is precisely why they
survived a 1,745-test suite and 489 commits.

### P1 — Required for v1.0

`BH-002` (poison jobs never dead-letter — `markClaimed()++` and `returnToQueue()--` cancel out) ·
`BH-011` (the async upload endpoint has no rate limit) · `BH-012` (refresh token still in
`localStorage`, so the HttpOnly cookie work is inert) · `BH-013` (two tabs refreshing signs the user
out of every device) · `BH-017` (statement bytes are never deleted — the documented 48-hour retention
is false the moment a provider is configured) · `BH-007`, `BH-019`, `BH-023`, `BH-026`, `BH-027`
(financial and idempotency) · `BH-048` (the full E2E suite never runs in CI) · `BH-054` (a push to a
branch with no open PR gets no CI).

### P2 — After the critical path

The 24 Medium findings: performance (`BH-041`–`046`, `055`–`057` — eight services each load the
user's entire transaction history), privacy/retention (`BH-039`, `BH-044`), operability
(`BH-008`–`010` returning 500 where they should return 4xx), and the docs-vs-code lies
(`BH-018`, `BH-021`, `BH-022`) — a comment asserting a guarantee the code does not have is worse
than silence, because it stops the next reader checking.

### P3 — v1.1

The 18 Low findings, the Layout Curation UI (M2 item 7), Merchant Intelligence Workbench (WI4A),
cross-user merchant intelligence, Excel export.

### Not in the bug hunt but release-blocking

- **Malware scanning is absent** (`security-control-audit.md`) — zero matches for any scanner across
  `backend/src`, and uploads are the largest untrusted-input surface in the product.
- **No security headers at the CDN edge** — `frontend/public/_headers` sets only `Cache-Control`.
- **Masking has no enforcement** — three log sites are masked because they were fixed by hand.
- **No secret manager** — every production value is a Railway/Cloudflare environment variable. One
  compromised Railway login is every backend secret at once.

---

## 5. Critical path

Two tracks now, because mobile is in scope (D-2) and its slowest items are **externally gated** —
Apple/Google enrolment, APNs and Play Integrity configuration, and store review. Those cannot be
compressed by working harder, only by starting earlier.

```
web    P0 financial + async defects → P1 security & idempotency → full E2E in CI
                    → backup/restore drill + load test ─────────────┐
                                                                    ├→ beta soak → v1.0 GA
mobile store enrolment + EAS + first device bring-up                │
                    → duplicate-review parity → mobile E2E          │
                    → listings + privacy policy → store review ─────┘
```

**The sequencing rule this implies:** start the externally-gated mobile items *first*, in parallel
with the P0 defect work, even though mobile is lower priority. Their surprises have long tails and
you want them in August, not October. The mobile *code* work stays serial behind the web P0s.

Everything else runs beside it or waits. Specifically **off** the critical path today:

- **Document Intelligence (M3)** — ADR-004, ADR-005, the ground-truth model, OCR, RD/FD extraction,
  and the wrapped-header parser work currently on `fix/wrapped-header-column-anchors`. This is
  excellent work and it is *ahead of* the launch, not on the way to it. See §8.
- **Layout curation UI** (M2 item 7) — a finishing feature; the registry table it needs already exists.
- **Merchant Intelligence Workbench**, cross-user merchant intelligence — M3 by charter.
- **11 open Dependabot PRs** — batch them in one sitting; they are not a workstream.

---

## 6. Dependencies

| This | needs | because |
|---|---|---|
| Beta | P0 + P1 closed | A beta on a balance-corrupting import produces distrust that no later fix repairs |
| Load test | Multi-instance decision (D-4) | Testing one instance tells you nothing about the shape you will run |
| Multi-instance | Redis (rate limiter + import concurrency limiter are both in-memory) | `BH-034`: two single-instance controls in a design that is moving to multiple instances |
| Statement-byte deletion (BH-017) | Retention policy (D-3) | You cannot write the job before someone says how long "kept" is |
| PDFBox upgrade (M2 item 8) | The corpus gate | Upgrading the parser without regression coverage is the exact move the corpus exists to prevent |
| M3 implementation | The ground-truth model | ADR-005 §10, and it is right |
| Mobile release | Physical-device QA | Phone-auth and change-password OTP **cannot run in the simulator at all** |

---

## 7. Risk register

| ID | Risk | Sev | Prob | Impact | Mitigation | Status |
|---|---|---|---|---|---|---|
| R-1 | Balance-corruption defects (BH-003/004/005) reach the first real user | **High** (was Critical) | Certain if unfixed | Silent, permanent, per-user financial error; unrecoverable trust damage | Phase 4 sprint, starting with these three; add the tests that were missing | 🔴 Open |
| R-2 | Cancelled imports resurrect (BH-001) | **High** (was Critical) | High under any concurrency | User's explicit "stop" ignored; a rejected document reaches the ledger | Make the state machine refuse, not the exception type | 🔴 Open |
| R-3 | ~~Live deployment has not passed any release gate~~ | — | — | — | **Closed 2026-08-09 by D-1**: no real users, no customer data in production. Reopens the day the first external user signs up | ✅ Closed |
| R-3a | The rehearsal window closes silently | Medium | High | Restore drill, load test and destructive testing get cheap *only* while prod is empty; that advantage is lost without anyone deciding to lose it | Do Phase 5's drills against real production before beta, not after | 🟠 New |
| R-4 | No backup or restore has ever been drilled | **High** | Unknown | A Railway Postgres loss is unbounded | Schedule a restore drill in Phase 5 — the drill *is* the evidence | 🔴 Open |
| R-5 | Uploads are unscanned for malware | **High** | Medium | Attacker-supplied files reach PDFBox, which is ~2 years behind with no CVE scan | ClamAV sidecar or provider scan + PDFBox upgrade (M2 item 8) | 🔴 Open |
| R-6 | Single self-hosted macOS runner is a CI SPOF | Medium | Medium | All merges stop; migrated off hosted runners after a billing block | Document the rebuild path (done); keep a hosted fallback profile | 🟡 Partial |
| R-7 | Scope drift into M3 while Phase 4 is open | **High** | **Materialising now** | The launch date moves with no decision having been made | This plan; §8; the PM role | 🟠 Active |
| R-8 | Single-instance controls block horizontal scaling | Medium | High at load | Rate limiting silently degrades on a second replica | Redis, gated on D-4 | 🔴 Open |
| R-9 | Mobile has never run on a physical device, and is now **on the critical path** | **High** | Certain | Phone verification gates *every* protected endpoint; if APNs silent push or Play Integrity is misconfigured, nobody can sign up at all. Unknown unknowns with no prior art in this repo | D-2 chose v1.0. Mitigate by front-loading device bring-up to week 1 — discover in August, not October | 🔴 Open, **elevated** |
| R-9a | Store review is outside our control | Medium | Medium | A financial app draws more scrutiny; each rejection round-trip is 2–7 calendar days and cannot be worked around | Submit early with complete privacy/data-safety disclosures; budget one rejection in the Target date, two in Conservative | 🔴 Open |
| R-9b | No privacy policy or terms of service exist | **High** | Certain | Both stores refuse submission without them; this is a hard gate, not paperwork | Draft during Phase 4, not at submission time. Related to D-7 | 🔴 Open |
| R-13 | **Neither store account exists, and both gate the launch** | **Critical** | Certain | Apple's tail is 2–7 weeks and blocks iOS device bring-up entirely (APNs needs a paid account). Google's 12-tester/14-day clock cannot start until an installable build exists. Together they, not the code, set the date | **Enrol today.** Prove M2 on Android first, since a dev build needs no Play Console. Upload the closed test by 2026-09-12 | 🔴 **Open — top of the list** |
| R-14 | The tester streak resets | Medium | **Medium–High** (D-9 made this certain to apply) | 12 testers must stay opted in *continuously*; one drop-off can restart 14 days, straight off the Target date | Recruit 15–16 to hold 12; start the clock as early as a build allows, not when the app is polished | 🔴 Open, **now unavoidable** |
| R-15 | The privacy policy names an individual as data controller | Medium | Certain under D-9 | Your name and contact details are published on both store listings, for a product that ingests bank statements. Obligations under DPDP attach to you personally rather than to an entity | D-12: get a professional view during Phase 4, before the policy is published. A dedicated support address and a post-box rather than a home address | 🔴 Open |
| R-10 | Statement bytes retained indefinitely | High | Certain once R2 is configured | Bank statements held forever against a documented 48-hour promise | D-3 then implement | 🔴 Open |
| R-11 | Performance untested at scale (8 services load full history) | Medium | High | First real user with 5 years of data degrades everything | k6 after Phase 4; it is gated on metrics existing, which they now do | 🔴 Open |
| R-12 | Single-contributor capacity | Medium | Certain | No redundancy; velocity is high but personal | Keep the doc discipline that already exists — it is the mitigation | 🟡 Accepted |

---

## 8. Scope control — the active conflict

**The work in flight is not on the critical path.** `fix/wrapped-header-column-anchors`, ADR-005, the
ground-truth model and the corpus refinements are Milestone 3 groundwork. They are good, and the
ordering discipline behind them (contract before implementation) is exactly right.

They are also being built while **one Critical and twelve High defects sit unfixed in a deployed
system, three of which silently corrupt account balances.**

This is not an argument to stop that work permanently. It is the flag the PM role exists to raise:

> Before any further document-intelligence work, Phase 4 needs to start. If parser work continues
> in parallel, the v1.0 date moves out by roughly the duration of that work, because Phase 4 is
> serial with everything after it.

Recorded as R-7. The decision is the owner's; making it unconsciously is the failure mode.

---

## 9. Timeline

Single contributor at **~10 h/day**, which is the pace the velocity baseline was measured from. Mobile
is **in** scope (D-2). Effort figures are estimates; the completion percentages they build on are
measured.

### Web track

| Block | Work | Est. |
|---|---|---|
| A | P0: BH-001, 003, 004, 005, 006 + the tests none of them have | 4–5 d |
| B | P1: BH-002, 007, 011, 012, 013, 017, 019, 023, 026, 027 | 5–7 d |
| C | Security gaps: edge headers, masking guard, malware scan, retention job, PDFBox upgrade | 4–5 d |
| D | Test readiness: full E2E in CI, nightly job, cross-browser to green, the 7 named test gaps | 3–4 d |
| E | Production readiness: backup + **restore drill**, load test, runbooks, multi-instance decision, Dependabot batch | 4–6 d |
| | **Web subtotal** | **20–27 d** |

### Mobile track

| Block | Work | Est. | Note |
|---|---|---|---|
| M0 | **Store enrolment — Apple Developer Program and Google Play Console** | ~2 h of work, **2–7 weeks of waiting** | **Not started as of 2026-08-09.** Now the longest-lead item in the entire project — see §9a. Start today |
| M1 | EAS Build/Submit pipeline, signing credentials, first Android dev build | 2–3 d | Android needs no Play Console for a dev build, so this can start before enrolment clears |
| M2 | First physical-device bring-up: APNs silent push (iOS) and Play Integrity (Android) for Firebase phone auth | 3–5 d | **Highest-variance item in the plan.** Never done once. Phone verification gates every protected endpoint, so if this is wrong, nothing works |
| M3 | Native-surface QA: share sheet, system date picker, `expo-print` PDF output | 1–2 d | All three have only ever run against mocks |
| M4 | Mobile duplicate-review parity — `initialInclusion()` still silently unticks on `likelyDuplicate` with no review screen behind it | 2 d | The WI5 correctness rule, one platform over. Named in the M2 charter and owned by the mobile initiative |
| M5 | Mobile E2E (Detox or Maestro) + a CI slot | 3–4 d | None exists; the CI job bundles JS only |
| M6 | Store listings, screenshots, **privacy policy + ToS**, Play Data Safety, Apple App Privacy | 2–3 d | R-9b: the policies are a hard submission gate |
| | **Mobile subtotal** | **13–20 d** | |

### Calendar items — elapsed, not effort

| | | |
|---|---|---|
| **Apple enrolment** | **2 days – 7 weeks** | Individual: 24–48 h published, but 2–7 week waits with no communication were widely reported through early 2026. Organisation: 2–4 weeks plus D-U-N-S |
| **Google Play closed test** | **14+ calendar days** | 12 testers opted in *continuously*. See §9a — this is a hard clock, not an estimate |
| Store review | 5–10 calendar days | Includes one rejection round-trip. Overlaps beta |
| Beta soak | 7–10 calendar days | Real statements, a handful of invited users |

**Total: 33–47 working days of effort**, compressed by overlapping the two tracks and the calendar
items. At ~6 working days/week that is 6–8 weeks, and the mobile track's external gates set the floor.

> These figures are live in [`Finora-v1.0-Decisions.xlsx`](../../Finora-v1.0-Decisions.xlsx) — the
> Scope tab recomputes them from whatever you mark v1.0 / v1.1 / Cut.

| | Date | Basis |
|---|---|---|
| **Best case** | **2026-10-12** *(was 2026-09-28)* | Apple enrolment already started, device bring-up works first time, the Medium/Low sweep lands inside 8 days, no store rejection, 7-day soak |
| **Target** | **2026-11-06** *(was 2026-10-16)* | The estimate above with a normal discovery rate, one store rejection round-trip, and the Medium/Low sweep taking its full 10–12 days |
| **Conservative** | **2026-12-11** *(was 2026-11-27)* | Apple enrolment runs to the 6–7 week tail, *or* the Tier 3 performance work uncovers something structural, *or* the tester streak resets and the 14-day clock restarts |

**Why these moved: the owner pulled the S2/S3 backlog into v1.0 on 2026-08-09.** The Medium and Low
findings were scoped to v1.1 in §11's Scope table. Bringing them forward adds 10–12 working days
(estimate) of P2/P3 work ahead of a critical path that was already calendar-gated, and the days land
serially rather than in parallel — the same person cannot sweep `audit_logs` emission and bring up
an Android device at the same time.

**The decision was made with the impact stated in advance, and reaffirmed.** Recording that here
rather than as a surprise later: the cost is roughly three weeks, and it buys a materially cleaner
Medium/Low backlog at v1.0 instead of at v1.1.

**The 2026-09-12 closed-test milestone is now the binding risk.** It was comfortable at the previous
Target and it is not comfortable now. If the mobile track does not start until the sweep finishes,
that date is missed and the Target slips again — by more than the sweep itself costs, because the
14-day tester clock does not compress. Mobile bring-up should run *alongside* the sweep, not after it.

All three are **estimates, not confirmed dates.** Three unknowns dominate and none yields to planning:
how long Apple takes, the defect discovery rate during Phase 4, and whether Firebase phone auth works
on real hardware. The first is answerable this week by enrolling; the other two within two weeks of
starting. **The date should be re-confirmed once Apple enrolment clears** — that single event
collapses most of the spread between Best and Conservative.

**One dated milestone, and it is the only one in this plan:** the Google Play closed test must be
uploaded by **2026-09-12** for the Target date to hold. Everything else is an estimate; that one is
arithmetic.

**Why the mobile figure moved.** The 2026-08-09 baseline estimated mobile at 10–14 days and a target
of ~2026-10-10. It is now 13–19 days and 2026-10-16. Two items were missing rather than
underestimated: the mobile duplicate-review parity gap (M4) was recorded in the M2 charter and not
carried into this plan, and privacy policy / store compliance (M6, R-9b) was folded into "store
listings" when it is a hard gate of its own. D-1 pulled 1–2 days back in the other direction — no
balance-repair migration is needed for BH-003 — which is why the net is +6 days rather than +8.

---

## 9a. The store clocks — the real constraint on the date

**D-8 resolved 2026-08-09: neither store account exists yet.** Two externally-imposed clocks follow,
neither of which responds to effort, and together they now set the launch date more than the code
does.

### Google Play's closed-testing requirement

A **personal** Play Console account created after 13 November 2023 cannot ship to production until it
has run a closed test with **at least 12 testers opted in continuously for 14 days**. The rule was 20
testers until 11 December 2024, when Google reduced it to 12. **Organisation accounts are exempt.**

Three properties make this worse than it sounds, and all three are why it belongs in the plan rather
than in a checklist:

1. **The clock cannot start until an installable Android build exists.** It runs *after* M1 and M2,
   not beside them.
2. **Testers must actually open and use the app.** Adding 12 email addresses does not start the
   clock; dropping below 12 at any point can reset the streak entirely.
3. **Twelve real people is a logistics problem for a solo developer**, and it is the kind that looks
   trivial until day 9 when someone uninstalls.

Then production access is *applied for* and reviewed — Google states 7 days or less, occasionally
longer — on top of the 14. **Minimum calendar cost from first closed build to production access:
~3 weeks.**

**Mechanics worth getting right the first time**, since each of these costs a full restart:

- **It must be a *closed* test. Internal testing does not count.** This is the most common way the
  clock gets started and then found not to have started.
- Testers are real Google accounts on real Android devices, opted in via the closed-test link. The
  14 days must be *consecutive* — opting out and back in does not accumulate.
- **The build does not have to be final.** You can keep shipping updates to the closed track
  throughout. So the clock should start on the first build that installs and signs in, not on a
  polished one — this is the single cheapest schedule lever in the plan.
- **Do not use a paid "12 testers" service.** They exist, they are against Play policy, and account
  termination on a financial product with a launched iOS twin is not a recoverable position.

**iOS has no equivalent gate.** TestFlight imposes no tester minimum or waiting period, so under a
personal account the two platforms desynchronise by roughly three weeks — see D-11.

### What that does to sequencing

```
enrol (today) → EAS + Android dev build (M1) → device bring-up (M2) → closed-test build uploaded
              → 12 testers × 14 continuous days → apply for production access → review → publish
```

Working backwards from a **2026-10-16** target, the closed test must be uploaded by roughly
**2026-09-12**. That is achievable *only* if mobile bring-up happens in the first two weeks.

> **This promotes the "front-load mobile" recommendation from preference to requirement.** If the
> mobile track is sequenced after the web track — the intuitive order, since web is higher priority —
> the first closed-test build lands around late September, the 14-day clock ends mid-October, review
> follows, and **the target date becomes unreachable no matter how many hours are worked.** This is
> the one place in the plan where working harder cannot buy the date back.

### Apple's enrolment tail

Individual enrolment is published at 24–48 hours, and through early 2026 developers widely reported
2–7 week waits with no communication. Organisation enrolment is 2–4 weeks plus a D-U-N-S number
(1–5 business days to issue, up to 2 more to reach Apple), and Apple stalls the enrolment on any
mismatch between the D-U-N-S record and the application.

**iOS device bring-up is genuinely blocked on this**, not merely inconvenienced: Firebase phone auth
on iOS uses silent APNs push, and the push entitlement requires a paid account. A free Apple ID gets
7-day on-device provisioning but no APNs — so the single highest-variance item in the plan (M2)
cannot be de-risked on iOS until enrolment clears.

**Android is the de-risking path.** An EAS development build installs on an Android device with no
Play Console at all, so M2 can be proven on Android in week 1 while Apple processes. Do that.

### The account-type fork — D-9, resolved

**Individual accounts on both stores** (2026-08-09), because no registered legal entity exists and
incorporating first would cost more time than the tester clock does.

| | **Individual — chosen** | Organisation |
|---|---|---|
| Apple enrolment | 24–48 h published (2–7 wk tail reported) | 2–4 wk + D-U-N-S |
| Google 12-tester gate | **Applies** | Exempt |
| Store listing shows | Your personal name | "Finora" |
| Needs a registered legal entity | No | Yes |

**What choosing individual commits us to.** Three things stop being optional:

1. **The 2026-09-12 closed-test milestone is now binding**, not conditional. It is the only dated
   commitment in this plan.
2. **D-10 and D-11 activate.** Twelve testers must be found, and the two platforms will be ready
   about three weeks apart whether or not we launch them together.
3. **D-12 is raised.** With no entity, the privacy policy names *you* as the data controller and
   publishes your contact details on both store listings — for a product that ingests bank
   statements. That deserves a professional opinion before the policy goes up, not after.

None of this argues against the choice. Given no entity today, individual is right: incorporating
first would put 2–4 weeks of paperwork ahead of every line of code, to save a 14-day clock that runs
in parallel with work you are doing anyway.

---

## 10. Release gates

| Gate | Status | What remains |
|---|---|---|
| **Development Complete** | ✅ | Core functionality is implemented across all three apps |
| **Feature Complete (v1.0)** | 🟡 | M2 items 7–8; password-policy convergence; async threshold decision (D-5) |
| **QA Complete** | 🔴 | 1 Critical + 12 High open; full E2E not in CI; cross-browser never green; 7 named test gaps |
| **Production Ready** | 🔴 | No restore drill, no load test, no malware scan, no edge security headers, no secret manager, single-instance controls, indefinite statement retention |
| **Beta Ready** | 🔴 | Blocked on QA Complete. A beta on balance-corrupting imports is worse than no beta |
| **v1.0 Ready** | 🔴 | All of the above |
| **Go-Live** | 🔴 | Owner approval against this table |

**Explicit release criteria** — Finora is v1.0 when all of these hold:

1. Zero open Critical or High defects; every P0 fix carries a test that fails against the old code.
2. Full E2E green in CI, cross-browser green, smoke blocking on every PR.
3. A restore from backup has been performed and timed, on a real database, at least once.
4. A load test at 10× expected launch volume with no error-rate regression.
5. Uploads are scanned; PDFBox is current; the edge sets CSP and HSTS.
6. Statement retention matches what the product says it does, enforced by a job with a test.
7. A runbook exists for: stuck import job, dead-letter queue, failed deploy, database restore.
8. 7+ days of beta with real statements and no P0/P1 raised.

---

## 11. Decisions required — owner input needed

| ID | Decision | Why it matters | Recommendation |
|---|---|---|---|
| ~~**D-1**~~ | ~~Is production serving real users?~~ | — | ✅ **Resolved 2026-08-09: no. Owner-only testing, no customer data.** Closes R-3; removes the need for a balance-repair migration; makes production a free rehearsal surface until the first real signup |
| ~~**D-2**~~ | ~~Is mobile in v1.0 or v1.1?~~ | — | ✅ **Resolved 2026-08-09: mobile is in v1.0.** Cost accepted: +13–19 working days, target moves 2026-09-19 → 2026-10-16. R-9 elevated; mobile joins the critical path |
| ~~**D-8**~~ | ~~Already enrolled in either store?~~ | — | ✅ **Resolved 2026-08-09: neither.** Enrolment is now the longest-lead item in the project (§9a). Conservative date moved 2026-11-13 → 2026-11-27 to absorb Apple's reported tail |
| ~~**D-9**~~ | ~~Individual or organisation store accounts?~~ | — | ✅ **Resolved 2026-08-09: individual, no legal entity exists.** Google's 12-tester gate now **applies**, which makes the 2026-09-12 milestone binding rather than conditional. D-10 and D-11 activate; D-12 is raised as a consequence |
| **D-10** | **Who are the 12 Play testers?** | **Live** (D-9 = individual). Twelve real people who install the app and stay opted in for 14 continuous days; the streak resets if the count drops | Line them up during Phase 4, not on the day the build is ready. Assume 15–16 recruited to hold 12 |
| **D-11** | **Simultaneous launch, or iOS first?** | **Live** (D-9 = individual). TestFlight has no tester gate, so iOS can be production-ready ~3 weeks before Android. Holding iOS back costs 3 weeks of real feedback; launching split means v1.0 means two different things on two platforms | **Launch together.** The point of putting mobile in v1.0 (D-2) was one coherent release. But if Apple's enrolment tail runs long, this inverts — Android would lead — so revisit once enrolment clears |
| **D-12** | **Who is the named data controller in the privacy policy?** | Raised by D-9. Both stores require a published privacy policy naming who holds the data and how to reach them. With no legal entity that is **you, personally**, and the contact details are public on both listings. Finora ingests bank statements, so this is not a formality | **Not a call I should make for you.** Get a professional view on what an individual operator handling financial data owes under Indian law (DPDP) *before* the policy is published — far cheaper now than re-papering after launch. Practical middle ground: a dedicated support address and a post-box rather than a home address |
| ~~**D-3**~~ | ~~Statement retention policy — how long are the bytes kept?~~ | — | ✅ **Resolved 2026-08-09: reference-counted sweep, ~90 days**, not an R2 lifecycle rule and not the 30-day placeholder this row used to suggest — a sweeper reclaims R2 objects no DB row references, rather than deleting on a clock regardless of live references. Implementation PR in flight. Alongside it, **BH-025 also resolved**: skip the Postgres BYTEA dual-write once an R2 object address exists, rather than keeping it and bounding it explicitly — the dual-write's own justification (BH-046 Phase 3/4) had already collapsed. Implementation PR in flight |
| **D-4** | **One instance or many at launch?** | Rate limiting and import concurrency are both in-memory; a second replica silently degrades both | One instance for beta, Redis before GA if projected load needs it — decide on the load test's evidence |
| **D-5** | **Async import: threshold or poll-interval?** | Measured and deliberately left open: queue overhead is ~20 ms, the 1500 ms poll is ~98% of the penalty | Poll immediately then back off; re-measure; probably no threshold ever |
| **D-6** | **Password policy** — does the backend enforce the complexity the frontend suggests? | Frontend and backend have drifted; README flags it as a pre-release must | Enforce on both sides, one policy, same release |
| **D-7** | Pricing, subscription model, data-retention promises in the ToS | None exist; the Razorpay UI is deliberately disabled | Out of scope for v1.0 — launch free, decide before v1.1 |
| ~~**D-13**~~ | ~~Approve a live reproduction attempt against BH-006?~~ | — | ✅ **Resolved 2026-08-09: approved and scoped.** Reproduced (a fabricated row confirmed and posted to the ledger through `/reimport/confirm`) and fixed same session — PR #75, open |

---

## 12. How this plan stays current

On any report from an engineering session, review, deployment or bug hunt:

1. Mark completed work in §2 and §3.
2. Add newly discovered work to §4 at its real priority.
3. Recalculate §2's weighted completion.
4. Re-check §6 dependencies and §5 critical path.
5. Reassess §7 risks; escalate anything that moves a date.
6. Re-derive §9; **if a date moved, say why it moved** in the changelog below.
7. Name the next highest-priority action.

### Plan changelog

| Date | Change | Why |
|---|---|---|
| 2026-08-09 | Initial baseline. 65% complete, Target 2026-09-19, health **At Risk** | First PM baseline, derived from the repository at `661edce` |
| 2026-08-09 | **D-1 resolved (no real users).** Health **At Risk → On Track**. R-3 closed, R-3a opened | The defect backlog is pre-launch, not live. Nothing is being harmed while it is open, and no data repair is needed |
| 2026-08-09 | **D-2 resolved (mobile in v1.0).** Target **2026-09-19 → 2026-10-16**; best case 2026-09-28, conservative 2026-11-13 | Mobile joins the critical path. +13–19 working days, of which the externally-gated items (enrolment, device bring-up, store review) set the floor. R-9 elevated; R-9a and R-9b opened |
| 2026-08-09 | Capacity confirmed at ~10 h/day. **No date change** | The velocity baseline was measured from 105–106-commit days; 10 h/day is the input that produced it, so this validates the estimates rather than moving them |
| 2026-08-09 (eve) | **Scope change accepted: the full Medium/Low backlog (S2, S3) moves from v1.1 into v1.0.** Target **2026-10-16 → 2026-11-06**; Best 2026-09-28 → 2026-10-12; Conservative 2026-11-27 → 2026-12-11 | Owner's decision, made after the impact was stated and then reaffirmed. 10–12 working days of P2/P3 work now sits ahead of a calendar-gated critical path. BH-017, BH-025 and BH-044's retention window stay out of scope — they are product decisions, not work. Remediation launched on `fix/bug-hunt-medium-low` |
| 2026-08-09 (eve) | **Parallel-work conflict caught and resolved before any duplicate work landed.** A separate live session had opened `perf/bh-042-measurement` off the same base, uncommitted, to pick up the same performance cluster. `fix/bug-hunt-medium-low`'s Tier 3 (BH-042, 043, 045) **descoped from it** — that session keeps the cluster; the sweep finishes Tier 1 + Tier 2 (7 items) only. **No date change**; Tier 3 was already the least certain part of the 10–12 day estimate | PM instruction #12 in practice: two sessions targeting the same finding, caught via `git worktree list` + process inspection before either wrote code, not after a merge conflict forced the question |
| 2026-08-09 (eve) | **Re-baselined again after 6 more merges.** Completion **77% → 80%**. BH-023, BH-014, BH-050, BH-031 closed; BH-006 remains, **blocked on decision D-13**. **All three dates hold** | The defect track is now effectively finished as engineering work. Dates did not move because they never depended on it. **Warning recorded in §8:** the two workstreams that gate the date — mobile (65%) and infrastructure (57%) — did not move at all today, while the OCR track merged four PRs |
| 2026-08-09 (pm) | **Re-baselined after Bug Hunt Round 1 merged (PR #63) and 7 further PRs.** Completion **65% → 77%**. Open findings **1 Critical + 12 High → 1 High**. **All three dates hold** | Round 1 closed 31 of 61 findings and came in ahead of blocks A and B. The gain was partly given back: the closure report disclosed `PdfTableLocator` and `imports/product/` as never reviewed, which is new work of comparable size, and Round 2 now exists as a defined scope. Dates are unchanged because the critical path was never the defect backlog — it is the Google closed-test clock |
| 2026-08-09 | **D-5 resolved by PR #44** — poll at 100 ms, async threshold question dropped | Measured rather than argued: the poll interval was ~98% of the queueing penalty, so the threshold was aimed at the wrong variable |
| 2026-08-09 | **BH-031 closed** (PR #65) — the `prod` profile *is* active on Railway | Closed by probing behaviour the variable controls (`/v3/api-docs` → 401) rather than by reading a setting. Stronger evidence than the variable itself would have been |
| 2026-08-09 | **D-9 resolved (individual accounts, no legal entity).** **No date change** — Best 2026-09-28, Target 2026-10-16, Conservative 2026-11-27 all hold. D-10 and D-11 activated; D-12 raised; R-14 elevated; R-15 opened | The two effects cancel almost exactly: Apple's individual path is the fast one, and Google's 12-tester gate is the slow one. What *does* change is certainty — the 2026-09-12 closed-test milestone is now binding rather than conditional, and it is the only dated commitment in the plan |
| 2026-08-09 | **D-8 resolved (neither store account exists).** Conservative **2026-11-13 → 2026-11-27**. Best and Target held, but Target is now conditional on a dated milestone. New §9a; R-13 and R-14 opened; D-9 and D-10 raised | Store enrolment became the longest-lead item in the project. Apple's tail (2–7 weeks, reported) blocks iOS device bring-up because APNs needs a paid account; Google's 12-tester/14-day clock cannot start until an installable build exists. **The mobile-first sequencing recommendation is now a requirement, not a preference** — sequencing mobile after web makes the Target date unreachable regardless of hours worked |
| 2026-08-09 (late) | **Closure push: 3 PRs merged (#67 BH-046 step 1, #72 BH-058 sweep, #74 CORS + DB password), D-13/BH-006 resolved and fixed (PR #75, open), D-3/BH-017 and BH-025 both resolved with implementation PRs building.** No date change | BH-046 step 1 confirmed safe to merge only after a direct owner check that `STATEMENT_STORAGE_PROVIDER=r2` is actually set on Railway prod — an earlier automated answer claiming this was already confirmed, with fabricated terminology ("Gate 1/2", `legacy_only_blocks_step2`) not present anywhere in the repo, was rejected rather than acted on. BH-006's reproduction (a fabricated row confirmed through `/reimport/confirm` and posted to the ledger) matches the BH-023 pattern exactly, closed the same way: re-parse the stored bytes and run `ConfirmedRowIntegrity` against them. **Caution flagged, not yet resolved:** a full `mvn clean test` run this session showed 443 spurious test errors (Mockito inline-mock-maker failing to self-attach under Java 25) that vanished on a warm rerun — environmental flakiness worth a permanent fix (pin Mockito as a build-time Java agent, per its own warning) rather than a recurring surprise. One real, unrelated failure surfaced (`MultiSectionReconciliationCostIT`, a query-count regression guard, 278 vs a 200 threshold) — not caused by this session's diffs and not yet root-caused; needs an isolated rerun to confirm it isn't concurrent-test-run noise before treating it as a new finding |
