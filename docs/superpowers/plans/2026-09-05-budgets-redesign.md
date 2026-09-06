# Budgets Page Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Replace the bare 125-line CRUD form at `frontend/src/pages/Budgets.tsx` with a real dashboard-grade Budgets page — stat cards, per-category rows with icons and status pills, and a spending-breakdown donut chart — while keeping every existing behavior (create/update a budget, cache invalidation) unchanged.

**Architecture:** Pure frontend change, one page + one shared design-system primitive. No new backend endpoints and no new API calls beyond one already-existing one (`categoriesApi.list()`) the page doesn't currently call. Reuses existing design-system components (`FinoraCard`, `MetricCard`, `SectionHeader`, `ChartContainer`, `EmptyState`, `Skeleton`, `Button`) and extends `Badge` with three new status tones rather than inventing a new "StatusPill" component.

**Tech Stack:** React 18 + TypeScript, Vite, Tailwind (CSS-variable tokens in `frontend/src/index.css`), TanStack Query (cache invalidation only — this page keeps its existing local-state fetch pattern, not a `useQuery` migration), Chart.js + `react-chartjs-2` (`Doughnut`), Vitest + `@testing-library/react`.

**Spec:** No separate written spec file exists. This plan's own "Source of truth" section below distills the spec directly from (a) the 9 Fynora web-dashboard mockups in `Prototype images/` (specifically the Budgets mockup, `Codex Image 5 Sept 2026, 20_54_47.png`) and (b) the in-session gap analysis and palette decision made against the live `frontend/` app. Treat this file as the spec of record for this page.

## Source of truth: what the mockup shows vs. what this plan actually builds

The mockup's Budgets screen has: 4 stat cards (Total Spend with a radial % ring, Budgets-on-track, Days-left-in-month, and a "Create Budget" CTA card), a category list with icons + colored progress bars + status pills, a spending-breakdown donut, a daily-spending trend chart, and a "Recent Budget Alerts" panel.

This plan deliberately builds a **subset**, grounded in what the current backend actually exposes (`GET /budgets` → `Budget[]` with `id, categoryId, categoryName, monthlyLimit, spentThisMonth` — see `frontend/src/types/index.ts:236-242` — and `GET /categories` → `CategoryOption[]` with `id, name, isSystem, icon, color` — see `frontend/src/api/endpoints.ts:667-672`). Two mockup elements are **explicitly deferred, not built**, because there is no supporting data:

- **Daily spending trend chart** — there is no daily-granularity spending endpoint anywhere in `frontend/src/api/endpoints.ts`. Building this would require new backend work, which is out of scope for a frontend redesign plan.
- **"Recent Budget Alerts" panel** — there is no alerts/notifications-per-budget endpoint. `DashboardSummary.notifications` (`frontend/src/types/index.ts`) is a generic string list with no structured link back to a specific budget/category, so it cannot honestly power a per-budget alert list without guessing at string parsing.

The mockup's radial "% used" progress ring is also simplified to plain text under a `MetricCard` — there is no circular-progress primitive in `frontend/src/design-system` today, and building one is a separate, reusable-component decision that deserves its own plan rather than a one-off inline SVG buried in this page.

The mockup's 4th stat card ("+ Create Budget" opening what is presumably a modal) is **not built as a modal** — there is no generic modal primitive in `frontend/src/design-system` (`ConfirmDialog` is confirm-specific, not a generic dialog). This plan keeps the existing always-visible inline create/update form (`Budgets.tsx`'s current top section), restyled to match the new visual language, rather than introducing a new modal component as a side effect of a page redesign.

## Global Constraints

- Palette: dark graphite `#262A33` (`--color-primary`, `--color-primary-dark: #15171C`) and warm cream `#F4F1EC` (`--color-primary-light`) — **no purple/indigo**, per the explicit in-session decision to match the live app, not the mockup's purple palette.
- No new backend endpoints. Every data point rendered must come from `budgetsApi.list()` (already used) or `categoriesApi.list()` (already exists, not yet called from this page).
- Every existing behavior in `Budgets.tsx` must be preserved: `budgetsApi.upsert()` on save, `queryClient.invalidateQueries({ queryKey: ['budgets'] })` and `queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] })` after a successful save (`frontend/src/pages/Budgets.tsx:58-59`).
- Reuse existing design-system primitives (`frontend/src/design-system/*`) instead of hand-rolled markup. Only new primitive addition allowed in this plan: three new `Badge` tones.
- No `Co-Authored-By` / AI-attribution trailer in any commit message (repo-wide rule, `CLAUDE.md`).
- Follow the existing per-file Chart.js registration convention (`ChartJS.register(...)` called locally in the page file, as `Investments.tsx` and `Dashboard.tsx` both already do) — no new shared chart-setup module.

