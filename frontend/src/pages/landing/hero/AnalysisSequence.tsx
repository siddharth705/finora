import { useStagedReveal } from '../primitives';
import { heroIntelligence } from '../landing-config';
import { HealthScoreRing } from './HealthScoreRing';
import { IntelligenceScan } from './IntelligenceScan';

/**
 * Pairs the score ring with the "Analyzing your finances…" checklist under ONE shared
 * staged-reveal counter, so the ring only draws to its final value once the last checklist item
 * has gone green -- "84" is the CONCLUSION of the checklist finishing, not a coincidence that
 * happens to land around the same time.
 *
 * Before this: HealthScoreRing and IntelligenceScan each ran their own independent
 * useStagedReveal/IntersectionObserver, both firing off the same scroll-into-view moment but on
 * unrelated clocks (ring: one 1200ms draw; checklist: 4 steps x 550ms = 2200ms) -- the ring
 * visibly finished nearly a second before the last checkmark landed, undercutting the "we
 * calculated this from what we just found" story the two are supposed to tell together.
 */
export function AnalysisSequence() {
  const { ref, step } = useStagedReveal(heroIntelligence.steps.length, 550);
  const allStepsComplete = step >= heroIntelligence.steps.length;

  return (
    <div ref={ref} className="flex flex-wrap items-center justify-center gap-6">
      <HealthScoreRing drawn={allStepsComplete} />
      <IntelligenceScan step={step} />
    </div>
  );
}
