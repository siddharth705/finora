# Dashboard KPI Cards + Hero Card Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the two highest-impact pieces of the reviewed "Fynora Dashboard V2" redesign concept into the real `Dashboard.tsx` — a premium visual treatment for the 5 existing KPI cards, and a hero card wrapping the greeting with Financial Health / Savings Rate chips and a decorative illustration — without changing what data is shown or adding any new backend field.

**Architecture:** Two independent, non-overlapping frontend-only changes to the same page. (1) `MetricCard` (a shared design-system component also used by Reports/Investments/Budgets) gets a new opt-in `variant="elevated"` prop — default omitted, every existing call site on every other page renders byte-identical to today. Dashboard's 5 KPI cards are the only call sites that pass it. (2) `Dashboard.tsx`'s plain greeting `<div>` is wrapped in a `FinoraCard`-style hero shell with two status chips (reusing data already fetched) and a purely decorative inline-SVG line-art illustration, hidden below the `lg` breakpoint and from assistive tech.

**Tech Stack:** React 18 + TypeScript, Vite, Tailwind, Vitest + `@testing-library/react`.

**Spec:** No separate written spec file. This plan's own "Context" section distills the spec from the reviewed design artifact ("Fynora Dashboard V2", a Claude Artifact published and browser-verified earlier this session — both light and dark themes confirmed against the real app's actual CSS custom properties in `frontend/src/index.css`) and the KPI-framing decision confirmed in conversation: **keep the existing 5 KPIs (Total Balance, Total Income, Total Expenses, Net Savings, Savings Rate) and apply the redesign's visual card treatment to them** — do not switch to the artifact's alternate 4-KPI set (that set was rejected; it would also require a new backend field for a monthly transaction count, which this plan does not add).

## Context: what's actually being built here

The full redesign artifact covers the entire Dashboard (KPIs, hero, analytics charts, insights row, transactions, goals, connected accounts, referral). This plan is deliberately scoped to only the two elements confirmed and highest-impact so far — the KPI row and the hero/greeting — matching this codebase's established pattern of one focused plan per page-section (see "Next plans in this series" at the bottom, and the precedent already set by the Budgets redesign's own roadmap).

