# Hero Cinematic Reveal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite the Finora landing page Hero into a cinematic, premium-SaaS reveal — dark background, a real-DOM dashboard preview with CSS-3D tilt, an ambient WebGL particle layer, a count-up financial health score, an "analyzing your finances" intelligence checklist, and floating data badges — while keeping all copy, claims, and the existing motion primitives unchanged.

**Architecture:** `Hero.tsx` becomes a thin orchestrator using Framer Motion's `staggerChildren` to sequence five new sub-components under `pages/landing/hero/`. The dashboard preview stays real DOM (wrapping the existing `DashboardMock`); React Three Fiber is isolated to a single code-split, lazy-loaded ambient background layer that never renders the dashboard itself. `CountUp` and `useStagedReveal` from the existing `primitives.tsx` are reused unchanged for the score count-up and the intelligence checklist.

**Tech Stack:** React 19, TypeScript, Tailwind CSS, Framer Motion (new), React Three Fiber + three.js (new, ambient layer only), Vitest + Testing Library (existing).

**Spec:** `docs/superpowers/specs/2026-08-22-hero-cinematic-reveal-design.md`

## Global Constraints

- Copy does not change: `landing-config.ts`'s existing exports and `landing-claims.test.tsx` are untouched; only new copy is added.
- The dashboard preview is never rendered inside WebGL — R3F is scoped to an ambient background layer only.
- `@react-three/drei` is not added — the ambient layer uses plain `@react-three/fiber`/`three` only.
- GSAP and Lenis are not added in this pass.
- Floating badge motion uses fixed, deterministic delays — never `Math.random()`.
- Below Tailwind's `md` breakpoint (768px): no `AmbientCanvas`, no mouse-tilt.
- `prefers-reduced-motion: reduce`: every entrance/loop/tilt animation is skipped and the final visual state renders immediately; `AmbientCanvas` does not mount.
- `AmbientCanvas`'s three.js/`@react-three/fiber` imports must stay in a separate, lazy-loaded chunk — never in the initial landing route bundle.

---

## Task 1: Install animation dependencies

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`

**Interfaces:**
- Produces: `framer-motion`, `three`, `@react-three/fiber` as runtime deps; `@types/three` as a dev dep, available to every later task.

- [x] **Step 1: Install the packages**

Run:
```bash
cd frontend && npm install framer-motion three @react-three/fiber --save
npm install @types/three --save-dev
```

- [x] **Step 2: Verify they resolve and the project still type-checks**

Run: `cd frontend && npx tsc -b`
Expected: no output (clean exit).

- [x] **Step 3: Commit**

```bash
cd frontend && git add package.json package-lock.json
git commit -m "chore(frontend): add framer-motion, three, @react-three/fiber"
```

---

## Task 2: Add hero copy to landing-config.ts

**Files:**
- Modify: `frontend/src/pages/landing/landing-config.ts`
- Create: `frontend/src/pages/landing/landing-config.test.ts`

**Interfaces:**
- Produces: `heroScore: { label: string; value: number; delta: string }`, `heroIntelligence: { heading: string; steps: string[] }`, `heroBadges: { label: string }[]` — consumed by Tasks 7, 8, 9.

- [x] **Step 1: Write the failing test**

```typescript
// frontend/src/pages/landing/landing-config.test.ts
import { describe, expect, it } from 'vitest';
import { heroBadges, heroIntelligence, heroScore } from './landing-config';

describe('hero cinematic copy', () => {
  it('keeps the health score within a real 0-100 range', () => {
    expect(heroScore.value).toBeGreaterThanOrEqual(0);
    expect(heroScore.value).toBeLessThanOrEqual(100);
    expect(heroScore.label.length).toBeGreaterThan(0);
    expect(heroScore.delta.length).toBeGreaterThan(0);
  });

  it('has at least one intelligence-scan step', () => {
    expect(heroIntelligence.steps.length).toBeGreaterThan(0);
    heroIntelligence.steps.forEach((step) => expect(step.length).toBeGreaterThan(0));
  });

  it('keeps the salary badge consistent with the dashboard mock\'s own salary figure', () => {
    // DashboardMock's TRANSACTIONS lists "Salary Credit" at +₹1,24,500 -- a badge quoting a
    // different salary number on the same screen would be the kind of internal inconsistency
    // DashboardMock's own file comment calls out as the first thing a finance-literate visitor
    // notices.
    const salaryBadge = heroBadges.find((b) => b.label.includes('Salary'));
    expect(salaryBadge?.label).toContain('1,24,500');
  });

  it('has at least three floating badges', () => {
    expect(heroBadges.length).toBeGreaterThanOrEqual(3);
  });
});
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/landing/landing-config.test.ts`
Expected: FAIL — `heroScore`/`heroIntelligence`/`heroBadges` are not exported yet.

- [x] **Step 3: Add the copy to landing-config.ts**

Add after the existing `hero` export in `frontend/src/pages/landing/landing-config.ts`:

```typescript
/**
 * Copy for the cinematic hero's score ring, intelligence-scan checklist, and floating data
 * badges. Same rule as the rest of this file: these are illustrative figures, kept internally
 * consistent with the numbers DashboardMock already shows elsewhere on the page (see
 * DashboardMock.tsx's own note on why that matters).
 */
export const heroScore = {
  label: 'Financial Health',
  value: 84,
  delta: '+6 this month',
};

export const heroIntelligence = {
  heading: 'Analyzing your finances…',
  steps: [
    'Spending patterns detected',
    'Subscription detected',
    'Saving opportunity found',
    'Financial health calculated',
  ],
};

export const heroBadges = [
  { label: '+₹1,24,500 Salary' },
  { label: 'Investment +12%' },
  { label: 'Goal 72%' },
  { label: 'AI Insight ✨' },
  { label: 'Savings improved' },
];
```

- [x] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/landing/landing-config.test.ts`
Expected: PASS (4 tests)

- [x] **Step 5: Commit**

```bash
cd frontend && git add src/pages/landing/landing-config.ts src/pages/landing/landing-config.test.ts
git commit -m "feat(landing): add hero cinematic copy (score, intelligence scan, badges)"
```

---

## Task 3: Shared matchMedia test helper

**Files:**
- Create: `frontend/src/test/mockMatchMedia.ts`

**Interfaces:**
- Produces: `mockMatchMedia(queryToMatches: Record<string, boolean>): () => void` — patches `window.matchMedia` for the queries given, returns a restore function. Consumed by Tasks 4, 6, 7, 10, 11.

No test file for this task: it's a test utility, not app code, and its only real behavior is exercised by every test that uses it starting with Task 4.

- [x] **Step 1: Write the helper**

```typescript
// frontend/src/test/mockMatchMedia.ts
import { vi } from 'vitest';

/**
 * Swaps window.matchMedia for one that resolves each query against `queryToMatches`, defaulting
 * any unlisted query to false. src/test/setup.ts installs a default no-op matchMedia globally
 * (always matches: false); several hero tests need to flip a *specific* query -- prefers-reduced-
 * motion, min-width -- per test, which this makes possible without touching the global default.
 */
export function mockMatchMedia(queryToMatches: Record<string, boolean>): () => void {
  const original = window.matchMedia;
  window.matchMedia = vi.fn((query: string) => ({
    matches: queryToMatches[query] ?? false,
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(() => false),
  })) as unknown as typeof window.matchMedia;
  return () => {
    window.matchMedia = original;
  };
}
```

