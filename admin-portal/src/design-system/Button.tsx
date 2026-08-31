import { forwardRef, type ReactNode } from 'react';
import { motion, useReducedMotion, type HTMLMotionProps } from 'framer-motion';
import { Loader2 } from 'lucide-react';

type ButtonVariant = 'primary' | 'secondary' | 'danger';
type ButtonSize = 'sm' | 'md';

const VARIANT_CLASSES: Record<ButtonVariant, string> = {
  primary: 'bg-primary text-on-primary hover:bg-primary-dark',
  secondary: 'border border-border text-ink hover:bg-bg',
  danger: 'border border-danger text-danger hover:bg-danger-bg',
};

const SIZE_CLASSES: Record<ButtonSize, string> = {
  sm: 'px-3 py-1.5 text-xs',
  md: 'px-4 py-2 text-xs',
};

interface ButtonProps extends Omit<HTMLMotionProps<'button'>, 'children'> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  /** Swaps in a spinner in place of the button's own leading icon slot and disables the button. */
  loading?: boolean;
  /** Opt-in `whileHover={{ scale: 1.02 }}`, deliberately not the default -- see
   *  docs/proposals/animation-polish-roadmap-proposal.md §1. */
  hoverScale?: boolean;
  children: ReactNode;
}

/**
 * The one button primitive admin-portal migrates to as part of the animation-polish roadmap.
 * Structurally identical to `frontend/src/design-system/Button.tsx` on purpose (the anti-drift
 * rule in the roadmap doc: the two are separate components in separate apps, kept in sync by
 * convention rather than a shared package) -- a change to one should prompt checking the other.
 *
 * Deliberately excludes icon-only/square buttons -- see `IconButton` for that shape.
 */
export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    variant = 'primary',
    size = 'md',
    loading = false,
    hoverScale = false,
    disabled,
    className = '',
    type = 'button',
    children,
    ...rest
  },
  ref
) {
  const prefersReducedMotion = useReducedMotion();

  return (
    <motion.button
      ref={ref}
      type={type}
      whileTap={prefersReducedMotion ? undefined : { scale: 0.96 }}
      whileHover={hoverScale && !prefersReducedMotion ? { scale: 1.02 } : undefined}
      disabled={disabled || loading}
      className={`inline-flex items-center justify-center gap-1.5 rounded-lg font-semibold transition-colors duration-200 ease-out disabled:opacity-50 ${VARIANT_CLASSES[variant]} ${SIZE_CLASSES[size]} ${className}`}
      {...rest}
    >
      {loading && <Loader2 size={13} className="animate-spin" aria-hidden="true" />}
      {children}
    </motion.button>
  );
});
