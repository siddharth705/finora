import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Mail, Store } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { adminMerchantsApi } from '../api/endpoints';
import { formatWhen } from '../lib/formatWhen';
import type { GmailMerchantParserStatDto, MerchantStatDto } from '../types';

const GMAIL_STATS_WINDOW_DAYS = 30;

/** Green at a healthy rate, amber in between, red once a parser is mostly failing -- the same
 *  three-tier severity split Dashboard.tsx already uses for system health, reused here rather
 *  than inventing new thresholds for what is the same "is this okay / watch it / fix it" signal. */
function successRateClassName(rate: number): string {
  if (rate >= 0.9) return 'text-success';
  if (rate >= 0.5) return 'text-warning';
  return 'text-danger';
}

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

/**
 * Gmail receipt parser health, by authenticated sending domain -- C6.2. A distinct dataset from
 * the platform catalog above (email domains and extraction outcomes, not the categorization
 * catalog), shown on the same page because it's the same MERCHANT_MANAGE audience asking the same
 * kind of question: "which merchants need attention?" The problem this answers: a merchant
 * changes their email template, a parser's success rate silently collapses, and today Fynora
 * finds out when a user complains rather than before.
 *
 * <p>Sorted worst-success-rate-first by the backend, so the domains most worth a look are already
 * at the top -- see GmailMerchantStatsService's own ordering comment.
 */
function GmailParserStatsContent() {
  const since = useMemo(
    () => new Date(Date.now() - GMAIL_STATS_WINDOW_DAYS * 24 * 60 * 60 * 1000),
    [],
  );
  const { data: stats, isLoading } = useQuery({
    queryKey: ['admin-gmail-merchant-parser-stats', since.toISOString()],
    queryFn: () => adminMerchantsApi.gmailParserStats(since),
  });

  const columns: DataTableColumn<GmailMerchantParserStatDto>[] = [
    {
      header: 'Merchant',
      render: (m) => (
        <div className="flex items-center gap-2.5">
          <span className="w-7 h-7 rounded-lg bg-bg border border-border flex items-center justify-center flex-shrink-0">
            <Mail size={13} className="text-muted" />
          </span>
          <div>
            <div className="font-medium text-ink">{m.merchant}</div>
            <div className="text-xs text-muted">{m.domain}</div>
          </div>
        </div>
      ),
    },
    {
      header: 'Success rate',
      render: (m) => m.successRate === null
        ? <span className="text-muted italic">No parser yet</span>
        : <span className={successRateClassName(m.successRate)}>{Math.round(m.successRate * 100)}%</span>,
    },
    { header: 'Parsed', render: (m) => m.parsed, cellClassName: 'text-muted' },
    { header: 'Failed', render: (m) => m.parseFailed, cellClassName: 'text-muted' },
    { header: 'Not a receipt', render: (m) => m.skippedNotReceipt, cellClassName: 'text-muted' },
    { header: 'No parser', render: (m) => m.noParserYet, cellClassName: 'text-muted' },
    { header: 'Last seen', render: (m) => formatWhen(m.lastSeen), cellClassName: 'text-muted' },
  ];

  return (
    <div className="space-y-6">
      <p className="text-sm text-muted max-w-xl">
        Every trusted-sender email Fynora's Gmail sync has processed in the last {GMAIL_STATS_WINDOW_DAYS} days,
        grouped by sending domain. "No parser" is coverage the platform doesn't have yet, not a
        fault -- it has no success rate to report. A domain with a real rate that drops is the
        signal worth acting on: the merchant likely changed their email template.
      </p>
      <DataTable
        columns={columns}
        rows={stats}
        keyFor={(m) => m.domain}
        loading={isLoading}
        emptyMessage={`No Gmail receipts processed in the last ${GMAIL_STATS_WINDOW_DAYS} days.`}
      />
    </div>
  );
}

export default function MerchantIntelligence() {
  return (
    <AdminLayout title="Merchant Intelligence" subtitle="Platform-wide merchant catalog, aggregated across every user">
      <RequirePermission permission="MERCHANT_MANAGE">
        <div className="space-y-10">
          <MerchantIntelligenceContent />
          <div>
            <h2 className="text-lg font-semibold text-ink mb-1">Gmail receipt parsers</h2>
            <GmailParserStatsContent />
          </div>
        </div>
      </RequirePermission>
    </AdminLayout>
  );
}
