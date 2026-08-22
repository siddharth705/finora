# Hero cinematic reveal — design

## Context

Sid asked for a full "premium SaaS" motion redesign of the Finora landing page (Stripe /
Linear / Vercel / Apple-product-launch territory), based on a detailed brief covering the
hero, scroll storytelling, per-feature micro-animations, nav/button chrome, and background
effects. That brief describes several independent pieces of work. This spec covers only the
first of them.

## Decomposition (full scope, for context — only #1 is designed/built here)

1. **Hero cinematic reveal** — this spec.
2. **Global chrome** — glass navbar on scroll, magnetic/spring button interactions, living
   background (blobs/particles). Small, cross-cutting, touches every section.
3. **Scroll storytelling** — the "scattered data → Finora AI → intelligence" morph sequence
   and section-to-section transitions (GSAP ScrollTrigger + Lenis).
4. **Per-feature animations** — Statement Intelligence upload→insight sequence, AI Insights
   reveal, Goals progress bar. One micro-animation per existing feature section.

Build order: 1 → 2 → 3 → 4. Each gets its own brainstorm/spec/plan cycle when it starts.

## Decisions carried into this spec

- **Visual direction is a full replace**, not a variant alongside the current page. The
  existing Hero.tsx is rewritten in place.
- **Copy does not change.** `landing-config.ts` and its claims-test discipline
  (`landing-claims.test.tsx`) stay as-is; only the visual/motion treatment changes. The
  hero's plain-language, specific claims ("Money tells a story...") are the actual product,
  not marketing filler — rewriting them is a positioning decision out of scope here.
- **Animation stack: Framer Motion + React Three Fiber**, added as new dependencies. GSAP
  and Lenis are explicitly **not** added in this pass — they belong to sub-project 3
  (scroll-tied work); the hero's entrance sequence is mount-triggered, not scroll-tied, so
  Framer Motion's `staggerChildren`/`delayChildren` is sufficient and keeps this pass's
  dependency footprint smaller.
- **React Three Fiber is scoped to an ambient background layer only.** The dashboard preview
  itself stays real DOM (Approach A from the design discussion), not a WebGL-rendered mesh —
  see Non-goals.
- **Mobile gets a simplified build**, not the full effects stack.

## Architecture

| File | Role |
|---|---|
| `Hero.tsx` (rewritten) | Orchestrates the sequence, dark background, layout |
| `hero/AmbientCanvas.tsx` | Lazy-loaded R3F `<Canvas>` — ambient particle/glow backdrop only, `React.lazy` + `Suspense`, WebGL support checked before mount |
| `hero/FloatingDashboardCard.tsx` | Wraps the existing `DashboardMock` in a CSS-3D glass shell — `perspective`/`rotateX/Y`, Framer Motion `useMotionValue`/springs for mouse-tilt and the entrance settle (blur+scale+translateY → 0, with an 8°/-5° rotate settle) |
| `hero/HealthScoreRing.tsx` | Circular SVG progress + glow, count-up via the **existing** `CountUp` primitive (`primitives.tsx`) |
| `hero/IntelligenceScan.tsx` | The "Analyzing your finances... ✓ ..." checklist, built on the **existing** `useStagedReveal` primitive |
| `hero/FloatingBadges.tsx` | The `+₹90,000 Salary` / `Investment +12%` pills, slow independent Framer Motion `animate` loops, randomized per-badge delay |

**Reuse, not reinvention:** `primitives.tsx` already has `Reveal`, `CountUp`, and
`useStagedReveal` — hand-rolled equivalents of what Framer Motion provides, each with
careful `prefers-reduced-motion` and no-`IntersectionObserver` fallback behavior already
proven in production. `CountUp` and `useStagedReveal` are reused unchanged inside the new
Hero rather than reimplemented with Framer Motion. Framer Motion is added specifically for
what the existing primitives don't do: spring-based mouse-tilt physics and declarative
`staggerChildren` sequencing of "background → particles → dashboard → charts → score →
insights" as one parent variant tree, instead of hand-timed `setTimeout` chains.

New dependencies: `framer-motion`, `@react-three/fiber`, `@react-three/drei`, `three`
(+ `@types/three` if not bundled).

## Fallbacks & accessibility

- **`prefers-reduced-motion`:** skip entrance animation entirely (render final state
  immediately — same rule the existing primitives already follow); no ambient canvas; no
  floating-badge loops; no mouse-tilt.
- **WebGL unavailable/fails to init:** feature-detect before mounting `AmbientCanvas`; on
  failure, render a static CSS gradient instead of a blank hole or a thrown error.
- **Mobile** (below Tailwind's `md` breakpoint, 768px — the same breakpoint `Nav.tsx` already
  uses for its own mobile/desktop split): `AmbientCanvas` does not mount at all;
  `FloatingDashboardCard` skips mouse-tilt (no pointer) and uses a simpler entrance (fade +
  translateY only, no rotateX/Y settle — matching `Reveal`'s existing motion budget).
- All dashboard numbers/labels stay real DOM text — screen readers get the actual content;
  nothing meaningful is trapped in canvas/WebGL.

## Testing

- Follow the existing pattern in this codebase: Vitest + Testing Library, structural/a11y
  assertions, not animation-frame assertions.
- Render each new component with `IntersectionObserver`/`matchMedia` mocked both ways
  (reduced-motion on and off) and assert the correct *end-state* content is present.
- One test forcing WebGL-unavailable to confirm `AmbientCanvas` falls back cleanly instead of
  crashing the page.
- One test confirming mobile viewport skips `AmbientCanvas`/tilt.
- Confirm `landing-claims.test.tsx` still passes unchanged once implemented (copy isn't
  moving, but worth verifying nothing in the new markup accidentally touches tested claims).

## Performance constraints

- Dashboard DOM renders before/independently of the WebGL layer — the ambient canvas must
  never block or delay the readable product preview.
- `AmbientCanvas` is lazy-loaded (`React.lazy` + `Suspense`) so it never blocks initial paint
  or interactivity.
- Target smooth behavior on mid-range devices, not just high-end ones — this is the reason
  for the mobile fallback and the WebGL feature-detection above, not an additional mechanism.

## Non-goals

- Do not render the dashboard (or any financially meaningful content) inside WebGL — Approach
  B (WebGL mesh dashboard) was considered and explicitly rejected for text-crispness,
  accessibility, and mobile-performance reasons.
- Do not replace `Reveal`/`CountUp`/`useStagedReveal` with Framer Motion equivalents where
  they already work — only add Framer Motion for what they don't cover.
- Do not change landing page copy or its claims-test discipline as part of this pass.
- Do not build sub-projects 2–4 (global chrome, scroll storytelling, per-feature animations)
  in this pass — they are listed above for context only.
- Do not optimize for visual complexity over usability, and never make financial information
  legibility depend on animation/motion state.
