import type { LucideIcon } from 'lucide-react';
import { ArrowUp, ArrowDown } from 'lucide-react';

/** One tile's "vs yesterday" comparison, already resolved to a direction and a display label --
 *  see Dashboard.tsx's computeDelta() for why the good/bad judgment (isGood) is computed by the
 *  caller rather than inferred here from the raw sign: for "Imports w/ skipped rows," a DOWN
 *  delta is the good direction, the opposite of every other daily tile, so this component must
 *  never guess polarity from direction alone. */
export interface StatDelta {
  direction: 'up' | 'down' | 'flat';
  label: string;
  isGood: boolean;
}

/**
 * Shared page-level stat tile -- was defined independently in Dashboard.tsx, LearningEngine.tsx,
 * and ReconciliationMonitor.tsx (two of the three copies were byte-for-byte identical, and none
 * of them agreed on which icon to borrow just for its type). Same reasoning as DataTable.tsx's
 * extraction: one visual definition instead of three that can silently drift apart. `tone`
 * defaults to 'default' so the two callers that never used it (Dashboard, LearningEngine) are
 * unaffected by picking this up. `delta` is optional for the same reason -- only Dashboard's
 * four daily tiles have a "vs yesterday" figure to show.
 */
export function StatCard({
  icon: Icon, label, value, tone = 'default', delta,
}: {
  icon: LucideIcon;
  label: string;
  value: string | number;
  tone?: 'default' | 'success' | 'warning';
  delta?: StatDelta;
}) {
  const toneClass = tone === 'success' ? 'text-success' : tone === 'warning' ? 'text-warning' : 'text-ink';
  const deltaClass = !delta || delta.direction === 'flat' ? 'text-muted' : delta.isGood ? 'text-success' : 'text-danger';
  return (
    <div className="bg-card border border-border rounded-xl2 p-5 shadow-card">
      <div className="flex items-center gap-2 text-muted mb-2">
        <Icon size={16} />
        <span className="text-xs font-medium uppercase tracking-wide">{label}</span>
      </div>
      <p className={`text-2xl font-bold ${toneClass}`}>{value}</p>
      {delta && (
        <p className={`text-xs mt-1 flex items-center gap-1 ${deltaClass}`}>
          {delta.direction === 'up' && <ArrowUp size={11} />}
          {delta.direction === 'down' && <ArrowDown size={11} />}
          {delta.label}
        </p>
      )}
    </div>
  );
}