---

## File Structure

- **Modify:** `frontend/src/design-system/Badge.tsx` — add `success`, `warning`, `danger` tones to the existing `TONE` map.
- **Modify:** `frontend/src/design-system/Badge.test.tsx` — cover the three new tones.
- **Modify:** `frontend/src/pages/Budgets.tsx` — full redesign of the render tree; data-fetching logic (`load()`, `addOrUpdate()`) stays behaviorally identical, extended to also fetch categories.
- **Modify:** `frontend/src/pages/Budgets.test.tsx` — extend existing tests for the new markup/queries, add new tests for stat cards, status pills, and the donut chart's empty/loaded states.

No new files. `Budgets.tsx` stays a single page file — at ~125 lines today and roughly 220-260 after this redesign, it does not cross into "split this file" territory (compare `Dashboard.tsx` at 1099 lines, which the codebase already tolerates as a single file).

---

### Task 1: Extend `Badge` with `success` / `warning` / `danger` tones

**Files:**
- Modify: `frontend/src/design-system/Badge.tsx`
- Test: `frontend/src/design-system/Badge.test.tsx`

**Interfaces:**
- Consumes: nothing new — extends the existing `Badge({ tone, label, className })` component (`frontend/src/design-system/Badge.tsx:13`).
- Produces: `Badge` now accepts `tone: 'primary' | 'neutral' | 'success' | 'warning' | 'danger'`. Task 4 consumes this directly as the budget status pill (`success` = "On track", `warning` = "Almost there", `danger` = "Over budget").

- [x] **Step 1: Write the failing tests**

Add to `frontend/src/design-system/Badge.test.tsx` (after the existing "neutral" tone test):

```tsx
  it('applies the "success" tone when requested', () => {
    render(<Badge label="On track" tone="success" />);
    expect(screen.getByText('On track')).toHaveClass('text-success');
  });

  it('applies the "warning" tone when requested', () => {
    render(<Badge label="Almost there" tone="warning" />);
    expect(screen.getByText('Almost there')).toHaveClass('text-warning');
  });

  it('applies the "danger" tone when requested', () => {
    render(<Badge label="Over budget" tone="danger" />);
    expect(screen.getByText('Over budget')).toHaveClass('text-danger');
  });
```

- [x] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx vitest run src/design-system/Badge.test.tsx`
Expected: the three new tests FAIL — `tone="success"`/`"warning"`/`"danger"` currently fall through `TONE[tone]`, which is `undefined` for keys not in the map, so the rendered class list never contains `text-success`/`text-warning`/`text-danger`.

- [x] **Step 3: Implement the new tones**

Replace the `TONE` map in `frontend/src/design-system/Badge.tsx`:

```tsx
const TONE = {
  primary: 'bg-primary/15 text-primary',
  neutral: 'bg-bg text-muted border border-border',
  success: 'bg-success-bg text-success',
  warning: 'bg-warning-bg text-warning',
  danger: 'bg-danger-bg text-danger',
} as const;
```

(`--color-success-bg`, `--color-warning-bg`, `--color-danger-bg` and their Tailwind tokens `success-bg`/`warning-bg`/`danger-bg` already exist in `frontend/src/index.css:42-47` and `frontend/tailwind.config.js` — no CSS changes needed.)

Update the doc comment above the function (currently says "the only badges anywhere in the app today" and "no tier/entitlement logic" — both still true, just note the tone count grew):

```tsx
/**
 * Extracted from Dashboard's one-off "Beta" pill and its recurring-item cadence pill (both the
 * same primary-tinted style) -- the only badges anywhere in the app today. "primary" names the
 * tone, not the word "Beta" -- this is also what Recurring's "Monthly"/"Weekly" labels use.
 * success/warning/danger added for Budgets' status pills (On track / Almost there / Over budget).
 * Deliberately just a visual primitive: no tier/entitlement logic. PR4 (Premium Layer, gated on
 * D-7) is what decides what a "Plus"/"Premium" tone means and whether it's shown at all.
 */
```

- [x] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/design-system/Badge.test.tsx`
Expected: all tests PASS (the 4 pre-existing ones plus the 3 new ones).

- [x] **Step 5: Commit**

```bash
git add frontend/src/design-system/Badge.tsx frontend/src/design-system/Badge.test.tsx
git commit -m "feat(design-system): add success/warning/danger tones to Badge"
```

---

### Task 2: Fetch categories and build an icon/color lookup in `Budgets.tsx`

**Files:**
- Modify: `frontend/src/pages/Budgets.tsx`
- Test: `frontend/src/pages/Budgets.test.tsx`

