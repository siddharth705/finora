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
| `Landing.tsx` | Wraps `<Hero />` in a plain `<div ref={heroRef}>` (not inside `Hero.tsx` — that file stays untouched) and observes that wrapper directly with an `IntersectionObserver`, `rootMargin: '-${NAV_HEIGHT}px 0px 0px 0px'`, `threshold: 0`. `overHero` is set to `entry.isIntersecting` directly — no `boundingClientRect` branching. Passed down as `<Nav overHero={overHero} />`. See the note below for why observing the Hero element itself (rather than a 1px sentinel at its trailing edge) makes the naive `isIntersecting` read already correct. |
| `Nav.tsx` | Accepts `overHero: boolean`. Replaces the old `scrolled` (`scrollY > 8`) state entirely — while `overHero`: transparent background, no blur, no shadow, white/light text, `<Logo invert />`, light-bordered mobile-menu button; once past Hero: today's translucent-glass look (unchanged). CSS-transitioned (`transition-all` ~300ms) — no Framer Motion here, consistent with Nav's existing lightweight-CSS approach; the navbar state change stays CSS-only even as other parts of the page pick up Framer Motion, deliberately, so the page doesn't turn into `motion.div` everywhere. The mobile dropdown panel (when open) gets an explicit dark translucent background while `overHero`, since it would otherwise inherit a transparent header background and show hero content bleeding through behind its links. |
| `landing/hooks/useIsDesktop.ts` | **Moved** from `landing/hero/useIsDesktop.ts` — promoted to a shared landing hooks folder now that Nav's magnetic buttons need it too, not just Hero's sub-components. `hero/FloatingDashboardCard.tsx`, `hero/AmbientCanvas.tsx`, and their tests update their import path accordingly. Also gains a `pointer: coarse` check (see below) so it doubles as the "does this device have a real hover-capable pointer" gate, not just a viewport-width check. |
| `landing/hooks/useMagnetic.ts` (new) | Pointer-relative spring transform (Framer Motion `useSpring`): `maxDistance: 8px`, `stiffness: 140`, `damping: 20`, `mass: 0.3` — a calm, restrained follow (Stripe/Linear-style), not an energetic cursor-chase. Reset on pointer leave. Disabled under `prefers-reduced-motion` and on non-desktop / coarse-pointer devices (reuses the moved `useIsDesktop`, which now also excludes `(pointer: coarse)` — a large tablet with no real mouse must not get magnetic tracking even if `useIsDesktop`'s width check alone would pass it). Mirrors `FloatingDashboardCard`'s own `use3D`-style gating. |
| `landing/MagneticLink.tsx` (new) | `MagneticLink` (wraps react-router `Link`) and `MagneticAnchor` (wraps a plain `<a>`), both built on `useMagnetic()` via `motion(Link)`/`motion.create(Link)` — no extra wrapper DOM node, the rendered element is still a single `<a>`. Same props as the components they replace (`to`/`href`, `className`, `children`) — a direct import swap at each call site, not a new usage pattern. |
| 6 CTA call sites | `Nav.tsx` ("Get started"), `Hero.tsx` (primary `Link` + secondary `<a href="#how">`), `Pricing.tsx` ("Start free"), `FinalCta.tsx` ("Start free"), `Landing.tsx` (mobile sticky "Import your first statement") — swap the import, nothing else changes. |

**Why observing Hero directly (not a 1px sentinel at its trailing edge) makes `entry.isIntersecting` already correct:**
Hero is taller than the viewport on real screens (confirmed in the shipped Hero's own screenshots
— the health score/dashboard bottom requires scrolling to reach). A 1px sentinel placed at Hero's
*bottom* edge starts off-screen below the fold at page load, so a naive `isIntersecting` read on
that sentinel would be `false` at the top of the page — backwards. But `IntersectionObserver`
computes intersection against the *entire observed target*, not a point, so observing the Hero
wrapper itself sidesteps the problem entirely: at page load Hero fills (or exceeds) the viewport,
so it's substantially intersecting and `isIntersecting` is correctly `true`; as the user scrolls,
`isIntersecting` only flips to `false` once Hero's bottom edge has scrolled past the *effective*
top of the viewport. The `rootMargin: '-${NAV_HEIGHT}px 0px 0px 0px'` is what makes "effective top"
mean "just below the navbar" rather than the literal viewport top, so the crossing point still
lines up exactly with Hero disappearing behind Nav — the same precision the sentinel approach was
after, without needing a second element or any manual geometry math in the callback.

**Scoping note:** Nav's "Log in" link and the mobile-menu section anchors are plain navigation,
not CTAs — they do not get magnetic behavior. Scope is strictly the 6 `.m-btn`-styled buttons the
original brief actually meant by "CTA buttons."

**Framer Motion component-wrapping API:** the exact API for wrapping an arbitrary component
(react-router's `Link`) with Framer Motion — `motion(Component)` vs `motion.create(Component)` —
depends on the installed `framer-motion` version (`^13.1.1` per `package.json`) and needs to be
confirmed against the installed package during implementation (Task 1), not assumed here.

## Fallbacks & accessibility

- **`prefers-reduced-motion`:** `useMagnetic()` returns inert handlers (no transform, no spring)
  — buttons render and behave exactly as plain `Link`/`<a>` elements. Critically, only the
  pointer-tracking *transform* is disabled: `className` is untouched either way, so `:hover`,
  `:focus-visible`, and `:active` styles already defined on `.m-btn`/`.m-btn-primary` (background,
  shadow, color changes) keep working exactly as they do today. Reduced motion removes the follow
  effect, not the button's normal interactive feedback.
- **Mobile / non-desktop / coarse pointer:** same as above — magnetic tracking never activates.
  `useIsDesktop` gates on both a viewport-width check and `(pointer: coarse)`, so a large tablet
  with a touchscreen but no real mouse doesn't get pointer-following behavior it can't sensibly
  produce (matching the existing hero-component pattern of skipping pointer-driven effects where
  there's no meaningful pointer).
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
- `MagneticLink` introduces no extra DOM wrapper: assert the rendered anchor's parent is not a
  `MagneticLink`-introduced `<div>` — e.g. render inside a marked container and assert
  `getByRole('link').parentElement === container` (or an equivalent structural/snapshot check).
  The whole point of building on `motion(Link)`/`motion.create(Link)` rather than a wrapping
  component is that the DOM shape stays `<a>`, not `<div><a></a></div>`; a passing className test
  alone wouldn't catch a regression back to a wrapper div.
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
