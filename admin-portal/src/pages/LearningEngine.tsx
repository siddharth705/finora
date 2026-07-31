import { useQuery } from '@tanstack/react-query';
import { Sparkles, CheckCircle2, RotateCcw, TrendingUp } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { StatCard } from '../components/StatCard';
import { RequirePermission } from '../components/ProtectedRoute';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { adminLearningApi } from '../api/endpoints';
import type { LearningGrowthPoint } from '../types';

/**
 * Platform-wide Learning Engine visibility -- there's no self-service equivalent of this page
 * (MerchantLearningService.summary() is always scoped to CurrentUser), so this is purely an
 * admin aggregate. To see or act on one specific account's learning history, open that account
 * from Users and use the Learning section there (read-only there too -- confirm/undo/reset stay
 * on the self-service Merchants console, see AdminUserLearningController's class comment).
 */
function LearningEngineContent() {
  const { data: stats, isLoading } = useQuery({
    queryKey: ['admin-learning-stats'],
    queryFn: () => adminLearningApi.platformStats(),
  });

  const trendColumns: DataTableColumn<LearningGrowthPoint>[] = [
    { header: 'Month', render: (p) => p.month },
    { header: 'Learned', render: (p) => p.learnedCount, cellClassName: 'text-success' },
    { header: 'Corrected', render: (p) => p.correctedCount, cellClassName: 'text-warning' },
  ];

  return (
    <div className="space-y-6">
      <p className="text-sm text-muted max-w-xl">
        Every merchant category confirmation, correction, and reset across every user's account,
        aggregated. A rising Corrected count month over month means the engine's guesses are
        being overridden more often, not less -- the opposite of what "learning growth" should
        look like if it's working.
      </p>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <StatCard icon={Sparkles} label="Learned pairs" value={isLoading ? '…' : stats?.learnedMerchantPairs ?? 0} />
        <StatCard icon={CheckCircle2} label="Confirmations" value={isLoading ? '…' : stats?.totalConfirmations ?? 0} />
        <StatCard icon={TrendingUp} label="Corrections" value={isLoading ? '…' : stats?.correctedCount ?? 0} />
        <StatCard icon={RotateCcw} label="Resets" value={isLoading ? '…' : stats?.resetCount ?? 0} />
      </div>

      <div>
        <h2 className="text-sm font-semibold text-muted uppercase tracking-wide mb-3">Monthly trend</h2>
        <DataTable
          columns={trendColumns}
          rows={stats?.trend}
          keyFor={(p) => p.month}
          loading={isLoading}
          emptyMessage="No learning activity recorded on the platform yet."
        />
      </div>
    </div>
  );
}

export default function LearningEngine() {
  return (
    <AdminLayout title="Learning Engine" subtitle="Platform-wide merchant category learning, aggregated across every user">
      <RequirePermission permission="MERCHANT_MANAGE">
        <LearningEngineContent />
      </RequirePermission>
    </AdminLayout>
  );
}
