import type { LucideIcon } from 'lucide-react';

/**
 * Shared page-level stat tile -- was defined independently in Dashboard.tsx, LearningEngine.tsx,
 * and ReconciliationMonitor.tsx (two of the three copies were byte-for-byte identical, and none
 * of them agreed on which icon to borrow just for its type). Same reasoning as DataTable.tsx's
 * extraction: one visual definition instead of three that can silently drift apart. `tone`
 * defaults to 'default' so the two callers that never used it (Dashboard, LearningEngine) are
 * unaffected by picking this up.
 */
export function StatCard({
  icon: Icon, label, value, tone = 'default',
}: {
  icon: LucideIcon;
  label: string;
  value: string | number;
  tone?: 'default' | 'success' | 'warning';
}) {
  const toneClass = tone === 'success' ? 'text-success' : tone === 'warning' ? 'text-warning' : 'text-ink';
  return (
    <div className="bg-card border border-border rounded-xl2 p-5 shadow-card">
      <div className="flex items-center gap-2 text-muted mb-2">
        <Icon size={16} />
        <span className="text-xs font-medium uppercase tracking-wide">{label}</span>
      </div>
      <p className={`text-2xl font-bold ${toneClass}`}>{value}</p>
    </div>
  );
}
