# Mobile UX Excellence — Phase 3 (P2): Haptics, Animated Numbers, Progressive Charts, Shared Element Transitions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add haptic feedback at real success/warning/selection touchpoints, smoothly animate balance/budget number transitions via `react-native-reanimated`, add a progressive draw-in reveal to the three existing hand-rolled SVG charts, and evaluate (without forcing an implementation) shared element transitions for screens that don't exist yet.

**Architecture:** A thin `src/lib/haptics.ts` wrapper funnels every haptic touchpoint through four named functions, so behavior and tests live in one place. `react-native-reanimated` 4.x is added as this app's first animation-capable native dependency (required, not preferred, since RN 0.86.2 has no old-architecture path — Reanimated 3 refuses to run without `react-native-worklets`, which Reanimated 4 requires); `AnimatedNumber` wraps a non-editable `TextInput` whose `text`/`defaultValue` props are driven by `useAnimatedProps` for a jump-free transition. Chart reveal reuses the same Reanimated primitives via `strokeDashoffset` animation on top of the existing `react-native-svg`-based `DonutChart`/`CashFlowChart`/`TrendChart` — no charting library is added, honoring this codebase's documented decision to hand-roll charts. Shared element transitions is evaluated only: no candidate destination screen exists yet, so no code changes are made for it in this plan.

**Tech Stack:** React Native 0.86.2 / React 19.2.8 (Expo SDK `~57.0.9`), `expo-haptics` (new), `react-native-reanimated` 4.x + `react-native-worklets` (new), `react-native-svg` (already installed, unchanged), Jest 29 + `@testing-library/react-native` 13.3.3.

**Spec:** User-provided "Mobile UX Excellence Initiative" brief (Phase 3 / P2 items: Haptic Feedback, Animated Numbers, Progressive Chart Rendering, Shared Element Transitions), scoped against a live codebase survey and a locked decision from conversation: animated numbers use `react-native-reanimated`, not RN's own `Animated` API.

## Global Constraints

