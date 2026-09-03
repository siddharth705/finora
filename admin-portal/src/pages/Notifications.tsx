import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Send, XCircle } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { Pagination } from '../components/Pagination';
import { adminNotificationApi } from '../api/endpoints';
import { formatWhen } from '../lib/formatWhen';
import type { NotificationAdminRow, NotificationAdminDetail } from '../types';

const PAGE_SIZE = 25;

/**
 * The admin notification dashboard (Task 12).
 *
 * Read-only, deliberately: the proposal (section 2.5/4) scopes this to a list plus basic
 * send-outcome counts, nothing richer -- no trend charts, no engagement scoring, and no
 * retry/resend action, because `NotificationDispatcher` (Task 3) is the only thing in this
 * codebase allowed to move a notification's status. There is no mutation button anywhere on this
 * page for the same reason LearningQueue.tsx has none for a RESOLVED event: there is nothing the
 * backend would accept.
 *
 * Unlike LearningQueue.tsx, a row here shows `userId` as a bare id, not a name or an email.
 * That is a deliberate difference from this page's own "show names not UUIDs" precedent, not an
 * oversight of it: AdminNotificationController never joins to a user's contact details, so an
 * email or phone number can never reach this screen even indirectly -- see its own doc comment.
 * `status` and every other field are rendered exactly as the server returns them; nothing here
 * re-derives a rule the backend already owns.
 */

const STATUSES = ['DEAD_LETTER', 'RETRYING', 'SENT', 'PROCESSING', 'QUEUED', 'CREATED'] as const;

function statusTone(status: NotificationAdminRow['status']) {
  switch (status) {
    case 'DEAD_LETTER':
      return 'bg-red-500/10 text-red-400 border-red-500/20';
    case 'RETRYING':
      return 'bg-amber-500/10 text-amber-400 border-amber-500/20';
    case 'PROCESSING':
      return 'bg-blue-500/10 text-blue-400 border-blue-500/20';
    case 'SENT':
      return 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20';
    default:
      return 'bg-slate-500/10 text-slate-400 border-slate-500/20';
  }
}

function NotificationsContent() {
  const [status, setStatus] = useState<string>('DEAD_LETTER');
  const [page, setPage] = useState(0);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const summary = useQuery({
    queryKey: ['notification-admin-summary'],
    queryFn: () => adminNotificationApi.summary(),
  });

  const list = useQuery({
    queryKey: ['notification-admin-list', status, page],
    queryFn: () => adminNotificationApi.list({ status: status || undefined, page, size: PAGE_SIZE }),
  });

  const detail = useQuery({
    queryKey: ['notification-admin-detail', selectedId],
    queryFn: () => adminNotificationApi.get(selectedId as string),
    enabled: selectedId !== null,
  });

  const columns: DataTableColumn<NotificationAdminRow>[] = [
    {
      header: 'Status',
      render: (row) => (
        <span className={`inline-block rounded-full border px-2 py-0.5 text-xs ${statusTone(row.status)}`}>
          {row.status}
        </span>
      ),
    },
    {
      header: 'Notification',
      render: (row) => (
        <div>
          <p className="text-ink">{row.title}</p>
          <p className="text-muted text-xs">{row.type}</p>
        </div>
      ),
    },
    { header: 'Channel', render: (row) => <span className="text-ink">{row.channel}</span> },
    {
      header: 'Recipient',
      // No email/phone is ever available here -- see the page's own doc comment. The id is what
      // an operator has to correlate against a user record, so it is shown in full and monospaced
      // rather than hidden behind "Details" only.
      render: (row) => <span className="text-muted text-xs font-mono">{row.userId}</span>,
    },
    { header: 'Attempts', render: (row) => <span className="text-ink">{row.attemptCount}</span> },
    { header: 'Sent', render: (row) => formatWhen(row.sentAt) },
    {
      header: '',
      render: (row) => (
        <button className="text-xs text-accent hover:underline" onClick={() => setSelectedId(row.id)}>
          Details
        </button>
      ),
    },
  ];

  const counts = summary.data;

  return (
    <div className="space-y-6">
      <p className="text-muted text-sm">
        Every email, SMS and push notification Fynora has queued for delivery, and what happened
        when it was sent.
      </p>

      {counts && (
        <div className="grid grid-cols-2 sm:grid-cols-5 gap-3">
          <div className="bg-card border border-border rounded-xl2 p-4">
            <p className="text-muted text-xs flex items-center gap-1"><Send className="h-3.5 w-3.5" /> Sent</p>
            <p className="text-2xl font-semibold text-ink mt-1">{counts.sent}</p>
          </div>
          <div className="bg-card border border-border rounded-xl2 p-4">
            <p className="text-muted text-xs flex items-center gap-1"><XCircle className="h-3.5 w-3.5" /> Failed</p>
            <p className="text-2xl font-semibold text-ink mt-1">{counts.failed}</p>
          </div>
          {counts.byChannel.map((c) => (
            <div key={c.channel} className="bg-card border border-border rounded-xl2 p-4">
              <p className="text-muted text-xs">{c.channel}</p>
              <p className="text-sm text-ink mt-1">
                <span className="text-emerald-400">{c.sent} sent</span>
                {' · '}
                <span className="text-red-400">{c.failed} failed</span>
              </p>
            </div>
          ))}
        </div>
      )}

      <div className="flex flex-wrap gap-2">
        {STATUSES.map((s) => (
          <button
            key={s}
            onClick={() => {
              setStatus(s);
              setPage(0);
            }}
            className={`rounded-full border px-3 py-1 text-xs ${
              status === s ? statusTone(s as NotificationAdminRow['status']) : 'border-border text-muted hover:text-ink'
            }`}
          >
            {s}
          </button>
        ))}
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
        rows={list.data?.content ?? []}
        keyFor={(row) => row.id}
        loading={list.isLoading}
        emptyMessage={
          status === 'DEAD_LETTER'
            ? 'Nothing has dead-lettered. Delivery is healthy.'
            : 'No notifications with this status.'
        }
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

      {selectedId && (
        <NotificationDetail
          detail={detail.data}
          loading={detail.isLoading}
          onClose={() => setSelectedId(null)}
        />
      )}
    </div>
  );
}

