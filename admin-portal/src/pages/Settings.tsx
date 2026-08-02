import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Settings as SettingsIcon, Save, Flag } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { platformSettingsApi, adminFeatureFlagsApi } from '../api/endpoints';
import { useNotify } from '../context/NotificationContext';
import type { FeatureFlagDto } from '../types';

/** Every row here is a real, seeded feature_flags row (V32) with at least one real call site
 *  wired to it (see RecurringService.detectForUser's doc comment); this is a toggle surface, not
 *  a place to invent new flags, so there's no "create flag" form. */
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

/** Feature Flags used to be its own separate top-level page -- folded in here since it already
 *  shared the exact same SYSTEM_SETTINGS gate as platform configuration above; both are
 *  "platform-wide switches an admin can flip," just two different shapes of switch. */
function FeatureFlagsSection() {
  const { data, isLoading } = useQuery({
    queryKey: ['admin-feature-flags'],
    queryFn: () => adminFeatureFlagsApi.list(),
  });

  return (
    <div className="bg-card border border-border rounded-xl2 shadow-card p-6 space-y-4">
      <div className="flex items-center gap-2.5">
        <Flag size={18} className="text-primary" />
        <h3 className="font-semibold text-ink">Platform feature flags</h3>
      </div>
      {isLoading && <p className="text-sm text-muted">Loading…</p>}
      {!isLoading && (
        <div className="border border-border rounded-lg divide-y divide-border">
          {(data?.length ?? 0) === 0 && (
            <p className="text-sm text-muted px-5 py-4">No feature flags are seeded yet.</p>
          )}
          {data?.map((flag) => <FlagRow key={flag.id} flag={flag} />)}
        </div>
      )}
    </div>
  );
}

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
            aria-label="Toggle new registrations"
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
            <label htmlFor="max-failed-login-attempts" className="text-sm font-medium text-ink mb-1 block">Max failed login attempts</label>
            <p className="text-xs text-muted mb-2">Consecutive bad passwords before an account is locked.</p>
            <input
              id="max-failed-login-attempts"
              type="number"
              min={1}
              max={20}
              value={maxFailedLoginAttempts}
              onChange={(e) => setMaxFailedLoginAttempts(Number(e.target.value))}
              className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
            />
          </div>
          <div>
            <label htmlFor="lockout-duration-minutes" className="text-sm font-medium text-ink mb-1 block">Lockout duration (minutes)</label>
            <p className="text-xs text-muted mb-2">How long a locked account stays locked.</p>
            <input
              id="lockout-duration-minutes"
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

      <FeatureFlagsSection />
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
