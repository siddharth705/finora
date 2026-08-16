import type { ReactNode } from 'react';

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
 *  reinvented per page. Pass the real chart (already spreading `baseChartOptions`) as children. */
export function ChartContainer({
  height = 256, loading, isEmpty, emptyState, loadingLabel = 'Loading…', children,
}: {
  height?: number; loading?: boolean; isEmpty?: boolean; emptyState?: ReactNode; loadingLabel?: string; children: ReactNode;
}) {
  if (loading) {
    return (
      <div className="flex items-center justify-center" style={{ height }}>
        <p className="text-sm text-muted">{loadingLabel}</p>
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
