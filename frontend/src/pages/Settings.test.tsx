import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import Settings from './Settings';
import { ThemeProvider } from '../context/ThemeContext';
import { AuthProvider } from '../context/AuthContext';
import { userApi, workspaceApi, analyticsApi, deviceApi } from '../api/endpoints';

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
}));

function userSettings(overrides: Partial<Record<string, unknown>> = {}) {
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
    ...overrides,
  };
}

function deviceSession(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 'session-1',
    browser: 'Chrome',
    device: 'Windows',
    lastSeenIp: '203.0.113.5',
    lastSeenAt: '2026-07-30T00:00:00Z',
    createdAt: '2026-07-01T00:00:00Z',
    expiresAt: '2026-08-30T00:00:00Z',
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
    vi.mocked(userApi.get).mockReset().mockResolvedValue(userSettings());
    vi.mocked(userApi.update).mockReset().mockResolvedValue(userSettings());
    vi.mocked(workspaceApi.getSettings).mockReset().mockResolvedValue({ autoApplyConfidenceThreshold: 90, updatedAt: '2026-05-01T00:00:00Z' });
    vi.mocked(workspaceApi.updateSettings).mockReset().mockResolvedValue({ autoApplyConfidenceThreshold: 75, updatedAt: '2026-05-01T00:00:00Z' });
    vi.mocked(analyticsApi.importStatistics).mockReset().mockResolvedValue({
      totalStatements: 3, totalTransactionsImported: 128, totalTransactionsSkipped: 2, lastImportedAt: '2026-07-01T00:00:00Z',
    });
    vi.mocked(deviceApi.list).mockReset().mockResolvedValue([deviceSession()]);
    vi.mocked(deviceApi.revoke).mockReset().mockResolvedValue(undefined as any);
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

  it('never renders placeholder controls for capabilities that do not exist yet', async () => {
    renderSettings();

    await screen.findByDisplayValue('2000');
    expect(screen.queryByText(/coming soon/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/coming in a future release/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/two-factor/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/api key/i)).not.toBeInTheDocument();
  });
});
