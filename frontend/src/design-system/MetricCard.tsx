import { useState } from 'react';
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
 *
 * `gateReasonText`: some of those "—" placeholders aren't just "no prior data" -- they're
 * DashboardService deliberately withholding a delta it could otherwise compute, because the prior
 * month is too thin to trust (see DashboardSummaryDto.comparisonGateReason). Passing a reason
 * turns the "—" into a "Why?" disclosure, same inline pattern as Import.tsx's product-detection
 * evidence toggle, instead of a user having to wonder why last month's line just disappeared.
 *
 * `moverLines`: the flip side -- a real, non-gated delta ("expenses up 12%") is still a bare
 * number with no explanation of what actually moved. Passing pre-formatted lines (e.g. "Dining
 * ₹8,000 vs ₹5,000 (+60%)", from DashboardSummaryDto.expenseCategoryMovers) gets the SAME "Why?"
 * disclosure on the delta line itself, so both "why is this hidden" and "why did this change"
 * share one interaction pattern instead of two different affordances on the same card.
 */
export function MetricCard({
  label, value, icon: Icon, iconBg, iconColor, valueColor,
  delta, deltaLabel, invertDelta, gateReasonText, moverLines, variant = 'default',
}: {
  label: string; value: string; icon: LucideIcon; iconBg: string; iconColor: string; valueColor?: string;
  delta?: number | null; deltaLabel?: string; invertDelta?: boolean; gateReasonText?: string | null;
  moverLines?: string[]; variant?: 'default' | 'elevated';
}) {
  const [showReason, setShowReason] = useState(false);
  const hasDelta = delta !== null && delta !== undefined;
  const hasMovers = !!moverLines && moverLines.length > 0;
  const positive = hasDelta && (invertDelta ? delta! < 0 : delta! >= 0);

  // Dashboard's KPI row only, per the reviewed redesign: a uniform graphite/cream icon medallion
  // and a pill-shaped delta instead of per-metric bright icon colors and plain colored text.
  // Reports/Investments/Budgets never pass this, so their MetricCard calls (the `return` below
  // this block) are unchanged.
  if (variant === 'elevated') {
    return (
      <FinoraCard className="transition-[transform,box-shadow] duration-150 hover:-translate-y-0.5 hover:shadow-soft">
        <div className="flex items-start justify-between mb-4">
          <p className="text-sm font-semibold text-muted">{label}</p>
          <div className="w-10 h-10 rounded-xl bg-primary-light flex items-center justify-center flex-shrink-0">
            <Icon size={18} className="text-primary" />
          </div>
        </div>
        <p className={`font-display text-[26px] font-extrabold mb-1.5 tracking-tight ${valueColor ?? 'text-ink'}`}>{value}</p>
        {deltaLabel && (
          hasDelta ? (
            <div>
              <span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-semibold ${positive ? 'bg-success-bg text-success' : 'bg-danger-bg text-danger'}`}>
                {delta! >= 0 ? '▲' : '▼'} {Math.abs(delta!).toFixed(1)}%
              </span>
              <span className="ml-1.5 text-xs text-muted">{deltaLabel}</span>
              {hasMovers && (
                <button
                  type="button"
                  onClick={() => setShowReason((v) => !v)}
                  aria-expanded={showReason}
                  className="ml-1.5 text-xs font-normal text-primary underline underline-offset-2"
                >
                  {showReason ? 'Hide' : 'Why?'}
                </button>
              )}
              {hasMovers && showReason && (
                <ul className="mt-1.5 space-y-0.5 text-[11px] text-muted list-disc list-inside">
                  {moverLines!.map((line, i) => <li key={i}>{line}</li>)}
                </ul>
              )}
            </div>
          ) : (
            <div>
              <span className="text-xs text-muted">— {deltaLabel}</span>
              {gateReasonText && (
                <button
                  type="button"
                  onClick={() => setShowReason((v) => !v)}
                  aria-expanded={showReason}
                  className="ml-1.5 text-xs text-primary underline underline-offset-2"
                >
                  {showReason ? 'Hide' : 'Why?'}
                </button>
              )}
              {gateReasonText && showReason && (
                <p className="text-[11px] text-muted mt-1">{gateReasonText}</p>
              )}
            </div>
          )
        )}
      </FinoraCard>
    );
  }

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
        hasDelta ? (
          <div>
            <p className={`text-xs font-medium ${(invertDelta ? delta! < 0 : delta! >= 0) ? 'text-success' : 'text-danger'}`}>
              {delta! >= 0 ? '▲' : '▼'} {Math.abs(delta!).toFixed(1)}% {deltaLabel}
              {hasMovers && (
                <button
                  type="button"
                  onClick={() => setShowReason((v) => !v)}
                  aria-expanded={showReason}
                  className="ml-1.5 font-normal text-primary underline underline-offset-2"
                >
                  {showReason ? 'Hide' : 'Why?'}
                </button>
              )}
            </p>
            {hasMovers && showReason && (
              <ul className="mt-1 space-y-0.5 text-[11px] text-muted list-disc list-inside">
                {moverLines!.map((line, i) => <li key={i}>{line}</li>)}
              </ul>
            )}
          </div>
        ) : (
          <div>
            <p className="text-xs text-muted">
              — {deltaLabel}
              {gateReasonText && (
                <button
                  type="button"
                  onClick={() => setShowReason((v) => !v)}
                  aria-expanded={showReason}
                  className="ml-1.5 text-primary underline underline-offset-2"
                >
                  {showReason ? 'Hide' : 'Why?'}
                </button>
              )}
            </p>
            {gateReasonText && showReason && (
              <p className="text-[11px] text-muted mt-1">{gateReasonText}</p>
            )}
          </div>
        )
      )}
    </FinoraCard>
  );
}
