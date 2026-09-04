import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { Pagination } from '../components/Pagination';
import { FilterBar } from '../components/FilterBar';
import { adminSupportTicketApi } from '../api/endpoints';
import { formatWhen } from '../lib/formatWhen';
import type { SupportTicketCategory, SupportTicketRow, SupportTicketStatus } from '../types';

const PAGE_SIZE = 25;

const STATUS_OPTIONS: { label: string; value: SupportTicketStatus }[] = [
  { label: 'Open', value: 'OPEN' },
  { label: 'In Progress', value: 'IN_PROGRESS' },
  { label: 'Resolved', value: 'RESOLVED' },
  { label: 'Closed', value: 'CLOSED' },
];

const CATEGORY_OPTIONS: { label: string; value: SupportTicketCategory }[] = [
  { label: 'Statement import', value: 'STATEMENT_IMPORT' },
  { label: 'Categorization', value: 'CATEGORIZATION' },
  { label: 'Account linking', value: 'ACCOUNT_LINKING' },
  { label: 'Data accuracy', value: 'DATA_ACCURACY' },
  { label: 'Technical issue', value: 'TECHNICAL_ISSUE' },
  { label: 'Other', value: 'OTHER' },
];

const STATUS_TONE: Record<SupportTicketStatus, string> = {
  OPEN: 'text-accent',
  IN_PROGRESS: 'text-amber-400',
  RESOLVED: 'text-emerald-400',
  CLOSED: 'text-muted',
};

/**
 * Support, Help & Feedback v1, Phase 9. Same shape as HeldStatements.tsx -- a filtered,
 * server-paginated queue with each row linking into its own detail page. Primary columns per the
 * proposal: Ticket Number, Subject, Category, Status, Created At -- Claimed by is added as a
 * fifth, ahead of any row being opened, because the plan itself requires it: "since takeover is
 * permitted, this column is the safeguard, so it has to be visible before anyone opens a ticket."
 */
function SupportTicketsContent() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<SupportTicketStatus | ''>('');
  const [category, setCategory] = useState<SupportTicketCategory | ''>('');

  const list = useQuery({
    queryKey: ['support-tickets-list', page, status, category],
    queryFn: () => adminSupportTicketApi.list({
      page,
      size: PAGE_SIZE,
      status: status || undefined,
      category: category || undefined,
    }),
  });

  const columns: DataTableColumn<SupportTicketRow>[] = [
    {
      header: 'Ticket Number',
      render: (row) => (
        <Link to={`/support-tickets/${row.id}`} className="text-ink font-mono text-xs text-accent hover:underline">
          {row.ticketNumber}
        </Link>
      ),
    },
    {
      header: 'Subject',
      render: (row) => <span className="text-ink text-sm">{row.subject}</span>,
    },
    {
      header: 'Category',
      render: (row) => (
        <span className="text-muted text-xs">
          {CATEGORY_OPTIONS.find((c) => c.value === row.category)?.label ?? row.category}
        </span>
      ),
    },
    {
      header: 'Status',
      render: (row) => <span className={`text-xs font-medium ${STATUS_TONE[row.status]}`}>{row.status.replace(/_/g, ' ')}</span>,
    },
    {
      header: 'Claimed by',
      // Raw id, not a resolved name -- same convention HeldStatementDetail.tsx already uses for
      // assignedEngineerId; there is no admin-name-lookup anywhere in this portal to resolve
      // against.
      render: (row) => <span className="text-muted text-xs font-mono">{row.claimedByAdminId ?? '—'}</span>,
    },
    { header: 'Created At', render: (row) => formatWhen(row.createdAt) },
  ];

  return (
    <div className="space-y-6">
      <FilterBar
        fields={[
          {
            type: 'select', key: 'status', value: status,
            onChange: (v) => { setStatus(v as SupportTicketStatus | ''); setPage(0); },
            placeholder: 'All statuses',
            label: 'Filter by status',
            options: STATUS_OPTIONS,
          },
          {
            type: 'select', key: 'category', value: category,
            onChange: (v) => { setCategory(v as SupportTicketCategory | ''); setPage(0); },
            placeholder: 'All categories',
            label: 'Filter by category',
            options: CATEGORY_OPTIONS,
          },
        ]}
      />

      <DataTable
        columns={columns}
        rows={list.data?.content ?? []}
        keyFor={(row) => row.id}
        loading={list.isLoading}
        emptyMessage="No support tickets match this filter."
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

export default function SupportTickets() {
  return (
    <AdminLayout title="Support Tickets" subtitle="Requests users have filed, newest first.">
      <RequirePermission permission="SUPPORT_MANAGE">
        <SupportTicketsContent />
      </RequirePermission>
    </AdminLayout>
  );
}
