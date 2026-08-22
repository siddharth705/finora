import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { FileStack, AlertTriangle, CheckCircle2 } from 'lucide-react';
import { adminSystemApi } from '../api/endpoints';

/**
 * Extracted from SystemHealth.tsx's own RecentImportsSection (Admin Portal Phase 7) so Dashboard
 * can show the same real data without a second, drifting copy -- same reasoning as StatCard's own
 * extraction. `limit` truncates client-side (the backend already caps the query itself, see
 * AdminSystemService.recentImports' RECENT_IMPORTS_LIMIT) for the Dashboard's more compact panel;
 * SystemHealth's own usage passes no limit and shows everything the backend returns.
 *
 * CSV import runs synchronously in the request, not on a queue, so there's no real job status to
 * poll -- each row's only real signal is whether it skipped any rows, never a fabricated "failed"
 * state (see RecentImportDto's own doc comment on the backend).
 */
export function RecentImportsPanel({ limit, viewAllTo }: { limit?: number; viewAllTo?: string }) {
  const { data, isLoading } = useQuery({
    queryKey: ['admin-recent-imports'],
    queryFn: () => adminSystemApi.recentImports(),
  });

  const rows = limit ? data?.slice(0, limit) : data;

  return (
    <div>
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <FileStack size={16} className="text-primary" />
          <h2 className="text-sm font-semibold text-muted uppercase tracking-wide">Recent imports</h2>
        </div>
        {viewAllTo && (
          <Link to={viewAllTo} className="text-xs text-primary font-medium">View all →</Link>
        )}
      </div>
      <div className="bg-card border border-border rounded-xl2 shadow-card divide-y divide-border">
        {isLoading && <p className="text-sm text-muted px-4 py-4">Loading…</p>}
        {!isLoading && (rows?.length ?? 0) === 0 && (
          <p className="text-sm text-muted px-4 py-4">No statement imports recorded yet.</p>
        )}
        {rows?.map((imp) => (
          <div key={imp.id} className="flex items-center gap-3 px-4 py-3">
            {imp.hadSkippedRows
              ? <AlertTriangle size={15} className="text-warning flex-shrink-0" />
              : <CheckCircle2 size={15} className="text-success flex-shrink-0" />}
            <div className="min-w-0 flex-1">
              <p className="text-sm font-medium text-ink truncate">{imp.fileName}</p>
              <p className="text-xs text-muted">
                {imp.userEmail} · {imp.transactionsImported} imported
                {imp.hadSkippedRows ? `, ${imp.transactionsSkipped} skipped` : ''} · {new Date(imp.importedAt).toLocaleString()}
              </p>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
