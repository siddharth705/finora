import { useReducedMotion } from 'framer-motion';
import { useStagedReveal } from '../primitives';
import { DocumentStack } from './DocumentStack';
import { ProcessingCore } from './ProcessingCore';
import { IntelligencePanel } from './IntelligencePanel';

const STEPS = [DocumentStack, ProcessingCore, IntelligencePanel] as const;

/**
 * The ImportSection scene: documents -> processing -> insights, revealed once via
 * useStagedReveal as the section scrolls into view. No pinning, no scroll-scrub -- this used to
 * be mobile/reduced-motion-only, with desktop getting a GSAP-ScrollTrigger pinned/scrubbed
 * version instead; that was dropped after real user feedback that pinning the page for ~2.5
 * screen-heights of scroll to watch three beats felt bad regardless of how short the pin distance
 * was tuned to. This is now the only version, for every visitor.
 *
 * step maps directly to STEPS by index (clamped), not step-1: step 0 (before the observer has
 * fired at all) shows STEPS[0] -- the opening beat, documents freshly scattered -- so there is
 * something to visibly progress FROM once the observer does fire. An earlier version showed the
 * FINISHED panel at step 0 (reasoning: never show a permanently-stuck-mid-sequence state if the
 * observer never fires) and only counted through the earlier beats afterwards -- which meant the
 * sequence a real visitor actually saw was finished -> documents -> processing -> finished, a
 * regression reported directly: the section looked like nothing was animating, because its
 * default state already WAS the end state. prefers-reduced-motion still skips straight to the
 * final IntelligencePanel state -- that visitor never sees motion either way, so starting on the
 * finished state instead of an animation's first frame is the honest choice for them specifically.
 */
export function ImportRevealSequence() {
  const prefersReducedMotion = useReducedMotion();
  const { ref, step } = useStagedReveal(STEPS.length);
  const Current = prefersReducedMotion ? IntelligencePanel : STEPS[Math.min(step, STEPS.length - 1)];

  return (
    <div ref={ref} aria-hidden="true" className="relative w-full h-64 flex items-center justify-center">
      <Current />
    </div>
  );
}
