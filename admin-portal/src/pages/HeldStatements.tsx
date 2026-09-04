import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { Pagination } from '../components/Pagination';
import { FilterBar } from '../components/FilterBar';
import { adminHeldStatementApi } from '../api/endpoints';
import { formatWhen } from '../lib/formatWhen';
import type { HeldStatementRow, HeldStatementStatus } from '../types';

const PAGE_SIZE = 25;

const STATUS_OPTIONS: { label: string; value: HeldStatementStatus }[] = [
  { label: 'Held', value: 'HELD' },
  { label: 'Assigned', value: 'ASSIGNED' },
  { label: 'Investigating', value: 'INVESTIGATING' },
  { label: 'Ready for import', value: 'READY_FOR_IMPORT' },
];

/**
 * The trust-review queue -- statements the pipeline held back not because parsing failed, but
 * because the extraction's own evidence contradicted it: a printed-versus-parsed count mismatch,
 * a row dropped where the document's own layout said one should exist, or a statement period that
 * does not hold together. `HeldImports.tsx` is the same shape of tool for the OTHER kind of hold
 * (a parser gap); this one is for a parse that worked but whose numbers do not add up.
 *
 * Every filter is optional and applies within the open queue only -- the server never returns a
 * resolved (imported or rejected) hold from this endpoint regardless of which filters are passed,
 * so there is nothing here that can accidentally expose one.
 */
function HeldStatementsContent() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<HeldStatementStatus | ''>('');
  const [bank, setBank] = useState('');
  const [bankInput, setBankInput] = useState('');
  const [engineerId, setEngineerId] = useState('');
  const [engineerIdInput, setEngineerIdInput] = useState('');
  const [olderThanHours, setOlderThanHours] = useState('');

  const list = useQuery({
    queryKey: ['held-statements-list', page, status, bank, engineerId, olderThanHours],
    queryFn: () => adminHeldStatementApi.list({
      page,
      size: PAGE_SIZE,
      status: status || undefined,
      bank: bank || undefined,
      engineerId: engineerId || undefined,
      olderThanHours: olderThanHours ? Number(olderThanHours) : undefined,
    }),
  });

  function applyFilters() {
    setBank(bankInput.trim());
    setEngineerId(engineerIdInput.trim());
    setPage(0);
  }

  const columns: DataTableColumn<HeldStatementRow>[] = [
    {
      header: 'Held ID',
      // The reference an operator quotes -- never the raw UUID. Links straight into the detail
      // page, same as the queue's whole purpose: this row is a worklist entry, not an end state.
      render: (row) => (
        <Link to={`/held-statements/${row.heldId}`} className="text-ink font-mono text-xs text-accent hover:underline">
          {row.heldId}
        </Link>
      ),
    },
    {
      header: 'User',
      // A bare id, deliberately: this controller never joins to a user's contact details, so no
      // email or phone number can reach this screen even indirectly.
      render: (row) => <span className="text-muted text-xs font-mono">{row.userId}</span>,
    },
    {
      header: 'Bank',
      render: (row) => <span className="text-ink">{row.bankName ?? '—'}</span>,
    },
    { header: 'Created At', render: (row) => formatWhen(row.createdAt) },
    {
      header: 'Reliability',
      render: (row) => <span className="text-ink text-xs">{row.reliabilityStatus ?? '—'}</span>,
    },
    {
      header: 'Trigger',
      // The sentence, not the numbers -- the numbers are what the detail view is for.
      render: (row) => (
        <span className="text-muted text-xs" title={row.triggerSummary ?? undefined}>
          {row.triggerSummary
            ? row.triggerSummary.length > 60
              ? `${row.triggerSummary.slice(0, 60)}…`
              : row.triggerSummary
            : '—'}
        </span>
      ),
    },
    {
      header: 'Parser',
      render: (row) => <span className="text-muted text-xs font-mono">{row.parserVersion ?? '—'}</span>,
    },
    {
      header: 'Status',
      render: (row) => <span className="text-ink text-xs">{row.status.replace(/_/g, ' ')}</span>,
    },
    {
      header: 'Assigned',
      render: (row) => <span className="text-muted text-xs font-mono">{row.assignedEngineerId ?? '—'}</span>,
    },
  ];

  return (
    <div className="space-y-6">
      <p className="text-muted text-sm">
        Statements the pipeline held back because its own evidence contradicted the extraction --
        a count that does not match, a row the layout says should exist but was dropped, or a
        period that does not hold together. Nothing here is shown to the user beyond &quot;running
        additional checks&quot;.
      </p>

      <FilterBar
        fields={[
          {
            type: 'select', key: 'status', value: status,
            onChange: (v) => { setStatus(v as HeldStatementStatus | ''); setPage(0); },
            placeholder: 'All open statuses',
            label: 'Filter by status',
            options: STATUS_OPTIONS,
          },
          {
            type: 'search', key: 'bank', value: bankInput, onChange: setBankInput,
            placeholder: 'Bank name…',
          },
          {
            type: 'search', key: 'engineerId', value: engineerIdInput, onChange: setEngineerIdInput,
            placeholder: 'Assigned engineer id…',
          },
        ]}
        onApply={applyFilters}
        applyLabel="Filter"
        trailingActions={
          <label className="flex items-center gap-1.5 text-xs text-muted whitespace-nowrap">
            Older than
            <input
              type="number"
              min={0}
              value={olderThanHours}
              onChange={(e) => { setOlderThanHours(e.target.value); setPage(0); }}
              placeholder="hours"
              className="w-20 bg-card border border-border rounded-lg px-2 py-1.5 text-sm shadow-card"
            />
            hours
          </label>
        }
      />

      <DataTable
        columns={columns}
        rows={list.data?.content ?? []}
        keyFor={(row) => row.id}
        loading={list.isLoading}
        emptyMessage="Nothing is held for trust review. Every import either completed, failed for a recognised reason, or is waiting for a parser fix elsewhere."
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

export default function HeldStatements() {
  return (
    <AdminLayout
      title="Held Statements"
      subtitle="Statements held for trust review -- extraction evidence that contradicted itself."
    >
      <RequirePermission permission="TRUST_REVIEW_MANAGE">
        <HeldStatementsContent />
      </RequirePermission>
    </AdminLayout>
  );
}
