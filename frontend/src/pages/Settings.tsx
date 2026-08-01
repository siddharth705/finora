import { useEffect, useState } from 'react';
import { userApi, workspaceApi } from '../api/endpoints';
import { useTheme } from '../context/ThemeContext';

// Financial Intelligence Workspace, System Settings module. Only autoApplyConfidenceThreshold is
// real/persisted (see backend WorkspaceSettingsService's class comment) -- everything else here
// is a static label, not backed by any API call, so the page is honest about what's actually
// configurable today versus planned.
const PLANNED_SETTINGS = [
  'Auto-categorization on import (always on today, not yet toggleable)',
  'Email digest frequency',
  'Default currency',
  'Duplicate-detection sensitivity',
];

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

export default function Settings() {
  const { theme, setTheme } = useTheme();
  const [email, setEmail] = useState('');
  const [fullName, setFullName] = useState('');
  const [lowBalanceThreshold, setLowBalanceThreshold] = useState('2000');
  const [timezone, setTimezone] = useState('Asia/Kolkata');
  const [timezones] = useState<string[]>(availableTimezones);
  const [loading, setLoading] = useState(true);
  const [saved, setSaved] = useState(false);

  const [confidenceThreshold, setConfidenceThreshold] = useState(90);
  const [settingsLoading, setSettingsLoading] = useState(true);
  const [settingsSaved, setSettingsSaved] = useState(false);
  const [settingsSaving, setSettingsSaving] = useState(false);

  useEffect(() => {
    userApi.get().then((u) => {
      setEmail(u.email);
      setFullName(u.fullName);
      setLowBalanceThreshold(String(u.lowBalanceThreshold));
      setTimezone(u.timezone);
      setLoading(false);
    });
    workspaceApi.getSettings().then((s) => {
      setConfidenceThreshold(s.autoApplyConfidenceThreshold);
      setSettingsLoading(false);
    }).catch(() => setSettingsLoading(false));
  }, []);

  async function savePreferences() {
    await userApi.update({ lowBalanceThreshold: parseFloat(lowBalanceThreshold), timezone });
    setSaved(true);
    setTimeout(() => setSaved(false), 2000);
  }

  async function saveWorkspaceSettings() {
    setSettingsSaving(true);
    try {
      const s = await workspaceApi.updateSettings({ autoApplyConfidenceThreshold: confidenceThreshold });
      setConfidenceThreshold(s.autoApplyConfidenceThreshold);
      setSettingsSaved(true);
      setTimeout(() => setSettingsSaved(false), 2000);
    } finally {
      setSettingsSaving(false);
    }
  }

  if (loading) return <p className="text-muted">Loading…</p>;

  return (
    <div className="space-y-6">
      <div className="bg-card rounded shadow p-5">
        <p className="text-xs uppercase text-gray-500 mb-4">Profile</p>
        <div className="grid md:grid-cols-2 gap-4">
          <div>
            <label className="block text-xs uppercase text-gray-500 mb-1">Full name</label>
            <input value={fullName} readOnly className="text-ink w-full border rounded px-3 py-2 text-sm bg-black/5" />
          </div>
          <div>
            <label className="block text-xs uppercase text-gray-500 mb-1">Email</label>
            <input value={email} readOnly className="text-ink w-full border rounded px-3 py-2 text-sm bg-black/5" />
          </div>
        </div>
        <p className="text-xs text-gray-400 mt-3">Editing name/email isn't wired up yet — this reflects what you registered with.</p>
      </div>

      <div className="bg-card rounded shadow p-5">
        <p className="text-xs uppercase text-gray-500 mb-4">Preferences</p>
        <div className="grid md:grid-cols-2 gap-4">
          <div>
            <label className="block text-xs uppercase text-gray-500 mb-1">Low balance alert threshold</label>
            <input type="number" value={lowBalanceThreshold} onChange={(e) => setLowBalanceThreshold(e.target.value)} className="bg-card text-ink w-full border rounded px-3 py-2 text-sm" />
          </div>
          <div>
            <label className="block text-xs uppercase text-gray-500 mb-1">Theme</label>
            <select
              value={theme}
              onChange={(e) => setTheme(e.target.value as 'light' | 'dark' | 'system')}
              className="bg-card text-ink w-full border rounded px-3 py-2 text-sm"
            >
              <option value="light">Light</option>
              <option value="dark">Dark</option>
              <option value="system">System</option>
            </select>
          </div>
          <div>
            <label className="block text-xs uppercase text-gray-500 mb-1">Timezone</label>
            <select
              value={timezone}
              onChange={(e) => setTimezone(e.target.value)}
              className="bg-card text-ink w-full border rounded px-3 py-2 text-sm"
            >
              {!timezones.includes(timezone) && <option value={timezone}>{timezone}</option>}
              {timezones.map((tz) => <option key={tz} value={tz}>{tz}</option>)}
            </select>
            <p className="text-[11px] text-gray-400 mt-1">Used for the Dashboard's "Good morning/afternoon/evening" greeting.</p>
          </div>
        </div>
        <button onClick={savePreferences} className="mt-4 bg-primary text-white hover:bg-primary-dark rounded px-4 py-2 text-xs uppercase">
          Save preferences
        </button>
        {saved && <span className="ml-3 text-success text-sm">Saved.</span>}
        <p className="text-xs text-gray-400 mt-2">
          The low balance threshold and timezone save when you click Save — theme applies and syncs to your account instantly when changed above.
        </p>
      </div>

      <div className="bg-card rounded shadow p-5">
        <p className="text-xs uppercase text-gray-500 mb-4">System Settings</p>
        {settingsLoading ? (
          <p className="text-muted text-sm">Loading…</p>
        ) : (
          <>
            <div className="max-w-md">
              <label className="block text-xs uppercase text-gray-500 mb-1">
                Auto-apply confidence threshold — {confidenceThreshold}%
              </label>
              <input
                type="range"
                min={0}
                max={100}
                value={confidenceThreshold}
                onChange={(e) => setConfidenceThreshold(Number(e.target.value))}
                className="w-full"
              />
              <p className="text-[11px] text-gray-400 mt-1">
                How confident a categorization suggestion needs to be before it's applied automatically.
                Saved and ready — not yet gating live categorization decisions, since suggestions don't
                carry a numeric confidence score through the write path yet.
              </p>
            </div>
            <button
              onClick={saveWorkspaceSettings}
              disabled={settingsSaving}
              className="mt-4 bg-primary text-white hover:bg-primary-dark rounded px-4 py-2 text-xs uppercase disabled:opacity-60"
            >
              {settingsSaving ? 'Saving…' : 'Save setting'}
            </button>
            {settingsSaved && <span className="ml-3 text-success text-sm">Saved.</span>}

            <div className="mt-5 pt-4 border-t border-dashed">
              {PLANNED_SETTINGS.map((label) => (
                <div key={label} className="flex items-center justify-between py-2 text-sm">
                  <span className="text-ink/70">{label}</span>
                  <span className="border border-border rounded px-3 py-1 text-[11px] uppercase text-gray-400">
                    Coming in a future release
                  </span>
                </div>
              ))}
            </div>
          </>
        )}
      </div>

      <div className="bg-card rounded shadow p-5">
        <p className="text-xs uppercase text-gray-500 mb-4">Security</p>
        <div className="flex items-center justify-between border-b border-dashed py-3 text-sm">
          <span>Two-factor authentication</span>
          <button className="border border-border rounded px-3 py-1.5 text-xs uppercase text-gray-400 cursor-not-allowed" disabled>
            Coming soon
          </button>
        </div>
        <div className="flex items-center justify-between py-3 text-sm">
          <span>Google sign-in</span>
          <button className="border border-border rounded px-3 py-1.5 text-xs uppercase text-gray-400 cursor-not-allowed" disabled>
            Coming soon
          </button>
        </div>
      </div>

      <div className="bg-card rounded shadow p-5">
        <p className="text-xs uppercase text-gray-500 mb-4">Subscription</p>
        <div className="flex items-center justify-between">
          <div>
            <p className="font-serif text-lg font-semibold">Free plan</p>
            <p className="text-sm text-ink/60">Up to 2 accounts, CSV import, budgets & goals.</p>
          </div>
          <button className="bg-primary text-ink rounded px-4 py-2 text-xs uppercase font-semibold cursor-not-allowed opacity-60" disabled>
            Upgrade to Premium
          </button>
        </div>
        <p className="text-xs text-gray-400 mt-3">Billing isn't built yet — no payment gateway is wired up, so this button is disabled rather than pretending to work.</p>
      </div>
    </div>
  );
}
