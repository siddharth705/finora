# LearningSection Reinforcement Animation — Design

**Sub-project 4a of the landing-page motion redesign** (see
`docs/superpowers/specs/2026-08-23-scroll-storytelling-design.md` for the sibling
`ImportSection` sub-project this one deliberately does *not* imitate).

## Purpose

`LearningSection` tells the landing page's third story, after Hero ("here is your
financial intelligence") and ImportSection ("here is how we create it"):
**"here is how it improves over time."** Today the section plays this out as four
cards that fade/border-transition into view on a plain `setTimeout` stagger
(`useStagedReveal`). The story itself is already right — first import guesses wrong,
you fix it, Finora records the fix, the next import is already right — it just isn't
*shown happening* the way ImportSection's beats are.

This is a reinforcement story, not a transformation story, and its animation budget
is scoped accordingly: sequencing, emphasis, and polish on the section's existing
four cards — not a new cinematic scene, no pin, no scroll-scrub, no particles/glow/3D.
ImportSection and Hero already own that register on this page; adding it a third time
would compete with them rather than complement them.

## Visual shape (validated via the visual companion)

Two shapes were mocked up live in-browser: (A) a single card morphing through all four
states in place, and (B) the current four-card layout with a line that actively draws
itself from card to card as each one lights up. **Option B was chosen** — it keeps all
four moments visible (closer to today, and to the section's existing narrative
captions), while making the causal thread between "you fixed it" and "it stayed
fixed" a visible connector instead of an implied one.

## Architecture

```
LearningSection.tsx
        |
        |  (container ref + data-target attrs on cards/connectors)
        v
useLearningTimeline.ts   <- the only file that imports gsap/ScrollTrigger
        |
        v
   GSAP timeline
        |
        +-- card 1
        +-- connector 1 (draw)
        +-- card 2
        +-- connector 2 (draw)
        +-- card 3
        +-- connector 3 (draw)
        +-- card 4 + confirmation highlight
```

No new presentational component files. The four cards are visually identical shapes
(unlike ImportSection's three distinct components — a document stack, a processing
core, an intelligence panel), and they exist for exactly one sequence on this one
section; splitting them out would be extraction without a second caller. The markup
stays inline in `LearningSection.tsx`; `useLearningTimeline.ts` is the sole owner of
GSAP, mirroring `useImportScrollTimeline.ts`'s "animation state lives in GSAP via
refs, not React state" rule so the timeline never triggers a React re-render.

## Trigger: play once, never reverse

Not scroll-scrubbed and not pinned — this is "enter viewport → play → stay played,"
the same intent `useStagedReveal`'s `IntersectionObserver` already expresses, just
driven by `ScrollTrigger` instead of a raw observer + four `setTimeout`s:

```ts
ScrollTrigger.create({
  trigger: containerRef.current,
  start: 'top 75%',
  once: true,
  animation: timeline,
});
```

`once: true` is the direct ScrollTrigger equivalent of "fire on enter, never
reverse on scroll back up" — it auto-kills the trigger after the first hit, which is
simpler than `toggleActions: 'play none none none'` for a trigger that will never
need `onLeave`/`onEnterBack`/`onLeaveBack` behavior at all. No `pin`, no `scrub`.

## Choreography

```
Card 1 (First import — Amazon, Uncategorized)
   |
   v  connector 1 draws left -> right
Card 2 (You fix it — Amazon, Shopping)
   |
   v  connector 2 draws
Card 3 (Finora records it — Pattern saved, "learning…")
   |
   v  connector 3 draws
Card 4 (Next import — Amazon, ✓ Pattern confirmed / Shopping) + confirmation pulse
```

Each card animates in with the same easing curve already used across the rest of the
page (`[0.16, 1, 0.3, 1]`), each connector's fill draws via a `scaleX` tween timed to
land as the next card starts. Total sequence duration: ~2.5–2.8s, close to today's
four-step timing but properly eased instead of four independent linear opacity steps.
The sequence ends with a brief glow/scale pulse on card 4's confirmation line to land
the "it stayed fixed" beat.

## Copy change: no fabricated confidence number

The final card currently reads "Amazon / ✓ Shopping". This design adds a middle line
communicating the reinforcement outcome more explicitly:

```
Amazon
✓ Pattern confirmed
Shopping
```

**"98% confidence" or any other literal percentage was considered and rejected.**
`docs/project-management/standards/marketing-claims-checklist.md` treats a hardcoded
number with nothing real behind it as a lie with a number attached — this page has
shipped and had to walk back exactly that shape of claim before (invented usage
counters). "Pattern confirmed" communicates the same unknown → recognized → reliable
arc without implying a calibrated confidence score the product doesn't expose
anywhere else. The pulse/glow lands on this line, not on the category tag.

## Accessibility

Unlike ImportSection's scene (marked `aria-hidden` because it is a decorative
illustration layered behind real copy elsewhere on the page), these four cards *are*
the section's real content — the captions, merchant names, and tags are not restated
anywhere else. They stay in the normal reading order throughout, with no
`aria-hidden` wrapper at any point.

`prefers-reduced-motion` renders the fully-settled final state immediately (all four
cards visible, all three connectors fully drawn, "Pattern confirmed" already shown) —
the same contract `useStagedReveal` already honors today, just implemented via
`useLearningTimeline`'s `enabled` flag (`false` under reduced motion) instead of the
old immediate `setStep(steps)`.

## Cleanup

`useLearningTimeline` follows the same `gsap.context()` / `.revert()` pattern as
`useImportScrollTimeline`, scoped to the container ref, torn down on unmount —
required for React 18 StrictMode's dev double-invoke, same as every other GSAP hook
on this page.

## Testing

Mirrors `useImportScrollTimeline.test.ts`: mock `gsap`/`gsap/ScrollTrigger`, assert
construction args (`trigger`, `start: 'top 75%'`, `once: true`) rather than animation
frame values — timeline-driven opacity/transform state at an arbitrary elapsed time is
exactly the kind of assertion that becomes fragile without exercising anything real.

New `LearningSection.test.tsx` (none exists today) asserts:
- all four cards' caption/merchant/tag text renders in the DOM
- text is not hidden behind `aria-hidden` at any point
- `prefers-reduced-motion` renders the final settled state (all four cards, "Pattern
  confirmed" visible) with the timeline hook's `enabled` flag `false`
- the container is not marked `aria-hidden`

`landing-claims.test.tsx` is left unchanged — this design does not add any new claim
class it doesn't already cover, and deliberately avoids the one new claim class
(a fabricated confidence percentage) that would have needed a new guard.

## Non-goals

- No particles, floating icons, AI glow, or 3D transforms — that register belongs to
  Hero and ImportSection; a third instance would compete with them, not add to them.
- No pin, no scroll-scrub — the section is not long enough to justify holding scroll
  hostage, and the "reinforcement" story doesn't need scroll position as a narrative
  axis the way ImportSection's transformation story did.
- No literal confidence percentage or other fabricated metric.
- No new presentational component files — the cards are not reusable product
  components, they are one storytelling sequence that belongs to this one section.
