import { useReducedMotion } from 'framer-motion';
import { useStagedReveal } from '../primitives';
import { StatementCard } from './StatementCard';
import { IntelligencePanel } from './IntelligencePanel';

/**
 * The ImportSection scene: one realistic statement, scanned, with the categorized result card
 * emerging from its corner -- not three independent illustrations swapped whole. Chosen via the
 * brainstorming visual companion after real feedback that the earlier document-icons ->
 * glowing-square -> disconnected-card version felt plain, generic and not premium.
 *
 * Two transitions (statement at rest -> scanning -> settled with the ledger card overlapping),
 * revealed once via useStagedReveal as the section scrolls into view. No pinning, no scroll-scrub
 * -- this used to be mobile/reduced-motion-only, with desktop getting a GSAP-ScrollTrigger
 * pinned/scrubbed version instead; that was dropped after separate feedback that pinning the page
 * for ~2.5 screen-heights of scroll to watch three beats felt bad regardless of pin distance. This
 * is the only version, for every visitor. prefers-reduced-motion skips straight to the settled
 * state with the ledger card already showing -- that visitor never sees the scan animation
 * either way, so starting on the finished composition is the honest choice for them specifically.
 */
export function ImportRevealSequence() {
  const prefersReducedMotion = useReducedMotion();
  const { ref, step } = useStagedReveal(2, 700);
  const scanning = !prefersReducedMotion && step === 1;
  const showLedger = prefersReducedMotion || step >= 2;

  return (
    <div ref={ref} aria-hidden="true" className="relative w-full h-64 flex items-center justify-center">
      <div className="relative w-[220px]">
        <StatementCard scanning={scanning} />
        {showLedger ? (
          <div className="absolute -right-4 -bottom-3">
            <IntelligencePanel />
          </div>
        ) : null}
      </div>
    </div>
  );
}
