import { forwardRef, type ReactNode } from 'react';
import { motion, useReducedMotion, type HTMLMotionProps } from 'framer-motion';
import { Loader2 } from 'lucide-react';

type IconButtonVariant = 'default' | 'danger';
type IconButtonSize = 'sm' | 'md';

const VARIANT_CLASSES: Record<IconButtonVariant, string> = {
  default: 'text-muted hover:text-ink hover:bg-bg',
  // Always-red at rest, not muted-until-hover -- matches Button's own danger variant
  // (`text-danger` unconditional) and Ledger.tsx's original delete-row button this variant was
  // first actually used to migrate. Bug fix: this originally read `text-muted hover:text-danger`,
  // which silently changed a destructive action's at-rest color the moment it got its first real
  // caller -- an inconsistency between this component and Button's own danger variant that no
  // Phase 0 test caught, since Phase 0 shipped before anything actually used `variant="danger"`.
  danger: 'text-danger hover:bg-danger-bg',
};

const SIZE_CLASSES: Record<IconButtonSize, string> = {
  sm: 'w-7 h-7',
  md: 'w-8 h-8',
};

interface IconButtonProps extends Omit<HTMLMotionProps<'button'>, 'children' | 'aria-label'> {
  icon: ReactNode;
  /** Required, not optional: an icon with no accessible name is the single easiest a11y bug to
   *  introduce with this component, so it's a type error to omit rather than a review-time catch. */
  'aria-label': string;
  variant?: IconButtonVariant;
  size?: IconButtonSize;
  loading?: boolean;
}

/**
 * Square, icon-only button -- the row-action/pagination/refresh shape already common on Ledger,
 * Settings, and nearly every admin-portal list page. A separate component from `Button` on
 * purpose: folding this into `Button` via an `iconOnly` prop is how that component would end up
 * accumulating `pagination`/`compact`/`toolbar`/`ghost` variants as more pages migrate to it. See
 * docs/proposals/animation-polish-roadmap-proposal.md §1.
 *
 * Keyboard-accessible for free from being a real `<button>` under the hood -- no custom
 * `role`/`tabIndex` wiring needed.
 */
export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(function IconButton(
  { icon, variant = 'default', size = 'md', loading = false, disabled, className = '', type = 'button', ...rest },
  ref
) {
  const prefersReducedMotion = useReducedMotion();

  return (
    <motion.button
      ref={ref}
      type={type}
      whileTap={prefersReducedMotion ? undefined : { scale: 0.9 }}
      disabled={disabled || loading}
      className={`inline-flex items-center justify-center rounded-lg border border-border transition-colors duration-200 ease-out disabled:opacity-40 disabled:hover:bg-transparent ${SIZE_CLASSES[size]} ${VARIANT_CLASSES[variant]} ${className}`}
      {...rest}
    >
      {loading ? <Loader2 size={14} className="animate-spin" aria-hidden="true" /> : icon}
    </motion.button>
  );
});
