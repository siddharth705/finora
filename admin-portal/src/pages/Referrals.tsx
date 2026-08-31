import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Users as UsersIcon } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { Pagination } from '../components/Pagination';
import { useNotify } from '../context/NotificationContext';
import { adminReferralsApi } from '../api/endpoints';
import type { AdminReferralSummaryDto } from '../types';

const PAGE_SIZE = 20;

function errorMessage(err: any, fallback: string) {
  return err?.response?.data?.message ?? fallback;
}

function statusBadge(status: string) {
  switch (status) {
    case 'REWARDED': return 'text-success';
    case 'SUBSCRIBED': return 'text-primary';
    default: return 'text-muted';
  }
}

function userCell(email: string | null, fullName: string | null) {
  return (
    <div className="flex items-center gap-2.5">
      <span className="w-7 h-7 rounded-lg bg-bg border border-border flex items-center justify-center flex-shrink-0">
        <UsersIcon size={13} className="text-muted" />
      </span>
      <div className="min-w-0">
        <p className="font-medium text-ink truncate">{fullName ?? '(no name)'}</p>
        <p className="text-xs text-muted truncate">{email}</p>
      </div>
    </div>
  );
}

/**
 * D-28 PR4-C. Reward crediting is admin-manual (see backend ReferralService.creditReward's own
 * doc comment for why the actual amount is never invented automatically) -- this page is the only
 * place it happens, same "admin types the number, backend enforces the rules" pattern
 * Subscriptions.tsx's plan-change dropdown already established. "Admin credited referral reward"
 * is a fixed reason, same simplification Subscriptions.tsx's "Admin manual override" makes:
 * refining reason-capture UX is a later decision, not a blocker for this first cut.
 */
function ReferralsContent() {
  const queryClient = useQueryClient();
  const notify = useNotify();
  const [amounts, setAmounts] = useState<Record<string, string>>({});
  const [page, setPage] = useState(0);
  const { data, isLoading } = useQuery({
    queryKey: ['admin-referrals', page],
    queryFn: () => adminReferralsApi.list(page, PAGE_SIZE),
  });

  const creditMutation = useMutation({
    mutationFn: ({ referralId, amount }: { referralId: string; amount: number }) =>
      adminReferralsApi.creditReward(referralId, amount, 'Admin credited referral reward'),
    onSuccess: (_data, variables) => {
      void queryClient.invalidateQueries({ queryKey: ['admin-referrals'] });
      setAmounts((prev) => {
        const next = { ...prev };
        delete next[variables.referralId];
        return next;
      });
      notify.success('Reward credited.');
    },
    onError: (err: any) => notify.error(errorMessage(err, 'Failed to credit this reward.')),
  });

  const columns: DataTableColumn<AdminReferralSummaryDto>[] = [
    { header: 'Referrer', render: (r) => userCell(r.referrerEmail, r.referrerFullName) },
    { header: 'Referred', render: (r) => userCell(r.referredEmail, r.referredFullName) },
    {
      header: 'Status',
      render: (r) => <span className={`font-medium ${statusBadge(r.status)}`}>{r.status}</span>,
    },
    {
      header: 'Reward',
      render: (r) => {
        if (r.status === 'REWARDED') return `₹${r.reward}`;
        if (r.status !== 'SUBSCRIBED') return <span className="text-muted">—</span>;
        return (
          <div className="flex items-center gap-1.5">
            <input
              type="number"
              min="0.01"
              step="0.01"
              placeholder="Amount"
              value={amounts[r.referralId] ?? ''}
              onChange={(e) => setAmounts((prev) => ({ ...prev, [r.referralId]: e.target.value }))}
              className="w-24 text-xs border border-border rounded-lg px-2 py-1.5 bg-card text-ink"
            />
            <button
              type="button"
              disabled={creditMutation.isPending || !amounts[r.referralId]}
              onClick={() => creditMutation.mutate({ referralId: r.referralId, amount: Number(amounts[r.referralId]) })}
              className="text-xs font-semibold bg-primary text-white rounded-lg px-2.5 py-1.5 disabled:opacity-40"
            >
              Credit
            </button>
          </div>
        );
      },
    },
    { header: 'Since', render: (r) => r.createdAt.slice(0, 10), cellClassName: 'text-muted' },
  ];

  return (
    <div className="space-y-4">
      <p className="text-sm text-muted max-w-xl">
        Every referral, both parties shown for abuse review. A reward can only be credited once a
        referral reaches Subscribed, and only once -- crediting is rejected if the two accounts
        share a device or IP.
      </p>
      <DataTable
        columns={columns}
        rows={data?.content ?? []}
        keyFor={(r) => r.referralId}
        loading={isLoading}
        emptyMessage="No referrals yet."
      />
      {data && (
        <Pagination
          page={page}
          totalPages={data.totalPages}
          totalElements={data.totalElements}
          pageSize={PAGE_SIZE}
          onPageChange={setPage}
        />
      )}
    </div>
  );
}

export default function Referrals() {
  return (
    <AdminLayout title="Referrals" subtitle="Review referrals and credit rewards">
      <RequirePermission permission="REFERRAL_MANAGEMENT_VIEW">
        <ReferralsContent />
      </RequirePermission>
    </AdminLayout>
  );
}
