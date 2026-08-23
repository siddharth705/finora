import { Check } from 'lucide-react';

const ROWS = [
  { label: 'Amazon', category: 'Shopping' },
  { label: 'Swiggy', category: 'Food' },
];

/**
 * Beat 3 of the ImportSection reveal sequence, and -- critically -- the ONLY render of this
 * component's markup: it's reused unmodified as the reduced-motion static final state and as
 * ImportRevealSequence's last frame. It must therefore read as a complete, deliberately designed
 * component on its own, not a truncated animation frame -- hence a real figure, real categorized
 * rows, and an explicit "Insights ready" line, not a bare shell.
 *
 * Sized and styled to sit overlapping StatementCard's bottom-right corner (see
 * ImportRevealSequence, which owns that positioning) rather than standing alone as a separate,
 * disconnected card -- the redesigned composition this replaces kept beat 3 visually tied to the
 * statement it came from.
 */
export function IntelligencePanel() {
  return (
    <div className="w-[168px] rounded-lg border border-[#E6EAF2] bg-white p-3.5 shadow-[0_20px_40px_-16px_rgba(15,23,42,.35)]">
      <p className="text-[8px] uppercase tracking-wide text-slate-400">Total organized</p>
      <p className="text-base font-bold text-slate-900 mb-2">₹42,350</p>
      <div className="flex flex-col gap-1 mb-2">
        {ROWS.map((row) => (
          <div key={row.label} className="flex items-center justify-between text-[9px]">
            <span className="text-slate-600">{row.label}</span>
            <span className="font-medium" style={{ color: 'var(--m-success)' }}>{row.category}</span>
          </div>
        ))}
      </div>
      <div className="flex items-center gap-1 text-[9px] font-medium" style={{ color: 'var(--m-success)' }}>
        <Check size={11} />
        Insights ready
      </div>
    </div>
  );
}
