import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import Profile from './Profile';
import { userApi } from '../api/endpoints';
import type { UserSettings } from '../api/endpoints';

// Profile/Settings split: this page owns identity facts (name/email/phone/member-since) and a
// read-only security summary. Settings.test.tsx covers the settings-behavior half (preferences,
// the real Change Password action, Active Sessions, AI, Data) -- not duplicated here.
vi.mock('../api/endpoints', () => ({
  userApi: { get: vi.fn(), update: vi.fn() },
}));

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
    ...overrides,
  };
}

function renderProfile() {
  return render(
    <MemoryRouter>
      <Profile />
    </MemoryRouter>
  );
}

describe('Profile', () => {
  beforeEach(() => {
    vi.mocked(userApi.get).mockReset().mockResolvedValue(userSettings());
    vi.mocked(userApi.update).mockReset().mockResolvedValue(userSettings());
  });

  it('renders the real identity facts once loaded, showing the phone number in full in Personal Information', async () => {
    renderProfile();

    // Appears twice: once in the header card, once in the Personal Information input.
    expect((await screen.findAllByText('Amy Santiago')).length + screen.getAllByDisplayValue('Amy Santiago').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByDisplayValue('amy@example.com')).toBeInTheDocument();
    // Personal Information shows the full number in a read-only input; Security Overview also
    // shows it, but masked (same PhoneMasking convention Settings' Security section uses) --
    // both are correct, in their own sections, so this only asserts the full-number copy exists.
    expect(screen.getByDisplayValue('+919876543210')).toBeInTheDocument();
    expect(screen.getByText('+•••••••••210')).toBeInTheDocument();
  });

  it('disables Save until the name actually changes, then saves via userApi.update', async () => {
    const user = userEvent.setup();
    renderProfile();

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

  it('shows "Never changed" in the Security Overview for an account with no recorded password change', async () => {
    vi.mocked(userApi.get).mockReset().mockResolvedValue(userSettings({ passwordChangedAt: null }));
    renderProfile();

    expect(await screen.findByText(/never changed/i)).toBeInTheDocument();
  });

  it('shows a relative "Last changed" date once the account has a recorded password change', async () => {
    vi.mocked(userApi.get).mockReset().mockResolvedValue(userSettings({ passwordChangedAt: '2026-06-02T00:00:00Z' }));
    renderProfile();

    expect(await screen.findByText(/last changed/i)).toBeInTheDocument();
  });

  it('links to Settings for actually managing security, rather than duplicating the action buttons', async () => {
    renderProfile();

    await screen.findByDisplayValue('Amy Santiago');
    const link = screen.getByRole('link', { name: /manage security/i });
    expect(link).toHaveAttribute('href', '/app/settings');
    // No Change Password button here -- that action lives on Settings now.
    expect(screen.queryByRole('button', { name: /change password/i })).not.toBeInTheDocument();
  });

  it('never hardcodes a subscription plan', async () => {
    renderProfile();

    await screen.findByDisplayValue('Amy Santiago');
    expect(screen.queryByText(/^free$/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/plan/i)).not.toBeInTheDocument();
  });
});
