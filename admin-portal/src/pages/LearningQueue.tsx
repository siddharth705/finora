import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ListRestart, RefreshCw, CheckCircle2, AlertTriangle } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { adminLearningQueueApi } from '../api/endpoints';
import { formatWhen } from '../lib/formatWhen';
import type { LearningQueueEvent } from '../types';

/**
 * The merchant learning queue's operator surface (WI2).
 *
 * Built against one requirement: an operator must be able to answer every question about a failed
 * event from this page alone — what failed, why, for which user, from which statement and session,
 * how many retries have happened, when the next one is, and whether they can force one — without
 * opening a database client. That requirement is what decides the layout: correlation is shown as
 * NAMES with ids available, not as four UUIDs, because a page full of UUIDs sends the operator to
 * the database anyway and fails at the only thing it was built for.
 *
 * `retryable` comes from the server rather than being re-derived here from `status`. The rule
 * ("only FAILED can be retried by hand") belongs to the backend's state machine, and a client that
 * re-implements it drifts the first time that machine changes — offering a Retry button the API
 * then refuses, which is worse than offering none.
 */

const STATUSES = ['FAILED', 'PENDING', 'PROCESSING', 'COMPLETED', 'RESOLVED'] as const;

function statusTone(status: LearningQueueEvent['status']) {
  switch (status) {
    case 'FAILED':
      return 'bg-red-500/10 text-red-400 border-red-500/20';
    case 'PENDING':
      return 'bg-amber-500/10 text-amber-400 border-amber-500/20';
    case 'PROCESSING':
      return 'bg-blue-500/10 text-blue-400 border-blue-500/20';
    case 'RESOLVED':
      return 'bg-slate-500/10 text-slate-400 border-slate-500/20';
    default:
      return 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20';
  }
}

