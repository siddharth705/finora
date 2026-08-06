import { useQuery } from '@tanstack/react-query';
import { Tag, Store } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { adminPlatformAnalyticsApi } from '../api/endpoints';
import type { PlatformCategorySpendDto, PlatformMerchantSpendDto } from '../types';

// Matches every other money formatter in both apps: the currency symbol before the digits but
// after the sign, and 'en-IN' pinned explicitly. This used to omit the symbol entirely and pass
// undefined as the locale, so an unlabelled spend figure sat beside an unlabelled transaction
// count and grouped according to the visiting admin's own OS locale -- 12,34,567 for one admin
// and 1,234,567 for another, looking at the same platform total.
function formatCurrency(amount: number) {
  return (amount < 0 ? '-₹' : '₹')
    + Math.abs(amount).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/**
 * Platform-wide spend analytics -- top categories and top merchants by EXPENSE spend, summed
 * across every user's own private rows that share the same name (see
 * AdminPlatformAnalyticsService's class comment for why that name-based grouping is the right
 * answer here, not id-level grouping). AnalyticsController's self-service equivalents are always
 * scoped to CurrentUser, by design -- this has no per-user counterpart to link out to.
 */
function PlatformAnalyticsContent() {
  const { data, isLoading } = useQuery({
    queryKey: ['admin-platform-analytics'],
    queryFn: () => adminPlatformAnalyticsApi.get(),
  });

  const categoryColumns: DataTableColumn<PlatformCategorySpendDto>[] = [
    {
      header: 'Category',
      render: (c) => (
        <div className="flex items-center gap-2.5">
          <span className="w-7 h-7 rounded-lg bg-bg border border-border flex items-center justify-center flex-shrink-0">
            <Tag size={13} className="text-muted" />
          </span>
          <span className="font-medium text-ink">{c.categoryName}</span>
        </div>
      ),
    },
    { header: 'Total spend', render: (c) => formatCurrency(c.totalSpend), cellClassName: 'text-muted' },
    { header: 'Transactions', render: (c) => c.transactionCount, cellClassName: 'text-muted' },
  ];

  const merchantColumns: DataTableColumn<PlatformMerchantSpendDto>[] = [
    {
      header: 'Merchant',
      render: (m) => (
        <div className="flex items-center gap-2.5">
          <span className="w-7 h-7 rounded-lg bg-bg border border-border flex items-center justify-center flex-shrink-0">
            <Store size={13} className="text-muted" />
          </span>
          <span className="font-medium text-ink">{m.merchantName}</span>
        </div>
      ),
    },
    { header: 'Total spend', render: (m) => formatCurrency(m.totalSpend), cellClassName: 'text-muted' },
    { header: 'Transactions', render: (m) => m.transactionCount, cellClassName: 'text-muted' },
  ];

  return (
    <div className="space-y-8">
      <p className="text-sm text-muted max-w-xl">
        Top 10 categories and merchants by EXPENSE spend, platform-wide -- excludes duplicates,
        internal transfers, and refunded income, the same exclusion rules the self-service
        Analytics views already apply per account.
      </p>

      <div>
        <h2 className="text-sm font-semibold text-muted uppercase tracking-wide mb-3">Top categories</h2>
        <DataTable
          columns={categoryColumns}
          rows={data?.topCategories}
          keyFor={(c) => c.categoryName}
          loading={isLoading}
          emptyMessage="No categorized spend recorded on the platform yet."
        />
      </div>

      <div>
        <h2 className="text-sm font-semibold text-muted uppercase tracking-wide mb-3">Top merchants</h2>
        <DataTable
          columns={merchantColumns}
          rows={data?.topMerchants}
          keyFor={(m) => m.merchantName}
          loading={isLoading}
          emptyMessage="No merchant spend recorded on the platform yet."
        />
      </div>
    </div>
  );
}

export default function PlatformAnalytics() {
  return (
    <AdminLayout title="Platform Analytics" subtitle="Platform-wide spend analytics, aggregated across every user">
      <RequirePermission permission="PLATFORM_ANALYTICS_VIEW">
        <PlatformAnalyticsContent />
      </RequirePermission>
    </AdminLayout>
  );
}
