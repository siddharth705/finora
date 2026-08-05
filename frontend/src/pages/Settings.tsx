import { useEffect, useState } from 'react';
import { SlidersHorizontal, Sparkles, ShieldCheck, Info, Smartphone, X } from 'lucide-react';
import { userApi, workspaceApi, analyticsApi, deviceApi, type ImportStatistics, type DeviceSession } from '../api/endpoints';
import { useTheme } from '../context/ThemeContext';
import { ChangePasswordModal } from '../components/ChangePasswordModal';
import { maskPhone } from '../lib/maskPhone';
import { parsePositiveAmount } from '../lib/validation';
import { formatDayMonthYear, formatRelativeTime, SectionCard, VerifiedBadge, SaveStatus, MetricTile } from '../components/AccountUI';

// v1 scope is deliberately capabilities-first, not roadmap-first: every section below reflects a
// real, backed setting or fact. No "Coming soon" placeholders for 2FA, API keys, integrations,
// email/SMS notification preferences, or storage usage -- none of those exist yet, so none of
// them get a settings control. Add a section here the same day the backend capability it
// configures actually ships, not before.
//
// One thing deliberately absent, on purpose:
// - Plan/Subscription: there's no subscription model on the backend at all (no plan field on
//   User, no billing). A hardcoded "Free" label tends to outlive the "temporary" caveat next to
//   it, so it's hidden entirely rather than displayed as a fact that isn't one yet.
//
// Personal identity fields (name/email/phone/member-since) live on Profile.tsx now, not here --
// this page is "how Finora behaves for you," not "who you are." Change Password stays here (a
// Security *action*, not an identity fact) via ChangePasswordModal -- an authenticated
// POST /api/v1/users/me/password-change/*, genuinely separate from the forgot-password flow used
// by someone who can't log in at all. See that component's own doc comment for the full reasoning.
// Active Sessions is new: DeviceController/RefreshTokenService already existed on the backend with
// no frontend caller at all until this page wired one up.

// Falls back to a curated list of common zones on browsers that predate
// Intl.supportedValuesOf (Safari < 15, older WebViews) rather than leaving the dropdown empty.
function availableTimezones(): string[] {
  try {
    // @ts-expect-error — not in the TS lib.d.ts on every configured target yet.
    const values = Intl.supportedValuesOf?.('timeZone');
    if (Array.isArray(values) && values.length > 0) return values;
  } catch {
    // fall through to the curated list below
  }
  return [
    'Asia/Kolkata', 'UTC', 'America/New_York', 'America/Chicago', 'America/Denver',
    'America/Los_Angeles', 'Europe/London', 'Europe/Paris', 'Europe/Berlin', 'Asia/Dubai',
    'Asia/Singapore', 'Asia/Tokyo', 'Asia/Shanghai', 'Australia/Sydney',
  ];
}

/** "Chrome on Windows" -- browser/device are both nullable (see DeviceSession's own doc comment,
 *  best-effort labels, not a guaranteed fingerprint), so this degrades gracefully either way. */
function deviceLabel(session: DeviceSession): string {
  if (session.browser && session.device) return `${session.browser} on ${session.device}`;
  return session.browser || session.device || 'Unknown device';
}

