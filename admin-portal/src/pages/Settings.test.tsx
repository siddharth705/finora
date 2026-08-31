import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Settings from './Settings';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { platformSettingsApi, adminFeatureFlagsApi, adminMfaApi, userApi } from '../api/endpoints';

// AdminLayout now renders ThemeToggle (dark-mode support), which calls useTheme() --
// same reason adminSearchApi is stubbed below for GlobalSearch: a real ThemeProvider isn't
// mounted in these tests, so without this mock every AdminLayout-wrapped page throws before
// any assertion runs.
vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
// MfaSection ("My security") now renders unconditionally on this page, outside the
// SYSTEM_SETTINGS gate below (see Settings.tsx's own comment) -- adminMfaApi/userApi need a stub
// here for the same reason adminFeatureFlagsApi does, or every render (including the
// access-denied test) throws before any assertion runs.
vi.mock('../api/endpoints', () => ({
  platformSettingsApi: { get: vi.fn(), update: vi.fn() },
  adminFeatureFlagsApi: { list: vi.fn(), update: vi.fn() },
  adminMfaApi: { status: vi.fn(), enroll: vi.fn(), confirm: vi.fn(), disable: vi.fn() },
  userApi: { get: vi.fn() },
}));

const notifySuccess = vi.fn();
const notifyError = vi.fn();
vi.mock('../context/NotificationContext', () => ({
  useNotify: () => ({ success: notifySuccess, error: notifyError }),
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Settings />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

// AdminLayout always renders Sidebar, which reads `permissions` off this same hook.
function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    fullName: 'Ops Admin',
    logout: vi.fn(),
  }));
}

const PLATFORM_SETTINGS = {
  registrationsEnabled: true, maxFailedLoginAttempts: 5, lockoutDurationMinutes: 15,
  updatedAt: new Date().toISOString(),
};

const RECURRING_FLAG = {
  id: 'flag-1', key: 'RECURRING_DETECTION_ENABLED',
  description: 'Gates the subscription/EMI pattern detection pass.',
  enabled: true, updatedAt: new Date().toISOString(),
};

describe('Settings', () => {
  beforeEach(() => {
    vi.mocked(platformSettingsApi.get).mockReset().mockResolvedValue(PLATFORM_SETTINGS);
    vi.mocked(platformSettingsApi.update).mockReset();
    vi.mocked(adminFeatureFlagsApi.list).mockReset().mockResolvedValue([RECURRING_FLAG]);
    vi.mocked(adminFeatureFlagsApi.update).mockReset();
    vi.mocked(adminMfaApi.status).mockReset().mockResolvedValue({ enabled: false });
    vi.mocked(userApi.get).mockReset().mockResolvedValue({ phoneNumber: '+919876543705', signInMethod: 'PASSWORD' }); // synthetic-ok
    notifySuccess.mockReset();
    notifyError.mockReset();
  });

  it('shows an access-denied message when the account lacks SYSTEM_SETTINGS', () => {
    mockAuth([]);

    renderPage();

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
  });

  describe('platform configuration', () => {
    it('loads and displays the current values', async () => {
      mockAuth(['SYSTEM_SETTINGS']);

      renderPage();

      await waitFor(() => expect(screen.getByRole('switch', { checked: true })).toBeInTheDocument());
      expect(screen.getByDisplayValue('5')).toBeInTheDocument();
      expect(screen.getByDisplayValue('15')).toBeInTheDocument();
    });

    it('saves edited values via platformSettingsApi.update', async () => {
      const user = userEvent.setup();
      mockAuth(['SYSTEM_SETTINGS']);
      vi.mocked(platformSettingsApi.update).mockResolvedValue({ ...PLATFORM_SETTINGS, maxFailedLoginAttempts: 10 });

      renderPage();
      await screen.findByDisplayValue('5');

      await user.clear(screen.getByDisplayValue('5'));
      await user.type(screen.getByLabelText(/max failed login attempts/i), '10');
      await user.click(screen.getByRole('button', { name: /save changes/i }));

      await waitFor(() => expect(platformSettingsApi.update).toHaveBeenCalledWith({
        registrationsEnabled: true, maxFailedLoginAttempts: 10, lockoutDurationMinutes: 15,
      }));
      await waitFor(() => expect(screen.getByText('Saved.')).toBeInTheDocument());
    });
  });

  describe('platform feature flags', () => {
    it('shows the empty message when no flags are seeded', async () => {
      mockAuth(['SYSTEM_SETTINGS']);
      vi.mocked(adminFeatureFlagsApi.list).mockResolvedValue([]);

      renderPage();

      await waitFor(() => expect(screen.getByText('No feature flags are seeded yet.')).toBeInTheDocument());
    });

    it('renders the seeded flag with its key, description, and current on/off state', async () => {
      mockAuth(['SYSTEM_SETTINGS']);

      renderPage();

      await waitFor(() => expect(screen.getByText('RECURRING_DETECTION_ENABLED')).toBeInTheDocument());
      expect(screen.getByText(/Gates the subscription\/EMI pattern detection pass\./)).toBeInTheDocument();
      expect(screen.getByRole('switch', { name: /Toggle RECURRING_DETECTION_ENABLED/ })).toHaveAttribute('aria-checked', 'true');
    });

    it('disabling a flag calls the update API and shows a success notification', async () => {
      const user = userEvent.setup();
      mockAuth(['SYSTEM_SETTINGS']);
      vi.mocked(adminFeatureFlagsApi.update).mockResolvedValue({ ...RECURRING_FLAG, enabled: false });

      renderPage();

      const toggle = await screen.findByRole('switch', { name: /Toggle RECURRING_DETECTION_ENABLED/ });
      await user.click(toggle);

      await waitFor(() => expect(adminFeatureFlagsApi.update).toHaveBeenCalledWith('flag-1', { enabled: false }));
      await waitFor(() => expect(notifySuccess).toHaveBeenCalledWith('RECURRING_DETECTION_ENABLED disabled.'));
    });

    it('shows an error notification when toggling fails', async () => {
      const user = userEvent.setup();
      mockAuth(['SYSTEM_SETTINGS']);
      vi.mocked(adminFeatureFlagsApi.update).mockRejectedValue(new Error('network error'));

      renderPage();

      const toggle = await screen.findByRole('switch', { name: /Toggle RECURRING_DETECTION_ENABLED/ });
      await user.click(toggle);

      await waitFor(() => expect(notifyError).toHaveBeenCalledWith('Failed to update the flag. Please try again.'));
    });
  });
});
