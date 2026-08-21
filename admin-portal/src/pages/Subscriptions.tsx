import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Users as UsersIcon } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { useNotify } from '../context/NotificationContext';
import { adminSubscriptionsApi } from '../api/endpoints';
import type { SubscriptionSummaryDto } from '../types';

function errorMessage(err: any, fallback: string) {
  return err?.response?.data?.message ?? fallback;
}

// D-28 PR4-A. Every plan a subscription can be manually moved to -- FREE is included so an admin
// can revert a manually-granted Plus/Premium, not just move upward.
const PLAN_CODES = ['FREE', 'PLUS', 'PREMIUM'];

/**
 * D-28 PR4-A. Manual plan changes are, for now, the only way anyone reaches Plus/Premium -- no
 * payment gateway exists yet (proposal §10). "Admin manual override" is a fixed reason: refining
 * the actual reason-capture UX is a later product decision, not a blocker for this first cut.
 */
function SubscriptionsContent() {
  const queryClient = useQueryClient();
  const notify = useNotify();
  const { data, isLoading } = useQuery({
    queryKey: ['admin-subscriptions'],
    queryFn: () => adminSubscriptionsApi.list(),
  });

  const changePlanMutation = useMutation({
    mutationFn: ({ userId, planCode }: { userId: string; planCode: string }) =>
      adminSubscriptionsApi.changePlan(userId, planCode, 'Admin manual override'),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['admin-subscriptions'] });
      notify.success('Plan updated.');
    },
    onError: (err: any) => notify.error(errorMessage(err, 'Failed to change this plan.')),
  });

  const columns: DataTableColumn<SubscriptionSummaryDto>[] = [
    {
      header: 'User',
      render: (s) => (
        <div className="flex items-center gap-2.5">
          <span className="w-7 h-7 rounded-lg bg-bg border border-border flex items-center justify-center flex-shrink-0">
            <UsersIcon size={13} className="text-muted" />
          </span>
          <div className="min-w-0">
            <p className="font-medium text-ink truncate">{s.userFullName ?? '(no name)'}</p>
            <p className="text-xs text-muted truncate">{s.userEmail}</p>
          </div>
        </div>
      ),
    },
    {
      header: 'Plan',
      render: (s) => (
        <select
          value={s.planCode ?? ''}
          disabled={changePlanMutation.isPending}
          onChange={(e) => changePlanMutation.mutate({ userId: s.userId, planCode: e.target.value })}
          className="text-xs border border-border rounded-lg px-2 py-1.5 bg-card text-ink"
        >
          {PLAN_CODES.map((code) => (
            <option key={code} value={code}>{code}</option>
          ))}
        </select>
      ),
    },
    { header: 'Status', render: (s) => s.status, cellClassName: 'text-muted' },
    { header: 'Start date', render: (s) => s.startDate, cellClassName: 'text-muted' },
    { header: 'Renewal date', render: (s) => s.renewalDate ?? '—', cellClassName: 'text-muted' },
  ];

  return (
    <div className="space-y-4">
      <p className="text-sm text-muted max-w-xl">
        Every user's current plan. Changing the dropdown grants or revokes access immediately --
        there is no payment gateway yet, so this is the only way anyone reaches Plus or Premium
        today.
      </p>
      <DataTable
        columns={columns}
        rows={data}
        keyFor={(s) => s.subscriptionId}
        loading={isLoading}
        emptyMessage="No subscriptions yet."
      />
    </div>
  );
}

export default function Subscriptions() {
  return (
    <AdminLayout title="Subscriptions" subtitle="Manage user plans -- Free, Plus, Premium">
      <RequirePermission permission="SUBSCRIPTION_MANAGEMENT_VIEW">
        <SubscriptionsContent />
      </RequirePermission>
    </AdminLayout>
  );
}
