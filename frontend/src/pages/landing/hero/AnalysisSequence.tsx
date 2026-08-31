import { useStagedReveal } from '../primitives';
import { heroIntelligence } from '../landing-config';
import { HealthScoreRing } from './HealthScoreRing';
import { IntelligenceScan } from './IntelligenceScan';

// Shared with HealthScoreRing's intervalMs prop below -- one constant so the two can never drift
// apart. The ring's continuous fill duration is derived from this value; if this changes without
// updating the prop passed to HealthScoreRing, the ring stops landing on 84 exactly when the
// checklist's last item ticks.
const STEP_INTERVAL_MS = 550;

/**
 * Pairs the score ring with the "Analyzing your finances…" checklist under ONE shared
 * staged-reveal counter, so the ring fills continuously in sync with the checklist rather than
 * sitting empty until it finishes and then jumping straight to 84.
 *
 * Before this, in order: (1) HealthScoreRing and IntelligenceScan each ran their own independent
 * useStagedReveal/IntersectionObserver, both firing off the same scroll-into-view moment but on
 * unrelated clocks (ring: one 1200ms draw; checklist: 4 steps x 550ms = 2200ms) -- the ring
 * visibly finished nearly a second before the last checkmark landed. (2) A fix made the ring wait
 * for the checklist to fully finish before drawing at all -- closer, but a single all-or-nothing
 * jump to 84. (3) A fix made the ring jump in four discrete steps, one per checkmark -- synced,
 * but reported as not looking natural (a value that visibly jumps 4 times isn't "filling").
 *
 * This version: the ring starts its ONE continuous fill the moment the checklist's first item
 * ticks (step reaches 1) and reaches 84 in a single smooth motion exactly as the last item ticks
 * (step reaches heroIntelligence.steps.length) -- see HealthScoreRing's own note on how its fill
 * duration is derived to land there precisely.
 */
export function AnalysisSequence() {
  const { ref, step } = useStagedReveal(heroIntelligence.steps.length, STEP_INTERVAL_MS);

  return (
    <div ref={ref} className="flex flex-wrap items-center justify-center gap-6">
      <HealthScoreRing step={step} totalSteps={heroIntelligence.steps.length} intervalMs={STEP_INTERVAL_MS} />
      <IntelligenceScan step={step} />
    </div>
  );
}