- [x] **Step 2: Verify it type-checks**

Run: `cd frontend && npx tsc -b`
Expected: no output (clean exit).

- [x] **Step 3: Commit**

```bash
cd frontend && git add src/test/mockMatchMedia.ts
git commit -m "test(frontend): add mockMatchMedia helper for per-test media queries"
```

---

## Task 4: `webglSupport.ts` — WebGL feature detection

**Files:**
- Create: `frontend/src/pages/landing/hero/webglSupport.ts`
- Test: `frontend/src/pages/landing/hero/webglSupport.test.ts`

**Interfaces:**
- Produces: `isWebglAvailable(): boolean`. Consumed by Task 6 (`AmbientCanvas.tsx`).

- [x] **Step 1: Write the failing test**

```typescript
// frontend/src/pages/landing/hero/webglSupport.test.ts
import { describe, expect, it } from 'vitest';
import { isWebglAvailable } from './webglSupport';

describe('isWebglAvailable', () => {
  it('returns false in jsdom, which implements no WebGL context', () => {
    // jsdom's canvas.getContext('webgl') always returns null -- this test documents that
    // environment fact and pins the function's real, unmocked behavior in this suite. Tests that
    // need the "WebGL present" branch mock this module directly (see AmbientCanvas.test.tsx).
    expect(isWebglAvailable()).toBe(false);
  });

  it('does not throw if canvas.getContext throws', () => {
    const original = HTMLCanvasElement.prototype.getContext;
    // @ts-expect-error -- deliberately breaking the mock to exercise the catch branch
    HTMLCanvasElement.prototype.getContext = () => {
      throw new Error('no context for you');
    };
    expect(() => isWebglAvailable()).not.toThrow();
    expect(isWebglAvailable()).toBe(false);
    HTMLCanvasElement.prototype.getContext = original;
  });
});
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/landing/hero/webglSupport.test.ts`
Expected: FAIL — module `./webglSupport` doesn't exist yet.

- [x] **Step 3: Write the implementation**

```typescript
// frontend/src/pages/landing/hero/webglSupport.ts
/**
 * Detects real WebGL support by actually creating a context, rather than trusting the UA string
 * -- some browsers report support they can't deliver (GPU blocklisted, disabled by policy), and
 * creating the context is the only reliable way to find out before React Three Fiber tries and
 * throws mid-render.
 */
export function isWebglAvailable(): boolean {
  try {
    const canvas = document.createElement('canvas');
    return !!(canvas.getContext('webgl2') || canvas.getContext('webgl'));
  } catch {
    return false;
  }
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/landing/hero/webglSupport.test.ts`
Expected: PASS (2 tests)

- [x] **Step 5: Commit**

```bash
cd frontend && git add src/pages/landing/hero/webglSupport.ts src/pages/landing/hero/webglSupport.test.ts
git commit -m "feat(landing/hero): add WebGL feature detection"
```

---

## Task 5: `useIsDesktop.ts` — breakpoint hook

**Files:**
- Create: `frontend/src/pages/landing/hero/useIsDesktop.ts`
- Test: `frontend/src/pages/landing/hero/useIsDesktop.test.ts`

**Interfaces:**
- Produces: `useIsDesktop(): boolean` (true at ≥768px). Consumed by Task 6 (`AmbientCanvas.tsx`) and Task 10 (`FloatingDashboardCard.tsx`).

- [x] **Step 1: Write the failing test**

```typescript
// frontend/src/pages/landing/hero/useIsDesktop.test.ts
import { renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { mockMatchMedia } from '../../../test/mockMatchMedia';
import { useIsDesktop } from './useIsDesktop';

describe('useIsDesktop', () => {
  let restore: (() => void) | undefined;

  afterEach(() => {
    restore?.();
    restore = undefined;
  });

  it('returns true when the min-width: 768px query matches', () => {
    restore = mockMatchMedia({ '(min-width: 768px)': true });
    const { result } = renderHook(() => useIsDesktop());
    expect(result.current).toBe(true);
  });

  it('returns false when the min-width: 768px query does not match', () => {
    restore = mockMatchMedia({ '(min-width: 768px)': false });
    const { result } = renderHook(() => useIsDesktop());
    expect(result.current).toBe(false);
  });
});
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/landing/hero/useIsDesktop.test.ts`
Expected: FAIL — module `./useIsDesktop` doesn't exist yet.

- [x] **Step 3: Write the implementation**

```typescript
// frontend/src/pages/landing/hero/useIsDesktop.ts
import { useEffect, useState } from 'react';

const QUERY = '(min-width: 768px)';

/** True at Tailwind's `md` breakpoint and above -- the same breakpoint Nav.tsx already uses for
 * its own mobile/desktop split. */
export function useIsDesktop(): boolean {
  const [isDesktop, setIsDesktop] = useState(() => window.matchMedia(QUERY).matches);

  useEffect(() => {
    const mql = window.matchMedia(QUERY);
    const onChange = () => setIsDesktop(mql.matches);
    onChange();
    mql.addEventListener('change', onChange);
    return () => mql.removeEventListener('change', onChange);
  }, []);

  return isDesktop;
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/landing/hero/useIsDesktop.test.ts`
Expected: PASS (2 tests)

- [x] **Step 5: Commit**

```bash
cd frontend && git add src/pages/landing/hero/useIsDesktop.ts src/pages/landing/hero/useIsDesktop.test.ts
git commit -m "feat(landing/hero): add useIsDesktop breakpoint hook"
```

---

## Task 6: `AmbientScene.tsx` — the actual R3F particle field

**Files:**
- Create: `frontend/src/pages/landing/hero/AmbientScene.tsx`

**Interfaces:**
- Produces: `AmbientScene(): JSX.Element` (named export). Consumed only by Task 7 (`AmbientCanvas.tsx`), via a lazy `import()`.

No dedicated unit test for this file: it renders a real `@react-three/fiber` `<Canvas>`, which requires an actual WebGL context to do anything meaningful — jsdom has none, so a unit test here would either test nothing real or need to mock away everything the file does. Per the spec's Testing section, this falls outside the "structural/a11y assertions" scope; it's verified visually (browser preview) once Task 8 wires it in.

- [x] **Step 1: Write the implementation**

