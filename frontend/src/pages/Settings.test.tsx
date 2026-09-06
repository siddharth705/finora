import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import Settings from './Settings';
import { ThemeProvider } from '../context/ThemeContext';
import { AuthProvider } from '../context/AuthContext';
import { userApi, workspaceApi, analyticsApi, deviceApi, accountLifecycleApi, authApi, gmailApi } from '../api/endpoints';
import type { UserSettings } from '../api/endpoints';
import { getAccessToken, setAccessToken } from '../api/client';

// v1 scope is capabilities-first: every section on this page reflects a real, backed setting or
// fact (see Settings.tsx's own top-of-file comment). These tests cover the real save paths, the
// dirty/save-status indicators, the real Change Password entry point, the Active Sessions list
// (wired to the previously frontend-less DeviceController), and guard against placeholders
// creeping back in for capabilities that don't exist (2FA, API keys, a hardcoded plan, etc.).
// Identity facts (name/email/phone/member-since) moved to Profile.test.tsx along with this split.
// ChangePasswordModal's own internal logic has its own dedicated test file, not duplicated here.
vi.mock('../api/endpoints', () => ({
  userApi: { get: vi.fn(), update: vi.fn() },
  passwordChangeApi: { start: vi.fn(), verifyOtp: vi.fn(), complete: vi.fn() },
  workspaceApi: { getSettings: vi.fn(), updateSettings: vi.fn() },
  analyticsApi: { importStatistics: vi.fn() },
  deviceApi: { list: vi.fn(), revoke: vi.fn() },
  accountLifecycleApi: { deactivate: vi.fn() },
  // SEC-01: AuthProvider (real, not mocked, in this file's harness) now attempts a silent
  // /auth/refresh on mount -- rejecting by default here keeps every test's starting state
  // "no recovered session," same as before this existed.
  authApi: { logout: vi.fn(), refresh: vi.fn().mockRejectedValue(new Error('no session')) },
  gmailApi: {
    status: vi.fn(), connect: vi.fn(), disconnect: vi.fn(), syncNow: vi.fn(),
    reviewQueue: vi.fn(), approve: vi.fn(), reject: vi.fn(),
  },
}));

function gmailStatus(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    connected: false, status: null, needsReconnect: false, googleEmail: null, grantedScopes: [],
    connectedAt: null, lastSyncedAt: null, lastDiscoveryAt: null,
    transactionsFound: 0, needsReview: 0, available: true,
    ...overrides,
  };
}

function userSettings(overrides: Partial<UserSettings> = {}): UserSettings {
  return {
    email: 'amy@example.com',
    fullName: 'Amy Santiago',
    lowBalanceThreshold: 2000,
    theme: 'system',
    timezone: 'Asia/Kolkata',
    phoneNumber: '+919876543210',
    phoneVerified: true,
    createdAt: '2026-05-01T00:00:00Z',
    passwordChangedAt: null,
    signInMethod: 'PASSWORD',
    onboardingCompleted: true,
    ...overrides,
  };
}

function deviceSession(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 'session-1',
    sessionId: 'sess-aaa',
    current: false,
    browser: 'Chrome',
    device: 'Windows',
    lastSeenIp: '203.0.113.5',
    lastSeenAt: '2026-07-30T00:00:00Z',
    createdAt: '2026-07-01T00:00:00Z',
    expiresAt: '2026-08-30T00:00:00Z',
    // Distinct from createdAt on purpose: rotation resets createdAt every refresh, so a fixture
    // where they are equal cannot catch the UI reading the wrong one for "signed in".
    sessionStartedAt: '2026-06-28T00:00:00Z',
    sessionExpiresAt: '2026-07-05T00:00:00Z',
    ...overrides,
  };
}

function renderSettings() {
  return render(
    <ThemeProvider>
      <MemoryRouter>
        <AuthProvider>
          <Settings />
        </AuthProvider>
      </MemoryRouter>
    </ThemeProvider>
  );
}

