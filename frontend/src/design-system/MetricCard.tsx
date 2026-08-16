import type { LucideIcon } from 'lucide-react';
import { FinoraCard } from './FinoraCard';

/**
 * Dashboard's KPI cards (`text-2xl font-bold text-ink`) and Reports'/Investments' own metric
 * tiles (`text-xl font-semibold`, a raw `text-gray-500` label) were two independent, incompatible
 * type scales for the same "here's a number" need. This is Dashboard's scale, since it's the
 * richer of the two (icon badge + delta line) rather than the older pages' plainer one.
 *
 * `delta`/`deltaLabel` are both optional and independent: a KPI with no delta *concept* at all
 * (e.g. Reports' single-month snapshot has no "vs last month" baseline) passes neither and gets
 * no delta line. A KPI that DOES have a delta concept but no value available yet (no prior period
 * to compare against) passes `deltaLabel` alone and gets a muted "—" placeholder instead of the
 * line silently vanishing.
 */
export function MetricCard({
  label, value, icon: Icon, iconBg, iconColor, valueColor,
  delta, deltaLabel, invertDelta,
}: {
  label: string; value: string; icon: LucideIcon; iconBg: string; iconColor: string; valueColor?: string;
  delta?: number | null; deltaLabel?: string; invertDelta?: boolean;
}) {
  return (
    <FinoraCard>
      <div className="flex items-start justify-between mb-3">
        <p className="text-sm text-muted">{label}</p>
        <div className={`w-9 h-9 rounded-full ${iconBg} flex items-center justify-center flex-shrink-0`}>
          <Icon size={17} className={iconColor} />
        </div>
      </div>
      <p className={`text-2xl font-bold mb-1 ${valueColor ?? 'text-ink'}`}>{value}</p>
      {deltaLabel && (
        delta !== null && delta !== undefined ? (
          <p className={`text-xs font-medium ${(invertDelta ? delta < 0 : delta >= 0) ? 'text-success' : 'text-danger'}`}>
            {delta >= 0 ? '▲' : '▼'} {Math.abs(delta).toFixed(1)}% {deltaLabel}
          </p>
        ) : (
          <p className="text-xs text-muted">— {deltaLabel}</p>
        )
      )}
    </FinoraCard>
  );
}
