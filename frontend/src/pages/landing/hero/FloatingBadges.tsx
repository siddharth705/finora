import { motion, useReducedMotion } from 'framer-motion';
import { heroBadges } from '../landing-config';

const POSITIONS = [
  { top: '8%', left: '-6%', hideOnMobile: false },
  { top: '68%', left: '-4%', hideOnMobile: true },
  { top: '4%', left: '78%', hideOnMobile: false },
  { top: '46%', left: '86%', hideOnMobile: true },
  { top: '82%', left: '70%', hideOnMobile: false },
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
        return (
          <motion.div
            key={badge.label}
            className={`absolute rounded-full border border-white/15 bg-white/10 backdrop-blur-md px-3 py-1.5 text-[11px] text-white/85 whitespace-nowrap pointer-events-none ${
              position.hideOnMobile ? 'hidden md:block' : ''
            }`}
            style={{ top: position.top, left: position.left }}
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
