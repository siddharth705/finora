import { useStagedReveal } from '../primitives';
import { heroIntelligence } from '../landing-config';
import { HealthScoreRing } from './HealthScoreRing';
import { IntelligenceScan } from './IntelligenceScan';

/**
 * Pairs the score ring with the "Analyzing your finances…" checklist under ONE shared
 * staged-reveal counter, so the ring fills IN STEP with the checklist rather than sitting empty
 * until it finishes and then jumping straight to 84 -- each checkmark visibly moves the ring
 * partway there, so 84 reads as something the checklist built up to.
 *
 * Before this: HealthScoreRing and IntelligenceScan each ran their own independent
 * useStagedReveal/IntersectionObserver, both firing off the same scroll-into-view moment but on
 * unrelated clocks (ring: one 1200ms draw; checklist: 4 steps x 550ms = 2200ms) -- the ring
 * visibly finished nearly a second before the last checkmark landed, undercutting the "we
 * calculated this from what we just found" story the two are supposed to tell together. A first
 * fix made the ring wait for the checklist to fully finish before drawing at all -- closer, but
 * still a single all-or-nothing jump to 84 rather than a fill that tracks the checklist's actual
 * progress.
 */
export function AnalysisSequence() {
  const { ref, step } = useStagedReveal(heroIntelligence.steps.length, 550);

  return (
    <div ref={ref} className="flex flex-wrap items-center justify-center gap-6">
      <HealthScoreRing step={step} totalSteps={heroIntelligence.steps.length} />
      <IntelligenceScan step={step} />
    </div>
  );
}
