# LearningSection Reinforcement Animation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `LearningSection`'s plain `setTimeout`-staggered fade with a GSAP-driven, play-once-on-scroll sequence: four cards animate in one at a time, connected by a self-drawing line, ending on a "Pattern confirmed" beat — no fabricated confidence number, no pin, no scrub.

**Architecture:** A new `useLearningTimeline.ts` hook is the sole owner of GSAP for this section (mirrors `useImportScrollTimeline.ts`). `LearningSection.tsx` keeps its existing four-stage data and markup shape, adding refs and `data-target` attributes GSAP queries directly — animation state lives in GSAP, never in React state, so the sequence can never trigger a re-render.

**Tech Stack:** React 18, TypeScript, GSAP 3 + `gsap/ScrollTrigger` (already a dependency after `ImportSection`), Tailwind, Vitest + Testing Library.

**Spec:** `docs/superpowers/specs/2026-08-23-learning-section-animation-design.md`

## Global Constraints

- No pin, no scroll-scrub — `ScrollTrigger` fires once (`once: true`) and never reverses.
- No new presentational component files — cards and connectors stay inline in `LearningSection.tsx`.
- No `aria-hidden` anywhere in this section — the cards are real content, not a decorative scene.
- No fabricated confidence percentage — the confirmation line reads "Pattern confirmed" (or equivalent non-numeric language), never a literal percentage.
- `prefers-reduced-motion` renders the fully-settled final state immediately: all four cards visible, all three connectors fully drawn, confirmation line shown.
- GSAP context is created via `gsap.context()` and torn down via `.revert()` on unmount (React 18 StrictMode double-invoke safety).
- Existing copy in `landing-config.ts`'s `learning` object is unchanged — only the new confirmation-line string is added inline in `LearningSection.tsx` (it does not reuse `learning-config.ts` since it is a UI micro-copy detail of one animation beat, not a section-level headline/blurb).

---

### Task 1: `useLearningTimeline` hook

**Files:**
- Create: `frontend/src/pages/landing/useLearningTimeline.ts`
- Test: `frontend/src/pages/landing/useLearningTimeline.test.ts`

**Interfaces:**
- Consumes: nothing from earlier tasks (first task).
- Produces: `useLearningTimeline(options: LearningTimelineOptions): void`, where:
  ```ts
  interface LearningTimelineOptions {
    enabled: boolean;
    containerRef: RefObject<HTMLElement | null>;
    card1Ref: RefObject<HTMLElement | null>;
    card2Ref: RefObject<HTMLElement | null>;
    card3Ref: RefObject<HTMLElement | null>;
    card4Ref: RefObject<HTMLElement | null>;
    connector1Ref: RefObject<HTMLElement | null>;
    connector2Ref: RefObject<HTMLElement | null>;
    connector3Ref: RefObject<HTMLElement | null>;
  }
  ```
  Task 2 imports this hook and these refs by name. Individual named refs (not an array) are deliberate: an array literal is a new object identity every render, which would re-run the effect on every render if placed in its dependency array — the same reason `useImportScrollTimeline` takes named refs rather than an array.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/pages/landing/useLearningTimeline.test.ts`:

```ts
import { renderHook } from '@testing-library/react';
import { createRef } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

const { revertSpy, contextSpy, timelineSpy, toSpy, registerPluginSpy } = vi.hoisted(() => {
  const revertSpy = vi.fn();
  const contextSpy = vi.fn((fn: () => void) => {
    fn();
    return { revert: revertSpy };
  });
  const toSpy = vi.fn().mockReturnThis();
  const timelineInstance = { to: toSpy };
  const timelineSpy = vi.fn((_config: { scrollTrigger: { trigger: unknown; start: string; once: boolean } }) => timelineInstance);
  const registerPluginSpy = vi.fn();
  return { revertSpy, contextSpy, timelineSpy, toSpy, registerPluginSpy };
});

vi.mock('gsap', () => ({
  gsap: {
    context: contextSpy,
    timeline: timelineSpy,
    registerPlugin: registerPluginSpy,
  },
}));
vi.mock('gsap/ScrollTrigger', () => ({ ScrollTrigger: { name: 'ScrollTrigger' } }));

import { useLearningTimeline } from './useLearningTimeline';

function makeRefs() {
  const names = [
    'containerRef', 'card1Ref', 'card2Ref', 'card3Ref', 'card4Ref',
    'connector1Ref', 'connector2Ref', 'connector3Ref',
  ] as const;
  const refs = {} as Record<(typeof names)[number], ReturnType<typeof createRef<HTMLDivElement>>>;
  for (const name of names) {
    const ref = createRef<HTMLDivElement>();
    (ref as { current: HTMLDivElement }).current = document.createElement('div');
    refs[name] = ref;
  }
  return refs;
}

