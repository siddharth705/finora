import { useEffect, useState, type ReactNode } from 'react';
import { CheckCircle2, User, SlidersHorizontal, Sparkles, ShieldCheck, Info } from 'lucide-react';
import { userApi, workspaceApi, analyticsApi, type ImportStatistics } from '../api/endpoints';
import { useTheme } from '../context/ThemeContext';
import { ChangePasswordModal } from '../components/ChangePasswordModal';

// v1 scope is deliberately capabilities-first, not roadmap-first: every section below reflects a
// real, backed setting or fact. No "Coming soon" placeholders for 2FA, API keys, integrations,
// notifications, AI preferences, data export/delete, active sessions, or storage usage -- none of
// those exist yet, so none of them get a settings control. Add a section here the same day the
// backend capability it configures actually ships, not before.
//
// One thing deliberately absent, on purpose:
// - Plan/Subscription: there's no subscription model on the backend at all (no plan field on
//   User, no billing). A hardcoded "Free" label tends to outlive the "temporary" caveat next to
//   it, so it's hidden entirely rather than displayed as a fact that isn't one yet.
//
// Change Password is real now (see ChangePasswordModal) -- an authenticated
// POST /api/v1/users/me/change-password, genuinely separate from the forgot-password flow used
// by someone who can't log in at all. See that component's own doc comment for the full reasoning.

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

function initials(name: string): string {
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? '') + (parts[1]?.[0] ?? '')).toUpperCase() || '?';
}

// Mirrors the backend's PhoneMasking.mask() exactly (3 trailing visible digits, "+" preserved
// when present) -- used only in Security's confirmation row; Profile keeps the full number
// visible, since that's the "your info" context, not the "don't expose this on screen" one.
function maskPhone(phone: string): string {
  const hasCountryCodePrefix = phone.startsWith('+');
  const prefix = hasCountryCodePrefix ? '+' : '';
  const digits = hasCountryCodePrefix ? phone.slice(1) : phone;
  const VISIBLE_SUFFIX_LENGTH = 3;
  if (digits.length <= VISIBLE_SUFFIX_LENGTH) return phone;
  const visible = digits.slice(-VISIBLE_SUFFIX_LENGTH);
  return prefix + '•'.repeat(digits.length - VISIBLE_SUFFIX_LENGTH) + visible;
}

function formatMonthYear(iso: string | null | undefined): string {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleDateString('en-IN', { month: 'long', year: 'numeric' });
  } catch {
    return '—';
  }
}

function formatDayMonthYear(iso: string | null | undefined): string {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
  } catch {
    return '—';
  }
}

function formatRelativeTime(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return null;
  const days = Math.floor((Date.now() - then) / (1000 * 60 * 60 * 24));
  if (days < 1) return 'today';
  if (days === 1) return 'yesterday';
  if (days < 30) return `${days} days ago`;
  const months = Math.floor(days / 30);
  if (months < 12) return `${months} month${months === 1 ? '' : 's'} ago`;
  const years = Math.floor(months / 12);
  return `${years} year${years === 1 ? '' : 's'} ago`;
}

function SectionCard({ icon, title, subtitle, children }: { icon: ReactNode; title: string; subtitle: string; children: ReactNode }) {
  return (
    <section className="bg-card rounded-xl2 p-6 shadow-card border border-border">
      <div className="flex items-start gap-3 mb-5">
        <div className="w-9 h-9 rounded-lg bg-primary/10 text-primary flex items-center justify-center flex-shrink-0">{icon}</div>
        <div>
          <h2 className="font-serif text-lg font-semibold text-ink">{title}</h2>
          <p className="text-sm text-muted">{subtitle}</p>
        </div>
      </div>
      {children}
    </section>
  );
}

function VerifiedBadge() {
  return (
    <span className="inline-flex items-center gap-1 text-xs font-medium text-success bg-success-bg rounded-full px-2 py-0.5 flex-shrink-0">
      <CheckCircle2 size={12} /> Verified
    </span>
  );
}

/** Per-section save state: a section is either clean (nothing to show), dirty (unsaved edits),
 *  mid-save, freshly saved (a brief confirmation), or errored -- one indicator, four sections,
 *  so "did my change stick" always looks and behaves the same way across the page. */
function SaveStatus({ dirty, saving, justSaved, error }: { dirty: boolean; saving: boolean; justSaved: boolean; error: boolean }) {
  if (error) return <span className="text-danger text-xs">Couldn't save — please try again.</span>;
  if (saving) return <span className="text-muted text-xs">Saving…</span>;
  if (justSaved) return (
    <span className="text-success text-xs inline-flex items-center gap-1"><CheckCircle2 size={12} /> Saved</span>
  );
  if (dirty) return <span className="text-warning text-xs">Unsaved changes</span>;
  return null;
}