Visual reference (both confirmed against real tokens, not invented):
- KPI cards: icon badge becomes a uniform `bg-primary-light`/`text-primary` rounded-square medallion (not per-metric bright colors — a deliberate "spend boldness in one place" restraint from the reviewed design, matching the brief's explicit "avoid generic admin template" / "no random gradients" constraints), the value becomes `font-display` (Manrope, already loaded by `index.html` for headings elsewhere in the app), and the delta becomes a colored pill (`bg-success-bg`/`bg-danger-bg`) instead of plain colored text. Card gets a subtle hover lift (`-translate-y-0.5` + `shadow-soft`, both already-defined tokens).
- Hero: the existing plain-text greeting gets wrapped in the same `bg-card rounded-xl2 border border-border shadow-card` shell every other card on this page already uses, plus two pill chips surfacing data the page already fetches (Financial Health score/label, Savings Rate), plus a quiet single-stroke SVG "horizon" line motif in the primary token color at low opacity, filling the card's right edge on desktop only.

## Global Constraints

- Palette: reuse only existing Tailwind semantic tokens already defined in `tailwind.config.js` / `frontend/src/index.css` (`bg-primary-light`, `text-primary`, `bg-success-bg`, `text-success`, `bg-danger-bg`, `text-danger`, `bg-card`, `border-border`, `shadow-card`, `shadow-soft`, `rounded-xl2`, `font-display`). No new colors, no gradients, no purple/indigo accents.
- `MetricCard`'s default (no `variant` prop) rendering path must stay byte-identical to its current output — Reports.tsx, Investments.tsx, and Budgets.tsx all call it with no `variant` prop and must not change visually.
- No new backend calls, no new component state beyond `MetricCard`'s existing `showReason` toggle (reused, not duplicated) — this is a rendering-only change built from data `Dashboard.tsx` already fetches (`summary.healthScoreAvailable`, `summary.healthScore`, `summary.healthLabel`, `summary.savingsRatePct`).
- The hero illustration is purely decorative: `aria-hidden="true"` on its wrapping container, no `alt` text claiming to describe data the chips/heading don't already state in words.
- Existing greeting text, `firstName`, `greeting()` time-of-day logic, and the `reportingMonthIsCurrent` conditional sentence must render unchanged — this plan only adds a wrapper and two new chips, it does not touch that logic.
- No `Co-Authored-By` / AI-attribution trailer in any commit message (repo-wide rule, `CLAUDE.md`).

---

## File Structure

- **Modify:** `frontend/src/design-system/MetricCard.tsx` — add an opt-in `variant?: 'default' | 'elevated'` prop; `'elevated'` is a self-contained early-return branch, the existing `'default'` return path is untouched.
- **Modify:** `frontend/src/design-system/MetricCard.test.tsx` — add tests for the new `elevated` branch (pill delta, inverted color, movers disclosure still works) and one test confirming the default (no `variant`) path is unchanged.
- **Modify:** `frontend/src/pages/Dashboard.tsx` — pass `variant="elevated"` on the 5 KPI `<MetricCard>` calls; wrap the greeting block in the new hero card.
- **Modify:** `frontend/src/pages/Dashboard.test.tsx` — one integration test confirming a KPI card renders with the elevated treatment, one confirming the hero card's chips and illustration render.
- **No new files.**

---

### Task 1: `MetricCard` elevated variant

**Files:**
- Modify: `frontend/src/design-system/MetricCard.tsx`
- Test: `frontend/src/design-system/MetricCard.test.tsx`

**Interfaces:**
- Consumes: nothing new — same props `MetricCard` already accepts, plus one new optional `variant`.
- Produces: `variant="elevated"` — consumed by Task 2's Dashboard wiring (Task 3 below).

- [ ] **Step 1: Write the failing tests**

Add to `frontend/src/design-system/MetricCard.test.tsx`, after the existing tests (the file already imports `describe`, `it`, `expect`, `render`, `screen`, `userEvent`, `Wallet`, `MetricCard` — no new imports needed):

```tsx
  it('variant="elevated" renders the delta as a colored pill instead of plain text', () => {
    render(
      <MetricCard
        label="Income" value="₹500" icon={Wallet} iconBg="bg-green-100" iconColor="text-green-600"
        delta={12.3} deltaLabel="vs last month" variant="elevated"
      />
    );
    const chip = screen.getByText('▲ 12.3%');
    expect(chip).toHaveClass('bg-success-bg', 'text-success');
    expect(screen.getByText('vs last month')).toBeInTheDocument();
  });

  it('variant="elevated" inverts the pill color for expense-like metrics', () => {
    render(
      <MetricCard
        label="Expenses" value="₹500" icon={Wallet} iconBg="bg-red-100" iconColor="text-red-600"
        delta={-5} deltaLabel="vs last month" invertDelta variant="elevated"
      />
    );
    expect(screen.getByText('▼ 5.0%')).toHaveClass('bg-success-bg', 'text-success');
  });

  it('variant="elevated" still supports the movers "Why?" disclosure', async () => {
    const user = userEvent.setup();
    render(
      <MetricCard
        label="Total Expenses" value="₹45,000" icon={Wallet} iconBg="bg-red-100" iconColor="text-red-600"
        delta={60} deltaLabel="vs last month" invertDelta variant="elevated"
        moverLines={['Dining: ₹8,000 vs ₹5,000 (+60%)']}
      />
    );
    await user.click(screen.getByRole('button', { name: 'Why?' }));
    expect(screen.getByText(/Dining: ₹8,000/)).toBeInTheDocument();
  });

  it('variant="elevated" renders a muted placeholder (no pill) when there is no delta value yet', () => {
    render(
      <MetricCard
        label="Balance" value="₹500" icon={Wallet} iconBg="bg-blue-100" iconColor="text-blue-600"
        deltaLabel="vs last month" variant="elevated"
      />
    );
    expect(screen.getByText('— vs last month')).toBeInTheDocument();
  });

  it('defaults to the original plain-text delta line when no variant is given', () => {
    render(<MetricCard label="Income" value="₹500" icon={Wallet} iconBg="bg-green-100" iconColor="text-green-600" delta={12.3} deltaLabel="vs last month" />);
    const line = screen.getByText(/12\.3% vs last month/);
    expect(line).toHaveClass('text-success');
  });
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx vitest run src/design-system/MetricCard.test.tsx`
Expected: the 4 new `variant="elevated"` tests FAIL (`variant` prop does not exist yet); the last test (default path) PASSES already since it asserts current behavior.

- [ ] **Step 3: Implement the elevated variant**

In `frontend/src/design-system/MetricCard.tsx`, change the props type and function signature to:

```tsx
export function MetricCard({
  label, value, icon: Icon, iconBg, iconColor, valueColor,
  delta, deltaLabel, invertDelta, gateReasonText, moverLines, variant = 'default',
}: {
  label: string; value: string; icon: LucideIcon; iconBg: string; iconColor: string; valueColor?: string;
  delta?: number | null; deltaLabel?: string; invertDelta?: boolean; gateReasonText?: string | null;
  moverLines?: string[]; variant?: 'default' | 'elevated';
}) {
  const [showReason, setShowReason] = useState(false);
  const hasDelta = delta !== null && delta !== undefined;
  const hasMovers = !!moverLines && moverLines.length > 0;
  const positive = hasDelta && (invertDelta ? delta! < 0 : delta! >= 0);

  // Dashboard's KPI row only, per the reviewed redesign: a uniform graphite/cream icon medallion
  // and a pill-shaped delta instead of per-metric bright icon colors and plain colored text.
  // Reports/Investments/Budgets never pass this, so their MetricCard calls (the `return` below
  // this block) are unchanged.
  if (variant === 'elevated') {
    return (
      <FinoraCard className="transition-[transform,box-shadow] duration-150 hover:-translate-y-0.5 hover:shadow-soft">
        <div className="flex items-start justify-between mb-4">
          <p className="text-sm font-semibold text-muted">{label}</p>
          <div className="w-10 h-10 rounded-xl bg-primary-light flex items-center justify-center flex-shrink-0">
            <Icon size={18} className="text-primary" />
          </div>
        </div>
        <p className={`font-display text-[26px] font-extrabold mb-1.5 tracking-tight ${valueColor ?? 'text-ink'}`}>{value}</p>
        {deltaLabel && (
          hasDelta ? (
            <div>
              <span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-semibold ${positive ? 'bg-success-bg text-success' : 'bg-danger-bg text-danger'}`}>
                {delta! >= 0 ? '▲' : '▼'} {Math.abs(delta!).toFixed(1)}%
              </span>
              <span className="ml-1.5 text-xs text-muted">{deltaLabel}</span>
              {hasMovers && (
                <button
                  type="button"
                  onClick={() => setShowReason((v) => !v)}
                  aria-expanded={showReason}
                  className="ml-1.5 text-xs font-normal text-primary underline underline-offset-2"
                >
                  {showReason ? 'Hide' : 'Why?'}
                </button>
              )}
              {hasMovers && showReason && (
                <ul className="mt-1.5 space-y-0.5 text-[11px] text-muted list-disc list-inside">
                  {moverLines!.map((line, i) => <li key={i}>{line}</li>)}
                </ul>
              )}
            </div>
          ) : (
            <div>
              <span className="text-xs text-muted">— {deltaLabel}</span>
              {gateReasonText && (
                <button
                  type="button"
                  onClick={() => setShowReason((v) => !v)}
                  aria-expanded={showReason}
                  className="ml-1.5 text-xs text-primary underline underline-offset-2"
                >
                  {showReason ? 'Hide' : 'Why?'}
                </button>
              )}
              {gateReasonText && showReason && (
                <p className="text-[11px] text-muted mt-1">{gateReasonText}</p>
              )}
            </div>
          )
        )}
      </FinoraCard>
    );
  }

  return (
    <FinoraCard>
      <div className="flex items-start justify-between mb-3">
        <p className="text-sm text-muted">{label}</p>
        <div className={`w-9 h-9 rounded-full ${iconBg} flex items-center justify-center flex-shrink-0`}>
          <Icon size={17} className={iconColor} />
        </div>
      </div>
      <p className={`text-2xl font-bold mb-1 ${valueColor ?? 'text-ink'}`}>{value}</p>
      {deltaLabel && (
        hasDelta ? (
          <div>
            <p className={`text-xs font-medium ${positive ? 'text-success' : 'text-danger'}`}>
              {delta! >= 0 ? '▲' : '▼'} {Math.abs(delta!).toFixed(1)}% {deltaLabel}
              {hasMovers && (
                <button
                  type="button"
                  onClick={() => setShowReason((v) => !v)}
                  aria-expanded={showReason}
                  className="ml-1.5 font-normal text-primary underline underline-offset-2"
                >
                  {showReason ? 'Hide' : 'Why?'}
                </button>
              )}
            </p>
            {hasMovers && showReason && (
              <ul className="mt-1 space-y-0.5 text-[11px] text-muted list-disc list-inside">
                {moverLines!.map((line, i) => <li key={i}>{line}</li>)}
              </ul>
            )}
          </div>
        ) : (
          <div>
            <p className="text-xs text-muted">
              — {deltaLabel}
              {gateReasonText && (
                <button
                  type="button"
                  onClick={() => setShowReason((v) => !v)}
                  aria-expanded={showReason}
                  className="ml-1.5 text-primary underline underline-offset-2"
                >
                  {showReason ? 'Hide' : 'Why?'}
                </button>
              )}
            </p>
            {gateReasonText && showReason && (
              <p className="text-[11px] text-muted mt-1">{gateReasonText}</p>
            )}
          </div>
        )
      )}
    </FinoraCard>
  );
}
```

Note the `return` block after the `if (variant === 'elevated')` branch is the **exact original file content, unchanged** — only the new branch above it and the `variant`/`positive` additions to the function signature/body are new.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/design-system/MetricCard.test.tsx`
Expected: every test in the file PASSES, including all pre-existing ones (proves the default path is untouched).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/design-system/MetricCard.tsx frontend/src/design-system/MetricCard.test.tsx
git commit -m "feat(frontend): add an elevated MetricCard variant for premium KPI cards"
```

---

### Task 2: Wire Dashboard's 5 KPI cards to the elevated variant

**Files:**
- Modify: `frontend/src/pages/Dashboard.tsx`
- Test: `frontend/src/pages/Dashboard.test.tsx`

**Interfaces:**
- Consumes: `MetricCard`'s `variant="elevated"` prop from Task 1.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the failing test**

Add to `frontend/src/pages/Dashboard.test.tsx`, inside the existing `describe('Dashboard — Financial Health Score', ...)` block (reuses its `beforeEach` mocks — the fixture's `currentBalance: 50000` is already set there):

```tsx
  it('renders KPI cards with the elevated visual treatment', async () => {
    renderDashboard();

    await screen.findByText('Financial Health Score');
    const balanceValue = screen.getByText('₹50,000');
    expect(balanceValue).toHaveClass('font-display');
  });
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/Dashboard.test.tsx -t "elevated visual treatment"`
Expected: FAIL — the KPI value has no `font-display` class yet (still on the default `MetricCard` path).

- [ ] **Step 3: Wire the variant prop**

In `frontend/src/pages/Dashboard.tsx`, in the KPI grid (the `kpis.map((k) => (...))` block), add `variant="elevated"` to the `<MetricCard>` call:

```tsx
      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-5 gap-4 mb-6">
        {kpis.map((k) => (
          <MetricCard
            key={k.label}
            label={k.label}
            value={k.value}
            icon={k.icon}
            iconBg={k.iconBg}
            iconColor={k.iconColor}
            delta={k.delta}
            deltaLabel={deltaLabel}
            invertDelta={k.invertDelta}
            gateReasonText={k.gateReasonText}
            moverLines={k.moverLines}
            variant="elevated"
          />
        ))}
      </div>
