# Finora Financial Context Engine — Technical Design Addendum

**Status:** Design Approved

**Implementation:** Deferred until v1.0 stabilization

**Scope:** MVP Financial Context Engine only. No subscription/entitlement logic, no billing, no
event bus, no AI/RAG integration in this document.

**Dependencies:**
- v1.0 bug-hunt backlog reviewed
- Production backup verification complete
- Monitoring readiness complete
- Privacy review of this document's data model

This addendum turns the product-vision doc ("Finora Adaptive Financial Onboarding & Personalization
System Design Document") into an engineering-executable design. It does not replace that doc — it
answers the questions engineering needs answered before any of it gets built: what tables, what
endpoints, what already exists that this must not duplicate, and what is explicitly out of scope.

---

## 1. Why "Financial Context Engine," not "Adaptive Onboarding"

Onboarding is one entry point into this system, not the system itself. The MVP only implements
user-declared context (onboarding answers), but the shape of the data model has to anticipate that
transactions, imported statements, Account Aggregator data, and goals are future context sources —
so the schema separates *where a signal came from* from *what the signal is* from day one (§5).
Naming and documenting it as a context engine, rather than an onboarding flow, is meant to stop the
team from treating `FinancialProfile` as disposable questionnaire storage.

Filename and future sibling doc:

```
docs/architecture/system-design/
    financial-context-engine-technical-design.md       (this document)
    subscription-entitlement-platform-design.md         (future — stub only, §10)
```

---

## 2. Existing system integration (audit findings)

Before designing new tables, the following was verified against the current codebase
(`backend/src/main/java/com/finora/`), not assumed:

| Question | Finding |
|---|---|
| Does `User` already carry DOB/income/occupation/dependents? | No. `User` holds auth/account fields (email, password hash, roles, phone) plus a few direct personal-preference columns (`lowBalanceThreshold`, `theme`, `timezone`). |
| Is there a precedent for a separate per-user extension table? | Yes — `UserSettings`: 1:1 with `User` via `user_id`, its own table, created lazily on first read. `FinancialProfile` follows this exact pattern, not a new one. |
| Does a Subscription/Plan/Entitlement system exist? | No. Only `FeatureFlag` exists, and it is a global platform-wide on/off switch, not per-user or tiered. Confirms Project 2 (§10) is genuinely greenfield. |
| Does a Goal entity already exist? | **Yes** — `com.finora.goals` (`Goal`, `GoalContribution`, `GoalService`, `GoalController`) is a live feature: user-created savings goals with `targetAmount`, `currentAmount`, `targetDate`, tracked via contributions (`GoalContribution` is a manually entered amount + date, not linked to `Transaction`). This is a tracked instrument, not a stated intent. See §6 for how the profile relates to it without duplicating it. |
| Does an event-publishing mechanism exist? | No. No `ApplicationEventPublisher` or `@EventListener` usage anywhere in the codebase. See §9 for why this document does not introduce one. |
| Is there a generic audit/history mechanism? | Yes — `AuditLog` (`user_id`, `action`, `entity_type`, `entity_id`, jsonb `metadata`, `created_at`). Used instead of a bespoke history table. See §5. |
| How does registration/onboarding currently flow? | `register → login → forgot/reset-password → refresh → logout`. No email verification step, no existing onboarding checkpoint. Inserting a "check profile completion → show onboarding" step after login is a clean net-new insertion. |
| How do existing endpoints scope requests to the current user? | Every controller (e.g. `GoalController`) takes a `CurrentUser` helper injected via constructor and calls `currentUser.id()` — no endpoint anywhere accepts a `userId` from a path or body. See §13. |

**Explicit rule for future engineers:** `FinancialProfile` does not replace or duplicate the
`com.finora.goals` module. It captures user *intent*; it may optionally initiate goal creation
through `GoalService`. Nothing in this design creates a second goal-tracking mechanism.

---

## 3. Module ownership boundaries

Finora already has enough separately-packaged modules (`goals`, `imports`, `transactions`,
`accounts`, `budgets`) that a new one needs its boundary stated explicitly, not left implicit.

| Module | Owns | Does not own |
|---|---|---|
| `FinancialProfile` (this doc) | User-declared financial context, intent tags, advisory personalization signals | Transactions, tracked goals, feature access |
| `Goals` (`com.finora.goals`) | Active, user-tracked financial goals with amounts/dates | User intentions/motivations, feature access |
| `Transactions` | Actual financial activity | User goals, user intent |
| `Subscription` (future, §10) | Feature access, entitlements | User personalization, profile data |

Rule: if a change is about *what the user wants or believes about their finances*, it belongs in
`FinancialProfile`. If it's about *what the user is actively tracking with a target*, it belongs in
`Goals`. If it's about *gating a feature*, it belongs in the future `Subscription` module and nowhere
else — `FinancialProfile` and `Goals` never implement access control.

---

## 4. MVP scope

Included:

1. `FinancialProfile` — declared vs. computed separation (§5)
2. Intent tags, not `financial_goals` (§6)
3. Question engine — data-driven, no admin CMS (§7)
4. Optional goal creation via existing `GoalService` (§6)
5. Basic personalization signals — advisory only (§8)
6. Privacy controls — consent, view/edit/delete/export (§11)

Explicitly not in scope for this document: subscription/entitlement enforcement (§10), billing,
event-driven consumers (§9), AI/RAG, Financial Health Score. These are named only as future
reference points (§12) so this design doesn't have to be revisited to accommodate them later.

---

## 5. Data model

### 5.1 `financial_profiles`

One row per user, created lazily on first onboarding interaction — same lifecycle as
`UserSettings`.

```
id                  UUID PRIMARY KEY
user_id             UUID UNIQUE NOT NULL REFERENCES users(id)

-- Declared context (user-provided, editable, source of truth for what the user told us)
life_stage          VARCHAR(50)
income_range        VARCHAR(50)
employment_type     VARCHAR(50)
intent_tags         JSONB            -- see §6
risk_preference     VARCHAR(30)
dependents_count    INTEGER
onboarding_state    VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED'  -- §7.1

-- Computed signals (system-derived; MVP populates minimally, see §5.2 and §8)
complexity_score    INTEGER
derived_signals      JSONB            -- e.g. ["MULTIPLE_ACCOUNT_USER"], see §8

created_at          TIMESTAMP NOT NULL
updated_at          TIMESTAMP NOT NULL
```

The declared/computed split is a schema decision, not a feature: even though computed signals are
minimal at MVP, keeping them in a distinct region of the row means a system-derived value can never
silently overwrite a user-declared one, and no migration is needed when computed signals grow. Do
not merge these into a flat field list.

**Why ranges, not exact values:** `income_range` stores a bucket (`"50k-1L"`), never an exact salary
figure. Reduces sensitivity, sufficient for segmentation, matches the product vision's original
intent.

**Explicitly deferred, not built:** a `source` + `confidence` field per signal (i.e.
`{value, source, confidence, updated_at}`) and a documented source-priority order (Account
Aggregator > imports > user-provided > default) are real requirements — but only once a second data
source exists to conflict with the user's declared answer. Today, every signal's source is trivially
"user provided." Do not build conflict-resolution logic against a conflict that cannot happen yet.
If a future addendum adds import-derived or AA-derived signals, that addendum must revisit this
table's shape to add those fields — this document deliberately does not pre-build them.

### 5.2 `complexity_score` contract

This is the weakest-specified value in the model, so its contract is explicit rather than implied:

- **Nullable** — absent until first computed (e.g. before onboarding completes).
- **Recalculated** whenever a declared field on the profile changes.
- **Never user-editable** — no endpoint accepts a client-supplied value for it.
- **Never used for access control or feature gating** — same advisory-only boundary as §8 and §3's
  ownership rule. A future entitlement system (§10) may *read* it as one input to a recommendation,
  but this module never enforces anything with it.

Illustrative MVP formula — weights are placeholders to be tuned at implementation, not final:

```
complexity_score = (intent_count × 2) + income_band_weight + dependents_weight
```

Do not build anything more elaborate than this for MVP; a full weighted model with transaction/
investment inputs is out of scope (§4).

### 5.3 Change history

No new `financial_profile_history` table. Use the existing `AuditLog` entity:

```json
{
  "action": "PROFILE_FIELD_CHANGED",
  "entityType": "FinancialProfile",
  "entityId": "<profile-uuid>",
  "metadata": {
    "field": "incomeRange",
    "oldValue": "50k-1L",
    "newValue": "1L-2L"
  }
}
```

Write one `AuditLog` row per changed field on every profile update. This is the audit trail this
data needs — do not add a parallel mechanism.

### 5.4 Field sensitivity classification

| Field | Classification |
|---|---|
| `income_range` | Financial sensitive |
| `dependents_count` | Personal sensitive |
| `life_stage` | Personal data |
| `employment_type` | Personal data |
| `intent_tags` | Financial preference |
| `risk_preference` | Financial preference |
| `complexity_score` | Derived — inherits sensitivity of its inputs |
| `derived_signals` | Derived — inherits sensitivity of its inputs |
| `onboarding_state` | Non-sensitive (operational) |

This classification drives §11's logging, export, deletion, and access-review rules directly — it
isn't decorative. "Financial sensitive" and "Personal sensitive" fields are exactly the ones §11
requires to be excluded from application logs.

---

## 6. Intent tags and Goal integration

`intent_tags` is a list of stated motivations, e.g.:

```json
{ "intentTags": ["BUY_HOME", "BUILD_EMERGENCY_FUND", "GROW_INVESTMENTS"] }
```

This is **not** the same thing as a `Goal` row. It answers "what does the user want," not "what is
currently being tracked." Naming it `intent_tags` (not `financial_goals`) is deliberate — it avoids
colliding with `com.finora.goals.Goal`, which already owns `targetAmount` / `currentAmount` /
`targetDate` and contribution tracking.

Flow — two steps, never automatic:

```
Onboarding: user selects "Buy a home"
        ↓
Save intent_tags += "BUY_HOME"   (profile write only — no Goal created)
        ↓
Post-onboarding prompt: "You mentioned buying a home. Create a goal to track it?"
        ↓
  [Create goal]  →  FinancialContextService → GoalService.create(...)
                     → real Goal row, user fills in target amount / date at creation time
  [Later]        →  no-op, intent_tags entry remains for future prompting
```

Do not auto-create a `Goal` from an intent tag. Target amount and date are unknown at onboarding
time, and the user may only be exploring — creating a half-populated `Goal` the user didn't ask for
is worse than not creating one.

**Module boundary (per §3):** `FinancialContextService` (this module) never writes to the `goals`
table directly and never calls `GoalRepository`. The only path from a profile action to a `Goal`
row is through `GoalService`'s public API. This keeps `Goals` the single owner of its own table.

---

## 7. Question engine

Data-driven, not hardcoded `if (age > 30)` branching:

```
profile_questions

id
key                    -- e.g. "PRIMARY_GOAL"
question_text
question_type          -- SINGLE_CHOICE | MULTI_CHOICE | DATE | RANGE
life_stage_filter       -- JSONB array; which life stages see this question
required                BOOLEAN
display_order           INTEGER
```

MVP explicitly excludes: an admin question-builder UI, a dynamic CMS, or any tooling for non-engineers
to add questions without a deploy. Questions are seeded via migration; changing them is a code
change. Build admin tooling later only if question churn turns out to justify it.

### 7.1 Onboarding completion states

Not a boolean. `onboarding_state` on `financial_profiles`:

```
NOT_STARTED | IN_PROGRESS | BASIC_COMPLETED | FULL_COMPLETED | SKIPPED
```

Needed because progressive profiling (asking contextually, not all at once) means a user can be
mid-flow indefinitely, and the product vision's "skip, always" principle means `SKIPPED` has to be a
resumable state, not a dead end.

### 7.2 Answer storage decision

`POST /profile/answers` (§13) needs a defined destination. Two options were considered:

- **Option A (chosen):** no separate answer-storage table. Each `profile_questions.key` maps to
  exactly one named column on `financial_profiles` (a fixed mapping in code — e.g. `PRIMARY_GOAL`
  writes into `intent_tags`, `INCOME_BAND` writes into `income_range`). This works because the MVP
  question set is small and finite, and every question has exactly one destination field.
- **Option B (rejected for MVP):** a `profile_answers` table storing every raw `(question_id,
  answer_value)` pair, to later support analytics like "which answers correlate with Plus upgrades."
  Real future value, but rejected here for the same reason §5.1 deferred `source`/`confidence` and
  §9 deferred events: it's a second store of the same sensitive PII (§5.4), with its own deletion/
  export/logging obligations, built for an analytics consumer that doesn't exist yet. If answer-level
  conversion analytics becomes a real need, it belongs with Project 2 / recommendation-engine work,
  scoped against an actual analytics consumer — not pre-built here.

The MVP stores normalized profile outcomes, not raw question responses. If future analytics,
experimentation, or recommendation systems require historical answer-level data, a separate
profile-answer event/history model should be designed as a dedicated initiative with its own
privacy review — this is not an oversight to revisit casually later.

---

## 8. Personalization signals (advisory only)

`derived_signals` on `financial_profiles` holds a small, fixed set for MVP:

```
ADVANCED_PLANNING_INTEREST
MULTIPLE_ACCOUNT_USER
GOAL_ORIENTED_USER
```

These are display-only. A signal may drive copy like "Finora Plus can help you understand spending
patterns" — it must not gate any feature, check any entitlement, or block any action. There is no
entitlement system to check against yet (§2), and even once one exists (§10), the boundary stays:
the profile recommends, it never enforces. `complexity_score` (§5.2) carries the identical
never-used-for-access-control rule.

---

## 9. No event architecture

This document does not introduce `FinancialProfileUpdatedEvent`, `ApplicationEventPublisher`, or any
publish/subscribe mechanism. The codebase has none today, and this profile has zero built consumers
of a "profile changed" signal — Dashboard personalization, notifications, and any recommendation
engine are all unbuilt. Introducing an event bus for one publisher and no consumers is architecture
built for a use case that doesn't exist.

**Rule for later:** introduce eventing when a second independent consumer of profile changes is
actually being built, sized against that real requirement — not speculatively here.

---

## 10. Project 2 stub — Subscription & Entitlement Platform (future initiative)

Not designed or scoped by this document — and, unlike the rest of this design, not a blank slate
either. A fuller proposal already exists: `docs/proposals/billing-subscription-entitlements-proposal.md`.
It independently confirms the greenfield finding in §2 (no `Plan`/`Subscription`/`Entitlement`
entity, no payment-gateway dependency anywhere in the codebase), and goes further than this document
does: it records a Product decision (2026-08-12) locking the tier taxonomy to exactly Free/Plus/
Premium, flags that `frontend/src/pages/landing/plans.ts` currently describes a different taxonomy
(Free/Premium/Family/Future) that needs reconciling before implementation, and specifies that
entitlement lookups must fail **closed** (unknown key → no access) — the opposite of `FeatureFlag`'s
existing fail-open default, which would otherwise be an easy revenue-leak bug to inherit by habit.

This section is kept only so `FinancialProfile`/`derived_signals` (§8) are documented as not
assuming an entitlement system exists yet — read it as a pointer to that proposal, not as a
competing or superseding design. Anyone picking up Project 2 should start from the proposal above,
not from the summary below.

**Purpose (future):** convert Free/Plus/Premium from a product idea into an enforced platform
capability — plans, per-user subscriptions, feature entitlements, access control, and (later still)
billing integration.

**Why deferred:** Project 1's advisory signals (§8) don't require an entitlement system to exist —
they render a message, they don't gate anything. Building a full entitlement platform before Project
1 has shipped and produced real signal data would mean guessing which features and segments actually
matter. Sequence: ship Project 1, observe which `derived_signals` correlate with upgrades, then scope
Project 2 against real data.

**Dependency for whenever it is picked up:** validated premium features and a billing provider
decision. Tier taxonomy is already decided (Free/Plus/Premium, per the proposal above); pricing
itself remains undefined per that same document.

Future module boundary (for reference only — see the proposal above for the actual design):

```
subscription/
├── Plan
├── Feature
├── Entitlement
├── UserSubscription
└── (billing integration — separate future initiative, not part of even the Project 2 design)
```

---

## 11. Privacy — mandatory for MVP, not deferrable

`financial_profiles` stores DOB-derived data, income range, dependents, and stated intent — sensitive
personal financial data, per the classification in §5.4. The following ship in the same release as
data collection, not after:

- **Consent before collection:** onboarding's welcome screen states why each category of question is
  asked (per the product vision doc's "value exchange" principle) before the first question is shown.
- **User controls:** view profile data, edit profile data, delete profile data, export profile data.
  All four, not a subset.
- **Deletion semantics defined before implementation:** deleting a profile removes `derived_signals`
  and stops future personalization; it does not retroactively delete `AuditLog` rows referencing the
  profile (audit trail integrity), and does not touch any `Goal` rows the user separately created via
  `GoalService` (those are owned by the goals module, per §3).
- **No sensitive values in logs.** Structured logging must exclude every field marked "Financial
  sensitive" or "Personal sensitive" in §5.4 — log field names and action types only, per the pattern
  `AuditLog.metadata` already uses safely (field name + old/new value is acceptable there because
  `AuditLog` is access-controlled data storage, not application logs).
- **Open question, not resolved by this document:** `AuditLog.metadata` retains old/new sensitive
  values (e.g. `oldValue: "50000-1L"`) indefinitely, including after the profile itself is deleted —
  deleting a `FinancialProfile` does not propose deleting or redacting its `AuditLog` history above.
  Whether that satisfies the product's data-deletion obligations is a privacy/legal decision, not an
  engineering one. Flag for privacy/legal review before this ships; do not resolve it by assumption
  in code.

This section is a hard MVP dependency, not a "future improvement" — it does not ship without it.

---

## 12. Future reference only (not implemented here)

`FinancialProfile` may later become an input to:

- Financial Health Score
- AI assistant / RAG-based guidance
- Advanced, transaction-informed recommendations
- Premium personalization (via the Project 2 platform, §10)

These are separate initiatives with their own scope, data requirements, and (for RAG) infrastructure
decisions. They are named here only so this schema isn't designed in a way that blocks them later —
none of them are designed, scoped, or approved by this document.

---

## 13. API surface (MVP)

```
GET  /api/v1/profile/questions          -- life-stage-filtered question set for this user
POST /api/v1/profile/answers            -- save one answer; writes AuditLog on change (§7.2)
GET  /api/v1/profile/completion         -- onboarding_state + progress
GET  /api/v1/profile                    -- full profile (self only)
PATCH /api/v1/profile                   -- edit declared fields (self only)
DELETE /api/v1/profile                  -- delete, per §11 semantics
GET  /api/v1/profile/export             -- user's own data, machine-readable
```

**Authentication:** JWT required, via the existing `AuthService`/`JwtAuthFilter`.

**Authorization:** user identity comes from the same `CurrentUser` helper every existing controller
already uses (`GoalController` is the reference example, per §2) — `currentUser.id()`, never a
`userId` accepted from a request path or body. No endpoint in this surface takes a `{userId}` path
segment; this is a hard rule, not a style preference — accepting a client-supplied `userId` on any of
these routes is an IDOR vulnerability against sensitive financial data.

---

## 14. Database migration plan (Flyway)

Matches the existing migration convention (the codebase is already on `V77` at time of writing):

```
Vxx__create_financial_profiles.sql
Vxx__create_profile_questions.sql
```

No `profile_answers` migration — per §7.2's Option A, there is no such table.

Existing users get no `financial_profiles` row created by migration; rows are created lazily on
first onboarding interaction, matching `UserSettings`'s existing lifecycle (§2). No backfill.

---

## 15. Rollout phases

```
Phase 0: DB tables deployed, no UI exposed.
Phase 1: Onboarding flow enabled for a small experiment cohort of new registrations.
Phase 2: Enabled for all new registrations.
Phase 3: Optional prompt surfaced to existing users (§16's migration flow).
```

Cohort size for Phase 1 is an experiment-design decision made at rollout time, not fixed by this
document.

---

## 16. Migration plan for existing users

```
Existing user logs in
        ↓
No financial_profiles row exists
        ↓
Show optional prompt: "Complete your financial profile to unlock personalized insights"
        [Start]  [Later]
```

Never blocks existing users. `onboarding_state` starts at `NOT_STARTED` for every pre-existing user;
there is no backfill of guessed values.

---

## 17. Success metrics

**Technical success (this document's responsibility):**

- Profile read/write API p95 < 200ms
- Export completes and returns all stored fields
- Delete removes profile data per §11's semantics, verified by test
- Zero cross-user access findings in security review (§13's authorization rule)

**Product success (tracked separately, not this document's scope):**

- Onboarding completion rate
- Profile completion rate
- Goal-creation conversion from intent tags (§6)
