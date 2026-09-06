# Scroll storytelling — design

## Context

Third sub-project of the premium SaaS landing page motion redesign (see
`docs/superpowers/specs/2026-08-22-hero-cinematic-reveal-design.md` for the full decomposition).
Hero shipped in PR #294/#296. Global chrome shipped in PR #297.

Remaining decomposition, for context:

3. **Scroll storytelling** — this spec.
4. **Per-feature animations** — Statement Intelligence upload→insight sequence, AI Insights
   reveal, Goals progress bar. One micro-animation per existing feature section.

## Decisions carried into this spec

- **Scope is `ImportSection` only.** The phrase "scattered data → Finora AI → intelligence" maps
  directly to the statement-import journey (raw statements → Finora AI processing → categorized
  financial intelligence) — not `LearningSection` (a different narrative: Finora improving over
  time from corrections), and not every section boundary. Premium SaaS sites earn their motion
  through contrast — one memorable, high-impact interaction, everything else calm. `LearningSection`
  and every `<Transition>` band stay exactly as they are today.
- **No Lenis.** Lenis replaces the browser's native scroll physics site-wide — it cannot be scoped
  to one section. Adding it would mean every section's scroll feel now depends on a persistent
  site-wide dependency, for the benefit of a single section. GSAP `ScrollTrigger` works fine against
  native scroll; that's what this section uses.
- **GSAP `ScrollTrigger` pins only `ImportSection`'s content area, not the page.** Scroll behavior
  everywhere else on the page is completely unaffected — normal scroll in, pinned transformation
  plays out, normal scroll continues out.
- **Real DOM/SVG layers, not canvas/WebGL.** Consistent with `Hero`'s own non-goal (the dashboard
  preview there is real DOM specifically so it stays crisp/accessible/animation-independent) and
  with the rest of the codebase's icon-driven, no-stock-imagery visual language.
- **Visual style: an isometric mini-scene**, not abstract gradients or line-art. Finora is a
  financial operating system, not a creative tool — the visual language needs to communicate
  transformation, structure, and trust, which a literal (if stylized) document → processing →
  dashboard sequence does and an abstract gradient ribbon doesn't. Considered and rejected:
  abstract flowing gradients (communicates mood, not product — fine for Stripe/Linear whose users
  already understand the product, wrong for a page that needs to explain *how* Finora works);
  minimal line-art (too close to generic SaaS/onboarding illustration, lacks the "wow moment" this
  sub-project exists to deliver).
- **The "AI" beat must not be a generic glowing orb.** Every AI product does that; it says nothing
  about Finora specifically. It should read as a financial-system pipeline (document → extraction →
  categorization → insights), not a chatbot mascot.
- **Mobile gets a reveal-once version of the same three beats, not the pinned/scrubbed version.**
  Pinning/scroll-scrubbing has no good mobile equivalent (no reliable scroll-linked control,
  fights touch scrolling) — rather than attempt a smaller/lighter pin (usually bad UX per prior
  experience with scroll-jacking on mobile), mobile drops pinning entirely: the same three
  components render as a single `IntersectionObserver`-triggered reveal-once sequence, matching
  Hero's own "mobile gets a simplified build, not the full effects stack" precedent.
- **`prefers-reduced-motion` skips the pin and scrub entirely on desktop too** — not just a slower
  or shorter version. A user who has disabled motion should not have to scroll through a long
  pinned sequence to reach the rest of the page; they see `IntelligencePanel`'s final assembled
  state immediately, as if the transformation had already happened.
- **Animation state is owned by GSAP, not React.** A scrubbed timeline updates on every scroll
  frame (up to 60fps). Storing that progress in React state and re-rendering three components per
  frame would fight React's reconciliation for no benefit. Presentational scene components render
  static DOM/SVG markup and expose their animatable elements via refs; the `ScrollTrigger` timeline
  mutates transforms/opacity on those refs directly, entirely outside React's render cycle.

## Architecture

| File | Responsibility |
|---|---|
| `landing/ImportSection.tsx` (modified) | Keeps its real, always-accessible copy (eyebrow/title/blurb — unchanged) exactly as it renders today, in normal document flow. Replaces the existing hover-triggered card with `<ImportScrollStory />` next to that copy. |
| `landing/import-story/ImportScrollStory.tsx` (new) | Owns the pin container (`position: relative`, pinned via `ScrollTrigger.pin`) and gates desktop-vs-mobile via the existing `landing/hooks/useIsDesktop` hook and reduced-motion via Framer Motion's `useReducedMotion` (same sitewide source of truth used everywhere else in this redesign — no separate `matchMedia` call). Renders `DocumentStack`/`ProcessingCore`/`IntelligencePanel` and forwards refs from each into `useImportScrollTimeline`. The whole scene wrapper carries `aria-hidden="true"` — it is illustrative reinforcement of the copy beside it, not the information carrier. |
| `landing/import-story/useImportScrollTimeline.ts` (new) | The only file that imports `gsap`/`gsap/ScrollTrigger`. Builds one `gsap.timeline({ scrollTrigger: { trigger, pin: true, scrub: true, start, end } })` scrubbing tweens against the three refs it's given, so all three beats stay driven by one source of truth (no per-component independent triggers that could drift out of sync). Built inside a `gsap.context()` (or equivalent scoping) whose `.revert()` runs in the effect's cleanup — required for React 18 `StrictMode`'s mount→unmount→remount double-invoke in dev (the exact class of bug the Hero sub-project hit with Framer Motion variants — see that spec's implementation notes) and for route navigation away from `/`. Does nothing (no `ScrollTrigger` created at all) when reduced-motion is on or `useIsDesktop()` is false — callers branch on those before invoking it. |
| `landing/import-story/DocumentStack.tsx` (new) | Beat 1 (0–35% of the timeline): tilted statement/PDF/CSV cards and floating transaction-row chips, scattered. Presentational only — forwards a ref to its root, no scroll/GSAP knowledge. |
| `landing/import-story/ProcessingCore.tsx` (new) | Beat 2 (35–70%): the Finora mark as a financial-pipeline visual (document → extraction → categorization → insights labels), with the beat-1 elements converging toward it. Not a generic glowing orb. Presentational, ref-forwarding only. |
| `landing/import-story/IntelligencePanel.tsx` (new) | Beat 3 (70–100%): a small dashboard mock assembling into place — same card radius, typography, and green accent as the Hero dashboard card (`FloatingDashboardCard`/`DashboardMock`), so the page reads as one product, not two dashboard designs. Presentational, ref-forwarding only; also the static final-state render used directly (no timeline) under reduced-motion and as the end frame of the mobile reveal-once sequence. |
| `landing/import-story/ImportRevealSequence.tsx` (new) | Mobile / reduced-motion fallback: the same three components in a single `IntersectionObserver`-driven reveal-once sequence (same "safe default, animate only if allowed" pattern as `useStagedReveal` in `primitives.tsx`), no `ScrollTrigger`, no pinning. |

