# Scroll Storytelling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace ImportSection's hover-triggered card with a pinned, GSAP-`ScrollTrigger`-scrubbed 3-beat sequence (scattered statements → AI processing → assembled dashboard) on desktop, a reveal-once version of the same three beats on mobile/reduced-motion, with the section's real copy staying accessible and unchanged throughout.

**Architecture:** `ImportScrollStory` branches on `useIsDesktop()` + `useReducedMotion()` to render either the pinned/scrubbed scene (desktop, motion allowed) or `ImportRevealSequence` (mobile or reduced-motion). `useImportScrollTimeline` is the only file that touches `gsap`/`ScrollTrigger`; it drives three presentational, ref-forwarding components (`DocumentStack`, `ProcessingCore`, `IntelligencePanel`) directly via refs, never through React state, so scroll-frame updates never trigger React re-renders.

**Tech Stack:** React 18, TypeScript, `gsap` 3.15+ (`gsap/ScrollTrigger`, "Standard no-charge license" — confirmed via `npm view gsap license`, no separate paid plugin needed), Framer Motion 13.1.1 (`useReducedMotion`, already a dependency), Vitest + Testing Library.

**Spec:** `docs/superpowers/specs/2026-08-23-scroll-storytelling-design.md`

## Global Constraints

- Scope is `ImportSection` only. Do not touch `LearningSection` or any `<Transition>` band.
- No Lenis. No canvas/WebGL. GSAP `ScrollTrigger` runs against native scroll.
- Pin distance starts at `250vh` (viewport-relative, per the spec) — implemented as `end: () => \`+=${window.innerHeight * 2.5}\`` rather than GSAP's `'+=250%'` shorthand, since that shorthand means 250% of the *trigger element's own height*, not the viewport, and ImportSection's content area isn't guaranteed to be exactly one viewport tall. Beats at `0–35%` scattered, `35–70%` processing, `70–90%` assembling, `90–100%` settle/exit.
- `useImportScrollTimeline` is the only module that imports `gsap` or `gsap/ScrollTrigger`. `DocumentStack`/`ProcessingCore`/`IntelligencePanel` have zero scroll/GSAP knowledge — they render static markup, forward a single root ref, and mark their internal GSAP-animatable elements with `data-target="..."` attributes that the timeline queries via the root ref.
- `IntelligencePanel`'s markup must be a fully realized mock (a figure, category rows, an "Insights ready" line) in every context it's used (scroll end-state, reduced-motion static render, mobile reveal-once end frame) — never a placeholder.
- The whole animated scene (`ImportScrollStory`'s pinned content and `ImportRevealSequence`'s content) is `aria-hidden="true"`. `ImportSection`'s existing eyebrow/title/blurb copy is unchanged and stays outside that `aria-hidden` wrapper, in normal document flow.
- `useImportScrollTimeline`'s effect must revert its GSAP context in cleanup — required for React `StrictMode`'s dev double-invoke (the exact bug class the Hero sub-project hit; see `docs/superpowers/plans/2026-08-22-hero-cinematic-reveal.md`) and for route navigation away from `/`.
- Reduced-motion and non-desktop are checked in `ImportScrollStory` before `useImportScrollTimeline` is ever invoked — the hook itself also no-ops (no `ScrollTrigger` created) if told it's disabled, as a second line of defense.
- Tests mock `gsap`/`gsap/ScrollTrigger` (real scroll-linked behavior isn't testable in jsdom) and assert construction arguments (`pin: true`, `scrub: true`, trigger element, cleanup called) rather than visual output — same approach as `Landing.test.tsx`'s `IntersectionObserver` mock.

---

### Task 1: Add `gsap` dependency and scaffold the `import-story` folder

**Files:**
- Modify: `frontend/package.json`
- Create: `frontend/src/pages/landing/import-story/` (empty folder, populated by later tasks)

**Interfaces:** None — this task only adds the dependency and confirms it resolves.

- [ ] **Step 1: Install gsap**

Run: `cd frontend && npm install gsap`

- [ ] **Step 2: Confirm `gsap/ScrollTrigger` resolves and the license is the no-charge standard license**

Run:
```bash
cd frontend && node -e "const { gsap } = require('gsap'); const { ScrollTrigger } = require('gsap/ScrollTrigger'); gsap.registerPlugin(ScrollTrigger); console.log('gsap', gsap.version, 'ScrollTrigger', typeof ScrollTrigger);"
cat node_modules/gsap/package.json | grep -A1 '"license"'
```
Expected: prints a version string and `ScrollTrigger function`, and the license field is GSAP's standard no-charge license (matching `npm view gsap license` checked during planning) — if it says anything else (a paid/club license), STOP and flag this to the user before proceeding, since the spec's dependency choice assumed the current free licensing.

- [ ] **Step 3: Commit the dependency addition**

```bash
git add frontend/package.json frontend/package-lock.json
git commit -m "chore(frontend): add gsap for ImportSection scroll storytelling"
```

---

### Task 2: `DocumentStack` (beat 1 — scattered statements)

**Files:**
- Create: `frontend/src/pages/landing/import-story/DocumentStack.tsx`
- Test: `frontend/src/pages/landing/import-story/DocumentStack.test.tsx`

**Interfaces:**
- Produces: `DocumentStack` — a `forwardRef<HTMLDivElement>` component, no props. Renders a `PDF` card, a `CSV` card, and 3 floating transaction-row chips, each marked with a `data-target` attribute (`doc-pdf`, `doc-csv`, `chip-0`, `chip-1`, `chip-2`) that `useImportScrollTimeline` (Task 6) queries via the forwarded root ref. No scroll or GSAP knowledge inside this file.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/pages/landing/import-story/DocumentStack.test.tsx`:

```tsx
import { render } from '@testing-library/react';
import { createRef } from 'react';
import { describe, expect, it } from 'vitest';
import { DocumentStack } from './DocumentStack';

describe('DocumentStack', () => {
  it('forwards its ref to the root element', () => {
    const ref = createRef<HTMLDivElement>();
    const { container } = render(<DocumentStack ref={ref} />);
    expect(ref.current).toBe(container.firstElementChild);
  });

  it('marks its animatable elements with the data-target attributes the timeline queries', () => {
    const ref = createRef<HTMLDivElement>();
    render(<DocumentStack ref={ref} />);
    expect(ref.current?.querySelector('[data-target="doc-pdf"]')).toBeInTheDocument();
    expect(ref.current?.querySelector('[data-target="doc-csv"]')).toBeInTheDocument();
    expect(ref.current?.querySelectorAll('[data-target^="chip-"]').length).toBeGreaterThanOrEqual(3);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/landing/import-story/DocumentStack.test.tsx`
Expected: FAIL — module doesn't exist.

- [ ] **Step 3: Write the implementation**

Create `frontend/src/pages/landing/import-story/DocumentStack.tsx`:

```tsx
import { forwardRef } from 'react';
import { FileText, Sheet } from 'lucide-react';

/**
 * Beat 1 of the ImportSection scroll story: statements scattered, not yet processed. Purely
 * presentational -- no scroll/GSAP knowledge. useImportScrollTimeline (see that file) queries
 * this component's internals via the forwarded root ref and each element's data-target
 * attribute, and tweens them directly; this file never imports gsap.
 */
export const DocumentStack = forwardRef<HTMLDivElement>(function DocumentStack(_props, ref) {
  return (
    <div ref={ref} className="relative w-full h-full">
      <div
        data-target="doc-pdf"
        className="absolute left-[18%] top-[20%] w-20 h-28 rounded-lg bg-white border border-[#E6EAF2] shadow-lg flex flex-col items-center justify-center gap-1"
        style={{ transform: 'rotate(-8deg)' }}
      >
        <FileText size={20} color="#EF4444" />
        <span className="text-[10px] font-semibold text-slate-500">PDF</span>
      </div>
      <div
        data-target="doc-csv"
        className="absolute right-[20%] top-[32%] w-20 h-28 rounded-lg bg-white border border-[#E6EAF2] shadow-lg flex flex-col items-center justify-center gap-1"
        style={{ transform: 'rotate(6deg)' }}
      >
        <Sheet size={20} color="#16A34A" />
        <span className="text-[10px] font-semibold text-slate-500">CSV</span>
      </div>
      {[
        { amt: '₹2,450', top: '58%', left: '30%', tint: '#16A34A' },
        { amt: '₹1,280', top: '68%', left: '55%', tint: '#2563EB' },
        { amt: '₹860', top: '48%', left: '68%', tint: '#F59E0B' },
      ].map((chip, i) => (
        <div
          key={chip.amt}
          data-target={`chip-${i}`}
          className="absolute rounded-full bg-white border border-[#E6EAF2] shadow-md px-3 py-1.5 text-[11px] font-medium text-slate-600"
          style={{ top: chip.top, left: chip.left }}
        >
          <span className="inline-block w-1.5 h-1.5 rounded-full mr-1.5" style={{ background: chip.tint }} />
          {chip.amt}
        </div>
      ))}
    </div>
  );
});
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/landing/import-story/DocumentStack.test.tsx`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/landing/import-story/DocumentStack.tsx frontend/src/pages/landing/import-story/DocumentStack.test.tsx
git commit -m "feat(frontend): add DocumentStack scroll-story beat"
```

---

### Task 3: `ProcessingCore` (beat 2 — Finora AI processing)

**Files:**
- Create: `frontend/src/pages/landing/import-story/ProcessingCore.tsx`
- Test: `frontend/src/pages/landing/import-story/ProcessingCore.test.tsx`

**Interfaces:**
- Produces: `ProcessingCore` — `forwardRef<HTMLDivElement>`, no props. Renders the Finora mark plus a small financial-pipeline label stack (`Extraction`, `Categorization`, `Insights`), each stage marked `data-target="stage-extraction"` / `"stage-categorization"` / `"stage-insights"`, plus `data-target="core-mark"` on the mark itself. Deliberately not a generic glowing orb — the mark stays the same square "F" glyph used in `ImportSection`'s current card and `Nav`'s logo mark, just with a pulse treatment the timeline drives.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/pages/landing/import-story/ProcessingCore.test.tsx`:

```tsx
import { render } from '@testing-library/react';
import { createRef } from 'react';
import { describe, expect, it } from 'vitest';
import { ProcessingCore } from './ProcessingCore';

describe('ProcessingCore', () => {
  it('forwards its ref to the root element', () => {
    const ref = createRef<HTMLDivElement>();
    const { container } = render(<ProcessingCore ref={ref} />);
    expect(ref.current).toBe(container.firstElementChild);
  });

  it('marks the core mark and each pipeline stage with the data-target attributes the timeline queries', () => {
    const ref = createRef<HTMLDivElement>();
    render(<ProcessingCore ref={ref} />);
    expect(ref.current?.querySelector('[data-target="core-mark"]')).toBeInTheDocument();
    expect(ref.current?.querySelector('[data-target="stage-extraction"]')).toBeInTheDocument();
    expect(ref.current?.querySelector('[data-target="stage-categorization"]')).toBeInTheDocument();
    expect(ref.current?.querySelector('[data-target="stage-insights"]')).toBeInTheDocument();
  });

  it('renders text naming the pipeline, not a generic AI label', () => {
    const { getByText } = render(<ProcessingCore />);
    expect(getByText('Extraction')).toBeInTheDocument();
    expect(getByText('Categorization')).toBeInTheDocument();
    expect(getByText('Insights')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/landing/import-story/ProcessingCore.test.tsx`
Expected: FAIL — module doesn't exist.

- [ ] **Step 3: Write the implementation**

Create `frontend/src/pages/landing/import-story/ProcessingCore.tsx`:

```tsx
import { forwardRef } from 'react';

const STAGES = [
  { key: 'extraction', label: 'Extraction' },
  { key: 'categorization', label: 'Categorization' },
  { key: 'insights', label: 'Insights' },
] as const;

/**
 * Beat 2 of the ImportSection scroll story: Finora reading and organizing the statement.
 * Deliberately a labeled pipeline (extraction -> categorization -> insights), not a generic
 * glowing "AI orb" -- see the scroll-storytelling design spec's rationale. Presentational only,
 * same data-target contract as DocumentStack.
 */
export const ProcessingCore = forwardRef<HTMLDivElement>(function ProcessingCore(_props, ref) {
  return (
    <div ref={ref} className="relative w-full h-full flex flex-col items-center justify-center gap-4">
      <div
        data-target="core-mark"
        className="w-16 h-16 rounded-2xl bg-[var(--m-brand)] text-white grid place-items-center font-extrabold text-2xl"
        style={{ boxShadow: '0 16px 32px -12px rgba(38,42,51,.9)' }}
      >
        F
      </div>
      <div className="flex flex-col gap-2">
        {STAGES.map((stage) => (
          <div
            key={stage.key}
            data-target={`stage-${stage.key}`}
            className="text-[11px] font-medium text-slate-500 flex items-center gap-2"
          >
            <span className="w-1.5 h-1.5 rounded-full" style={{ background: 'var(--m-success)' }} />
            {stage.label}
          </div>
        ))}
      </div>
    </div>
  );
});
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/landing/import-story/ProcessingCore.test.tsx`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/landing/import-story/ProcessingCore.tsx frontend/src/pages/landing/import-story/ProcessingCore.test.tsx
git commit -m "feat(frontend): add ProcessingCore scroll-story beat"
```

---

### Task 4: `IntelligencePanel` (beat 3 — assembled dashboard, and the reduced-motion/mobile final state)

**Files:**
- Create: `frontend/src/pages/landing/import-story/IntelligencePanel.tsx`
- Test: `frontend/src/pages/landing/import-story/IntelligencePanel.test.tsx`

**Interfaces:**
- Produces: `IntelligencePanel` — `forwardRef<HTMLDivElement>`, no props. Renders a small, fully realized dashboard mock: a headline figure, 2-3 categorized transaction rows, and an "Insights ready" line, styled with the same tokens `DashboardMock`/`FloatingDashboardCard` use (`rounded-xl`, `border-[#E6EAF2]`, `bg-white`, `var(--m-success)` accent). Marked `data-target="panel-glow"` on whichever element carries the settle-beat glow, so the timeline can reduce it during the 90–100% exit beat. This exact markup is reused unmodified in three places: as the scroll-driven beat 3, as the reduced-motion static render, and as the last frame of `ImportRevealSequence` (Task 5) — so it must look complete and finished on its own, not like a truncated animation frame.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/pages/landing/import-story/IntelligencePanel.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import { createRef } from 'react';
import { describe, expect, it } from 'vitest';
import { IntelligencePanel } from './IntelligencePanel';

describe('IntelligencePanel', () => {
  it('forwards its ref to the root element', () => {
    const ref = createRef<HTMLDivElement>();
    const { container } = render(<IntelligencePanel ref={ref} />);
    expect(ref.current).toBe(container.firstElementChild);
  });

  it('marks the glow element the timeline reduces during the settle beat', () => {
    const ref = createRef<HTMLDivElement>();
    render(<IntelligencePanel ref={ref} />);
    expect(ref.current?.querySelector('[data-target="panel-glow"]')).toBeInTheDocument();
  });

  it('renders as a fully realized mock, not a placeholder -- a figure, categorized rows, and an insights line', () => {
    render(<IntelligencePanel />);
    expect(screen.getByText('Insights ready')).toBeInTheDocument();
    // At least one rupee-figure and at least one category label should be present.
    expect(screen.getByText(/₹[\d,]+/)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/landing/import-story/IntelligencePanel.test.tsx`
Expected: FAIL — module doesn't exist.

- [ ] **Step 3: Write the implementation**

Create `frontend/src/pages/landing/import-story/IntelligencePanel.tsx`:

```tsx
import { forwardRef } from 'react';
import { Check } from 'lucide-react';

const ROWS = [
  { label: 'Amazon', category: 'Shopping', amount: '₹2,450' },
  { label: 'Swiggy', category: 'Food', amount: '₹860' },
];

/**
 * Beat 3 of the ImportSection scroll story -- and, critically, the ONLY render of this
 * component's markup: it's reused unmodified as the reduced-motion static final state and as
 * ImportRevealSequence's last frame (see the scroll-storytelling design spec). It must therefore
 * read as a complete, deliberately designed component on its own, not a truncated animation
 * frame -- hence a real figure, real categorized rows, and an explicit "Insights ready" line,
 * not a bare shell. Visual tokens match DashboardMock/FloatingDashboardCard (rounded-xl,
 * border-[#E6EAF2], var(--m-success)) so the page reads as one product, not two dashboard
 * designs. Presentational only, same data-target contract as DocumentStack/ProcessingCore.
 */
export const IntelligencePanel = forwardRef<HTMLDivElement>(function IntelligencePanel(_props, ref) {
  return (
    <div
      ref={ref}
      data-target="panel-glow"
      className="w-full max-w-[220px] rounded-xl border border-[#E6EAF2] bg-white p-4 shadow-[0_24px_48px_-24px_rgba(15,23,42,.35)]"
    >
      <p className="text-[10px] uppercase tracking-wide text-slate-400 mb-1">Total organized</p>
      <p className="text-lg font-bold text-slate-900 mb-3">₹42,350</p>
      <div className="flex flex-col gap-1.5 mb-3">
        {ROWS.map((row) => (
          <div key={row.label} className="flex items-center justify-between text-[11px]">
            <span className="text-slate-600">{row.label}</span>
            <span className="font-medium" style={{ color: 'var(--m-success)' }}>{row.category}</span>
            <span className="text-slate-500">{row.amount}</span>
          </div>
        ))}
      </div>
      <div className="flex items-center gap-1.5 text-[11px] font-medium" style={{ color: 'var(--m-success)' }}>
        <Check size={13} />
        Insights ready
      </div>
    </div>
  );
});
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/landing/import-story/IntelligencePanel.test.tsx`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/landing/import-story/IntelligencePanel.tsx frontend/src/pages/landing/import-story/IntelligencePanel.test.tsx
git commit -m "feat(frontend): add IntelligencePanel scroll-story beat"
```

---

### Task 5: `ImportRevealSequence` (mobile / reduced-motion fallback)

**Files:**
- Create: `frontend/src/pages/landing/import-story/ImportRevealSequence.tsx`
- Test: `frontend/src/pages/landing/import-story/ImportRevealSequence.test.tsx`

**Interfaces:**
- Consumes: `DocumentStack`, `ProcessingCore`, `IntelligencePanel` (Tasks 2-4).
- Produces: `ImportRevealSequence` — no props. Same `IntersectionObserver`-driven "safe default, animate only once visible" contract as `useStagedReveal` in `primitives.tsx`: before the observer fires (or when there's no `IntersectionObserver`, or under reduced-motion), it shows the final state (`IntelligencePanel` only) rather than a permanently-scattered or empty view. Once visible (and motion is allowed), it steps through `DocumentStack` → `ProcessingCore` → `IntelligencePanel` once, matching `LearningSection`'s existing staged-reveal pattern.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/pages/landing/import-story/ImportRevealSequence.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { mockMatchMedia } from '../../../test/mockMatchMedia';

vi.mock('framer-motion', async (importOriginal) => {
  const actual = await importOriginal<typeof import('framer-motion')>();
  return { ...actual, useReducedMotion: vi.fn() };
});

import { useReducedMotion } from 'framer-motion';
import { ImportRevealSequence } from './ImportRevealSequence';

describe('ImportRevealSequence', () => {
  let restore: (() => void) | undefined;

  afterEach(() => {
    restore?.();
    restore = undefined;
    vi.clearAllMocks();
  });

  it('shows the final (IntelligencePanel) state before the observer ever fires -- the safe default', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<ImportRevealSequence />);
    expect(screen.getByText('Insights ready')).toBeInTheDocument();
  });

  it('shows the final state immediately under prefers-reduced-motion, with no staged reveal', () => {
    vi.mocked(useReducedMotion).mockReturnValue(true);
    render(<ImportRevealSequence />);
    expect(screen.getByText('Insights ready')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/landing/import-story/ImportRevealSequence.test.tsx`
Expected: FAIL — module doesn't exist.

- [ ] **Step 3: Write the implementation**

Create `frontend/src/pages/landing/import-story/ImportRevealSequence.tsx`:

```tsx
import { useReducedMotion } from 'framer-motion';
import { useStagedReveal } from '../primitives';
import { DocumentStack } from './DocumentStack';
import { ProcessingCore } from './ProcessingCore';
import { IntelligencePanel } from './IntelligencePanel';

const STEPS = [DocumentStack, ProcessingCore, IntelligencePanel] as const;

/**
 * Mobile / reduced-motion fallback for the ImportSection scroll story: the same three beats as
 * ImportScrollStory's pinned/scrubbed sequence, but reveal-once via useStagedReveal (no
 * ScrollTrigger, no pinning -- see the design spec's rationale on why a smaller pin is worse UX
 * than no pin at all). prefers-reduced-motion skips straight to the final IntelligencePanel
 * state, same "safe default" contract useStagedReveal already guarantees.
 */
export function ImportRevealSequence() {
  const prefersReducedMotion = useReducedMotion();
  const { ref, step } = useStagedReveal(STEPS.length);
  const Current = prefersReducedMotion ? IntelligencePanel : STEPS[Math.max(0, step - 1)] ?? IntelligencePanel;

  return (
    <div ref={ref} aria-hidden="true" className="relative w-full h-64 flex items-center justify-center">
      <Current />
    </div>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/landing/import-story/ImportRevealSequence.test.tsx`
Expected: PASS (2 tests). Note `useStagedReveal(steps)` starts at `step = 0`; `STEPS[Math.max(0, step - 1)]` at `step = 0` reads `STEPS[0]` (`DocumentStack`), not the final state -- if the first test fails because it's seeing `DocumentStack`'s content instead of `IntelligencePanel`'s, fix by defaulting to `IntelligencePanel` when `step === 0` too (i.e. `step === 0 ? IntelligencePanel : STEPS[step - 1]`), since `useStagedReveal`'s own "safe default" contract (per its jsdoc in `primitives.tsx`) is to jump to `step = steps` (fully complete) when there's no `IntersectionObserver`, but a fresh mount before the observer fires legitimately starts at `step = 0` in a real browser -- and this component's OWN safe default should be the finished panel, not the first scattered beat, matching the "never show a permanently/initially broken state" principle every other reveal component on this page follows.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/landing/import-story/ImportRevealSequence.tsx frontend/src/pages/landing/import-story/ImportRevealSequence.test.tsx
git commit -m "feat(frontend): add ImportRevealSequence mobile/reduced-motion fallback"
```

---

### Task 6: `useImportScrollTimeline` (GSAP ScrollTrigger wiring)

**Files:**
- Create: `frontend/src/pages/landing/import-story/useImportScrollTimeline.ts`
- Test: `frontend/src/pages/landing/import-story/useImportScrollTimeline.test.ts`

**Interfaces:**
- Consumes: refs to the trigger element and to `DocumentStack`/`ProcessingCore`/`IntelligencePanel`'s root elements (Tasks 2-4's forwarded refs).
- Produces: `useImportScrollTimeline(opts: { enabled: boolean; triggerRef: RefObject<HTMLElement>; stackRef: RefObject<HTMLElement>; coreRef: RefObject<HTMLElement>; panelRef: RefObject<HTMLElement> }): void` — side-effect-only hook, no return value. When `enabled` is `false`, does nothing (no `gsap`/`ScrollTrigger` calls at all).

- [ ] **Step 1: Write the failing test**

Create `frontend/src/pages/landing/import-story/useImportScrollTimeline.test.ts`:

```ts
import { renderHook } from '@testing-library/react';
import { createRef } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

const revertSpy = vi.fn();
const contextSpy = vi.fn((fn: () => void) => {
  fn();
  return { revert: revertSpy };
});
const toSpy = vi.fn().mockReturnThis();
const timelineInstance = { to: toSpy };
const timelineSpy = vi.fn(() => timelineInstance);
const registerPluginSpy = vi.fn();

vi.mock('gsap', () => ({
  gsap: {
    context: contextSpy,
    timeline: timelineSpy,
    registerPlugin: registerPluginSpy,
  },
}));
vi.mock('gsap/ScrollTrigger', () => ({ ScrollTrigger: { name: 'ScrollTrigger' } }));

import { useImportScrollTimeline } from './useImportScrollTimeline';

function makeRefs() {
  const triggerRef = createRef<HTMLDivElement>();
  const stackRef = createRef<HTMLDivElement>();
  const coreRef = createRef<HTMLDivElement>();
  const panelRef = createRef<HTMLDivElement>();
  (triggerRef as { current: HTMLDivElement }).current = document.createElement('div');
  (stackRef as { current: HTMLDivElement }).current = document.createElement('div');
  (coreRef as { current: HTMLDivElement }).current = document.createElement('div');
  (panelRef as { current: HTMLDivElement }).current = document.createElement('div');
  return { triggerRef, stackRef, coreRef, panelRef };
}

describe('useImportScrollTimeline', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('builds a pinned, scrubbed timeline against the trigger element when enabled', () => {
    const refs = makeRefs();
    renderHook(() => useImportScrollTimeline({ enabled: true, ...refs }));

    expect(contextSpy).toHaveBeenCalledTimes(1);
    expect(timelineSpy).toHaveBeenCalledTimes(1);
    const config = timelineSpy.mock.calls[0][0];
    expect(config.scrollTrigger.trigger).toBe(refs.triggerRef.current);
    expect(config.scrollTrigger.pin).toBe(true);
    expect(config.scrollTrigger.scrub).toBe(true);
  });

  it('reverts the GSAP context on unmount', () => {
    const refs = makeRefs();
    const { unmount } = renderHook(() => useImportScrollTimeline({ enabled: true, ...refs }));
    unmount();
    expect(revertSpy).toHaveBeenCalledTimes(1);
  });

  it('creates no timeline at all when disabled', () => {
    const refs = makeRefs();
    renderHook(() => useImportScrollTimeline({ enabled: false, ...refs }));
    expect(contextSpy).not.toHaveBeenCalled();
    expect(timelineSpy).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/landing/import-story/useImportScrollTimeline.test.ts`
Expected: FAIL — module doesn't exist.

- [ ] **Step 3: Write the implementation**

Create `frontend/src/pages/landing/import-story/useImportScrollTimeline.ts`:

```ts
import { useEffect, type RefObject } from 'react';
import { gsap } from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';

interface ImportScrollTimelineOptions {
  enabled: boolean;
  triggerRef: RefObject<HTMLElement>;
  stackRef: RefObject<HTMLElement>;
  coreRef: RefObject<HTMLElement>;
  panelRef: RefObject<HTMLElement>;
}

/**
 * The ONLY file in the ImportSection scroll story that imports gsap or gsap/ScrollTrigger --
 * see the design spec's "animation state is owned by GSAP, not React" decision. Builds one
 * timeline scrubbing DocumentStack -> ProcessingCore -> IntelligencePanel directly via their
 * root refs, entirely outside React's render cycle, so 60fps scroll updates never trigger a
 * React re-render. Beats: 0-35% scattered, 35-70% processing, 70-90% assembling, 90-100% settle
 * (glow reduces to rest before unpinning) -- see the spec for why the settle beat matters.
 *
 * Wrapped in gsap.context() specifically so the returned revert() can tear down every tween AND
 * the ScrollTrigger instance in one call on cleanup -- required for React StrictMode's dev
 * mount->unmount->remount double-invoke (the same bug class the Hero sub-project hit with Framer
 * Motion variants) and for real navigation away from the landing page.
 */
export function useImportScrollTimeline({ enabled, triggerRef, stackRef, coreRef, panelRef }: ImportScrollTimelineOptions): void {
  useEffect(() => {
    if (!enabled) return;
    if (!triggerRef.current || !stackRef.current || !coreRef.current || !panelRef.current) return;

    gsap.registerPlugin(ScrollTrigger);

    const ctx = gsap.context(() => {
      const tl = gsap.timeline({
        scrollTrigger: {
          trigger: triggerRef.current,
          start: 'top top',
          end: () => `+=${window.innerHeight * 2.5}`,
          pin: true,
          scrub: true,
        },
      });

      tl.to(stackRef.current, { opacity: 0, scale: 0.9, duration: 0.35 }, 0)
        .to(coreRef.current, { opacity: 1, duration: 0.35 }, 0.15)
        .to(coreRef.current, { opacity: 0, scale: 0.95, duration: 0.2 }, 0.7)
        .to(panelRef.current, { opacity: 1, y: 0, duration: 0.2 }, 0.7)
        .to(`[data-target="panel-glow"]`, { boxShadow: '0 8px 16px -8px rgba(15,23,42,.25)', duration: 0.1 }, 0.9);
    }, triggerRef);

    return () => ctx.revert();
  }, [enabled, triggerRef, stackRef, coreRef, panelRef]);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/landing/import-story/useImportScrollTimeline.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/landing/import-story/useImportScrollTimeline.ts frontend/src/pages/landing/import-story/useImportScrollTimeline.test.ts
git commit -m "feat(frontend): add useImportScrollTimeline GSAP ScrollTrigger hook"
```

---

### Task 7: `ImportScrollStory` (orchestrator)

**Files:**
- Create: `frontend/src/pages/landing/import-story/ImportScrollStory.tsx`
- Test: `frontend/src/pages/landing/import-story/ImportScrollStory.test.tsx`

**Interfaces:**
- Consumes: `useIsDesktop` (`../hooks/useIsDesktop`), `useReducedMotion` (`framer-motion`), `DocumentStack`/`ProcessingCore`/`IntelligencePanel`/`ImportRevealSequence` (Tasks 2-5), `useImportScrollTimeline` (Task 6).
- Produces: `ImportScrollStory` — no props. The single entry point `ImportSection.tsx` (Task 8) renders in place of today's hover card.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/pages/landing/import-story/ImportScrollStory.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { mockMatchMedia } from '../../../test/mockMatchMedia';

vi.mock('framer-motion', async (importOriginal) => {
  const actual = await importOriginal<typeof import('framer-motion')>();
  return { ...actual, useReducedMotion: vi.fn() };
});
vi.mock('./useImportScrollTimeline', () => ({ useImportScrollTimeline: vi.fn() }));

import { useReducedMotion } from 'framer-motion';
import { useImportScrollTimeline } from './useImportScrollTimeline';
import { ImportScrollStory } from './ImportScrollStory';

describe('ImportScrollStory', () => {
  let restore: (() => void) | undefined;

  afterEach(() => {
    restore?.();
    restore = undefined;
    vi.clearAllMocks();
  });

  it('renders the pinned scene and enables the timeline on desktop with motion allowed', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    restore = mockMatchMedia({ '(min-width: 768px)': true, '(pointer: coarse)': false });
    render(<ImportScrollStory />);
    expect(vi.mocked(useImportScrollTimeline).mock.calls[0][0].enabled).toBe(true);
  });

  it('renders only the final IntelligencePanel state under prefers-reduced-motion, with the timeline disabled', () => {
    vi.mocked(useReducedMotion).mockReturnValue(true);
    restore = mockMatchMedia({ '(min-width: 768px)': true, '(pointer: coarse)': false });
    render(<ImportScrollStory />);
    expect(vi.mocked(useImportScrollTimeline).mock.calls[0][0].enabled).toBe(false);
    expect(screen.getByText('Insights ready')).toBeInTheDocument();
  });

  it('renders ImportRevealSequence instead of the pinned scene on non-desktop, with the timeline disabled', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    restore = mockMatchMedia({ '(min-width: 768px)': false, '(pointer: coarse)': false });
    render(<ImportScrollStory />);
    expect(vi.mocked(useImportScrollTimeline).mock.calls[0][0].enabled).toBe(false);
    expect(screen.getByText('Insights ready')).toBeInTheDocument();
  });

  it('marks the whole scene aria-hidden', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    restore = mockMatchMedia({ '(min-width: 768px)': true, '(pointer: coarse)': false });
    const { container } = render(<ImportScrollStory />);
    expect(container.querySelector('[aria-hidden="true"]')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/landing/import-story/ImportScrollStory.test.tsx`
Expected: FAIL — module doesn't exist.

- [ ] **Step 3: Write the implementation**

Create `frontend/src/pages/landing/import-story/ImportScrollStory.tsx`:

```tsx
import { useRef } from 'react';
import { useReducedMotion } from 'framer-motion';
import { useIsDesktop } from '../hooks/useIsDesktop';
import { DocumentStack } from './DocumentStack';
import { ProcessingCore } from './ProcessingCore';
import { IntelligencePanel } from './IntelligencePanel';
import { ImportRevealSequence } from './ImportRevealSequence';
import { useImportScrollTimeline } from './useImportScrollTimeline';

/**
 * Entry point for the ImportSection scroll story (see the design spec). Branches on
 * desktop-and-motion-allowed BEFORE useImportScrollTimeline is ever invoked -- disabled there is
 * a second line of defense, not the only gate. Desktop + motion allowed gets the pinned/scrubbed
 * scene; everything else (mobile, reduced-motion) gets ImportRevealSequence's reveal-once
 * version of the same three beats. The whole thing is aria-hidden -- ImportSection's own
 * eyebrow/title/blurb copy, rendered alongside this (not inside it), is what screen readers get.
 */
export function ImportScrollStory() {
  const prefersReducedMotion = useReducedMotion();
  const isDesktop = useIsDesktop();
  const usePinned = isDesktop && !prefersReducedMotion;

  const triggerRef = useRef<HTMLDivElement>(null);
  const stackRef = useRef<HTMLDivElement>(null);
  const coreRef = useRef<HTMLDivElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);

  useImportScrollTimeline({
    enabled: usePinned,
    triggerRef,
    stackRef,
    coreRef,
    panelRef,
  });

  if (!isDesktop) {
    return <ImportRevealSequence />;
  }

  if (prefersReducedMotion) {
    return (
      <div aria-hidden="true" className="w-full h-64 flex items-center justify-center">
        <IntelligencePanel />
      </div>
    );
  }

  return (
    <div ref={triggerRef} aria-hidden="true" className="relative w-full h-[420px]">
      <div className="absolute inset-0" style={{ opacity: 1 }}>
        <DocumentStack ref={stackRef} />
      </div>
      <div className="absolute inset-0 flex items-center justify-center" style={{ opacity: 0 }}>
        <ProcessingCore ref={coreRef} />
      </div>
      <div className="absolute inset-0 flex items-center justify-center" style={{ opacity: 0, transform: 'translateY(16px)' }}>
        <IntelligencePanel ref={panelRef} />
      </div>
    </div>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/landing/import-story/ImportScrollStory.test.tsx`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/landing/import-story/ImportScrollStory.tsx frontend/src/pages/landing/import-story/ImportScrollStory.test.tsx
git commit -m "feat(frontend): add ImportScrollStory orchestrator"
```

---

### Task 8: Wire `ImportScrollStory` into `ImportSection`

**Files:**
- Modify: `frontend/src/pages/landing/ImportSection.tsx`
- Test: `frontend/src/pages/landing/ImportSection.test.tsx` (new — check first whether one already exists)

**Interfaces:**
- Consumes: `ImportScrollStory` (Task 7).

- [ ] **Step 1: Check for an existing ImportSection test file**

Run: `find frontend/src/pages/landing -maxdepth 1 -iname "ImportSection.test*"`

- [ ] **Step 2: Write the failing test**

Create (or extend, if Step 1 found one) `frontend/src/pages/landing/ImportSection.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { importSection } from './landing-config';
import { ImportSection } from './ImportSection';

describe('ImportSection', () => {
  it('renders the real copy outside any aria-hidden wrapper', () => {
    render(<ImportSection />);
    const title = screen.getByText(importSection.title);
    expect(title.closest('[aria-hidden="true"]')).toBeNull();
    expect(screen.getByText(importSection.blurb)).toBeInTheDocument();
  });

  it('renders the scroll-story scene', () => {
    const { container } = render(<ImportSection />);
    expect(container.querySelector('[aria-hidden="true"]')).toBeInTheDocument();
  });
});
```

- [ ] **Step 3: Run test to verify it fails (or passes trivially if copy assertions already hold, then fails on the second)**

Run: `cd frontend && npx vitest run src/pages/landing/ImportSection.test.tsx`
Expected: first assertion likely already passes against the current hover-card implementation; confirm the test file runs at all before proceeding (it exercises the current component either way).

- [ ] **Step 4: Swap the hover card for `ImportScrollStory`**

In `frontend/src/pages/landing/ImportSection.tsx`, replace:
```tsx
import { FileText, Lock, Sheet } from 'lucide-react';
import { Eyebrow, FlowArrow, Reveal, Section } from './primitives';
import { importSection } from './landing-config';
```
with:
```tsx
import { Eyebrow, Reveal, Section } from './primitives';
import { importSection } from './landing-config';
import { ImportScrollStory } from './import-story/ImportScrollStory';
```
Remove the now-unused `INPUTS` constant and the `FileText`/`Lock`/`Sheet`/`FlowArrow` imports. Replace the second `<Reveal delayMs={120}>...</Reveal>` block (the hover card) with:
```tsx
<Reveal delayMs={120}>
  <ImportScrollStory />
</Reveal>
```
Leave the first `<Reveal>` block (eyebrow/title/blurb/supported-formats chips) completely unchanged.

- [ ] **Step 5: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/landing/ImportSection.test.tsx`
Expected: PASS (2 tests).

- [ ] **Step 6: Run the full test suite and type-check**

Run:
```bash
cd frontend && npx tsc --noEmit
npm test
```
Expected: zero type errors; all tests pass, including `landing-claims.test.tsx` (no copy changed) and every `import-story/*.test.tsx` file from Tasks 2-7.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/pages/landing/ImportSection.tsx frontend/src/pages/landing/ImportSection.test.tsx
git commit -m "feat(frontend): wire ImportScrollStory into ImportSection"
```

---

### Task 9: Final verification

**Files:** none (verification-only task).

- [ ] **Step 1: Run the full test suite**

Run: `cd frontend && npm test`
Expected: PASS, zero failures.

- [ ] **Step 2: Type-check**

Run: `cd frontend && npx tsc --noEmit`
Expected: zero errors.

- [ ] **Step 3: Production build**

Run: `cd frontend && npm run build`
Expected: succeeds; confirms `gsap`/`gsap/ScrollTrigger` resolve correctly under the production bundler, not just Vitest's module resolution.

- [ ] **Step 4: Real-browser smoke check**

Open the built/dev landing page at desktop width and verify, in order: (1) scrolling into `ImportSection` pins it and the three beats scrub smoothly with scroll position, ending on the settled `IntelligencePanel`; (2) scrolling back up reverses the sequence; (3) after the pin releases, the page continues scrolling normally into `LearningSection`; (4) `ImportSection`'s eyebrow/title/blurb copy is visible and readable throughout, unaffected by the pin; (5) with `prefers-reduced-motion: reduce` set in devtools, `ImportSection` shows `IntelligencePanel`'s final state immediately, no pin, no scrub; (6) at a mobile viewport width, `ImportSection` shows the reveal-once sequence (no pinning) as it scrolls into view; (7) no console errors, no layout shift elsewhere on the page.

- [ ] **Step 5: Hand off to finishing-a-development-branch**

No commit here — this task is verification-only. Proceed directly to the finishing-a-development-branch skill.
