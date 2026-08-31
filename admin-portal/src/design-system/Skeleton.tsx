import type { ReactNode } from 'react';

const PULSE = 'bg-surface animate-pulse motion-reduce:animate-none';

function SkeletonText({ width = 'w-3/5', className = '' }: { width?: string; className?: string }) {
  return <div aria-hidden="true" className={`h-3.5 rounded ${PULSE} ${width} ${className}`} />;
}

function SkeletonBlock({ className = '' }: { className?: string }) {
  return <div aria-hidden="true" className={`rounded-lg ${PULSE} ${className}`} />;
}

function SkeletonCircle({ size = 32, className = '' }: { size?: number; className?: string }) {
  return (
    <div
      aria-hidden="true"
      style={{ width: size, height: size }}
      className={`rounded-full flex-shrink-0 ${PULSE} ${className}`}
    />
  );
}

/** One list-row shape: title + subtitle line, a field-shaped block, a button-shaped block. */
function SkeletonRow({ className = '' }: { className?: string }) {
  return (
    <div className={`flex gap-3 items-center ${className}`}>
      <div className="min-w-0 flex-1 space-y-1.5">
        <SkeletonText width="w-3/5" />
        <SkeletonText width="w-1/4" className="h-2.5" />
      </div>
      <SkeletonBlock className="flex-shrink-0 w-40 h-9" />
      <SkeletonBlock className="flex-shrink-0 w-24 h-7" />
    </div>
  );
}

/** A `StatCard`/role-card shape: icon circle, label bar, value bar. */
function SkeletonCard({ className = '' }: { className?: string }) {
  return (
    <div className={`bg-card rounded-xl2 border border-border shadow-card p-5 space-y-3 ${className}`}>
      <SkeletonCircle size={28} />
      <SkeletonText width="w-1/2" className="h-2.5" />
      <SkeletonText width="w-3/4" className="h-5" />
    </div>
  );
}

/** One fixed-height chart placeholder (admin-portal's `PlatformActivityChart` etc.). */
function SkeletonChart({ className = 'h-48' }: { className?: string }) {
  const barHeights = [40, 65, 50, 80, 35, 60, 45];
  return (
    <div className={`flex items-end gap-2 px-2 ${className}`} aria-hidden="true">
      {barHeights.map((h, i) => (
        <div key={i} className={`flex-1 rounded-t ${PULSE}`} style={{ height: `${h}%` }} />
      ))}
    </div>
  );
}

/**
 * Wraps a group of skeleton pieces with the accessibility contract every loading region needs
 * (`aria-busy`, `role="status"`, `aria-live="polite"`).
 *
 * **Required, not automatic.** Every shape above (`Row`/`Card`/`Chart`/...) renders
 * `aria-hidden="true"` on itself -- deliberately, since a list of 5 skeleton rows must announce
 * as one loading region, not five. That means the accessibility contract only exists where a
 * `Region` wraps the shapes; a page that renders `Skeleton.Row` (or any other shape) without one
 * is *strictly worse* for screen-reader users than the plain-text `"Loading…"` it's replacing --
 * that text was readable content by default, where a bare `aria-hidden` skeleton announces
 * nothing at all. Every per-page skeleton composition in the animation-polish roadmap (see
 * docs/proposals/animation-polish-roadmap-proposal.md §2/§3/§4) must be wrapped in exactly one
 * `Region` per logical loading area -- not one per shape, and not skipped. Structurally identical
 * to `frontend/src/design-system/Skeleton.tsx` -- see that file's doc comments and the roadmap's
 * anti-drift rule.
 */
function SkeletonRegion({
  children,
  label = 'Loading',
  className = '',
}: {
  children: ReactNode;
  label?: string;
  className?: string;
}) {
  return (
    <div role="status" aria-busy="true" aria-live="polite" className={className}>
      <span className="sr-only">{label}</span>
      {children}
    </div>
  );
}

export const Skeleton = {
  Text: SkeletonText,
  Block: SkeletonBlock,
  Circle: SkeletonCircle,
  Row: SkeletonRow,
  Card: SkeletonCard,
  Chart: SkeletonChart,
  Region: SkeletonRegion,
};
