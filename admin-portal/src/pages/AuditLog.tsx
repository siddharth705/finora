import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Activity, LogIn, UserPlus, Wallet, ArrowLeftRight, Store,
  ListFilter, FileStack, Link2, ShieldCheck,
} from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { FilterBar } from '../components/FilterBar';
import { Pagination } from '../components/Pagination';
import { useSavedViews } from '../hooks/useSavedViews';
import { adminAuditApi } from '../api/endpoints';
import type { AuditLogDto } from '../types';

const PAGE_SIZE = 25;

/** Maps an audit action's prefix to a representative icon, first match wins. Generic Activity is
 *  the fallback -- a brand-new action string AuditService starts writing tomorrow (see
 *  ActivityController's class comment for the current full list of callers) still renders
 *  sensibly with zero changes needed here, same "registry with a safe fallback" discipline the
 *  health provider registry and GlobalSearch's TYPE_META use. */
const ACTION_ICON_RULES: Array<[string, typeof Activity]> = [
  ['USER_LOGIN', LogIn],
  ['USER_', UserPlus],
  ['ACCOUNT_', Wallet],
  ['TRANSACTION_', ArrowLeftRight],
  ['MERCHANT_', Store],
  ['RULE_', ListFilter],
  ['STATEMENT_', FileStack],
  ['RELATIONSHIP_', Link2],
  ['ROLE_', ShieldCheck],
  ['PERMISSION_', ShieldCheck],
];

function iconForAction(action: string) {
  const match = ACTION_ICON_RULES.find(([prefix]) => action.startsWith(prefix));
  return match ? match[1] : Activity;
}

function relativeTime(iso: string) {
  const date = new Date(iso);
  const seconds = Math.max(0, Math.floor((Date.now() - date.getTime()) / 1000));
  if (seconds < 60) return 'just now';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}d ago`;
  return date.toLocaleDateString();
}

function dayLabel(iso: string) {
  const date = new Date(iso);
  const today = new Date();
  const yesterday = new Date();
  yesterday.setDate(today.getDate() - 1);
  if (date.toDateString() === today.toDateString()) return 'Today';
  if (date.toDateString() === yesterday.toDateString()) return 'Yesterday';
  return date.toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' });
}

/** Groups an already-newest-first flat list into [dayLabel, entries[]] buckets in one pass,
 *  preserving order -- the backend already sorts by createdAt desc (AuditLogRepository
 *  .findAllByOrderByCreatedAtDesc), this only adds the day boundaries a real timeline needs. */
function groupByDay(logs: AuditLogDto[]): Array<[string, AuditLogDto[]]> {
  const groups: Array<[string, AuditLogDto[]]> = [];
  for (const log of logs) {
    const label = dayLabel(log.createdAt);
    const current = groups[groups.length - 1];
    if (current && current[0] === label) {
      current[1].push(log);
    } else {
      groups.push([label, [log]]);
    }
  }
  return groups;
}

function TimelineEntry({ log }: { log: AuditLogDto }) {
  const Icon = iconForAction(log.action);
  return (
    <div className="flex items-start gap-3 px-4 py-3">
      <span className="w-8 h-8 rounded-lg bg-primary-light flex items-center justify-center flex-shrink-0 text-primary">
        <Icon size={15} />
      </span>
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium text-ink">{log.action}</p>
        <p className="text-xs text-muted mt-0.5">
          {log.entityType}{log.entityId ? ` #${log.entityId.slice(0, 8)}` : ''} · user {log.userId.slice(0, 8)}
        </p>
      </div>
      <span
        className="text-xs text-muted flex-shrink-0 whitespace-nowrap"
        title={new Date(log.createdAt).toLocaleString()}
      >
        {relativeTime(log.createdAt)}
      </span>
    </div>
  );
}

interface AuditFilterValues {
  q: string;
  dateFrom: string;
  dateTo: string;
  sortDir: string;
}

const BLANK_FILTERS: AuditFilterValues = { q: '', dateFrom: '', dateTo: '', sortDir: 'desc' };