export default function Settings() {
  const { theme, setTheme } = useTheme();
  const [phoneNumber, setPhoneNumber] = useState('');
  const [phoneVerified, setPhoneVerified] = useState(false);
  const [passwordChangedAt, setPasswordChangedAt] = useState<string | null>(null);
  const [changePasswordOpen, setChangePasswordOpen] = useState(false);
  const [lowBalanceThreshold, setLowBalanceThreshold] = useState('2000');
  const [savedLowBalanceThreshold, setSavedLowBalanceThreshold] = useState('2000');
  const [timezone, setTimezone] = useState('Asia/Kolkata');
  const [savedTimezone, setSavedTimezone] = useState('Asia/Kolkata');
  const [timezones] = useState<string[]>(availableTimezones);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);

  const [prefsSaving, setPrefsSaving] = useState(false);
  const [prefsJustSaved, setPrefsJustSaved] = useState(false);
  const [prefsError, setPrefsError] = useState(false);
  const [prefsInvalid, setPrefsInvalid] = useState<string | null>(null);

  const [confidenceThreshold, setConfidenceThreshold] = useState(90);
  const [savedConfidenceThreshold, setSavedConfidenceThreshold] = useState(90);
  const [intelLoading, setIntelLoading] = useState(true);
  const [intelSaving, setIntelSaving] = useState(false);
  const [intelJustSaved, setIntelJustSaved] = useState(false);
  const [intelError, setIntelError] = useState(false);

  const [importStats, setImportStats] = useState<ImportStatistics | null>(null);

  const [sessions, setSessions] = useState<DeviceSession[]>([]);
  const [sessionsLoading, setSessionsLoading] = useState(true);
  const [sessionsError, setSessionsError] = useState(false);
  const [revokingId, setRevokingId] = useState<string | null>(null);

  const prefsDirty = lowBalanceThreshold !== savedLowBalanceThreshold || timezone !== savedTimezone;
  const intelDirty = confidenceThreshold !== savedConfidenceThreshold;

  function loadSessions() {
    setSessionsLoading(true);
    setSessionsError(false);
    deviceApi.list()
      .then(setSessions)
      .catch(() => setSessionsError(true))
      .finally(() => setSessionsLoading(false));
  }

  useEffect(() => {
    userApi.get().then((u) => {
      setPhoneNumber(u.phoneNumber);
      setPhoneVerified(u.phoneVerified);
      setPasswordChangedAt(u.passwordChangedAt);
      setLowBalanceThreshold(String(u.lowBalanceThreshold));
      setSavedLowBalanceThreshold(String(u.lowBalanceThreshold));
      setTimezone(u.timezone);
      setSavedTimezone(u.timezone);
      setLoading(false);
    }).catch(() => {
      setLoadError(true);
      setLoading(false);
    });
    workspaceApi.getSettings().then((s) => {
      setConfidenceThreshold(s.autoApplyConfidenceThreshold);
      setSavedConfidenceThreshold(s.autoApplyConfidenceThreshold);
      setIntelLoading(false);
    }).catch(() => setIntelLoading(false));
    // Best-effort — the Data section shows "—" for any stat that doesn't load rather than
    // blocking the rest of the page on it.
    analyticsApi.importStatistics().then(setImportStats).catch(() => {});
    loadSessions();
  }, []);

  async function savePreferences() {
    // Validate before sending. A cleared `type="number"` field yields '', and parseFloat('') is
    // NaN -- which JSON.stringify writes as `null`, which UserSettingsService reads as "leave this
    // field unchanged". The request then succeeds with only `timezone` applied, and the old code
    // set the saved threshold from the local text regardless, so the form went clean and flashed
    // "Saved" over a value the server never stored.
    const threshold = parsePositiveAmount(lowBalanceThreshold);
    if (threshold === null) {
      setPrefsInvalid('Low balance alert must be a number greater than zero.');
      return;
    }
    setPrefsInvalid(null);
    setPrefsSaving(true);
    setPrefsError(false);
    try {
      // Trust the server's response over local state -- the same pattern
      // saveIntelligencePreferences() below already uses. If the backend ever normalizes or
      // rejects part of this payload, the form reflects what was actually stored.
      const saved = await userApi.update({ lowBalanceThreshold: threshold, timezone });
      setLowBalanceThreshold(String(saved.lowBalanceThreshold));
      setSavedLowBalanceThreshold(String(saved.lowBalanceThreshold));
      setTimezone(saved.timezone);
      setSavedTimezone(saved.timezone);
      setPrefsJustSaved(true);
      setTimeout(() => setPrefsJustSaved(false), 2000);
    } catch {
      setPrefsError(true);
    } finally {
      setPrefsSaving(false);
    }
  }

  async function saveIntelligencePreferences() {
    setIntelSaving(true);
    setIntelError(false);
    try {
      const s = await workspaceApi.updateSettings({ autoApplyConfidenceThreshold: confidenceThreshold });
      setConfidenceThreshold(s.autoApplyConfidenceThreshold);
      setSavedConfidenceThreshold(s.autoApplyConfidenceThreshold);
      setIntelJustSaved(true);
      setTimeout(() => setIntelJustSaved(false), 2000);
    } catch {
      setIntelError(true);
    } finally {
      setIntelSaving(false);
    }
  }

  async function revokeSession(id: string) {
    setRevokingId(id);
    try {
      await deviceApi.revoke(id);
      setSessions((prev) => prev.filter((s) => s.id !== id));
    } catch {
      // Best-effort UI -- the list simply keeps the row and the user can retry; no dedicated
      // error state for a single row action on an otherwise-working list.
    } finally {
      setRevokingId(null);
    }
  }

  if (loading) return <p className="text-muted">Loading…</p>;

  if (loadError) return <p className="text-muted">Couldn't load your settings — please try again later.</p>;

  return (
    <div className="space-y-6 max-w-3xl">
      <div>
        <h1 className="font-serif text-2xl font-semibold text-ink">Settings</h1>
        <p className="text-sm text-muted mt-1">Manage your preferences, security, and account data.</p>
      </div>

      <SectionCard icon={<SlidersHorizontal size={18} />} title="General" subtitle="Customize your Finora experience">
        <div className="grid md:grid-cols-3 gap-4">
          <div>
            <label htmlFor="settings-low-balance-threshold" className="block text-xs uppercase text-muted mb-1">Low balance alert</label>
            <input
              id="settings-low-balance-threshold"
              type="number"
              min="1"
              step="1"
              value={lowBalanceThreshold}
              onChange={(e) => { setLowBalanceThreshold(e.target.value); setPrefsInvalid(null); }}
              className="bg-card text-ink w-full border border-border rounded-lg px-3 py-2 text-sm"
            />
          </div>
          <div>
            <label htmlFor="settings-timezone" className="block text-xs uppercase text-muted mb-1">Timezone</label>
            <select
              id="settings-timezone"
              value={timezone}
              onChange={(e) => setTimezone(e.target.value)}
              className="bg-card text-ink w-full border border-border rounded-lg px-3 py-2 text-sm"
            >
              {!timezones.includes(timezone) && <option value={timezone}>{timezone}</option>}
              {timezones.map((tz) => <option key={tz} value={tz}>{tz}</option>)}
            </select>
          </div>
          <div>
            <label htmlFor="settings-theme" className="block text-xs uppercase text-muted mb-1">Theme</label>
            <select
              id="settings-theme"
              value={theme}
              onChange={(e) => setTheme(e.target.value as 'light' | 'dark' | 'system')}
              className="bg-card text-ink w-full border border-border rounded-lg px-3 py-2 text-sm"
            >
              <option value="light">Light</option>
              <option value="dark">Dark</option>
              <option value="system">System</option>
            </select>
          </div>
        </div>
        <div className="flex items-center justify-between mt-5 pt-4 border-t border-border">
          <p className="text-xs text-muted">Theme applies instantly. Low balance alert and timezone save when you click Save.</p>
          <div className="flex items-center gap-3">
            <SaveStatus dirty={prefsDirty} saving={prefsSaving} justSaved={prefsJustSaved} error={prefsError} errorMessage={prefsInvalid} />
            <button
              onClick={savePreferences}
              disabled={prefsSaving || !prefsDirty}
              className="bg-primary text-white hover:bg-primary-dark disabled:opacity-50 rounded-lg px-4 py-2 text-xs uppercase font-medium"
            >
              {prefsSaving ? 'Saving…' : 'Save preferences'}
            </button>
          </div>
        </div>
      </SectionCard>

      <SectionCard icon={<ShieldCheck size={18} />} title="Security" subtitle="Manage your password, verification, and active sessions">
        <div className="border-b border-border py-3 text-sm">
          <p className="text-ink font-medium">Password</p>
          <p className="text-muted text-xs mt-0.5">
            {formatRelativeTime(passwordChangedAt) ? `Last changed ${formatRelativeTime(passwordChangedAt)}` : 'Never changed'}
          </p>
          <p className="text-muted text-[11px] mt-1">Keep your account secure by using a unique password.</p>
          <button
            onClick={() => setChangePasswordOpen(true)}
            className="mt-3 border border-border rounded-lg px-3 py-1.5 text-xs uppercase font-medium text-ink hover:bg-black/5"
          >
            Change Password
          </button>
        </div>
        <div className="flex items-center justify-between border-b border-border py-3 text-sm">
          <div>
            <p className="text-ink font-medium">Phone verification</p>
            <p className="text-muted text-xs">{phoneNumber ? maskPhone(phoneNumber) : 'No phone number on file'}</p>
          </div>
          {phoneVerified ? <VerifiedBadge /> : <span className="text-xs text-muted flex-shrink-0">Not verified</span>}
        </div>

        <div className="pt-3">
          <p className="text-ink font-medium text-sm">Active Sessions</p>
          <p className="text-muted text-[11px] mt-0.5 mb-3">
            Every device currently signed in to your account. Signing one out here ends that
            session the next time it needs to refresh.
          </p>
          {sessionsLoading ? (
            <p className="text-xs text-muted">Loading…</p>
          ) : sessionsError ? (
            <p className="text-xs text-danger">Couldn't load your active sessions — please try again later.</p>
          ) : sessions.length === 0 ? (
            <p className="text-xs text-muted italic">No active sessions found.</p>
          ) : (
            <div className="space-y-2">
              {sessions.map((s) => (
                <div key={s.id} className="flex items-center justify-between gap-3 border border-border rounded-lg px-3 py-2.5">
                  <div className="flex items-center gap-2.5 min-w-0">
                    <Smartphone size={15} className="text-muted flex-shrink-0" />
                    <div className="min-w-0">
                      <p className="text-sm text-ink truncate">{deviceLabel(s)}</p>
                      <p className="text-[11px] text-muted truncate">
                        {s.lastSeenAt ? `Last active ${formatRelativeTime(s.lastSeenAt) ?? 'recently'}` : 'Not used yet'}
                        {s.lastSeenIp ? ` · ${s.lastSeenIp}` : ''}
                      </p>
                    </div>
                  </div>
                  <button
                    type="button"
                    title="Sign out this device"
                    disabled={revokingId === s.id}
                    onClick={() => revokeSession(s.id)}
                    className="w-7 h-7 rounded-lg hover:bg-danger-bg text-muted hover:text-danger inline-flex items-center justify-center flex-shrink-0 disabled:opacity-50"
                  >
                    <X size={14} />
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>
      </SectionCard>

      <SectionCard icon={<Sparkles size={18} />} title="AI" subtitle="Control how Finora reviews and understands your financial documents">
        {intelLoading ? (
          <p className="text-muted text-sm">Loading…</p>
        ) : (
          <>
            <div className="max-w-md">
              <label htmlFor="settings-confidence-threshold" className="block text-xs uppercase text-muted mb-1">
                Confidence threshold — {confidenceThreshold}%
              </label>
              <input
                id="settings-confidence-threshold"
                type="range"
                min={0}
                max={100}
                value={confidenceThreshold}
                onChange={(e) => setConfidenceThreshold(Number(e.target.value))}
                className="w-full"
              />
              <p className="text-xs text-muted mt-1">
                How confident a categorization suggestion needs to be before it's applied automatically.
              </p>
            </div>
            <div className="flex items-center gap-3 mt-4 pt-4 border-t border-border">
              <button
                onClick={saveIntelligencePreferences}
                disabled={intelSaving || !intelDirty}
                className="bg-primary text-white hover:bg-primary-dark disabled:opacity-50 rounded-lg px-4 py-2 text-xs uppercase font-medium"
              >
                {intelSaving ? 'Saving…' : 'Save setting'}
              </button>
              <SaveStatus dirty={intelDirty} saving={intelSaving} justSaved={intelJustSaved} error={intelError} />
            </div>
          </>
        )}
      </SectionCard>

      <SectionCard icon={<Info size={18} />} title="Data" subtitle="Your imported statements and transaction history">
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <MetricTile label="Statements Imported" value={importStats ? importStats.totalStatements.toLocaleString('en-IN') : '—'} />
          <MetricTile label="Transactions" value={importStats ? importStats.totalTransactionsImported.toLocaleString('en-IN') : '—'} />
          <MetricTile label="Rows Skipped" value={importStats ? importStats.totalTransactionsSkipped.toLocaleString('en-IN') : '—'} />
          <MetricTile label="Last Import" value={formatDayMonthYear(importStats?.lastImportedAt)} />
        </div>
      </SectionCard>

      {changePasswordOpen && (
        <ChangePasswordModal
          onClose={() => setChangePasswordOpen(false)}
          onSuccess={() => setPasswordChangedAt(new Date().toISOString())}
        />
      )}
    </div>
  );
}
