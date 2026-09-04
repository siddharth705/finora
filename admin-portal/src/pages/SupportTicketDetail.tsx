import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Download, Paperclip } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { adminSupportTicketApi } from '../api/endpoints';
import { formatWhen } from '../lib/formatWhen';
import type { SupportTicketCategory, SupportTicketStatus } from '../types';

const CATEGORY_LABELS: Record<SupportTicketCategory, string> = {
  STATEMENT_IMPORT: 'Statement import',
  CATEGORIZATION: 'Categorization',
  ACCOUNT_LINKING: 'Account linking',
  DATA_ACCURACY: 'Data accuracy',
  TECHNICAL_ISSUE: 'Technical issue',
  OTHER: 'Other',
};

/** Mirrors SupportTicket.Status.canTransitionTo on the backend -- shown here only so an admin is
 *  never offered an illegal move in the dropdown; the backend's own check is still what actually
 *  enforces it (D6: a resolved ticket cannot be reopened, D7: OPEN can close in one step). */
const LEGAL_NEXT_STATUSES: Record<SupportTicketStatus, SupportTicketStatus[]> = {
  OPEN: ['IN_PROGRESS', 'RESOLVED', 'CLOSED'],
  IN_PROGRESS: ['RESOLVED', 'CLOSED'],
  RESOLVED: [],
  CLOSED: [],
};

function onActionErrorMessage(error: unknown): string {
  const response = (error as { response?: { data?: { message?: string } } })?.response;
  return response?.data?.message ?? 'That action could not be completed.';
}

function bytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

