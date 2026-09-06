# First-Login Onboarding Tour & Getting-Started Checklist — Design

**Status:** Approved design, ready for implementation planning.

## 1. Objective

A brand-new Fynora user's first dashboard view is empty — no accounts, no imported
transactions, no budgets, no goals. Nothing on screen tells them what to do first or what the
product actually does for them (auto-import, categorization, budgets, goals, insights, all in
one place — a combination most users haven't used together before). This design adds a guided,
one-time first-login experience — a welcome screen, a short "what are you here for" question, an
interactive spotlight tour of the real dashboard, and a persistent getting-started checklist —
to raise activation instead of leaving new users to discover the product cold.

Web and mobile both get the full flow. Content (copy, chip options, tour steps, checklist items)
was drafted collaboratively and is treated as final for v1; this document adds only the minimum
technical model needed to support it.

## 2. Current behavior (verified against this codebase)

- No onboarding/tour concept exists anywhere in the codebase today (`grep -ri onboarding` across
  `frontend/src`, `mobile/src`, `backend/src` turns up nothing but unrelated comments). Neither
  `frontend/package.json` nor `mobile/package.json` has a tour library (no joyride/driver.js/
  intro.js/shepherd/react-native-copilot).
- `Goals` is already a real, shipped feature — savings targets with progress tracking
  ([frontend/src/pages/Goals.tsx](../../../frontend/src/pages/Goals.tsx),
  [mobile/src/screens/GoalsScreen.tsx](../../../mobile/src/screens/GoalsScreen.tsx)). The new
  onboarding question about user intent is called **Financial Focus**, not "goals," to avoid
  collision.
- `accountsApi.create` ([frontend/src/api/endpoints.ts:147](../../../frontend/src/api/endpoints.ts))
  is manual account entry only (name, type, balance, holder name, account number, bank) — there is
  no OAuth/aggregator bank-linking (no Plaid/Yodlee/SaltEdge/open-banking integration anywhere in
  the codebase). This settles the Success screen's CTA order below.
- Every authenticated web route is gated through `Protected`/`ProtectedRoute` in
  [frontend/src/App.tsx](../../../frontend/src/App.tsx); mobile's equivalent gate lives in
  [mobile/src/navigation/RootNavigator.tsx](../../../mobile/src/navigation/RootNavigator.tsx).
  Both are the natural interception point for "first login, not yet toured."
- This codebase's established convention for "did a one-time thing happen, and when" is a
  nullable `TIMESTAMPTZ` column that's set once and never cleared in normal operation —
  `goals.completed_at` (V94), `users.deactivated_at` (V88), `users.password_changed_at` (V40).
  `users.onboarding_completed_at` follows the same pattern (reset is the one exception, needed for
  "Retake Product Tour").
- This codebase's established convention for "a small set of tagged values per user" is a child
  table shaped like `feature_entitlements` (V99) — not `@ElementCollection` (no entity in
  `backend/src/main/java/com/finora/entity` uses it). `user_financial_focus` follows that shape.
- Backfilling existing rows in the same migration that adds a new gating column is established
  practice — V99's `subscriptions` backfill exists for exactly the same reason this feature needs
  one: without it, every current user would be ambushed by a tour appearing on their next login.
- Latest Flyway migration on this branch's base is V160
  (`backend/src/main/resources/db/migration`); the implementation plan must re-check
  `origin/main` at build time per this repo's migration-collision rule.

## 3. Content (approved, treated as final)

### Screen 1 — Welcome
- Title: "Welcome to Fynora 👋"
- Subtitle: "Take control of your finances in one place. Track spending, create budgets, monitor
  goals, and understand where your money goes with powerful insights."
- Buttons: **Start Setup**, Skip for Now

### Screen 2 — Financial Focus
- Title: "What would you like to achieve with Fynora?"
- Subtitle: "Select all that apply. We'll personalize your experience."
- Options (multi-select chips): 💰 Track my spending · 📊 Create and manage budgets · 🎯 Save for
  a goal · 🏦 See all my accounts in one place · 📈 Improve my financial habits · 💳 Reduce debt ·
  🔍 Just exploring (selecting this alone clears the others)
- Button: Continue (always enabled — no forced answer)

### Screen 3 — Tour Intro
- Title: "Let's take a quick tour"
- Subtitle: "This will only take about 30 seconds and will help you get the most out of Fynora."
- Buttons: **Start Tour**, Skip

### Flow control (skip semantics)
- Welcome's **Skip for Now** exits the entire flow immediately (no Financial Focus, no tour) —
  calls `onboarding-complete` only, straight to dashboard. `financialFocus` stays empty, same as if the
  question had been asked and left blank.
- Financial Focus's **Continue** always advances regardless of selection count (0 selected is a
  valid answer, equivalent to skipping this screen alone) — calls `financial-focus` with whatever
  is selected (possibly `[]`), then proceeds to the Tour Intro screen.