```

(`iconBg`/`iconColor` are still passed even though the elevated branch ignores them — `MetricCard`'s props stay required so Reports/Investments/Budgets' existing calls don't need touching.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/Dashboard.test.tsx -t "elevated visual treatment"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/Dashboard.tsx frontend/src/pages/Dashboard.test.tsx
git commit -m "feat(frontend): apply the elevated KPI card treatment to the Dashboard"
```

---

### Task 3: Hero card — greeting wrapper with status chips and illustration

**Files:**
- Modify: `frontend/src/pages/Dashboard.tsx`
- Test: `frontend/src/pages/Dashboard.test.tsx`

**Interfaces:**
- Consumes: `summary.healthScoreAvailable`, `summary.healthScore`, `summary.healthLabel`, `summary.savingsRatePct` (all already fetched/computed earlier in the component), the existing `healthColor()` helper, and the already-imported `ShieldCheck`/`PiggyBank` icons.
- Produces: nothing consumed by later tasks — independent of Task 1/2 (different lines of the same file; no merge conflict either order).

- [ ] **Step 1: Write the failing test**

Add to `frontend/src/pages/Dashboard.test.tsx`, inside the existing `describe('Dashboard — Financial Health Score', ...)` block (its `beforeEach` already mocks `summary()` with `healthScore: 82, healthLabel: 'Excellent', savingsRatePct: 43.75`):