- One P2-adjacent item ("Validate Ledger Performance") is **already merged to `main`** (PR #623 / `ebf68f57`) — `FlatList` virtualization tuning on `LedgerScreen.tsx`, deliberately without `getItemLayout`. NOT part of this plan.
- **LOCKED DECISION:** Animated numbers use `react-native-reanimated`, not RN's own `Animated` API — do not substitute.
- No new charting library — every chart-reveal task builds on the existing hand-rolled `react-native-svg` components, per this codebase's own documented reasoning (`DonutChart.tsx`'s top comment: avoiding a charting dependency "to re-validate against each Expo SDK bump").
- No new dependency for shared element transitions — Item D is evaluation-only; no `TransactionDetail`/`BudgetDetail`/`AccountDetail` screen exists to transition between yet, so no code changes are made for it.
- Reanimated is native code: a rebuilt custom dev client is required before Task 8+ works on-device (a JS-only reload is not enough). The app already requires a custom dev client (Firebase's native modules force that); this just means rebuilding the existing one.
- Test tooling: Jest 29, `@testing-library/react-native` 13.3.3, `npm test -- <path>` from `mobile/`. Reanimated ships its own documented Jest test-mode setup (`setUpTests()`) — required before any test renders a Reanimated-backed component.
- No chart component in this codebase has ever had a rendered-component test (only `chartGeometry.ts`'s pure functions are tested), and Reanimated's own docs state SVG props are untestable under its Jest mode — chart-reveal tasks (12-15) therefore carry geometry-level tests only, not rendered-component tests. This is a stated scope boundary, not an oversight.

---

Baseline findings from reading the current tree before writing this plan (all confirmed against the worktree, Expo SDK `~57.0.9`, React Native `0.86.2`, React `19.2.8`):

- `expo-haptics` is not a dependency anywhere (`package.json`, `package-lock.json` both clean).
- `react-native-reanimated` is not a dependency anywhere, and **the project has no `babel.config.js` at all** — it currently relies on Expo/Metro's implicit `babel-preset-expo` default (confirmed against Expo's own docs: "if no custom Babel configuration is required, the file can be safely removed from the project" — the inverse of what's about to become true here). `babel-preset-expo` (`~57.0.5`) is already present transitively (used by `jest-expo`/`expo`), so it's available to reference without adding it as a new direct dependency.
- RN `0.86.2` has no old-architecture path left to opt into, so this app is New-Architecture-only regardless of any `newArchEnabled` flag (`app.config.ts` sets none). That mandates **Reanimated 4.x**, not 3.x — Reanimated 3 refuses to run against `react-native-worklets`, and Reanimated 4 requires it. Its own babel plugin also moved packages: `'react-native-reanimated/plugin'` (3.x) is now `'react-native-worklets/plugin'` (4.x, still last in the array). `react-native-worklets`' published compatibility table (as of the docs read for this plan) lists RN `0.81`–`0.85` for its `0.7.x`/`0.8.x` lines — RN `0.86.2` isn't yet a listed row, so Task 7 below includes a pre-flight check rather than assuming the newest line covers it.
- The app already requires a custom dev client, not Expo Go (`app.config.ts`'s own comment: Firebase's native modules force this already, and `expo-dev-client` is already a dependency). Adding Reanimated doesn't newly require a dev client — but it does require **rebuilding** the existing one, since it's new native code.
- `expo-haptics` and `react-native-reanimated` both need a `jest.mock`/test hookup added to `src/test/setup.ts`, matching this repo's existing convention for every other native module with no JS implementation under the test runner (`expo-screen-capture`, `expo-device`, etc. are already handled this way there).
- `transactionsApi.create` exists in `src/api/endpoints.ts:135` but **is called from nowhere in the mobile app** — there is no manual "add transaction" screen; transactions only enter via import. The brief's "transaction saved" haptic has no real call site yet. Rather than invent one, Item A below covers the two real success points that exist (import confirmation, budget creation) and calls this gap out explicitly instead of papering over it.
- `LedgerScreen.tsx`'s long-press-to-delete is real (`onLongPress={() => confirmDelete(t)}` at line 200), confirming the brief's example.
- `OptionPickerModal.tsx` is the one shared category-picker component behind both `ImportScreen.tsx`'s and `BudgetsScreen.tsx`'s category pickers — a single wiring point covers "category selection" for both flows.
- No `TransactionDetail`, `BudgetDetail`, or `AccountDetail` screen exists anywhere (`src/screens/`, `src/navigation/AppTabs.tsx`, `src/navigation/RootNavigator.tsx`, `src/navigation/types.ts` all checked). `LedgerScreen` rows, `BudgetsScreen` cards, and `AccountsScreen` rows are all non-navigating today (`AccountsScreen`'s only row `onPress` is `toggleRevealed`, a balance-masking toggle, not navigation). This confirms Item D's premise directly from the repo rather than by assertion.
- `DashboardScreen.test.tsx` contains **load-bearing regression tests** (a real historical "two different totals shown for one number" bug) that cross-check the Expenses KPI's rendered text against `DonutChart`'s centre label via `getAllByText('₹35,500').length >= 2` (lines 188, 201, 234, 247). Converting the KPI to `AnimatedNumber` moves that value out of `<Text>` into a `TextInput`'s `defaultValue` prop, which `getAllByText` can no longer see. Task 9 below rewrites those four assertions to check the same invariant through the new rendering path instead of quietly breaking or dodging them.

---

## Item A — Haptic Feedback

### Task 1: Add `expo-haptics` dependency

**Files:**
- Modify: `package.json`, `package-lock.json` (via install, not hand-edited)

**Interfaces:**
- Consumes: nothing
- Produces: the `expo-haptics` package, importable as `import * as Haptics from 'expo-haptics'` by Task 2

- [ ] **Step 1: Manual verification setup** — no automated test is meaningful for a dependency-manifest change by itself; this task is exercised for real once Task 2's unit tests run against the real package.
```bash
cd /Users/sid/Downloads/finora/.claude/worktrees/mobile-ux-excellence/mobile
npx expo install expo-haptics
```
This resolves the exact patch version Expo SDK 57's bundled-native-module manifest blesses (every other `expo-*` package in `package.json` is pinned the same way, e.g. `"expo-screen-capture": "~57.0.2"`) — do not hand-pick a version number.
- [ ] **Step 2: Verify** — confirm the entry landed in `package.json` on the `~57.0.x` line and that `npx tsc --noEmit` (via `npm run typecheck`) still passes with no new errors.
- [ ] **Step 3: N/A** (no implementation code in this task)
- [ ] **Step 4: N/A**
- [ ] **Step 5: Commit** — `git add package.json package-lock.json` / `git commit -m "chore(mobile): add expo-haptics"`

---

### Task 2: Haptics wrapper module

**Files:**
- Create: `src/lib/haptics.ts`
- Create: `src/lib/haptics.test.ts`
- Modify: `src/test/setup.ts` (add the `expo-haptics` native-module mock, alongside the existing ones)

**Interfaces:**
- Consumes: `expo-haptics` (Task 1)
- Produces: `hapticSuccess()`, `hapticWarning()`, `hapticSelection()`, `hapticImpact()` — the four functions every later task in Item A calls instead of touching `expo-haptics` directly

- [ ] **Step 1: Write the failing test**
```ts
// src/lib/haptics.test.ts
import * as Haptics from 'expo-haptics';
import { hapticImpact, hapticSelection, hapticSuccess, hapticWarning } from './haptics';

const haptics = Haptics as jest.Mocked<typeof Haptics>;

describe('haptics', () => {
  it('fires a success notification', () => {
    hapticSuccess();
    expect(haptics.notificationAsync).toHaveBeenCalledWith(Haptics.NotificationFeedbackType.Success);
  });

  it('fires a warning notification', () => {
    hapticWarning();
    expect(haptics.notificationAsync).toHaveBeenCalledWith(Haptics.NotificationFeedbackType.Warning);
  });

  it('fires a selection change', () => {
    hapticSelection();
    expect(haptics.selectionAsync).toHaveBeenCalledTimes(1);
  });

  it('fires a medium impact', () => {
    hapticImpact();
    expect(haptics.impactAsync).toHaveBeenCalledWith(Haptics.ImpactFeedbackStyle.Medium);
  });
});
```
- [ ] **Step 2: Run test to verify it fails** — `npm test -- src/lib/haptics.test.ts` — Expected: FAIL, `Cannot find module './haptics'` (and, until Step 2b below lands, `expo-haptics` has no JS implementation under the runner and would throw on import).

Add the mock this test (and every later screen test in Item A) needs, in `src/test/setup.ts`, immediately after the existing `expo-screen-capture` mock block (around line 209):
```ts
// expo-haptics is a native module with no JS implementation under the test runner -- same
// posture as expo-screen-capture and expo-device above. Every haptic touchpoint in the app calls
// through src/lib/haptics.ts, so stubbing the underlying three Expo APIs here is enough for both
// haptics.test.ts and any screen test asserting a particular haptic fired.
jest.mock('expo-haptics', () => ({
  notificationAsync: jest.fn(async () => {}),
  impactAsync: jest.fn(async () => {}),
  selectionAsync: jest.fn(async () => {}),
  NotificationFeedbackType: { Success: 'success', Warning: 'warning', Error: 'error' },
  ImpactFeedbackStyle: { Light: 'light', Medium: 'medium', Heavy: 'heavy', Rigid: 'rigid', Soft: 'soft' },
}));
```
- [ ] **Step 3: Write minimal implementation**
```ts
// src/lib/haptics.ts
import * as Haptics from 'expo-haptics';

/**
 * Every haptic touchpoint in the app funnels through here rather than calling `expo-haptics`
 * directly: one place to change the mapping from "kind of feedback" to Expo's three underlying
 * APIs (impact/notification/selection), and one place a test can assert against instead of every
 * screen that fires one. See src/test/setup.ts for the module-level jest.mock.
 *
 * expo-haptics' promises resolve once the native call is dispatched, not once it's felt, and
 * nothing here awaits them -- a haptic is a fire-and-forget side effect of a UI event, not
 * something a caller should block on or fail over if it rejects.
 */

/** A flow completed: an import finished, a budget saved. */
export function hapticSuccess(): void {
  void Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
}

/**
 * A validation error stopped a submit. Deliberately `Warning`, not `Error` -- nothing failed on
 * the server, the form just isn't complete yet, and iOS's own semantics keep those separate.
 */
export function hapticWarning(): void {
  void Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
}

/** Picking one option among many -- a category, a filter -- where the feedback confirms the tap
 * registered rather than announcing an outcome. */
export function hapticSelection(): void {
  void Haptics.selectionAsync();
}

/** A physical acknowledgement for a gesture that isn't a simple tap -- e.g. a long-press that is
 * about to open a destructive confirmation. Medium matches expo-haptics' own default style. */
export function hapticImpact(): void {
  void Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
}
```
- [ ] **Step 4: Run test to verify it passes** — `npm test -- src/lib/haptics.test.ts` — Expected: PASS, all four assertions green.
- [ ] **Step 5: Commit** — `git add src/lib/haptics.ts src/lib/haptics.test.ts src/test/setup.ts` / `git commit -m "feat(mobile): add haptics wrapper around expo-haptics"`

---

### Task 3: Success haptic on import completion

**Files:**
- Modify: `src/screens/import/ImportScreen.tsx:275-277`
- Test: no dedicated automated test for this call site — see justification below

**Interfaces:**
- Consumes: `hapticSuccess` (Task 2)
- Produces: nothing new consumed by later tasks

`ImportScreen.tsx` currently has **no test file at all** (`src/screens/import/` only has `StagedRowCard.test.tsx`). Building one from scratch to exercise `confirmImport()`'s success path would require mocking the full upload/review harness (`pickStatement`, `importApi.stageCsv`/`stagePdf`, `categoriesApi.list`, `accountsApi.list`, `importApi.confirm`, `statementImportsApi.confirmReimport`, `useRoute`) — a much larger undertaking than this one-line haptic wire, and out of scope for this task. The underlying `hapticSuccess()` call itself is already unit-tested (Task 2). Manual verification: run the app, complete an import through to the summary screen, confirm a success haptic fires on a physical device (haptics don't simulate on the iOS Simulator/most Android emulators).

- [ ] **Step 1: Manual verification setup** — read the real success branch first (already done above): `confirmImport()`'s try block, `src/screens/import/ImportScreen.tsx:274-277`, currently:
```tsx
      setSummary(result);
      setStep('summary');
      invalidateFinancialData(queryClient);
```
- [ ] **Step 2: N/A** (no automated test to fail)
- [ ] **Step 3: Write minimal implementation**
```tsx
import { hapticSuccess } from '../../lib/haptics';
```
add near the top import block (after the `invalidateFinancialData` import), then change the success branch to:
```tsx
      setSummary(result);
      setStep('summary');
      invalidateFinancialData(queryClient);
      // The moment the import actually lands, not when the button is pressed -- firing before
      // the request resolves would celebrate a network failure too.
      hapticSuccess();
```
- [ ] **Step 4: Manual verification** — on a physical device: Import tab → pick a CSV → review → "Import N transactions" → feel a success haptic exactly as the summary screen appears.
- [ ] **Step 5: Commit** — `git add src/screens/import/ImportScreen.tsx` / `git commit -m "feat(mobile): success haptic when an import completes"`

---

### Task 4: Success + warning haptics on budget creation

**Files:**
- Modify: `src/screens/BudgetsScreen.tsx:49-78`
- Modify: `src/screens/BudgetsScreen.test.tsx` (existing file — add mock + assertions)

**Interfaces:**
- Consumes: `hapticSuccess`, `hapticWarning` (Task 2)
- Produces: nothing new consumed by later tasks

- [ ] **Step 1: Write the failing test** — add to the existing `src/screens/BudgetsScreen.test.tsx`, right after its current `jest.mock('../api/endpoints', ...)` block:
```tsx
jest.mock('../lib/haptics', () => ({
  hapticSuccess: jest.fn(),
  hapticWarning: jest.fn(),
}));
```
and import at the top:
```tsx
import { hapticSuccess, hapticWarning } from '../lib/haptics';
```
Then extend three existing tests with new assertions (leaving everything else in each test unchanged):
```tsx
  it('saves a budget for the category chosen from the picker', async () => {
    renderScreen();
    await screen.findByText('₹6,000 left this month');

    fireEvent.press(screen.getByLabelText('Choose a category'));
    await settle();
    fireEvent.press(screen.getByRole('button', { name: 'Groceries' }));
    await settle();
    fireEvent.changeText(screen.getByLabelText(/Monthly limit/i), '12000');
    fireEvent.press(screen.getByText('Set Budget'));
    await settle();

    await waitFor(() => expect(api.upsert).toHaveBeenCalledWith('Groceries', 12000));
    expect(hapticSuccess).toHaveBeenCalledTimes(1);
  });

  it('refuses to save without a category', async () => {
    renderScreen();
    await screen.findByText('₹6,000 left this month');

    fireEvent.changeText(screen.getByLabelText(/Monthly limit/i), '12000');
    fireEvent.press(screen.getByText('Set Budget'));
    await settle();

    expect(api.upsert).not.toHaveBeenCalled();
    expect(screen.getByText('Pick a category first.')).toBeTruthy();
    expect(hapticWarning).toHaveBeenCalledTimes(1);
  });

  it('refuses a limit that is not a positive number', async () => {
    renderScreen();
    await screen.findByText('₹6,000 left this month');

    fireEvent.press(screen.getByLabelText('Choose a category'));
    await settle();
    fireEvent.press(screen.getByRole('button', { name: 'Groceries' }));
    await settle();

    for (const bad of ['0', '-1', 'abc']) {
      fireEvent.changeText(screen.getByLabelText(/Monthly limit/i), bad);
      fireEvent.press(screen.getByText('Set Budget'));
      await settle();
    }

    expect(api.upsert).not.toHaveBeenCalled();
    expect(hapticWarning).toHaveBeenCalledTimes(3);
  });
```
- [ ] **Step 2: Run test to verify it fails** — `npm test -- src/screens/BudgetsScreen.test.tsx` — Expected: FAIL on the three new `expect(hapticSuccess/hapticWarning)` lines (`hapticSuccess`/`hapticWarning` never called — `save()` doesn't call them yet).
- [ ] **Step 3: Write minimal implementation** — in `src/screens/BudgetsScreen.tsx`, add the import:
```tsx
import { hapticSuccess, hapticWarning } from '../lib/haptics';
```
and change `save()` (lines 49-78):
```tsx
  async function save() {
    const amount = parsePositiveAmount(limit);
    if (!category) {
      setError('Pick a category first.');
      hapticWarning();
      return;
    }
    if (amount === null) {
      setError('Monthly limit must be a number greater than zero.');
      hapticWarning();
      return;
    }
    setError(null);
    await singleFlight(async () => {
      setSaving(true);
      try {
        await budgetsApi.upsert(category, amount);
        setCategory(null);
        setLimit('');
        confirmSaved();
        hapticSuccess();
        void queryClient.invalidateQueries({ queryKey: ['budgets'] });
        void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
      } catch (e) {
        setError(toUserMessage(e, 'Could not save this budget. Try again.'));
      } finally {
        setSaving(false);
      }
    });
  }
```
- [ ] **Step 4: Run test to verify it passes** — `npm test -- src/screens/BudgetsScreen.test.tsx` — Expected: PASS, including the pre-existing tests (unaffected by this change).
- [ ] **Step 5: Commit** — `git add src/screens/BudgetsScreen.tsx src/screens/BudgetsScreen.test.tsx` / `git commit -m "feat(mobile): success/warning haptics on budget save"`

---

### Task 5: Selection haptic on category picking

**Files:**
- Modify: `src/components/OptionPickerModal.tsx:42-52`
- Create: `src/components/OptionPickerModal.test.tsx`

**Interfaces:**
- Consumes: `hapticSelection` (Task 2)
- Produces: nothing new consumed by later tasks

This is the shared picker sheet behind both `ImportScreen.tsx`'s category picker and `BudgetsScreen.tsx`'s category picker (`OptionPickerModal`'s own file comment: "Replaces the web's inline `<select>`... the import review's category dropdown, the budget form's category, the reports month picker"). One wiring point covers the brief's "category selection" for both real flows without touching either screen.

- [ ] **Step 1: Write the failing test**
```tsx
// src/components/OptionPickerModal.test.tsx
import { fireEvent, render, screen } from '@testing-library/react-native';
import { OptionPickerModal } from './OptionPickerModal';
import { hapticSelection } from '../lib/haptics';

jest.mock('../lib/haptics', () => ({ hapticSelection: jest.fn() }));

describe('OptionPickerModal', () => {
  it('fires a selection haptic when an option is tapped', () => {
    const onSelect = jest.fn();
    render(
      <OptionPickerModal
        visible
        title="Category"
        options={['Groceries', 'Dining']}
        selected={null}
        onSelect={onSelect}
        onClose={jest.fn()}
      />
    );

    fireEvent.press(screen.getByRole('button', { name: 'Groceries' }));

    expect(hapticSelection).toHaveBeenCalledTimes(1);
    expect(onSelect).toHaveBeenCalledWith('Groceries');
  });
});
```
- [ ] **Step 2: Run test to verify it fails** — `npm test -- src/components/OptionPickerModal.test.tsx` — Expected: FAIL, `hapticSelection` never called.
- [ ] **Step 3: Write minimal implementation** — add the import to `src/components/OptionPickerModal.tsx`:
```tsx
import { hapticSelection } from '../lib/haptics';
```
and change the option `Pressable`'s `onPress` (lines 42-52):
```tsx
              <Pressable
                onPress={() => {
                  hapticSelection();
                  onSelect(item);
                }}
                accessibilityRole="button"
                accessibilityState={{ selected: isSelected }}
                style={[styles.option, { borderBottomColor: c.border }]}
                android_ripple={{ color: c.border }}
              >
```
- [ ] **Step 4: Run test to verify it passes** — `npm test -- src/components/OptionPickerModal.test.tsx` — Expected: PASS.
- [ ] **Step 5: Commit** — `git add src/components/OptionPickerModal.tsx src/components/OptionPickerModal.test.tsx` / `git commit -m "feat(mobile): selection haptic in the shared option picker"`

---

### Task 6: Impact haptic on the Ledger's long-press-to-delete

**Files:**
- Modify: `src/screens/LedgerScreen.tsx:63-73`
- Modify: `src/screens/LedgerScreen.test.tsx` (existing file — add mock + one test)

**Interfaces:**
- Consumes: `hapticImpact` (Task 2)
- Produces: nothing new consumed by later tasks

- [ ] **Step 1: Write the failing test** — add to `src/screens/LedgerScreen.test.tsx`, alongside its existing `jest.mock('../api/endpoints', ...)`:
```tsx
jest.mock('../lib/haptics', () => ({ hapticImpact: jest.fn() }));
```
and import at the top:
```tsx
import { hapticImpact } from '../lib/haptics';
```
Add a new test using the file's existing `renderScreen`/`txn`/`page` helpers:
```tsx
  it('acknowledges the long press with an impact haptic before offering to delete', async () => {
    transactions.search.mockResolvedValue(page([txn()]));
    renderScreen();
    await waitFor(() => screen.getByText('Grocery run'));

    fireEvent(screen.getByRole('button', { name: /Grocery run/ }), 'longPress');

    expect(hapticImpact).toHaveBeenCalledTimes(1);
  });
```
(`waitFor` and `fireEvent` are already imported in this file per its existing usage; if `waitFor` isn't already in the import list, add it alongside `fireEvent, render, screen`.)
- [ ] **Step 2: Run test to verify it fails** — `npm test -- src/screens/LedgerScreen.test.tsx` — Expected: FAIL, `hapticImpact` never called.
- [ ] **Step 3: Write minimal implementation** — add the import to `src/screens/LedgerScreen.tsx`:
```tsx
import { hapticImpact } from '../lib/haptics';
```
and change `confirmDelete` (lines 63-73):
```tsx
  function confirmDelete(t: Transaction) {
    // Before the alert, not after a choice is made -- the same convention iOS's own system apps
    // use for a press that's about to open a destructive confirmation, so the gesture itself
    // feels acknowledged rather than only its eventual outcome.
    hapticImpact();
    // Alert.alert replaces the web's window.confirm(), which doesn't exist in React Native.
    Alert.alert(
      'Delete transaction?',
      `"${t.description || t.merchant}" (${fmtCurrency(t.amount)}) can't be recovered.`,
      [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Delete', style: 'destructive', onPress: () => void handleDelete(t) },
      ]
    );
  }
```
- [ ] **Step 4: Run test to verify it passes** — `npm test -- src/screens/LedgerScreen.test.tsx` — Expected: PASS, including all pre-existing tests in the file.
- [ ] **Step 5: Commit** — `git add src/screens/LedgerScreen.tsx src/screens/LedgerScreen.test.tsx` / `git commit -m "feat(mobile): impact haptic on the ledger's long-press-to-delete"`

---

## Item B — Animated Numbers

### Task 7: Add `react-native-reanimated` + babel/jest wiring

**Files:**
- Modify: `package.json`, `package-lock.json` (via install)
- Create: `babel.config.js` (project has none today — see baseline findings above)
- Modify: `src/test/setup.ts` (add Reanimated's test setup call)

**Interfaces:**
- Consumes: nothing
- Produces: `react-native-reanimated`'s exports (`useSharedValue`, `useAnimatedProps`, `useDerivedValue`, `withTiming`, `withDelay`, `Easing`, `Animated.createAnimatedComponent`), usable by Task 8 (`AnimatedNumber`) and, later, Item C's Task 12 (`ChartReveal`)

**LOCKED DECISION** (per instruction): `react-native-reanimated`, not RN's own `Animated` API.

**Version**: RN `0.86.2` has no old-architecture path, so Reanimated **4.x** is required (Reanimated 3 explicitly refuses to run with `react-native-worklets` installed, and Reanimated 4 requires it as a dependency — the two lines are mutually exclusive, not a preference). Reanimated 4 also moved its babel plugin into a separate package: `'react-native-reanimated/plugin'` (the 3.x name) becomes `'react-native-worklets/plugin'` in 4.x, still required to be **last** in the plugins array.

- [ ] **Step 1: Manual verification setup** — pre-flight and install, in order:
```bash
cd /Users/sid/Downloads/finora/.claude/worktrees/mobile-ux-excellence/mobile
# Pre-flight: react-native-worklets' published compatibility table (0.7.x/0.8.x lines) lists RN
# up to 0.85 as of this writing; this app is on RN 0.86.2, one point past the newest listed row.
# Check the current table before installing -- if 0.86 genuinely isn't covered yet, that's a real
# blocker to raise with Sid before proceeding, not something to install through silently.
npm view react-native-worklets versions --json | tail -5
npx expo install react-native-reanimated react-native-worklets
```
This resolves the exact versions Expo SDK 57's bundled-native-module manifest blesses (at the time this plan was researched, that's the 4.1.x Reanimated line per its own published docs) rather than hand-picking numbers.

Create `babel.config.js` (there is none in this repo today — see baseline findings above for why that's been fine until now):
```js
module.exports = function (api) {
  api.cache(true);
  return {
    presets: ['babel-preset-expo'],
    plugins: [
      // Must be listed LAST -- Reanimated's worklets plugin has to run after every other
      // transform has finished rewriting the file, or it can miss code it needs to convert.
      // This project never needed a babel.config.js before Reanimated: Expo's Metro/Jest
      // tooling applies babel-preset-expo as an implicit default when no file is present, and
      // this is functionally identical to that default plus the one plugin Reanimated needs.
      'react-native-worklets/plugin',
    ],
  };
};
```

Add Reanimated's own documented test setup to `src/test/setup.ts`, near the top (before the native-module `jest.mock` calls, since this isn't a mock but a real setup call against the actual test-mode Reanimated runtime):
```ts
// Reanimated ships a real (non-native) implementation for use under Jest -- see
// https://docs.swmansion.com/react-native-reanimated/docs/guides/testing. AnimatedNumber
// (src/components/AnimatedNumber.tsx) and the chart reveal components in
// src/components/charts/ChartReveal.tsx both depend on this being called before any test that
// renders them.
require('react-native-reanimated').setUpTests();
```
- [ ] **Step 2: Verify** — `npm run typecheck` passes; `npm test` (full suite) still passes with no new failures, confirming the new babel config didn't change how anything else compiles.
- [ ] **Step 3: N/A** (no implementation code beyond the config files above)
- [ ] **Step 4: N/A**
- [ ] **Step 5: Commit** — `git add package.json package-lock.json babel.config.js src/test/setup.ts` / `git commit -m "chore(mobile): add react-native-reanimated 4.x + babel/jest wiring"`

**Native rebuild required before this works on-device**: Reanimated is native code. The app already needs a custom dev client (Firebase's native modules force that already — this isn't a new requirement), but the *existing* dev client binary must be rebuilt before Reanimated will load — a JS-only reload/OTA update is not enough for a new native module. Run `npx expo run:ios` / `npx expo run:ios --device` (or `eas build --profile development`) before testing Task 8+ on a simulator/device.

---

### Task 8: `AnimatedNumber` component

**Files:**
- Create: `src/components/AnimatedNumber.tsx`
- Create: `src/components/AnimatedNumber.test.tsx`

**Interfaces:**
- Consumes: `fmtCurrency` (`src/lib/format.ts:11-13`), `react-native-reanimated` (Task 7)
- Produces: `AnimatedNumber({ value: number; style?: StyleProp<TextStyle>; duration?: number; testID?: string })`, used by Tasks 9 and 10

- [ ] **Step 1: Write the failing test**
```tsx
// src/components/AnimatedNumber.test.tsx
import { render, screen, waitFor } from '@testing-library/react-native';
import { AnimatedNumber } from './AnimatedNumber';

describe('AnimatedNumber', () => {
  it('renders the formatted currency for its initial value', async () => {
    render(<AnimatedNumber value={82000} testID="balance" />);
    await waitFor(() => {
      expect(screen.getByTestId('balance').props.defaultValue).toBe('₹82,000');
    });
  });

  it('formats a negative value with the sign before the symbol, same as fmtCurrency', async () => {
    render(<AnimatedNumber value={-500} testID="balance" />);
    await waitFor(() => {
      expect(screen.getByTestId('balance').props.defaultValue).toBe('-₹500');
    });
  });

  it('settles on the new formatted value when the prop changes', async () => {
    const { rerender } = render(<AnimatedNumber value={1000} duration={50} testID="balance" />);
    await waitFor(() => expect(screen.getByTestId('balance').props.defaultValue).toBe('₹1,000'));

    rerender(<AnimatedNumber value={2000} duration={50} testID="balance" />);
    await waitFor(() => expect(screen.getByTestId('balance').props.defaultValue).toBe('₹2,000'));
  });
});
```
- [ ] **Step 2: Run test to verify it fails** — `npm test -- src/components/AnimatedNumber.test.tsx` — Expected: FAIL, `Cannot find module './AnimatedNumber'`.
- [ ] **Step 3: Write minimal implementation**
```tsx
// src/components/AnimatedNumber.tsx
import { useEffect } from 'react';
import { StyleSheet, TextInput, type StyleProp, type TextStyle } from 'react-native';
import Animated, { Easing, useAnimatedProps, useSharedValue, withTiming } from 'react-native-reanimated';
import { fmtCurrency } from '../lib/format';

const AnimatedTextInput = Animated.createAnimatedComponent(TextInput);

interface AnimatedNumberProps {
  value: number;
  style?: StyleProp<TextStyle>;
  /** Milliseconds for the transition. Short and eased, not sprung -- the brief's "no flashy
   * casino-style animation" rule rules out overshoot, so this is a plain ease-out, never a
   * bounce. */
  duration?: number;
  testID?: string;
}

/**
 * Smoothly transitions the displayed value when `value` changes, instead of a hard jump cut --
 * balances/totals ticking rather than flashing to a new number.
 *
 * On a non-editable TextInput rather than a <Text>: Reanimated's useAnimatedProps can only update
 * a native prop directly on the UI thread without a JS re-render, and TextInput's `text` prop is
 * the one built-in RN component prop it can drive that way (the same technique used by
 * Reanimated's own AnimatedCounter cookbook example). Content is otherwise a plain label: not
 * focusable, not editable, no cursor, no keyboard.
 *
 * No animation on first mount: the shared value initialises to the same value it's animating
 * towards, so the very first render shows the correct number immediately -- this only animates a
 * value that CHANGES after mount (a refresh, a save), never a count-up intro.
 */
export function AnimatedNumber({ value, style, duration = 400, testID }: AnimatedNumberProps) {
  const animated = useSharedValue(value);

  useEffect(() => {
    animated.value = withTiming(value, { duration, easing: Easing.out(Easing.cubic) });
  }, [value, duration, animated]);

  const animatedProps = useAnimatedProps(() => {
    // `text`/`defaultValue` aren't part of RN's public TextInputProps type, but both are real
    // native props TextInput accepts -- the cast mirrors Reanimated's own documented example.
    return {
      text: fmtCurrency(animated.value),
      defaultValue: fmtCurrency(animated.value),
    } as Partial<React.ComponentProps<typeof TextInput>>;
  });

  return (
    <AnimatedTextInput
      testID={testID}
      editable={false}
      pointerEvents="none"
      underlineColorAndroid="transparent"
      style={[styles.text, style]}
      animatedProps={animatedProps}
      accessibilityLabel={fmtCurrency(value)}
    />
  );
}

const styles = StyleSheet.create({
  text: { padding: 0, margin: 0 },
});
```
- [ ] **Step 4: Run test to verify it passes** — `npm test -- src/components/AnimatedNumber.test.tsx` — Expected: PASS. (Relies on Task 7's `setUpTests()` call in `src/test/setup.ts` — if these fail with a Reanimated runtime error, verify that call landed first.)
- [ ] **Step 5: Commit** — `git add src/components/AnimatedNumber.tsx src/components/AnimatedNumber.test.tsx` / `git commit -m "feat(mobile): add AnimatedNumber component on reanimated"`

---

### Task 9: Apply `AnimatedNumber` to Dashboard KPIs

**Files:**
- Modify: `src/screens/DashboardScreen.tsx:179-235`
- Modify: `src/screens/DashboardScreen.test.tsx` (existing file — see the regression-test conflict below)

**Interfaces:**
- Consumes: `AnimatedNumber` (Task 8)
- Produces: nothing new consumed by later tasks

**A real conflict discovered while reading the test file, not glossed over**: `DashboardScreen.test.tsx` has four assertions (lines 188, 201, 234, 247) built around a real historical bug — the Expenses KPI and `DonutChart`'s centre label once showed two different totals for the same number. They currently prove agreement via `screen.getAllByText('₹35,500').length >= 2`, matching both as plain `<Text>` nodes. Once the Expenses KPI renders through `AnimatedNumber` (a `TextInput`), its value lives in the `defaultValue` prop, invisible to `getAllByText` — those four assertions would start silently under-counting (`1`, not `2`+) rather than failing loudly, which is worse. This task rewrites them to check the same invariant against how each value actually renders now, not just Dashboard's production code.

Also: `adjustsFontSizeToFit`/`numberOfLines` on the current KPI `<Text>` (`DashboardScreen.tsx:217-219`) have no `TextInput` equivalent — `TextInput` has no `adjustsFontSizeToFit` prop at all. `numberOfLines={1}`'s effect is preserved for free (a non-`multiline` `TextInput` is already single-line), but the auto-shrink-to-fit behaviour is not. Accepted deliberately here: `fmtCurrency` rounds to whole rupees and the KPI card (`48%` width) has comfortable headroom at this font size for realistic balances; flagged in-code as a place to revisit if a real balance is ever reported clipping, rather than silently dropped.

- [ ] **Step 1: Write the failing test** — in `src/screens/DashboardScreen.test.tsx`, add a small helper near the top (after the existing `dimensionsGetSpy` declaration) and rewrite the four affected assertions:
```tsx
/**
 * The Expenses KPI renders through AnimatedNumber now -- a non-editable TextInput, so its
 * settled value lives in `defaultValue` (see AnimatedNumber's own doc comment) rather than in
 * text content getByText can see. These cross-checks against DonutChart's plain-Text centre
 * label predate that change; kept accurate by reading each source the way it actually renders.
 */
function expensesKpiValue(): string {
  return screen.getByTestId('kpi-Expenses').props.defaultValue as string;
}
```
Then replace, in the `'shows the whole period total in the centre, not just the slices that fit'` test (around line 188):
```tsx
    expect(screen.queryByText('₹34,000')).toBeNull();
    // The centre label is still a plain Text; the Expenses KPI now renders through AnimatedNumber
    // (see expensesKpiValue's own comment) -- both must agree on the true total.
    expect(screen.getByText('₹35,500')).toBeTruthy();
    expect(expensesKpiValue()).toBe('₹35,500');
```
in `'agrees with the Expenses KPI, which reads the same backend field'` (around line 201):
```tsx
    expect(screen.getByText('₹35,500')).toBeTruthy();
    expect(expensesKpiValue()).toBe('₹35,500');
```
in `'does not show two rows both labelled Other'` (around line 234, leaving the `₹8,500`/`₹5,500` legend assertions above it untouched — those are the donut legend, unaffected by this task):
```tsx
    // And the invariant that started all of this still holds.
    expect(screen.getByText('₹39,000')).toBeTruthy();
    expect(expensesKpiValue()).toBe('₹39,000');
```
and in `'is unaffected when every category already fits'` (around line 247):
```tsx
    expect(screen.getByText('₹25,000')).toBeTruthy();
    expect(expensesKpiValue()).toBe('₹25,000');
```
- [ ] **Step 2: Run test to verify it fails** — `npm test -- src/screens/DashboardScreen.test.tsx` — Expected: FAIL, `expensesKpiValue()` throws (`Unable to find an element with testID: kpi-Expenses` — the KPI grid doesn't render `AnimatedNumber` or set that `testID` yet).
- [ ] **Step 3: Write minimal implementation** — in `src/screens/DashboardScreen.tsx`, add the import:
```tsx
import { AnimatedNumber } from '../components/AnimatedNumber';
```
Change the `kpis` array (lines 179-184) to carry raw numbers instead of pre-formatted strings:
```tsx
  const kpis = [
    { label: 'Total Balance', value: summary.currentBalance, delta: null as number | null, invert: false },
    { label: 'Income', value: summary.monthlyIncome, delta: summary.incomeDeltaPct, invert: false },
    { label: 'Expenses', value: summary.monthlyExpense, delta: summary.expenseDeltaPct, invert: true },
    { label: 'Net Savings', value: summary.netCashFlow, delta: summary.netDeltaPct, invert: false },
  ];
```
Change the KPI grid rendering (lines 202-235) — the two edits are the `accessibilityLabel` (now formatting `k.value` itself, since it's no longer pre-formatted) and swapping the value `<Text>` for `AnimatedNumber`:
```tsx
      <View style={styles.kpiGrid}>
        {kpis.map((k) => (
          <Card key={k.label} style={styles.kpiCard}>
            <View
              accessible
              accessibilityLabel={
                k.delta !== null && k.delta !== undefined
                  ? `${k.label}: ${fmtCurrency(k.value)}, ${k.delta >= 0 ? 'up' : 'down'} ${Math.abs(k.delta).toFixed(1)} percent ${deltaSpokenLabel}`
                  : `${k.label}: ${fmtCurrency(k.value)}`
              }
            >
              <Text style={[styles.kpiLabel, { color: c.muted }]}>{k.label}</Text>
              {/* AnimatedNumber renders on a non-editable TextInput (see its own doc comment),
                  which has no adjustsFontSizeToFit equivalent -- the auto-shrink this line used
                  to get for an overflowing value is traded for the transition. Accepted
                  deliberately: fmtCurrency rounds to whole rupees and this card has headroom for
                  realistic balances at this font size. Revisit if a real balance is ever reported
                  clipping. numberOfLines={1}'s effect is preserved for free -- a non-multiline
                  TextInput is already single-line. */}
              <AnimatedNumber
                testID={`kpi-${k.label}`}
                value={k.value}
                style={[styles.kpiValue, { color: c.ink }]}
              />
              {k.delta !== null && k.delta !== undefined ? (
                <Text
                  style={[
                    styles.kpiDelta,
                    { color: (k.invert ? k.delta < 0 : k.delta >= 0) ? c.success : c.danger },
                  ]}
                >
                  {k.delta >= 0 ? '▲' : '▼'} {Math.abs(k.delta).toFixed(1)}% {deltaLabel}
                </Text>
              ) : (
                <Text style={styles.kpiDelta} />
              )}
            </View>
          </Card>
        ))}
      </View>
```
- [ ] **Step 4: Run test to verify it passes** — `npm test -- src/screens/DashboardScreen.test.tsx` — Expected: PASS, all tests including the pre-existing ones untouched by this task.
- [ ] **Step 5: Commit** — `git add src/screens/DashboardScreen.tsx src/screens/DashboardScreen.test.tsx` / `git commit -m "feat(mobile): animate Dashboard KPI values"`

---

### Task 10: Apply `AnimatedNumber` to budget totals

**Files:**
- Modify: `src/screens/BudgetsScreen.tsx:156-163`

**Interfaces:**
- Consumes: `AnimatedNumber` (Task 8)
- Produces: nothing new consumed by later tasks

The budget card's `spentThisMonth` / `monthlyLimit` pair (`budgetAmounts`, lines 160-162) is the clear "budget total" in this screen. `budgetFoot`'s remaining-amount sentence (lines 165-169, `"₹X left this month"` / `"₹X over budget"`) is deliberately left alone: it's a full sentence with the number embedded mid-string, and `AnimatedNumber` renders on its own `TextInput`, so animating just the number would mean splitting one sentence into three fragments (`Text` + `AnimatedNumber` + `Text`) fighting the parent `accessible accessibilityLabel` grouping one line above it (`BudgetsScreen.tsx:148-154`) for no real visual gain. No existing test in `BudgetsScreen.test.tsx` asserts on the visible `budgetAmounts` text (confirmed by grep — only `'Set Budget'`/`'Pick a category first.'` are matched via `getByText`), so this task needs no test-file changes.

- [ ] **Step 1: Manual verification setup** — this is a pure visual layout change to an already-rendered value; `AnimatedNumber`'s own formatting/clamping behaviour is fully covered by Task 8's test, and no `BudgetsScreen.test.tsx` assertion touches this text today (verified above), so there is nothing new to pin here beyond a manual check.
- [ ] **Step 2: N/A**
- [ ] **Step 3: Write minimal implementation** — add the import to `src/screens/BudgetsScreen.tsx`:
```tsx
import { AnimatedNumber } from '../components/AnimatedNumber';
```
Change the budget header amounts (lines 156-163):
```tsx
                  <View style={styles.budgetHeader}>
                    <Text style={[styles.budgetName, { color: c.ink }]} numberOfLines={1}>
                      {b.categoryName}
                    </Text>
                    <View style={styles.budgetAmountsRow}>
                      <AnimatedNumber value={b.spentThisMonth} style={[styles.budgetAmounts, { color: c.muted }]} />
                      <Text style={[styles.budgetAmounts, { color: c.muted }]}> / </Text>
                      <AnimatedNumber value={b.monthlyLimit} style={[styles.budgetAmounts, { color: c.muted }]} />
                    </View>
                  </View>
```
and add a style next to the existing `budgetAmounts` entry in the `StyleSheet.create` block:
```tsx
  budgetAmountsRow: { flexDirection: 'row', alignItems: 'baseline' },
```
- [ ] **Step 4: Manual verification** — Budgets tab (More → Budgets): confirm each card still reads `"₹4,000 / ₹10,000"`-style, and that saving a new limit for a category with an existing budget (upsert) causes the total to visibly tick to the new value rather than jump-cutting. Run `npm test -- src/screens/BudgetsScreen.test.tsx` to confirm the pre-existing suite still passes unmodified.
- [ ] **Step 5: Commit** — `git add src/screens/BudgetsScreen.tsx` / `git commit -m "feat(mobile): animate budget totals"`

---

## Item C — Progressive Chart Rendering

Constraint respected throughout: this codebase deliberately hand-rolls SVG charts on `react-native-svg` rather than a charting library (`DonutChart.tsx`'s own top comment: "every RN charting package would add a native dependency to re-validate against each Expo SDK bump for it"). Nothing here adds a new charting dependency — it builds a progressive draw-in on top of the three existing components, using Reanimated (installed in Task 7) plus `react-native-svg`'s existing `strokeDasharray`/`strokeDashoffset` support.

**Test coverage note, stated once here rather than repeated per task**: Reanimated's own testing docs state plainly that "testing react-native-svg props is not supported" under its Jest mode. Independently, no chart component in this codebase has ever had a component-level test — only `chartGeometry.ts`'s pure functions are tested (`chartGeometry.test.ts`), and `DonutChart`/`CashFlowChart`/`TrendChart` have no test files today. Given both of those, Tasks 12-15 below carry no rendered-component test — not because nothing in them is testable (the new geometry math in Task 11 gets full real tests, same as the existing file), but because the one thing actually new in the components themselves (the animated `strokeDashoffset`) is explicitly outside what this project's tooling can assert on, and inventing a snapshot test that doesn't touch that value wouldn't verify anything real.

### Task 11: Chart-reveal geometry helpers

**Files:**
- Modify: `src/lib/chartGeometry.ts` (add `arcLength`, `DONUT_CIRCUMFERENCE`, `polylineLength`)
- Modify: `src/lib/chartGeometry.test.ts` (existing file — add new `describe` blocks)

**Interfaces:**
- Consumes: `DONUT_RADIUS` (already exported in this file)
- Produces: `arcLength(sweepDegrees: number): number`, `DONUT_CIRCUMFERENCE: number`, `polylineLength(points: { x: number; y: number }[]): number` — used by Task 12

- [ ] **Step 1: Write the failing test** — append to `src/lib/chartGeometry.test.ts`:
```ts
import {
  DONUT_CIRCUMFERENCE, DONUT_RADIUS, arcLength, polylineLength,
} from './chartGeometry';

describe('chart reveal geometry', () => {
  it('measures a quarter-circle arc as a quarter of the circumference', () => {
    expect(arcLength(90)).toBeCloseTo((Math.PI * DONUT_RADIUS) / 2);
  });

  it('measures a full sweep as the exact circumference', () => {
    expect(arcLength(360)).toBeCloseTo(2 * Math.PI * DONUT_RADIUS);
    expect(DONUT_CIRCUMFERENCE).toBeCloseTo(2 * Math.PI * DONUT_RADIUS);
  });

  it('measures a right-triangle polyline by straight-line distance, not by bounding box', () => {
    // (0,0) -> (3,0) -> (3,4): legs of 3 and 4, so 3 + 4 = 7 -- not the diagonal (5) and not the
    // sum of both axes' extents guessed independently.
    const length = polylineLength([{ x: 0, y: 0 }, { x: 3, y: 0 }, { x: 3, y: 4 }]);
    expect(length).toBeCloseTo(7);
  });

  it('is zero for a single point or an empty series -- nothing to draw, nothing to animate', () => {
    expect(polylineLength([])).toBe(0);
    expect(polylineLength([{ x: 10, y: 10 }])).toBe(0);
  });
});
```
- [ ] **Step 2: Run test to verify it fails** — `npm test -- src/lib/chartGeometry.test.ts` — Expected: FAIL, `arcLength`/`DONUT_CIRCUMFERENCE`/`polylineLength` are not exported yet.
- [ ] **Step 3: Write minimal implementation** — append to `src/lib/chartGeometry.ts`, after `buildArcs`:
```ts
export const DONUT_CIRCUMFERENCE = 2 * Math.PI * DONUT_RADIUS;

/**
 * Arc length in px for a slice of the donut's fixed radius, given its sweep in degrees --
 * used to size a progressive draw-in's strokeDasharray/strokeDashoffset pair to exactly this
 * slice's own length, so slices don't visually overlap mid-animation. Exact, not approximated:
 * DONUT_RADIUS is constant and every slice is a true circular arc.
 */
export function arcLength(sweepDegrees: number): number {
  return DONUT_RADIUS * ((sweepDegrees * Math.PI) / 180);
}

/**
 * Cumulative straight-line length through a polyline's points -- the line-chart counterpart to
 * arcLength, sizing the same strokeDasharray/strokeDashoffset draw-in technique for
 * CashFlowChart's and TrendChart's polylines. Zero for zero or one points: there is nothing to
 * draw, so nothing to animate.
 */
export function polylineLength(points: { x: number; y: number }[]): number {
  let total = 0;
  for (let i = 1; i < points.length; i++) {
    const dx = points[i].x - points[i - 1].x;
    const dy = points[i].y - points[i - 1].y;
    total += Math.sqrt(dx * dx + dy * dy);
  }
  return total;
}
```
- [ ] **Step 4: Run test to verify it passes** — `npm test -- src/lib/chartGeometry.test.ts` — Expected: PASS, including every pre-existing test in the file.
- [ ] **Step 5: Commit** — `git add src/lib/chartGeometry.ts src/lib/chartGeometry.test.ts` / `git commit -m "feat(mobile): add chart-reveal geometry helpers"`

---

### Task 12: `RevealArc` / `RevealPolyline` components

**Files:**
- Create: `src/components/charts/ChartReveal.tsx`
- Test: no automated test — see the Item C-wide note above (Reanimated's own docs: SVG props aren't testable under its Jest mode; no chart component in this repo has ever had one)

**Interfaces:**
- Consumes: `arcLength`, `DONUT_CIRCUMFERENCE`, `arcPath`, `DONUT_CENTER`, `DONUT_RADIUS` (`src/lib/chartGeometry.ts`, Task 11), `react-native-reanimated` (Task 7)
- Produces: `RevealArc({ a, color, strokeWidth, delay? })`, `RevealPolyline({ points, length, color, strokeWidth, delay? })`, `CHART_REVEAL_DURATION` — used by Tasks 13, 14, 15

- [ ] **Step 1: Manual verification setup** — read `DonutChart.tsx`'s existing arc rendering (lines 54-75) and `CashFlowChart.tsx`/`TrendChart.tsx`'s `Polyline` rendering (already done above) to match the real prop shapes these replace.
- [ ] **Step 2: N/A**
- [ ] **Step 3: Write minimal implementation**
```tsx
// src/components/charts/ChartReveal.tsx
import { useEffect } from 'react';
import { Circle, Path, Polyline } from 'react-native-svg';
import Animated, {
  Easing, useAnimatedProps, useSharedValue, withDelay, withTiming,
} from 'react-native-reanimated';
import {
  DONUT_CENTER, DONUT_RADIUS, arcLength, arcPath, type ArcSlice,
} from '../../lib/chartGeometry';

const AnimatedPath = Animated.createAnimatedComponent(Path);
const AnimatedCircle = Animated.createAnimatedComponent(Circle);
const AnimatedPolyline = Animated.createAnimatedComponent(Polyline);

/**
 * Shared timing for every progressive chart reveal in the app -- same duration and easing as
 * AnimatedNumber (src/components/AnimatedNumber.tsx), so a screen showing both (Dashboard)
 * doesn't mix two different senses of "how fast things settle." Short and monotonic: a fill-in,
 * not a bounce -- the brief's "no flashy casino-style animation" rule.
 */
export const CHART_REVEAL_DURATION = 450;
const CHART_REVEAL_EASING = Easing.out(Easing.cubic);

interface RevealArcProps {
  a: Pick<ArcSlice, 'start' | 'end' | 'full'>;
  color: string;
  strokeWidth: number;
  /** Stagger offset in ms, so slices sweep in one after another rather than all at once --
   * everything moving in lockstep is exactly the "jackpot" look the brief rules out. */
  delay?: number;
}

/**
 * One donut slice (or, when `a.full`, the single-category full ring), revealed by animating
 * strokeDashoffset from the slice's own arc length down to 0 -- a progressive draw-in scoped to
 * exactly this slice's length, so slices don't visually overlap mid-animation.
 */
export function RevealArc({ a, color, strokeWidth, delay = 0 }: RevealArcProps) {
  const length = a.full ? 2 * Math.PI * DONUT_RADIUS : arcLength(a.end - a.start);
  const progress = useSharedValue(0);

  useEffect(() => {
    progress.value = withDelay(
      delay,
      withTiming(1, { duration: CHART_REVEAL_DURATION, easing: CHART_REVEAL_EASING })
    );
    // Runs once per mount -- these charts reveal in on first render, not on every value change.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const animatedProps = useAnimatedProps(() => ({
    strokeDashoffset: length * (1 - progress.value),
  }));

  if (a.full) {
    return (
      <AnimatedCircle
        cx={DONUT_CENTER}
        cy={DONUT_CENTER}
        r={DONUT_RADIUS}
        stroke={color}
        strokeWidth={strokeWidth}
        fill="none"
        strokeDasharray={length}
        animatedProps={animatedProps}
      />
    );
  }

  return (
    <AnimatedPath
      d={arcPath(a.start, a.end)}
      stroke={color}
      strokeWidth={strokeWidth}
      fill="none"
      strokeLinecap="butt"
      strokeDasharray={length}
      animatedProps={animatedProps}
    />
  );
}

interface RevealPolylineProps {
  points: string;
  /** Precomputed via polylineLength(...) against the same {x,y} pairs used to build `points`. */
  length: number;
  color: string;
  strokeWidth: number;
  delay?: number;
}

/** Line-chart counterpart to RevealArc -- the same draw-in technique applied to a Polyline's own
 * length. Shared by CashFlowChart's two series and TrendChart's one. */
export function RevealPolyline({ points, length, color, strokeWidth, delay = 0 }: RevealPolylineProps) {
  const progress = useSharedValue(0);

  useEffect(() => {
    progress.value = withDelay(
      delay,
      withTiming(1, { duration: CHART_REVEAL_DURATION, easing: CHART_REVEAL_EASING })
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const animatedProps = useAnimatedProps(() => ({
    strokeDashoffset: length * (1 - progress.value),
  }));

  return (
    <AnimatedPolyline
      points={points}
      fill="none"
      stroke={color}
      strokeWidth={strokeWidth}
      strokeDasharray={length}
      animatedProps={animatedProps}
    />
  );
}
```
- [ ] **Step 4: Manual verification** — nothing to run yet in isolation; verified together with Tasks 13-15 on-device (Reanimated + react-native-svg animated props require a real rebuilt dev client, not just Jest — see Task 7's rebuild note).
- [ ] **Step 5: Commit** — `git add src/components/charts/ChartReveal.tsx` / `git commit -m "feat(mobile): add RevealArc/RevealPolyline chart draw-in primitives"`

---

### Task 13: Wire progressive reveal into `DonutChart`

**Files:**
- Modify: `src/components/charts/DonutChart.tsx:52-77`

**Interfaces:**
- Consumes: `RevealArc` (Task 12)

- [ ] **Step 1-2: N/A** — see Item C-wide test note.
- [ ] **Step 3: Write minimal implementation** — add the import:
```tsx
import { RevealArc } from './ChartReveal';
```
Replace the arc-rendering block (lines 52-77) — the empty-ring branch (lines 32-47, no data) is left untouched, since there's nothing to reveal:
```tsx
        <Svg width={DONUT_SIZE} height={DONUT_SIZE}>
          <G>
            {arcs.map((a) => (
              <RevealArc
                key={a.index}
                a={a}
                color={colorFor(a.index)}
                strokeWidth={DONUT_STROKE}
                delay={a.index * 60}
              />
            ))}
          </G>
        </Svg>
```
(`Circle`/`Path` imports from `react-native-svg` are still needed for the empty-ring branch above and can stay; `G` stays too.)
- [ ] **Step 4: Manual verification** — Dashboard tab, "Spending by Category": on load, slices should sweep in one after another (60ms stagger) rather than popping in at once; the legend (unaffected, still plain `Text`/`View`) should appear immediately as before.
- [ ] **Step 5: Commit** — `git add src/components/charts/DonutChart.tsx` / `git commit -m "feat(mobile): progressive reveal for the spending donut"`

---

### Task 14: Wire progressive reveal into `CashFlowChart`

**Files:**
- Modify: `src/components/charts/CashFlowChart.tsx:1-58`

**Interfaces:**
- Consumes: `RevealPolyline`, `CHART_REVEAL_DURATION` (Task 12), `polylineLength` (Task 11)

- [ ] **Step 1-2: N/A** — see Item C-wide test note.
- [ ] **Step 3: Write minimal implementation** — add imports:
```tsx
import { CHART_REVEAL_DURATION, RevealPolyline } from './ChartReveal';
import { polylineLength } from '../../lib/chartGeometry';
```
Add a `toPoints` helper alongside the existing `toPolyline` (after line 28) and replace the two `<Polyline>` elements (lines 50-51):
```tsx
  const { xAt, yAt } = cashFlowScale(points, width);
  const toPolyline = (pick: (p: CashFlowPoint) => number) =>
    points.map((p, i) => `${xAt(i)},${yAt(pick(p))}`).join(' ');
  const toPoints = (pick: (p: CashFlowPoint) => number) =>
    points.map((p, i) => ({ x: xAt(i), y: yAt(pick(p)) }));
```
```tsx
          <RevealPolyline
            points={toPolyline((p) => p.income)}
            length={polylineLength(toPoints((p) => p.income))}
            color={c.success}
            strokeWidth={2}
          />
          <RevealPolyline
            points={toPolyline((p) => p.expense)}
            length={polylineLength(toPoints((p) => p.expense))}
            color={c.danger}
            strokeWidth={2}
            // Expense sweeps in just behind income, not simultaneously -- a small stagger reads
            // as two distinct series arriving, not one blob.
            delay={CHART_REVEAL_DURATION / 3}
          />
```
The per-point `Circle` markers (lines 52-57) stay static, unanimated — they're small enough that animating them adds motion without adding clarity, and they read correctly the instant the line beneath them finishes.
- [ ] **Step 4: Manual verification** — Dashboard tab, "Cash Flow": income line draws in first, expense line follows just behind; switching the 3M/6M/12M range re-triggers the reveal on the new point set (a fresh mount of the same component with different `points`).
- [ ] **Step 5: Commit** — `git add src/components/charts/CashFlowChart.tsx` / `git commit -m "feat(mobile): progressive reveal for the cash flow chart"`

---

### Task 15: Wire progressive reveal into `TrendChart`

**Files:**
- Modify: `src/components/charts/TrendChart.tsx:1-59`

**Interfaces:**
- Consumes: `RevealPolyline` (Task 12), `polylineLength` (Task 11)

- [ ] **Step 1-2: N/A** — see Item C-wide test note.
- [ ] **Step 3: Write minimal implementation** — add imports:
```tsx
import { RevealPolyline } from './ChartReveal';
import { polylineLength } from '../../lib/chartGeometry';
```
Replace the single `<Polyline>` (line 54):
```tsx
  const { xAt, yAt } = trendScale(points.map((p) => p.value), width);
  const polylinePoints = points.map((p, i) => `${xAt(i)},${yAt(p.value)}`).join(' ');
  const linePoints = points.map((p, i) => ({ x: xAt(i), y: yAt(p.value) }));
```
```tsx
          <RevealPolyline
            points={polylinePoints}
            length={polylineLength(linePoints)}
            color={c.primary}
            strokeWidth={2}
          />
```
(rename the local `polyline` variable to `polylinePoints` throughout the render to avoid a name collision with the `points` prop; the point-marker `Circle`s below stay static, same reasoning as Task 14.)
- [ ] **Step 4: Manual verification** — Investments tab, Net Worth Trend: the line draws in on load rather than appearing fully formed.
- [ ] **Step 5: Commit** — `git add src/components/charts/TrendChart.tsx` / `git commit -m "feat(mobile): progressive reveal for the net worth trend chart"`

---

## Item D — Shared Element Transitions

**Files:** none created or modified in the near term — this is an evaluation task, per the brief's own instruction that it "can be shorter/more exploratory... explicitly meant to avoid unnecessary scope, not to force a shared-element implementation." Forcing a code change onto an unrelated screen just to have a diff would itself be the unnecessary scope this task is meant to avoid.

**Findings** (from reading the actual navigation tree, not assumed):

1. **None of the three candidate destinations exist yet.** Confirmed by reading `src/navigation/RootNavigator.tsx`, `src/navigation/AppTabs.tsx`, and `src/navigation/types.ts` in full, and by grepping every screen for `onPress`/`navigate`:
   - Transaction row → transaction detail: `LedgerScreen.tsx`'s rows (`renderItem`, line 198) have no navigation — only `onLongPress` (delete) and the accessibility `delete` action.
   - Budget card → budget detail: `BudgetsScreen.tsx`'s cards (`budgetCard`, line 145) have no `onPress` at all.
   - Account card → account detail: `AccountsScreen.tsx`'s only row `onPress` is `toggleRevealed(a.id)` (balance masking), not navigation.

   There is no `TransactionDetail`/`BudgetDetail`/`AccountDetail` entry in `AppTabParamList` or `MoreStackParamList` (`src/navigation/types.ts`). A shared-element transition needs two screens to transition *between*; right now there is one screen and nowhere to go. This is a product-scope gap (whether these detail screens get built at all), not a tooling gap — no transition library, shared-element or otherwise, changes that.

2. **The zero-dependency default, for when those screens exist**: `@react-navigation/native-stack@^7.18.6` (already installed) exposes an `animation` screen option directly — `'default' | 'fade' | 'flip' | 'simple_push' | 'slide_from_bottom' | 'slide_from_right' | 'slide_from_left' | 'none'` — plus `animationTypeForReplace: 'push' | 'pop'` for screens that replace rather than push. These are real native transitions (`UINavigationController`/`Fragment` transitions under the hood, not JS-driven), require no new dependency, and match this app's "respect platform conventions" principle better than a shared-element library would: `react-native-shared-element` (the library named in the brief as a candidate) hooks into native-stack's transition internals from outside the library, which is exactly the kind of thing that breaks silently on an Expo SDK bump — the same risk `DonutChart.tsx`'s own comment already calls out for charting libraries, one layer down in the stack. Recommended default, once a detail screen exists:
```tsx
<MoreStack.Screen
  name="BudgetDetail"
  component={BudgetDetailScreen}
  options={{ animation: 'slide_from_right' }} // or 'fade' for a subtler feel, per the brief's tone
/>
```
   For an *element* actually appearing to move between the two screens (the literal "shared element" effect — a budget card growing into its detail header, not just the screen sliding in behind it), native-stack v7 has no built-in primitive; that specific effect is the one place a dedicated library would still be doing something native-stack's own `animation` option cannot.

3. **Decision point for Sid, not decided here**: if and when transaction/budget/account detail screens get built, use native-stack's `animation`/`animationTypeForReplace` as the default (zero new dependencies, matches the recommendation above) for all three. Only reconsider a shared-element library at that point, and only if a specific one of the three candidates is judged to need the literal card-morphs-into-header effect rather than a screen-level transition — and even then, evaluate against native-stack v7's transition APIs directly (which have continued to grow release over release) before reaching for `react-native-shared-element`, which has seen materially less maintenance activity relative to native-stack itself over the same period. Flagging this as a live decision point rather than pre-deciding it, since it depends on product scope (whether these detail screens ship at all) that hasn't been made yet.

## Self-Review Notes

- **Spec coverage:** Haptic Feedback (Tasks 1-6), Animated Numbers (Tasks 7-10), Progressive Chart Rendering (Tasks 11-15), Shared Element Transitions (Item D findings/recommendation, no code). All four Phase 3 brief items covered; ledger perf validation correctly excluded as already merged.
- **Placeholder scan:** none found — every implementation step shows complete, real code; N/A steps are explicitly justified (dependency-manifest-only tasks, untestable SVG animation props) rather than silently skipped.
- **Type consistency:** `AnimatedNumberProps` (Task 8) matches every call site in Tasks 9-10 (`value: number`, `style`, `testID`). `RevealArc`'s `a: Pick<ArcSlice, 'start' | 'end' | 'full'>` (Task 12) matches `DonutChart`'s existing `arcs` array shape from `buildArcs` (Task 11's file). `RevealPolyline`'s `length` prop is always paired with a `polylineLength(...)` call over the same point set used to build its `points` string, in both Task 14 and Task 15.
</content>
