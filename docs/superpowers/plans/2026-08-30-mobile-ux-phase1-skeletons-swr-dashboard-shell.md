# Mobile UX Excellence — Phase 1 (P0): Skeletons, Stale-While-Revalidate, Dashboard Shell Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace bare spinners with theme-aware skeleton placeholders across Dashboard/Ledger/Reports/Budgets/Insights, verify stale-while-revalidate behavior holds (already-rendered content never gets replaced by a loading state on background refetch), and restructure DashboardScreen so its shell (greeting, section headings, tab bar) mounts immediately instead of waiting behind one full-screen spinner.

**Architecture:** A single `Shimmer` primitive (core RN `Animated`, no new dependency) composes into five typed skeleton components (`SkeletonCard`, `SkeletonTransactionRow`, `SkeletonBudgetCard`, `SkeletonDashboardSection`, `SkeletonChart`) under `src/components/skeletons/`. Each of the five target screens swaps its `isLoading`-gated `ActivityIndicator` for the matching skeleton, scoped as narrowly as each screen's actual data dependency allows (e.g. a form that doesn't need the list data it's next to must not wait on it). DashboardScreen is the capstone: its shell renders unconditionally and each section (KPIs, Cash Flow, category donut, Recent Transactions) independently swaps between skeleton and real content based on its own query's `isLoading`, not one screen-wide flag.