describe('Settings', () => {
  beforeEach(() => {
    // The deactivate-flow tests write finora_session_ended_reason (storage) and clear the
    // in-memory access token via logout()'s real calls (AuthProvider is the real provider here,
    // not mocked) -- without these resets, whichever ran first leaks into the next test.
    localStorage.clear();
    setAccessToken(null);
    vi.mocked(userApi.get).mockReset().mockResolvedValue(userSettings());
    vi.mocked(userApi.update).mockReset().mockResolvedValue(userSettings());
    vi.mocked(workspaceApi.getSettings).mockReset().mockResolvedValue({ autoApplyConfidenceThreshold: 90, updatedAt: '2026-05-01T00:00:00Z' });
    vi.mocked(workspaceApi.updateSettings).mockReset().mockResolvedValue({ autoApplyConfidenceThreshold: 75, updatedAt: '2026-05-01T00:00:00Z' });
    vi.mocked(analyticsApi.importStatistics).mockReset().mockResolvedValue({
      totalStatements: 3, totalTransactionsImported: 128, totalTransactionsSkipped: 2, lastImportedAt: '2026-07-01T00:00:00Z',
    });
    vi.mocked(deviceApi.list).mockReset().mockResolvedValue([deviceSession()]);
    vi.mocked(deviceApi.revoke).mockReset().mockResolvedValue(undefined as any);
    vi.mocked(accountLifecycleApi.deactivate).mockReset().mockResolvedValue({ message: 'Deactivated.' });
    vi.mocked(authApi.logout).mockReset().mockResolvedValue({ message: 'Signed out.' });
    vi.mocked(gmailApi.status).mockReset().mockResolvedValue(gmailStatus());
    vi.mocked(gmailApi.connect).mockReset();
    vi.mocked(gmailApi.disconnect).mockReset();
    vi.mocked(gmailApi.syncNow).mockReset();
  });

  it('renders the real preferences and import-stat facts once loaded', async () => {
    renderSettings();

    expect(await screen.findByDisplayValue('2000')).toBeInTheDocument();
    expect(await screen.findByText('3')).toBeInTheDocument(); // statements imported
    expect(screen.getByText('128')).toBeInTheDocument(); // transactions imported
    expect(screen.getByText('2')).toBeInTheDocument(); // rows skipped
  });

  it('masks the phone number in the Security section, unlike Profile which shows it in full', async () => {
    renderSettings();

    // Masked to its last 3 digits (mirrors the backend's PhoneMasking) -- Settings never shows
    // the raw number, unlike Profile.
    expect(await screen.findByText('+•••••••••210')).toBeInTheDocument();
    expect(screen.queryByDisplayValue('+919876543210')).not.toBeInTheDocument();
  });

  it('disables "Save preferences" until the low balance threshold or timezone actually changes', async () => {
    const user = userEvent.setup();
    renderSettings();

    const saveButton = await screen.findByRole('button', { name: /save preferences/i });
    expect(saveButton).toBeDisabled();

    const thresholdInput = screen.getByDisplayValue('2000');
    await user.clear(thresholdInput);
    await user.type(thresholdInput, '5000');
    expect(saveButton).toBeEnabled();

    await user.click(saveButton);

    await waitFor(() => expect(userApi.update).toHaveBeenCalledWith({ lowBalanceThreshold: 5000, timezone: 'Asia/Kolkata' }));
  });

  it('clears the pending "just saved" timer on unmount instead of leaking it past teardown', async () => {
    // The "Saved" flash schedules a 2s setTimeout to clear itself. Left uncancelled, that timer
    // outlives the component -- harmless mid-test, but fatal once Vitest tears down jsdom's
    // `window` at the end of the file: the leaked timer fires into a torn-down environment and
    // throws "ReferenceError: window is not defined" from inside React's setState path, failing
    // the whole run despite every individual assertion passing. Regression test for that leak.
    const clearTimeoutSpy = vi.spyOn(global, 'clearTimeout');
    const user = userEvent.setup();
    const { unmount } = renderSettings();

    const saveButton = await screen.findByRole('button', { name: /save preferences/i });
    const thresholdInput = screen.getByDisplayValue('2000');
    await user.clear(thresholdInput);
    await user.type(thresholdInput, '5000');
    await user.click(saveButton);

    // Confirms the timer was actually armed, not just that the save request fired.
    await screen.findByText('Saved');

    clearTimeoutSpy.mockClear();
    unmount();
    expect(clearTimeoutSpy).toHaveBeenCalled();

    clearTimeoutSpy.mockRestore();
  });

  it('disables "Save setting" until the confidence threshold actually changes', async () => {
    renderSettings();

    await screen.findByText(/confidence threshold — 90%/i);
    const saveButton = screen.getByRole('button', { name: /save setting/i });
    expect(saveButton).toBeDisabled();

    // fireEvent (not userEvent, which has no "drag a slider" primitive) -- this only needs the
    // value to change so dirty-state kicks in, not a realistic pointer drag.
    const slider = screen.getByRole('slider');
    fireEvent.change(slider, { target: { value: '60' } });
    expect(saveButton).toBeEnabled();
  });

  it('shows "Never changed" for an account with no recorded password change', async () => {
    vi.mocked(userApi.get).mockReset().mockResolvedValue(userSettings({ passwordChangedAt: null }));
    renderSettings();

    expect(await screen.findByText(/never changed/i)).toBeInTheDocument();
  });

  it('shows a relative "Last changed" date once the account has a recorded password change', async () => {
    vi.mocked(userApi.get).mockReset().mockResolvedValue(userSettings({ passwordChangedAt: '2026-06-02T00:00:00Z' }));
    renderSettings();

    expect(await screen.findByText(/last changed/i)).toBeInTheDocument();
  });

  it('opens the real Change Password modal, not the forgot-password flow', async () => {
    const user = userEvent.setup();
    renderSettings();

    await screen.findByRole('button', { name: /change password/i });
    await user.click(screen.getByRole('button', { name: /change password/i }));

    // The modal's own heading -- confirms the authenticated modal opened, not a navigation to
    // /forgot-password (which would unmount Settings entirely, not render a heading here).
    expect(await screen.findByRole('heading', { name: /change password/i })).toBeInTheDocument();
    expect(screen.getByLabelText(/^current password$/i)).toBeInTheDocument();
  });

  it('renders the active sessions list from deviceApi.list', async () => {
    renderSettings();

    expect(await screen.findByText('Chrome on Windows')).toBeInTheDocument();
    expect(screen.getByText(/203\.0\.113\.5/)).toBeInTheDocument();
  });

  it('shows when each session started and when it will expire', async () => {
    // Computed relative to now rather than hard-coded, because the label is a countdown -- a fixed
    // date would silently become "Expires shortly" once it drifted into the past and the test
    // would still pass while asserting nothing about the countdown.
    const day = 24 * 3_600_000;
    vi.mocked(deviceApi.list).mockReset().mockResolvedValue([
      deviceSession({
        // The real-world shape: createdAt is the LAST ROTATION, minutes ago, while the session
        // itself started days back. Asserting only that the words "Signed in" appear would pass
        // against either field -- verified by swapping them, which is why these are now four days
        // apart and the assertion names the value.
        createdAt: new Date(Date.now() - 5 * 60_000).toISOString(),
        sessionStartedAt: new Date(Date.now() - 4 * day).toISOString(),
        sessionExpiresAt: new Date(Date.now() + 3 * day).toISOString(),
      }) as never,
    ]);

    renderSettings();

    expect(await screen.findByText(/Signed in 4 days ago/)).toBeInTheDocument();
    expect(screen.getByText(/Expires in 3 days/)).toBeInTheDocument();
    // createdAt would render "today"; seeing that here means the UI is reporting the age of the
    // current token rather than of the session.
    expect(screen.queryByText(/Signed in today/)).not.toBeInTheDocument();
  });

  it('omits the expiry countdown when the absolute session cap is disabled', async () => {
    // The cap is configurable and 0 turns it off, in which case the backend sends null. Inventing
    // a date there would tell the user their session ends when it does not.
    vi.mocked(deviceApi.list).mockReset().mockResolvedValue([
      deviceSession({ sessionExpiresAt: null }) as never,
    ]);

    renderSettings();

    expect(await screen.findByText('Chrome on Windows')).toBeInTheDocument();
    expect(screen.queryByText(/Expires in/)).not.toBeInTheDocument();
  });

  it('marks only the calling session as this device', async () => {
    vi.mocked(deviceApi.list).mockReset().mockResolvedValue([
      deviceSession({ id: 'a', sessionId: 'sess-a', current: false, browser: 'Safari', device: 'macOS' }) as never,
      deviceSession({ id: 'b', sessionId: 'sess-b', current: true }) as never,
    ]);

    renderSettings();

    // Asserts WHICH row carries the badge, not merely that exactly one does. Written the weaker
    // way first, it passed with the condition inverted -- one badge still rendered, just on the
    // wrong device, which is the entire bug this test exists to catch and the worst possible one
    // here: a user signing out the session they are sitting in.
    const badge = await screen.findByText('This device');
    expect(badge.parentElement).toHaveTextContent('Chrome on Windows');
    expect(badge.parentElement).not.toHaveTextContent('Safari');
    expect(screen.getAllByText('This device')).toHaveLength(1);
  });

  it('shows a friendly message when there are no active sessions', async () => {
    vi.mocked(deviceApi.list).mockReset().mockResolvedValue([]);
    renderSettings();

    expect(await screen.findByText(/no active sessions/i)).toBeInTheDocument();
  });

  it('shows an error message when active sessions fail to load', async () => {
    vi.mocked(deviceApi.list).mockReset().mockRejectedValue(new Error('network error'));
    renderSettings();

    expect(await screen.findByText(/couldn't load your active sessions/i)).toBeInTheDocument();
  });

  it('revokes a session via deviceApi.revoke and removes it from the list', async () => {
    const user = userEvent.setup();
    renderSettings();

    await screen.findByText('Chrome on Windows');
    await user.click(screen.getByRole('button', { name: /sign out this device/i }));

    await waitFor(() => expect(deviceApi.revoke).toHaveBeenCalledWith('session-1'));
    await waitFor(() => expect(screen.queryByText('Chrome on Windows')).not.toBeInTheDocument());
  });

  it('never hardcodes a subscription plan', async () => {
    renderSettings();

    await screen.findByDisplayValue('2000');
    expect(screen.queryByText(/^free$/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/plan/i)).not.toBeInTheDocument();
  });

  // Phase 3 (animation-polish roadmap): every button on this page was `text-xs uppercase
  // font-medium` before migrating to the shared <Button> primitive. <Button>'s own base classes
  // don't include `uppercase` (Dashboard/Ledger's buttons were never uppercase, so the primitive
  // never needed it) -- without passing it back in via `className` at each call site, the swap
  // would have silently dropped this page's small-caps look on every button.
  it('preserves the small-caps button styling this page always had, on every migrated button', async () => {
    renderSettings();

    const uppercaseButtons = [
      /save preferences/i, /^change password$/i, /save setting/i, /export my data/i,
      /^connect gmail$/i, /^deactivate account$/i, /^delete account$/i,
    ];
    for (const name of uppercaseButtons) {
      expect(await screen.findByRole('button', { name })).toHaveClass('uppercase');
    }
  });

  it('preserves the small-caps styling on the Gmail Review/Disconnect buttons too', async () => {
    vi.mocked(gmailApi.status).mockResolvedValue(gmailStatus({
      connected: true, googleEmail: 'amy@gmail.example.test', needsReview: 3,
    }));
    renderSettings();

    expect(await screen.findByRole('button', { name: /review 3/i })).toHaveClass('uppercase');
    expect(screen.getByRole('button', { name: /disconnect/i })).toHaveClass('uppercase');
  });

  it('never renders placeholder controls for capabilities that do not exist yet', async () => {
    renderSettings();

    await screen.findByDisplayValue('2000');
    expect(screen.queryByText(/coming soon/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/coming in a future release/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/two-factor/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/api key/i)).not.toBeInTheDocument();
  });

  describe('Manage Your Account — deactivate', () => {
    it('opens the deactivate modal and calls the API with the entered password', async () => {
      const user = userEvent.setup();
      renderSettings();

      await user.click(await screen.findByRole('button', { name: /^deactivate account$/i }));
      await user.type(screen.getByLabelText(/current password/i), 'CorrectPassword123');
      await user.selectOptions(screen.getByLabelText(/^reason$/i), 'TAKING_A_BREAK');
      // Both the page's own trigger button and the modal's submit button share this exact text --
      // the modal's is the one rendered last in the DOM.
      const submitButtons = screen.getAllByRole('button', { name: /^deactivate account$/i });
      await user.click(submitButtons[submitButtons.length - 1]);

      await waitFor(() => expect(accountLifecycleApi.deactivate)
        .toHaveBeenCalledWith('CorrectPassword123', null, 'TAKING_A_BREAK', undefined));
    });

    it('hands the reactivation reason to the login page via a hard redirect, not router state', async () => {
      // Regression test for bugs found only by actually driving this in a real browser (this
      // suite's mocked useAuth() masks the first two -- Settings isn't rendered behind
      // ProtectedRoute here, so neither race below can happen in this test the way it did against
      // the real app):
      //
      // 1. This used to call navigate('/login', { state: { message } }), the way ResetPassword.tsx
      //    hands Login.tsx a one-shot confirmation. That works for ResetPassword because it isn't
      //    behind ProtectedRoute; here it raced App.tsx's ProtectedRoute (which wraps
      //    /app/settings), whose own stateless <Navigate to="/login" replace /> landed on /login
      //    with no message at all.
      // 2. The first fix called AuthContext's logout() before redirecting -- logout() calls
      //    setToken(null), a REACT STATE update, which is exactly what triggers ProtectedRoute's
      //    reactive redirect in the first place, mounting a client-side-routed Login instance that
      //    reads AND clears SESSION_ENDED_REASON_KEY before the real, hard-reloaded page ever gets
      //    to read it.
      // 3. That second fix then hand-rolled the storage-clearing logic directly in this file
      //    instead of calling client.ts's own clearSessionAndRedirect(reason) -- caught in code
      //    review as a needless third copy of the same key list (client.ts's own comment already
      //    documents a bug from a second copy missing a key once), and as dropping the best-effort
      //    authApi.logout() call that actually clears the httpOnly refresh-token cookie in the
      //    browser (the refresh token is already revoked server-side either way, so this was a
      //    browser-hygiene regression, not a security one).
      //
      // The final fix: call authApi.logout() for the cookie, then the shared, exported
      // clearSessionAndRedirect(reason) for everything else -- reusing the one real implementation
      // instead of a fourth copy.
      const user = userEvent.setup();
      renderSettings();

      await user.click(await screen.findByRole('button', { name: /^deactivate account$/i }));
      await user.type(screen.getByLabelText(/current password/i), 'CorrectPassword123');
      await user.selectOptions(screen.getByLabelText(/^reason$/i), 'TAKING_A_BREAK');
      const submitButtons = screen.getAllByRole('button', { name: /^deactivate account$/i });
      await user.click(submitButtons[submitButtons.length - 1]);

      await waitFor(() => expect(localStorage.getItem('finora_session_ended_reason'))
        .toBe('Your account has been deactivated. Sign in again any time to reactivate it.'));
      expect(getAccessToken()).toBeNull();
      // The cookie-clearing half of the fix -- best-effort, but it must actually be attempted.
      expect(authApi.logout).toHaveBeenCalled();
    });

    it('shows the server error inline and stays open when the password is wrong', async () => {
      vi.mocked(accountLifecycleApi.deactivate).mockReset().mockRejectedValue({
        response: { data: { message: 'Current password is incorrect.' } },
      });
      const user = userEvent.setup();
      renderSettings();

      await user.click(await screen.findByRole('button', { name: /^deactivate account$/i }));
      await user.type(screen.getByLabelText(/current password/i), 'WrongPassword');
      await user.selectOptions(screen.getByLabelText(/^reason$/i), 'TAKING_A_BREAK');
      const submitButtons = screen.getAllByRole('button', { name: /^deactivate account$/i });
      await user.click(submitButtons[submitButtons.length - 1]);

      expect(await screen.findByText(/current password is incorrect/i)).toBeInTheDocument();
      // A failed deactivation must not sign the user out.
      expect(localStorage.getItem('finora_session_ended_reason')).toBeNull();
    });

    it('requires a reason before submitting', async () => {
      const user = userEvent.setup();
      renderSettings();

      await user.click(await screen.findByRole('button', { name: /^deactivate account$/i }));
      await user.type(screen.getByLabelText(/current password/i), 'CorrectPassword123');
      const submitButtons = screen.getAllByRole('button', { name: /^deactivate account$/i });
      await user.click(submitButtons[submitButtons.length - 1]);

      expect(await screen.findByText(/choose a reason for deactivating/i)).toBeInTheDocument();
      expect(accountLifecycleApi.deactivate).not.toHaveBeenCalled();
    });

    it('passes an optional note through to the API, trimmed', async () => {
      const user = userEvent.setup();
      renderSettings();

      await user.click(await screen.findByRole('button', { name: /^deactivate account$/i }));
      await user.type(screen.getByLabelText(/current password/i), 'CorrectPassword123');
      await user.selectOptions(screen.getByLabelText(/^reason$/i), 'PRIVACY_CONCERNS');
      await user.type(screen.getByLabelText(/anything else/i), '  Not comfortable with data retention  ');
      const submitButtons = screen.getAllByRole('button', { name: /^deactivate account$/i });
      await user.click(submitButtons[submitButtons.length - 1]);

      await waitFor(() => expect(accountLifecycleApi.deactivate)
        .toHaveBeenCalledWith('CorrectPassword123', null, 'PRIVACY_CONCERNS', 'Not comfortable with data retention'));
    });

    it('closes without calling the API when cancelled', async () => {
      const user = userEvent.setup();
      renderSettings();

      await user.click(await screen.findByRole('button', { name: /^deactivate account$/i }));
      await user.click(screen.getByRole('button', { name: /^cancel$/i }));

      expect(screen.queryByLabelText(/current password/i)).not.toBeInTheDocument();
      expect(accountLifecycleApi.deactivate).not.toHaveBeenCalled();
    });
  });

  // C5.4, D-15: the first frontend caller for GoogleOAuthController's connect/status/disconnect
  // endpoints, which existed on the backend with nothing wired to them since Phase B.
  describe('Gmail connection', () => {
    it('offers a Connect Gmail button when nothing is connected yet', async () => {
      renderSettings();

      expect(await screen.findByRole('button', { name: /connect gmail/i })).toBeInTheDocument();
    });

    it('redirects the browser to the authorization URL returned by connect()', async () => {
      const user = userEvent.setup();
      vi.mocked(gmailApi.connect).mockResolvedValue({ authorizationUrl: 'https://accounts.google.com/o/oauth2/auth?x=1' });
      // src/test/setup.ts stands in for jsdom's missing navigation and records the attempted URL
      // on window.location.href, so the redirect is asserted directly. This used to hand-roll its
      // own `delete window.location` replacement, which was never restored -- every test after it
      // in this file inherited a bare `{ href }` object in place of the real Location.
      renderSettings();
      await user.click(await screen.findByRole('button', { name: /connect gmail/i }));

      await waitFor(() => expect(window.location.href).toBe('https://accounts.google.com/o/oauth2/auth?x=1'));
    });

    it('shows the connected state with account, sync stats, and actions', async () => {
      vi.mocked(gmailApi.status).mockResolvedValue(gmailStatus({
        connected: true, status: 'CONNECTED', googleEmail: 'amy@gmail.example.test',
        lastDiscoveryAt: '2026-08-15T05:00:00Z', transactionsFound: 245, needsReview: 12,
      }));

      renderSettings();

      expect(await screen.findByText('amy@gmail.example.test')).toBeInTheDocument();
      expect(screen.getByText('245')).toBeInTheDocument();
      expect(screen.getByText('12')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /review 12/i })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /disconnect/i })).toBeInTheDocument();
    });

    // D-19 Step 1 (Trust Center): grantedScopes has been on the DTO since C5.4, never rendered.
    it('shows what Gmail access was actually granted, in plain English', async () => {
      vi.mocked(gmailApi.status).mockResolvedValue(gmailStatus({
        connected: true, googleEmail: 'amy@gmail.example.test',
        grantedScopes: [
          'openid',
          'https://www.googleapis.com/auth/userinfo.email',
          'https://www.googleapis.com/auth/gmail.readonly',
        ],
      }));

      renderSettings();

      // "openid" is skipped -- it has no user-meaningful capability of its own.
      expect(await screen.findByText(/read gmail messages/i)).toBeInTheDocument();
      expect(screen.getByText(/see your email address/i)).toBeInTheDocument();
      expect(screen.queryByText(/openid/i)).not.toBeInTheDocument();
    });

    it('shows no permissions line when the connection somehow carries no known scopes', async () => {
      vi.mocked(gmailApi.status).mockResolvedValue(gmailStatus({
        connected: true, googleEmail: 'amy@gmail.example.test', grantedScopes: [],
      }));

      renderSettings();

      await screen.findByText('amy@gmail.example.test');
      expect(screen.queryByText(/permissions/i)).not.toBeInTheDocument();
    });

    it('does not offer a Review button when nothing needs review', async () => {
      vi.mocked(gmailApi.status).mockResolvedValue(gmailStatus({
        connected: true, googleEmail: 'amy@gmail.example.test', needsReview: 0,
      }));

      renderSettings();

      await screen.findByText('amy@gmail.example.test');
      expect(screen.queryByRole('button', { name: /review/i })).not.toBeInTheDocument();
    });

    it('calls disconnect and reloads status', async () => {
      const user = userEvent.setup();
      vi.mocked(gmailApi.status).mockResolvedValue(gmailStatus({ connected: true, googleEmail: 'amy@gmail.example.test' }));
      vi.mocked(gmailApi.disconnect).mockResolvedValue(undefined as any);

      renderSettings();
      await user.click(await screen.findByRole('button', { name: /disconnect/i }));

      await waitFor(() => expect(gmailApi.disconnect).toHaveBeenCalled());
      await waitFor(() => expect(gmailApi.status).toHaveBeenCalledTimes(2));
    });

    it('shows a visible error when Connect Gmail fails, not a silently-stuck button', async () => {
      const user = userEvent.setup();
      vi.mocked(gmailApi.connect).mockRejectedValue(new Error('network error'));

      renderSettings();
      await user.click(await screen.findByRole('button', { name: /connect gmail/i }));

      expect(await screen.findByText(/couldn't start the gmail connection/i)).toBeInTheDocument();
      // And the button itself recovers rather than staying stuck on "Connecting…" forever.
      expect(screen.getByRole('button', { name: /connect gmail/i })).toBeInTheDocument();
    });

    it('shows a visible error when Disconnect fails', async () => {
      const user = userEvent.setup();
      vi.mocked(gmailApi.status).mockResolvedValue(gmailStatus({ connected: true, googleEmail: 'amy@gmail.example.test' }));
      vi.mocked(gmailApi.disconnect).mockRejectedValue(new Error('network error'));

      renderSettings();
      await user.click(await screen.findByRole('button', { name: /disconnect/i }));

      expect(await screen.findByText(/couldn't disconnect gmail/i)).toBeInTheDocument();
    });

    it('surfaces a cooldown-specific message when Sync Now is rate-limited', async () => {
      const user = userEvent.setup();
      vi.mocked(gmailApi.status).mockResolvedValue(gmailStatus({ connected: true, googleEmail: 'amy@gmail.example.test' }));
      vi.mocked(gmailApi.syncNow).mockRejectedValue({ response: { status: 429 } });

      renderSettings();
      await user.click(await screen.findByTitle(/sync now/i));

      expect(await screen.findByText(/synced recently/i)).toBeInTheDocument();
    });

    // C6.1: `connected: false` alone can't distinguish "never connected" from "the grant just
    // died" -- REAUTH_REQUIRED collapses `connected` to false the same way a never-connected user
    // does. Without this, a real user whose token Google rejected saw the exact same "Connect
    // Gmail" first-time prompt, with no account, no explanation.
    it('shows a distinct reconnect prompt, not the first-time Connect prompt, when the grant needs reauth', async () => {
      vi.mocked(gmailApi.status).mockResolvedValue(gmailStatus({
        connected: false, status: 'REAUTH_REQUIRED', needsReconnect: true, googleEmail: 'amy@gmail.example.test',
      }));

      renderSettings();

      expect(await screen.findByText(/needs reconnect/i)).toBeInTheDocument();
      expect(screen.getByText('amy@gmail.example.test')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /reconnect gmail/i })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /^connect gmail$/i })).not.toBeInTheDocument();
    });

    it('shows the same reconnect prompt for a connection Google revoked', async () => {
      vi.mocked(gmailApi.status).mockResolvedValue(gmailStatus({
        connected: false, status: 'REVOKED', needsReconnect: true, googleEmail: 'amy@gmail.example.test',
      }));

      renderSettings();

      expect(await screen.findByText(/needs reconnect/i)).toBeInTheDocument();
    });

    it('still shows the plain first-time Connect prompt when the user disconnected on purpose', async () => {
      vi.mocked(gmailApi.status).mockResolvedValue(gmailStatus({ connected: false, status: 'DISCONNECTED' }));

      renderSettings();

      expect(await screen.findByRole('button', { name: /^connect gmail$/i })).toBeInTheDocument();
      expect(screen.queryByText(/needs reconnect/i)).not.toBeInTheDocument();
    });

    it('clicking Reconnect Gmail starts the same OAuth flow as Connect Gmail', async () => {
      const user = userEvent.setup();
      vi.mocked(gmailApi.status).mockResolvedValue(gmailStatus({ connected: false, status: 'REAUTH_REQUIRED', needsReconnect: true }));
      vi.mocked(gmailApi.connect).mockResolvedValue({ authorizationUrl: 'https://accounts.google.com/o/oauth2/auth?x=1' });

      renderSettings();
      await user.click(await screen.findByRole('button', { name: /reconnect gmail/i }));

      await waitFor(() => expect(window.location.href).toBe('https://accounts.google.com/o/oauth2/auth?x=1'));
    });

    // The bug this guards against: the backend used to refuse EVERY Reconnect click for a
    // REAUTH_REQUIRED connection with a 409 ("already connected") -- the guard didn't distinguish
    // a dead grant from a working one. handleGmailConnect's bare `catch {}` then discarded that
    // 409's own message and showed a generic "please try again", which is actively bad advice for
    // a failure retrying can never fix. The backend guard is now scoped to CONNECTED only, but this
    // covers the frontend half on its own: whatever the backend does say should reach the user.
    it('surfaces the backend message when Reconnect Gmail is refused, instead of a generic one', async () => {
      const user = userEvent.setup();
      vi.mocked(gmailApi.status).mockResolvedValue(gmailStatus({
        connected: false, status: 'REAUTH_REQUIRED', needsReconnect: true, googleEmail: 'amy@gmail.example.test',
      }));
      vi.mocked(gmailApi.connect).mockRejectedValue({
        response: {
          status: 409,
          data: { message: 'A Gmail account (amy@gmail.example.test) is already connected. Disconnect it first to connect a different one.' },
        },
      });

      renderSettings();
      await user.click(await screen.findByRole('button', { name: /reconnect gmail/i }));

      expect(await screen.findByText(/disconnect it first to connect a different one/i)).toBeInTheDocument();
      expect(screen.queryByText(/couldn't start the gmail connection/i)).not.toBeInTheDocument();
    });
  });

  // Phase 3 (animation-polish roadmap): General/Security's fields, Active Sessions, AI, and
  // Connected Apps each fetch independently, but a single outer `if (loading) return ...` used to
  // block the whole page on General/Security's userApi.get() alone -- so a slow account-settings
  // request delayed sections that had nothing to do with it. These prove each section renders off
  // its own fetch, not the others'.
  describe('Phase 3 section-scoped loading', () => {
    function pendingUserGet() {
      let resolveGet: (u: ReturnType<typeof userSettings>) => void;
      vi.mocked(userApi.get).mockReset().mockReturnValue(
        new Promise((resolve) => { resolveGet = resolve; })
      );
      return { resolveGet: () => resolveGet(userSettings()) };
    }

    it('renders Active Sessions before General/Security have finished loading', async () => {
      pendingUserGet();
      renderSettings();

      expect(await screen.findByText('Chrome on Windows')).toBeInTheDocument();
      // General's own content (fed by the still-pending userApi.get()) must not have appeared.
      expect(screen.queryByDisplayValue('2000')).not.toBeInTheDocument();
    });

    it('renders the AI section before General/Security have finished loading', async () => {
      pendingUserGet();
      renderSettings();

      expect(await screen.findByText(/confidence threshold — 90%/i)).toBeInTheDocument();
      expect(screen.queryByDisplayValue('2000')).not.toBeInTheDocument();
    });

    it('renders the Connected Apps section before General/Security have finished loading', async () => {
      pendingUserGet();
      renderSettings();

      expect(await screen.findByRole('button', { name: /^connect gmail$/i })).toBeInTheDocument();
      expect(screen.queryByDisplayValue('2000')).not.toBeInTheDocument();
    });

    // Export/Deactivate/Delete all open a modal that needs the real signInMethod -- fetched by the
    // same userApi.get() call as General/Security, and defaulted to 'PASSWORD' until it resolves.
    // Decoupling Data/Manage-Your-Account from the outer `loading` gate (so Active Sessions/AI/
    // Gmail could render independently) accidentally made these three reachable before that value
    // is trustworthy -- a Google-sign-in account could see the password-based flow. They must stay
    // disabled until userApi.get() actually resolves.
    it('keeps Export/Deactivate/Delete Account disabled until signInMethod has actually loaded, then enables them', async () => {
      const { resolveGet } = pendingUserGet();
      renderSettings();

      const exportButton = await screen.findByRole('button', { name: /^export my data$/i });
      const deactivateButton = screen.getByRole('button', { name: /^deactivate account$/i });
      const deleteButton = screen.getByRole('button', { name: /^delete account$/i });
      expect(exportButton).toBeDisabled();
      expect(deactivateButton).toBeDisabled();
      expect(deleteButton).toBeDisabled();

      resolveGet();

      await waitFor(() => expect(exportButton).toBeEnabled());
      expect(deactivateButton).toBeEnabled();
      expect(deleteButton).toBeEnabled();
    });

    it('keeps Export/Deactivate/Delete Account permanently disabled when the account data fails to load', async () => {
      vi.mocked(userApi.get).mockReset().mockRejectedValue(new Error('network error'));
      renderSettings();

      await screen.findAllByText(/couldn't load your settings/i);
      expect(screen.getByRole('button', { name: /^export my data$/i })).toBeDisabled();
      expect(screen.getByRole('button', { name: /^deactivate account$/i })).toBeDisabled();
      expect(screen.getByRole('button', { name: /^delete account$/i })).toBeDisabled();
    });

    it('shows an inline error scoped to General and Security when their data fails to load, without blocking the independent sections', async () => {
      vi.mocked(userApi.get).mockReset().mockRejectedValue(new Error('network error'));
      renderSettings();

      const errors = await screen.findAllByText(/couldn't load your settings/i);
      expect(errors.length).toBeGreaterThan(0);
      // Active Sessions, AI, and Connected Apps all fetch independently of userApi.get() and must
      // still render their real content.
      expect(await screen.findByText('Chrome on Windows')).toBeInTheDocument();
      expect(await screen.findByText(/confidence threshold — 90%/i)).toBeInTheDocument();
      expect(await screen.findByRole('button', { name: /^connect gmail$/i })).toBeInTheDocument();
    });
  });
});
