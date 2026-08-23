import { useQuery } from '@tanstack/react-query';
import { Plug, RefreshCw, Clock } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { adminIntegrationsApi } from '../api/endpoints';

function statusColor(status: string) {
  if (status === 'UP') return 'text-success bg-success-bg';
  if (status === 'DOWN') return 'text-danger bg-danger-bg';
  return 'text-warning bg-warning-bg';
}

function IntegrationsContent() {
  const { data, isLoading, isError, refetch, isFetching } = useQuery({
    queryKey: ['admin-integrations-overview'],
    queryFn: () => adminIntegrationsApi.overview(),
    refetchInterval: 30_000,
  });

  if (isLoading) return <p className="text-muted text-sm">Loading…</p>;
  // Same fix, same reasoning as SystemHealth.tsx/Diagnostics.tsx: `if (!data) return null` would
  // render a blank page on a failed request instead of telling the admin the check itself failed.
  if (isError || !data) {
    return (
      <p className="text-sm text-danger bg-danger-bg rounded-lg px-3.5 py-2.5">
        Couldn't load integrations -- please try again later.
      </p>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex justify-end">
        <button
          type="button"
          onClick={() => refetch()}
          disabled={isFetching}
          className="inline-flex items-center gap-1.5 text-sm font-medium text-ink border border-border rounded-lg px-3.5 py-2 hover:bg-bg disabled:opacity-50"
        >
          <RefreshCw size={14} className={isFetching ? 'animate-spin' : ''} /> Refresh
        </button>
      </div>

      <div>
        <h2 className="text-sm font-semibold text-ink uppercase tracking-wide mb-3">Live integrations</h2>
        {data.integrations.length === 0 ? (
          <p className="text-sm text-muted">No integrations reporting health right now.</p>
        ) : (
          <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
            {data.integrations.map((integration) => (
              <div key={integration.name} className="bg-card border border-border rounded-xl2 shadow-card p-5 space-y-2">
                <div className="flex items-center justify-between">
                  <span className="font-medium text-ink flex items-center gap-2">
                    <Plug size={16} className="text-muted" /> {integration.name}
                  </span>
                  <span className={`text-xs font-semibold rounded-full px-2.5 py-1 ${statusColor(integration.status)}`}>
                    {integration.status}
                  </span>
                </div>
                <p className="text-xs text-muted">{integration.category}</p>
                <p className="text-sm text-ink">{integration.description}</p>
                <p className="text-xs text-muted">{integration.detail}</p>
              </div>
            ))}
          </div>
        )}
      </div>

      <div>
        <h2 className="text-sm font-semibold text-ink uppercase tracking-wide mb-3">Upcoming integrations</h2>
        {data.upcoming.length === 0 ? (
          <p className="text-sm text-muted">Nothing planned right now.</p>
        ) : (
          <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
            {data.upcoming.map((integration) => (
              <div key={integration.name} className="border border-dashed border-border rounded-xl2 p-5 space-y-2 bg-bg">
                <span className="font-medium text-muted flex items-center gap-2">
                  <Clock size={16} /> {integration.name}
                </span>
                <p className="text-sm text-muted">{integration.description}</p>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

export default function Integrations() {
  return (
    <AdminLayout title="Integrations" subtitle="Third-party services Finora talks to, their live status, and what's planned next">
      <RequirePermission permission="PLATFORM_DIAGNOSTICS_VIEW">
        <IntegrationsContent />
      </RequirePermission>
    </AdminLayout>
  );
}
