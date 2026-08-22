# Global chrome — design

## Context

Second sub-project of the premium SaaS landing page motion redesign (see
`docs/superpowers/specs/2026-08-22-hero-cinematic-reveal-design.md` for the full decomposition
and the first sub-project, Hero, which shipped in PR #294 with a follow-up fix in PR #296).

Remaining decomposition, for context:

2. **Global chrome** — this spec.
3. **Scroll storytelling** — the "scattered data → Finora AI → intelligence" morph sequence and
   section-to-section transitions (GSAP ScrollTrigger + Lenis).
4. **Per-feature animations** — Statement Intelligence upload→insight sequence, AI Insights
   reveal, Goals progress bar. One micro-animation per existing feature section.

## Decisions carried into this spec

- **Living background (blobs/particles) stays Hero-only.** The rest of the page is deliberately
  calm/light — the reason only Hero + the two mid-page dark bands + the final CTA are dark at
  all. Particle fields on white sections would fight that restraint. Not extended here.
- **Magnetic button hover is sitewide**, applied via a shared drop-in component rather than
  duplicated per section. Actual scope is small: 6 CTA sites total (`Nav.tsx`, `Hero.tsx` ×2,
  `Pricing.tsx`, `FinalCta.tsx`, `Landing.tsx`'s mobile sticky bar) — verified by grep, not the
  ~14 originally assumed.
- **Nav crosses from transparent (over Hero) to today's translucent-glass look (past Hero)**,
  rather than staying permanently glass. This is a deliberate change from Nav's current behavior
  (always translucent-white with a scroll-triggered shadow).
- **Hero-visibility detection lives in `Landing.tsx`, not Nav.tsx.** Nav and Hero are siblings;
  `Landing.tsx` already owns page composition ("the running order and nothing else" — its own
  file comment), so it's the natural owner of an `IntersectionObserver` bridging the two, rather
  than coupling them through a DOM-id sentinel.
- **Magnetic buttons are `motion(Link)`-based drop-in replacements**, not a wrapping component —
  no extra DOM node, existing `className`/`w-full`/flex layouts at each of the 6 sites stay
  untouched.

## Architecture

| File | Change |
|---|---|
| `Landing.tsx` | A 1px sentinel `<div ref={heroEndRef} aria-hidden="true" className="h-px" />` placed immediately after `<Hero />` (not inside it — `Hero.tsx` itself is untouched). An `IntersectionObserver` on that ref, `rootMargin: '-${NAV_HEIGHT}px 0px 0px 0px'` and `threshold: 0` (so the callback fires right as the sentinel crosses the nav-height line), drives `overHero: boolean` — passed as `<Nav overHero={overHero} />`. See the note below on why the decision uses `boundingClientRect.top`, not `entry.isIntersecting` directly. |
| `Nav.tsx` | Accepts `overHero: boolean`. Replaces the old `scrolled` (`scrollY > 8`) state entirely — while `overHero`: transparent background, no blur, no shadow, white/light text, `<Logo invert />`, light-bordered mobile-menu button; once past Hero: today's translucent-glass look (unchanged). CSS-transitioned (`transition-all` ~300ms) — no Framer Motion here, consistent with Nav's existing lightweight-CSS approach; the navbar state change stays CSS-only even as other parts of the page pick up Framer Motion, deliberately, so the page doesn't turn into `motion.div` everywhere. The mobile dropdown panel (when open) gets an explicit dark translucent background while `overHero`, since it would otherwise inherit a transparent header background and show hero content bleeding through behind its links. |
| `landing/hooks/useIsDesktop.ts` | **Moved** from `landing/hero/useIsDesktop.ts` — promoted to a shared landing hooks folder now that Nav's magnetic buttons need it too, not just Hero's sub-components. `hero/FloatingDashboardCard.tsx`, `hero/AmbientCanvas.tsx`, and their tests update their import path accordingly. |
| `landing/hooks/useMagnetic.ts` (new) | Pointer-relative spring transform (Framer Motion `useSpring`): `maxDistance: 10px`, `stiffness: 180`, `damping: 15`, `mass: 0.3` — small, subtle follow, not an aggressive jump. Reset on pointer leave. Disabled under `prefers-reduced-motion` and on non-desktop (reuses the moved `useIsDesktop`) — mirrors `FloatingDashboardCard`'s own `use3D`-style gating. |
| `landing/MagneticLink.tsx` (new) | `MagneticLink` (wraps react-router `Link`) and `MagneticAnchor` (wraps a plain `<a>`), both built on `useMagnetic()`. Same props as the components they replace (`to`/`href`, `className`, `children`) — a direct import swap at each call site, not a new usage pattern. |
| 6 CTA call sites | `Nav.tsx` ("Get started"), `Hero.tsx` (primary `Link` + secondary `<a href="#how">`), `Pricing.tsx` ("Start free"), `FinalCta.tsx` ("Start free"), `Landing.tsx` (mobile sticky "Import your first statement") — swap the import, nothing else changes. |

**Why the `overHero` decision uses `boundingClientRect.top`, not a bare `isIntersecting` check:**
Hero is taller than the viewport on real screens (confirmed in the shipped Hero's own screenshots
— the health score/dashboard bottom requires scrolling to reach). That means at page load the
sentinel (Hero's true bottom edge) starts off-screen, below the fold. A plain `entry.isIntersecting`
read is `false` in that state — indistinguishable from "the user has already scrolled past Hero,"
which is the opposite of the truth (Hero is the very first thing on screen) and would start Nav in
glass mode at the top of the page. `IntersectionObserver` always fires once immediately on
`.observe()` with the current geometry, so reading `entry.boundingClientRect.top > NAV_HEIGHT_PX`
inside the callback (rather than trusting `isIntersecting`) gives the correct answer in every case:
a large positive `top` (sentinel far below, Hero still fills the screen) and a `top` between 0 and
`NAV_HEIGHT_PX` (Hero ending, still behind/at Nav) both correctly read as `overHero: true`; only
once `top` drops below `NAV_HEIGHT_PX` (sentinel has scrolled up behind Nav) does it flip to
`false`. The `rootMargin`/`threshold` configuration is still what makes the observer callback fire
right at that crossing point — it's just not the value being branched on directly.

**Scoping note:** Nav's "Log in" link and the mobile-menu section anchors are plain navigation,
not CTAs — they do not get magnetic behavior. Scope is strictly the 6 `.m-btn`-styled buttons the
original brief actually meant by "CTA buttons."

**Framer Motion component-wrapping API:** the exact API for wrapping an arbitrary component
(react-router's `Link`) with Framer Motion — `motion(Component)` vs `motion.create(Component)` —
depends on the installed `framer-motion` version (`^13.1.1` per `package.json`) and needs to be
confirmed against the installed package during implementation (Task 1), not assumed here.

## Fallbacks & accessibility

- **`prefers-reduced-motion`:** `useMagnetic()` returns inert handlers (no transform, no spring)
  — buttons render and behave exactly as plain `Link`/`<a>` elements.
- **Mobile / non-desktop (no pointer):** same as above — magnetic tracking never activates
  (reuses `useIsDesktop`, matching the existing hero-component pattern of skipping pointer-driven
  effects where there's no meaningful pointer).
- **Nav crossfade:** CSS transition only, so it degrades gracefully under reduced-motion by
  default browser behavior (`prefers-reduced-motion` is respected automatically for
  transition-only visual changes; no extra JS gating needed since nothing here is a decorative
  loop or entrance animation the user could find distracting — it's a one-time state change tied
  to scroll position, informational rather than decorative).
- **Accessibility of `MagneticLink`/`MagneticAnchor`:** must render as a real, focusable,
  keyboard-navigable anchor (`<a>` under the hood, whether via react-router's `Link` or a plain
  `<a>`) — never a `<div>` with a click handler pretending to be a link. Framer Motion's
  component-wrapping preserves the underlying element type, so this is a property to verify in
  tests, not something requiring extra implementation work.
- **Keyboard accessibility, explicitly:** the magnetic effect is a pointer-only enhancement and
  must never interfere with keyboard use. Tab navigation must reach the button in the same order
  as today; Enter/Space must activate navigation exactly as a plain `Link`/`<a>` would; the
  browser's native `:focus-visible` outline must remain visible and unmodified — the transform
  applied by `useMagnetic()` only ever responds to `pointermove`/`pointerleave`, never to
  focus/blur, so a keyboard user never sees the button move and never loses its focus ring.

## Testing

- `Nav.test.tsx` (new): render `<Nav overHero={true} />` and `<Nav overHero={false} />` directly
  and assert the inverted vs. non-inverted styling (logo `invert` prop, text color, background).
  `IntersectionObserver` never fires in the jsdom test environment (per `src/test/setup.ts`), so
  testing the `overHero` prop directly on `Nav` is more reliable than trying to simulate the
  observer through `Landing.tsx`.
- `MagneticLink`/`MagneticAnchor` tests: assert the rendered element is a real, navigable anchor
  (`getByRole('link', ...)`, correct `href`/`to` resolution) under both default and
  reduced-motion conditions — accessibility-critical, not just a rendering nicety.
- `MagneticLink` preserves the existing `className`/Tailwind styling: this is the actual risk in
  swapping `Link` for a Framer-Motion-wrapped version at 6 already-styled call sites — assert the
  rendered anchor still carries the exact `className` passed in (e.g. `m-btn m-btn-primary
  w-full`), not just that it renders and navigates. A wrapper that silently drops or reorders
  className handling would pass every other test here while visually breaking every CTA on the
  page.
- `useMagnetic()` gating tests follow the same `vi.mock('framer-motion', ...)` /
  `mockMatchMedia` pattern already established for `FloatingDashboardCard`/`FloatingBadges` in
  the Hero sub-project (see that spec's Task 7 note on why `useReducedMotion` must be mocked
  directly rather than via `mockMatchMedia`).
- Existing `landing-claims.test.tsx` (renders full `Landing`) must still pass unchanged — no copy
  is touched by this sub-project.

## Non-goals

- Do not extend the ambient particle/glow background beyond Hero.
- Do not add GSAP or Lenis in this pass — no scroll-tied animation work here, only a scroll-driven
  boolean (`overHero`) via `IntersectionObserver`, which is a different and much simpler
  mechanism.
- Do not apply magnetic behavior to non-CTA navigation links (Nav's "Log in", section anchors).
- Do not build sub-projects 3–4 (scroll storytelling, per-feature animations) in this pass.
