import { forwardRef, type ReactNode } from 'react';
import { motion, useReducedMotion, type HTMLMotionProps } from 'framer-motion';
import { Loader2 } from 'lucide-react';

type IconButtonVariant = 'default' | 'danger';
type IconButtonSize = 'sm' | 'md';

const VARIANT_CLASSES: Record<IconButtonVariant, string> = {
  default: 'text-muted hover:text-ink hover:bg-bg',
  // Always-red at rest, not muted-until-hover -- matches Button's own danger variant
  // (`text-danger` unconditional). See frontend/src/design-system/IconButton.tsx's identical
  // comment: this was a real inconsistency between the two Phase 0 primitives, caught only once
  // Phase 2 (frontend/Ledger.tsx) actually used `variant="danger"` for the first time.
  danger: 'text-danger hover:bg-danger-bg',
};

const SIZE_CLASSES: Record<IconButtonSize, string> = {
  sm: 'w-7 h-7',
  md: 'w-8 h-8',
};

interface IconButtonProps extends Omit<HTMLMotionProps<'button'>, 'children' | 'aria-label'> {
  icon: ReactNode;
  /** Required, not optional -- an icon with no accessible name is a type error here, not a
   *  review-time catch. */
  'aria-label': string;
  variant?: IconButtonVariant;
  size?: IconButtonSize;
  loading?: boolean;
}

/**
 * Square, icon-only button. Structurally identical to `frontend/src/design-system/IconButton.tsx`
 * (see that file's doc comment and the roadmap's anti-drift rule). A separate component from
 * `Button` so `Button` doesn't accumulate an `iconOnly`/`pagination`/`compact` prop surface as
 * more admin-portal pages migrate to it.
 */
export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(function IconButton(
  { icon, variant = 'default', size = 'md', loading = false, disabled, className = '', type = 'button', 'aria-label': ariaLabel, ...rest },
  ref
) {
  const prefersReducedMotion = useReducedMotion();

  return (
    <motion.button
      ref={ref}
      type={type}
      whileTap={prefersReducedMotion ? undefined : { scale: 0.9 }}
      disabled={disabled || loading}
      // Suffixed onto the label rather than added as sr-only content the way Button does it:
      // `aria-label` REPLACES an element's contents for accessible-name purposes, so an sr-only
      // span inside this button would never be announced at all. Kept identical to the user-facing
      // app's IconButton per §1's anti-drift rule.
      aria-label={loading ? `${ariaLabel}, loading` : ariaLabel}
      aria-busy={loading}
      className={`inline-flex items-center justify-center rounded-lg border border-border transition-colors duration-200 ease-out disabled:opacity-40 disabled:hover:bg-transparent ${SIZE_CLASSES[size]} ${VARIANT_CLASSES[variant]} ${className}`}
      {...rest}
    >
      {loading ? <Loader2 size={14} className="animate-spin" aria-hidden="true" /> : icon}
    </motion.button>
  );
});
