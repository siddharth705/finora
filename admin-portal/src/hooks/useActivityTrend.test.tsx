import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { useActivityTrend } from './useActivityTrend';
import { useAdminAuth } from '../context/AdminAuthContext';
import { adminDashboardApi } from '../api/endpoints';
import { mockAdminAuthState } from '../test/mockAdminAuth';

vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminDashboardApi: { activityTrend: vi.fn() },
}));

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

describe('useActivityTrend', () => {
  beforeEach(() => {
    vi.mocked(adminDashboardApi.activityTrend).mockReset();
  });

  it('does not fire the query for an account without PLATFORM_STATS_VIEW -- mirrors the backend @PreAuthorize', () => {
    vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({ hasPermission: () => false }));
    vi.mocked(adminDashboardApi.activityTrend).mockResolvedValue([]);

    renderHook(() => useActivityTrend(), { wrapper });

    expect(adminDashboardApi.activityTrend).not.toHaveBeenCalled();
  });

  it('fires the query for an account that does hold PLATFORM_STATS_VIEW', async () => {
    vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({ hasPermission: (p: string) => p === 'PLATFORM_STATS_VIEW' }));
    vi.mocked(adminDashboardApi.activityTrend).mockResolvedValue([]);

    renderHook(() => useActivityTrend(), { wrapper });

    await waitFor(() => expect(adminDashboardApi.activityTrend).toHaveBeenCalledTimes(1));
  });
});