- Tour Intro's **Skip** and the tour's own **Skip** (available on every step) both end the same
  way: call `onboarding-complete`, go straight to the Success screen — a partially-seen tour still
  counts as seen, same as a fully-completed one, so the user is never re-prompted.
- Completing the final tour step calls `onboarding-complete` and shows the Success screen.

### Interactive tour — 7 steps, in this order
1. **Dashboard** — "Your Financial Command Center" / "This dashboard gives you a complete view
   of your finances, including spending, budgets, goals, and account balances."
2. **Accounts** — targets the Accounts nav item.
3. **Import Statements** — "Import Bank Statements" / "Upload your bank statements and Fynora
   automatically organizes your transactions. No manual entry required."
4. **Transactions** — "Every Transaction Explained" / "Search, filter, categorize, and understand
   every transaction in one place. See exactly where your money is going."
5. **Budgets** — "Stay Within Budget" / "Create monthly budgets and track your progress in real
   time. Get notified before you overspend."
6. **Goals** — "Achieve Your Financial Goals" / "Whether it's an emergency fund, vacation, or new
   car, Fynora helps you stay on track."
7. **Insights** — "Discover Spending Patterns" / "Fynora automatically identifies trends and
   spending habits so you can make smarter financial decisions."

Order rationale: Accounts → Data (Import) → Transactions → Planning (Budgets/Goals) → Analysis
(Insights) — mirrors the order a new user would actually act in, rather than jumping straight
from Import to Transactions.

### Screen 4 — Success
- Title: "You're Ready to Go 🚀"
- Content: "Start by importing your first bank statement or connecting an account. The more data
  you add, the smarter Fynora becomes."
- Next steps preview (the same 6 checklist items shown unchecked, so the user knows what's ahead
  before they ever see the dashboard widget): Complete profile · Import first statement · Review
  transactions · Create a budget · Create a goal · View insights. Read-only here — no backend
  call from this screen; the real checklist state is fetched once the user reaches the dashboard.
- Buttons, in priority order: **Import Statement** (primary, routes to the existing
  `/app/import`) · Connect Account (secondary, routes to the existing manual-entry
  `/app/accounts` — Fynora has no OAuth bank linking today (§2), so this button adds an account
  by hand, same as `Setup.tsx` always has) · Go to Dashboard (tertiary, routes to `/app`). Import
  is primary because manual account linking isn't yet a retention feature in this product — a
  statement import is the real first action that produces value.

### Getting-Started checklist (persists on the dashboard until 6/6)
"Getting Started — N of 6 completed" with a progress bar (`N/6`, rounded %):
- ✅/⬜ Complete your profile
- ✅/⬜ Import first statement
- ✅/⬜ Review transactions
- ✅/⬜ Create a budget
- ✅/⬜ Create a goal
- ✅/⬜ View insights

### Settings
New entry, both platforms: **Retake Product Tour** — "Replay the onboarding experience anytime."

## 4. Scope decisions (from brainstorming)

- **Financial Focus answers are captured but drive nothing in v1.** No dashboard reordering, no
  conditional tour content, no recommendations engine. Stored now so later personalization (email
  nudges, empty states, dashboard widgets) doesn't require touching the onboarding flow again.
- **No generic onboarding-tracking subsystem.** Deliberately not building a configurable
  "checklist engine" or DB-driven step list. The tour's 7 steps are a static array in frontend
  code; the checklist's 6 items are a fixed, hardcoded set on both the frontend and backend.
