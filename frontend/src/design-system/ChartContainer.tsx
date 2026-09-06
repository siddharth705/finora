import type { ReactNode } from 'react';
import { useDelayedLoading } from '../hooks/useDelayedLoading';
import { Skeleton } from './Skeleton';

/**
 * Dashboard and Investments each hand-built an independent Chart.js `options` object -- same
 * font, same tooltip styling, retyped twice. `baseChartOptions` is what every `<Line>`/`<Doughnut>`
 * call site should spread into its own options (this deliberately does NOT wrap Chart.js itself --
 * see design-system-proposal.md §6 on why).
 */
export const baseChartOptions = {
  responsive: true,
  maintainAspectRatio: false,
  plugins: {
    legend: { position: 'bottom' as const, labels: { boxWidth: 8, boxHeight: 8, usePointStyle: true } },
  },
};

/** Fixed-height wrapper around a chart, with a shared loading/empty treatment so neither has to be
 *  reinvented per page. Pass the real chart (already spreading `baseChartOptions`) as children.
 *
 *  `loading` renders `Skeleton.Chart` rather than centered text, but only past
 *  `useDelayedLoading`'s show-after window -- a chart whose data resolves quickly renders nothing
 *  during that window rather than flashing a skeleton in and out. Applied here, once, so every
 *  `ChartContainer` consumer gets it automatically instead of each page re-deriving its own
 *  delayed-skeleton timing. */
export function ChartContainer({
  height = 256, loading, isEmpty, emptyState, loadingLabel = 'Loading…', children,
}: {
  height?: number; loading?: boolean; isEmpty?: boolean; emptyState?: ReactNode; loadingLabel?: string; children: ReactNode;
}) {
  const showSkeleton = useDelayedLoading(!!loading);
  if (loading) {
    // The accessible label (Skeleton.Region's sr-only span) renders immediately -- only the
    // visual skeleton shape is delayed. A screen-reader user shouldn't wait out the same
    // flash-prevention window a sighted user benefits from; that window exists to avoid a visual
    // flicker, which doesn't apply to an announcement.
    return (
      <div style={{ height }}>
        <Skeleton.Region label={loadingLabel} className="h-full">
          {showSkeleton && <Skeleton.Chart className="h-full" />}
        </Skeleton.Region>
      </div>
    );
  }
  if (isEmpty) {
    return (
      <div className="flex items-center justify-center" style={{ height }}>
        {emptyState}
      </div>
    );
  }
  return <div style={{ height }}>{children}</div>;
}