```typescript
// frontend/src/pages/landing/hero/AmbientScene.tsx
import { useMemo, useRef } from 'react';
import { Canvas, useFrame } from '@react-three/fiber';
import * as THREE from 'three';

const PARTICLE_COUNT = 220;

function ParticleField() {
  const pointsRef = useRef<THREE.Points>(null);

  const positions = useMemo(() => {
    const array = new Float32Array(PARTICLE_COUNT * 3);
    for (let i = 0; i < PARTICLE_COUNT; i++) {
      array[i * 3] = (Math.random() - 0.5) * 12;
      array[i * 3 + 1] = (Math.random() - 0.5) * 8;
      array[i * 3 + 2] = (Math.random() - 0.5) * 6;
    }
    return array;
  }, []);

  useFrame((state) => {
    if (!pointsRef.current) return;
    // Slow, ambient drift only -- this is depth-of-field decoration behind a financial dashboard,
    // not something meant to be watched.
    pointsRef.current.rotation.y = state.clock.elapsedTime * 0.02;
  });

  return (
    <points ref={pointsRef}>
      <bufferGeometry>
        <bufferAttribute attach="attributes-position" args={[positions, 3]} />
      </bufferGeometry>
      {/* #16A34A matches --m-success in index.css -- three.js can't consume a CSS custom
          property, so this hex has to be kept in sync with that token by hand. */}
      <pointsMaterial color="#16A34A" size={0.035} sizeAttenuation transparent opacity={0.55} />
    </points>
  );
}

/**
 * Ambient depth layer only -- never the dashboard itself (see the hero design spec's Non-goals).
 * Only ever reached via AmbientCanvas's React.lazy() boundary, so this file's three.js/
 * @react-three/fiber imports never land in the main landing-route bundle.
 */
export function AmbientScene() {
  return (
    <Canvas camera={{ position: [0, 0, 6], fov: 50 }} gl={{ alpha: true, antialias: true }}>
      <ParticleField />
    </Canvas>
  );
}
```

- [x] **Step 2: Verify it type-checks**

Run: `cd frontend && npx tsc -b`
Expected: no output (clean exit).

- [x] **Step 3: Commit**

```bash
cd frontend && git add src/pages/landing/hero/AmbientScene.tsx
git commit -m "feat(landing/hero): add ambient R3F particle scene"
```

---

## Task 7: `AmbientCanvas.tsx` — gating wrapper

**Files:**
- Create: `frontend/src/pages/landing/hero/AmbientCanvas.tsx`
- Test: `frontend/src/pages/landing/hero/AmbientCanvas.test.tsx`

**Interfaces:**
- Consumes: `isWebglAvailable` from `./webglSupport` (Task 4); `useIsDesktop` from `./useIsDesktop` (Task 5); `AmbientScene` from `./AmbientScene` (Task 6, via lazy import); `useReducedMotion` from `framer-motion`.
- Produces: `AmbientCanvas(): JSX.Element` (named export). Consumed by Task 12 (`Hero.tsx`).

**Environment note discovered during implementation:** framer-motion's real `useReducedMotion`
caches its result in a module-level singleton (`motion-dom`'s `prefersReducedMotion.current`,
resolved once via a `matchMedia` listener registered on first use per worker process).
Reassigning `window.matchMedia` per test via `mockMatchMedia`, as used elsewhere in this plan for
`useIsDesktop`, does **not** reliably control it once that singleton has already resolved once in
the current test worker. Every test below mocks `useReducedMotion` directly instead — this
supersedes the plan's original approach and is the pattern later tasks (10, 11, 12) also use.

- [x] **Step 1: Write the failing test**

```typescript
// frontend/src/pages/landing/hero/AmbientCanvas.test.tsx
import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('./webglSupport', () => ({ isWebglAvailable: vi.fn() }));
vi.mock('./useIsDesktop', () => ({ useIsDesktop: vi.fn() }));
vi.mock('./AmbientScene', () => ({
  AmbientScene: () => <div data-testid="ambient-scene-stub" />,
}));
vi.mock('framer-motion', async (importOriginal) => {
  const actual = await importOriginal<typeof import('framer-motion')>();
  return { ...actual, useReducedMotion: vi.fn() };
});

import { useReducedMotion } from 'framer-motion';
import { isWebglAvailable } from './webglSupport';
import { useIsDesktop } from './useIsDesktop';
import { AmbientCanvas } from './AmbientCanvas';

describe('AmbientCanvas', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('renders the ambient scene when desktop + WebGL + motion are all available', async () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    vi.mocked(isWebglAvailable).mockReturnValue(true);
    vi.mocked(useIsDesktop).mockReturnValue(true);

    render(<AmbientCanvas />);
    expect(await screen.findByTestId('ambient-scene-stub')).toBeInTheDocument();
  });

  it('renders a static gradient fallback, not the scene, when WebGL is unavailable', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    vi.mocked(isWebglAvailable).mockReturnValue(false);
    vi.mocked(useIsDesktop).mockReturnValue(true);

    render(<AmbientCanvas />);
    expect(screen.queryByTestId('ambient-scene-stub')).not.toBeInTheDocument();
    expect(screen.getByTestId('ambient-fallback-gradient')).toBeInTheDocument();
  });

  it('renders nothing on mobile, even with WebGL and motion available', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    vi.mocked(isWebglAvailable).mockReturnValue(true);
    vi.mocked(useIsDesktop).mockReturnValue(false);

    const { container } = render(<AmbientCanvas />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when the user prefers reduced motion, even with WebGL and desktop available', () => {
    vi.mocked(useReducedMotion).mockReturnValue(true);
    vi.mocked(isWebglAvailable).mockReturnValue(true);
    vi.mocked(useIsDesktop).mockReturnValue(true);

    const { container } = render(<AmbientCanvas />);
    expect(container).toBeEmptyDOMElement();
  });
});
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/landing/hero/AmbientCanvas.test.tsx`
Expected: FAIL — module `./AmbientCanvas` doesn't exist yet.

- [x] **Step 3: Write the implementation**

```typescript
// frontend/src/pages/landing/hero/AmbientCanvas.tsx
import { Suspense, lazy, useState } from 'react';
import { useReducedMotion } from 'framer-motion';
import { isWebglAvailable } from './webglSupport';
import { useIsDesktop } from './useIsDesktop';

const AmbientScene = lazy(() =>
  import('./AmbientScene').then((mod) => ({ default: mod.AmbientScene }))
);

/**
 * Ambient WebGL backdrop for the hero -- ONLY a particle/glow layer, never the dashboard itself
 * (see docs/superpowers/specs/2026-08-22-hero-cinematic-reveal-design.md's Non-goals). Gated
 * behind desktop + no-reduced-motion + real WebGL support, and code-split via React.lazy so the
 * three.js/@react-three/fiber bundle never loads when any gate fails, and never blocks the rest
 * of the hero either way.
 *
 * WebGL failing specifically (desktop, motion allowed, but no real GPU context) still gets a
 * static CSS gradient so the hero doesn't lose all ambient depth -- reduced-motion and mobile
 * render nothing at all, because the hero's own background already supplies enough surface there.
 */
export function AmbientCanvas() {
  const prefersReducedMotion = useReducedMotion();
  const isDesktop = useIsDesktop();
  const [webglOk] = useState(isWebglAvailable);

  if (prefersReducedMotion || !isDesktop) return null;

  if (!webglOk) {
    return (
      <div
        aria-hidden="true"
        data-testid="ambient-fallback-gradient"
        className="absolute inset-0 pointer-events-none"
        style={{
          background:
            'radial-gradient(60% 60% at 70% 30%, rgb(22 163 74 / .12), transparent 70%)',
        }}
      />
    );
  }

  return (
    <div className="absolute inset-0 pointer-events-none" aria-hidden="true">
      <Suspense fallback={null}>
        <AmbientScene />
      </Suspense>
    </div>
  );
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/landing/hero/AmbientCanvas.test.tsx`
Expected: PASS (4 tests)