```tsx
  it('wraps the greeting in a hero card with Financial Health and Savings Rate chips', async () => {
    renderDashboard();

    const heading = await screen.findByRole('heading', { level: 1 });
    expect(heading.textContent).toMatch(/👋/);

    expect(screen.getByText('Financial Health: Excellent · 82/100')).toBeInTheDocument();
    expect(screen.getByText('Savings rate 44%')).toBeInTheDocument();

    const illustration = document.querySelector('[data-testid="dashboard-hero-illustration"]');
    expect(illustration).toBeTruthy();
    expect(illustration).toHaveAttribute('aria-hidden', 'true');
  });

  it('omits the Financial Health chip when the score is not yet available', async () => {
    vi.mocked(dashboardApi.summary).mockReset().mockResolvedValue(
      summary({ healthScoreAvailable: false, healthScore: null, healthLabel: null, healthScoreTransactionCount: 3 }),
    );
    renderDashboard();

    await screen.findByRole('heading', { level: 1 });
    expect(screen.queryByText(/Financial Health:/)).not.toBeInTheDocument();
    expect(screen.getByText('Savings rate 44%')).toBeInTheDocument();
  });
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx vitest run src/pages/Dashboard.test.tsx -t "hero card"`
Expected: FAIL — neither chip nor `[data-testid="dashboard-hero-illustration"]` exist yet.

