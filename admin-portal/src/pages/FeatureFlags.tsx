import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Flag } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { adminFeatureFlagsApi } from '../api/endpoints';
import { useNotify } from '../context/NotificationContext';
import type { FeatureFlagDto } from '../types';

/**
 * Admin Portal Phase 8 -- every row here is a real, seeded feature_flags row (V32) with at least
 * one real call site wired to it (see RecurringService.detectForUser's doc comment); this page is
 * a toggle surface, not a place to invent new flags, so there's no "create flag" form.
 */
function FlagRow({ flag }: { flag: FeatureFlagDto }) {
  const queryClient = useQueryClient();
  const notify = useNotify();

  const toggleMutation = useMutation({
    mutationFn: (enabled: boolean) => adminFeatureFlagsApi.update(flag.id, { enabled }),
    onSuccess: (updated) => {
      queryClient.setQueryData<FeatureFlagDto[]>(['admin-feature-flags'], (prev) =>
        prev?.map((f) => (f.id === updated.id ? updated : f)));
      notify.success(`${updated.key} ${updated.enabled ? 'enabled' : 'disabled'}.`);
    },
    onError: () => notify.error('Failed to update the flag. Please try again.'),
  });

  return (
    <div className="flex items-center justify-between px-5 py-4">
      <div className="min-w-0 pr-6">
        <p className="text-sm font-semibold text-ink">{flag.key}</p>
        <p className="text-xs text-muted mt-1">{flag.description}</p>
        <p className="text-[11px] text-muted mt-1.5">Last updated {new Date(flag.updatedAt).toLocaleString()}</p>
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={flag.enabled}
        aria-label={`Toggle ${flag.key}`}
        disabled={toggleMutation.isPending}
        onClick={() => toggleMutation.mutate(!flag.enabled)}
        className={`w-11 h-6 rounded-full flex-shrink-0 transition-colors relative disabled:opacity-50 ${
          flag.enabled ? 'bg-primary' : 'bg-border'
        }`}
      >
        <span
          className={`absolute top-0.5 w-5 h-5 rounded-full bg-white transition-transform ${
            flag.enabled ? 'translate-x-[22px]' : 'translate-x-0.5'
          }`}
        />
      </button>
    </div>
  );
}

function FeatureFlagsContent() {
  const { data, isLoading } = useQuery({
    queryKey: ['admin-feature-flags'],
    queryFn: () => adminFeatureFlagsApi.list(),
  });

  if (isLoading) return <p className="text-muted text-sm">Loading…</p>;

  return (
    <div className="max-w-2xl space-y-6">
      <div className="flex items-center gap-2.5">
        <Flag size={18} className="text-primary" />
        <h3 className="font-semibold text-ink">Platform feature flags</h3>
      </div>
      <div className="bg-card border border-border rounded-xl2 shadow-card divide-y divide-border">
        {(data?.length ?? 0) === 0 && (
          <p className="text-sm text-muted px-5 py-4">No feature flags are seeded yet.</p>
        )}
        {data?.map((flag) => <FlagRow key={flag.id} flag={flag} />)}
      </div>
    </div>
  );
}

export default function FeatureFlags() {
  return (
    <AdminLayout title="Feature Flags" subtitle="Platform-wide switches that take effect on the very next call">
      <RequirePermission permission="SYSTEM_SETTINGS">
        <FeatureFlagsContent />
      </RequirePermission>
    </AdminLayout>
  );
}
