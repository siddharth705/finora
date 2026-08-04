import { useQuery } from '@tanstack/react-query';
import { HeartPulse, RefreshCw, FileStack, AlertTriangle, CheckCircle2 } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { adminSystemApi } from '../api/endpoints';

function statusColor(status: string) {
  if (status === 'UP') return 'text-success bg-success-bg';
  if (status === 'DOWN' || status === 'OUT_OF_SERVICE') return 'text-danger bg-danger-bg';
  return 'text-warning bg-warning-bg';
}

function formatUptime(seconds: number) {
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (hours > 0) return `${hours}h ${minutes}m`;
  return `${minutes}m`;
}

/**
 * Admin Portal Phase 7 -- the closest honest equivalent to a background-job monitor this codebase
 * has: CSV import runs synchronously in the request, not on a queue, so there's no real job
 * status to poll (see RecentImportDto's doc comment on the backend). Each row's only real signal
 * is whether it skipped any rows -- never a fabricated "failed" state.
 */
function RecentImportsSection() {
  const { data, isLoading } = useQuery({
    queryKey: ['admin-recent-imports'],
    queryFn: () => adminSystemApi.recentImports(),
  });

  return (
    <div>
      <div className="flex items-center gap-2 mb-3">
        <FileStack size={16} className="text-primary" />
        <h2 className="text-sm font-semibold text-muted uppercase tracking-wide">Recent imports</h2>
      </div>
      <div className="bg-card border border-border rounded-xl2 shadow-card divide-y divide-border">
        {isLoading && <p className="text-sm text-muted px-4 py-4">Loading…</p>}
        {!isLoading && (data?.length ?? 0) === 0 && (
          <p className="text-sm text-muted px-4 py-4">No statement imports recorded yet.</p>
        )}
        {data?.map((imp) => (
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

function SystemHealthContent() {
  const { data, isLoading, isError, refetch, isFetching } = useQuery({
    queryKey: ['admin-system-health'],
    queryFn: () => adminSystemApi.health(),
    refetchInterval: 30_000,
  });

  if (isLoading) return <p className="text-muted text-sm">Loading…</p>;
  // Bug fix: this used to be `if (!data) return null`, so a failed request (network error, 500)
  // rendered a completely blank page with zero indication anything went wrong -- on a page whose
  // entire purpose is telling an admin whether something is broken, the one state it couldn't
  // represent was "the health check itself failed". Same fix, same reasoning as Diagnostics.tsx.
  if (isError || !data) {
    return (
      <p className="text-sm text-danger bg-danger-bg rounded-lg px-3.5 py-2.5">
        Couldn't load system health -- please try again later.
      </p>
    );
  }

  return (
    <div className="space-y-6">
      <div className="bg-card border border-border rounded-xl2 shadow-card p-6 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <HeartPulse size={22} className={data.status === 'UP' ? 'text-success' : 'text-danger'} />
          <div>
            <p className="text-lg font-bold text-ink">Overall status: {data.status}</p>
            <p className="text-sm text-muted">Uptime {formatUptime(data.uptimeSeconds)} · checked {new Date(data.checkedAt).toLocaleTimeString()}</p>
          </div>
        </div>
        <button
          type="button"
          onClick={() => refetch()}
          disabled={isFetching}
          className="inline-flex items-center gap-1.5 text-sm font-medium text-ink border border-border rounded-lg px-3.5 py-2 hover:bg-bg disabled:opacity-50"
        >
          <RefreshCw size={14} className={isFetching ? 'animate-spin' : ''} /> Refresh
        </button>
      </div>

      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
        {Object.entries(data.components).map(([name, status]) => (
          <div key={name} className="bg-card border border-border rounded-xl2 shadow-card p-5 flex items-center justify-between">
            <span className="font-medium text-ink capitalize">{name}</span>
            <span className={`text-xs font-semibold rounded-full px-2.5 py-1 ${statusColor(status)}`}>{status}</span>
          </div>
        ))}
      </div>

      <RecentImportsSection />
    </div>
  );
}

export default function SystemHealth() {
  return (
    <AdminLayout title="System Health" subtitle="Live status of the backend and its dependencies">
      <RequirePermission permission="PLATFORM_DIAGNOSTICS_VIEW">
        <SystemHealthContent />
      </RequirePermission>
    </AdminLayout>
  );
}