**Interfaces:**
- Consumes: `categoriesApi.list()` → `Promise<CategoryOption[]>` (`frontend/src/api/endpoints.ts:667-676`, already defined, not yet imported in this file). `CategoryOption = { id: string; name: string; isSystem: boolean; icon: string; color: string }`. `ICON_COMPONENTS: Record<string, LucideIcon>` and `COLOR_HEX: Record<string, string>` from `frontend/src/lib/categoryIcons.ts` (already used the same way by `Dashboard.tsx:21,762`).
- Produces: a `categoriesById: Map<string, CategoryOption>` value in component state, keyed by `CategoryOption.id`, matched against `Budget.categoryId` (`frontend/src/types/index.ts:238`). Task 4 consumes this map to render each budget row's icon and color.

- [x] **Step 1: Write the failing test**

Extend the `vi.mock('../api/endpoints', ...)` block at the top of `frontend/src/pages/Budgets.test.tsx` to also mock `categoriesApi`:

```tsx
vi.mock('../api/endpoints', () => ({
  budgetsApi: { list: vi.fn(), upsert: vi.fn() },
  categoriesApi: { list: vi.fn() },
}));
```

Update the import line to also bring in `categoriesApi`:

```tsx
import { budgetsApi, categoriesApi } from '../api/endpoints';
```

Add a `category()` fixture helper next to the existing `budget()` helper:

```tsx
import type { Budget } from '../types';
import type { CategoryOption } from '../api/endpoints';

function category(overrides: Partial<CategoryOption> = {}): CategoryOption {
  return {
    id: 'c1',
    name: 'Dining',
    isSystem: true,
    icon: 'utensils',
    color: 'orange',
    ...overrides,
  };
}
```

Make every existing test that calls `budgetsApi.list.mockResolvedValue(...)` also stub `categoriesApi.list`, so the page's new fetch has something to resolve — add this single line inside the existing `beforeEach`:

```tsx
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(categoriesApi.list).mockResolvedValue([]);
  });
```

(Any individual test that needs specific categories overrides this with its own `mockResolvedValueOnce`/`mockResolvedValue` call — Task 4's tests do exactly that.)

Add a new test proving the categories fetch actually happens:

```tsx
  it('fetches categories alongside budgets, to look up each budget row\'s icon and color', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([budget({ categoryId: 'c1', categoryName: 'Dining' })]);
    vi.mocked(categoriesApi.list).mockResolvedValue([category({ id: 'c1' })]);

    renderPage();

    await waitFor(() => expect(categoriesApi.list).toHaveBeenCalledTimes(1));
  });
```

Also add `categoryId: 'c1'` to the existing `budget()` fixture's defaults, since `Budget.categoryId` is required by the type and every other test constructs one via this helper:

```tsx
function budget(overrides: Partial<Budget> = {}): Budget {
  return {
    id: 'b1',
    categoryId: 'c1',
    categoryName: 'Dining',
    monthlyLimit: 5000,
    spentThisMonth: 2000,
    ...overrides,
  } as Budget;
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/Budgets.test.tsx -t "fetches categories"`
Expected: FAIL — `categoriesApi.list` is never called, because `Budgets.tsx` doesn't import or call it yet.

- [x] **Step 3: Implement the categories fetch and lookup map**

In `frontend/src/pages/Budgets.tsx`, add the import:

```tsx
import { budgetsApi, categoriesApi, type CategoryOption } from '../api/endpoints';
```

Add state and extend `load()`:

```tsx
  const [categoriesById, setCategoriesById] = useState<Map<string, CategoryOption>>(new Map());

  function load() {
    setLoading(true);
    Promise.all([budgetsApi.list(), categoriesApi.list()])
      .then(([budgetList, categoryList]) => {
        setBudgets(budgetList);
        setCategoriesById(new Map(categoryList.map((c) => [c.id, c])));
      })
      .catch(() => setError('Could not load budgets.'))
      .finally(() => setLoading(false));
  }
```

(This replaces the current single-promise `budgetsApi.list().then(setBudgets).catch(...).finally(...)` body — same error message, same `loading` gating, now resolving both requests together so the skeleton covers both.)

- [x] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/pages/Budgets.test.tsx`
Expected: all tests PASS, including the pre-existing 4 (now updated with `categoryId` and the `categoriesApi.list` stub) and the 1 new one.

- [x] **Step 5: Commit**

```bash
git add frontend/src/pages/Budgets.tsx frontend/src/pages/Budgets.test.tsx
git commit -m "feat(budgets): fetch categories alongside budgets for icon/color lookup"
```

---

### Task 3: Add the stat-cards row (Total Spend, Total Budget, Budgets on Track, Days Left)

**Files:**
- Modify: `frontend/src/pages/Budgets.tsx`
- Test: `frontend/src/pages/Budgets.test.tsx`

**Interfaces:**
- Consumes: `MetricCard` (`frontend/src/design-system/MetricCard.tsx:29`, props `{ label, value, icon, iconBg, iconColor, valueColor? }` — this page uses none of the optional `delta*`/`gateReasonText`/`moverLines` props, since there's no prior-month comparison data for budgets), `Skeleton.Card`/`Skeleton.Region` (`frontend/src/design-system/Skeleton.tsx`), icons `Wallet`, `PiggyBank`, `CheckCircle2`, `CalendarClock` from `lucide-react`.
- Produces: a `daysLeftInMonth()` pure function local to `Budgets.tsx`, used only by this task.

- [x] **Step 1: Write the failing tests**

Add to `frontend/src/pages/Budgets.test.tsx`:

```tsx
  it('shows the four stat cards once budgets and categories have loaded', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([
      budget({ id: 'b1', categoryId: 'c1', categoryName: 'Dining', monthlyLimit: 10000, spentThisMonth: 8400 }),
      budget({ id: 'b2', categoryId: 'c2', categoryName: 'Shopping', monthlyLimit: 8000, spentThisMonth: 4230 }),
    ]);
    renderPage();

    // Total Spend = 8400 + 4230 = 12630; Total Budget = 10000 + 8000 = 18000
    expect(await screen.findByText('₹12,630')).toBeInTheDocument();
    expect(screen.getByText('₹12,630 / ₹18,000')).toBeInTheDocument();
    // Budgets on Track: "on track" is < 90% used. Dining is 84% (on track), Shopping is 53% (on track) -> 2 of 2.
    expect(screen.getByText('2 of 2')).toBeInTheDocument();
    expect(screen.getByText('Budgets on Track')).toBeInTheDocument();
    expect(screen.getByText('Days Left')).toBeInTheDocument();
  });

  it('counts a budget at or above 90% used as not on track', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([
      budget({ id: 'b1', categoryId: 'c1', monthlyLimit: 4000, spentThisMonth: 3800 }), // 95%, not on track
      budget({ id: 'b2', categoryId: 'c2', monthlyLimit: 8000, spentThisMonth: 4230 }), // 53%, on track
    ]);
    renderPage();

    expect(await screen.findByText('1 of 2')).toBeInTheDocument();
  });