- **Checklist state is derived wherever it already exists, tracked explicitly only where it
  doesn't.** 4 of 6 items reuse rows that already exist for other reasons (an `ImportJob`, a
  `Budget`, a `Goal`, profile fields on `User`) — no new writes for those. Only "Review
  transactions" and "View insights" get a new event table, because nothing else in the system
  records "the user opened this screen."
- **The new event table has a fixed, closed enum — not a generic string.** Two valid values only:
  `REVIEW_TRANSACTIONS`, `VIEW_INSIGHTS`. Not designed to be extended into a general analytics
  events table; if more screens need this later, that's a new decision, not an assumed default.
- **No admin-portal visibility into onboarding data in v1.** No aggregate view of Financial Focus
  answers or checklist completion rates. Out of scope until asked for.

## 5. Data model

### `users` — one new column
```sql
ALTER TABLE users ADD COLUMN onboarding_completed_at TIMESTAMPTZ;
UPDATE users SET onboarding_completed_at = now() WHERE onboarding_completed_at IS NULL;
```
Null = tour not yet seen (new users only, since the same migration backfills everyone existing).
Set once by completing or skipping the tour; cleared back to `NULL` only by "Retake Product Tour."

### `user_financial_focus` — new table
```sql
CREATE TABLE user_financial_focus (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    focus_key VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, focus_key)
);
CREATE INDEX idx_user_financial_focus_user_id ON user_financial_focus(user_id);
```
`focus_key` validated server-side against a fixed Java enum (`TRACK_SPENDING`,
`MANAGE_BUDGETS`, `SAVE_FOR_GOAL`, `SEE_ALL_ACCOUNTS`, `IMPROVE_HABITS`, `REDUCE_DEBT`,
`EXPLORING`) — not free text. Submitting a new set replaces the old one (delete + insert), so the
question can be answered exactly once but the row set always reflects the latest answer if the
endpoint is ever called again (e.g. a retry). `focus_key` is a stable backend identifier only —
the chip labels in §3 (e.g. "Track my spending") are frontend copy attached to each key at render
time, never persisted. Wording can change freely without a migration or a data backfill.

### `user_checklist_events` — new table, closed enum
```sql
CREATE TABLE user_checklist_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    item_key VARCHAR(30) NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, item_key)
);
```
Valid `item_key` values: `REVIEW_TRANSACTIONS`, `VIEW_INSIGHTS` — enforced in the service layer
against a fixed Java enum, same defensiveness as `focus_key`. Insert is idempotent (unique
constraint + "insert if absent" in the service), fired once from the frontend the first time the
Ledger or Insights screen mounts for a user who hasn't completed that item yet.

## 6. Backend API

New `OnboardingController` at `/api/v1/onboarding/*` (mirrors how this codebase already separates
`AdminUserController` from `UserController` for a distinct concern):

- `GET /api/v1/onboarding/status` → `{ onboardingCompleted: boolean, financialFocus: string[] }`
- `POST /api/v1/onboarding/financial-focus` → body `{ focusKeys: string[] }` (empty array allowed —
  "Skip for Now" / no chips selected); replaces the user's focus set.
- `POST /api/v1/onboarding/complete` → sets `onboarding_completed_at = now()` if null; idempotent.
- `POST /api/v1/onboarding/reset` → sets `onboarding_completed_at = NULL`; backs "Retake Product
  Tour."
- `GET /api/v1/onboarding/checklist` → `{ items: [{ key, completed }], completedCount, totalCount:
  6 }`. Computes `COMPLETE_PROFILE`/`IMPORT_STATEMENT`/`CREATE_BUDGET`/`CREATE_GOAL` live from
  existing tables and reads `REVIEW_TRANSACTIONS`/`VIEW_INSIGHTS` from `user_checklist_events`.
  Derivation rules for the 4 computed items (fixed at implementation time against whatever the
  `User`/profile fields actually are — to be confirmed against the entity, not assumed):
  `COMPLETE_PROFILE` = name set and email verified and (phone verified or no phone on file);
  `IMPORT_STATEMENT` = at least one `ImportJob`/`StatementImport` row exists; `CREATE_BUDGET` =
  at least one `Budget` row exists; `CREATE_GOAL` = at least one `Goal` row exists.
