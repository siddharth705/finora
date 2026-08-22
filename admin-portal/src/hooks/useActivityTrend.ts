import { useQuery } from '@tanstack/react-query';
import { adminDashboardApi } from '../api/endpoints';
import { useAdminAuth } from '../context/AdminAuthContext';

/**
 * Platform Activity chart data (dashboard redesign PR4) -- its own query rather than folded into
 * useDashboardOverview, since the 7-day trend is materially heavier to compute (21 queries on the
 * backend) than the rest of the overview and only Dashboard.tsx itself needs it, unlike the
 * overview data NotificationBell also shares. `enabled` mirrors the same
 * @PreAuthorize("hasAuthority('PLATFORM_STATS_VIEW')") gate as GET /admin/dashboard/overview and
 * /activation-funnel -- see useDashboardOverview's own doc comment for why this matters.
 */
export function useActivityTrend() {
  const { hasPermission } = useAdminAuth();
  const canSeeTrend = hasPermission('PLATFORM_STATS_VIEW');
  return useQuery({
    queryKey: ['admin-dashboard-activity-trend'],
    queryFn: () => adminDashboardApi.activityTrend(),
    enabled: canSeeTrend,
  });
}