- [x] **Step 5: Commit**

```bash
cd frontend && git add src/pages/landing/hero/AmbientCanvas.tsx src/pages/landing/hero/AmbientCanvas.test.tsx
git commit -m "feat(landing/hero): add AmbientCanvas gating wrapper"
```

---

## Task 8: `HealthScoreRing.tsx`

**Files:**
- Create: `frontend/src/pages/landing/hero/HealthScoreRing.tsx`
- Test: `frontend/src/pages/landing/hero/HealthScoreRing.test.tsx`

**Interfaces:**
- Consumes: `CountUp` from `../primitives` (existing); `heroScore` from `../landing-config` (Task 2).
- Produces: `HealthScoreRing(): JSX.Element` (named export). Consumed by Task 12 (`Hero.tsx`).

- [x] **Step 1: Write the failing test**

```typescript
// frontend/src/pages/landing/hero/HealthScoreRing.test.tsx
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { heroScore } from '../landing-config';
import { HealthScoreRing } from './HealthScoreRing';

describe('HealthScoreRing', () => {
  it('renders the score, label and delta from landing-config', () => {
    render(<HealthScoreRing />);
    expect(screen.getByText(String(heroScore.value))).toBeInTheDocument();
    expect(screen.getByText(heroScore.label)).toBeInTheDocument();
    // heroScore.delta ("+6 this month") starts with a regex-special "+" -- match the literal
    // rendered text ("↑ +6 this month") instead of building a RegExp from it.
    expect(screen.getByText(`↑ ${heroScore.delta}`)).toBeInTheDocument();
  });

  it('exposes the score as an accessible label on the ring itself', () => {
    render(<HealthScoreRing />);
    expect(
      screen.getByRole('img', { name: `Financial health score ${heroScore.value} out of 100` })
    ).toBeInTheDocument();
  });
});
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/landing/hero/HealthScoreRing.test.tsx`
Expected: FAIL — module `./HealthScoreRing` doesn't exist yet.

- [x] **Step 3: Write the implementation**

```typescript
// frontend/src/pages/landing/hero/HealthScoreRing.tsx
import { useEffect, useRef, useState } from 'react';
import { CountUp } from '../primitives';
import { heroScore } from '../landing-config';

const RADIUS = 54;
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;

/**
 * Circular score dial. Mirrors CountUp's own contract (see primitives.tsx): starts already at
 * the final ring position, so a browser without IntersectionObserver -- or a test -- shows the
 * real score rather than a permanently empty ring, and only animates the draw once the ring is
 * actually scrolled into view (and the visitor hasn't asked for reduced motion).
 */
export function HealthScoreRing() {
  const ref = useRef<SVGSVGElement | null>(null);
  const [drawn, setDrawn] = useState(true);

  useEffect(() => {
    const node = ref.current;
    if (!node || typeof IntersectionObserver === 'undefined') return;
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return;

    setDrawn(false);
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (!entry.isIntersecting) return;
        observer.disconnect();
        // Two frames so the browser paints the 0% state before transitioning -- a same-frame
        // change to a CSS-transitioned property doesn't transition at all.
        requestAnimationFrame(() => requestAnimationFrame(() => setDrawn(true)));
      },
      { threshold: 0.4 }
    );
    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  const target = (heroScore.value / 100) * CIRCUMFERENCE;
  const dash = drawn ? target : 0;

  return (
    <div className="relative inline-flex flex-col items-center">
      <svg
        ref={ref}
        viewBox="0 0 140 140"
        className="w-[132px] h-[132px]"
        role="img"
        aria-label={`Financial health score ${heroScore.value} out of 100`}
      >
        <circle cx="70" cy="70" r={RADIUS} fill="none" stroke="rgba(255,255,255,0.12)" strokeWidth="10" />
        <circle
          cx="70"
          cy="70"
          r={RADIUS}
          fill="none"
          stroke="var(--m-success)"
          strokeWidth="10"
          strokeLinecap="round"
          strokeDasharray={`${dash} ${CIRCUMFERENCE - dash}`}
          transform="rotate(-90 70 70)"
          style={{
            transition: 'stroke-dasharray 1200ms cubic-bezier(0.16,1,0.3,1)',
            filter: 'drop-shadow(0 0 8px rgb(22 163 74 / .6))',
          }}
        />
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center">
        <span className="text-3xl font-bold text-white">
          <CountUp value={heroScore.value} />
        </span>
        <span className="text-[10px] uppercase tracking-wide text-white/60">{heroScore.label}</span>
      </div>
      <p className="mt-2 text-xs" style={{ color: 'var(--m-success)' }}>
        ↑ {heroScore.delta}
      </p>
    </div>
  );
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/landing/hero/HealthScoreRing.test.tsx`
Expected: PASS (2 tests)

- [x] **Step 5: Commit**

```bash
cd frontend && git add src/pages/landing/hero/HealthScoreRing.tsx src/pages/landing/hero/HealthScoreRing.test.tsx
git commit -m "feat(landing/hero): add HealthScoreRing"
```

---

## Task 9: `IntelligenceScan.tsx`

**Files:**
- Create: `frontend/src/pages/landing/hero/IntelligenceScan.tsx`
- Test: `frontend/src/pages/landing/hero/IntelligenceScan.test.tsx`

**Interfaces:**
- Consumes: `useStagedReveal` from `../primitives` (existing); `heroIntelligence` from `../landing-config` (Task 2).
- Produces: `IntelligenceScan(): JSX.Element` (named export). Consumed by Task 12 (`Hero.tsx`).

- [x] **Step 1: Write the failing test**

```typescript
// frontend/src/pages/landing/hero/IntelligenceScan.test.tsx
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { heroIntelligence } from '../landing-config';
import { IntelligenceScan } from './IntelligenceScan';

describe('IntelligenceScan', () => {
  it('renders the heading and every step, regardless of reveal progress', () => {
    // useStagedReveal only changes opacity/transform on each item -- it never removes them from
    // the DOM (same convention DashboardMock's own progressive panels use), so every step is
    // queryable immediately even before the observer fires.
    render(<IntelligenceScan />);
    expect(screen.getByText(heroIntelligence.heading)).toBeInTheDocument();
    heroIntelligence.steps.forEach((step) => {
      expect(screen.getByText(step)).toBeInTheDocument();
    });
  });
});
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/landing/hero/IntelligenceScan.test.tsx`
Expected: FAIL — module `./IntelligenceScan` doesn't exist yet.

- [x] **Step 3: Write the implementation**