function LearningQueueContent() {
  const [status, setStatus] = useState<string>('FAILED');
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<LearningQueueEvent | null>(null);
  const queryClient = useQueryClient();

  const summary = useQuery({
    queryKey: ['learning-queue-summary'],
    queryFn: () => adminLearningQueueApi.summary(),
  });

  const queue = useQuery({
    queryKey: ['learning-queue', status, page],
    queryFn: () => adminLearningQueueApi.list({ status: status || undefined, page, size: 25 }),
  });

  const refreshAll = () => {
    void queryClient.invalidateQueries({ queryKey: ['learning-queue'] });
    void queryClient.invalidateQueries({ queryKey: ['learning-queue-summary'] });
  };

  const retry = useMutation({
    mutationFn: (eventId: string) => adminLearningQueueApi.retry(eventId),
    onSuccess: (updated) => {
      setSelected(updated);
      refreshAll();
    },
  });

  const retryAll = useMutation({
    mutationFn: () => adminLearningQueueApi.retryAll(),
    onSuccess: refreshAll,
  });

  const resolve = useMutation({
    mutationFn: (eventId: string) => adminLearningQueueApi.resolve(eventId),
    onSuccess: (updated) => {
      setSelected(updated);
      refreshAll();
    },
  });

  const columns: DataTableColumn<LearningQueueEvent>[] = [
    {
      header: 'Status',
      render: (row) => (
        <span className={`inline-block rounded-full border px-2 py-0.5 text-xs ${statusTone(row.status)}`}>
          {row.status}
        </span>
      ),
    },
    {
      header: 'What failed',
      // Name first, id underneath: the name is what an operator recognises, the id is what they
      // paste into a support ticket.
      render: (row) => (
        <div>
          <p className="text-ink">{row.merchantName ?? <span className="text-muted italic">merchant deleted</span>}</p>
          <p className="text-muted text-xs">→ {row.categoryName ?? 'category deleted'}</p>
        </div>
      ),
    },
    {
      header: 'Affected user',
      render: (row) => (
        <div>
          <p className="text-ink">{row.userEmail ?? '—'}</p>
          <p className="text-muted text-xs font-mono">{row.userId.slice(0, 8)}…</p>
        </div>
      ),
    },
    {
      header: 'Retries',
      render: (row) => (
        <span className="text-ink">
          {row.attemptCount}/{row.maxAttempts}
        </span>
      ),
    },
    { header: 'Next retry', render: (row) => formatWhen(row.nextAttemptAt) },
    {
      header: '',
      render: (row) => (
        <div className="flex gap-2">
          <button
            className="text-xs text-accent hover:underline"
            onClick={() => setSelected(row)}
          >
            Details
          </button>
          {row.retryable && (
            <button
              className="text-xs text-accent hover:underline disabled:opacity-50"
              disabled={retry.isPending}
              onClick={() => retry.mutate(row.id)}
            >
              Retry
            </button>
          )}
        </div>
      ),
    },
  ];

  const counts = summary.data;

  return (
    <div className="space-y-6">
      <div className="flex items-start justify-between gap-4">
        <p className="text-muted text-sm">
          Failed confirmations are safe to leave here: the transactions they came from are already
          imported. Retrying applies the learning the engine missed.
        </p>
        <button
          className="rounded-lg border border-border px-3 py-2 text-sm text-ink hover:bg-card disabled:opacity-50"
          disabled={retryAll.isPending || (counts?.failed ?? 0) === 0}
          onClick={() => retryAll.mutate()}
        >
          <RefreshCw className="inline h-4 w-4 mr-1" />
          Retry all failed
        </button>
      </div>

      {retryAll.isSuccess && (
        <p className="text-sm text-emerald-400">
          {retryAll.data.retried} event(s) queued for retry.
        </p>
      )}

      <div className="flex flex-wrap gap-2">
        {STATUSES.map((s) => {
          const count = counts ? counts[s.toLowerCase() as keyof typeof counts] : undefined;
          return (
            <button
              key={s}
              onClick={() => {
                setStatus(s);
                setPage(0);
              }}
              className={`rounded-full border px-3 py-1 text-xs ${
                status === s ? statusTone(s) : 'border-border text-muted hover:text-ink'
              }`}
            >
              {s}
              {count !== undefined && <span className="ml-1 opacity-70">{count}</span>}
            </button>
          );
        })}
        <button
          onClick={() => {
            setStatus('');
            setPage(0);
          }}
          className={`rounded-full border px-3 py-1 text-xs ${
            status === '' ? 'bg-card text-ink border-border' : 'border-border text-muted hover:text-ink'
          }`}
        >
          All
        </button>
      </div>

      <DataTable
        columns={columns}
        rows={queue.data?.content ?? []}
        keyFor={(row) => row.id}
        loading={queue.isLoading}
        emptyMessage={
          status === 'FAILED'
            ? 'Nothing has failed. The queue is healthy.'
            : 'No events with this status.'
        }
      />

      {queue.data && queue.data.totalPages > 1 && (
        <div className="flex items-center justify-between text-sm text-muted">
          <span>
            Page {queue.data.page + 1} of {queue.data.totalPages} ({queue.data.totalElements} events)
          </span>
          <div className="flex gap-2">
            <button
              className="rounded border border-border px-2 py-1 disabled:opacity-40"
              disabled={page === 0}
              onClick={() => setPage((p) => p - 1)}
            >
              Previous
            </button>
            <button
              className="rounded border border-border px-2 py-1 disabled:opacity-40"
              disabled={page + 1 >= queue.data.totalPages}
              onClick={() => setPage((p) => p + 1)}
            >
              Next
            </button>
          </div>
        </div>
      )}

      {selected && (
        <EventDetail
          event={selected}
          onClose={() => setSelected(null)}
          onRetry={() => retry.mutate(selected.id)}
          onResolve={() => resolve.mutate(selected.id)}
          busy={retry.isPending || resolve.isPending}
        />
      )}
    </div>
  );
}

/**
 * The detail panel.
 *
 * Everything here is correlation — the questions an operator asks after "what failed": whose
 * account, which statement, which session, how long has it been broken. Ids are rendered in full
 * and monospaced so they can be copied into a ticket or a query, with the human-readable name
 * above them.
 */