Pinned scroll distance starts at **250vh**, tuned visually during implementation, mapped as:
`0–35%` scattered → `35–70%` converging/processing → `70–100%` assembled dashboard.

## Fallbacks & accessibility

- **Screen readers:** the entire animated scene (`ImportScrollStory`'s pinned content, and
  `ImportRevealSequence`'s mobile content) is `aria-hidden="true"`. The real information — "upload
  a statement, Finora's AI processes it, you get organized transactions" — lives in `ImportSection`'s
  existing eyebrow/title/blurb copy, unchanged, in normal document flow, never gated behind scroll
  position or animation state. This mirrors `DashboardMock`'s existing pattern of decorative mock
  data behind a `role="img"`/`aria-label`, generalized to `aria-hidden` since here the illustration
  has no informational content beyond what the adjacent copy already states.
- **Keyboard:** `ScrollTrigger.pin` responds to native scroll regardless of input method — Page
  Down, arrow keys, and Space all drive the same scroll events a mouse wheel or trackpad would.
  Tab order is unaffected (pinning doesn't change DOM order or focusability).
- **`prefers-reduced-motion`:** no `ScrollTrigger` is created at all (checked before
  `useImportScrollTimeline` is ever invoked, not inside it) — `ImportScrollStory` renders
  `IntelligencePanel` alone, in its final assembled state, no pin, no scrub, no decorative movement.
- **Mobile (`!useIsDesktop`):** `ImportRevealSequence` renders instead of `ImportScrollStory` — no
  pinning, no `ScrollTrigger`, same reveal-once pattern as the rest of the page.
- **Cleanup:** `useImportScrollTimeline`'s effect must revert/kill its GSAP context on unmount.
  Required both for React `StrictMode`'s double-invoke in dev and for real navigation away from
  `/` — an un-reverted `ScrollTrigger` left listening after unmount is a real leak, not a
  theoretical one (the Hero sub-project's StrictMode/Framer-Motion bug is the direct precedent for
  taking this seriously up front rather than discovering it in production).

## Testing

- **`useImportScrollTimeline`:** `gsap` and `gsap/ScrollTrigger` are mocked (same approach as
  `Landing.test.tsx`'s `IntersectionObserver` mock — assert construction arguments, not visual
  output; GSAP/ScrollTrigger's real scroll-linked behavior is not meaningfully testable in jsdom,
  which has no real layout or scroll). Assert: a timeline is created with `pin: true`, `scrub: true`,
  and the expected trigger element; the effect's cleanup calls the revert/kill path; no `ScrollTrigger`
  is created at all when called under reduced-motion or non-desktop conditions.
- **`DocumentStack`/`ProcessingCore`/`IntelligencePanel`:** plain presentational-component tests —
  render, assert expected decorative structure exists, assert the ref forwards to the correct root
  node. No GSAP or scroll mocking needed, since these components have no scroll knowledge at all.
- **`ImportRevealSequence`:** reuses the existing `IntersectionObserver`-mock pattern from
  `HealthScoreRing`/`useStagedReveal`'s own tests — verify it shows a safe default (final state,
  not scattered) before the observer fires, and animates once visible.
- **`ImportScrollStory`:** verify it renders `ImportScrollStory`'s pinned scene under normal
  conditions, `IntelligencePanel` alone under reduced-motion, and `ImportRevealSequence` under
  non-desktop — the three-way branch is the thing most likely to regress silently.
- **`ImportSection.test.tsx` (if none exists, new):** the section's existing copy (eyebrow/title/
  blurb) renders unchanged and is not inside the `aria-hidden` scene wrapper — the accessibility
  guarantee this whole design rests on.
- Existing `landing-claims.test.tsx` must still pass unchanged — no copy is touched by this
  sub-project.

## Non-goals

- Do not add Lenis or any site-wide smooth-scroll engine.
- Do not use canvas or WebGL for this scene — real DOM/SVG only.
- Do not apply pinning, `ScrollTrigger`, or scroll-scrubbing to any other section (`LearningSection`,
  the `<Transition>` bands, or anywhere else) in this pass.
- Do not design a generic "AI orb" visual for the processing beat.
- Do not build sub-project 4 (per-feature animations) in this pass.

## Open item for implementation time

Verify `gsap` + `gsap/ScrollTrigger`'s current package licensing and installation requirements
against the actual installed version when adding the dependency — not asserted here, the same way
the Hero spec deferred confirming Framer Motion's exact component-wrapping API until implementation.
