import { useQuery } from '@tanstack/react-query';
import { Store } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { adminMerchantsApi } from '../api/endpoints';
import type { MerchantStatDto } from '../types';

/**
 * Platform-wide merchant catalog -- there's no shared/canonical merchant table today (see the
 * backend's Merchant.java class comment), so this is purely an aggregate view over every user's
 * own private merchant rows, grouped by name. userCount vs. rowCount surfaces the same signal a
 * duplicate-cleanup pass would want: a merchant name held by many distinct users (Swiggy, Amazon,
 * ...) is a platform-common one, while a name with more rows than users on one account hints at
 * that user having near-duplicate spellings worth merging (see the Merchants section on a user's
 * own detail page for the actual rename/merge actions, which stay per-user by design).
 */
function MerchantIntelligenceContent() {
  const { data: merchants, isLoading } = useQuery({
    queryKey: ['admin-merchant-stats'],
    queryFn: () => adminMerchantsApi.platformStats(),
  });

  const columns: DataTableColumn<MerchantStatDto>[] = [
    {
      header: 'Merchant',
      render: (m) => (
        <div className="flex items-center gap-2.5">
          <span className="w-7 h-7 rounded-lg bg-bg border border-border flex items-center justify-center flex-shrink-0">
            <Store size={13} className="text-muted" />
          </span>
          <span className="font-medium text-ink">{m.canonicalName}</span>
        </div>
      ),
    },
    { header: 'Users', render: (m) => m.userCount, cellClassName: 'text-muted' },
    { header: 'Merchant rows', render: (m) => m.rowCount, cellClassName: 'text-muted' },
  ];

  return (
    <div className="space-y-6">
      <p className="text-sm text-muted max-w-xl">
        Every account builds its own private merchant list as transactions get categorized -- this
        is an aggregate view across all of them, grouped by name. To rename or merge a specific
        user's merchants, open that account from Users and use the Merchants section there.
      </p>
      <DataTable
        columns={columns}
        rows={merchants}
        keyFor={(m) => m.canonicalName}
        loading={isLoading}
        emptyMessage="No merchants recorded on the platform yet."
      />
    </div>
  );
}

export default function MerchantIntelligence() {
  return (
    <AdminLayout title="Merchant Intelligence" subtitle="Platform-wide merchant catalog, aggregated across every user">
      <RequirePermission permission="MERCHANT_MANAGE">
        <MerchantIntelligenceContent />
      </RequirePermission>
    </AdminLayout>
  );
}
