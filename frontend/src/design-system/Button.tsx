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
  /** Swaps in a spinner in place of the button's own leading icon slot and disables the button --
   *  the pattern CategoryCreateEditPanel's Save button used inline before this component existed
   *  (PR #682), now shared. */
  loading?: boolean;
  /** Opt-in `whileHover={{ scale: 1.02 }}`, deliberately not the default -- see
   *  docs/proposals/animation-polish-roadmap-proposal.md §1 for why hover-scale is reserved for a
   *  handful of prominent actions (FAB, Quick Actions, primary CTAs) rather than every button. */
  hoverScale?: boolean;
  children: ReactNode;
}

/**
 * The one button primitive every page in the animation-polish roadmap migrates to. Wraps
 * `motion.button` rather than a plain `<button>` so tap (and opt-in hover) feedback is built in
 * instead of re-added per call site. `whileTap` is universal and matches the convention already
 * established by AskOnceCard/MerchantGroupReviewCard/CategoryCreateEditPanel; `prefers-reduced-motion`
 * disables both motion props entirely rather than just shrinking them.
 *
 * Deliberately excludes icon-only/square buttons -- see `IconButton` for that shape. Keeping the
 * two separate is what stops this component from accumulating an `iconOnly`/`pagination`/`compact`
 * prop for every page's row-action button as the migration reaches more pages.
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
      aria-busy={loading}
      className={`inline-flex items-center justify-center gap-1.5 rounded-lg font-semibold transition-colors duration-200 ease-out disabled:opacity-50 ${VARIANT_CLASSES[variant]} ${SIZE_CLASSES[size]} ${className}`}
      {...rest}
    >
      {loading && <Loader2 size={13} className="animate-spin" aria-hidden="true" />}
      {children}
      {/* The spinner is aria-hidden, so without this the pending state is conveyed to assistive
          tech only as "disabled" -- strictly less than the "Adding…"/"Saving…" label swaps this
          component replaced, which were real readable text. The leading comma is deliberate: it
          separates the state from the label in the computed accessible name ("Add, loading")
          rather than running the two words together. */}
      {loading && <span className="sr-only">, loading</span>}
    </motion.button>
  );
});