/**
 * Admin Portal Phase 3 (Unified Activity Feed) -- a real chronological timeline over the same
 * underlying data the plain table used to show. Grouped by calendar day with relative
 * "Xm/Xh/Xd ago" timestamps (exact time still available via the title tooltip) and a
 * representative icon per action via iconForAction's prefix-matched registry above.
 *
 * Admin Portal Phase 5 (Shared Filtering Framework) -- adds FilterBar (search + date range +
 * sort) wired to AdminController.globalAuditLogs' real q/dateFrom/dateTo/sortDir params (not
 * client-side filtering of just the currently-loaded page, which would silently misrepresent
 * what "search" found), plus useSavedViews so a support admin can bookmark, say, "failed logins
 * this week" as a one-click filter combination.
 */
function AuditLogContent() {
  const [page, setPage] = useState(0);
  const [filters, setFilters] = useState<AuditFilterValues>(BLANK_FILTERS);
  const [searchInput, setSearchInput] = useState('');
  const savedViews = useSavedViews<AuditFilterValues>('finora-admin-views-audit-log');

  const { data, isLoading } = useQuery({
    queryKey: ['admin-audit-global', page, filters],
    queryFn: () => adminAuditApi.global(page, PAGE_SIZE, {
      q: filters.q || undefined,
      dateFrom: filters.dateFrom || undefined,
      dateTo: filters.dateTo || undefined,
      sortDir: filters.sortDir === 'asc' ? 'asc' : 'desc',
    }),
  });

  function applySearch() {
    setFilters((f) => ({ ...f, q: searchInput.trim() }));
    setPage(0);
  }

  function updateFilter<K extends keyof AuditFilterValues>(key: K, value: AuditFilterValues[K]) {
    setFilters((f) => ({ ...f, [key]: value }));
    setPage(0);
  }

  function applyView(values: AuditFilterValues) {
    setFilters(values);
    setSearchInput(values.q);
    setPage(0);
  }

  const totalPages = data?.totalPages ?? 0;
  const groups = data ? groupByDay(data.content) : [];

  return (
    <div>
      <FilterBar<AuditFilterValues>
        fields={[
          { type: 'search', key: 'q', value: searchInput, onChange: setSearchInput, placeholder: 'Search action or entity type…' },
          { type: 'date', key: 'dateFrom', label: 'From', value: filters.dateFrom, onChange: (v) => updateFilter('dateFrom', v) },
          { type: 'date', key: 'dateTo', label: 'To', value: filters.dateTo, onChange: (v) => updateFilter('dateTo', v) },
          {
            type: 'select', key: 'sortDir', value: filters.sortDir, onChange: (v) => updateFilter('sortDir', v),
            options: [{ label: 'Newest first', value: 'desc' }, { label: 'Oldest first', value: 'asc' }],
          },
        ]}
        onApply={applySearch}
        savedViews={{
          views: savedViews.views,
          currentValues: { ...filters, q: searchInput.trim() },
          onApply: applyView,
          onSave: savedViews.save,
          onDelete: savedViews.remove,
        }}
      />

      <div className="bg-card border border-border rounded-xl2 shadow-card overflow-hidden">
        {isLoading && <p className="px-4 py-8 text-center text-muted text-sm">Loading…</p>}
        {!isLoading && groups.length === 0 && (
          <p className="px-4 py-8 text-center text-muted text-sm">
            {filters.q || filters.dateFrom || filters.dateTo
              ? 'No activity matches these filters.'
              : 'No activity recorded yet.'}
          </p>
        )}
        {groups.map(([label, entries]) => (
          <div key={label}>
            <p className="px-4 py-2 text-[10px] font-bold uppercase tracking-wide text-muted bg-bg border-b border-border">
              {label}
            </p>
            <div className="divide-y divide-border">
              {entries.map((log) => <TimelineEntry key={log.id} log={log} />)}
            </div>
          </div>
        ))}
      </div>

      {data && data.totalElements > 0 && (
        <Pagination
          page={page}
          totalPages={totalPages}
          totalElements={data.totalElements}
          pageSize={PAGE_SIZE}
          onPageChange={setPage}
        />
      )}
    </div>
  );
}

export default function AuditLog() {
  return (
    <AdminLayout title="Audit Log" subtitle="Platform-wide activity feed, most recent first">
      <RequirePermission permission="AUDIT_VIEW">
        <AuditLogContent />
      </RequirePermission>
    </AdminLayout>
  );
}
