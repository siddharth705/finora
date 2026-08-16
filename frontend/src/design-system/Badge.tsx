const TONE = {
  primary: 'bg-primary/15 text-primary',
  neutral: 'bg-bg text-muted border border-border',
} as const;

/**
 * Extracted from Dashboard's one-off "Beta" pill and its recurring-item cadence pill (both the
 * same primary-tinted style) -- the only badges anywhere in the app today. "primary" names the
 * tone, not the word "Beta" -- this is also what Recurring's "Monthly"/"Weekly" labels use.
 * Deliberately just a visual primitive: no tier/entitlement logic. PR4 (Premium Layer, gated on
 * D-7) is what decides what a "Plus"/"Premium" tone means and whether it's shown at all.
 */
export function Badge({ tone = 'primary', label, className = '' }: { tone?: keyof typeof TONE; label: string; className?: string }) {
  return (
    <span className={`text-[10px] uppercase font-semibold px-1.5 py-0.5 rounded ${TONE[tone]} ${className}`}>
      {label}
    </span>
  );
}
