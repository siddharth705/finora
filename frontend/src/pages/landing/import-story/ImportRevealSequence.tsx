import { useReducedMotion } from 'framer-motion';
import { useStagedReveal } from '../primitives';
import { DocumentStack } from './DocumentStack';
import { ProcessingCore } from './ProcessingCore';
import { IntelligencePanel } from './IntelligencePanel';

const STEPS = [DocumentStack, ProcessingCore, IntelligencePanel] as const;

/**
 * Mobile / reduced-motion fallback for the ImportSection scroll story: the same three beats as
 * ImportScrollStory's pinned/scrubbed sequence, but reveal-once via useStagedReveal (no
 * ScrollTrigger, no pinning -- see the design spec's rationale on why a smaller pin is worse UX
 * than no pin at all). prefers-reduced-motion skips straight to the final IntelligencePanel
 * state, same "safe default" contract useStagedReveal already guarantees. A fresh mount before
 * useStagedReveal's own IntersectionObserver has fired starts at step 0 -- this component's own
 * safe default for that state is the finished panel (not the first scattered beat), matching the
 * "never show a permanently/initially broken state" principle every other reveal component here
 * follows.
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
