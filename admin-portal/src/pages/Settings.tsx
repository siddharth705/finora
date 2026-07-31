import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Settings as SettingsIcon, Save } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { platformSettingsApi } from '../api/endpoints';

function SettingsContent() {
  const queryClient = useQueryClient();
  const { data, isLoading } = useQuery({
    queryKey: ['admin-platform-settings'],
    queryFn: () => platformSettingsApi.get(),
  });

  const [registrationsEnabled, setRegistrationsEnabled] = useState(true);
  const [maxFailedLoginAttempts, setMaxFailedLoginAttempts] = useState(5);
  const [lockoutDurationMinutes, setLockoutDurationMinutes] = useState(15);
  const [saved, setSaved] = useState(false);

  // Sync local form state whenever a fresh settings row loads -- e.g. on first fetch, or after
  // another admin's change is picked up by a refetch.
  useEffect(() => {
    if (data) {
      setRegistrationsEnabled(data.registrationsEnabled);
      setMaxFailedLoginAttempts(data.maxFailedLoginAttempts);
      setLockoutDurationMinutes(data.lockoutDurationMinutes);
    }
  }, [data]);

  const updateMutation = useMutation({
    mutationFn: () => platformSettingsApi.update({ registrationsEnabled, maxFailedLoginAttempts, lockoutDurationMinutes }),
    onSuccess: (updated) => {
      queryClient.setQueryData(['admin-platform-settings'], updated);
      setSaved(true);
      setTimeout(() => setSaved(false), 2500);
    },
  });

  if (isLoading || !data) return <p className="text-muted text-sm">Loading…</p>;

  return (
    <div className="max-w-2xl space-y-6">
      <div className="bg-card border border-border rounded-xl2 shadow-card p-6 space-y-6">
        <div className="flex items-center gap-2.5">
          <SettingsIcon size={18} className="text-primary" />
          <h3 className="font-semibold text-ink">Platform configuration</h3>
        </div>

        <div className="flex items-center justify-between pb-5 border-b border-border">
          <div>
            <p className="text-sm font-medium text-ink">New registrations</p>
            <p className="text-xs text-muted mt-0.5">
              When off, public sign-up is blocked. Admin-created accounts (from the Users page) are
              unaffected.
            </p>
          </div>
          <button
            type="button"
            role="switch"
            aria-checked={registrationsEnabled}
            onClick={() => setRegistrationsEnabled((v) => !v)}
            className={`w-11 h-6 rounded-full flex-shrink-0 transition-colors relative ${
              registrationsEnabled ? 'bg-primary' : 'bg-border'
            }`}
          >
            <span
              className={`absolute top-0.5 w-5 h-5 rounded-full bg-white transition-transform ${
                registrationsEnabled ? 'translate-x-[22px]' : 'translate-x-0.5'
              }`}
            />
          </button>
        </div>

        <div className="grid gap-5 md:grid-cols-2">
          <div>
            <label className="text-sm font-medium text-ink mb-1 block">Max failed login attempts</label>
            <p className="text-xs text-muted mb-2">Consecutive bad passwords before an account is locked.</p>
            <input
              type="number"
              min={1}
              max={20}
              value={maxFailedLoginAttempts}
              onChange={(e) => setMaxFailedLoginAttempts(Number(e.target.value))}
              className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
            />
          </div>
          <div>
            <label className="text-sm font-medium text-ink mb-1 block">Lockout duration (minutes)</label>
            <p className="text-xs text-muted mb-2">How long a locked account stays locked.</p>
            <input
              type="number"
              min={1}
              max={1440}
              value={lockoutDurationMinutes}
              onChange={(e) => setLockoutDurationMinutes(Number(e.target.value))}
              className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
            />
          </div>
        </div>

        {updateMutation.isError && (
          <p className="text-sm text-danger bg-danger-bg rounded-lg px-3 py-2">
            {(updateMutation.error as any)?.response?.data?.message ?? 'Failed to save settings.'}
          </p>
        )}

        <div className="flex items-center gap-3 pt-2">
          <button
            type="button"
            disabled={updateMutation.isPending}
            onClick={() => updateMutation.mutate()}
            className="inline-flex items-center gap-1.5 bg-primary hover:bg-primary-dark text-white text-sm font-semibold rounded-lg px-4 py-2.5 disabled:opacity-50"
          >
            <Save size={14} /> {updateMutation.isPending ? 'Saving…' : 'Save changes'}
          </button>
          {saved && <span className="text-sm text-success">Saved.</span>}
          <span className="text-xs text-muted ml-auto">
            Last updated {new Date(data.updatedAt).toLocaleString()}
          </span>
        </div>
      </div>
    </div>
  );
}

export default function Settings() {
  return (
    <AdminLayout title="Settings" subtitle="Platform-wide configuration that takes effect immediately">
      <RequirePermission permission="SYSTEM_SETTINGS">
        <SettingsContent />
      </RequirePermission>
    </AdminLayout>
  );
}