```typescript
// frontend/src/pages/landing/hero/IntelligenceScan.tsx
import { useStagedReveal } from '../primitives';
import { heroIntelligence } from '../landing-config';

/**
 * The "Analyzing your finances…" checklist. Built on the existing useStagedReveal primitive --
 * same jsdom/reduced-motion/no-IntersectionObserver fallback behavior as the rest of the page,
 * not reimplemented with Framer Motion.
 */
export function IntelligenceScan() {
  const { ref, step } = useStagedReveal(heroIntelligence.steps.length, 550);

  return (
    <div
      ref={ref}
      className="rounded-2xl border border-white/10 bg-white/5 backdrop-blur-md px-5 py-4 w-full max-w-xs"
    >
      <p className="text-xs font-medium text-white/70 mb-3">{heroIntelligence.heading}</p>
      <ul className="space-y-2">
        {heroIntelligence.steps.map((label, i) => {
          const revealed = step > i;
          return (
            <li
              key={label}
              className="flex items-center gap-2 text-xs text-white/90"
              style={{
                opacity: revealed ? 1 : 0.25,
                transform: revealed ? 'none' : 'translateX(-6px)',
                transition: 'opacity 360ms ease, transform 360ms ease',
              }}
            >
              <span
                aria-hidden="true"
                className="flex h-4 w-4 shrink-0 items-center justify-center rounded-full text-[10px]"
                style={{
                  background: revealed ? 'var(--m-success)' : 'rgba(255,255,255,0.15)',
                  color: revealed ? '#fff' : 'transparent',
                  transition: 'background 360ms ease',
                }}
              >
                ✓
              </span>
              {label}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/landing/hero/IntelligenceScan.test.tsx`
Expected: PASS (1 test)

- [x] **Step 5: Commit**

```bash
cd frontend && git add src/pages/landing/hero/IntelligenceScan.tsx src/pages/landing/hero/IntelligenceScan.test.tsx
git commit -m "feat(landing/hero): add IntelligenceScan checklist"
```

---

## Task 10: `FloatingBadges.tsx`

**Files:**
- Create: `frontend/src/pages/landing/hero/FloatingBadges.tsx`
- Test: `frontend/src/pages/landing/hero/FloatingBadges.test.tsx`

**Interfaces:**
- Consumes: `heroBadges` from `../landing-config` (Task 2); `motion`, `useReducedMotion` from `framer-motion`.
- Produces: `FloatingBadges(): JSX.Element` (named export). Consumed by Task 12 (`Hero.tsx`).

- [x] **Step 1: Write the failing test**

Per Task 7's note: `useReducedMotion` is mocked directly, not via `mockMatchMedia`.

```typescript
// frontend/src/pages/landing/hero/FloatingBadges.test.tsx
import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('framer-motion', async (importOriginal) => {
  const actual = await importOriginal<typeof import('framer-motion')>();
  return { ...actual, useReducedMotion: vi.fn() };
});

import { useReducedMotion } from 'framer-motion';
import { heroBadges } from '../landing-config';
import { FloatingBadges } from './FloatingBadges';

describe('FloatingBadges', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('renders every badge label from landing-config', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<FloatingBadges />);
    heroBadges.forEach((badge) => {
      expect(screen.getByText(badge.label)).toBeInTheDocument();
    });
  });

  it('still renders every badge label under prefers-reduced-motion', () => {
    vi.mocked(useReducedMotion).mockReturnValue(true);
    render(<FloatingBadges />);
    heroBadges.forEach((badge) => {
      expect(screen.getByText(badge.label)).toBeInTheDocument();
    });
  });
});
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/landing/hero/FloatingBadges.test.tsx`
Expected: FAIL — module `./FloatingBadges` doesn't exist yet.

- [x] **Step 3: Write the implementation**

```typescript
// frontend/src/pages/landing/hero/FloatingBadges.tsx
import { motion, useReducedMotion } from 'framer-motion';
import { heroBadges } from '../landing-config';

const POSITIONS = [
  { top: '8%', left: '-6%', hideOnMobile: false },
  { top: '68%', left: '-4%', hideOnMobile: true },
  { top: '4%', left: '78%', hideOnMobile: false },
  { top: '46%', left: '86%', hideOnMobile: true },
  { top: '82%', left: '70%', hideOnMobile: false },
] as const;

// Fixed, not Math.random() -- deterministic delays keep screenshots and visual-regression runs
// stable and avoid hydration mismatches. See the hero design spec's Global Constraints.
const DELAYS_S = [0, 1.2, 2.4, 3.6, 0.6] as const;

/** The small floating data pills around the dashboard preview. */
export function FloatingBadges() {
  const prefersReducedMotion = useReducedMotion();

  return (
    <>
      {heroBadges.map((badge, i) => {
        const position = POSITIONS[i % POSITIONS.length];
        const delay = DELAYS_S[i % DELAYS_S.length];
        return (
          <motion.div
            key={badge.label}
            className={`absolute rounded-full border border-white/15 bg-white/10 backdrop-blur-md px-3 py-1.5 text-[11px] text-white/85 whitespace-nowrap pointer-events-none ${
              position.hideOnMobile ? 'hidden md:block' : ''
            }`}
            style={{ top: position.top, left: position.left }}
            initial={prefersReducedMotion ? false : { opacity: 0, y: 12 }}
            animate={
              prefersReducedMotion
                ? { opacity: 1, y: 0 }
                : { opacity: [0, 1, 1, 0.9], y: [12, 0, -6, 0] }
            }
            transition={
              prefersReducedMotion
                ? { duration: 0 }
                : { duration: 6, repeat: Infinity, repeatType: 'mirror', delay, ease: 'easeInOut' }
            }
          >
            {badge.label}
          </motion.div>
        );
      })}
    </>
  );
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/landing/hero/FloatingBadges.test.tsx`
Expected: PASS (2 tests)

- [x] **Step 5: Commit**

```bash
cd frontend && git add src/pages/landing/hero/FloatingBadges.tsx src/pages/landing/hero/FloatingBadges.test.tsx
git commit -m "feat(landing/hero): add FloatingBadges"
```

---

## Task 11: `FloatingDashboardCard.tsx`

**Files:**
- Create: `frontend/src/pages/landing/hero/FloatingDashboardCard.tsx`
- Test: `frontend/src/pages/landing/hero/FloatingDashboardCard.test.tsx`

**Interfaces:**
- Consumes: `DashboardMock` from `../DashboardMock` (existing); `useIsDesktop` from `./useIsDesktop` (Task 5); `motion`, `useReducedMotion`, `useSpring` from `framer-motion`.
- Produces: `FloatingDashboardCard(): JSX.Element` (named export). Consumed by Task 12 (`Hero.tsx`).

- [x] **Step 1: Write the failing test**

Per Task 7's note: `useReducedMotion` is mocked directly, not via `mockMatchMedia`. The mobile
case still uses `mockMatchMedia` because `useIsDesktop` reads `window.matchMedia` directly on
every call, with no framer-motion singleton involved — that one is unaffected by the Task 7
discovery.