```

- [x] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx vitest run src/pages/Budgets.test.tsx -t "stat cards"`
Expected: FAIL — none of this markup exists yet.

- [x] **Step 3: Implement the stat cards**

Add imports to `frontend/src/pages/Budgets.tsx`:

```tsx
import { Wallet, PiggyBank, CheckCircle2, CalendarClock } from 'lucide-react';
import { MetricCard } from '../design-system';
```

(`MetricCard` joins the existing `import { FinoraCard, EmptyState, Button, Skeleton } from '../design-system';` line — combine into one import statement.)

Add a pure helper above the component (same file, module scope, next to the existing `fmt()` helper):

```tsx
function daysLeftInMonth(): number {
  const now = new Date();
  const lastDayOfMonth = new Date(now.getFullYear(), now.getMonth() + 1, 0).getDate();
  return lastDayOfMonth - now.getDate();
}
```

Compute the derived stats inside the component, above the `return`:

```tsx
  const totalSpend = budgets.reduce((sum, b) => sum + b.spentThisMonth, 0);
  const totalLimit = budgets.reduce((sum, b) => sum + b.monthlyLimit, 0);
  const onTrackCount = budgets.filter((b) => b.monthlyLimit > 0 && (b.spentThisMonth / b.monthlyLimit) * 100 < 90).length;
```

