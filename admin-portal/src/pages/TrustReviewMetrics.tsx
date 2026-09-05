import { useQuery } from '@tanstack/react-query';
import { ShieldAlert, ListChecks, CheckCircle2, XCircle, AlertTriangle, Clock } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { StatCard } from '../components/StatCard';
import { adminHeldStatementApi } from '../api/endpoints';

function TrustReviewMetricsContent() {
  const { data, isLoading } = useQuery({
    queryKey: ['trust-review-telemetry'],
    queryFn: () => adminHeldStatementApi.telemetry(),
  });

  if (isLoading) return <p className="text-muted text-sm">Loading…</p>;
  if (!data) return <p className="text-muted text-sm">Could not load telemetry.</p>;

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-2 sm:grid-cols-3 gap-4">
        <StatCard icon={ShieldAlert} label="Total holds" value={data.totalHolds} />
        <StatCard icon={ListChecks} label="Resolved" value={data.resolved} />
        <StatCard icon={CheckCircle2} label="Approved" value={data.approved} />
        <StatCard icon={XCircle} label="Rejected" value={data.rejected} />
        <StatCard icon={AlertTriangle} label="False positives" value={data.falsePositives} tone="warning" />
        <StatCard
          icon={Clock}
          label="Median resolution"
          value={data.medianResolutionHours == null ? '—' : `${data.medianResolutionHours.toFixed(1)}h`}
        />
      </div>

      <section className="bg-card border border-border rounded-xl2 p-6">
        <h3 className="text-sm font-semibold text-ink mb-3">Holds by triggering condition</h3>
        <p className="text-xs text-muted mb-3">
          All holds, open or resolved — which trust signals generate review work.
        </p>
        {Object.keys(data.byCategory).length === 0 ? (
          <p className="text-muted text-xs">No trust-condition data recorded yet.</p>
        ) : (
          <dl className="grid grid-cols-2 sm:grid-cols-3 gap-4 text-sm">
            {Object.entries(data.byCategory).map(([category, count]) => (
              <div key={category}>
                <dt className="text-muted text-xs font-mono">{category}</dt>
                <dd className="text-ink text-lg">{count}</dd>
              </div>
            ))}
          </dl>
        )}
      </section>
    </div>
  );
}

export default function TrustReviewMetrics() {
  return (
    <AdminLayout title="Trust Review Metrics" subtitle="Aggregate counts over the held-statement queue — no customer data.">
      <RequirePermission permission="PLATFORM_DIAGNOSTICS_VIEW">
        <TrustReviewMetricsContent />
      </RequirePermission>
    </AdminLayout>
  );
}