- `POST /api/v1/onboarding/checklist/{itemKey}/complete` → `itemKey` restricted to
  `REVIEW_TRANSACTIONS`/`VIEW_INSIGHTS` only (400 for anything else — the other 4 items are
  derived, never explicitly settable); idempotent insert into `user_checklist_events`.

## 7. Frontend / mobile architecture

**Interception point.** `onboardingCompleted` rides on the same channel `phoneVerified` already
uses on both platforms, rather than a separate boot-time round trip — `AuthResponse` (login/
register/Google/Apple) and `UserSettingsDto` (`GET /users/me`, read on web's silent-refresh
bootstrap and on any manual re-fetch) both gain the field, exactly where `phoneVerified` already
lives in each. If `onboardingCompleted` is false, the onboarding flow renders instead of the
requested route (web: a check inside `Protected` in `App.tsx`; mobile: the equivalent check in
`RootNavigator.tsx`) — same gate that already exists for "unverified" states, extended with one
more condition. `GET /api/v1/onboarding/status` still exists, but only as the read call the
onboarding flow itself uses to resume `financialFocus` state, not as the routing signal.

**Tour engine — built in-house, no new dependency.** Neither platform has a tour library today
(§2), and adopting one means fighting its default styling to match Fynora's existing design
system. A small `TourOverlay` component takes an ordered `{ targetRef, title, body }[]`, dims the
screen, cuts a spotlight hole around the measured target, and renders a tooltip with
Next/Back/Skip.

Web's `Sidebar` already renders every one of the 7 tour targets (Dashboard, Accounts, Import,
Transactions, Budgets, Goals, Insights) as persistent `NavLink`s regardless of which page is
active, so the tour never navigates — it spotlights sidebar links in place over the Dashboard.
Mobile's bottom tab bar is narrower (`Home`/`Transactions`/`Import`/`More` only —
`mobile/src/navigation/AppTabs.tsx`); Accounts/Budgets/Goals/Insights live as rows inside the
`More` tab's own list screen (`MoreScreen.tsx`), not as separate top-level tabs. The mobile tour
therefore navigates the tab bar as it advances — Home → More (staying on `MoreScreen` for the
Accounts/Budgets/Goals/Insights steps, spotlighting each row in turn) → Import → Transactions,
matching the step order in §3 — rather than assuming tab-bar parity with web's sidebar.
- Web: `getBoundingClientRect()` on the target ref, an absolutely-positioned SVG mask.
- Mobile: `measureInWindow()` on the target ref inside `onLayout`, an `react-native-svg` mask
  (already a dependency — no new library needed there either).

**Getting-started checklist widget.** A dashboard card, both platforms, fetching
`GET /api/v1/onboarding/checklist`; hidden once `completedCount === totalCount`. The Ledger and
Insights screens each fire `POST /api/v1/onboarding/checklist/{key}/complete` on a **1.5s dwell
timer** started on mount (not on mount itself), cleared on unmount — so a screen opened and
immediately left doesn't get credited — if that item isn't already complete (checked against the
same fetched status, no extra round trip
needed to decide whether to fire).

## 8. Testing

- Backend: `OnboardingService` unit tests — backfill idempotency, focus-set replace semantics,
  checklist-item idempotent insert, the four derived items computed correctly from
  `ImportJob`/`Budget`/`Goal`/`User` fixtures. A migration IT confirming existing users backfill
  `onboarding_completed_at` and none are left `NULL`.
- Frontend/mobile: component tests for `TourOverlay` step advancement (Next/Back/Skip, spotlight
  target changes per step) and the Financial Focus multi-select (including the "Just exploring"
  exclusivity rule). An integration test confirming a fresh user is routed into onboarding and a
  returning one is not.

## 9. Explicitly out of scope (v1)

No personalization or dashboard changes driven by the Financial Focus answer; no admin-portal
view of onboarding data; no A/B testing of copy; no server-driven/configurable step list for
either the tour or the checklist.