Insert the stat-cards row into the JSX, immediately after the opening `<div className="space-y-4">` and before the existing create/update `FinoraCard` (moved to Task 6's restyle — for now it stays where it is, this task only adds the row above it):

```tsx
      {loading ? (
        showSkeleton && (
          <Skeleton.Region label="Loading budget summary" className="grid grid-cols-2 md:grid-cols-3 gap-4">
            {[0, 1, 2].map((i) => <Skeleton.Card key={i} />)}
          </Skeleton.Region>
        )
      ) : (
        <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
          <MetricCard
            label="Total Spend"
            value={fmt(totalSpend)}
            icon={Wallet}
            iconBg="bg-primary-light"
            iconColor="text-primary"
          />
          <MetricCard
            label="Total Budget"
            value={`${fmt(totalSpend)} / ${fmt(totalLimit)}`}
            icon={PiggyBank}
            iconBg="bg-primary-light"
            iconColor="text-primary"
          />
          <MetricCard
            label="Budgets on Track"
            value={`${onTrackCount} of ${budgets.length}`}
            icon={CheckCircle2}
            iconBg="bg-success-bg"
            iconColor="text-success"
          />
          <MetricCard
            label="Days Left"
            value={String(daysLeftInMonth())}
            icon={CalendarClock}
            iconBg="bg-warning-bg"
            iconColor="text-warning"
          />
        </div>
      )}
```

This reuses the same `loading`/`showSkeleton` flags the rest of the page already has — no new loading state.

- [x] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/pages/Budgets.test.tsx`
Expected: all tests PASS.

- [x] **Step 5: Commit**

```bash
git add frontend/src/pages/Budgets.tsx frontend/src/pages/Budgets.test.tsx
git commit -m "feat(budgets): add Total Spend / Total Budget / On Track / Days Left stat cards"
```

---

### Task 4: Redesign category rows with icon, colored progress bar, and status pill

**Files:**
- Modify: `frontend/src/pages/Budgets.tsx`
- Test: `frontend/src/pages/Budgets.test.tsx`

**Interfaces:**
- Consumes: `Badge` with the `success`/`warning`/`danger` tones from Task 1; `ICON_COMPONENTS`/`COLOR_HEX` from `frontend/src/lib/categoryIcons.ts`; `categoriesById` map from Task 2.
- Produces: a `budgetStatus(pct: number): { label: string; tone: 'success' | 'warning' | 'danger' }` pure function local to `Budgets.tsx`, used only by this task's row rendering.

- [x] **Step 1: Write the failing tests**

Add to `frontend/src/pages/Budgets.test.tsx`:

```tsx
  it('shows an "On track" pill for a budget under 90% used', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([budget({ monthlyLimit: 10000, spentThisMonth: 5000 })]);
    renderPage();

    expect(await screen.findByText('On track')).toBeInTheDocument();
  });

  it('shows an "Almost there" pill for a budget between 90% and 100% used', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([budget({ monthlyLimit: 10000, spentThisMonth: 9200 })]);
    renderPage();

    expect(await screen.findByText('Almost there')).toBeInTheDocument();
  });

  it('shows an "Over budget" pill once spend reaches or exceeds the limit', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([budget({ monthlyLimit: 10000, spentThisMonth: 10500 })]);
    renderPage();

    expect(await screen.findByText('Over budget')).toBeInTheDocument();
  });

  it("renders the budget's category icon using the matched category's color", async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([budget({ categoryId: 'c1', categoryName: 'Dining' })]);
    vi.mocked(categoriesApi.list).mockResolvedValue([category({ id: 'c1', icon: 'utensils', color: 'orange' })]);
    renderPage();

    const row = await screen.findByText('Dining');
    // The icon sits in the same row container as the category name.
    const iconEl = row.closest('[data-testid="budget-row"]')?.querySelector('svg');
    expect(iconEl).toBeTruthy();
  });
```

- [x] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx vitest run src/pages/Budgets.test.tsx -t "pill"`
Expected: FAIL — no "On track"/"Almost there"/"Over budget" text exists yet (the old row only showed the raw category name and the amount).

- [x] **Step 3: Implement the redesigned rows**

Add imports:

```tsx
import { ICON_COMPONENTS, COLOR_HEX } from '../lib/categoryIcons';
import { Badge } from '../design-system';
```

Add the status helper above the component, next to `daysLeftInMonth()`:

```tsx
function budgetStatus(pct: number): { label: string; tone: 'success' | 'warning' | 'danger' } {
  if (pct >= 100) return { label: 'Over budget', tone: 'danger' };
  if (pct >= 90) return { label: 'Almost there', tone: 'warning' };
  return { label: 'On track', tone: 'success' };
}
```

Replace the existing row-rendering `budgets.map((b) => { ... })` block (the one using the plain 3-column grid with a raw category name, a plain progress bar, and `{fmt(...)} / {fmt(...)}`) with:

