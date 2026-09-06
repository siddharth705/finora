import { useParams, Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ArrowLeft, FileQuestion, Paperclip, Download } from 'lucide-react';
import { supportApi, type SupportTicketCategory, type SupportTicketStatus } from '../api/endpoints';
import { PageLoading } from '../components/PageLoading';
import { formatDate } from '../utils/date';

const CATEGORY_LABELS: Record<SupportTicketCategory, string> = {
  STATEMENT_IMPORT: 'Statement import',
  CATEGORIZATION: 'Categorization',
  ACCOUNT_LINKING: 'Account linking',
  DATA_ACCURACY: 'Data accuracy',
  TECHNICAL_ISSUE: 'Technical issue',
  OTHER: 'Other',
};

const STATUS_STYLE: Record<SupportTicketStatus, { label: string; className: string }> = {
  OPEN: { label: 'Open', className: 'text-primary bg-primary-light' },
  IN_PROGRESS: { label: 'In Progress', className: 'text-warning bg-warning-bg' },
  RESOLVED: { label: 'Resolved', className: 'text-success bg-success-bg' },
  CLOSED: { label: 'Closed', className: 'text-muted bg-bg border border-border' },
};

function bytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

/** Support, Help & Feedback v1, Phase 8. One ticket's detail -- SupportTicketController.detail()
 *  serves both a user viewing their own ticket and an admin holding SUPPORT_MANAGE (see that
 *  controller's own doc), but this page is the user-facing render of it: no claim/status-change
 *  controls, no internal-notes panel -- SupportTicketDto.Detail carries no note content at all,
 *  so there is nothing here to accidentally expose. AdminSupportTicketDetail (Phase 9) is the
 *  admin-side render of the same data plus those controls. */
export default function SupportTicketDetail() {
  const { ticketId } = useParams<{ ticketId: string }>();

  const ticketQuery = useQuery({
    queryKey: ['support-ticket-detail', ticketId],
    queryFn: () => supportApi.detail(ticketId as string),
    enabled: !!ticketId,
    // A 404 here means "not yours, or doesn't exist" -- SupportTicketService.getDetail answers
    // both identically for a non-admin caller, same reasoning ImportDetail.tsx's identical query
    // already applies to a different owned resource.
    retry: false,
  });

  if (!ticketId) return null;

  if (ticketQuery.isLoading) {
    return <PageLoading />;
  }

  if (!ticketQuery.data) {
    return (
      <div className="bg-card rounded-xl2 shadow-card border border-border p-10 text-center">
        <FileQuestion size={28} className="mx-auto text-muted" />
        <p className="text-sm font-medium text-ink mt-3">Ticket not found</p>
        <p className="text-xs text-muted mt-1">This ticket doesn't exist, or isn't yours to view.</p>
        <Link to="/app/support" className="mt-4 inline-block text-xs font-medium text-primary hover:underline">
          Back to My Tickets
        </Link>
      </div>
    );
  }

  const t = ticketQuery.data;
  const status = STATUS_STYLE[t.status];

  return (
    <div className="space-y-4 max-w-2xl">
      <Link to="/app/support" className="text-xs text-muted hover:text-ink inline-flex items-center gap-1">
        <ArrowLeft size={12} /> Back to My Tickets
      </Link>

      <div className="bg-card rounded-xl2 shadow-card border border-border p-6">
        <div className="flex items-start justify-between gap-4 flex-wrap">
          <div className="min-w-0">
            <p className="text-xs font-mono text-muted mb-1">{t.ticketNumber} · {CATEGORY_LABELS[t.category]}</p>
            <h1 className="text-lg font-bold text-ink">{t.subject}</h1>
            <p className="text-sm text-muted mt-0.5">Opened {formatDate(t.createdAt)}</p>
          </div>
          <span className={`text-[10px] uppercase font-semibold rounded px-2 py-1 flex-shrink-0 ${status.className}`}>
            {status.label}
          </span>
        </div>

        <p className="text-sm text-ink whitespace-pre-wrap mt-4 leading-relaxed">{t.description}</p>

        {t.attachments.length > 0 && (
          <div className="mt-4 space-y-1.5">
            {t.attachments.map((a) => (
              <button
                key={a.id}
                type="button"
                onClick={() => void supportApi.downloadAttachment(t.id, a.id, a.filename)}
                className="flex items-center gap-2 text-xs text-primary hover:underline"
              >
                <Paperclip size={12} className="flex-shrink-0" />
                {a.filename}
                <span className="text-muted">({bytes(a.sizeBytes)})</span>
                <Download size={12} className="flex-shrink-0" />
              </button>
            ))}
          </div>
        )}

        {(t.status === 'RESOLVED' || t.status === 'CLOSED') && (
          <p className="text-xs text-muted mt-4 pt-4 border-t border-border">
            This ticket is {t.status === 'RESOLVED' ? 'resolved' : 'closed'} and can't be reopened —
            file a new ticket if the issue comes back.
          </p>
        )}
      </div>
    </div>
  );
}
