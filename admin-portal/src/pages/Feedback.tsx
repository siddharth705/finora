import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { Pagination } from '../components/Pagination';
import { FilterBar } from '../components/FilterBar';
import { adminFeedbackApi } from '../api/endpoints';
import { formatWhen } from '../lib/formatWhen';
import type { FeedbackBreakdownCount, FeedbackContext, FeedbackRow, FeedbackType } from '../types';

const PAGE_SIZE = 25;

const TYPE_OPTIONS: { label: string; value: FeedbackType }[] = [
  { label: 'Bug', value: 'BUG' },
  { label: 'Feature request', value: 'FEATURE_REQUEST' },
  { label: 'Improvement', value: 'IMPROVEMENT' },
  { label: 'General', value: 'GENERAL' },
];

const CONTEXT_OPTIONS: { label: string; value: FeedbackContext }[] = [
  { label: 'Dashboard', value: 'DASHBOARD' },
  { label: 'Transactions', value: 'TRANSACTIONS' },
  { label: 'Reports', value: 'REPORTS' },
  { label: 'Budgets', value: 'BUDGETS' },
  { label: 'Goals', value: 'GOALS' },
  { label: 'Import flow', value: 'IMPORT_FLOW' },
  { label: 'Accounts', value: 'ACCOUNTS' },
  { label: 'Settings', value: 'SETTINGS' },
  { label: 'Help', value: 'HELP' },
  { label: 'Other', value: 'OTHER' },
];

/** One dimension's counts as horizontal bars, widest first -- countGrouped() already sorted them
 *  that way (see FeedbackDto.Breakdown.from's own doc comment), this just draws it. */
function BreakdownPanel({ title, counts }: { title: string; counts: FeedbackBreakdownCount[] }) {
  const max = counts.length > 0 ? counts[0].total : 0;
  return (
    <div className="bg-card border border-border rounded-xl2 p-6">
      <h3 className="text-sm font-semibold text-ink mb-3">{title}</h3>
      {counts.length === 0 ? (
        <p className="text-xs text-muted">No feedback yet.</p>
      ) : (
        <ul className="space-y-2">
          {counts.map((c) => (
            <li key={c.label} className="text-xs">
              <div className="flex justify-between mb-1">
                <span className="text-ink">{c.label.replace(/_/g, ' ')}</span>
                <span className="text-muted font-mono">{c.total}</span>
              </div>
              <div className="h-1.5 rounded-full bg-bg overflow-hidden">
                <div
                  className="h-full rounded-full bg-accent"
                  style={{ width: max > 0 ? `${(c.total / max) * 100}%` : '0%' }}
                />
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/**
 * Support, Help & Feedback v1, Phase 9. Proposal §3.4 / the plan's own words: "List. Filter.
 * Counts. Display breakdowns by: Type, Context, Source. No trend analysis. No AI clustering. No
 * dashboards." -- so the breakdown panel below is three static bar lists, not a chart library, and
 * there is deliberately no time axis anywhere on this page.
 */
function FeedbackContent() {
  const [page, setPage] = useState(0);
  const [type, setType] = useState<FeedbackType | ''>('');
  const [context, setContext] = useState<FeedbackContext | ''>('');

  const list = useQuery({
    queryKey: ['feedback-list', page, type, context],
    queryFn: () => adminFeedbackApi.list({
      page,
      size: PAGE_SIZE,
      type: type || undefined,
      context: context || undefined,
    }),
  });
  // Always unfiltered -- see adminFeedbackApi.breakdown's own doc comment. Not refetched when
  // type/context change above; there is nothing for those filters to affect here.
  const breakdown = useQuery({
    queryKey: ['feedback-breakdown'],
    queryFn: () => adminFeedbackApi.breakdown(),
  });

  const columns: DataTableColumn<FeedbackRow>[] = [
    {
      header: 'Type',
      render: (row) => <span className="text-ink text-xs">{TYPE_OPTIONS.find((t) => t.value === row.type)?.label ?? row.type}</span>,
    },
    {
      header: 'Context',
      render: (row) => <span className="text-muted text-xs">{CONTEXT_OPTIONS.find((c) => c.value === row.context)?.label ?? row.context}</span>,
    },
    {
      header: 'Source',
      render: (row) => <span className="text-muted text-xs">{row.source.replace(/_/g, ' ')}</span>,
    },
    {
      header: 'Message',
      render: (row) => (
        <span className="text-ink text-sm" title={row.message}>
          {row.message.length > 80 ? `${row.message.slice(0, 80)}…` : row.message}
        </span>
      ),
    },
    { header: 'Submitted', render: (row) => formatWhen(row.createdAt) },
  ];

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <BreakdownPanel title={`By Type (${breakdown.data?.total ?? 0} total)`} counts={breakdown.data?.byType ?? []} />
        <BreakdownPanel title="By Context" counts={breakdown.data?.byContext ?? []} />
        <BreakdownPanel title="By Source" counts={breakdown.data?.bySource ?? []} />
      </div>

      <FilterBar
        fields={[
          {
            type: 'select', key: 'type', value: type,
            onChange: (v) => { setType(v as FeedbackType | ''); setPage(0); },
            placeholder: 'All types',
            label: 'Filter by type',
            options: TYPE_OPTIONS,
          },
          {
            type: 'select', key: 'context', value: context,
            onChange: (v) => { setContext(v as FeedbackContext | ''); setPage(0); },
            placeholder: 'All contexts',
            label: 'Filter by context',
            options: CONTEXT_OPTIONS,
          },
        ]}
      />

      <DataTable
        columns={columns}
        rows={list.data?.content ?? []}
        keyFor={(row) => row.id}
        loading={list.isLoading}
        emptyMessage="No feedback matches this filter."
      />

      {list.data && (
        <Pagination
          page={page}
          totalPages={list.data.totalPages}
          totalElements={list.data.totalElements}
          pageSize={PAGE_SIZE}
          onPageChange={setPage}
        />
      )}
    </div>
  );
}

export default function Feedback() {
  return (
    <AdminLayout title="Feedback" subtitle="What users are telling us, unprompted.">
      <RequirePermission permission="SUPPORT_MANAGE">
        <FeedbackContent />
      </RequirePermission>
    </AdminLayout>
  );
}