```tsx
          budgets.map((b) => {
            const pct = b.monthlyLimit > 0 ? Math.min(999, (b.spentThisMonth / b.monthlyLimit) * 100) : 0;
            const barPct = Math.min(100, pct);
            const status = budgetStatus(pct);
            const cat = categoriesById.get(b.categoryId);
            const Icon = ICON_COMPONENTS[cat?.icon ?? 'tag'] ?? Wallet;
            const color = COLOR_HEX[cat?.color ?? 'gray'];
            return (
              <div key={b.id} data-testid="budget-row" className="flex items-center gap-3 text-sm">
                <div className="w-8 h-8 rounded-full flex items-center justify-center flex-shrink-0" style={{ backgroundColor: `${color}26` }}>
                  <Icon size={16} style={{ color }} />
                </div>
                <span className="w-32 flex-shrink-0 truncate">{b.categoryName}</span>
                <div className="flex-1 h-2 bg-black/10 rounded overflow-hidden">
                  <div
                    className={`h-full ${status.tone === 'danger' ? 'bg-danger' : status.tone === 'warning' ? 'bg-warning' : 'bg-success'}`}
                    style={{ width: `${barPct}%` }}
                  />
                </div>
                <span className="w-36 flex-shrink-0 text-right">{fmt(b.spentThisMonth)} / {fmt(b.monthlyLimit)}</span>
                <Badge tone={status.tone} label={status.label} className="flex-shrink-0" />
              </div>
            );
          })
```

(`${color}26` appends a fixed ~15% alpha hex suffix to the category's hex color for the icon-badge background — same technique the codebase already has no precedent for doing differently; `COLOR_HEX` values are all 6-digit hex, so this is a safe string append, not a color-math operation.)

- [x] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/pages/Budgets.test.tsx`
Expected: all tests PASS.

- [x] **Step 5: Commit**

```bash
git add frontend/src/pages/Budgets.tsx frontend/src/pages/Budgets.test.tsx
git commit -m "feat(budgets): add category icons and On Track/Almost There/Over Budget status pills"
```

---

### Task 5: Add the Spending Breakdown donut chart

**Files:**
- Modify: `frontend/src/pages/Budgets.tsx`
- Test: `frontend/src/pages/Budgets.test.tsx`

**Interfaces:**
- Consumes: `ChartContainer`, `baseChartOptions` (`frontend/src/design-system/ChartContainer.tsx`), `Doughnut` from `react-chartjs-2`, `Chart as ChartJS, ArcElement, Tooltip, Legend` from `chart.js` — same registration pattern `Investments.tsx:2-3,12` already uses. Reuses `categoriesById`/`COLOR_HEX` from Tasks 2 and 4 for per-slice colors, and the existing `budgets` state — no new data fetch.
- Produces: nothing new consumed by later tasks — this is the last data-visualization piece of this plan.

- [x] **Step 1: Write the failing tests**

Add to `frontend/src/pages/Budgets.test.tsx`:

```tsx
  it('shows an empty state for the spending breakdown chart when there are no budgets', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([]);
    renderPage();

    expect(await screen.findByText('No spending to break down yet')).toBeInTheDocument();
  });

  it('renders the Spending Breakdown section header once budgets exist', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([budget({ categoryName: 'Dining', spentThisMonth: 2000 })]);
    renderPage();

    expect(await screen.findByText('Spending Breakdown')).toBeInTheDocument();
  });
```

(Chart.js canvas rendering itself is not asserted on — consistent with how `Investments.test.tsx` avoids asserting on `<Doughnut>` internals; only the section header and the loading/empty contract, which `ChartContainer` already guarantees, are tested here.)

- [x] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/Budgets.test.tsx -t "Spending Breakdown"`
Expected: FAIL — neither the section header nor the empty-state copy exists yet.

- [x] **Step 3: Implement the donut chart**

Add imports:

```tsx
import { Doughnut } from 'react-chartjs-2';
import { Chart as ChartJS, ArcElement, Tooltip, Legend } from 'chart.js';
import { PieChart } from 'lucide-react';
import { FinoraCard, EmptyState, Button, Skeleton, MetricCard, Badge, SectionHeader, ChartContainer, baseChartOptions } from '../design-system';

ChartJS.register(ArcElement, Tooltip, Legend);
```

