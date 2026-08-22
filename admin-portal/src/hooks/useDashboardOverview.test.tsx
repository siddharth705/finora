import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { useDashboardOverview } from './useDashboardOverview';
import { useAdminAuth } from '../context/AdminAuthContext';
import { adminDashboardApi } from '../api/endpoints';
import { mockAdminAuthState } from '../test/mockAdminAuth';

vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminDashboardApi: { overview: vi.fn() },
}));

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

describe('useDashboardOverview', () => {
  beforeEach(() => {
    vi.mocked(adminDashboardApi.overview).mockReset();
  });

  it('does not fire the query for an account without PLATFORM_STATS_VIEW -- mirrors the backend @PreAuthorize', () => {
    vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({ hasPermission: () => false }));
    vi.mocked(adminDashboardApi.overview).mockResolvedValue({} as never);

    renderHook(() => useDashboardOverview(), { wrapper });

    expect(adminDashboardApi.overview).not.toHaveBeenCalled();
  });

  it('fires the query for an account that does hold PLATFORM_STATS_VIEW', async () => {
    vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({ hasPermission: (p: string) => p === 'PLATFORM_STATS_VIEW' }));
    vi.mocked(adminDashboardApi.overview).mockResolvedValue({} as never);

    renderHook(() => useDashboardOverview(), { wrapper });

    await waitFor(() => expect(adminDashboardApi.overview).toHaveBeenCalledTimes(1));
  });
});
