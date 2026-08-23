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
 * was tuned to. This is now the only version, for every visitor. prefers-reduced-motion skips
 * straight to the final IntelligencePanel state, same "safe default" contract useStagedReveal
 * already guarantees. A fresh mount before useStagedReveal's own IntersectionObserver has fired
 * starts at step 0 -- this component's own safe default for that state is the finished panel (not
 * the first scattered beat), matching the "never show a permanently/initially broken state"
 * principle every other reveal component here follows.
 */
export function ImportRevealSequence() {
  const prefersReducedMotion = useReducedMotion();
  const { ref, step } = useStagedReveal(STEPS.length);
  const Current = prefersReducedMotion || step === 0 ? IntelligencePanel : STEPS[step - 1];

  return (
    <div ref={ref} aria-hidden="true" className="relative w-full h-64 flex items-center justify-center">
      <Current />
    </div>
  );
}