(Combine into the page's existing single design-system import line rather than a second one.)

Restructure the page's outer layout so the category list and the new chart sit side by side on desktop, wrapping the existing category-list `FinoraCard` and the new chart `FinoraCard` in a two-column grid:

```tsx
      <div className="grid lg:grid-cols-3 gap-4">
        <FinoraCard padding="sm" className="lg:col-span-2 space-y-3">
          {/* existing loading / empty / budgets.map(...) block from Tasks 2-4 stays here, unchanged */}
        </FinoraCard>

        <FinoraCard>
          <SectionHeader title="Spending Breakdown" size="sm" />
          <ChartContainer
            height={220}
            loading={loading}
            loadingLabel="Loading spending breakdown"
            isEmpty={budgets.length === 0}
            emptyState={
              <EmptyState
                icon={PieChart}
                iconBg="bg-primary-light"
                iconColor="text-primary"
                title="No spending to break down yet"
                desc="Set a budget above to see how your spending splits by category."
              />
            }
          >
            <Doughnut
              data={{
                labels: budgets.map((b) => b.categoryName),
                datasets: [{
                  data: budgets.map((b) => b.spentThisMonth),
                  backgroundColor: budgets.map((b) => COLOR_HEX[categoriesById.get(b.categoryId)?.color ?? 'gray']),
                  borderWidth: 0,
                }],
              }}
              options={{ ...baseChartOptions }}
            />
          </ChartContainer>
        </FinoraCard>
      </div>
```

- [x] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/pages/Budgets.test.tsx`
Expected: all tests PASS.

- [x] **Step 5: Commit**

```bash
git add frontend/src/pages/Budgets.tsx frontend/src/pages/Budgets.test.tsx
git commit -m "feat(budgets): add Spending Breakdown donut chart"
```

---

### Task 6: Restyle the create/update budget form

**Files:**
- Modify: `frontend/src/pages/Budgets.tsx`
- Test: `frontend/src/pages/Budgets.test.tsx`

**Interfaces:**
- Consumes: `SectionHeader` (already imported by Task 5). No behavior change — `addOrUpdate()`, `newCategory`/`newLimit`/`saving`/`saved` state, and the `error` banner all stay exactly as they are today.
- Produces: nothing new for later tasks — this is a pure visual pass.

- [x] **Step 1: Write the failing test**

Add to `frontend/src/pages/Budgets.test.tsx`:

```tsx
  it('still saves a budget through the restyled form', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([]);
    vi.mocked(budgetsApi.upsert).mockResolvedValue(budget());
    renderPage();

    await screen.findByText('No budgets set');
    await userEvent.type(screen.getByLabelText('Category'), 'Travel');
    await userEvent.type(screen.getByLabelText('Monthly limit'), '3000');
    await userEvent.click(screen.getByRole('button', { name: /set budget/i }));

    await waitFor(() => expect(budgetsApi.upsert).toHaveBeenCalledWith('Travel', 3000));
  });
```

Add the `userEvent` import at the top of the test file:

```tsx
import userEvent from '@testing-library/user-event';
```

(Verified already a real devDependency — `frontend/package.json:37` lists `@testing-library/user-event: ^14.6.4`, and `Sidebar.test.tsx`/`AuthContext.test.tsx`/`ChangeEmailModal.test.tsx` already import it the same way.)

- [x] **Step 2: Run test to verify it fails or passes for the wrong reason**

Run: `cd frontend && npx vitest run src/pages/Budgets.test.tsx -t "restyled form"`
Expected: this test should already PASS against the current (unstyled) form, since it only asserts on behavior, not appearance — confirming the form's existing accessible labels (`Category`, `Monthly limit`) and button name (`Set Budget`) survive Step 3's visual restyle is the actual point of running it again after Step 3.

- [x] **Step 3: Restyle the form**

Replace the form's outer `FinoraCard`:

```tsx
      <FinoraCard padding="sm" className="flex gap-2 items-end">
```

with a titled section consistent with the rest of the redesigned page:

```tsx
      <FinoraCard>
        <SectionHeader title="Set a Budget" size="sm" />
        <div className="flex gap-2 items-end">
