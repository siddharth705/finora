import { useQuery } from '@tanstack/react-query';
import { CheckCircle2, Copy, ArrowLeftRight, Undo2, Repeat, Layers, RotateCcw, TrendingUp, History } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { StatCard } from '../components/StatCard';
import { RequirePermission } from '../components/ProtectedRoute';
import { adminReconciliationApi } from '../api/endpoints';

/**
 * Platform-wide Reconciliation Monitor -- ReconciliationService has always run fully
 * automatically (after every import/create/edit/delete, no manual trigger anywhere in this
 * codebase), so there's nothing to manage here, only to observe. Per-user reconciliation +
 * Workspace Health visibility lives on that account's own Users detail page (WorkspaceSection),
 * proxying the same WorkspaceDashboardService.summarize() the self-service Workspace Dashboard
 * used before this moved off the User Portal's main nav.
 */
function ReconciliationMonitorContent() {
  const { data: stats, isLoading } = useQuery({
    queryKey: ['admin-reconciliation-stats'],
    queryFn: () => adminReconciliationApi.platformStats(),
  });

  return (
    <div className="space-y-6">
      <p className="text-sm text-muted max-w-xl">
        Every transaction on the platform, broken down by how reconciliation resolved it. OK is
        everything left untouched; the rest were automatically flagged as a likely duplicate,
        internal transfer, refund, reversal, investment transfer, or superseded by a re-uploaded
        statement, and excluded from spend totals. To investigate one specific account, open it
        from Users and use the Workspace section there.
      </p>

      <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
        <StatCard icon={Layers} label="Total transactions" value={isLoading ? '…' : stats?.totalTransactions ?? 0} />
        <StatCard icon={CheckCircle2} label="OK" value={isLoading ? '…' : stats?.okCount ?? 0} tone="success" />
        <StatCard icon={Repeat} label="Recurring" value={isLoading ? '…' : stats?.recurringCount ?? 0} />
        <StatCard icon={Copy} label="Duplicates" value={isLoading ? '…' : stats?.duplicateCount ?? 0} tone="warning" />
        <StatCard icon={ArrowLeftRight} label="Transfers" value={isLoading ? '…' : stats?.transferCount ?? 0} tone="warning" />
        <StatCard icon={Undo2} label="Refunds" value={isLoading ? '…' : stats?.refundCount ?? 0} tone="warning" />
        <StatCard icon={RotateCcw} label="Reversals" value={isLoading ? '…' : stats?.reversalCount ?? 0} tone="warning" />
        <StatCard icon={TrendingUp} label="Investment transfers" value={isLoading ? '…' : stats?.investmentTransferCount ?? 0} tone="warning" />
        <StatCard icon={History} label="Superseded" value={isLoading ? '…' : stats?.supersededCount ?? 0} tone="warning" />
      </div>
    </div>
  );
}

export default function ReconciliationMonitor() {
  return (
    <AdminLayout title="Reconciliation Monitor" subtitle="Platform-wide reconciliation outcomes, aggregated across every user">
      <RequirePermission permission="RECONCILIATION_VIEW">
        <ReconciliationMonitorContent />
      </RequirePermission>
    </AdminLayout>
  );
}
