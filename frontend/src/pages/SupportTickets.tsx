import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { LifeBuoy, Plus } from 'lucide-react';
import { supportApi, type SupportTicketCategory, type SupportTicketStatus } from '../api/endpoints';
import { NewTicketModal } from '../components/NewTicketModal';
import { PageLoading } from '../components/PageLoading';
import { EmptyState, Badge } from '../design-system';
import { formatDate } from '../utils/date';

const CATEGORY_LABELS: Record<SupportTicketCategory, string> = {
  STATEMENT_IMPORT: 'Statement import',
  CATEGORIZATION: 'Categorization',
  ACCOUNT_LINKING: 'Account linking',
  DATA_ACCURACY: 'Data accuracy',
  TECHNICAL_ISSUE: 'Technical issue',
  OTHER: 'Other',
};

// bg/text pairs, not a Badge `tone` — Badge only ships "primary"/"neutral" today and this needs
// four visually distinct states (open vs. resolved has to read at a glance in a scanned list).
const STATUS_STYLE: Record<SupportTicketStatus, { label: string; className: string }> = {
  OPEN: { label: 'Open', className: 'text-primary bg-primary-light' },
  IN_PROGRESS: { label: 'In Progress', className: 'text-warning bg-warning-bg' },
  RESOLVED: { label: 'Resolved', className: 'text-success bg-success-bg' },
  CLOSED: { label: 'Closed', className: 'text-muted bg-bg border border-border' },
};

/** Support, Help & Feedback v1, Phase 8. "My Tickets" -- every ticket this user has filed, newest
 *  first (matches SupportTicketRepository.findByUserIdOrderByCreatedAtDesc), with a New Ticket
 *  entry point. Ticket creation lives here rather than on the public /contact page -- see
 *  NewTicketModal's own doc for why. */
export default function SupportTickets() {
  const navigate = useNavigate();
  const [showNew, setShowNew] = useState(false);

  const ticketsQuery = useQuery({
    queryKey: ['support-tickets-mine'],
    queryFn: () => supportApi.list(0, 50),
  });

  const tickets = ticketsQuery.data?.content ?? [];

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-4 flex-wrap">
        <div>
          <h1 className="text-xl font-bold text-ink">My Tickets</h1>
          <p className="text-sm text-muted">Support requests you've filed with Fynora.</p>
        </div>
        <button
          type="button"
          onClick={() => setShowNew(true)}
          className="flex items-center gap-1.5 bg-primary text-on-primary hover:bg-primary-dark rounded-lg px-4 py-2.5 text-sm font-semibold flex-shrink-0"
        >
          <Plus size={16} /> New Ticket
        </button>
      </div>

      {ticketsQuery.isLoading ? (
        <PageLoading />
      ) : tickets.length === 0 ? (
        <div className="bg-card rounded-xl2 shadow-card border border-border p-8">
          <EmptyState
            icon={LifeBuoy}
            iconBg="bg-blue-100"
            iconColor="text-blue-600"
            title="No support tickets yet"
            desc="Run into a problem? File a ticket and we'll take a look."
          />
        </div>
      ) : (
        <div className="bg-card rounded-xl2 shadow-card border border-border overflow-hidden">
          <div className="divide-y divide-border">
            {tickets.map((t) => (
              <button
                key={t.id}
                type="button"
                onClick={() => void navigate(`/app/support/${t.id}`)}
                className="w-full text-left px-5 py-3.5 flex items-center justify-between gap-4 flex-wrap hover:bg-bg"
              >
                <div className="min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="text-xs font-mono text-muted">{t.ticketNumber}</span>
                    <Badge tone="neutral" label={CATEGORY_LABELS[t.category]} />
                  </div>
                  <p className="text-sm font-medium text-ink truncate mt-0.5">{t.subject}</p>
                  <p className="text-xs text-muted mt-0.5">
                    Opened {formatDate(t.createdAt)} · Updated {formatDate(t.updatedAt)}
                  </p>
                </div>
                <span className={`text-[10px] uppercase font-semibold rounded px-2 py-1 flex-shrink-0 ${STATUS_STYLE[t.status].className}`}>
                  {STATUS_STYLE[t.status].label}
                </span>
              </button>
            ))}
          </div>
        </div>
      )}

      <p className="text-xs text-muted">
        Looking for general answers instead? Visit the{' '}
        <Link to="/help" className="text-primary hover:underline">Help Center</Link>.
      </p>

      {showNew && (
        <NewTicketModal
          onClose={() => setShowNew(false)}
          onCreated={(ticket) => {
            setShowNew(false);
            void ticketsQuery.refetch();
            void navigate(`/app/support/${ticket.id}`);
          }}
        />
      )}
    </div>
  );
}
