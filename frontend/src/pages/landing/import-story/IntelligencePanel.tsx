import { forwardRef } from 'react';
import { Check } from 'lucide-react';

const ROWS = [
  { label: 'Amazon', category: 'Shopping', amount: '₹2,450' },
  { label: 'Swiggy', category: 'Food', amount: '₹860' },
];

/**
 * Beat 3 of the ImportSection scroll story -- and, critically, the ONLY render of this
 * component's markup: it's reused unmodified as the reduced-motion static final state and as
 * ImportRevealSequence's last frame (see the scroll-storytelling design spec). It must therefore
 * read as a complete, deliberately designed component on its own, not a truncated animation
 * frame -- hence a real figure, real categorized rows, and an explicit "Insights ready" line,
 * not a bare shell. Visual tokens match DashboardMock/FloatingDashboardCard (rounded-xl,
 * border-[#E6EAF2], var(--m-success)) so the page reads as one product, not two dashboard
 * designs. Presentational only, same data-target contract as DocumentStack/ProcessingCore.
 */
export const IntelligencePanel = forwardRef<HTMLDivElement>(function IntelligencePanel(_props, ref) {
  return (
    <div
      ref={ref}
      data-target="panel-glow"
      className="w-full max-w-[220px] rounded-xl border border-[#E6EAF2] bg-white p-4 shadow-[0_24px_48px_-24px_rgba(15,23,42,.35)]"
    >
      <p className="text-[10px] uppercase tracking-wide text-slate-400 mb-1">Total organized</p>
      <p className="text-lg font-bold text-slate-900 mb-3">₹42,350</p>
      <div className="flex flex-col gap-1.5 mb-3">
        {ROWS.map((row) => (
          <div key={row.label} className="flex items-center justify-between text-[11px]">
            <span className="text-slate-600">{row.label}</span>
            <span className="font-medium" style={{ color: 'var(--m-success)' }}>{row.category}</span>
            <span className="text-slate-500">{row.amount}</span>
          </div>
        ))}
      </div>
      <div className="flex items-center gap-1.5 text-[11px] font-medium" style={{ color: 'var(--m-success)' }}>
        <Check size={13} />
        Insights ready
      </div>
    </div>
  );
});