```typescript
// frontend/src/pages/landing/hero/FloatingDashboardCard.test.tsx
import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { mockMatchMedia } from '../../../test/mockMatchMedia';

vi.mock('framer-motion', async (importOriginal) => {
  const actual = await importOriginal<typeof import('framer-motion')>();
  return { ...actual, useReducedMotion: vi.fn() };
});

import { useReducedMotion } from 'framer-motion';
import { FloatingDashboardCard } from './FloatingDashboardCard';

describe('FloatingDashboardCard', () => {
  let restore: (() => void) | undefined;

  afterEach(() => {
    restore?.();
    restore = undefined;
    vi.clearAllMocks();
  });

  it('renders the real DashboardMock content, not a placeholder', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<FloatingDashboardCard />);
    // DashboardMock's own aria-label for level="simple" -- see DashboardMock.tsx's LEVEL_LABEL.
    expect(
      screen.getByRole('img', {
        name: /The Finora dashboard showing income, expenses, savings and cash flow/,
      })
    ).toBeInTheDocument();
  });

  it('renders the same dashboard content under prefers-reduced-motion', () => {
    vi.mocked(useReducedMotion).mockReturnValue(true);
    render(<FloatingDashboardCard />);
    expect(
      screen.getByRole('img', {
        name: /The Finora dashboard showing income, expenses, savings and cash flow/,
      })
    ).toBeInTheDocument();
  });

  it('renders the same dashboard content below the desktop breakpoint (mobile fallback path)', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    restore = mockMatchMedia({ '(min-width: 768px)': false });
    render(<FloatingDashboardCard />);
    expect(
      screen.getByRole('img', {
        name: /The Finora dashboard showing income, expenses, savings and cash flow/,
      })
    ).toBeInTheDocument();
  });
});
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/landing/hero/FloatingDashboardCard.test.tsx`
Expected: FAIL — module `./FloatingDashboardCard` doesn't exist yet.

- [x] **Step 3: Write the implementation**

```typescript
// frontend/src/pages/landing/hero/FloatingDashboardCard.tsx
import { useEffect, useRef, type PointerEvent } from 'react';
import { motion, useReducedMotion, useSpring } from 'framer-motion';
import { DashboardMock } from '../DashboardMock';
import { useIsDesktop } from './useIsDesktop';

const TILT_RANGE = 10; // degrees of live mouse-driven tilt in either direction
const REST_ROTATE_X = 8; // initial entrance tilt, per the hero design spec
const REST_ROTATE_Y = -5;

/**
 * Wraps the real DashboardMock in a CSS-3D glass shell. The dashboard itself never enters WebGL
 * -- see the hero design spec's Non-goals -- so this is the entire "3D" effect: real DOM, a
 * perspective transform, and spring-smoothed mouse-driven tilt on top of an initial settle from
 * (8deg, -5deg) down to level.
 *
 * `use3D` gates BOTH the tilt and the richer entrance (scale+blur) behind desktop-and-motion-ok --
 * per the hero design spec's mobile fallback, touch/mobile gets the plain fade+translateY entrance
 * (matching primitives.tsx's own Reveal motion budget) and no rotateX/Y settle at all, not a
 * lighter version of the 3D one.
 */
export function FloatingDashboardCard() {
  const prefersReducedMotion = useReducedMotion();
  const isDesktop = useIsDesktop();
  const use3D = isDesktop && !prefersReducedMotion;
  const containerRef = useRef<HTMLDivElement | null>(null);

  const rotateX = useSpring(use3D ? REST_ROTATE_X : 0, { stiffness: 120, damping: 20 });
  const rotateY = useSpring(use3D ? REST_ROTATE_Y : 0, { stiffness: 120, damping: 20 });

  useEffect(() => {
    if (!use3D) return;
    rotateX.set(0);
    rotateY.set(0);
  }, [use3D, rotateX, rotateY]);

  function handlePointerMove(event: PointerEvent<HTMLDivElement>) {
    if (!use3D) return;
    const rect = containerRef.current?.getBoundingClientRect();
    if (!rect) return;
    const px = (event.clientX - rect.left) / rect.width - 0.5;
    const py = (event.clientY - rect.top) / rect.height - 0.5;
    rotateY.set(px * TILT_RANGE);
    rotateX.set(-py * TILT_RANGE);
  }

  function handlePointerLeave() {
    if (!use3D) return;
    rotateX.set(0);
    rotateY.set(0);
  }

  return (
    <motion.div
      ref={containerRef}
      onPointerMove={handlePointerMove}
      onPointerLeave={handlePointerLeave}
      initial={
        prefersReducedMotion
          ? false
          : use3D
            ? { opacity: 0, y: 80, scale: 0.95, filter: 'blur(12px)' }
            : { opacity: 0, y: 14 }
      }
      animate={use3D ? { opacity: 1, y: 0, scale: 1, filter: 'blur(0px)' } : { opacity: 1, y: 0 }}
      transition={use3D ? { duration: 0.9, ease: [0.16, 1, 0.3, 1] } : { duration: 0.42, ease: 'easeOut' }}
      style={{ rotateX, rotateY, transformPerspective: 1200, transformStyle: 'preserve-3d' }}
      className="relative"
    >
      <div className="rounded-[24px] p-1 backdrop-blur-xl bg-white/10 border border-white/15 shadow-[0_40px_100px_-32px_rgba(0,0,0,0.6)]">
        <DashboardMock level="simple" progressive />
      </div>
    </motion.div>
  );
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/landing/hero/FloatingDashboardCard.test.tsx`
Expected: PASS (3 tests)

- [x] **Step 5: Commit**

```bash
cd frontend && git add src/pages/landing/hero/FloatingDashboardCard.tsx src/pages/landing/hero/FloatingDashboardCard.test.tsx
git commit -m "feat(landing/hero): add FloatingDashboardCard with CSS-3D tilt"
```

---

## Task 12: Rewrite `Hero.tsx` to orchestrate the sequence

**Files:**
- Modify: `frontend/src/pages/landing/Hero.tsx`
- Create: `frontend/src/pages/landing/Hero.test.tsx`

**Interfaces:**
- Consumes: `hero` from `./landing-config` (existing); `AmbientCanvas` (Task 7), `HealthScoreRing` (Task 8), `IntelligenceScan` (Task 9), `FloatingBadges` (Task 10), `FloatingDashboardCard` (Task 11); `motion`, `useReducedMotion` from `framer-motion`.
- Produces: `Hero(): JSX.Element` (named export, same signature as before). Consumed by `Landing.tsx` (Task 13) — no signature change, so that file needs no import changes, only the Transition band adjustment.

Per Task 7's note: `useReducedMotion` is mocked directly, not via `mockMatchMedia`.

- [x] **Step 1: Write the failing test**

