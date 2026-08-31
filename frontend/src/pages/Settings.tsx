import { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { SlidersHorizontal, Sparkles, ShieldCheck, Info, Smartphone, UserX, X, Mail, RefreshCw } from 'lucide-react';
import {
  authApi, userApi, workspaceApi, analyticsApi, deviceApi, gmailApi,
  type ImportStatistics, type DeviceSession, type GmailConnectionStatus,
} from '../api/endpoints';
import { useTheme } from '../context/ThemeContext';
import { ChangePasswordModal } from '../components/ChangePasswordModal';
import { DeactivateAccountModal } from '../components/DeactivateAccountModal';
import { DeleteAccountModal } from '../components/DeleteAccountModal';
import { ExportDataModal } from '../components/ExportDataModal';
import { maskPhone } from '../lib/maskPhone';
import { parsePositiveAmount } from '../lib/validation';
import { formatDayMonthYear, formatRelativeTime, SectionCard, VerifiedBadge, SaveStatus, MetricTile } from '../components/AccountUI';
import { clearSessionAndRedirect } from '../api/client';
import { useDelayedLoading } from '../hooks/useDelayedLoading';
import { Skeleton } from '../design-system/Skeleton';
import { Button } from '../design-system/Button';
import { IconButton } from '../design-system/IconButton';

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
// this page is "how Fynora behaves for you," not "who you are." Change Password stays here (a
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

/**
 * "Expires in 2 days" for the absolute session cap.
 *
 * Answers the question a user actually has when they get signed out — "why did that happen, and
 * when will it happen again" — which is most of what makes an automatic sign-out feel like a fault
 * rather than a policy. Deliberately coarse: hours below a day, days above it. A live countdown
 * would imply a precision the idle timeout can undercut at any moment, since a session can also
 * end 30 minutes after the last activity, well before this date.
 *
 * Null when the backend reports no cap (it is configurable, and 0 disables it), rather than
 * inventing a date.
 */
function expiresInLabel(sessionExpiresAt: string | null): string | null {
  if (!sessionExpiresAt) return null;
  const ms = new Date(sessionExpiresAt).getTime() - Date.now();
  if (Number.isNaN(ms)) return null;
  if (ms <= 0) return 'Expires shortly';
  const hours = Math.floor(ms / 3_600_000);
  if (hours < 1) return 'Expires within the hour';
  if (hours < 24) return `Expires in ${hours} hour${hours === 1 ? '' : 's'}`;
  const days = Math.round(hours / 24);
  return `Expires in ${days} day${days === 1 ? '' : 's'}`;
}

/** One-shot message for the `?gmail=` query param GoogleOAuthController's callback redirect
 *  lands with -- see that controller's own doc comment: the outcome travels as a query parameter
 *  specifically so no token or error detail can end up in it. Null for anything else (including
 *  no param at all), which is the signal to render nothing. */
function gmailCallbackMessage(gmail: string | null): { text: string; isError: boolean } | null {
  switch (gmail) {
    case 'connected': return { text: 'Gmail connected.', isError: false };
    case 'declined': return { text: 'Gmail connection was cancelled.', isError: false };
    case 'invalid':
    case 'failed':
      return { text: "Couldn't connect Gmail -- please try again.", isError: true };
    default: return null;
  }
}

function gmailLastSyncedLabel(status: GmailConnectionStatus): string {
  const label = formatRelativeTime(status.lastDiscoveryAt);
  return label ? `Last synced ${label}` : 'Never synced yet';
}

// D-19 Step 1 (Trust Center): grantedScopes has been on GmailConnectionStatusDto since C5.4,
// never rendered. `openid` has no user-meaningful description of its own (it's what makes `sub`
// available, not a capability over the user's data) -- skipped rather than shown as a raw URI.
// "Read Gmail messages", not "read receipts only": gmail.readonly is what Google's consent
// screen actually grants access to (the whole mailbox, at the OAuth layer) -- the trusted-sender
// gate (C3) is Fynora's own policy restriction on top of that, not something this scope itself
// enforces, and this list should say what was actually granted, not what Fynora chooses to do
// with it.
const SCOPE_LABELS: Record<string, string> = {
  'https://www.googleapis.com/auth/gmail.readonly': 'Read Gmail messages',
  'https://www.googleapis.com/auth/userinfo.email': 'See your email address',
};
function gmailPermissionLabels(scopes: string[]): string[] {
  return scopes.map((s) => SCOPE_LABELS[s]).filter((label): label is string => !!label);
}

export default function Settings() {
  const { theme, setTheme } = useTheme();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [phoneNumber, setPhoneNumber] = useState('');
  const [phoneVerified, setPhoneVerified] = useState(false);
  const [passwordChangedAt, setPasswordChangedAt] = useState<string | null>(null);
  // 'PASSWORD' until userApi.get() resolves -- every "re-enter your current password" modal below
  // gates its Google-vs-password branch on this, so defaulting to the ordinary (more common) case
  // means a slow load never flashes the Google button for a password account or vice versa in the
  // split second before the real value arrives; the modals aren't openable until then anyway
  // (their Manage Your Account buttons live below this same load).
  const [signInMethod, setSignInMethod] = useState<'PASSWORD' | 'GOOGLE'>('PASSWORD');
  const [changePasswordOpen, setChangePasswordOpen] = useState(false);
  const [deactivateOpen, setDeactivateOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [exportOpen, setExportOpen] = useState(false);
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
  const [importStatsFailed, setImportStatsFailed] = useState(false);

  const [sessions, setSessions] = useState<DeviceSession[]>([]);
  const [sessionsLoading, setSessionsLoading] = useState(true);
  const [sessionsError, setSessionsError] = useState(false);
  const [revokingId, setRevokingId] = useState<string | null>(null);

  const [gmailStatus, setGmailStatus] = useState<GmailConnectionStatus | null>(null);
  const [gmailLoading, setGmailLoading] = useState(true);
  const [gmailError, setGmailError] = useState(false);
  const [gmailConnecting, setGmailConnecting] = useState(false);
  const [gmailSyncing, setGmailSyncing] = useState(false);
  const [gmailSyncError, setGmailSyncError] = useState<string | null>(null);
  // Distinct from gmailError above: that one gates the "couldn't load the connection at all" page
  // state, rendered only while gmailStatus is still null. Connect/disconnect fail AFTER a status
  // has already loaded successfully, so reusing gmailError for them would set a flag nothing on
  // screen ever reads -- a silent failure the user sees as a button that just stops saying
  // "Connecting…" with no explanation. Caught in self-review, not by a test failing.
  const [gmailActionError, setGmailActionError] = useState<string | null>(null);
  const [gmailDisconnecting, setGmailDisconnecting] = useState(false);
  // The one-shot callback message reads from the URL once and is then dismissed by user action
  // (or a fresh status load below removing the param) -- not re-derived from searchParams on
  // every render, the same reason SESSION_ENDED_REASON_KEY is read once and cleared, not left
  // live in the URL for a refresh to replay.
  const [gmailCallbackNotice] = useState(() => gmailCallbackMessage(searchParams.get('gmail')));

  const prefsDirty = lowBalanceThreshold !== savedLowBalanceThreshold || timezone !== savedTimezone;
  const intelDirty = confidenceThreshold !== savedConfidenceThreshold;

  // General and Security's Password/Phone-verification rows both come from the same userApi.get()
  // call, so they share one skeleton timer -- Active Sessions, AI, and Connected Apps each fetch
  // independently and get their own, so none of them has to wait on `loading` to render.
  const showAccountSkeleton = useDelayedLoading(loading);
  const showSessionsSkeleton = useDelayedLoading(sessionsLoading);
  const showIntelSkeleton = useDelayedLoading(intelLoading);
  const showGmailSkeleton = useDelayedLoading(gmailLoading);

  const prefsJustSavedTimeout = useRef<ReturnType<typeof setTimeout> | null>(null);
  const intelJustSavedTimeout = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Both "just saved" flashes fire a setTimeout that outlives the request they came from -- if the
  // component unmounts (navigating away, or a test finishing) before the 2s elapses, the timer
  // still fires into a torn-down tree. Clearing on unmount is the same discipline this codebase
  // applies to async work elsewhere (see the AfterCommit pattern on the backend).
  useEffect(() => {
    return () => {
      if (prefsJustSavedTimeout.current) clearTimeout(prefsJustSavedTimeout.current);
      if (intelJustSavedTimeout.current) clearTimeout(intelJustSavedTimeout.current);
    };
  }, []);

  function loadSessions() {
    setSessionsLoading(true);
    setSessionsError(false);
    deviceApi.list()
      .then(setSessions)
      .catch(() => setSessionsError(true))
      .finally(() => setSessionsLoading(false));
  }

  function loadGmailStatus() {
    setGmailLoading(true);
    setGmailError(false);
    gmailApi.status()
      .then(setGmailStatus)
      .catch(() => setGmailError(true))
      .finally(() => setGmailLoading(false));
  }

  async function handleGmailConnect() {
    setGmailConnecting(true);
    setGmailActionError(null);
    try {
      const { authorizationUrl } = await gmailApi.connect();
      // A real browser navigation, not client-side routing -- Google's consent screen is a
      // different origin entirely, the same reason GoogleOAuthController.connect()'s own doc
      // comment gives for returning the URL rather than issuing a redirect itself.
      window.location.href = authorizationUrl;
    } catch {
      setGmailConnecting(false);
      setGmailActionError("Couldn't start the Gmail connection -- please try again.");
    }
  }

  async function handleGmailSyncNow() {
    setGmailSyncing(true);
    setGmailSyncError(null);
    try {
      await gmailApi.syncNow();
      loadGmailStatus();
    } catch (err) {
      // The cooldown (429) and a dead grant (409) are the two outcomes worth telling the user
      // apart from a generic failure -- everything else collapses to one message, same as the
      // Active Sessions list's own best-effort error handling above.
      const status = (err as { response?: { status?: number } })?.response?.status;
      setGmailSyncError(
        status === 429 ? 'Gmail was synced recently -- try again in a moment.'
          : status === 409 ? 'This connection needs to be reconnected -- disconnect and connect again.'
          : "Gmail sync didn't complete -- try again in a moment.");
    } finally {
      setGmailSyncing(false);
    }
  }

  async function handleGmailDisconnect() {
    setGmailDisconnecting(true);
    setGmailActionError(null);
    try {
      await gmailApi.disconnect();
      loadGmailStatus();
    } catch {
      setGmailActionError("Couldn't disconnect Gmail -- please try again.");
    } finally {
      setGmailDisconnecting(false);
    }
  }

  useEffect(() => {
    userApi.get().then((u) => {
      // '' not null: a Google Sign-In account has no phone number on file at all (see
      // AuthService.createGoogleUserRecord's own doc comment) -- the empty string already
      // renders correctly below ("No phone number on file"), no separate null case needed.
      setPhoneNumber(u.phoneNumber ?? '');
      setPhoneVerified(u.phoneVerified);
      setPasswordChangedAt(u.passwordChangedAt);
      setSignInMethod(u.signInMethod);
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
    // blocking the rest of the page on it. That part is still deliberate.
    //
    // What it did not account for: "—" for a failed request is indistinguishable from "—" for a
    // user who has genuinely never imported anything, so a broken endpoint reads as an empty
    // account. The failure is now recorded and labelled; the page still renders regardless, which
    // was the actual intent.
    analyticsApi.importStatistics().then(setImportStats).catch(() => setImportStatsFailed(true));
    loadSessions();
    loadGmailStatus();
    // Strip ?gmail=... from the URL once read (gmailCallbackNotice's initializer already captured
    // it) so a page refresh doesn't replay a stale "Gmail connected" message.
    if (searchParams.has('gmail')) {
      const next = new URLSearchParams(searchParams);
      next.delete('gmail');
      setSearchParams(next, { replace: true });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
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
      if (prefsJustSavedTimeout.current) clearTimeout(prefsJustSavedTimeout.current);
      prefsJustSavedTimeout.current = setTimeout(() => setPrefsJustSaved(false), 2000);
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
      if (intelJustSavedTimeout.current) clearTimeout(intelJustSavedTimeout.current);
      intelJustSavedTimeout.current = setTimeout(() => setIntelJustSaved(false), 2000);
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

  // The account was just deactivated -- UserAccountLifecycleService.deactivate already revoked
  // every refresh token server-side, so there is nothing left to be signed in to.
  //
  // Bug fix, confirmed against a real browser (not just this app's mocked-useAuth test suite):
  // this used to call AuthContext's logout() and then navigate('/auth', { state: { message } }),
  // the way ResetPassword.tsx hands AuthEntry's PasswordStep a one-shot confirmation. That works
  // for ResetPassword because it isn't behind ProtectedRoute. Here, logout() calls setToken(null)
  // -- a REACT STATE update -- which App.tsx's ProtectedRoute (wrapping /app/settings) reacts to
  // immediately by client-side-routing to /auth itself, via its own stateless
  // <Navigate to="/auth" replace />. That reactive redirect runs and mounts a throwaway AuthEntry
  // instance -- landing on its identify step, since a bare <Navigate> carries no deep-link state
  // -- before the browser's actual window.location.href navigation below ever fires. The
  // SESSION_ENDED_REASON_KEY this function is about to set would land in storage while that
  // instance still exists, only for it to be torn down and replaced by the real, hard-reloaded
  // page moments later -- fragile either way, whether or not that particular instance happens to
  // read the key back out before dying.
  //
  // The fix is to never touch AuthContext's React state at all, the same way client.ts's
  // (now-exported) clearSessionAndRedirect already avoids this. Second bug fix, caught in review:
  // the first version of this fix hand-rolled clearSessionAndRedirect's own storage-clearing logic
  // a second time instead of calling it, AND dropped the best-effort authApi.logout() call that
  // AuthContext.logout() makes -- which is what actually clears the httpOnly refresh-token cookie
  // in the browser (the token itself is already revoked server-side either way, so this is a
  // browser-hygiene fix, not a security one: without it, "you'll be signed out everywhere" left a
  // stale cookie sitting in the browser). The access token here is still fully valid at the moment
  // of this call (unlike clearSessionAndRedirect's own callers, which only ever run after a refresh
  // has already failed), so this call can actually succeed.
  function handleDeactivated() {
    authApi.logout().catch(() => {});
    clearSessionAndRedirect('Your account has been deactivated. Sign in again any time to reactivate it.');
  }

  // Same reasoning as handleDeactivated above -- requestDeletion already revoked every refresh
  // token server-side, this just clears the browser's own httpOnly cookie best-effort and redirects.
  function handleDeleted() {
    authApi.logout().catch(() => {});
    clearSessionAndRedirect("Your account has been permanently deleted. You've been signed out everywhere.");
  }

  return (
    <div className="space-y-6 max-w-3xl">
      <div>
        <h1 className="font-serif text-2xl font-semibold text-ink">Settings</h1>
        <p className="text-sm text-muted mt-1">Manage your preferences, security, and account data.</p>
      </div>

      <SectionCard icon={<SlidersHorizontal size={18} />} title="General" subtitle="Customize your Fynora experience">
        {loading ? (
          <Skeleton.Region label="Loading your preferences">
            {showAccountSkeleton && <GeneralSkeletonFields />}
          </Skeleton.Region>
        ) : loadError ? (
          <p className="text-muted text-sm">Couldn't load your settings — please try again later.</p>
        ) : (
          <>
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
                <Button className="uppercase" onClick={savePreferences} disabled={!prefsDirty} loading={prefsSaving}>
                  Save preferences
                </Button>
              </div>
            </div>
          </>
        )}
      </SectionCard>

      <SectionCard icon={<ShieldCheck size={18} />} title="Security" subtitle="Manage your password, verification, and active sessions">
        {loading ? (
          <Skeleton.Region label="Loading your security settings">
            {showAccountSkeleton && <SecurityBasicsSkeletonFields />}
          </Skeleton.Region>
        ) : loadError ? (
          <p className="text-muted text-sm py-3">Couldn't load your settings — please try again later.</p>
        ) : (
          <>
            <div className="border-b border-border py-3 text-sm">
              <p className="text-ink font-medium">Password</p>
              <p className="text-muted text-xs mt-0.5">
                {formatRelativeTime(passwordChangedAt) ? `Last changed ${formatRelativeTime(passwordChangedAt)}` : 'Never changed'}
              </p>
              <p className="text-muted text-[11px] mt-1">Keep your account secure by using a unique password.</p>
              <Button variant="secondary" size="sm" className="mt-3 uppercase" onClick={() => setChangePasswordOpen(true)}>
                Change Password
              </Button>
            </div>
            <div className="flex items-center justify-between border-b border-border py-3 text-sm">
              <div>
                <p className="text-ink font-medium">Phone verification</p>
                <p className="text-muted text-xs">{phoneNumber ? maskPhone(phoneNumber) : 'No phone number on file'}</p>
              </div>
              {phoneVerified ? <VerifiedBadge /> : <span className="text-xs text-muted flex-shrink-0">Not verified</span>}
            </div>
          </>
        )}

        <div className="pt-3">
          <p className="text-ink font-medium text-sm">Active Sessions</p>
          <p className="text-muted text-[11px] mt-0.5 mb-3">
            Every device currently signed in to your account. Signing one out here ends that
            session the next time it needs to refresh. Sessions also end on their own — after 30
            minutes of inactivity, or 7 days after signing in, whichever comes first.
          </p>
          {sessionsLoading ? (
            <Skeleton.Region label="Loading your active sessions">
              {showSessionsSkeleton && <ActiveSessionsSkeletonFields />}
            </Skeleton.Region>
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
                      <p className="text-sm text-ink truncate">
                        {deviceLabel(s)}
                        {s.current && (
                          <span className="ml-2 text-[10px] font-medium uppercase tracking-wide text-success bg-success-bg rounded px-1.5 py-0.5 align-middle">
                            This device
                          </span>
                        )}
                      </p>
                      <p className="text-[11px] text-muted truncate">
                        {s.lastSeenAt ? `Last active ${formatRelativeTime(s.lastSeenAt) ?? 'recently'}` : 'Not used yet'}
                        {s.lastSeenIp ? ` · ${s.lastSeenIp}` : ''}
                      </p>
                      <p className="text-[11px] text-muted truncate">
                        Signed in {formatRelativeTime(s.sessionStartedAt) ?? 'recently'}
                        {expiresInLabel(s.sessionExpiresAt) ? ` · ${expiresInLabel(s.sessionExpiresAt)}` : ''}
                      </p>
                    </div>
                  </div>
                  <IconButton
                    size="sm"
                    variant="danger"
                    icon={<X size={14} />}
                    aria-label="Sign out this device"
                    title="Sign out this device"
                    loading={revokingId === s.id}
                    onClick={() => revokeSession(s.id)}
                  />
                </div>
              ))}
            </div>
          )}
        </div>
      </SectionCard>

      <SectionCard icon={<Sparkles size={18} />} title="AI" subtitle="Control how Fynora reviews and understands your financial documents">
        {intelLoading ? (
          <Skeleton.Region label="Loading your AI settings">
            {showIntelSkeleton && <AISkeletonFields />}
          </Skeleton.Region>
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
              <Button className="uppercase" onClick={saveIntelligencePreferences} disabled={!intelDirty} loading={intelSaving}>
                Save setting
              </Button>
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
        {importStatsFailed && (
          <p className="text-xs text-warning mt-3">
            Couldn't load these statistics just now — they're unavailable, not zero.
          </p>
        )}
        <div className="pt-4 mt-4 border-t border-border">
          <p className="text-ink font-medium text-sm">Export My Data</p>
          <p className="text-muted text-[11px] mt-1 mb-3">
            Download a ZIP of everything in your account, including your original bank statement
            files.
          </p>
          <Button
            variant="secondary"
            size="sm"
            className="uppercase"
            disabled={loading || loadError}
            title={loadError ? "Couldn't load your account details" : undefined}
            onClick={() => setExportOpen(true)}
          >
            Export My Data
          </Button>
        </div>
      </SectionCard>

      <SectionCard icon={<Mail size={18} />} title="Connected Apps" subtitle="Link external accounts Fynora can read transactions from">
        {gmailCallbackNotice && (
          <p className={`text-xs mb-3 ${gmailCallbackNotice.isError ? 'text-danger' : 'text-success'}`}>
            {gmailCallbackNotice.text}
          </p>
        )}
        {gmailLoading ? (
          <Skeleton.Region label="Loading your Gmail connection">
            {showGmailSkeleton && <GmailSkeletonFields />}
          </Skeleton.Region>
        ) : gmailError && !gmailStatus ? (
          <p className="text-xs text-danger">Couldn't load your Gmail connection — please try again later.</p>
        ) : !gmailStatus?.available ? (
          <p className="text-xs text-muted italic">Gmail sync isn't available on this deployment yet.</p>
        ) : !gmailStatus.connected && gmailStatus.needsReconnect ? (
          <div className="border border-warning/40 rounded-lg px-3 py-2.5">
            <div className="flex items-center justify-between gap-3">
              <div className="min-w-0">
                <p className="text-sm text-ink font-medium flex items-center gap-2">
                  Gmail
                  <span className="text-[10px] font-medium uppercase tracking-wide text-warning bg-warning-bg rounded px-1.5 py-0.5">
                    Needs reconnect
                  </span>
                </p>
                {gmailStatus.googleEmail && (
                  <p className="text-[11px] text-muted truncate mt-0.5">{gmailStatus.googleEmail}</p>
                )}
                <p className="text-[11px] text-muted mt-1">
                  Google stopped accepting this connection -- reconnect to keep finding receipts.
                </p>
              </div>
              <Button size="sm" className="flex-shrink-0 uppercase" loading={gmailConnecting} onClick={handleGmailConnect}>
                Reconnect Gmail
              </Button>
            </div>
            {gmailActionError && <p className="text-xs text-danger mt-2">{gmailActionError}</p>}
          </div>
        ) : !gmailStatus.connected ? (
          <div>
            <div className="flex items-center justify-between gap-3">
              <div>
                <p className="text-sm text-ink font-medium">Gmail</p>
                <p className="text-[11px] text-muted mt-0.5">
                  Automatically detect receipts from your inbox — nothing is imported without your review.
                </p>
              </div>
              <Button size="sm" className="flex-shrink-0 uppercase" loading={gmailConnecting} onClick={handleGmailConnect}>
                Connect Gmail
              </Button>
            </div>
            {gmailActionError && <p className="text-xs text-danger mt-2">{gmailActionError}</p>}
          </div>
        ) : (
          <div className="border border-border rounded-lg px-3 py-2.5">
            <div className="flex items-center justify-between gap-3">
              <div className="min-w-0">
                <p className="text-sm text-ink font-medium flex items-center gap-2">
                  Gmail
                  <span className="text-[10px] font-medium uppercase tracking-wide text-success bg-success-bg rounded px-1.5 py-0.5">
                    Connected
                  </span>
                </p>
                <p className="text-[11px] text-muted truncate mt-0.5">{gmailStatus.googleEmail}</p>
                <p className="text-[11px] text-muted mt-1">{gmailLastSyncedLabel(gmailStatus)}</p>
                {gmailPermissionLabels(gmailStatus.grantedScopes).length > 0 && (
                  <p className="text-[11px] text-muted mt-1">
                    <span className="text-ink">Permissions:</span> {gmailPermissionLabels(gmailStatus.grantedScopes).join(', ')}
                    {' — never sent, modified, or deleted'}
                  </p>
                )}
              </div>
              <IconButton
                size="sm"
                className="flex-shrink-0"
                icon={<RefreshCw size={14} />}
                aria-label="Sync Gmail now"
                title="Sync now"
                loading={gmailSyncing}
                onClick={handleGmailSyncNow}
              />
            </div>

            <div className="grid grid-cols-2 gap-3 mt-3">
              <MetricTile label="Transactions Found" value={gmailStatus.transactionsFound.toLocaleString('en-IN')} />
              <MetricTile label="Needs Review" value={gmailStatus.needsReview.toLocaleString('en-IN')} />
            </div>

            {gmailSyncError && <p className="text-xs text-danger mt-2">{gmailSyncError}</p>}
            {gmailActionError && <p className="text-xs text-danger mt-2">{gmailActionError}</p>}

            <div className="flex items-center gap-3 mt-3 pt-3 border-t border-border">
              {gmailStatus.needsReview > 0 && (
                <Button size="sm" className="uppercase" onClick={() => navigate('/app/settings/gmail/review')}>
                  Review {gmailStatus.needsReview}
                </Button>
              )}
              <Button variant="secondary" size="sm" className="uppercase" loading={gmailDisconnecting} onClick={handleGmailDisconnect}>
                Disconnect
              </Button>
            </div>
          </div>
        )}
      </SectionCard>

      <SectionCard icon={<UserX size={18} />} title="Manage Your Account" subtitle="Deactivate or permanently delete your Fynora account">
        <div className="pt-1 pb-4 border-b border-border">
          <p className="text-ink font-medium text-sm">Deactivate Account</p>
          <p className="text-muted text-[11px] mt-1 mb-3">
            Temporarily disable your account. You'll be signed out everywhere and won't be able to
            sign in until you reactivate -- your data is retained securely, and reactivating is as
            simple as signing in again.
          </p>
          <Button
            variant="secondary"
            size="sm"
            className="uppercase"
            disabled={loading || loadError}
            title={loadError ? "Couldn't load your account details" : undefined}
            onClick={() => setDeactivateOpen(true)}
          >
            Deactivate Account
          </Button>
        </div>
        <div className="pt-4">
          <p className="text-ink font-medium text-sm">Delete Account</p>
          <p className="text-muted text-[11px] mt-1 mb-3">
            Permanently delete your account and all your data. This cannot be undone, and there is
            no way to cancel this request once submitted.
          </p>
          <Button
            variant="danger"
            size="sm"
            className="uppercase"
            disabled={loading || loadError}
            title={loadError ? "Couldn't load your account details" : undefined}
            onClick={() => setDeleteOpen(true)}
          >
            Delete Account
          </Button>
        </div>
      </SectionCard>

      {changePasswordOpen && (
        <ChangePasswordModal
          onClose={() => setChangePasswordOpen(false)}
          onSuccess={() => setPasswordChangedAt(new Date().toISOString())}
          signInMethod={signInMethod}
        />
      )}

      {deactivateOpen && (
        <DeactivateAccountModal
          onClose={() => setDeactivateOpen(false)}
          onDeactivated={handleDeactivated}
          signInMethod={signInMethod}
        />
      )}

      {deleteOpen && (
        <DeleteAccountModal
          onClose={() => setDeleteOpen(false)}
          onDeleted={handleDeleted}
          signInMethod={signInMethod}
        />
      )}

      {exportOpen && <ExportDataModal onClose={() => setExportOpen(false)} signInMethod={signInMethod} />}
    </div>
  );
}

/** Matches General's 3-field grid + footer save row. */
function GeneralSkeletonFields() {
  return (
    <>
      <div className="grid md:grid-cols-3 gap-4">
        {[0, 1, 2].map((i) => (
          <div key={i} className="space-y-1.5">
            <Skeleton.Text width="w-24" className="h-2.5" />
            <Skeleton.Block className="h-9 w-full" />
          </div>
        ))}
      </div>
      <div className="flex items-center justify-between mt-5 pt-4 border-t border-border">
        <Skeleton.Text width="w-72" className="h-2.5" />
        <Skeleton.Block className="h-8 w-32" />
      </div>
    </>
  );
}

/** Matches Security's Password row + Phone verification row -- Active Sessions has its own
 *  independent skeleton below since it loads separately. */
function SecurityBasicsSkeletonFields() {
  return (
    <>
      <div className="border-b border-border py-3 space-y-2">
        <Skeleton.Text width="w-20" />
        <Skeleton.Text width="w-32" className="h-2.5" />
        <Skeleton.Block className="h-7 w-36 mt-2" />
      </div>
      <div className="flex items-center justify-between border-b border-border py-3">
        <div className="space-y-1.5">
          <Skeleton.Text width="w-32" />
          <Skeleton.Text width="w-28" className="h-2.5" />
        </div>
        <Skeleton.Block className="h-5 w-16" />
      </div>
    </>
  );
}

/** Matches a session row: device icon, two text lines, a sign-out icon button. */
function ActiveSessionsSkeletonFields() {
  return (
    <div className="space-y-2">
      {[0, 1].map((i) => (
        <div key={i} className="flex items-center justify-between gap-3 border border-border rounded-lg px-3 py-2.5">
          <div className="flex items-center gap-2.5 flex-1 min-w-0">
            <Skeleton.Circle size={15} />
            <div className="min-w-0 flex-1 space-y-1.5">
              <Skeleton.Text width="w-40" />
              <Skeleton.Text width="w-28" className="h-2.5" />
            </div>
          </div>
          <Skeleton.Circle size={28} />
        </div>
      ))}
    </div>
  );
}

/** Matches the confidence-threshold slider + its save row. */
function AISkeletonFields() {
  return (
    <div className="max-w-md">
      <Skeleton.Text width="w-56" className="h-2.5 mb-2" />
      <Skeleton.Block className="h-2 w-full rounded-full" />
      <Skeleton.Text width="w-72" className="h-2.5 mt-2" />
      <div className="mt-4 pt-4 border-t border-border">
        <Skeleton.Block className="h-8 w-28" />
      </div>
    </div>
  );
}

/** Matches the Gmail connect/connected card's title + subtitle + action button. */
function GmailSkeletonFields() {
  return (
    <div className="flex items-center justify-between gap-3">
      <div className="space-y-1.5 flex-1">
        <Skeleton.Text width="w-16" />
        <Skeleton.Text width="w-64" className="h-2.5" />
      </div>
      <Skeleton.Block className="h-7 w-28 flex-shrink-0" />
    </div>
  );
}
