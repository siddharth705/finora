import type { ReactNode } from 'react';

const PADDING = {
  none: '',
  sm: 'p-4',
  md: 'p-5',
  lg: 'p-6',
} as const;

/**
 * The one card shell every page hand-rolled its own slightly-different version of --
 * `bg-card rounded-xl2 shadow-card border border-border` with a padding value nobody agreed on.
 * `padding="none"` is for cards with their own internal header/body padding split (e.g. a card
 * with a bordered header row and a differently-padded body) rather than one uniform inset.
 */
export function FinoraCard({
  children, padding = 'md', className = '',
}: {
  children: ReactNode; padding?: keyof typeof PADDING; className?: string;
}) {
  return (
    <div className={`bg-card rounded-xl2 shadow-card border border-border ${PADDING[padding]} ${className}`}>
      {children}
    </div>
  );
}