```typescript
// frontend/src/pages/landing/Hero.test.tsx
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('framer-motion', async (importOriginal) => {
  const actual = await importOriginal<typeof import('framer-motion')>();
  return { ...actual, useReducedMotion: vi.fn() };
});

import { useReducedMotion } from 'framer-motion';
import { hero, heroScore, heroIntelligence } from './landing-config';
import { Hero } from './Hero';

function renderHero() {
  return render(
    <MemoryRouter>
      <Hero />
    </MemoryRouter>
  );
}

describe('Hero', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('renders the existing headline, blurb and CTAs unchanged', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    renderHero();
    expect(screen.getByText(hero.headline)).toBeInTheDocument();
    expect(screen.getByText(hero.headlineAccent)).toBeInTheDocument();
    expect(screen.getByText(hero.blurb)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: new RegExp(hero.primaryCta) })).toBeInTheDocument();
  });

  it('renders the score ring and intelligence scan', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    renderHero();
    expect(screen.getByText(String(heroScore.value))).toBeInTheDocument();
    expect(screen.getByText(heroIntelligence.heading)).toBeInTheDocument();
  });

  it('under prefers-reduced-motion, renders the same final content and never mounts AmbientCanvas', () => {
    vi.mocked(useReducedMotion).mockReturnValue(true);
    const { container } = renderHero();

    // Content is still all there -- reduced motion changes HOW it appears, never WHAT appears.
    expect(screen.getByText(hero.headline)).toBeInTheDocument();
    expect(screen.getByText(String(heroScore.value))).toBeInTheDocument();

    // AmbientCanvas's own gate returns null under reduced motion (see AmbientCanvas.test.tsx);
    // this asserts that behavior actually reaches the page, not just the unit in isolation --
    // jsdom has no real WebGL either way, but the canvas element itself would still appear if the
    // gate were bypassed.
    expect(container.querySelector('canvas')).not.toBeInTheDocument();
  });
});
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/landing/Hero.test.tsx`
Expected: FAIL — current `Hero.tsx` doesn't render `heroScore`/`heroIntelligence` content yet.

- [x] **Step 3: Rewrite Hero.tsx**

**Critical discovery made implementing this step, verified in a real browser (not just jsdom):**
the original draft below used a shared `container`/`item` Framer Motion `Variants` pair, with the
outer `motion.div` setting `animate="show"` and every descendant `motion.div` relying on
context-based variant propagation (no own `initial`/`animate`) to inherit that "show" state --
the standard documented Framer Motion pattern for `staggerChildren`. Under this app's
`React.StrictMode` (`main.tsx`), that propagation never completed: every descendant stayed
permanently frozen at its `hidden` state (`opacity: 0`, confirmed via each element's `style`
attribute in a real Chrome tab), even though `useReducedMotion()` correctly returned `false` and
the outer container itself received no style overrides at all. The content was fully present in
the DOM (confirmed via `get_page_text`/`CountUp` mid-animation) — it was invisible, not missing —
which is why this needs calling out explicitly rather than trusting the component-level jsdom
tests alone: none of the earlier per-component tests (Tasks 7-11) exercise this integration point.

The fix: give every `motion.div` its own explicit `initial`/`animate`/`transition`, and replace
`staggerChildren` with a fixed per-instance `delay` (`reveal(delay)` below) to recreate the same
staggered sequencing without relying on propagation at all. Replace the full contents of
`frontend/src/pages/landing/Hero.tsx` with this (not the originally-planned `container`/`item`
version):

```typescript
import { Link } from 'react-router-dom';
import { ArrowRight, Check } from 'lucide-react';
import { motion, useReducedMotion } from 'framer-motion';
import { hero } from './landing-config';
import { AmbientCanvas } from './hero/AmbientCanvas';
import { FloatingDashboardCard } from './hero/FloatingDashboardCard';
import { HealthScoreRing } from './hero/HealthScoreRing';
import { IntelligenceScan } from './hero/IntelligenceScan';
import { FloatingBadges } from './hero/FloatingBadges';

const EASE = [0.16, 1, 0.3, 1] as const;

/**
 * Cinematic reveal for the dark hero band. See
 * docs/superpowers/specs/2026-08-22-hero-cinematic-reveal-design.md -- the mount sequence
 * ("background/particles -> content -> dashboard -> score/insights") is staggered via a fixed
 * per-section `delay` on each motion.div's own `animate`, not via Framer Motion's
 * staggerChildren/variant-propagation mechanism: that mechanism relies on child motion
 * components inheriting their parent's `animate` label through React context, which -- verified
 * against a real browser during implementation, not just jsdom -- got stuck permanently at each
 * child's `initial` state under this app's React.StrictMode in main.tsx (children never reached
 * "show"). Explicit per-instance `initial`/`animate`/`transition` sidesteps that failure mode
 * entirely and is what every sub-component below (FloatingDashboardCard, FloatingBadges) already
 * does for the same reason.
 *
 * The ambient WebGL layer, the score ring and the intelligence-scan checklist are separate
 * components reusing (not replacing) the existing Reveal/CountUp/useStagedReveal primitives. The
 * dashboard preview itself is always real DOM -- never rendered inside WebGL -- so it stays crisp,
 * accessible and never depends on animation state to be understood.
 *
 * Copy lives in ./landing-config, unchanged from before this rewrite -- this file decides how the
 * hero looks, not what it says. See landing-config.ts's own note on the claim-review discipline
 * that applies to every sentence here.
 *
 * The fade from this section's dark background into white belongs to Landing.tsx's <Transition>
 * band immediately after <Hero />, like every other section boundary on this page -- Hero does
 * NOT own its own exit fade. See Task 13's note on why that's a deliberate correction from an
 * earlier draft of this plan, not an oversight.
 */
export function Hero() {
  const prefersReducedMotion = useReducedMotion();

  function reveal(delay: number) {
    return prefersReducedMotion
      ? { initial: false as const, animate: { opacity: 1, y: 0 }, transition: { duration: 0 } }
      : {
          initial: { opacity: 0, y: 24 },
          animate: { opacity: 1, y: 0 },
          transition: { duration: 0.6, ease: EASE, delay },
        };
  }

  return (
    <section
      className="relative overflow-hidden"
      style={{
        background:
          'radial-gradient(120% 100% at 50% -10%, #16202E 0%, #0B1220 55%, #05070C 100%)',
      }}
    >
      <AmbientCanvas />

      <div className="relative z-10 max-w-6xl mx-auto px-5 sm:px-6 pt-28 pb-24 lg:pt-36 lg:pb-32">
        <div className="grid lg:grid-cols-[minmax(0,1fr)_minmax(0,1.15fr)] gap-14 items-center">
          <motion.div {...reveal(0)}>
            <h1 className="m-display mb-5" style={{ color: '#F8FAFC' }}>
              {hero.headline}
              <br />
              <span style={{ color: 'var(--m-success)' }}>{hero.headlineAccent}</span>
            </h1>
            <p className="m-lead mb-8 max-w-lg" style={{ color: '#94A3B8' }}>
              {hero.blurb}
            </p>
            <div className="flex flex-col sm:flex-row gap-3 mb-8">
              <Link to="/register" className="m-btn m-btn-primary w-full sm:w-auto">
                {hero.primaryCta} <ArrowRight size={16} />
              </Link>
              <a
                href="#how"
                className="m-btn m-btn-ghost w-full sm:w-auto"
                style={{ background: 'transparent', color: '#F8FAFC', borderColor: 'rgba(255,255,255,0.25)' }}
              >
                {hero.secondaryCta}
              </a>
            </div>
            <ul className="grid sm:grid-cols-2 gap-x-6 gap-y-2.5">
              {hero.assurances.map((t) => (
                <li key={t} className="flex items-center gap-2 text-sm" style={{ color: '#94A3B8' }}>
                  <Check size={15} className="shrink-0" style={{ color: 'var(--m-success)' }} />
                  {t}
                </li>
              ))}
            </ul>

            <motion.div {...reveal(0.5)} className="mt-10 flex flex-wrap items-center gap-6">
              <HealthScoreRing />
              <IntelligenceScan />
            </motion.div>
          </motion.div>

          <motion.div {...reveal(0.25)} className="relative">
            <FloatingDashboardCard />
            <FloatingBadges />
          </motion.div>
        </div>
      </div>
    </section>
  );
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/landing/Hero.test.tsx`
Expected: PASS (3 tests)

