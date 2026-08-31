import { useQuery } from '@tanstack/react-query';
import { adminDashboardApi } from '../api/endpoints';
import { useAdminAuth } from '../context/AdminAuthContext';

/**
 * Shared React Query cache entry for the Operational Dashboard's overview data -- Dashboard.tsx
 * and AdminLayout's NotificationBell (dashboard redesign PR3) both read from this ONE query
 * instead of each firing their own request, since the bell needs the same alerts/needsAttention
 * data Dashboard already displays. `enabled` mirrors the backend's own
 * @PreAuthorize("hasAuthority('PLATFORM_STATS_VIEW')") on GET /admin/dashboard/overview --
 * AdminLayout wraps every admin page, including ones an account without that permission can
 * still reach, so this must not fire (and 403) for them.
 */
export function useDashboardOverview() {
  const { hasPermission } = useAdminAuth();
  const canSeeOverview = hasPermission('PLATFORM_STATS_VIEW');
  return useQuery({
    queryKey: ['admin-dashboard-overview'],
    queryFn: () => adminDashboardApi.overview(),
    enabled: canSeeOverview,
  });
}