- [ ] **Step 3: Implement the hero card**

In `frontend/src/pages/Dashboard.tsx`, replace the existing greeting block:

```tsx
      <div className="mb-8">
        <h1 className="text-[26px] font-bold text-ink mb-1">{greeting(settingsQ.data?.timezone)}, {firstName}! 👋</h1>
        <p className="text-muted text-sm">
          Here's what's happening with your finances today.
          {!summary.reportingMonthIsCurrent && summary.reportingMonth && (
            // Not a warning -- reporting on the newest month with data is the intended behaviour.
            // What was missing is that nothing said which month, so the figures read as current.
            <> Your latest figures are from <span className="font-medium text-ink">{periodLabel}</span>.</>
          )}
        </p>
      </div>
```

with:

```tsx
      <div className="relative overflow-hidden bg-card rounded-xl2 border border-border shadow-card mb-8 px-6 py-6 lg:pr-4">
        <div className="relative z-10 lg:max-w-[62%]">
          <h1 className="text-[26px] font-bold text-ink mb-1">{greeting(settingsQ.data?.timezone)}, {firstName}! 👋</h1>
          <p className="text-muted text-sm mb-4">
            Here's what's happening with your finances today.
            {!summary.reportingMonthIsCurrent && summary.reportingMonth && (
              // Not a warning -- reporting on the newest month with data is the intended behaviour.
              // What was missing is that nothing said which month, so the figures read as current.
              <> Your latest figures are from <span className="font-medium text-ink">{periodLabel}</span>.</>
            )}
          </p>
          <div className="flex flex-wrap gap-2">
            {summary.healthScoreAvailable && (
              <span className={`inline-flex items-center gap-1.5 rounded-full border border-border bg-bg px-3 py-1.5 text-xs font-semibold ${healthColor(summary.healthLabel!)}`}>
                <ShieldCheck size={13} /> Financial Health: {summary.healthLabel} · {summary.healthScore}/100
              </span>
            )}
            <span className="inline-flex items-center gap-1.5 rounded-full border border-border bg-bg px-3 py-1.5 text-xs font-semibold text-primary">
              <PiggyBank size={13} /> Savings rate {summary.savingsRatePct.toFixed(0)}%
            </span>
          </div>
        </div>
        {/* Purely decorative -- the illustration carries no information the heading/chips above
            don't already state, so the whole region is hidden from assistive tech rather than
            given (unhelpful, made-up) alt text. Hidden below `lg`: there isn't room for a side
            illustration without shrinking or overlapping the greeting text on a narrow viewport. */}
        <div
          data-testid="dashboard-hero-illustration"
          aria-hidden="true"
          className="hidden lg:block absolute inset-y-0 right-0 w-[42%]"
        >
          <svg viewBox="0 0 380 220" className="absolute inset-0 w-full h-full" preserveAspectRatio="xMaxYMid slice">
            <polygon
              points="0,220 40,150 70,158 110,120 150,138 190,100 230,122 270,86 310,108 340,72 380,92 380,220"
              className="fill-primary/[0.05]"
            />
            <polyline
              points="0,170 40,150 70,158 110,120 150,138 190,100 230,122 270,86 310,108 340,72 380,92"
              className="stroke-primary/[0.35]"
              fill="none" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"
            />
            <circle cx="340" cy="72" r="4.5" className="fill-primary" />
          </svg>
        </div>
      </div>
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/pages/Dashboard.test.tsx -t "hero card"`
Expected: both new tests PASS.

