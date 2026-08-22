import { motion, useReducedMotion } from 'framer-motion';
import { heroBadges } from '../landing-config';

// Anchored to the card's exact edge (right: 100% / left: 100%) plus a fixed px margin, rather
// than a percentage-of-width offset. A badge is ~120-150px wide; a small percentage offset (e.g.
// left: -6%) scales with the CARD's width, not the BADGE's, so on a wide viewport the offset ends
// up smaller than the badge itself and most of the badge lands ON the card instead of beside it
// -- confirmed in production (badge bounding boxes overlapped the dashboard's income figures and
// trend chart by ~80-90px). Edge-anchoring guarantees the badge starts exactly at the card
// boundary and grows outward, so it can never overlap the card regardless of viewport width.
const POSITIONS = [
  { top: '8%', side: 'left', offset: 16, hideOnMobile: false },
  { top: '68%', side: 'left', offset: 16, hideOnMobile: true },
  { top: '4%', side: 'right', offset: 16, hideOnMobile: false },
  { top: '46%', side: 'right', offset: 16, hideOnMobile: true },
  { top: '82%', side: 'right', offset: 16, hideOnMobile: false },
] as const;

// Fixed, not Math.random() -- deterministic delays keep screenshots and visual-regression runs
// stable and avoid hydration mismatches. See the hero design spec's Global Constraints.
const DELAYS_S = [0, 1.2, 2.4, 3.6, 0.6] as const;

/** The small floating data pills around the dashboard preview. */
export function FloatingBadges() {
  const prefersReducedMotion = useReducedMotion();

  return (
    <>
      {heroBadges.map((badge, i) => {
        const position = POSITIONS[i % POSITIONS.length];
        const delay = DELAYS_S[i % DELAYS_S.length];
        const edgeStyle =
          position.side === 'left'
            ? { right: '100%', marginRight: position.offset }
            : { left: '100%', marginLeft: position.offset };
        return (
          <motion.div
            key={badge.label}
            className={`absolute rounded-full border border-white/15 bg-white/10 backdrop-blur-md px-3 py-1.5 text-[11px] text-white/85 whitespace-nowrap pointer-events-none ${
              position.hideOnMobile ? 'hidden md:block' : ''
            }`}
            style={{ top: position.top, ...edgeStyle }}
            initial={prefersReducedMotion ? false : { opacity: 0, y: 12 }}
            animate={
              prefersReducedMotion
                ? { opacity: 1, y: 0 }
                : { opacity: [0, 1, 1, 0.9], y: [12, 0, -6, 0] }
            }
            transition={
              prefersReducedMotion
                ? { duration: 0 }
                : { duration: 6, repeat: Infinity, repeatType: 'mirror', delay, ease: 'easeInOut' }
            }
          >
            {badge.label}
          </motion.div>
        );
      })}
    </>
  );
}
