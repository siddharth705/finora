import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import FeatureFlags from './FeatureFlags';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminFeatureFlagsApi } from '../api/endpoints';

vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
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
        <FeatureFlags />
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

const RECURRING_FLAG = {
  id: 'flag-1', key: 'RECURRING_DETECTION_ENABLED',
  description: 'Gates the subscription/EMI pattern detection pass.',
  enabled: true, updatedAt: new Date().toISOString(),
};

describe('FeatureFlags', () => {
  beforeEach(() => {
    vi.mocked(adminFeatureFlagsApi.list).mockReset();
    vi.mocked(adminFeatureFlagsApi.update).mockReset();
    notifySuccess.mockReset();
    notifyError.mockReset();
  });

  it('shows an access-denied message when the account lacks SYSTEM_SETTINGS', () => {
    mockAuth([]);
    vi.mocked(adminFeatureFlagsApi.list).mockResolvedValue([RECURRING_FLAG]);

    renderPage();

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
  });

  it('shows the empty message when no flags are seeded', async () => {
    mockAuth(['SYSTEM_SETTINGS']);
    vi.mocked(adminFeatureFlagsApi.list).mockResolvedValue([]);

    renderPage();

    await waitFor(() => expect(screen.getByText('No feature flags are seeded yet.')).toBeInTheDocument());
  });

  it('renders the seeded flag with its key, description, and current on/off state', async () => {
    mockAuth(['SYSTEM_SETTINGS']);
    vi.mocked(adminFeatureFlagsApi.list).mockResolvedValue([RECURRING_FLAG]);

    renderPage();

    await waitFor(() => expect(screen.getByText('RECURRING_DETECTION_ENABLED')).toBeInTheDocument());
    expect(screen.getByText(/Gates the subscription\/EMI pattern detection pass\./)).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: /Toggle RECURRING_DETECTION_ENABLED/ })).toHaveAttribute('aria-checked', 'true');
  });

  it('disabling a flag calls the update API and shows a success notification', async () => {
    const user = userEvent.setup();
    mockAuth(['SYSTEM_SETTINGS']);
    vi.mocked(adminFeatureFlagsApi.list).mockResolvedValue([RECURRING_FLAG]);
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
    vi.mocked(adminFeatureFlagsApi.list).mockResolvedValue([RECURRING_FLAG]);
    vi.mocked(adminFeatureFlagsApi.update).mockRejectedValue(new Error('network error'));

    renderPage();

    const toggle = await screen.findByRole('switch', { name: /Toggle RECURRING_DETECTION_ENABLED/ });
    await user.click(toggle);

    await waitFor(() => expect(notifyError).toHaveBeenCalledWith('Failed to update the flag. Please try again.'));
  });
});
