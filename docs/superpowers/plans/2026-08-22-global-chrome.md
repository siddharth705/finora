# Global Chrome Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Nav transparent-over-Hero / glass-past-Hero, and give the site's 6 CTA buttons a subtle sitewide magnetic-hover effect, without touching Hero's own visuals or extending motion anywhere else.

**Architecture:** `Landing.tsx` wraps `<Hero />` in a ref'd `<div>` and drives an `overHero` boolean via `IntersectionObserver`, passed to `Nav`, which switches its own look with a plain CSS transition (no Framer Motion). A new `useMagnetic()` hook (Framer Motion `useSpring`, gated by `useIsDesktop`) powers `MagneticLink`/`MagneticAnchor` — `motion.create(Link)`/`motion.create('a')` drop-in replacements with zero extra DOM — swapped in at all 6 existing CTA call sites.

**Tech Stack:** React 18, TypeScript, Framer Motion 13.1.1 (`motion.create()`), react-router-dom 7.18.2, Vitest + Testing Library.

**Spec:** `docs/superpowers/specs/2026-08-22-global-chrome-design.md`

## Global Constraints

- Magnetic scope is exactly 6 CTA sites: `Nav.tsx` ("Get started"), `Hero.tsx` (primary `Link` + secondary `<a href="#how">`), `Pricing.tsx` ("Start free"), `FinalCta.tsx` ("Start free"), `Landing.tsx` (mobile sticky "Import your first statement"). No other links get magnetic behavior — not "Log in", not section anchors, not the mobile menu button.
- `useMagnetic()` physics: `maxDistance: 8px`, `stiffness: 140`, `damping: 20`, `mass: 0.3`.
- `useMagnetic()` is inert (no transform, no spring) under `prefers-reduced-motion`, below the desktop breakpoint, and under `(pointer: coarse)`.
- Nav's transparent/glass crossfade is CSS-only (`transition-all`, ~300ms) — never Framer Motion.
- No living-background/particle extension beyond Hero. No GSAP/Lenis. No sub-projects 3-4 in this plan.
- `MagneticLink`/`MagneticAnchor` introduce zero extra DOM nodes and must render as a real, focusable `<a>` preserving the exact `className` passed at each call site.
- Framer Motion component-wrapping uses `motion.create()` (confirmed available and non-deprecated on the installed `framer-motion@13.1.1`), not the older `motion(Component)` call form.
- Reduced-motion state in tests must be controlled via `vi.mock('framer-motion', ...)` + `vi.mocked(useReducedMotion).mockReturnValue(...)`, not `mockMatchMedia` alone (framer-motion caches `useReducedMotion` in a module-level singleton — see the Hero plan's Task 7 note).

---

### Task 1: Move `useIsDesktop` and add `pointer: coarse` gating

**Files:**
- Create: `frontend/src/pages/landing/hooks/useIsDesktop.ts`
- Delete: `frontend/src/pages/landing/hero/useIsDesktop.ts`
- Modify: `frontend/src/pages/landing/hero/FloatingDashboardCard.tsx` (import path)
- Modify: `frontend/src/pages/landing/hero/AmbientCanvas.tsx` (import path, if it imports `useIsDesktop`)
- Test: `frontend/src/pages/landing/hooks/useIsDesktop.test.ts` (new, replacing any existing hero-scoped test)

**Interfaces:**
- Produces: `useIsDesktop(): boolean` — `true` only when the viewport is at least `768px` wide AND the primary pointer is fine (not `pointer: coarse`). Same exported name and zero-arg signature as before, so every existing call site (`FloatingDashboardCard`, `AmbientCanvas`) keeps working via the import-path change alone.

- [ ] **Step 1: Check for an existing test file to migrate**

Run: `find frontend/src/pages/landing/hero -iname "useIsDesktop*"`

If a test file exists alongside the hook, its content will be moved (not rewritten) in Step 3.

- [ ] **Step 2: Write the failing test at the new location**

Create `frontend/src/pages/landing/hooks/useIsDesktop.test.ts`:

```ts
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

  it('is true at desktop width with a fine pointer', () => {
    restore = mockMatchMedia({
      '(min-width: 768px)': true,
      '(pointer: coarse)': false,
    });
    const { result } = renderHook(() => useIsDesktop());
    expect(result.current).toBe(true);
  });

  it('is false below the desktop breakpoint', () => {
    restore = mockMatchMedia({
      '(min-width: 768px)': false,
      '(pointer: coarse)': false,
    });
    const { result } = renderHook(() => useIsDesktop());
    expect(result.current).toBe(false);
  });

  it('is false on a wide viewport with a coarse pointer (large touch tablet)', () => {
    restore = mockMatchMedia({
      '(min-width: 768px)': true,
      '(pointer: coarse)': true,
    });
    const { result } = renderHook(() => useIsDesktop());
    expect(result.current).toBe(false);
  });
});
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/landing/hooks/useIsDesktop.test.ts`
Expected: FAIL — `frontend/src/pages/landing/hooks/useIsDesktop.ts` doesn't exist yet.

- [ ] **Step 4: Create the hook at the new location**

Create `frontend/src/pages/landing/hooks/useIsDesktop.ts`:

```ts
import { useEffect, useState } from 'react';

const DESKTOP_QUERY = '(min-width: 768px)';
const COARSE_POINTER_QUERY = '(pointer: coarse)';

function computeIsDesktop(): boolean {
  return window.matchMedia(DESKTOP_QUERY).matches && !window.matchMedia(COARSE_POINTER_QUERY).matches;
}

/** True at Tailwind's `md` breakpoint and above, AND only when the primary pointer is fine
 * (not touch) -- a wide touchscreen tablet has no meaningful hover/cursor-follow pointer even
 * though it clears the width check, so pointer-driven effects (3D tilt, magnetic buttons) must
 * stay off there too. Same breakpoint Nav.tsx already uses for its own mobile/desktop split. */
export function useIsDesktop(): boolean {
  const [isDesktop, setIsDesktop] = useState(computeIsDesktop);

  useEffect(() => {
    const desktopMql = window.matchMedia(DESKTOP_QUERY);
    const coarseMql = window.matchMedia(COARSE_POINTER_QUERY);
    const onChange = () => setIsDesktop(computeIsDesktop());
    onChange();
    desktopMql.addEventListener('change', onChange);
    coarseMql.addEventListener('change', onChange);
    return () => {
      desktopMql.removeEventListener('change', onChange);
      coarseMql.removeEventListener('change', onChange);
    };
  }, []);

  return isDesktop;
}
```

- [ ] **Step 5: Delete the old hook file and its co-located test**

Run: `rm frontend/src/pages/landing/hero/useIsDesktop.ts` and remove any old test file found in Step 1.

- [ ] **Step 6: Update import paths in dependents**

In `frontend/src/pages/landing/hero/FloatingDashboardCard.tsx`, change:
```ts
import { useIsDesktop } from './useIsDesktop';
```
to:
```ts
import { useIsDesktop } from '../hooks/useIsDesktop';
```

Run: `grep -rn "hero/useIsDesktop\|from '\./useIsDesktop'" frontend/src` and fix every remaining reference the same way (this includes `AmbientCanvas.tsx` if it imports the hook, and `FloatingDashboardCard.test.tsx`'s own `mockMatchMedia` usage does not need a path change since it doesn't import the hook directly).

- [ ] **Step 7: Run the full test suite to verify nothing broke**

Run: `cd frontend && npm test`
Expected: PASS — the new `useIsDesktop.test.ts` passes, and `FloatingDashboardCard.test.tsx` / `AmbientCanvas.test.tsx` (which exercise the hook indirectly via `mockMatchMedia`) still pass unchanged since the hook's exported behavior for `(min-width: 768px)` alone is unaffected by the added `(pointer: coarse)` check (test setup's default `matchMedia` mock resolves unlisted queries to `false`, so `(pointer: coarse)` defaults to not-coarse in every existing test that doesn't mention it).

- [ ] **Step 8: Commit**

```bash
git add frontend/src/pages/landing/hooks frontend/src/pages/landing/hero
git commit -m "refactor(frontend): move useIsDesktop to shared landing hooks, add pointer:coarse gating"
```

---

### Task 2: `useMagnetic` hook

**Files:**
- Create: `frontend/src/pages/landing/hooks/useMagnetic.ts`
- Test: `frontend/src/pages/landing/hooks/useMagnetic.test.ts`

**Interfaces:**
- Consumes: `useIsDesktop()` from Task 1 (`../hooks/useIsDesktop`).
- Produces: `useMagnetic(): { x: MotionValue<number>; y: MotionValue<number>; onPointerMove: (e: PointerEvent) => void; onPointerLeave: () => void; ref: RefObject<HTMLElement> }` — `x`/`y` are Framer Motion spring `MotionValue`s driving a `translate` transform; `ref` must be attached to the element whose bounding box the pointer offset is measured against; `onPointerMove`/`onPointerLeave` are inert no-ops (springs never move off `0`) whenever motion is disabled (reduced-motion, non-desktop, or coarse pointer).

- [ ] **Step 1: Write the failing test**

Create `frontend/src/pages/landing/hooks/useMagnetic.test.ts`:

```ts
import { renderHook, act } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { mockMatchMedia } from '../../../test/mockMatchMedia';

vi.mock('framer-motion', async (importOriginal) => {
  const actual = await importOriginal<typeof import('framer-motion')>();
  return { ...actual, useReducedMotion: vi.fn() };
});

import { useReducedMotion } from 'framer-motion';
import { useMagnetic } from './useMagnetic';

describe('useMagnetic', () => {
  let restore: (() => void) | undefined;

  afterEach(() => {
    restore?.();
    restore = undefined;
    vi.clearAllMocks();
  });

  it('exposes zeroed x/y springs and a ref before any pointer movement', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    restore = mockMatchMedia({ '(min-width: 768px)': true, '(pointer: coarse)': false });
    const { result } = renderHook(() => useMagnetic());
    expect(result.current.x.get()).toBe(0);
    expect(result.current.y.get()).toBe(0);
  });

  it('does not move the springs on pointer move under prefers-reduced-motion', () => {
    vi.mocked(useReducedMotion).mockReturnValue(true);
    restore = mockMatchMedia({ '(min-width: 768px)': true, '(pointer: coarse)': false });
    const { result } = renderHook(() => useMagnetic());
    const el = document.createElement('div');
    Object.assign(result.current.ref, { current: el });
    vi.spyOn(el, 'getBoundingClientRect').mockReturnValue({
      left: 0, top: 0, width: 100, height: 40, right: 100, bottom: 40, x: 0, y: 0, toJSON: () => {},
    } as DOMRect);
    act(() => {
      result.current.onPointerMove({ clientX: 90, clientY: 36 } as unknown as PointerEvent);
    });
    expect(result.current.x.get()).toBe(0);
    expect(result.current.y.get()).toBe(0);
  });

  it('does not move the springs below the desktop breakpoint', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    restore = mockMatchMedia({ '(min-width: 768px)': false, '(pointer: coarse)': false });
    const { result } = renderHook(() => useMagnetic());
    const el = document.createElement('div');
    Object.assign(result.current.ref, { current: el });
    vi.spyOn(el, 'getBoundingClientRect').mockReturnValue({
      left: 0, top: 0, width: 100, height: 40, right: 100, bottom: 40, x: 0, y: 0, toJSON: () => {},
    } as DOMRect);
    act(() => {
      result.current.onPointerMove({ clientX: 90, clientY: 36 } as unknown as PointerEvent);
    });
    expect(result.current.x.get()).toBe(0);
    expect(result.current.y.get()).toBe(0);
  });

  it('resets the springs to 0 on pointer leave', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    restore = mockMatchMedia({ '(min-width: 768px)': true, '(pointer: coarse)': false });
    const { result } = renderHook(() => useMagnetic());
    act(() => {
      result.current.x.set(4);
      result.current.y.set(-3);
      result.current.onPointerLeave();
    });
    expect(result.current.x.get()).toBe(0);
    expect(result.current.y.get()).toBe(0);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/landing/hooks/useMagnetic.test.ts`
Expected: FAIL with "Cannot find module './useMagnetic'".

- [ ] **Step 3: Write the implementation**

Create `frontend/src/pages/landing/hooks/useMagnetic.ts`:

```ts
import { useRef, type PointerEvent as ReactPointerEvent } from 'react';
import { useReducedMotion, useSpring } from 'framer-motion';
import { useIsDesktop } from './useIsDesktop';

const MAX_DISTANCE = 8; // px -- calm, restrained follow, not an aggressive cursor-chase
const SPRING = { stiffness: 140, damping: 20, mass: 0.3 };

/**
 * Pointer-relative spring transform for the sitewide magnetic-hover CTA effect. Gated off
 * (springs stay at 0, handlers become no-ops) under prefers-reduced-motion and on non-desktop /
 * coarse-pointer devices -- mirrors FloatingDashboardCard's own `use3D`-style gating pattern from
 * the Hero sub-project. maxDistance/stiffness/damping/mass are fixed per the global chrome spec,
 * not configurable per call site -- one calm feel across all 6 CTAs.
 */
export function useMagnetic() {
  const prefersReducedMotion = useReducedMotion();
  const isDesktop = useIsDesktop();
  const enabled = isDesktop && !prefersReducedMotion;

  const ref = useRef<HTMLElement | null>(null);
  const x = useSpring(0, SPRING);
  const y = useSpring(0, SPRING);

  function onPointerMove(event: ReactPointerEvent<HTMLElement> | PointerEvent) {
    if (!enabled) return;
    const rect = ref.current?.getBoundingClientRect();
    if (!rect) return;
    const px = (event.clientX - rect.left) / rect.width - 0.5;
    const py = (event.clientY - rect.top) / rect.height - 0.5;
    x.set(px * MAX_DISTANCE * 2);
    y.set(py * MAX_DISTANCE * 2);
  }

  function onPointerLeave() {
    x.set(0);
    y.set(0);
  }

  return { ref, x, y, onPointerMove, onPointerLeave };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/landing/hooks/useMagnetic.test.ts`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/landing/hooks/useMagnetic.ts frontend/src/pages/landing/hooks/useMagnetic.test.ts
git commit -m "feat(frontend): add useMagnetic hook for sitewide CTA hover effect"
```

---

### Task 3: `MagneticLink` and `MagneticAnchor` components

**Files:**
- Create: `frontend/src/pages/landing/MagneticLink.tsx`
- Test: `frontend/src/pages/landing/MagneticLink.test.tsx`

**Interfaces:**
- Consumes: `useMagnetic()` from Task 2.
- Produces: `MagneticLink(props: { to: string; className?: string; children: ReactNode })` and `MagneticAnchor(props: { href: string; className?: string; children: ReactNode })` — both render a single `<a>` element (no wrapper), forwarding `className`/`children` unchanged, with `x`/`y` springs applied as a `translate` transform via Framer Motion's `style` prop.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/pages/landing/MagneticLink.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { mockMatchMedia } from '../../test/mockMatchMedia';

vi.mock('framer-motion', async (importOriginal) => {
  const actual = await importOriginal<typeof import('framer-motion')>();
  return { ...actual, useReducedMotion: vi.fn() };
});

import { useReducedMotion } from 'framer-motion';
import { MagneticLink, MagneticAnchor } from './MagneticLink';

describe('MagneticLink', () => {
  let restore: (() => void) | undefined;

  afterEach(() => {
    restore?.();
    restore = undefined;
    vi.clearAllMocks();
  });

  it('renders a real, navigable anchor with the exact className preserved', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(
      <MemoryRouter>
        <MagneticLink to="/register" className="m-btn m-btn-primary w-full">
          Start free
        </MagneticLink>
      </MemoryRouter>
    );
    const link = screen.getByRole('link', { name: 'Start free' });
    expect(link.tagName).toBe('A');
    expect(link).toHaveAttribute('href', '/register');
    expect(link).toHaveClass('m-btn', 'm-btn-primary', 'w-full');
  });

  it('introduces no extra DOM wrapper -- the anchor is its own top-level element', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    const { container } = render(
      <MemoryRouter>
        <MagneticLink to="/register" className="m-btn">Start free</MagneticLink>
      </MemoryRouter>
    );
    // container's single child must be the <a> itself, not a <div> wrapping it.
    expect(container.firstElementChild?.tagName).toBe('A');
  });

  it('behaves identically under prefers-reduced-motion -- still a real navigable anchor', () => {
    vi.mocked(useReducedMotion).mockReturnValue(true);
    render(
      <MemoryRouter>
        <MagneticLink to="/register" className="m-btn">Start free</MagneticLink>
      </MemoryRouter>
    );
    const link = screen.getByRole('link', { name: 'Start free' });
    expect(link).toHaveAttribute('href', '/register');
  });

  it('MagneticAnchor renders a plain <a> preserving href and className, no wrapper', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    const { container } = render(
      <MagneticAnchor href="#how" className="m-btn m-btn-ghost">
        See how it works
      </MagneticAnchor>
    );
    const link = screen.getByRole('link', { name: 'See how it works' });
    expect(link.tagName).toBe('A');
    expect(link).toHaveAttribute('href', '#how');
    expect(link).toHaveClass('m-btn', 'm-btn-ghost');
    expect(container.firstElementChild?.tagName).toBe('A');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/landing/MagneticLink.test.tsx`
Expected: FAIL with "Cannot find module './MagneticLink'".

- [ ] **Step 3: Write the implementation**

Create `frontend/src/pages/landing/MagneticLink.tsx`:

```tsx
import { forwardRef, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { motion } from 'framer-motion';
import { useMagnetic } from './hooks/useMagnetic';

const MotionLink = motion.create(Link);
const MotionAnchor = motion.create('a');

interface MagneticLinkProps {
  to: string;
  className?: string;
  children: ReactNode;
}

/**
 * Drop-in replacement for react-router's `Link`, wrapping it in a small pointer-relative spring
 * transform (see useMagnetic) via motion.create() -- NOT a wrapping <div>, so the rendered DOM is
 * still a single <a>, and every existing className/layout at each call site is untouched. The
 * magnetic effect is a pointer-only enhancement: onPointerMove/onPointerLeave never fire from
 * keyboard interaction, so tab order, Enter/Space activation and the native :focus-visible outline
 * are exactly what Link would give you unwrapped.
 */
export const MagneticLink = forwardRef<HTMLAnchorElement, MagneticLinkProps>(function MagneticLink(
  { to, className, children },
  forwardedRef
) {
  const { ref, x, y, onPointerMove, onPointerLeave } = useMagnetic();
  return (
    <MotionLink
      to={to}
      className={className}
      ref={(node: HTMLAnchorElement | null) => {
        ref.current = node;
        if (typeof forwardedRef === 'function') forwardedRef(node);
        else if (forwardedRef) forwardedRef.current = node;
      }}
      style={{ x, y }}
      onPointerMove={onPointerMove}
      onPointerLeave={onPointerLeave}
    >
      {children}
    </MotionLink>
  );
});

interface MagneticAnchorProps {
  href: string;
  className?: string;
  children: ReactNode;
}

/** Same as MagneticLink but for a plain `<a href>` (e.g. in-page anchors like #how). */
export const MagneticAnchor = forwardRef<HTMLAnchorElement, MagneticAnchorProps>(function MagneticAnchor(
  { href, className, children },
  forwardedRef
) {
  const { ref, x, y, onPointerMove, onPointerLeave } = useMagnetic();
  return (
    <MotionAnchor
      href={href}
      className={className}
      ref={(node: HTMLAnchorElement | null) => {
        ref.current = node;
        if (typeof forwardedRef === 'function') forwardedRef(node);
        else if (forwardedRef) forwardedRef.current = node;
      }}
      style={{ x, y }}
      onPointerMove={onPointerMove}
      onPointerLeave={onPointerLeave}
    >
      {children}
    </MotionAnchor>
  );
});
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/landing/MagneticLink.test.tsx`
Expected: PASS (4 tests). If `motion.create(Link)`'s ref callback signature conflicts with `forwardRef` typing, adjust the inner ref-merge callback's parameter type to match what TypeScript infers for `MotionLink`'s `ref` prop rather than changing the public props.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/landing/MagneticLink.tsx frontend/src/pages/landing/MagneticLink.test.tsx
git commit -m "feat(frontend): add MagneticLink/MagneticAnchor drop-in CTA components"
```

---

### Task 4: Swap the 6 CTA call sites

**Files:**
- Modify: `frontend/src/pages/landing/Nav.tsx`
- Modify: `frontend/src/pages/landing/Hero.tsx`
- Modify: `frontend/src/pages/landing/Pricing.tsx`
- Modify: `frontend/src/pages/landing/FinalCta.tsx`
- Modify: `frontend/src/pages/Landing.tsx`
- Test: existing tests for these files (no new test file; the className/DOM-shape guarantees are already covered by Task 3's `MagneticLink.test.tsx` at the component level, so this task only needs each site's existing tests to keep passing)

**Interfaces:**
- Consumes: `MagneticLink`/`MagneticAnchor` from Task 3 (`./MagneticLink` or `../MagneticLink` depending on file depth).

- [ ] **Step 1: Swap `Nav.tsx`'s "Get started" CTA**

In `frontend/src/pages/landing/Nav.tsx`, add the import:
```ts
import { MagneticLink } from './MagneticLink';
```
Replace:
```tsx
<Link to="/register" className="m-btn m-btn-primary !min-h-[44px] !px-4 !text-sm">
  Get started <ArrowRight size={14} />
</Link>
```
with:
```tsx
<MagneticLink to="/register" className="m-btn m-btn-primary !min-h-[44px] !px-4 !text-sm">
  Get started <ArrowRight size={14} />
</MagneticLink>
```
Leave the "Log in" `Link` and the mobile-menu section anchors untouched — out of scope per the spec.

- [ ] **Step 2: Swap `Hero.tsx`'s two CTAs**

In `frontend/src/pages/landing/Hero.tsx`, add the import:
```ts
import { MagneticLink, MagneticAnchor } from './MagneticLink';
```
Replace:
```tsx
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
```
with:
```tsx
<MagneticLink to="/register" className="m-btn m-btn-primary w-full sm:w-auto">
  {hero.primaryCta} <ArrowRight size={16} />
</MagneticLink>
<MagneticAnchor
  href="#how"
  className="m-btn m-btn-ghost w-full sm:w-auto"
>
  {hero.secondaryCta}
</MagneticAnchor>
```
The secondary CTA's inline `style` (transparent background, light text/border) must move onto `MagneticAnchor` — since `MagneticAnchor` doesn't currently accept a `style` prop, extend its props in `MagneticLink.tsx` (Task 3's file) to accept an optional `style?: CSSProperties` passed through to `MotionAnchor`/`MotionLink` merged with the internal `{ x, y }` transform (spread `style` before `x`/`y` so the spring transform is never clobbered):
```tsx
style={{ ...style, x, y }}
```
Apply the same optional `style` prop addition to `MagneticLink` for consistency, even though no current call site needs it there. Remove the now-unused `Link` import from `Hero.tsx` if nothing else in the file uses it (`grep -n "Link" frontend/src/pages/landing/Hero.tsx` to check).

- [ ] **Step 3: Swap `Pricing.tsx`'s "Start free" CTA**

In `frontend/src/pages/landing/Pricing.tsx`, add the import:
```ts
import { MagneticLink } from './MagneticLink';
```
Replace:
```tsx
<Link to="/register" className="m-btn m-btn-primary w-full">Start free</Link>
```
with:
```tsx
<MagneticLink to="/register" className="m-btn m-btn-primary w-full">Start free</MagneticLink>
```
Remove the `Link` import if it's now unused in this file.

- [ ] **Step 4: Swap `FinalCta.tsx`'s "Start free" CTA**

In `frontend/src/pages/landing/FinalCta.tsx`, add the import:
```ts
import { MagneticLink } from './MagneticLink';
```
Replace:
```tsx
<Link to="/register" className="m-btn w-full sm:w-auto bg-white text-[var(--m-brand-deep)] hover:bg-slate-50">
```
...and its closing tag...
```tsx
</Link>
```
with `<MagneticLink to="/register" className="m-btn w-full sm:w-auto bg-white text-[var(--m-brand-deep)] hover:bg-slate-50">` / `</MagneticLink>`. Remove the `Link` import if now unused.

- [ ] **Step 5: Swap `Landing.tsx`'s mobile sticky CTA**

In `frontend/src/pages/Landing.tsx`, add the import:
```ts
import { MagneticLink } from './landing/MagneticLink';
```
Replace:
```tsx
<Link to="/register" className="m-btn m-btn-primary w-full">
  Import your first statement <ArrowRight size={16} />
</Link>
```
with:
```tsx
<MagneticLink to="/register" className="m-btn m-btn-primary w-full">
  Import your first statement <ArrowRight size={16} />
</MagneticLink>
```
Leave the `Link` import in place — it's still used by the skip-to-content anchor's sibling elements potentially not, so check with `grep -n "Link" frontend/src/pages/Landing.tsx` and remove the import only if genuinely unused.

- [ ] **Step 6: Run the full test suite**

Run: `cd frontend && npm test`
Expected: PASS. `landing-claims.test.tsx` and every per-section test (`Nav.test.tsx` if it exists yet, `Hero.test.tsx`, `Pricing.test.tsx`, `FinalCta.test.tsx`) must still pass — they query by role/text/href, which `MagneticLink`/`MagneticAnchor` preserve exactly. If any test queried the previous `<a>` by a Framer-Motion-added default class or attribute that doesn't exist, that would be a real regression — investigate rather than loosening the assertion.

- [ ] **Step 7: Manually verify no visual regression**

Run: `cd frontend && npm run build && npm run preview` (or `npm run dev`), open the landing page, and confirm all 6 CTAs still render with their original size/color/layout and now show a subtle follow effect on mouse hover (desktop, no reduced-motion).

- [ ] **Step 8: Commit**

```bash
git add frontend/src/pages/landing/Nav.tsx frontend/src/pages/landing/Hero.tsx frontend/src/pages/landing/Pricing.tsx frontend/src/pages/landing/FinalCta.tsx frontend/src/pages/Landing.tsx frontend/src/pages/landing/MagneticLink.tsx
git commit -m "feat(frontend): apply magnetic hover to the 6 landing page CTAs"
```

---

### Task 5: Hero-visibility detection in `Landing.tsx`

**Files:**
- Modify: `frontend/src/pages/Landing.tsx`
- Test: `frontend/src/pages/Landing.test.tsx` (new, or extend `landing-claims.test.tsx` if that's the existing convention — check first)

**Interfaces:**
- Produces: an `overHero: boolean` value passed as `<Nav overHero={overHero} />` (Nav itself is updated in Task 6 to accept it — until Task 6 lands, this task's own build will show a TS error on the new prop, which is expected and resolved by the very next task; both tasks must be verified together via Step 4 below before considering Task 5 "done" in isolation is not required by this plan's task boundaries, but running the type-check after Task 6 is what actually proves this task correct).

- [ ] **Step 1: Check for an existing Landing-level test file**

Run: `find frontend/src/pages -maxdepth 1 -iname "*landing*test*"` — note the file name and its existing test structure so this task extends it rather than duplicating a second file rendering the same page.

- [ ] **Step 2: Write a failing test for the observer wiring**

In the existing landing test file (or a new `frontend/src/pages/Landing.test.tsx` if none exists), add:

```tsx
it('drives overHero via the IntersectionObserver watching the Hero wrapper', () => {
  const observeSpy = vi.fn();
  const observerInstances: Array<{ callback: IntersectionObserverCallback }> = [];
  const OriginalIO = window.IntersectionObserver;
  window.IntersectionObserver = vi.fn((callback: IntersectionObserverCallback) => {
    observerInstances.push({ callback });
    return { observe: observeSpy, unobserve: vi.fn(), disconnect: vi.fn(), takeRecords: () => [] } as unknown as IntersectionObserver;
  }) as unknown as typeof IntersectionObserver;

  render(
    <MemoryRouter>
      <Landing />
    </MemoryRouter>
  );

  expect(observeSpy).toHaveBeenCalledTimes(1);
  expect(observerInstances).toHaveLength(1);

  window.IntersectionObserver = OriginalIO;
});
```

Adjust imports (`render` from `@testing-library/react`, `MemoryRouter` from `react-router-dom`, `vi` from `vitest`, `Landing` from the default export) to match whatever the existing test file already imports — do not introduce a second, differently-configured render setup if one already exists (e.g. router providers, mocked child sections).

- [ ] **Step 3: Run test to verify it fails**

Run: `cd frontend && npx vitest run <path-to-landing-test-file>`
Expected: FAIL — `Landing.tsx` doesn't construct an `IntersectionObserver` yet.

- [ ] **Step 4: Implement the observer in `Landing.tsx`**

In `frontend/src/pages/Landing.tsx`, add near the top:
```ts
import { useEffect, useRef, useState } from 'react';
```
(merge with any existing React import if present). Add a constant near the top of the file, alongside `WHITE`/`ALT`/`DEEP`:
```ts
const NAV_HEIGHT_PX = 64; // Nav.tsx's own h-16 (64px) header height
```
Inside the `Landing` component, before the `return`:
```tsx
const heroRef = useRef<HTMLDivElement | null>(null);
const [overHero, setOverHero] = useState(true);

useEffect(() => {
  const node = heroRef.current;
  if (!node) return;
  const observer = new IntersectionObserver(
    ([entry]) => setOverHero(entry.isIntersecting),
    { rootMargin: `-${NAV_HEIGHT_PX}px 0px 0px 0px`, threshold: 0 }
  );
  observer.observe(node);
  return () => observer.disconnect();
}, []);
```
Wrap `<Hero />` and pass `overHero` to `Nav`:
```tsx
<Nav overHero={overHero} />

<main id="main-content">
  <div ref={heroRef}>
    <Hero />
  </div>
  <Transition from="#05070C" to={WHITE} height={80} />
  ...
```
`overHero` initial state is `true` (Hero fills the screen at page load, matching the spec's rationale) so the Nav renders correctly on first paint before the observer's first callback fires.

- [ ] **Step 5: Run test to verify it passes**

Run: `cd frontend && npx vitest run <path-to-landing-test-file>`
Expected: PASS. (This will show a TypeScript error on `<Nav overHero={overHero} />` until Task 6 adds the prop — run `cd frontend && npx tsc --noEmit` after Task 6, not after this task, to confirm zero type errors.)

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/Landing.tsx frontend/src/pages/Landing.test.tsx
git commit -m "feat(frontend): wire Hero-visibility IntersectionObserver into Landing"
```

---

### Task 6: `Nav.tsx` transparent-over-Hero / glass-past-Hero styling

**Files:**
- Modify: `frontend/src/pages/landing/Nav.tsx`
- Test: `frontend/src/pages/landing/Nav.test.tsx` (new)

**Interfaces:**
- Consumes: `overHero: boolean` prop (from Task 5's `Landing.tsx`).
- Produces: `Nav({ overHero }: { overHero: boolean })` — no other exported signature change; `Logo` stays as-is (already accepts `invert`).

- [ ] **Step 1: Write the failing test**

Create `frontend/src/pages/landing/Nav.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { Nav } from './Nav';

describe('Nav', () => {
  it('renders transparent, inverted styling when overHero is true', () => {
    render(
      <MemoryRouter>
        <Nav overHero={true} />
      </MemoryRouter>
    );
    const header = screen.getByRole('banner');
    expect(header.style.background).not.toContain('255, 255, 255');
    expect(header.style.backdropFilter === 'none' || header.style.backdropFilter === '').toBe(true);
    // Logo should render its inverted (light-on-dark) wordmark color.
    expect(screen.getByText('Finora')).toHaveStyle({ color: '#F8FAFC' });
  });

  it('renders the translucent-glass look when overHero is false', () => {
    render(
      <MemoryRouter>
        <Nav overHero={false} />
      </MemoryRouter>
    );
    const header = screen.getByRole('banner');
    expect(header.style.background).toContain('255 255 255');
    expect(screen.getByText('Finora')).toHaveStyle({ color: '#0F172A' });
  });

  it('gives the open mobile menu panel an explicit dark background while overHero', () => {
    render(
      <MemoryRouter>
        <Nav overHero={true} />
      </MemoryRouter>
    );
    const menuButton = screen.getByRole('button', { name: 'Open menu' });
    menuButton.click();
    const panel = screen.getByText('How it works').closest('div');
    expect(panel).not.toBeNull();
    expect(panel?.style.background).toBeTruthy();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/landing/Nav.test.tsx`
Expected: FAIL — `Nav` doesn't accept an `overHero` prop yet, and current styling is scroll-based, not prop-based.

- [ ] **Step 3: Implement `overHero`-driven styling in `Nav.tsx`**

Replace the whole file content from the `export function Nav()` declaration onward:

```tsx
export function Nav({ overHero }: { overHero: boolean }) {
  const [open, setOpen] = useState(false);

  return (
    <header
      className="sticky top-0 z-30 transition-all duration-300"
      style={{
        background: overHero ? 'transparent' : 'rgb(255 255 255 / .88)',
        backdropFilter: overHero ? 'none' : 'blur(12px)',
        borderBottom: overHero ? '1px solid transparent' : '1px solid var(--m-line)',
        boxShadow: overHero ? 'none' : '0 1px 0 rgba(15,23,42,.06), 0 8px 24px -16px rgba(15,23,42,.25)',
      }}
    >
      <div className="max-w-6xl mx-auto px-5 sm:px-6 h-16 flex items-center justify-between">
        <Logo invert={overHero} />
        <nav className="hidden md:flex items-center gap-8 text-sm" style={{ color: overHero ? '#F8FAFC' : 'var(--m-ink-2)' }}>
          {LINKS.map(([label, href]) => (
            <a
              key={href}
              href={href}
              className="transition-colors"
              style={{ color: overHero ? 'rgba(248,250,252,0.85)' : undefined }}
              onMouseEnter={(e) => { if (overHero) e.currentTarget.style.color = '#F8FAFC'; }}
              onMouseLeave={(e) => { if (overHero) e.currentTarget.style.color = 'rgba(248,250,252,0.85)'; }}
            >
              {label}
            </a>
          ))}
        </nav>
        <div className="flex items-center gap-3">
          <Link
            to="/login"
            className="hidden sm:block text-sm transition-colors"
            style={{ color: overHero ? 'rgba(248,250,252,0.85)' : 'var(--m-ink-2)' }}
          >
            Log in
          </Link>
          <MagneticLink to="/register" className="m-btn m-btn-primary !min-h-[44px] !px-4 !text-sm">
            Get started <ArrowRight size={14} />
          </MagneticLink>
          <button
            type="button"
            onClick={() => setOpen((v) => !v)}
            className="md:hidden w-11 h-11 grid place-items-center rounded-lg border"
            style={{ borderColor: overHero ? 'rgba(255,255,255,0.35)' : 'var(--m-line)', color: overHero ? '#F8FAFC' : undefined }}
            aria-label={open ? 'Close menu' : 'Open menu'}
            aria-expanded={open}
          >
            {open ? <X size={18} /> : <Menu size={18} />}
          </button>
        </div>
      </div>
      {open ? (
        <div
          className="md:hidden border-t px-5 py-2"
          style={{
            borderColor: overHero ? 'rgba(255,255,255,0.15)' : 'var(--m-line)',
            background: overHero ? 'rgba(5,7,12,0.96)' : undefined,
          }}
        >
          {LINKS.map(([label, href]) => (
            <a
              key={href}
              href={href}
              onClick={() => setOpen(false)}
              className="m-tap block text-sm"
              style={{ color: overHero ? '#F8FAFC' : 'var(--m-ink-2)' }}
            >
              {label}
            </a>
          ))}
          <Link
            to="/login"
            className="m-tap block text-sm"
            style={{ color: overHero ? '#F8FAFC' : 'var(--m-ink-2)' }}
          >
            Log in
          </Link>
        </div>
      ) : null}
    </header>
  );
}
```

Update the file's imports at the top: drop `useEffect` (no longer used — `scrolled` state and its scroll listener are gone), keep `useState`, add `import { MagneticLink } from './MagneticLink';`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/landing/Nav.test.tsx`
Expected: PASS (3 tests).

- [ ] **Step 5: Type-check the whole frontend**

Run: `cd frontend && npx tsc --noEmit`
Expected: zero errors — this confirms Task 5's `<Nav overHero={overHero} />` call site now matches Nav's updated signature.

- [ ] **Step 6: Run the full test suite**

Run: `cd frontend && npm test`
Expected: PASS, all suites including `landing-claims.test.tsx`, `Landing.test.tsx` (Task 5), `Nav.test.tsx`, `MagneticLink.test.tsx`, `useMagnetic.test.ts`, `useIsDesktop.test.ts`.

- [ ] **Step 7: Manually verify in the browser**

Run: `cd frontend && npm run dev`, open the landing page, and confirm: Nav is transparent with light text/logo while Hero is on screen, and crossfades to the existing white/glass look once scrolled past Hero; the mobile menu (resize to a narrow viewport) shows a dark, readable panel while over Hero.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/pages/landing/Nav.tsx frontend/src/pages/landing/Nav.test.tsx
git commit -m "feat(frontend): transparent-over-Hero to glass Nav transition"
```

---

### Task 7: Final full-suite verification

**Files:** none (verification-only task).

- [ ] **Step 1: Run the full test suite**

Run: `cd frontend && npm test`
Expected: PASS, zero failures, zero skipped tests introduced by this plan.

- [ ] **Step 2: Type-check**

Run: `cd frontend && npx tsc --noEmit`
Expected: zero errors.

- [ ] **Step 3: Production build**

Run: `cd frontend && npm run build`
Expected: succeeds; confirms `motion.create()` usage and the moved `useIsDesktop` import paths all resolve correctly under the production bundler, not just under Vitest's module resolution.

- [ ] **Step 4: Real-browser smoke check**

Open the built/dev landing page and verify, in order: (1) Nav is transparent over Hero and crossfades to glass past it, at both desktop and mobile widths; (2) all 6 CTAs (Nav, Hero ×2, Pricing, FinalCta, mobile sticky bar) still look and navigate exactly as before; (3) on desktop with a real mouse, hovering each of the 6 CTAs shows a small, calm follow effect; (4) with `prefers-reduced-motion: reduce` set in devtools, the follow effect disappears but every CTA still renders, is clickable, and keyboard-focusable with a visible focus ring; (5) no console errors.

- [ ] **Step 5: Hand off to finishing-a-development-branch**

No commit here — this task is verification-only. Proceed directly to the finishing-a-development-branch skill.