```

...and close the added wrapper `</div>` before the existing `</FinoraCard>`. Leave every `<label>`/`<input>`/`<Button>` element and its `id`/`htmlFor` pairing completely unchanged — only the wrapping structure changes.

- [x] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/pages/Budgets.test.tsx`
Expected: all tests PASS, including the Step 1 test (now proving the restyle didn't break the save flow).

- [x] **Step 5: Commit**

```bash
git add frontend/src/pages/Budgets.tsx frontend/src/pages/Budgets.test.tsx
git commit -m "style(budgets): restyle the create/update budget form with a section header"
```

---

### Task 7: Full-page integration pass

**Files:**
- Modify: `frontend/src/pages/Budgets.tsx` (composition only — assembling Tasks 1-6's pieces in final order)
- Test: `frontend/src/pages/Budgets.test.tsx`

**Interfaces:**
- Consumes: everything produced by Tasks 1-6.
- Produces: the finished page. Nothing downstream depends on this task.

- [x] **Step 1: Write the failing test**

Add a top-to-bottom smoke test to `frontend/src/pages/Budgets.test.tsx`:

```tsx
  it('renders stat cards, the category list, the spending breakdown chart, and the form together', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([
      budget({ id: 'b1', categoryId: 'c1', categoryName: 'Dining', monthlyLimit: 10000, spentThisMonth: 8400 }),
    ]);
    vi.mocked(categoriesApi.list).mockResolvedValue([category({ id: 'c1', icon: 'utensils', color: 'orange' })]);
    renderPage();

    expect(await screen.findByText('Total Spend')).toBeInTheDocument();
    expect(screen.getByText('Dining')).toBeInTheDocument();
    expect(screen.getByText('Almost there')).toBeInTheDocument();
    expect(screen.getByText('Spending Breakdown')).toBeInTheDocument();
    expect(screen.getByText('Set a Budget')).toBeInTheDocument();
  });
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/Budgets.test.tsx -t "together"`
Expected: FAIL only if any earlier task's piece is missing from the final JSX order — this is the checkpoint that the tasks above compose into one coherent page rather than five isolated fragments.

- [x] **Step 3: Assemble the final page structure**

Confirm `frontend/src/pages/Budgets.tsx`'s `return` is ordered exactly:

```tsx
  return (
    <div className="space-y-4">
      {/* Task 3: stat cards row */}
      {/* Task 6: "Set a Budget" form */}
      {error && <p className="text-danger text-sm">{error}</p>}
      {/* Task 5: two-column grid containing Task 2+4's category list (left) and the donut chart (right) */}
    </div>
  );
```

Move any block that ended up out of this order (most likely: the `error` banner, which existed above the form before this redesign and should now render below the form, above the two-column grid, matching where a save error is most useful — directly under the control that produced it) so the final file matches this order top to bottom.

- [x] **Step 4: Run the full test file and the full frontend suite**

Run: `cd frontend && npx vitest run src/pages/Budgets.test.tsx`
Expected: all tests in the file PASS.

Run: `cd frontend && npx vitest run`
Expected: the full suite passes — this redesign touches `lib/categoryIcons.ts` consumers and `design-system/Badge.tsx`, both used elsewhere (`Dashboard.tsx`), so a full run is the check that nothing else regressed.

- [x] **Step 5: Manual verification in the browser**

Run: `cd frontend && npm run dev`

Log in, navigate to `/app/budgets`, and confirm against the mockup (`Prototype images/Codex Image 5 Sept 2026, 20_54_47.png`) side by side:
- Stat cards show real numbers, not placeholders.
- Each category row shows the correct icon and color (cross-check against `/app/settings` or wherever categories are managed, to confirm the icon/color actually matches that category's real configured icon).
- A budget you push over 90% (edit one via the form) flips its pill from "On track" to "Almost there" without a page reload.
- The donut chart's slice colors match each row's icon color.
- Toggle dark mode (existing theme toggle) and confirm the new `success-bg`/`warning-bg`/`danger-bg` badge backgrounds remain legible — these tokens already have dark-mode values (`frontend/src/index.css:69-74`), so this is a visual check, not a code change.

- [x] **Step 6: Commit**

```bash
git add frontend/src/pages/Budgets.tsx frontend/src/pages/Budgets.test.tsx
git commit -m "feat(budgets): assemble redesigned Budgets page"
```

---

## Self-Review Notes

**Spec coverage:** Every buildable element from the "Source of truth" section has a task — stat cards (Task 3), category icons/status pills (Task 4), spending breakdown donut (Task 5), form (Task 6), composition (Task 7). The two explicitly-deferred mockup elements (daily trend chart, budget alerts panel) are called out by name in "Source of truth" with the concrete reason (no backend data) rather than silently dropped.

**Placeholder scan:** No "TBD"/"handle edge cases"/"add validation" language anywhere above — every step has real, complete code.

**Type consistency:** `budgetStatus()` returns `tone: 'success' | 'warning' | 'danger'`, which is exactly the tone-name subset `Badge` (Task 1) accepts and exactly what Task 4's row rendering passes through unchanged. `categoriesById: Map<string, CategoryOption>` is defined once in Task 2 and consumed with the same name and shape in Tasks 4 and 5 — no renaming across tasks.

## Next plans in this redesign series

This is plan 1 of the multi-page Fynora redesign. Follow-on plans, each scoped the same way (grounded in real backend data, explicit about what's deferred):
1. **Goals redesign** — stat cards, photo-card goals grid (using the generated goal photos once finalized), status pills (reusing this plan's `Badge` tones), goal-journey line chart.
2. **Accounts overview page** — new page distinct from the `Setup.tsx` bank-connection wizard: total balance cards, balance-by-type donut, per-account cards.
3. **Asset integration** — wire the hero-banner illustration, hand-drawn annotation, and PDF/bank/XLS collage (from `Prototype images/`) into Dashboard/Import Statement, plus rebuild "Refer & earn"/"Upgrade to Pro" as visual sidebar cards.
4. Remaining pages (Transactions/Ledger, Import Statement, Statement History, Investments) get lighter-touch plans, since those are already functionally close to their mockups — mostly visual polish, not net-new sections.