- [ ] **Step 5: Run the full Dashboard test file**

Run: `cd frontend && npx vitest run src/pages/Dashboard.test.tsx`
Expected: every pre-existing test in the file still PASSES — this catches any pre-existing test that implicitly depended on the greeting's old DOM structure (the plan's no-guessing standard means this must be confirmed by running the suite, not assumed from a grep).

- [ ] **Step 6: Manual verification in the browser**

Run: `cd frontend && npm run dev`, navigate to `/app` (requires a real login — see the fallback below if one isn't available), and confirm:
- Both KPI cards and the hero card render with the new treatment at desktop width (≥1024px).
- The hero illustration appears on the right edge of the hero card at ≥1024px and disappears entirely below it, with no overlap or clipping of the greeting text or chips at any width ≥1024px.
- The Financial Health chip's color matches `healthColor()`'s existing mapping (green for Excellent/Good... same colors the Financial Health Score card below already uses).
- Dark mode: toggle the theme and confirm the hero card and KPI cards switch correctly and the illustration isn't jarring against the dark surface.
- For an account below the health-score transaction floor, confirm the Financial Health chip is absent (Savings Rate chip still shows).

If no backend/database is available to actually log in, at minimum confirm via the browser's Elements/Network panel that the page renders with no build/runtime errors, and take a screenshot of `ProtectedRoute`'s redirect to confirm the build itself is sound — the same fallback verification the Budgets and hero-banner redesign plans used.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/pages/Dashboard.tsx frontend/src/pages/Dashboard.test.tsx
git commit -m "feat(frontend): wrap the Dashboard greeting in a hero card with status chips"
```

---

## Self-Review Notes

**Spec coverage:** Both elements confirmed in conversation (elevated KPI cards; hero card with Financial Health + Savings Rate chips and a decorative illustration) have their own task. The KPI-set framing question itself is not re-litigated here — it was already resolved (keep the existing 5) before this plan was written.

**Placeholder scan:** No "TBD"/"handle edge cases" language — every step's code block is the complete implementation, not a sketch.

**Type consistency:** `variant?: 'default' | 'elevated'` is defined once in Task 1 and referenced identically (string literal `"elevated"`) in Task 2 — no drift. `healthColor()`, `ShieldCheck`, `PiggyBank` in Task 3 are all already-existing, already-imported symbols in `Dashboard.tsx` — verified by reading the file, not assumed.

**Blast radius check:** `MetricCard` is used by `Reports.tsx`, `Investments.tsx`, `Budgets.tsx`, and `Dashboard.tsx` (verified by grep). Task 1's new branch only activates on an explicit `variant="elevated"` prop that only Dashboard passes (Task 2) — the other three pages' calls are untouched text in the diff.

## Next plans in this redesign series

Per the reviewed artifact's remaining sections, each as its own plan/worktree/PR:
- Spending Trend + Category Breakdown: larger visual footprint, drawn-to-scale SVG/chart treatment matching the artifact.
- Insights row: replace the current two-column sentences/movers layout with 3 status-coded cards (on-track / watch / projected).
- Recent Transactions: merchant icon badges + category chips in place of the current plain rows.
- Goals: radial progress rings alongside the existing linear bars.
- Connected Accounts: bank badges + sync/health status pills.
- Referral widget: integrate the existing sidebar-only "Refer & Earn" card's content into a Dashboard-native panel instead of the current promotional-card framing.