- [x] **Step 5: Run the full frontend test suite to check for regressions**

Run: `cd frontend && npx vitest run`
Expected: all tests pass, including `landing-claims.test.tsx` (copy is unchanged) and the rest of the `src/pages/landing/` suite.

- [x] **Step 6: Commit**

```bash
cd frontend && git add src/pages/landing/Hero.tsx src/pages/landing/Hero.test.tsx
git commit -m "feat(landing): rewrite Hero as a cinematic Framer Motion reveal"
```

---

## Task 13: Update Landing.tsx transition band and the index.css dark-band comment

**Files:**
- Modify: `frontend/src/pages/Landing.tsx:88` (the `<Transition>` immediately after `<Hero />`)
- Modify: `frontend/src/index.css:175-177` (the "two deliberate dark bands" comment)

**Interfaces:** none — this task only touches composition wiring and a documentation comment; no new exports.

This task has no isolated unit test of its own: `<Transition>` is a pure presentational gradient band with no logic branches of its own (see `primitives.tsx`), and there's no dedicated test for any of its 14 other uses on this page either. What this task changes is checked by re-running the existing suite (Step 2) and by visual verification (Step 3).

**Design note — transition ownership stays centralized in `<Transition>`, not Hero:** an earlier draft of this plan had `Hero.tsx` render its own bottom-edge fade div and reduced this band to a `WHITE`-to-`WHITE` no-op. That split ownership across two files for one visual seam and broke the pattern every other section boundary on this page already follows — Landing.tsx's own comment calls the `<Transition>` bands "why this reads as one page rather than fourteen." The fix is simpler than the original draft: `<Transition>` already renders a CSS gradient (`from`/`to`), so pointing `from` at Hero's own terminal background color does the identical visual fade without Hero needing to own any of it.

- [x] **Step 1: Update the Transition band after Hero**

In `frontend/src/pages/Landing.tsx`, change:

```tsx
        <Hero />
        {/* Hero already fades toward #FBFCFE, so this picks up close to where it ends. */}
        <Transition from="#FBFCFE" to={WHITE} height={48} />
```

to:

```tsx
        <Hero />
        {/* Hero's dark radial-gradient background bottoms out at #05070C (see Hero.tsx) -- this
            bridges that into white, the same way every other section boundary on this page does.
            Hero does not own any of its own exit fade; this band is the single place that does. */}
        <Transition from="#05070C" to={WHITE} height={80} />
```

- [x] **Step 2: Update the index.css dark-band comment**

In `frontend/src/index.css`, change:

```css
   Light-only, on purpose. The page is art-directed around two deliberate dark bands; if the whole
   page could also go dark, those bands stop reading as intentional. The app's own light/dark
   toggle is untouched -- this scope simply doesn't participate.
```

to:

```css
   Light-only, on purpose. The page is art-directed around three deliberate dark surfaces --
   Hero (the opening cinematic reveal), LearningSection and Trust -- bracketed by a brand-colored
   final CTA; if the whole page could also go dark, none of those would read as intentional
   anymore. The principle is "dark surfaces are intentional and limited," not "exactly two" --
   Hero was added as a third in the 2026-08-22 cinematic reveal (see
   docs/superpowers/specs/2026-08-22-hero-cinematic-reveal-design.md) and the count changed on
   purpose. The app's own light/dark toggle is untouched -- this scope simply doesn't participate.
```

- [x] **Step 3: Run the full frontend test suite**

Run: `cd frontend && npx vitest run`
Expected: all tests pass (no test asserts on the removed `#FBFCFE` string or the old comment text).

- [x] **Step 4: Visual verification in the browser**

Start the dev server for this worktree's `frontend` (see the note on launch.json below — the shared `.claude/launch.json` `--prefix frontend` resolves against the primary checkout, not this worktree; either add a worktree-local `.claude/launch.json` pointing `--prefix` at this worktree's `frontend` directory, or run `npx vite --port <free-port> --strictPort` directly from this worktree's `frontend/` and open that port).

Check, at both desktop and mobile (375px) viewport widths:
- Hero renders with the dark cinematic background, score ring counting up, intelligence checklist revealing, floating badges, and the dashboard preview tilting slightly on mouse move (desktop only).
- The transition from Hero into Problem is a smooth fade, not a hard color seam.
- No console errors (particularly none from `@react-three/fiber`/`three`).
- With the OS/browser "reduce motion" setting on (or via `prefers-reduced-motion` emulation in devtools), the hero renders its final state immediately with no ambient canvas and no floating-badge loops.

- [x] **Step 5: Commit**

```bash
cd frontend && git add src/pages/Landing.tsx src/index.css
git commit -m "docs(landing): update dark-band rationale and hero transition band"
```

---

## Task 14: Verify bundle splitting and payload

**Files:** none modified — this task only builds and inspects the output.

**Interfaces:** none.

- [x] **Step 1: Build the frontend**

Run: `cd frontend && npm run build`
Expected: build succeeds.

- [x] **Step 2: Inspect the chunk output**

Run: `ls -la frontend/dist/assets/*.js` (or read Vite's own build summary printed to stdout, which lists each output chunk with its size).

Expected: a separate JS chunk exists containing `three`/`@react-three/fiber` (its name will include a hash, e.g. `AmbientScene-<hash>.js` or similar, depending on Vite's chunk naming) — distinct from the main landing-page/index chunk. If `three`/`@react-three/fiber` show up inside the main entry chunk instead of their own chunk, the `React.lazy(() => import('./AmbientScene'))` boundary in `AmbientCanvas.tsx` (Task 7) isn't being respected — check that the import is a dynamic `import()` call, not a static top-level import, and that nothing else in the app imports `AmbientScene.tsx` statically.

- [x] **Step 3: Confirm the main chunk doesn't reference three.js symbols**

Run: `grep -l "THREE\." frontend/dist/assets/*.js | grep -v -i ambient`
Expected: no output (empty) — if the main chunk shows up in this grep, three.js leaked into it.

This task ends the plan with no commit of its own — it's a verification gate on work already committed in Tasks 1-13.