function EventDetail({
  event,
  onClose,
  onRetry,
  onResolve,
  busy,
}: {
  event: LearningQueueEvent;
  onClose: () => void;
  onRetry: () => void;
  onResolve: () => void;
  busy: boolean;
}) {
  return (
    <div className="bg-card border border-border rounded-xl2 p-6 space-y-5">
      <div className="flex items-start justify-between">
        <div>
          <h2 className="text-lg font-semibold text-ink">Event detail</h2>
          <p className="text-muted text-xs font-mono mt-1">{event.id}</p>
        </div>
        <button className="text-muted hover:text-ink text-sm" onClick={onClose}>
          Close
        </button>
      </div>

      {event.lastError && (
        <div className="rounded-lg border border-red-500/20 bg-red-500/5 p-3">
          <p className="text-xs text-red-400 font-medium flex items-center gap-1">
            <AlertTriangle className="h-3.5 w-3.5" /> Last error
          </p>
          <p className="text-sm text-ink mt-1 font-mono break-words">{event.lastError}</p>
        </div>
      )}

      <dl className="grid grid-cols-1 sm:grid-cols-2 gap-4 text-sm">
        <Field label="Merchant" value={event.merchantName} id={event.merchantId} />
        <Field label="Category" value={event.categoryName} id={event.categoryId} />
        <Field label="Affected user" value={event.userEmail} id={event.userId} href={`/users/${event.userId}`} />
        <Field
          label="Statement"
          value={event.statementFileName}
          id={event.statementImportId}
          // The originating import lives on the user's own detail page -- the only place in the
          // admin portal that renders a specific user's statement imports.
          href={event.statementImportId ? `/users/${event.userId}` : undefined}
        />
        <Field
          label="Import session"
          // Never a placeholder: an import that went through the direct-file confirm path genuinely
          // had no session, and saying so is more useful than an id that resolves to nothing.
          value={event.importSessionId ? 'Session-based import' : 'No session (direct file import)'}
          id={event.importSessionId}
        />
        <div>
          <dt className="text-muted text-xs">Retries</dt>
          <dd className="text-ink">
            {event.attemptCount} of {event.maxAttempts}
          </dd>
        </div>
        <div>
          <dt className="text-muted text-xs">First failed</dt>
          <dd className="text-ink">{formatWhen(event.firstFailedAt)}</dd>
        </div>
        <div>
          <dt className="text-muted text-xs">Last retry</dt>
          <dd className="text-ink">{formatWhen(event.lastRetryAt)}</dd>
        </div>
        <div>
          <dt className="text-muted text-xs">Next retry</dt>
          <dd className="text-ink">{formatWhen(event.nextAttemptAt)}</dd>
        </div>
        <div>
          <dt className="text-muted text-xs">Queued</dt>
          <dd className="text-ink">{formatWhen(event.createdAt)}</dd>
        </div>
      </dl>

      {event.retryable && (
        <div className="flex gap-2 pt-2 border-t border-border">
          <button
            className="rounded-lg bg-accent px-3 py-2 text-sm text-white disabled:opacity-50"
            disabled={busy}
            onClick={onRetry}
          >
            <RefreshCw className="inline h-4 w-4 mr-1" /> Retry now
          </button>
          <button
            className="rounded-lg border border-border px-3 py-2 text-sm text-ink disabled:opacity-50"
            disabled={busy}
            onClick={onResolve}
          >
            <CheckCircle2 className="inline h-4 w-4 mr-1" /> Mark resolved
          </button>
          <p className="text-muted text-xs self-center">
            Resolving closes this without applying the learning.
          </p>
        </div>
      )}
    </div>
  );
}

function Field({
  label,
  value,
  id,
  href,
}: {
  label: string;
  value: string | null;
  id: string | null;
  href?: string;
}) {
  return (
    <div>
      <dt className="text-muted text-xs">{label}</dt>
      <dd className="text-ink">
        {href && value ? (
          <a className="text-accent hover:underline" href={href}>
            {value}
          </a>
        ) : (
          value ?? <span className="text-muted italic">deleted</span>
        )}
      </dd>
      {id && <p className="text-muted text-[11px] font-mono break-all">{id}</p>}
    </div>
  );
}

export default function LearningQueue() {
  return (
    <AdminLayout
      title="Learning Queue"
      subtitle="Merchant-learning confirmations that could not be applied. Nothing here ever blocked an import."
    >
      <RequirePermission permission="LEARNING_QUEUE_MANAGE">
        <LearningQueueContent />
      </RequirePermission>
    </AdminLayout>
  );
}