/**
 * The detail panel: the notification's own content plus every provider attempt against it,
 * newest first -- the server's own order (NotificationLogRepository
 * .findByNotificationIdOrderByTimestampDesc), never re-sorted here.
 */
function NotificationDetail({
  detail,
  loading,
  onClose,
}: {
  detail: NotificationAdminDetail | undefined;
  loading: boolean;
  onClose: () => void;
}) {
  return (
    <div className="bg-card border border-border rounded-xl2 p-6 space-y-5">
      <div className="flex items-start justify-between">
        <h2 className="text-lg font-semibold text-ink">Notification detail</h2>
        <button className="text-muted hover:text-ink text-sm" onClick={onClose}>
          Close
        </button>
      </div>

      {loading && <p className="text-muted text-sm">Loading…</p>}

      {detail && (
        <>
          <div className="rounded-lg border border-border bg-bg p-3">
            <p className="text-ink font-medium">{detail.title}</p>
            <p className="text-muted text-sm mt-1">{detail.message}</p>
          </div>

          {detail.lastError && (
            <div className="rounded-lg border border-red-500/20 bg-red-500/5 p-3">
              <p className="text-xs text-red-400 font-medium">Last error</p>
              <p className="text-sm text-ink mt-1 font-mono break-words">{detail.lastError}</p>
            </div>
          )}

          <dl className="grid grid-cols-1 sm:grid-cols-2 gap-4 text-sm">
            <div>
              <dt className="text-muted text-xs">Recipient</dt>
              <dd className="text-ink text-xs font-mono break-all">{detail.userId}</dd>
            </div>
            <div>
              <dt className="text-muted text-xs">Channel / Priority</dt>
              <dd className="text-ink">{detail.channel} · {detail.priority}</dd>
            </div>
            <div>
              <dt className="text-muted text-xs">Attempts</dt>
              <dd className="text-ink">{detail.attemptCount}</dd>
            </div>
            <div>
              <dt className="text-muted text-xs">Next attempt</dt>
              <dd className="text-ink">{formatWhen(detail.nextAttemptAt)}</dd>
            </div>
            <div>
              <dt className="text-muted text-xs">Sent</dt>
              <dd className="text-ink">{formatWhen(detail.sentAt)}</dd>
            </div>
            <div>
              <dt className="text-muted text-xs">Queued</dt>
              <dd className="text-ink">{formatWhen(detail.createdAt)}</dd>
            </div>
          </dl>

          <div>
            <p className="text-muted text-xs mb-2">Attempt log</p>
            {detail.attempts.length === 0 ? (
              <p className="text-muted text-sm italic">No delivery attempts recorded yet.</p>
            ) : (
              <div className="space-y-2">
                {detail.attempts.map((a) => (
                  <div key={a.id} className="rounded-lg border border-border p-3 text-sm">
                    <div className="flex items-center justify-between">
                      <span className="text-ink">
                        {a.provider} · attempt {a.attempt}
                      </span>
                      <span className={a.success ? 'text-emerald-400' : 'text-red-400'}>
                        {a.success ? 'OK' : 'Failed'}
                      </span>
                    </div>
                    {a.response && <p className="text-muted text-xs font-mono mt-1 break-words">{a.response}</p>}
                    <p className="text-muted text-xs mt-1">{formatWhen(a.timestamp)}</p>
                  </div>
                ))}
              </div>
            )}
          </div>
        </>
      )}
    </div>
  );
}

export default function Notifications() {
  return (
    <AdminLayout
      title="Notifications"
      subtitle="Delivery outcomes for every email, SMS and push notification Fynora has queued."
    >
      <RequirePermission permission="NOTIFICATION_MANAGE">
        <NotificationsContent />
      </RequirePermission>
    </AdminLayout>
  );
}
