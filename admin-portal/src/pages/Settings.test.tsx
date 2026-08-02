import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Settings from './Settings';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { platformSettingsApi, adminFeatureFlagsApi } from '../api/endpoints';

vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  platformSettingsApi: { get: vi.fn(), update: vi.fn() },
  adminFeatureFlagsApi: { list: vi.fn(), update: vi.fn() },
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