function SupportTicketDetailContent({ id }: { id: string }) {
  const queryClient = useQueryClient();
  const [actionError, setActionError] = useState<string | null>(null);
  const [noteDraft, setNoteDraft] = useState('');
  const [confirmingTakeover, setConfirmingTakeover] = useState(false);

  const detail = useQuery({
    queryKey: ['support-ticket-detail-admin', id],
    queryFn: () => adminSupportTicketApi.get(id),
  });
  const notes = useQuery({
    queryKey: ['support-ticket-notes', id],
    queryFn: () => adminSupportTicketApi.notes(id),
  });

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ['support-ticket-detail-admin', id] });
    void queryClient.invalidateQueries({ queryKey: ['support-tickets-list'] });
  }

  function onError(error: unknown) {
    setActionError(onActionErrorMessage(error));
  }

  const updateStatus = useMutation({
    mutationFn: (status: SupportTicketStatus) => adminSupportTicketApi.updateStatus(id, status),
    onSuccess: () => { setActionError(null); invalidate(); },
    onError,
  });
  const claim = useMutation({
    mutationFn: () => adminSupportTicketApi.claim(id),
    onSuccess: () => { setActionError(null); setConfirmingTakeover(false); invalidate(); },
    onError: (error) => { setConfirmingTakeover(false); onError(error); },
  });
  const unclaim = useMutation({
    mutationFn: () => adminSupportTicketApi.unclaim(id),
    onSuccess: () => { setActionError(null); invalidate(); },
    onError,
  });
  const addNote = useMutation({
    mutationFn: (note: string) => adminSupportTicketApi.addNote(id, note),
    onSuccess: () => {
      setActionError(null);
      setNoteDraft('');
      void queryClient.invalidateQueries({ queryKey: ['support-ticket-notes', id] });
    },
    onError,
  });
  const download = useMutation({
    mutationFn: ({ attachmentId, filename }: { attachmentId: string; filename: string }) =>
      adminSupportTicketApi.downloadAttachment(id, attachmentId, filename),
    onError,
  });

  if (detail.isLoading) {
    return <p className="text-muted text-sm">Loading…</p>;
  }
  if (detail.isError || !detail.data) {
    return <p className="text-muted text-sm">No such support ticket.</p>;
  }

  const ticket = detail.data;
  const nextStatuses = LEGAL_NEXT_STATUSES[ticket.status];

  return (
    <div className="space-y-6">
      <Link to="/support-tickets" className="text-xs text-accent hover:underline">
        &larr; Back to queue
      </Link>

      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-ink font-mono">{ticket.ticketNumber}</h2>
          <p className="text-ink text-sm mt-1">{ticket.subject}</p>
          <p className="text-muted text-xs mt-1">
            {CATEGORY_LABELS[ticket.category]} · Opened {formatWhen(ticket.createdAt)}
          </p>
        </div>
        <span className="text-xs font-medium text-ink rounded-lg border border-border px-2.5 py-1">
          {ticket.status.replace(/_/g, ' ')}
        </span>
      </div>

      {actionError && (
        <div className="rounded-lg border border-red-500/20 bg-red-500/5 p-3">
          <p className="text-sm text-red-400">{actionError}</p>
        </div>
      )}

      {/* Description + attachments */}
      <section className="bg-card border border-border rounded-xl2 p-6 space-y-4">
        <h3 className="text-sm font-semibold text-ink">Description</h3>
        <p className="text-sm text-ink whitespace-pre-wrap">{ticket.description}</p>

        {ticket.attachments.length > 0 && (
          <div className="space-y-1.5 border-t border-border pt-3">
            {ticket.attachments.map((a) => (
              <button
                key={a.id}
                type="button"
                onClick={() => download.mutate({ attachmentId: a.id, filename: a.filename })}
                disabled={download.isPending}
                className="flex items-center gap-2 text-xs text-accent hover:underline disabled:opacity-50"
              >
                <Paperclip className="h-3.5 w-3.5" />
                {a.filename}
                <span className="text-muted">({bytes(a.sizeBytes)})</span>
                <Download className="h-3.5 w-3.5" />
              </button>
            ))}
          </div>
        )}
      </section>

      {/* Status */}
      <section className="bg-card border border-border rounded-xl2 p-6 space-y-3">
        <h3 className="text-sm font-semibold text-ink">Status</h3>
        {nextStatuses.length === 0 ? (
          <p className="text-xs text-muted">
            {ticket.status === 'RESOLVED' ? 'Resolved' : 'Closed'} tickets can&apos;t change status
            again -- the customer would file a new ticket instead.
          </p>
        ) : (
          <div className="flex flex-wrap gap-2">
            {nextStatuses.map((next) => (
              <button
                key={next}
                type="button"
                onClick={() => updateStatus.mutate(next)}
                disabled={updateStatus.isPending}
                className="rounded-lg border border-border px-3 py-1.5 text-xs text-muted hover:text-ink disabled:opacity-50"
              >
                Move to {next.replace(/_/g, ' ')}
              </button>
            ))}
          </div>
        )}
      </section>

      {/* Claim */}
      <section className="bg-card border border-border rounded-xl2 p-6 space-y-3">
        <h3 className="text-sm font-semibold text-ink">Claim</h3>
        <p className="text-xs text-muted">
          Claimed by: <span className="font-mono">{ticket.claimedByAdminId ?? '— unclaimed —'}</span>
        </p>
        <div className="flex flex-wrap gap-2">
          {!ticket.claimedByAdminId ? (
            <button
              type="button"
              onClick={() => claim.mutate()}
              disabled={claim.isPending}
              className="rounded-lg bg-accent px-3 py-1.5 text-xs font-medium text-white disabled:opacity-50"
            >
              Claim
            </button>
          ) : (
            <button
              type="button"
              onClick={() => setConfirmingTakeover(true)}
              disabled={claim.isPending}
              className="rounded-lg border border-border px-3 py-1.5 text-xs text-muted hover:text-ink disabled:opacity-50"
            >
              Take over
            </button>
          )}
          {ticket.claimedByAdminId && (
            <button
              type="button"
              onClick={() => unclaim.mutate()}
              disabled={unclaim.isPending}
              className="rounded-lg border border-border px-3 py-1.5 text-xs text-muted hover:text-ink disabled:opacity-50"
            >
              Unclaim
            </button>
          )}
        </div>
      </section>

      {/* Internal notes */}
      <section className="bg-card border border-border rounded-xl2 p-6 space-y-4">
        <h3 className="text-sm font-semibold text-ink">Internal notes</h3>
        <p className="text-xs text-muted">Visible to admins only -- never shown to the customer.</p>

        <ul className="space-y-2">
          {(notes.data ?? []).map((note) => (
            <li key={note.id} className="text-xs border-l-2 border-border pl-3">
              <span className="text-ink font-mono">{note.adminId}</span>{' '}
              <span className="text-muted">{formatWhen(note.createdAt)}</span>
              <p className="text-ink mt-0.5 whitespace-pre-wrap">{note.note}</p>
            </li>
          ))}
          {notes.data?.length === 0 && <p className="text-muted text-xs">No internal notes yet.</p>}
        </ul>

        <div className="space-y-2 border-t border-border pt-4">
          <textarea
            value={noteDraft}
            onChange={(e) => setNoteDraft(e.target.value)}
            rows={2}
            placeholder="Add a note visible to other admins…"
            className="w-full rounded-lg border border-border bg-bg px-3 py-1.5 text-sm text-ink"
          />
          <button
            type="button"
            onClick={() => addNote.mutate(noteDraft.trim())}
            disabled={addNote.isPending || noteDraft.trim().length === 0}
            className="rounded-lg border border-border px-3 py-1.5 text-xs text-muted hover:text-ink disabled:opacity-50"
          >
            {addNote.isPending ? 'Adding…' : 'Add note'}
          </button>
        </div>
      </section>

      {confirmingTakeover && (
        <ConfirmDialog
          title="Take over this ticket?"
          message={`This ticket is claimed by ${ticket.claimedByAdminId}. Taking it over reassigns it to you -- they won't be notified automatically.`}
          confirmLabel="Take over"
          busy={claim.isPending}
          onConfirm={() => claim.mutate()}
          onCancel={() => setConfirmingTakeover(false)}
        />
      )}
    </div>
  );
}

export default function SupportTicketDetail() {
  const { id } = useParams<{ id: string }>();
  return (
    <AdminLayout title="Support Ticket" subtitle="Description, attachments, status, claim, and internal notes.">
      <RequirePermission permission="SUPPORT_MANAGE">
        {id ? <SupportTicketDetailContent id={id} /> : <p className="text-muted text-sm">No ticket id.</p>}
      </RequirePermission>
    </AdminLayout>
  );
}
