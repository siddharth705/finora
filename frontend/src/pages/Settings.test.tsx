import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import Settings from './Settings';
import { ThemeProvider } from '../context/ThemeContext';
import { AuthProvider } from '../context/AuthContext';
import { userApi, workspaceApi, analyticsApi } from '../api/endpoints';

// v1 scope is capabilities-first: every section on this page reflects a real, backed setting or
// fact (see Settings.tsx's own top-of-file comment). These tests cover the real save paths, the
// dirty/save-status indicators, the real Change Password entry point, and guard against
// placeholders creeping back in for capabilities that don't exist (2FA, API keys, a hardcoded
// plan, etc.). ChangePasswordModal's own internal logic (strength meter, confirm-matching,
// submit/error handling) has its own dedicated test file, not duplicated here.
vi.mock('../api/endpoints', () => ({
  userApi: { get: vi.fn(), update: vi.fn() },
  passwordChangeApi: { start: vi.fn(), verifyOtp: vi.fn(), complete: vi.fn() },
  workspaceApi: { getSettings: vi.fn(), updateSettings: vi.fn() },
  analyticsApi: { importStatistics: vi.fn() },
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
  });

  it('renders the real profile, phone verification, and import-stat facts once loaded', async () => {
    renderSettings();

    // Appears twice: once in the top Account Summary header, once in the Profile input.
    expect((await screen.findAllByText('Amy Santiago')).length + (await screen.findAllByDisplayValue('Amy Santiago')).length).toBeGreaterThanOrEqual(2);
    expect(screen.getByDisplayValue('amy@example.com')).toBeInTheDocument();
    expect(screen.getByDisplayValue('+919876543210')).toBeInTheDocument();
    // "Verified" badges: Account Summary (phone), Profile (phone), Security (phone) — at least 2.
    expect(screen.getAllByText(/verified/i).length).toBeGreaterThanOrEqual(2);
    expect(await screen.findByText('3')).toBeInTheDocument(); // statements imported
    expect(screen.getByText('128')).toBeInTheDocument(); // transactions imported
  });

  it('masks the phone number in the Security section, unlike Profile which shows it in full', async () => {
    renderSettings();

    await screen.findByDisplayValue('Amy Santiago');
    // Profile: full number, in a read-only input.
    expect(screen.getByDisplayValue('+919876543210')).toBeInTheDocument();
    // Security: same number, masked to its last 3 digits (mirrors the backend's PhoneMasking).
    expect(screen.getByText('+•••••••••210')).toBeInTheDocument();
  });

  it('disables Save until the name actually changes, then saves via userApi.update', async () => {
    const user = userEvent.setup();
    renderSettings();

    const nameInput = await screen.findByDisplayValue('Amy Santiago');
    const saveButton = screen.getByRole('button', { name: /save changes/i });
    expect(saveButton).toBeDisabled();

    await user.clear(nameInput);
    await user.type(nameInput, 'Rosa Diaz');
    expect(screen.getByText(/unsaved changes/i)).toBeInTheDocument();
    expect(saveButton).toBeEnabled();

    await user.click(saveButton);

    await waitFor(() => expect(userApi.update).toHaveBeenCalledWith({ fullName: 'Rosa Diaz' }));
  });

  it('disables "Save preferences" until the low balance threshold or timezone actually changes', async () => {
    const user = userEvent.setup();
    renderSettings();

    await screen.findByDisplayValue('Amy Santiago');
    const saveButton = screen.getByRole('button', { name: /save preferences/i });
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

    await screen.findByDisplayValue('Amy Santiago');
    await user.click(screen.getByRole('button', { name: /change password/i }));

    // The modal's own heading -- confirms the authenticated modal opened, not a navigation to
    // /forgot-password (which would unmount Settings entirely, not render a heading here).
    expect(await screen.findByRole('heading', { name: /change password/i })).toBeInTheDocument();
    expect(screen.getByLabelText(/^current password$/i)).toBeInTheDocument();
  });

  it('never hardcodes a subscription plan', async () => {
    renderSettings();

    await screen.findByDisplayValue('Amy Santiago');
    expect(screen.queryByText(/^free$/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/plan/i)).not.toBeInTheDocument();
  });

  it('never renders placeholder controls for capabilities that do not exist yet', async () => {
    renderSettings();

    await screen.findByDisplayValue('Amy Santiago');
    expect(screen.queryByText(/coming soon/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/coming in a future release/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/two-factor/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/api key/i)).not.toBeInTheDocument();
  });
});
