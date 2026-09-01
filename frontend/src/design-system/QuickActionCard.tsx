import { Link } from 'react-router-dom';
import { motion, useReducedMotion } from 'framer-motion';
import type { LucideIcon } from 'lucide-react';

const className = 'flex flex-col items-center gap-1.5 text-center p-3 rounded-lg bg-bg hover:bg-primary-light text-ink hover:text-primary transition-colors';

// react-router's Link isn't a motion component by default -- motion.create() (framer-motion's
// current API for this, superseding the older motion(Component) call syntax) wraps it so the
// same whileHover/whileTap treatment Dashboard's floating action button uses can apply here too.
const MotionLink = motion.create(Link);

/**
 * One tile in Dashboard's Quick Actions grid -- a `Link` when `to` is given, a `button` otherwise.
 * One of the first two hoverScale adopters named in the animation-polish roadmap (the other being
 * the Dashboard FAB) -- baked in here rather than behind a prop, since every tile in this grid is
 * a prominent, deliberately-clicked action, not a secondary/row-level one.
 */
export function QuickActionCard({
  icon: Icon, label, to, onClick,
}: {
  icon: LucideIcon; label: string; to?: string; onClick?: () => void;
}) {
  const prefersReducedMotion = useReducedMotion();
  const motionProps = {
    whileTap: prefersReducedMotion ? undefined : { scale: 0.96 },
    whileHover: prefersReducedMotion ? undefined : { scale: 1.02 },
  };
  const body = (
    <>
      <Icon size={18} />
      <span className="text-[11px] font-medium leading-tight">{label}</span>
    </>
  );
  return to
    ? <MotionLink to={to} className={className} {...motionProps}>{body}</MotionLink>
    : <motion.button type="button" onClick={onClick} className={className} {...motionProps}>{body}</motion.button>;
}