**Tech Stack:** React Native (Expo SDK 57), `@tanstack/react-query` v5.101.4 (already the app's data layer, `staleTime: 30_000`), Jest 29 + `@testing-library/react-native` 13.3.3, run via `npm test` from `mobile/`.

**Spec:** User-provided "Mobile UX Excellence Initiative" brief (Phase 1 / P0 items: Skeleton Loading System, Stale-While-Revalidate UX, Dashboard Shell, Production Sentry), scoped against a live codebase survey and an in-conversation sequencing decision to build on top of PR #623 (merged 2026-08-30, commit `ebf68f57`).

## Global Constraints

- Two P0 items are **already merged to `main`** (PR #623 / `ebf68f57`) and are NOT part of this plan: axios request timeout (30s, both clients), and the native splash screen no longer auto-hiding before first frame commits.
- Skeletons appear only on first load (no cached data yet) — a background refetch (`isFetching` without `isLoading`) must never replace already-rendered content with a skeleton.
- Every skeleton must be theme-aware (light/dark via `useTheme()`/`../../theme`) and dimensionally match the real content it stands in for (see `chartGeometry.ts`'s `CASHFLOW_HEIGHT`/`DONUT_SIZE` for chart skeletons).
- No new dependency is needed for the shimmer animation — core RN `Animated` with `useNativeDriver: true` is sufficient (confirmed: no `react-native-reanimated` or gradient library exists in this project yet).
- Production Sentry (brief item 13) is an operational/credentials task, not code — captured as a checklist at the end of this plan, not as bite-sized TDD tasks.
- Test tooling: Jest 29, `@testing-library/react-native` 13.3.3, `npm test -- <path>` from `mobile/`. Match each existing screen test file's house style (mocking conventions, `renderScreen()` helpers) rather than inventing a new one.
- **Found during Task 1, applies to every later task:** (1) Shimmer/skeleton components deliberately hide themselves from accessibility (`accessibilityElementsHidden`/`importantForAccessibility="no-hide-descendants"`) — correct a11y behavior, but RNTL 13.3.3 excludes accessibility-hidden elements from every query by default (`defaultIncludeHiddenElements: false`). Every `getByTestId`/`getAllByTestId`/`queryByTestId` call targeting `shimmer-block` or any `skeleton-*` testID must pass `{ hidden: true }` as a second argument, or it will silently fail to find an element that is genuinely in the tree. (2) This project's `eslint-plugin-react-hooks` v7 (React Compiler rules) forbids reading `.current` during render at all, including the common `useRef(init).current` lazy-initialization idiom — use `useState(() => init)[0]` instead for any render-scoped singleton (e.g. an `Animated.Value`).

---

### Task 1: Shimmer primitive

**Files:**
- Create: `src/components/skeletons/Shimmer.tsx`
- Test: `src/components/skeletons/Shimmer.test.tsx`

**Interfaces:**
- Consumes: `useTheme()` and `radius` from `../../theme` (`c.border`, `radius.md`).
- Produces: `Shimmer({ width?: DimensionValue; height: number; borderRadius?: number; style?: ViewStyle; testID?: string })` — every skeleton component in Task 2/3 is built from this.

- [x] **Step 1: Write the failing test**
```tsx
import { render, screen } from '@testing-library/react-native';
import { Shimmer } from './Shimmer';
import { ThemeProvider } from '../../theme';

function renderShimmer(props: Partial<React.ComponentProps<typeof Shimmer>> = {}) {
  return render(
    <ThemeProvider>
      <Shimmer height={20} {...props} />
    </ThemeProvider>
  );
}

function flatStyle(style: unknown): Record<string, unknown> {
  return Array.isArray(style)
    ? Object.assign({}, ...(style as unknown[]).flat(Infinity).filter(Boolean))
    : (style as Record<string, unknown>);
}

describe('Shimmer', () => {
  it('renders a block sized to the given width and height', () => {
    renderShimmer({ width: 120, height: 20 });
    const block = screen.getByTestId('shimmer-block');
    const style = flatStyle(block.props.style);
    expect(style.width).toBe(120);
    expect(style.height).toBe(20);
  });

  it('defaults to a full-width block with the theme border color', () => {
    renderShimmer();
    const style = flatStyle(screen.getByTestId('shimmer-block').props.style);
    expect(style.width).toBe('100%');
    // light.border from src/theme/palette.ts -- ThemeProvider defaults to 'system', which resolves
    // to light under the test runner's default (non-dark) color scheme.
    expect(style.backgroundColor).toBe('#E6EAF2');
  });

  it('is hidden from assistive technology, since it carries no information of its own', () => {
    renderShimmer();
    const block = screen.getByTestId('shimmer-block');
    expect(block.props.accessibilityElementsHidden).toBe(true);
    expect(block.props.importantForAccessibility).toBe('no-hide-descendants');
  });

  it('accepts a custom testID so a composed skeleton can query it distinctly', () => {
    renderShimmer({ testID: 'skeleton-chart-bar' });
    expect(screen.getByTestId('skeleton-chart-bar')).toBeTruthy();
    expect(screen.queryByTestId('shimmer-block')).toBeNull();
  });
});
```
- [x] **Step 2: Run test to verify it fails** — `npm test -- src/components/skeletons/Shimmer.test.tsx` — Expected: FAIL with `Cannot find module './Shimmer'`.
- [x] **Step 3: Write minimal implementation**
```tsx
import { useEffect, useRef } from 'react';
import {
  Animated, StyleSheet, type DimensionValue, type ViewStyle,
} from 'react-native';
import { radius, useTheme } from '../../theme';

export interface ShimmerProps {
  width?: DimensionValue;
  height: number;
  borderRadius?: number;
  style?: ViewStyle;
  testID?: string;
}

/**
 * The shared pulse every skeleton component in this folder is built from -- one Animated.Value
 * looping between two opacities over a theme-aware block (`c.border`, already correct in both
 * light and dark -- see src/theme/palette.ts). There is no react-native-reanimated or gradient
 * library in this project (confirmed: neither appears in package.json), so this uses core
 * `Animated` from 'react-native' with useNativeDriver: true -- opacity is a native-driver-safe
 * property, so this needs no new dependency.
 */
export function Shimmer({
  width = '100%', height, borderRadius = radius.md, style, testID = 'shimmer-block',
}: ShimmerProps) {
  const c = useTheme();
  const pulse = useRef(new Animated.Value(0.35)).current;

  useEffect(() => {
    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(pulse, { toValue: 1, duration: 700, useNativeDriver: true }),
        Animated.timing(pulse, { toValue: 0.35, duration: 700, useNativeDriver: true }),
      ])
    );
    loop.start();
    return () => loop.stop();
  }, [pulse]);

  return (
    <Animated.View
      testID={testID}
      // Placeholder only -- it stands in for content a screen reader will hear announced once the
      // real value replaces it. Exposing it too would double-announce every field on first load.
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
      style={[styles.block, { width, height, borderRadius, backgroundColor: c.border, opacity: pulse }, style]}
    />
  );
}

const styles = StyleSheet.create({
  block: { overflow: 'hidden' },
});
```
- [x] **Step 4: Run test to verify it passes** — Expected: PASS
- [x] **Step 5: Commit** — `git add src/components/skeletons/Shimmer.tsx src/components/skeletons/Shimmer.test.tsx` / `git commit -m "feat(mobile): add Shimmer skeleton primitive"`

---

### Task 2: SkeletonCard and SkeletonTransactionRow

**Files:**
- Create: `src/components/skeletons/Skeletons.tsx`
- Test: `src/components/skeletons/Skeletons.test.tsx`

**Interfaces:**
- Consumes: `Shimmer` (Task 1), `radius`/`spacing`/`useTheme` from `../../theme`.
- Produces: `SkeletonCard({ lines?: number; style?: ViewStyle })`, `SkeletonTransactionRow()` — both used by Task 3's components and by the screen-level tasks (4, 6, 7, 9).

- [x] **Step 1: Write the failing test**
```tsx
import { render, screen } from '@testing-library/react-native';
import { SkeletonCard, SkeletonTransactionRow } from './Skeletons';
import { ThemeProvider } from '../../theme';

function withTheme(node: React.ReactElement) {
  return render(<ThemeProvider>{node}</ThemeProvider>);
}

describe('SkeletonCard', () => {
  it('renders a heading placeholder plus the requested number of line placeholders', () => {
    withTheme(<SkeletonCard lines={2} />);
    // heading + 2 lines = 3 shimmer blocks
    expect(screen.getAllByTestId('shimmer-block')).toHaveLength(3);
  });

  it('defaults to 3 lines when none is given', () => {
    withTheme(<SkeletonCard />);
    expect(screen.getAllByTestId('shimmer-block')).toHaveLength(4);
  });
});

describe('SkeletonTransactionRow', () => {
  it('renders the row shape: description, meta and amount placeholders', () => {
    withTheme(<SkeletonTransactionRow />);
    expect(screen.getByTestId('skeleton-transaction-row')).toBeTruthy();
    expect(screen.getAllByTestId('shimmer-block')).toHaveLength(3);
  });
});
```
- [x] **Step 2: Run test to verify it fails** — `npm test -- src/components/skeletons/Skeletons.test.tsx` — Expected: FAIL with `Cannot find module './Skeletons'`.
- [x] **Step 3: Write minimal implementation**
```tsx
import { StyleSheet, View, type ViewStyle } from 'react-native';
import { radius, spacing, useTheme } from '../../theme';
import { Shimmer } from './Shimmer';

/**
 * First-load placeholders only. Every call site in this app gates these behind `isLoading`
 * (React Query v5 semantics: true only when there is no cached data for that query key yet), never
 * `isFetching` -- so a background refetch never swaps rendered data back out for one of these. See
 * DashboardScreen, LedgerScreen, ReportsScreen, BudgetsScreen and InsightsScreen for the call sites.
 */

export function SkeletonCard({ lines = 3, style }: { lines?: number; style?: ViewStyle }) {
  const c = useTheme();
  return (
    <View style={[styles.card, { backgroundColor: c.card, borderColor: c.border }, style]}>
      <Shimmer width="40%" height={13} style={styles.heading} />
      {Array.from({ length: lines }).map((_, i) => (
        <Shimmer key={i} width={i === lines - 1 ? '60%' : '100%'} height={12} style={styles.line} />
      ))}
    </View>
  );
}

/** Mirrors DashboardScreen's and LedgerScreen's own txnRow layout: description + meta on the
 *  left, an amount on the right. */
export function SkeletonTransactionRow() {
  const c = useTheme();
  return (
    <View style={[styles.txnRow, { borderBottomColor: c.border }]} testID="skeleton-transaction-row">
      <View style={styles.txnMain}>
        <Shimmer width="70%" height={14} style={styles.line} />
        <Shimmer width="40%" height={11} />
      </View>
      <Shimmer width={60} height={14} />
    </View>
  );
}

const styles = StyleSheet.create({
  card: { borderWidth: 1, borderRadius: radius.lg, padding: spacing.md },
  heading: { marginBottom: spacing.sm },
  line: { marginBottom: 8 },
  txnRow: {
    flexDirection: 'row', alignItems: 'center', paddingVertical: 10, borderBottomWidth: StyleSheet.hairlineWidth,
  },
  txnMain: { flex: 1, marginRight: spacing.sm, gap: 6 },
});
```
- [x] **Step 4: Run test to verify it passes** — Expected: PASS
- [x] **Step 5: Commit** — `git add src/components/skeletons/Skeletons.tsx src/components/skeletons/Skeletons.test.tsx` / `git commit -m "feat(mobile): add SkeletonCard and SkeletonTransactionRow"`

---

### Task 3: SkeletonBudgetCard, SkeletonDashboardSection and SkeletonChart

**Files:**
- Modify: `src/components/skeletons/Skeletons.tsx` (append)
- Modify: `src/components/skeletons/Skeletons.test.tsx` (append)

**Interfaces:**
- Consumes: `Shimmer` (Task 1), `SkeletonTransactionRow` (Task 2), `CASHFLOW_HEIGHT`/`DONUT_SIZE` from `../../lib/chartGeometry`.
- Produces: `SkeletonBudgetCard()`, `SkeletonDashboardSection({ rows?: number })`, `SkeletonChart({ variant?: 'bar' | 'donut'; width?: number })`.

- [x] **Step 1: Write the failing test**
```tsx
// appended to src/components/skeletons/Skeletons.test.tsx
import { SkeletonBudgetCard, SkeletonChart, SkeletonDashboardSection } from './Skeletons';

describe('SkeletonBudgetCard', () => {
  it('renders the budget card shape: header, progress bar and footer placeholders', () => {
    withTheme(<SkeletonBudgetCard />);
    expect(screen.getByTestId('skeleton-budget-card')).toBeTruthy();
    expect(screen.getAllByTestId('shimmer-block')).toHaveLength(3);
  });
});

describe('SkeletonDashboardSection', () => {
  it('renders a heading placeholder plus the requested number of transaction-row placeholders', () => {
    withTheme(<SkeletonDashboardSection rows={2} />);
    expect(screen.getByTestId('skeleton-dashboard-section')).toBeTruthy();
    expect(screen.getAllByTestId('skeleton-transaction-row')).toHaveLength(2);
  });
});

describe('SkeletonChart', () => {
  it('renders a bar-shaped placeholder matching CashFlowChart height by default', () => {
    withTheme(<SkeletonChart width={280} />);
    expect(screen.getByTestId('skeleton-chart-bar')).toBeTruthy();
  });

  it('renders a circular placeholder matching DonutChart when variant="donut"', () => {
    withTheme(<SkeletonChart variant="donut" />);
    expect(screen.getByTestId('skeleton-chart-donut')).toBeTruthy();
  });
});
```
- [x] **Step 2: Run test to verify it fails** — `npm test -- src/components/skeletons/Skeletons.test.tsx` — Expected: FAIL with `SkeletonBudgetCard is not exported`/similar.
- [x] **Step 3: Write minimal implementation**
```tsx
// appended to src/components/skeletons/Skeletons.tsx
import { CASHFLOW_HEIGHT, DONUT_SIZE } from '../../lib/chartGeometry';

/** Mirrors BudgetsScreen's budget card: category + amounts header, a progress-bar-shaped
 *  placeholder, and a one-line footer. */
export function SkeletonBudgetCard() {
  const c = useTheme();
  return (
    <View style={[styles.card, { backgroundColor: c.card, borderColor: c.border }]} testID="skeleton-budget-card">
      <View style={styles.budgetHeader}>
        <Shimmer width="50%" height={14} />
        <Shimmer width={70} height={12} />
      </View>
      <Shimmer width="100%" height={8} borderRadius={4} style={styles.line} />
      <Shimmer width="35%" height={11} style={styles.line} />
    </View>
  );
}

/** A Card-shaped section with a heading and N transaction-row placeholders -- for any Dashboard
 *  section (Recent Transactions, Goals, Insights) that lists rows once its query resolves. */
export function SkeletonDashboardSection({ rows = 3 }: { rows?: number }) {
  const c = useTheme();
  return (
    <View style={[styles.card, { backgroundColor: c.card, borderColor: c.border }]} testID="skeleton-dashboard-section">
      <Shimmer width="45%" height={15} style={styles.heading} />
      {Array.from({ length: rows }).map((_, i) => (
        <SkeletonTransactionRow key={i} />
      ))}
    </View>
  );
}

/** Matches CashFlowChart's fixed height (CASHFLOW_HEIGHT) and DonutChart's fixed diameter
 *  (DONUT_SIZE) -- see lib/chartGeometry.ts, the single source both real charts already draw from,
 *  so the skeleton never drifts out of sync with the real layout it stands in for. */
export function SkeletonChart({ variant = 'bar', width = 300 }: { variant?: 'bar' | 'donut'; width?: number }) {
  if (variant === 'donut') {
    return (
      <View style={styles.donutWrap} testID="skeleton-chart-donut">
        <Shimmer width={DONUT_SIZE} height={DONUT_SIZE} borderRadius={DONUT_SIZE / 2} />
      </View>
    );
  }
  return <Shimmer testID="skeleton-chart-bar" width={width} height={CASHFLOW_HEIGHT} borderRadius={radius.md} />;
}
```
Also extend the `styles` object:
```tsx
const styles = StyleSheet.create({
  card: { borderWidth: 1, borderRadius: radius.lg, padding: spacing.md },
  heading: { marginBottom: spacing.sm },
  line: { marginBottom: 8 },
  txnRow: {
    flexDirection: 'row', alignItems: 'center', paddingVertical: 10, borderBottomWidth: StyleSheet.hairlineWidth,
  },
  txnMain: { flex: 1, marginRight: spacing.sm, gap: 6 },
  budgetHeader: { flexDirection: 'row', justifyContent: 'space-between', marginBottom: spacing.sm },
  donutWrap: { alignItems: 'center', justifyContent: 'center', paddingVertical: spacing.sm },
});
```
- [x] **Step 4: Run test to verify it passes** — Expected: PASS
- [x] **Step 5: Commit** — `git add src/components/skeletons/Skeletons.tsx src/components/skeletons/Skeletons.test.tsx` / `git commit -m "feat(mobile): add SkeletonBudgetCard, SkeletonDashboardSection, SkeletonChart"`

---

### Task 4: Apply skeletons to LedgerScreen

**Files:**
- Modify: `src/screens/LedgerScreen.tsx:133-136`
- Test: Modify `src/screens/LedgerScreen.test.tsx` (append)

**Interfaces:**
- Consumes: `SkeletonTransactionRow` (Task 2).

- [x] **Step 1: Write the failing test**
```tsx
// appended to src/screens/LedgerScreen.test.tsx
describe('skeleton loading', () => {
  it('shows skeleton placeholder rows while the first page is loading, not a spinner', async () => {
    let resolveSearch: (value: unknown) => void = () => {};
    transactions.search.mockReturnValue(new Promise((resolve) => { resolveSearch = resolve; }));

    renderScreen();

    expect(screen.getAllByTestId('skeleton-transaction-row').length).toBeGreaterThan(0);
    expect(screen.queryByTestId('ledger-list')).toBeNull();

    await act(async () => resolveSearch(page([])));
  });
});
```
(Add `act` to the top-level `@testing-library/react-native` import if not already present in this file.)
- [x] **Step 2: Run test to verify it fails** — `npm test -- src/screens/LedgerScreen.test.tsx` — Expected: FAIL, `getAllByTestId('skeleton-transaction-row')` finds none (the current code renders an `ActivityIndicator`).
- [x] **Step 3: Write minimal implementation**
```tsx
// LedgerScreen.tsx -- import addition near the top
import { SkeletonTransactionRow } from '../components/skeletons/Skeletons';
```
```tsx
// LedgerScreen.tsx:133-136, replacing the isLoading branch
      {isLoading ? (
        <View style={styles.listContent}>
          {Array.from({ length: 8 }).map((_, i) => (
            <SkeletonTransactionRow key={i} />
          ))}
        </View>
      ) : isError && txns.length === 0 ? (
```
- [x] **Step 4: Run test to verify it passes** — Expected: PASS
- [x] **Step 5: Commit** — `git add src/screens/LedgerScreen.tsx src/screens/LedgerScreen.test.tsx` / `git commit -m "feat(mobile): show skeleton rows on LedgerScreen's first load"`

---

### Task 5: Apply skeletons to BudgetsScreen (and mount the form shell immediately)

**Files:**
- Modify: `src/screens/BudgetsScreen.tsx:1-4` (imports), `:80-86` (remove full-screen early return), `:130-138` (add skeleton branch)
- Test: Modify `src/screens/BudgetsScreen.test.tsx` (append)

**Interfaces:**
- Consumes: `SkeletonBudgetCard` (Task 3).

Today `if (isLoading) return <ActivityIndicator/>` (lines 80-86) hides the entire screen — including the category picker, limit field and "Set Budget" button, none of which depend on the budgets list — while the budgets query is in flight. That's a shell-doesn't-mount-immediately bug independent of Item A's skeleton work, and fixing it is what actually gets BudgetsScreen showing something useful on first paint.

- [x] **Step 1: Write the failing test**
```tsx
// appended to src/screens/BudgetsScreen.test.tsx
it('shows skeleton budget cards on first load, with the add-budget form already usable', () => {
  api.list.mockReset().mockReturnValue(new Promise(() => {}));
  categories.list.mockReset().mockResolvedValue([{ id: 'c-1', name: 'Groceries', isSystem: true }]);

  renderScreen();

  // The shell -- category picker and Set Budget button -- must not wait on the budgets list.
  expect(screen.getByLabelText('Choose a category')).toBeTruthy();
  expect(screen.getByText('Set Budget')).toBeTruthy();
  expect(screen.getAllByTestId('skeleton-budget-card').length).toBeGreaterThan(0);
});
```
- [x] **Step 2: Run test to verify it fails** — `npm test -- src/screens/BudgetsScreen.test.tsx` — Expected: FAIL, `getByLabelText('Choose a category')` throws because the screen currently early-returns a bare spinner while `isLoading` is true.
- [x] **Step 3: Write minimal implementation**
```tsx
// BudgetsScreen.tsx -- import addition
import { SkeletonBudgetCard } from '../components/skeletons/Skeletons';
```
```tsx
// BudgetsScreen.tsx -- remove lines 80-86 entirely:
//   if (isLoading) {
//     return (
//       <View style={[styles.centered, { backgroundColor: c.bg }]}>
//         <ActivityIndicator size="large" color={c.primary} />
//       </View>
//     );
//   }
```
```tsx
// BudgetsScreen.tsx:130-138, replacing the isError/budgets.length ternary
      <View style={styles.list}>
        {isLoading ? (
          <>
            <SkeletonBudgetCard />
            <SkeletonBudgetCard />
            <SkeletonBudgetCard />
          </>
        ) : isError ? (
          <Card>
            <Text style={[styles.error, { color: c.danger }]}>Could not load budgets.</Text>
          </Card>
        ) : budgets.length === 0 ? (
          <Card>
            <EmptyState message="No budgets set yet. Set one above to start tracking a category." />
          </Card>
        ) : (
```
Also drop `ActivityIndicator` from the `react-native` import at the top of the file (line 3) — it's now unused.
- [x] **Step 4: Run test to verify it passes** — Expected: PASS
- [x] **Step 5: Commit** — `git add src/screens/BudgetsScreen.tsx src/screens/BudgetsScreen.test.tsx` / `git commit -m "feat(mobile): mount BudgetsScreen's form immediately, skeleton the list"`

---

### Task 6: Apply skeletons to ReportsScreen (and mount the month picker shell immediately)

**Files:**
- Modify: `src/screens/ReportsScreen.tsx:1-13` (imports), `:70-76` (months loading), `:150-151` (report loading)
- Test: Modify `src/screens/ReportsScreen.test.tsx` (append)

**Interfaces:**
- Consumes: `SkeletonCard` (Task 2).

Two loading sites here: `monthsLoading` (lines 70-76) currently blanks the whole screen; `reportLoading` (line 150) is already scoped to just the report body, but renders a bare `ActivityIndicator` instead of a shape matching the totals row + category breakdown it replaces. Switching months (`onSelect` sets `pickedMonth`) changes the `['report', month]` query key — for a month never fetched this session, `reportLoading` goes true again, which is the exact "isLoading with no cached data yet" case a skeleton belongs in.

- [x] **Step 1: Write the failing test**
```tsx
// appended to src/screens/ReportsScreen.test.tsx
describe('skeleton loading', () => {
  it('shows a skeleton shell instead of a spinner while the month list is loading', () => {
    api.availableMonths.mockReset().mockReturnValue(new Promise(() => {}));
    renderScreen();

    expect(screen.getAllByTestId('shimmer-block').length).toBeGreaterThan(0);
  });

  it('skeletons the report body on an uncached month, keeping the month picker usable', async () => {
    renderScreen();
    await loadedReport();

    api.forMonth.mockReset().mockReturnValue(new Promise(() => {}));
    fireEvent.press(screen.getByLabelText(/Month: Jul 26/));
    await settle();
    fireEvent.press(screen.getByText('2026-05'));
    await settle();

    expect(screen.getAllByTestId('shimmer-block').length).toBeGreaterThan(0);
    // The month picker -- part of the shell -- must stay mounted and usable while the new month's
    // report is still in flight.
    expect(screen.getByLabelText(/Month: May 26/)).toBeTruthy();
  });
});
```
- [x] **Step 2: Run test to verify it fails** — `npm test -- src/screens/ReportsScreen.test.tsx` — Expected: FAIL, no `shimmer-block` testIDs exist yet in ReportsScreen's output.
- [x] **Step 3: Write minimal implementation**
```tsx
// ReportsScreen.tsx -- import addition
import { SkeletonCard } from '../components/skeletons/Skeletons';
```
```tsx
// ReportsScreen.tsx:70-76, replacing the monthsLoading branch
  if (monthsLoading) {
    return (
      <ScrollView style={{ backgroundColor: c.bg }} contentContainerStyle={styles.content}>
        <SkeletonCard lines={2} />
        <View style={styles.totals}>
          <SkeletonCard style={styles.totalCard} lines={1} />
          <SkeletonCard style={styles.totalCard} lines={1} />
          <SkeletonCard style={styles.totalCard} lines={1} />
        </View>
        <SkeletonCard style={styles.section} lines={4} />
      </ScrollView>
    );
  }
```
```tsx
// ReportsScreen.tsx:150-151, replacing the reportLoading branch
      {reportLoading ? (
        <>
          <View style={styles.totals}>
            <SkeletonCard style={styles.totalCard} lines={1} />
            <SkeletonCard style={styles.totalCard} lines={1} />
            <SkeletonCard style={styles.totalCard} lines={1} />
          </View>
          <SkeletonCard style={styles.section} lines={4} />
        </>
      ) : reportError || !report ? (
```
Drop `ActivityIndicator` from the `react-native` import at the top of the file — both former call sites are gone.
- [x] **Step 4: Run test to verify it passes** — Expected: PASS
- [x] **Step 5: Commit** — `git add src/screens/ReportsScreen.tsx src/screens/ReportsScreen.test.tsx` / `git commit -m "feat(mobile): skeleton ReportsScreen's totals and category breakdown"`

---

### Task 7: Apply skeletons to InsightsScreen (and mount the disclaimer shell immediately)

**Files:**
- Modify: `src/screens/InsightsScreen.tsx:1-8` (imports), `:36-146` (restructure return)
- Test: Modify `src/screens/InsightsScreen.test.tsx` (append)

**Interfaces:**
- Consumes: `SkeletonCard` (Task 2).

The current `if (loading) return <ActivityIndicator/>` (lines 36-42) also hides the static disclaimer banner ("These are rule-based statistical observations… not an AI-generated assistant") — text that has no data dependency at all and should never wait on a network call.

- [x] **Step 1: Write the failing test**
```tsx
// appended to src/screens/InsightsScreen.test.tsx
it('shows the static notice and skeleton sections immediately, before either query resolves', () => {
  insights.get.mockReset().mockReturnValue(new Promise(() => {}));
  recurring.list.mockReset().mockReturnValue(new Promise(() => {}));

  renderScreen();

  expect(screen.getByText(/not an\s+AI-generated assistant/)).toBeTruthy();
  expect(screen.getAllByTestId('shimmer-block').length).toBeGreaterThan(0);
  expect(screen.queryByText("This Month's Observations")).toBeNull();
});
```
- [x] **Step 2: Run test to verify it fails** — `npm test -- src/screens/InsightsScreen.test.tsx` — Expected: FAIL, `getByText(/not an\s+AI-generated assistant/)` throws because the whole screen is currently one `ActivityIndicator` while `loading` is true.
- [x] **Step 3: Write minimal implementation**
```tsx
// InsightsScreen.tsx -- import addition
import { SkeletonCard } from '../components/skeletons/Skeletons';
```
```tsx
// InsightsScreen.tsx:36-146, replacing the early return and the rest of the function body
  return (
    <ScrollView
      style={{ backgroundColor: c.bg }}
      contentContainerStyle={styles.content}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={c.primary} />}
    >
      {/* Static -- no data dependency -- so it renders on the very first frame, before either
          query has a chance to resolve. */}
      <View style={[styles.notice, { backgroundColor: c.primaryLight, borderLeftColor: c.primary }]}>
        <Text style={[styles.noticeText, { color: c.ink }]}>
          These are rule-based statistical observations from your own transaction history — not an
          AI-generated assistant.
        </Text>
      </View>

      {loading ? (
        <>
          <SkeletonCard style={styles.section} lines={3} />
          <SkeletonCard style={styles.section} lines={4} />
          <SkeletonCard style={styles.section} lines={3} />
        </>
      ) : (
        <>
          <Card style={styles.section}>
            <SectionHeading title="This Month's Observations" />
            {insightsQ.isError ? (
              <Text style={[styles.error, { color: c.danger }]}>
                Couldn&apos;t load your insights — pull down to try again.
              </Text>
            ) : sentences.length === 0 ? (
              <EmptyState message="Nothing stands out this month yet — observations appear as more transactions land." />
            ) : (
              sentences.map((s, i) => (
                <View key={i} style={[styles.observation, { borderLeftColor: c.border }]}>
                  <Text style={[styles.observationText, { color: c.ink }]}>{s}</Text>
                </View>
              ))
            )}
          </Card>

          <Card style={styles.section}>
            <SectionHeading title="Recurring Payments & Subscriptions" />
            {recurringQ.isError ? (
              <Text style={[styles.error, { color: c.danger }]}>
                Couldn&apos;t load recurring payments — pull down to try again.
              </Text>
            ) : recurring.length === 0 ? (
              <EmptyState message="No recurring payments detected yet — this needs at least 2 charges from the same merchant on a regular interval to spot a pattern." />
            ) : (
              recurring.map((r) => (
                <View
                  key={r.merchant}
                  style={[styles.row, { borderBottomColor: c.border }]}
                  accessible
                  accessibilityLabel={`${r.merchant}, ${r.label}. ${fmtCurrency(r.averageAmount)} on average, seen ${
                    r.occurrences
                  } times. Next expected around ${fmtDate(r.nextEstimate) ?? r.nextEstimate}`}
                >
                  <View style={styles.rowMain}>
                    <Text style={[styles.rowTitle, { color: c.ink }]} numberOfLines={1}>
                      {r.merchant}
                    </Text>
                    <Text style={[styles.rowMeta, { color: c.mutedInk }]}>
                      {fmtCurrency(r.averageAmount)} · seen {r.occurrences}×
                    </Text>
                  </View>
                  <View style={styles.rowRight}>
                    <Text style={[styles.badge, { color: c.primary, backgroundColor: c.primaryLight }]}>{r.label}</Text>
                    <Text style={[styles.rowMeta, { color: c.mutedInk }]}>next ~{fmtDate(r.nextEstimate) ?? r.nextEstimate}</Text>
                  </View>
                </View>
              ))
            )}
          </Card>

          <Card style={styles.section}>
            <SectionHeading title="Category Movers" />
            {insightsQ.isError ? null : movers.length === 0 ? (
              <EmptyState message="Not enough history yet to compare trends — add a few months of transactions." />
            ) : (
              movers.map((m) => (
                <View
                  key={m.category}
                  style={[styles.row, { borderBottomColor: c.border }]}
                  accessible
                  accessibilityLabel={`${m.category}: ${fmtCurrency(m.current)} versus a usual ${fmtCurrency(
                    m.priorAverage
                  )}, ${(m.pctChange ?? 0) >= 0 ? 'up' : 'down'} ${Math.abs(m.pctChange ?? 0).toFixed(0)} percent`}
                >
                  <View style={styles.rowMain}>
                    <Text style={[styles.rowTitle, { color: c.ink }]} numberOfLines={1}>
                      {m.category}
                    </Text>
                    <Text style={[styles.rowMeta, { color: c.mutedInk }]}>
                      {fmtCurrency(m.current)} vs usual {fmtCurrency(m.priorAverage)}
                    </Text>
                  </View>
                  <Text style={[styles.delta, { color: (m.pctChange ?? 0) >= 0 ? c.danger : c.success }]}>
                    {(m.pctChange ?? 0) >= 0 ? '▲' : '▼'} {Math.abs(m.pctChange ?? 0).toFixed(0)}%
                  </Text>
                </View>
              ))
            )}
          </Card>
        </>
      )}
    </ScrollView>
  );
}
```
(The `styles.centered` rule can stay in the stylesheet even though nothing references it after this change, or be deleted — either is fine; deleting it is the tidier choice.) Drop `ActivityIndicator` from the `react-native` import.
- [x] **Step 4: Run test to verify it passes** — Expected: PASS
- [x] **Step 5: Commit** — `git add src/screens/InsightsScreen.tsx src/screens/InsightsScreen.test.tsx` / `git commit -m "feat(mobile): mount InsightsScreen's notice immediately, skeleton the sections"`

---

### Task 8: Fix DashboardScreen's pull-to-refresh indicator (Item B bug fix)

**Files:**
- Modify: `src/screens/DashboardScreen.tsx:85`
- Test: Modify `src/screens/DashboardScreen.test.tsx` (append)

**Interfaces:**
- Consumes: nothing new — this is a pure logic fix inside the existing component.

Audit result for Item B, across all 5 screens: `LedgerScreen` (`refreshing={isFetching && !isFetchingNextPage}`), `ReportsScreen` (`refreshing={isFetching && !reportLoading}`), `BudgetsScreen` (`refreshing={isFetching && !isLoading}`) and `InsightsScreen` (`refreshing = (insightsQ.isFetching || recurringQ.isFetching) && !loading`) all already gate their full-screen loading state on `isLoading`/`isPending` (true only with no cached data) and drive `RefreshControl`/`FlatList.refreshing` off `isFetching` — none of them clear already-rendered content on a background refetch. `DashboardScreen` is the one exception, and it's not a content-clearing bug but an indicator-completeness one: `refresh()` (line 87-90) invalidates six query keys, but

```tsx
const refreshing = summaryQ.isFetching && !summaryQ.isLoading;
```

only tracks `summaryQ`. If `summaryQ` resolves before `accountsQ`/`recentTxnsQ` do, the pull-to-refresh spinner disappears while those two are still in flight — the user releases the gesture believing the refresh finished when part of it hasn't.

- [x] **Step 1: Write the failing test**
```tsx
// appended to src/screens/DashboardScreen.test.tsx -- add `RefreshControl` to the 'react-native' import
import { RefreshControl } from 'react-native';

describe('pull-to-refresh indicator', () => {
  it('stays visible until every visible section has finished refetching, not just the summary', async () => {
    dashboard.summary.mockResolvedValue(emptySummary());
    const { queryClient } = renderScreen();
    await screen.findByText('Total Balance');

    // Summary resolves fast (still resolves via mockResolvedValue); accounts is held open on
    // purpose to prove the indicator has to track it too, not just summary.
    let resolveAccounts: (value: unknown) => void = () => {};
    accounts.list.mockReturnValue(new Promise((resolve) => { resolveAccounts = resolve; }));

    await act(async () => {
      void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
      void queryClient.invalidateQueries({ queryKey: ['accounts'] });
    });

    expect(screen.UNSAFE_getByType(RefreshControl).props.refreshing).toBe(true);

    await act(async () => resolveAccounts([]));

    expect(screen.UNSAFE_getByType(RefreshControl).props.refreshing).toBe(false);
  });
});
```
(Add `act` to the top-level `@testing-library/react-native` import if not already present in this file — it currently imports `fireEvent, render, screen, waitFor`.)
- [x] **Step 2: Run test to verify it fails** — `npm test -- src/screens/DashboardScreen.test.tsx` — Expected: FAIL, `refreshing` reads `false` right after `summaryQ` settles even though `accountsQ` is still fetching.
- [x] **Step 3: Write minimal implementation**
```tsx
// DashboardScreen.tsx:84-85
  const loading = summaryQ.isLoading || accountsQ.isLoading || recentTxnsQ.isLoading;
  const refreshing =
    (summaryQ.isFetching || accountsQ.isFetching || recentTxnsQ.isFetching) && !loading;
```
- [x] **Step 4: Run test to verify it passes** — Expected: PASS
- [x] **Step 5: Commit** — `git add src/screens/DashboardScreen.tsx src/screens/DashboardScreen.test.tsx` / `git commit -m "fix(mobile): dashboard pull-to-refresh now tracks accounts and transactions too"`

---

### Task 9: Dashboard shell capstone — per-section skeletons, no full-screen block

**Files:**
- Modify: `src/screens/DashboardScreen.tsx:1-17` (imports), `:84-331` (loading gates, error guard, and return)
- Test: Modify `src/screens/DashboardScreen.test.tsx` (append)

**Interfaces:**
- Consumes: `SkeletonCard`, `SkeletonChart`, `SkeletonTransactionRow` (Tasks 2-3); extends the `refreshing` expression from Task 8.
- Produces: the final `DashboardScreen` shape — greeting, section headings and the tab bar (already rendered unconditionally by `AppTabs`/`RootNavigator` regardless of data state — confirmed: `AppTabs` in `src/navigation/AppTabs.tsx` renders `Tab.Navigator` with no query dependency, and `RootNavigator.tsx` only blocks on `bootstrapping`/`navPersistence.isReady`, never on any screen's own data queries) all mount on first paint; only each data-dependent section body swaps between its skeleton and real content.

Today (lines 143-149) the whole screen is one `ActivityIndicator` gated on `summaryQ.isLoading || accountsQ.isLoading || recentTxnsQ.isLoading`. Two findings from reading the full component:

1. `accountsQ.data` is never read anywhere in this file — only `accountsQ.isLoading`/`accountsQ.isFetching` are used (for gating and for the refresh indicator). Blocking first paint on it is pure waste; it's dropped from the *initial-load* gate below (it stays part of the `refreshing` calculation from Task 8, since `refresh()` still explicitly re-fetches it and a user's pull gesture should track that).
2. The existing `if (!summary) return errorScreen` guard (currently reached only after the full-screen loading branch) is the correct, already-tested behavior for a genuinely failed request (see `DashboardScreen.test.tsx`'s "when /dashboard/summary fails" suite) — it must fire only on a *settled* failure, not while `summaryQ` is still loading for the first time.

- [x] **Step 1: Write the failing test**
```tsx
// appended to src/screens/DashboardScreen.test.tsx
describe('the shell mounts before the network settles (dashboard shell capstone)', () => {
  it('shows the greeting and section skeletons immediately, then swaps in real content once summary and recent transactions arrive', async () => {
    let resolveSummary: (value: unknown) => void = () => {};
    dashboard.summary.mockReturnValue(new Promise((resolve) => { resolveSummary = resolve; }));
    let resolveTxns: (value: unknown) => void = () => {};
    transactions.search.mockReturnValue(new Promise((resolve) => { resolveTxns = resolve; }));

    renderScreen();

    // The shell -- greeting and section headings -- is already on screen, not hidden behind a
    // full-screen spinner.
    expect(screen.getByText(/Good (morning|afternoon|evening|night)/)).toBeTruthy();
    expect(screen.getByText('Cash Flow')).toBeTruthy();
    expect(screen.getByText('Recent Transactions')).toBeTruthy();
    expect(screen.getAllByTestId('shimmer-block').length).toBeGreaterThan(0);
    expect(screen.queryByText('Total Balance')).toBeNull();

    await act(async () => {
      resolveSummary(emptySummary({ currentBalance: 4200 }));
      resolveTxns({ content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 });
    });

    expect(await screen.findByText('Total Balance')).toBeTruthy();
    expect(screen.queryByTestId('shimmer-block')).toBeNull();
  });

  it('skeletons Recent Transactions independently of the summary section', async () => {
    dashboard.summary.mockResolvedValue(emptySummary());
    let resolveTxns: (value: unknown) => void = () => {};
    transactions.search.mockReturnValue(new Promise((resolve) => { resolveTxns = resolve; }));

    renderScreen();

    expect(await screen.findByText('Total Balance')).toBeTruthy();
    // Summary has already settled, but the transactions section is still on its own skeleton.
    expect(screen.getAllByTestId('skeleton-transaction-row').length).toBeGreaterThan(0);

    await act(async () => resolveTxns({ content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 }));

    expect(await screen.findByText(/No transactions yet/i)).toBeTruthy();
  });
});
```
- [x] **Step 2: Run test to verify it fails** — `npm test -- src/screens/DashboardScreen.test.tsx` — Expected: FAIL, `screen.getByText('Cash Flow')` throws because the current code renders only an `ActivityIndicator` while `summaryQ.isLoading` is true.
- [x] **Step 3: Write minimal implementation**

Replace `DashboardScreen.tsx:1-17` (imports):
```tsx
import { useMemo, useState } from 'react';
import {
  Pressable, RefreshControl, ScrollView, StyleSheet, Text, useWindowDimensions, View,
} from 'react-native';
import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { usePreventScreenCapture } from 'expo-screen-capture';
import { Card, EmptyState, SectionHeading } from '../components/Card';
import { SkeletonCard, SkeletonChart, SkeletonTransactionRow } from '../components/skeletons/Skeletons';
import { DonutChart, type Slice } from '../components/charts/DonutChart';
import { CashFlowChart } from '../components/charts/CashFlowChart';
import {
  accountsApi, dashboardApi, goalsApi, insightsApi, reportsApi, transactionsApi, userApi,
} from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import { fmtCurrency, greeting, monthLabel } from '../lib/format';
import { useLargeFontScale } from '../lib/useLargeFontScale';
import { radius, spacing, useTheme } from '../theme';
```

Replace `DashboardScreen.tsx:84-331` — everything from `const loading = ...` through the end of the component (the `useQueries`/`useQuery`/`useMemo` hooks above line 84 are unchanged):
```tsx
  // accountsQ.data is never read on this screen (only its loading/fetching state, for
  // pre-warming and for the refresh indicator below) -- see this task's own note. It stays out of
  // the initial-load gate so the shell doesn't wait on a fetch whose result isn't rendered here.
  const initialLoad = summaryQ.isLoading || recentTxnsQ.isLoading;
  const refreshing =
    (summaryQ.isFetching || accountsQ.isFetching || recentTxnsQ.isFetching) && !initialLoad;

  function refresh() {
    ['dashboard-summary', 'accounts', 'recent-transactions', 'goals', 'insights', 'report-months', 'report']
      .forEach((key) => void queryClient.invalidateQueries({ queryKey: [key] }));
  }

  const summary = summaryQ.data;
  const recentTxns = recentTxnsQ.data?.content ?? [];
  const goals = (goalsQ.data ?? []).slice(0, 2);
  const sentences = insightsQ.data?.sentences ?? [];
  const firstName = fullName?.split(' ')[0] ?? 'there';

  const donutSlices: Slice[] = useMemo(() => {
    if (!summary) return [];
    const sorted = Object.entries(summary.spendByCategory).sort((a, b) => b[1] - a[1]);
    if (sorted.length <= DONUT_COLORS.length) {
      return sorted.map(([label, value], i) => ({ label, value, color: DONUT_COLORS[i] }));
    }
    const named = sorted.slice(0, DONUT_COLORS.length - 1);
    const rest = sorted.slice(DONUT_COLORS.length - 1).reduce((sum, [, value]) => sum + value, 0);
    const collidingIndex = named.findIndex(([label]) => label === OTHER_LABEL);
    if (collidingIndex >= 0) {
      return named.map(([label, value], i) => ({
        label,
        value: i === collidingIndex ? value + rest : value,
        color: DONUT_COLORS[i],
      }));
    }
    return [
      ...named.map(([label, value], i) => ({ label, value, color: DONUT_COLORS[i] })),
      { label: OTHER_LABEL, value: rest, color: DONUT_COLORS[DONUT_COLORS.length - 1] },
    ];
  }, [summary]);

  // Settled failure only. "Still loading" (summaryQ.isLoading with no cached data yet) falls
  // through to the shell below, which shows its own per-section skeletons instead of blocking the
  // whole screen -- see this task's own note on why this guard's condition changed.
  if (!summaryQ.isLoading && !summary) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <Text style={[styles.errorText, { color: c.muted }]}>Couldn't load your dashboard.</Text>
        <Pressable onPress={refresh} hitSlop={12} accessibilityRole="button">
          <Text style={[styles.retry, { color: c.primary }]}>Try again</Text>
        </Pressable>
      </View>
    );
  }

  const periodIsCurrent = summary ? (summary.reportingMonthIsCurrent || !summary.reportingMonth) : true;
  const periodLabel = periodIsCurrent ? 'this month' : monthLabel(summary!.reportingMonth!);
  const deltaLabel = periodIsCurrent
    ? 'vs last month'
    : `vs the month before ${monthLabel(summary!.reportingMonth!)}`;
  const deltaSpokenLabel = periodIsCurrent
    ? 'versus last month'
    : `versus the month before ${monthLabel(summary!.reportingMonth!)}`;

  const kpis = summary
    ? [
        { label: 'Total Balance', value: fmtCurrency(summary.currentBalance), delta: null as number | null, invert: false },
        { label: 'Income', value: fmtCurrency(summary.monthlyIncome), delta: summary.incomeDeltaPct, invert: false },
        { label: 'Expenses', value: fmtCurrency(summary.monthlyExpense), delta: summary.expenseDeltaPct, invert: true },
        { label: 'Net Savings', value: fmtCurrency(summary.netCashFlow), delta: summary.netDeltaPct, invert: false },
      ]
    : [];

  const chartWidth = width - spacing.md * 2 - spacing.md * 2;

  return (
    <ScrollView
      style={{ backgroundColor: c.bg }}
      contentContainerStyle={[styles.content, { paddingTop: insets.top + spacing.md }]}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={c.primary} />}
    >
      <Text style={[styles.greeting, { color: c.ink }]}>
        {greeting(settingsQ.data?.timezone)}, {firstName}
      </Text>
      <Text style={[styles.subGreeting, { color: c.muted }]}>
        Here's what's happening with your finances.
        {!periodIsCurrent && ` Your latest figures are from ${periodLabel}.`}
      </Text>

      <View style={styles.kpiGrid}>
        {summary
          ? kpis.map((k) => (
              <Card key={k.label} style={styles.kpiCard}>
                <View
                  accessible
                  accessibilityLabel={
                    k.delta !== null && k.delta !== undefined
                      ? `${k.label}: ${k.value}, ${k.delta >= 0 ? 'up' : 'down'} ${Math.abs(k.delta).toFixed(1)} percent ${deltaSpokenLabel}`
                      : `${k.label}: ${k.value}`
                  }
                >
                  <Text style={[styles.kpiLabel, { color: c.muted }]}>{k.label}</Text>
                  <Text style={[styles.kpiValue, { color: c.ink }]} numberOfLines={1} adjustsFontSizeToFit>
                    {k.value}
                  </Text>
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
            ))
          : [0, 1, 2, 3].map((i) => <SkeletonCard key={i} style={styles.kpiCard} lines={1} />)}
      </View>

      <Card style={styles.section}>
        <SectionHeading
          title="Cash Flow"
          action={
            <View style={[styles.rangeRow, { borderColor: c.border }]}>
              {(Object.keys(RANGE_MONTHS) as CashFlowRange[]).map((r) => (
                <Pressable
                  key={r}
                  onPress={() => setCashFlowRange(r)}
                  accessibilityRole="button"
                  accessibilityState={{ selected: cashFlowRange === r }}
                  accessibilityLabel={`Show ${RANGE_MONTHS[r]} months`}
                  style={[styles.rangeChip, cashFlowRange === r && { backgroundColor: c.primaryLight }]}
                >
                  <Text style={[styles.rangeText, { color: cashFlowRange === r ? c.primary : c.muted }]}>{r}</Text>
                </Pressable>
              ))}
            </View>
          }
        />
        {summary ? <CashFlowChart points={cashFlowPoints} width={chartWidth} /> : <SkeletonChart width={chartWidth} />}
      </Card>

      <Card style={styles.section}>
        <SectionHeading title="Spending by Category" />
        {summary ? (
          donutSlices.length === 0 ? (
            <EmptyState message={`No spending recorded ${periodLabel} yet.`} />
          ) : (
            <DonutChart
              slices={donutSlices}
              centerLabel={fmtCurrency(donutSlices.reduce((s, x) => s + x.value, 0))}
            />
          )
        ) : (
          <SkeletonChart variant="donut" />
        )}
      </Card>

      <Card style={styles.section}>
        <SectionHeading title="Recent Transactions" />
        {recentTxnsQ.isLoading ? (
          <>
            <SkeletonTransactionRow />
            <SkeletonTransactionRow />
            <SkeletonTransactionRow />
          </>
        ) : recentTxns.length === 0 ? (
          <EmptyState message="No transactions yet. Import a statement to get started." />
        ) : (
          recentTxns.map((t) => (
            <View key={t.id} style={[styles.txnRow, { borderBottomColor: c.border }]}>
              <View style={styles.txnMain}>
                <Text style={[styles.txnDesc, { color: c.ink }]} numberOfLines={largeText ? 2 : 1}>
                  {t.description || t.merchant || 'Transaction'}
                </Text>
                <Text style={[styles.txnMeta, { color: c.mutedInk }]} numberOfLines={1}>
                  {t.categoryName} · {t.date}
                </Text>
              </View>
              <Text style={[styles.txnAmount, { color: t.type === 'INCOME' ? c.success : c.ink }]}>
                {t.type === 'INCOME' ? '+' : '-'}
                {fmtCurrency(Math.abs(t.amount))}
              </Text>
            </View>
          ))
        )}
      </Card>

      {goals.length > 0 ? (
        <Card style={styles.section}>
          <SectionHeading title="Goals" />
          {goals.map((g) => {
            const pct = g.targetAmount > 0 ? Math.min(100, (g.currentAmount / g.targetAmount) * 100) : 0;
            return (
              <View key={g.id} style={styles.goalRow}>
                <View style={styles.goalHeader}>
                  <Text style={[styles.goalName, { color: c.ink }]} numberOfLines={largeText ? 2 : 1}>{g.name}</Text>
                  <Text style={[styles.goalPct, { color: c.mutedInk }]}>{pct.toFixed(0)}%</Text>
                </View>
                <View style={[styles.progressTrack, { backgroundColor: c.border }]}>
                  <View style={[styles.progressFill, { width: `${pct}%`, backgroundColor: c.primary }]} />
                </View>
                <Text style={[styles.goalMeta, { color: c.mutedInk }]}>
                  {fmtCurrency(g.currentAmount)} of {fmtCurrency(g.targetAmount)}
                </Text>
              </View>
            );
          })}
        </Card>
      ) : null}

      {sentences.length > 0 ? (
        <Card style={styles.section}>
          <SectionHeading title="Insights" />
          {sentences.slice(0, 3).map((s) => (
            <Text key={s} style={[styles.insight, { color: c.ink }]}>
              • {s}
            </Text>
          ))}
        </Card>
      ) : null}
    </ScrollView>
  );
}
```
- [x] **Step 4: Run test to verify it passes** — Expected: PASS. Also re-run `npm test -- src/screens/DashboardScreen.test.tsx` in full to confirm the pre-existing suites (`when /dashboard/summary fails`, `M0-A: the spending donut…`, `when the dashboard is legitimately empty`, `large Dynamic Type support`) still pass unmodified — they all assert on *settled* states reached via `mockResolvedValue`/`mockRejectedValue`, none of which the loading-state restructuring touches.
- [x] **Step 5: Commit** — `git add src/screens/DashboardScreen.tsx src/screens/DashboardScreen.test.tsx` / `git commit -m "feat(mobile): dashboard shell mounts immediately with per-section skeletons"`

---

## Operational Checklist: Production Sentry (Item D — not a code task)

`src/lib/monitoring.ts` is fully wired with PII scrubbing and no-ops cleanly when `EXPO_PUBLIC_SENTRY_DSN` is unset (verified: `src/test/setup.ts` relies on exactly this — it mocks `@sentry/react-native` and leaves the DSN unset so no test can emit a real event). `eas.json`'s four build profiles (`development`, `preview`, `dev`, `production`) never set `EXPO_PUBLIC_SENTRY_DSN` in their `env` blocks. This needs a real Sentry DSN from Sid's own Sentry org — no engineering task can supply that credential. Steps:

1. In the Sentry dashboard, open the Fynora mobile project's Settings → Client Keys (DSN), and copy the DSN value.
2. Add `EXPO_PUBLIC_SENTRY_DSN` as an EAS environment variable for the `preview` and `production` profiles in `eas.json` (`eas env:create --scope project --environment preview --name EXPO_PUBLIC_SENTRY_DSN` and the same for `production`) — the `development`/`dev` profiles should stay unset so local/dev-client builds keep no-oping, matching how `APP_VARIANT` already separates dev from production identity in `app.config.ts`.
3. Trigger a test build on the `preview` profile (`eas build --profile preview --platform ios` or `android`) and install it.
4. Force a test crash or exception in that build (e.g., a temporary `throw new Error('sentry-smoke-test')` behind a debug-only control, removed afterward) and confirm the event appears in the Sentry dashboard within a few minutes.
5. Verify source maps resolve to real file/line numbers rather than minified bundle offsets. `app.config.ts` already conditionally applies `withSentry(config, { organization: sentryOrg, project: sentryProject })` at the bottom of the file, gated on `process.env.SENTRY_ORG` and `process.env.SENTRY_PROJECT` both being present — confirm those two are also set as EAS secrets for the build profile (alongside a `SENTRY_AUTH_TOKEN`, which the plugin's upload step needs and which isn't referenced anywhere else in this codebase), then re-check step 4's crash event for resolved symbols instead of a minified stack.
6. Confirm the crash event in Sentry shows the correct release/version string (from `eas.json`'s `production` profile's `autoIncrement` and `app.config.ts`'s `version: '1.0.0'`), so crashes can be attributed to the build that shipped them.

## Self-Review Notes

- **Spec coverage:** Skeleton Loading System (Tasks 1-7), Stale-While-Revalidate UX (Task 8's bug fix + the audit note covering all 5 screens), Dashboard Shell (Task 9), Production Sentry (Operational Checklist). All four Phase 1 brief items are covered; axios timeout and splash-flash are correctly excluded as already merged.
- **Placeholder scan:** Task 9 Step 3 originally risked being summarized rather than shown in full during assembly — corrected to include the complete real `DashboardScreen.tsx` replacement code rather than a description of it.
- **Type consistency:** `SkeletonChart`'s `variant` prop (`'bar' | 'donut'`) is used consistently across Task 3's definition and Task 9's call sites. `SkeletonCard`'s `lines`/`style` props match between Task 2's definition and Tasks 6/7/9's call sites.
- **2026-08-30 execution note (Task 9, and retroactively Tasks 4/8):** the "hold a promise open, resolve it later" test pattern (`let resolveX: (value: unknown) => void = () => {}; mock.mockReturnValue(new Promise((resolve) => { resolveX = resolve; }));`) fails `tsc --noEmit` under this project's strict function-type variance — the real `resolve`'s inferred parameter type (e.g. `Account[] | PromiseLike<Account[]>`) is a narrower type than the pre-declared `(value: unknown) => void`, and TS's contravariant check for function-type variables (not method syntax) rejects the assignment. Fixed at every occurrence with `resolveX = resolve as typeof resolveX;`. Apply this cast on any future use of the same pattern.
- **2026-08-30 execution note (Task 8):** the plan's original test asserted `refreshing` synchronously right after `act(async () => { invalidateQueries(); invalidateQueries(); })` with no internal `await`. Under React 19, the QueryClient's own state (`fetchStatus: 'fetching'`, confirmed via `queryClient.getQueryState`) flips correctly and synchronously, but the component's re-render via the query observer's subscriber can land a tick after `act()`'s own flush completes — so the direct `expect(...).toBe(true)` was flaky-false. Fixed by wrapping both `refreshing` assertions in `await waitFor(() => expect(...))`. Apply the same pattern to any future test that asserts on a React Query-driven UI value immediately after `invalidateQueries`/`refetch` inside a synchronous `act` callback.
- **2026-08-30 execution note:** Task 3's own implementation and test disagreed with each other (`SkeletonBudgetCard` rendered 4 `shimmer-block`s — a split category/amount header — but its own test expected 3). Fixed during execution by collapsing the header to a single shimmer, matching the test's "header, progress bar, footer" description; `budgetHeader` style removed as a result (now unused). Also found during Task 1 and applied throughout: RNTL query calls against skeleton testIDs need `{ hidden: true }` (see Global Constraints), and `Animated.Value` singletons need `useState(() => ...)` not `useRef(...).current` (this project's `react-hooks/refs` lint rule forbids the latter). Task 6's new `describe('skeleton loading', ...)` block, as written, was appended as a SIBLING of `describe('ReportsScreen', ...)` rather than nested inside it — meaning it never inherited that block's `beforeEach` mock reset, so the second new test silently hung on a still-pending mock left over from the first. Fixed by nesting the new describe block inside the existing one, matching how every other test group in the file is structured. This same trap applies to Task 7 (`InsightsScreen.test.tsx`'s top-level `it(...)` calls, not a `describe`, so no nesting issue there) and any future appended test block in a file with a shared `beforeEach` — append INSIDE the existing describe, not after its closing brace.
</content>