function MetricTile({ label, value }: { label: string; value: string }) {
  return (
    <div className="bg-bg rounded-lg border border-border px-4 py-3">
      <p className="text-xs uppercase text-muted mb-1">{label}</p>
      <p className="text-lg font-semibold text-ink">{value}</p>
    </div>
  );
}

export default function Settings() {
  const { theme, setTheme } = useTheme();
  const [email, setEmail] = useState('');
  const [fullName, setFullName] = useState('');
  const [savedFullName, setSavedFullName] = useState('');
  const [phoneNumber, setPhoneNumber] = useState('');
  const [phoneVerified, setPhoneVerified] = useState(false);
  const [createdAt, setCreatedAt] = useState<string | null>(null);
  const [passwordChangedAt, setPasswordChangedAt] = useState<string | null>(null);
  const [changePasswordOpen, setChangePasswordOpen] = useState(false);
  const [lowBalanceThreshold, setLowBalanceThreshold] = useState('2000');
  const [savedLowBalanceThreshold, setSavedLowBalanceThreshold] = useState('2000');
  const [timezone, setTimezone] = useState('Asia/Kolkata');
  const [savedTimezone, setSavedTimezone] = useState('Asia/Kolkata');
  const [timezones] = useState<string[]>(availableTimezones);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);

  const [profileSaving, setProfileSaving] = useState(false);
  const [profileJustSaved, setProfileJustSaved] = useState(false);
  const [profileError, setProfileError] = useState(false);

  const [prefsSaving, setPrefsSaving] = useState(false);
  const [prefsJustSaved, setPrefsJustSaved] = useState(false);
  const [prefsError, setPrefsError] = useState(false);

  const [confidenceThreshold, setConfidenceThreshold] = useState(90);
  const [savedConfidenceThreshold, setSavedConfidenceThreshold] = useState(90);
  const [intelLoading, setIntelLoading] = useState(true);
  const [intelSaving, setIntelSaving] = useState(false);
  const [intelJustSaved, setIntelJustSaved] = useState(false);
  const [intelError, setIntelError] = useState(false);

  const [importStats, setImportStats] = useState<ImportStatistics | null>(null);

  const profileDirty = fullName.trim() !== savedFullName;
  const prefsDirty = lowBalanceThreshold !== savedLowBalanceThreshold || timezone !== savedTimezone;
  const intelDirty = confidenceThreshold !== savedConfidenceThreshold;

  useEffect(() => {
    userApi.get().then((u) => {
      setEmail(u.email);
      setFullName(u.fullName);
      setSavedFullName(u.fullName);
      setPhoneNumber(u.phoneNumber);
      setPhoneVerified(u.phoneVerified);
      setCreatedAt(u.createdAt);
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
    // Best-effort — the Account section shows "—" for any stat that doesn't load rather than
    // blocking the rest of the page on it.
    analyticsApi.importStatistics().then(setImportStats).catch(() => {});
  }, []);

  async function saveProfile() {
    setProfileSaving(true);
    setProfileError(false);
    try {
      const updated = await userApi.update({ fullName: fullName.trim() });
      setFullName(updated.fullName);
      setSavedFullName(updated.fullName);
      setProfileJustSaved(true);
      setTimeout(() => setProfileJustSaved(false), 2000);
    } catch {
      setProfileError(true);
    } finally {
      setProfileSaving(false);
    }
  }

  async function savePreferences() {
    setPrefsSaving(true);
    setPrefsError(false);
    try {
      await userApi.update({ lowBalanceThreshold: parseFloat(lowBalanceThreshold), timezone });
      setSavedLowBalanceThreshold(lowBalanceThreshold);
      setSavedTimezone(timezone);
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

  if (loading) return <p className="text-muted">Loading…</p>;

  if (loadError) return <p className="text-muted">Couldn't load your settings — please try again later.</p>;

  return (
    <div className="space-y-6 max-w-3xl">
      <div>
        <h1 className="font-serif text-2xl font-semibold text-ink">Settings</h1>
        <p className="text-sm text-muted mt-1">Manage your profile, preferences and account settings.</p>
      </div>

      {/* Account summary -- reflects the SAVED name/email, not an in-progress edit below, so it
          never flickers ahead of what Profile's own "Unsaved changes" indicator is reporting. */}
      <div className="bg-card rounded-xl2 p-6 shadow-card border border-border flex items-center gap-4">
        <div className="w-14 h-14 rounded-full bg-primary flex items-center justify-center text-white text-lg font-semibold flex-shrink-0">
          {initials(savedFullName)}
        </div>
        <div className="min-w-0">
          <p className="font-serif text-xl font-semibold text-ink truncate">{savedFullName || 'Your account'}</p>
          <p className="text-sm text-muted truncate">{email}</p>
          <div className="flex items-center gap-3 mt-1.5 text-xs text-muted flex-wrap">
            {phoneVerified && (
              <span className="inline-flex items-center gap-1 text-success"><CheckCircle2 size={12} /> Phone verified</span>
            )}
            <span>Member since {formatMonthYear(createdAt)}</span>
          </div>
        </div>
      </div>

      <SectionCard icon={<User size={18} />} title="Profile" subtitle="Your personal information">
        <div className="grid md:grid-cols-2 gap-4">
          <div>
            <label className="block text-xs uppercase text-muted mb-1">Full name</label>
            <input
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              className="bg-card text-ink w-full border border-border rounded-lg px-3 py-2 text-sm"
            />
          </div>
          <div>
            <label className="block text-xs uppercase text-muted mb-1">Email</label>
            <input value={email} readOnly className="text-ink w-full border border-border rounded-lg px-3 py-2 text-sm bg-black/5" />
          </div>
          <div>
            <label className="block text-xs uppercase text-muted mb-1">Phone number</label>
            <div className="flex items-center gap-2">
              <input value={phoneNumber || '—'} readOnly className="text-ink w-full border border-border rounded-lg px-3 py-2 text-sm bg-black/5" />
              {phoneVerified && <VerifiedBadge />}
            </div>
          </div>
          <div>
            <label className="block text-xs uppercase text-muted mb-1">Member since</label>
            <input value={formatMonthYear(createdAt)} readOnly className="text-ink w-full border border-border rounded-lg px-3 py-2 text-sm bg-black/5" />
          </div>
        </div>
        <div className="flex items-center justify-end gap-3 mt-5 pt-4 border-t border-border">
          <SaveStatus dirty={profileDirty} saving={profileSaving} justSaved={profileJustSaved} error={profileError} />
          <button
            onClick={saveProfile}
            disabled={profileSaving || !profileDirty || !fullName.trim()}
            className="bg-primary text-white hover:bg-primary-dark disabled:opacity-50 rounded-lg px-4 py-2 text-xs uppercase font-medium"
          >
            {profileSaving ? 'Saving…' : 'Save changes'}
          </button>
        </div>
      </SectionCard>

      <SectionCard icon={<SlidersHorizontal size={18} />} title="Preferences" subtitle="Customize your Finora experience">
        <div className="grid md:grid-cols-3 gap-4">
          <div>
            <label className="block text-xs uppercase text-muted mb-1">Low balance alert</label>
            <input
              type="number"
              value={lowBalanceThreshold}
              onChange={(e) => setLowBalanceThreshold(e.target.value)}
              className="bg-card text-ink w-full border border-border rounded-lg px-3 py-2 text-sm"
            />
          </div>
          <div>
            <label className="block text-xs uppercase text-muted mb-1">Timezone</label>
            <select
              value={timezone}
              onChange={(e) => setTimezone(e.target.value)}
              className="bg-card text-ink w-full border border-border rounded-lg px-3 py-2 text-sm"
            >
              {!timezones.includes(timezone) && <option value={timezone}>{timezone}</option>}
              {timezones.map((tz) => <option key={tz} value={tz}>{tz}</option>)}
            </select>
          </div>
          <div>
            <label className="block text-xs uppercase text-muted mb-1">Theme</label>
            <select
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
            <SaveStatus dirty={prefsDirty} saving={prefsSaving} justSaved={prefsJustSaved} error={prefsError} />
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

      <SectionCard
        icon={<Sparkles size={18} />}
        title="Intelligence Preferences"
        subtitle="Control how Finora reviews and understands your financial documents"
      >
        {intelLoading ? (
          <p className="text-muted text-sm">Loading…</p>
        ) : (
          <>
            <div className="max-w-md">
              <label className="block text-xs uppercase text-muted mb-1">
                Confidence threshold — {confidenceThreshold}%
              </label>
              <input
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

      <SectionCard icon={<ShieldCheck size={18} />} title="Security" subtitle="Manage your password and account protection">
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
        <div className="flex items-center justify-between py-3 text-sm">
          <div>
            <p className="text-ink font-medium">Phone verification</p>
            <p className="text-muted text-xs">{phoneNumber ? maskPhone(phoneNumber) : 'No phone number on file'}</p>
          </div>
          {phoneVerified ? <VerifiedBadge /> : <span className="text-xs text-muted flex-shrink-0">Not verified</span>}
        </div>
      </SectionCard>

      <SectionCard icon={<Info size={18} />} title="Account" subtitle="Information about your Finora workspace">
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <MetricTile label="Statements Imported" value={importStats ? importStats.totalStatements.toLocaleString('en-IN') : '—'} />
          <MetricTile label="Transactions" value={importStats ? importStats.totalTransactionsImported.toLocaleString('en-IN') : '—'} />
          <MetricTile label="Last Import" value={formatDayMonthYear(importStats?.lastImportedAt)} />
          <MetricTile label="Member Since" value={formatMonthYear(createdAt)} />
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