describe('useLearningTimeline', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('builds a play-once timeline against the container when enabled', () => {
    const refs = makeRefs();
    renderHook(() => useLearningTimeline({ enabled: true, ...refs }));

    expect(contextSpy).toHaveBeenCalledTimes(1);
    expect(timelineSpy).toHaveBeenCalledTimes(1);
    const config = timelineSpy.mock.calls[0][0];
    expect(config.scrollTrigger.trigger).toBe(refs.containerRef.current);
    expect(config.scrollTrigger.start).toBe('top 75%');
    expect(config.scrollTrigger.once).toBe(true);
    expect(config.scrollTrigger).not.toHaveProperty('pin');
    expect(config.scrollTrigger).not.toHaveProperty('scrub');
  });

  it('animates all four cards and all three connectors', () => {
    const refs = makeRefs();
    renderHook(() => useLearningTimeline({ enabled: true, ...refs }));

    const animatedTargets = toSpy.mock.calls.map((call) => call[0]);
    expect(animatedTargets).toContain(refs.card1Ref.current);
    expect(animatedTargets).toContain(refs.card2Ref.current);
    expect(animatedTargets).toContain(refs.card3Ref.current);
    expect(animatedTargets).toContain(refs.card4Ref.current);
    expect(animatedTargets).toContain(refs.connector1Ref.current);
    expect(animatedTargets).toContain(refs.connector2Ref.current);
    expect(animatedTargets).toContain(refs.connector3Ref.current);
  });

  it('reverts the GSAP context on unmount', () => {
    const refs = makeRefs();
    const { unmount } = renderHook(() => useLearningTimeline({ enabled: true, ...refs }));
    unmount();
    expect(revertSpy).toHaveBeenCalled();
  });

  it('creates no timeline at all when disabled', () => {
    const refs = makeRefs();
    renderHook(() => useLearningTimeline({ enabled: false, ...refs }));
    expect(contextSpy).not.toHaveBeenCalled();
    expect(timelineSpy).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/landing/useLearningTimeline.test.ts`
Expected: FAIL with "Cannot find module './useLearningTimeline'"

- [ ] **Step 3: Write minimal implementation**

Create `frontend/src/pages/landing/useLearningTimeline.ts`:

```ts
import { useEffect, type RefObject } from 'react';
import { gsap } from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';

interface LearningTimelineOptions {
  enabled: boolean;
  containerRef: RefObject<HTMLElement | null>;
  card1Ref: RefObject<HTMLElement | null>;
  card2Ref: RefObject<HTMLElement | null>;
  card3Ref: RefObject<HTMLElement | null>;
  card4Ref: RefObject<HTMLElement | null>;
  connector1Ref: RefObject<HTMLElement | null>;
  connector2Ref: RefObject<HTMLElement | null>;
  connector3Ref: RefObject<HTMLElement | null>;
}

/**
 * The only file in the LearningSection reinforcement sequence that imports gsap or
 * gsap/ScrollTrigger -- mirrors useImportScrollTimeline's "animation state lives in GSAP via
 * refs, not React state" rule. Unlike ImportSection's pinned/scrubbed scene, this is a
 * play-once, never-reverse sequence: `once: true` auto-kills the ScrollTrigger after the first
 * hit, which is "enter viewport -> play -> stay played" -- see the design spec's "Trigger"
 * section for why this isn't toggleActions or a pin. No `pin`, no `scrub` in the scrollTrigger
 * config, deliberately -- their absence is asserted directly in the test above.
 *
 * Named individual refs rather than an array/object of refs: an array literal is a new object
 * identity every render, which would re-run this effect on every render if it were a dependency.
 *
 * Eases use GSAP's built-in named curves (power3.out / power2.inOut) rather than an exact
 * cubic-bezier match to the rest of the page's [0.16,1,0.3,1] curve -- matching it precisely
 * would need the CustomEase plugin for one cosmetic detail, which isn't worth the extra import.
 */
export function useLearningTimeline({
  enabled,
  containerRef,
  card1Ref,
  card2Ref,
  card3Ref,
  card4Ref,
  connector1Ref,
  connector2Ref,
  connector3Ref,
}: LearningTimelineOptions): void {
  useEffect(() => {
    if (!enabled) return;
    const cardRefs = [card1Ref, card2Ref, card3Ref, card4Ref];
    const connectorRefs = [connector1Ref, connector2Ref, connector3Ref];
    if (!containerRef.current) return;
    if (cardRefs.some((r) => !r.current) || connectorRefs.some((r) => !r.current)) return;

    gsap.registerPlugin(ScrollTrigger);

    const ctx = gsap.context(() => {
      const tl = gsap.timeline({
        scrollTrigger: {
          trigger: containerRef.current,
          start: 'top 75%',
          once: true,
        },
      });

      cardRefs.forEach((cardRef, i) => {
        const at = i * 0.6;
        tl.to(cardRef.current, { opacity: 1, y: 0, duration: 0.5, ease: 'power3.out' }, at);
        const connectorRef = connectorRefs[i];
        if (connectorRef) {
          tl.to(connectorRef.current, { scaleX: 1, duration: 0.4, ease: 'power2.inOut' }, at + 0.3);
        }
      });

      tl.to(
        '[data-target="confirmation"]',
        { scale: 1.06, duration: 0.2, ease: 'power2.out', yoyo: true, repeat: 1 },
        '>-0.1'
      );
    }, containerRef);

    return () => ctx.revert();
  }, [enabled, containerRef, card1Ref, card2Ref, card3Ref, card4Ref, connector1Ref, connector2Ref, connector3Ref]);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/landing/useLearningTimeline.test.ts`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/landing/useLearningTimeline.ts frontend/src/pages/landing/useLearningTimeline.test.ts
git commit -m "feat(frontend): add useLearningTimeline GSAP hook for LearningSection"
```

---

### Task 2: Wire `LearningSection.tsx` to the timeline

**Files:**
- Modify: `frontend/src/pages/landing/LearningSection.tsx` (full rewrite of the component body; `STAGES`/`TAG_STYLE` shapes extended, not replaced)

**Interfaces:**
- Consumes: `useLearningTimeline` from Task 1, `useReducedMotion` from `framer-motion` (already used by `Hero.tsx`/`ImportScrollStory.tsx`), `Check` from `lucide-react` (already imported today).
- Produces: nothing new for later tasks — this is the last file in the animation itself. Task 3 tests render `<LearningSection />` directly.

- [ ] **Step 1: Write the failing test first (Task 3 covers this — see below)**

Task 3's test file is written before this step's implementation is exercised end-to-end; this task's own "test" is the existing build/typecheck plus Task 3 running immediately after. There is no separate red/green cycle purely for markup changes with no new logic of their own (the logic lives in Task 1's hook, already tested).

- [ ] **Step 2: Replace `LearningSection.tsx`**

Replace the full contents of `frontend/src/pages/landing/LearningSection.tsx`:

```tsx
import { useRef } from 'react';
import { Check } from 'lucide-react';
import { useReducedMotion } from 'framer-motion';
import { Section, SectionHeading } from './primitives';
import { learning } from './landing-config';
import { useLearningTimeline } from './useLearningTimeline';

/**
 * The learning loop, played rather than diagrammed.
 *
 * Five beats: the import guesses, the guess is wrong, you fix it, Finora records the preference,
 * the next import is already right. A static before/after states the same thing but leaves the
 * reader to work out the causality; watching the connector draw from card to card and the final
 * "Pattern confirmed" pulse land does it for them.
 *
 * This mirrors a real capability. A corrected merchant is remembered and applied on later imports
 * -- it is not a roadmap animation. See docs/superpowers/specs/2026-08-23-learning-section-
 * animation-design.md for the full design, including why this stays a play-once sequence (no
 * pin, no scrub -- see ImportSection for that register) and why the confirmation line reads
 * "Pattern confirmed" rather than a literal confidence percentage.
 */
const STAGES = [
  { caption: 'First import', merchant: 'Amazon', tag: 'Uncategorized', tone: 'unknown' as const },
  { caption: 'You fix it', merchant: 'Amazon', tag: 'Shopping', tone: 'edit' as const },
  { caption: 'Finora records it', merchant: 'Pattern saved', tag: null, tone: 'learn' as const },
  { caption: 'Next import', merchant: 'Amazon', tag: 'Shopping', tone: 'done' as const, confirmedLine: 'Pattern confirmed' },
];

const TAG_STYLE: Record<string, { background: string; color: string }> = {
  unknown: { background: 'rgb(148 163 184 / .18)', color: '#CBD5E1' },
  edit: { background: 'rgb(244 241 236 / .16)', color: '#F4F1EC' },
  learn: { background: 'rgb(244 241 236 / .16)', color: '#F4F1EC' },
  done: { background: 'rgb(22 163 74 / .18)', color: '#4ADE80' },
};

export function LearningSection() {
  const prefersReducedMotion = useReducedMotion();

  const containerRef = useRef<HTMLDivElement>(null);
  const card1Ref = useRef<HTMLDivElement>(null);
  const card2Ref = useRef<HTMLDivElement>(null);
  const card3Ref = useRef<HTMLDivElement>(null);
  const card4Ref = useRef<HTMLDivElement>(null);
  const connector1Ref = useRef<HTMLDivElement>(null);
  const connector2Ref = useRef<HTMLDivElement>(null);
  const connector3Ref = useRef<HTMLDivElement>(null);
  const cardRefs = [card1Ref, card2Ref, card3Ref, card4Ref];
  const connectorRefs = [connector1Ref, connector2Ref, connector3Ref];

  useLearningTimeline({
    enabled: !prefersReducedMotion,
    containerRef,
    card1Ref,
    card2Ref,
    card3Ref,
    card4Ref,
    connector1Ref,
    connector2Ref,
    connector3Ref,
  });

  return (
    <Section tone="deep">
      <SectionHeading
        invert
        eyebrow={learning.eyebrow}
        title={<>{learning.title}<br />{learning.titleLine2}</>}
        blurb={learning.blurb}
      />
      {/* grid at base/sm (2-column wrap, matches today), flex row at lg where there's room for
          connectors between cards -- `display: contents` on the per-stage wrapper at base makes
          each wrapper invisible to the grid (its card/connector children participate directly),
          then lg:flex turns the same wrapper into a real flex item holding one card + its
          trailing connector as a unit. */}
      <div ref={containerRef} className="grid grid-cols-1 sm:grid-cols-2 gap-3 lg:flex lg:gap-0 lg:items-stretch">
        {STAGES.map((s, i) => {
          const isDone = s.tone === 'done';
          const initialCardStyle = prefersReducedMotion
            ? { opacity: 1, transform: 'none' }
            : { opacity: 0, transform: 'translateY(8px)' };
          const initialConnectorStyle = prefersReducedMotion ? { transform: 'scaleX(1)' } : { transform: 'scaleX(0)' };

          return (
            <div key={s.caption} className="contents lg:flex lg:flex-1 lg:items-stretch">
              <div
                ref={cardRefs[i]}
                className="rounded-xl p-4 border relative lg:flex-1"
                style={{
                  background: 'rgb(255 255 255 / .05)',
                  borderColor: isDone ? 'rgb(22 163 74 / .55)' : 'rgb(244 241 236 / .45)',
                  ...initialCardStyle,
                }}
              >
                <p className="text-[10px] uppercase tracking-wide text-slate-400 mb-2">{s.caption}</p>
                <p className="text-sm font-semibold text-slate-100 mb-2">{s.merchant}</p>
                {s.confirmedLine ? (
                  <p
                    data-target="confirmation"
                    className="flex items-center gap-1 text-xs font-medium mb-2"
                    style={{ color: '#4ADE80' }}
                  >
                    <Check size={12} /> {s.confirmedLine}
                  </p>
                ) : null}
                {s.tag ? (
                  <span
                    className="inline-flex items-center gap-1 text-[10px] font-medium px-2 py-1 rounded-md"
                    style={TAG_STYLE[s.tone]}
                  >
                    {isDone ? <Check size={11} /> : null}
                    {s.tag}
                  </span>
                ) : (
                  <span className="inline-block text-[10px] px-2 py-1 rounded-md" style={TAG_STYLE.edit}>
                    learning…
                  </span>
                )}
              </div>
              {i < STAGES.length - 1 ? (
                <div className="hidden lg:flex items-center px-2 flex-none w-8">
                  <div className="w-full h-0.5 rounded-full overflow-hidden" style={{ background: 'rgba(255,255,255,.12)' }}>
                    <div
                      ref={connectorRefs[i]}
                      className="h-full origin-left"
                      style={{ background: '#4ADE80', ...initialConnectorStyle }}
                    />
                  </div>
                </div>
              ) : null}
            </div>
          );
        })}
      </div>
      <p className="text-center text-sm mt-8 text-slate-400">
        {learning.footnote}
      </p>
    </Section>
  );
}
```

- [ ] **Step 3: Typecheck**

Run: `cd frontend && npx tsc --noEmit`
Expected: no new errors.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/landing/LearningSection.tsx
git commit -m "feat(frontend): wire LearningSection cards to useLearningTimeline"
```

---

### Task 3: `LearningSection.test.tsx`

**Files:**
- Create: `frontend/src/pages/landing/LearningSection.test.tsx`

**Interfaces:**
- Consumes: `LearningSection` from Task 2, mocks `useLearningTimeline` from Task 1 (same pattern `ImportScrollStory.test.tsx` uses for `useImportScrollTimeline` — assert the hook's `enabled` arg, not real animation).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/pages/landing/LearningSection.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('framer-motion', async (importOriginal) => {
  const actual = await importOriginal<typeof import('framer-motion')>();
  return { ...actual, useReducedMotion: vi.fn() };
});
vi.mock('./useLearningTimeline', () => ({ useLearningTimeline: vi.fn() }));

import { useReducedMotion } from 'framer-motion';
import { useLearningTimeline } from './useLearningTimeline';
import { LearningSection } from './LearningSection';
import { learning } from './landing-config';

describe('LearningSection', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('renders the heading copy and all four learning-loop cards', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<LearningSection />);

    expect(screen.getByText(learning.title)).toBeInTheDocument();
    expect(screen.getAllByText('Amazon').length).toBeGreaterThanOrEqual(3);
    expect(screen.getByText('First import')).toBeInTheDocument();
    expect(screen.getByText('You fix it')).toBeInTheDocument();
    expect(screen.getByText('Finora records it')).toBeInTheDocument();
    expect(screen.getByText('Next import')).toBeInTheDocument();
  });

  it('shows the non-numeric confirmation line, never a fabricated percentage', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<LearningSection />);

    expect(screen.getByText('Pattern confirmed')).toBeInTheDocument();
    expect(screen.queryByText(/%/)).not.toBeInTheDocument();
  });

  it('marks no element aria-hidden -- these cards are real content, not a decorative scene', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    const { container } = render(<LearningSection />);
    expect(container.querySelector('[aria-hidden="true"]')).not.toBeInTheDocument();
  });

  it('enables the timeline only when motion is allowed', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<LearningSection />);
    expect(vi.mocked(useLearningTimeline).mock.calls[0][0].enabled).toBe(true);
  });

  it('disables the timeline and still shows all four cards under prefers-reduced-motion', () => {
    vi.mocked(useReducedMotion).mockReturnValue(true);
    render(<LearningSection />);

    expect(vi.mocked(useLearningTimeline).mock.calls[0][0].enabled).toBe(false);
    expect(screen.getByText('First import')).toBeInTheDocument();
    expect(screen.getByText('Next import')).toBeInTheDocument();
    expect(screen.getByText('Pattern confirmed')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/landing/LearningSection.test.tsx`
Expected: at minimum, FAIL on the "Pattern confirmed" assertion against the pre-Task-2 component (if run before Task 2) — or, run after Task 2's implementation, this step should already largely PASS since Task 2 wrote the implementation directly. Run it regardless to confirm the full file is green before moving on.

- [ ] **Step 3: Fix anything that fails**

If any assertion fails, adjust `LearningSection.tsx` from Task 2 (not the test) unless the test itself is wrong per the spec.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/landing/LearningSection.test.tsx`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/landing/LearningSection.test.tsx
git commit -m "test(frontend): cover LearningSection content, a11y and reduced-motion contract"
```

---

### Task 4: Full verification

**Files:** none (verification only)

- [ ] **Step 1: Full test suite**

Run: `cd frontend && npx vitest run`
Expected: all tests pass, including the 3 new files above.

- [ ] **Step 2: Typecheck**

Run: `cd frontend && npx tsc --noEmit`
Expected: no errors.

- [ ] **Step 3: Production build**

Run: `cd frontend && npm run build`
Expected: build succeeds.

- [ ] **Step 4: Self bug/gap review**

Before finishing, re-read the diff for this plan's three files against the spec's non-goals list: confirm no `aria-hidden` was added anywhere in `LearningSection.tsx`, confirm no literal percentage appears anywhere in the new copy, confirm `useLearningTimeline.ts` is the only new file importing `gsap`/`gsap/ScrollTrigger`, confirm the `scrollTrigger` config has no `pin`/`scrub` keys.

- [ ] **Step 5: Manual browser check**

Start the dev server, scroll to the LearningSection band, and confirm: (a) at a desktop width, four cards appear left-to-right with a visibly drawing connector between each; (b) at a narrow width, the connectors are hidden and the four cards wrap 2-per-row exactly as before; (c) with reduced-motion simulated (OS setting or DevTools emulation), all four cards and the "Pattern confirmed" line are visible immediately with no animation.

- [ ] **Step 6: Commit any fixes found**

If Steps 4–5 surface anything, fix it and commit separately with a message describing what was found (matching this project's established "bug/gap review" commit pattern from the ImportSection and Global-chrome sub-projects).
