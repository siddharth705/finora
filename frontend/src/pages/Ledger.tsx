import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient, keepPreviousData } from '@tanstack/react-query';
import {
  Pencil, Trash2, X, ChevronLeft, ChevronRight, HelpCircle, Loader2,
  Wallet, Receipt, Tag, PiggyBank, FilterX, type LucideIcon,
} from 'lucide-react';
import {
  transactionsApi, categoriesApi, accountsApi, budgetsApi,
  type TransactionFilters, type UpdateTransactionPayload, type TransactionExplanation,
} from '../api/endpoints';
import { AskOnceCard } from '../components/AskOnceCard';
import { CategoryCombobox } from '../components/CategoryCombobox';
import { CategoryCreateEditPanel } from '../components/CategoryCreateEditPanel';
import { MerchantGroupReviewCard } from '../components/MerchantGroupReviewCard';
import { CounterpartyGroupReviewCard } from '../components/CounterpartyGroupReviewCard';
import { MerchantLogo } from '../components/MerchantLogo';
import { BankLogo } from '../components/BankLogo';
import { MaskedAccountNumber } from '../components/MaskedAccountNumber';
import type { Transaction } from '../types';
import { counterpartyLabel } from '../lib/counterpartyLabel';
import { ConfirmDialog, Button, IconButton, Skeleton, FinoraCard, MetricCard, Badge } from '../design-system';
import { useDelayedLoading } from '../hooks/useDelayedLoading';
import { ICON_COMPONENTS, COLOR_HEX } from '../lib/categoryIcons';

const PAGE_SIZE_OPTIONS = [10, 25, 50];
// Bounds the client-side aggregation the KPI row and category chips are built from (see
// `statsFilters` below) -- large enough to cover a real personal-finance user's filtered window in
// one request, without asking the backend for a dedicated aggregation endpoint it doesn't have.
// Above this many matching rows, the total transaction COUNT (from the response's own
// totalElements) stays exact, but the spend/category breakdown is computed only over the first
// batch -- called out inline where it's used, not silently wrong.
const STATS_PAGE_SIZE = 500;

function fmt(n: number) {
  // Negative amounts (e.g. a month where spend exceeded income) must render as "-₹500",
  // not "₹-500" -- string concatenation put the currency symbol before the sign.
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

// The label+icon-badge header row `MetricCard` renders internally -- factored out here rather
// than hand-rolled twice, since Top Category/This Month need a custom body `MetricCard` itself
// doesn't support (a "spend (pct%)" line, a progress bar) but still want the identical header.
function KpiCardHeader({ label, icon: Icon, iconBg, iconColor }: { label: string; icon: LucideIcon; iconBg: string; iconColor: string }) {
  return (
    <div className="flex items-start justify-between mb-3">
      <p className="text-sm text-muted">{label}</p>
      <div className={`w-9 h-9 rounded-full ${iconBg} flex items-center justify-center flex-shrink-0`}>
        <Icon size={17} className={iconColor} />
      </div>
    </div>
  );
}

/**
 * `t.reconciliationStatus` used to render straight into the Status column, unfiltered and
 * unexplained -- `OK`, the status of the overwhelming majority of ordinary transactions, has no
 * useful meaning to a person reading their ledger, but it looked exactly as prominent and
 * exactly as alarming as `DUPLICATE`. Reported directly: "what is this OK status?".
 *
 * `null` for `OK` on purpose -- the badge disappears rather than saying something with no
 * content, which is also what TransactionExplanationService.reconciliationExplanationFor already
 * does for the same status one layer down (`return null` for OK). Every other status gets a
 * short, human label and a tooltip that names it in one line; clicking it opens the same
 * "Why this category?" panel already wired to `explaining`, which fetches the FULL reasoning
 * (via transactionsApi.explanation -- see ExplanationModal's reconciliation section below) for
 * whichever specific transaction was clicked, rather than duplicating that copy here.
 */
function reconciliationBadge(status: Transaction['reconciliationStatus']): { label: string; hint: string; className: string } | null {
  switch (status) {
    case 'OK':
      return null;
    case 'DUPLICATE':
      return { label: 'Duplicate', hint: 'Matched as a repeat of another transaction', className: 'bg-danger-bg text-danger' };
    case 'TRANSFER':
      return { label: 'Transfer', hint: 'Matched as money moving between your own accounts', className: 'bg-primary/15 text-primary' };
    case 'REFUND':
      return { label: 'Refund', hint: 'Matched as a refund of an earlier purchase', className: 'bg-success-bg text-success' };
    case 'REVERSAL':
      return { label: 'Reversed', hint: 'Matched as a reversal of an earlier purchase', className: 'bg-warning-bg text-warning' };
    case 'INVESTMENT_TRANSFER':
      return { label: 'Investment', hint: 'Excluded from spend as an investment transfer', className: 'bg-primary/15 text-primary' };
    case 'SUPERSEDED':
      return { label: 'Superseded', hint: 'From a statement re-upload that replaced this period', className: 'bg-gray-200 text-gray-500' };
  }
}

/**
 * The mockup's Status column (Needs Review / Categorized / Recurring / Reviewed) mapped onto real
 * fields already on `Transaction` -- nothing here is a new backend concept. `needsCategoryReview`
 * and `recurring` are independent booleans, both worth showing at once: a recurring subscription
 * that also needs a category review is real (see e.g. a confidence downgrade on an otherwise
 * well-established merchant), and a reader scanning the Needs Review rows shouldn't lose the
 * "this repeats every month" signal just because the row also needs review. Reviewed/Categorized
 * only fills in when NEITHER of those is true -- they're the "nothing else to say" fallback, not
 * one more option in a single priority chain.
 */
function statusBadges(t: Transaction): { label: string; tone: 'warning' | 'primary' | 'success' }[] {
  const badges: { label: string; tone: 'warning' | 'primary' | 'success' }[] = [];
  if (t.needsCategoryReview) badges.push({ label: 'Needs Review', tone: 'warning' });
  if (t.recurring) badges.push({ label: 'Recurring', tone: 'primary' });
  if (badges.length === 0) {
    badges.push(t.categoryManuallySet ? { label: 'Reviewed', tone: 'primary' } : { label: 'Categorized', tone: 'success' });
  }
  return badges;
}

function splitDate(dateStr: string): { day: string; monthYear: string } {
  const d = new Date(dateStr + 'T00:00:00');
  if (Number.isNaN(d.getTime())) return { day: dateStr, monthYear: '' };
  return {
    day: String(d.getDate()).padStart(2, '0'),
    monthYear: d.toLocaleDateString('en-IN', { month: 'short', year: 'numeric' }),
  };
}

// Windowed page-number list (first, last, current ± 1, "…" for the gaps) -- a real "1 2 3 … 9 10"
// control rather than one button per page, which would be unusable once totalPages grows.
function pageNumbers(current: number, total: number): (number | '…')[] {
  if (total <= 7) return Array.from({ length: total }, (_, i) => i);
  const keep = new Set<number>([0, total - 1, current - 1, current, current + 1]);
  const sorted = [...keep].filter((p) => p >= 0 && p < total).sort((a, b) => a - b);
  const result: (number | '…')[] = [];
  let prev = -1;
  for (const p of sorted) {
    if (prev !== -1 && p - prev > 1) result.push('…');
    result.push(p);
    prev = p;
  }
  return result;
}

// Small debounce hook so typing in the search box doesn't fire a query per keystroke —
// the debounced value becomes part of the query key, so TanStack Query only refetches
// once typing settles, and caches each distinct filter combination it's already seen.
function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}

export default function Ledger() {
  // Seeds the search box from the TopBar's global search ("/app/transactions?q=..."), so
  // pressing Enter up there actually lands here with the term already applied rather than
  // just navigating to an empty ledger.
  const [searchParams] = useSearchParams();
  const [filters, setFilters] = useState<TransactionFilters>({ page: 0, size: 10, sortField: 'date', sortDir: 'desc' });
  const [keywordInput, setKeywordInput] = useState(() => searchParams.get('q') ?? '');
  const debouncedKeyword = useDebouncedValue(keywordInput, 300);
  const queryClient = useQueryClient();

  const [editing, setEditing] = useState<Transaction | null>(null);
  const [explaining, setExplaining] = useState<Transaction | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<Transaction | null>(null);

  // Ledger doesn't unmount between two TopBar searches fired while already on this page (same
  // route, just a new ?q=), so the useState initializer above only covers the first visit —
  // this covers every subsequent one.
  useEffect(() => {
    const q = searchParams.get('q');
    if (q !== null) setKeywordInput(q);
  }, [searchParams]);

  // Every other filter control already resets to page 0 on change (see the type/date filters
  // below) -- the search box was the one gap, since it goes through the debounce above rather
  // than a plain onChange. Without this, searching while on page 3 of the unfiltered list could
  // land on a now out-of-range page of the filtered one.
  useEffect(() => {
    setFilters((f) => (f.page === 0 ? f : { ...f, page: 0 }));
    // Only when the search term itself actually changes, not on every filters update (that would
    // fight with the other filters' own `page: 0` resets and the Previous/Next handlers below).
    // Safe to depend on debouncedKeyword alone: setFilters uses the functional-updater form
    // above, so it never needs `filters` itself in this array.
  }, [debouncedKeyword]);

  const activeFilters = { ...filters, keyword: debouncedKeyword || undefined };
  const hasActiveFilters = !!(activeFilters.type || activeFilters.status || activeFilters.categoryId
    || activeFilters.dateFrom || activeFilters.dateTo || activeFilters.keyword);
  // Deliberately excludes categoryId, unlike hasActiveFilters above -- the KPI row's numbers
  // (see statsFilters below) never factor in the category chip, so labelling them "filtered"
  // when a chip is the ONLY active filter would be true of the label but false of the value.
  const hasStatsFilters = !!(activeFilters.type || activeFilters.status
    || activeFilters.dateFrom || activeFilters.dateTo || activeFilters.keyword);

  const { data: page, isLoading, isFetching } = useQuery({
    queryKey: ['transactions', activeFilters],
    queryFn: () => transactionsApi.search(activeFilters),
    placeholderData: keepPreviousData, // keep showing the old page while the new one loads, no flash-to-empty
  });
  const txns = page?.content ?? [];
  const showTableSkeleton = useDelayedLoading(isLoading);

  // Powers the KPI row and the category chips below -- every OTHER active filter applies (search,
  // type, status, dates) but never `categoryId`, so a chip's own count always answers "how many
  // rows would show if I clicked this," including for the currently-selected one. Capped at
  // STATS_PAGE_SIZE (see its own comment) rather than a dedicated aggregation endpoint, since none
  // exists on the backend today.
  const statsFilters: TransactionFilters = {
    type: activeFilters.type,
    status: activeFilters.status,
    dateFrom: activeFilters.dateFrom,
    dateTo: activeFilters.dateTo,
    keyword: activeFilters.keyword,
    page: 0,
    size: STATS_PAGE_SIZE,
    sortField: 'date',
    sortDir: 'desc',
  };
  const { data: statsPage, isLoading: statsLoading } = useQuery({
    queryKey: ['transactions-stats', statsFilters],
    queryFn: () => transactionsApi.search(statsFilters),
    placeholderData: keepPreviousData,
  });
  const transactionCount = statsPage?.totalElements ?? 0;

  const { data: categories } = useQuery({ queryKey: ['categories'], queryFn: () => categoriesApi.list() });
  const categoriesById = useMemo(() => new Map((categories ?? []).map((c) => [c.id, c])), [categories]);

  const { data: accounts } = useQuery({ queryKey: ['accounts'], queryFn: () => accountsApi.list() });
  const accountsById = useMemo(() => new Map((accounts ?? []).map((a) => [a.id, a])), [accounts]);

  const { data: budgets } = useQuery({ queryKey: ['budgets'], queryFn: () => budgetsApi.list() });
  const budgetSpend = (budgets ?? []).reduce((s, b) => s + b.spentThisMonth, 0);
  const budgetLimit = (budgets ?? []).reduce((s, b) => s + b.monthlyLimit, 0);
  const budgetPct = budgetLimit > 0 ? Math.min(100, Math.round((budgetSpend / budgetLimit) * 100)) : 0;

  const { totalSpend, categoryChips, topCategory } = useMemo(() => {
    const statsTxns = statsPage?.content ?? [];
    const totals = new Map<string, { id: string; name: string; count: number; spend: number }>();
    let spend = 0;
    for (const t of statsTxns) {
      const cur = totals.get(t.categoryId) ?? { id: t.categoryId, name: t.categoryName, count: 0, spend: 0 };
      cur.count += 1;
      if (t.type === 'EXPENSE') {
        cur.spend += t.amount;
        spend += t.amount;
      }
      totals.set(t.categoryId, cur);
    }
    const chips = [...totals.values()].sort((a, b) => b.count - a.count);
    const top = [...totals.values()].sort((a, b) => b.spend - a.spend)[0] ?? null;
    return { totalSpend: spend, categoryChips: chips, topCategory: top && top.spend > 0 ? top : null };
  }, [statsPage]);

  // Deleting a transaction can shrink the total below the page currently being viewed (e.g. the
  // only row left on the last page) -- without this, that page would just render empty with no
  // way back except manually clicking Previous. Keyed off the server's own totalPages/page in the
  // response, not local `filters.page`, so this only fires once the backend has actually confirmed
  // the current page no longer exists.
  useEffect(() => {
    if (page && page.totalPages > 0 && page.page > 0 && page.page >= page.totalPages) {
      setFilters((f) => ({ ...f, page: page.totalPages - 1 }));
    }
  }, [page]);

  // Editing/deleting a transaction can shift its own category totals, its account's balance,
  // budget progress, goals funded from it, and any AI insight built from spend patterns — same
  // cascading-refresh set Import.tsx already invalidates after a CSV confirm.
  function invalidateEverything() {
    // 'report'/'report-months' feed the Dashboard's Cash Flow Overview chart -- easy to miss
    // since Ledger doesn't render that chart itself, but an edit/delete here changes exactly
    // the per-month totals that chart is built from. 'transactions-stats' is this page's own
    // KPI/chip aggregation query (above) -- an edit/delete changes exactly what it's built from.
    ['transactions', 'transactions-stats', 'dashboard-summary', 'accounts', 'recent-transactions', 'budgets', 'goals', 'insights', 'report-months', 'report']
      .forEach((key) => { void queryClient.invalidateQueries({ queryKey: [key] }); });
  }

  async function handleDelete(t: Transaction) {
    setDeletingId(t.id);
    setError(null);
    try {
      await transactionsApi.remove(t.id);
      invalidateEverything();
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not delete this transaction.');
    } finally {
      setDeletingId(null);
    }
  }

  function clearFilters() {
    setKeywordInput('');
    setFilters((f) => ({ page: 0, size: f.size, sortField: f.sortField, sortDir: f.sortDir }));
  }

  return (
    <div className="space-y-5">
      {/* Page header -- eyebrow + headline, matching the mockup's copy. No illustration/quote
          card: there's no real asset for this page, and fabricating one would be decoration
          invented for this redesign rather than anything grounded in the product. */}
      <div>
        <p className="text-[11px] font-semibold uppercase tracking-widest text-muted mb-1">Transactions</p>
        <h1 className="text-2xl md:text-3xl font-bold text-ink font-display">
          Every transaction <span className="text-primary">tells a story</span>
        </h1>
        <p className="text-sm text-muted mt-1">Search, categorize, and understand your spending better.</p>
      </div>

      {/* KPI row. Total Spent/Top Category are computed client-side over `statsFilters`'
          bounded fetch (see its own comment); Transactions is the exact server-reported count for
          the same window. This Month reuses the same budgetsApi aggregation Budgets.tsx already
          shows, since a budget is inherently a calendar-month concept independent of whatever
          date range is filtered here. Deliberately no "vs last month" deltas -- see PR description. */}
      {statsLoading && !statsPage ? (
        <Skeleton.Region label="Loading transaction summary" className="grid grid-cols-2 lg:grid-cols-4 gap-4">
          {[0, 1, 2, 3].map((i) => <Skeleton.Card key={i} />)}
        </Skeleton.Region>
      ) : (
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
          <MetricCard
            label={hasStatsFilters ? 'Total Spent (filtered)' : 'Total Spent'}
            value={fmt(totalSpend)}
            icon={Wallet}
            iconBg="bg-primary-light"
            iconColor="text-primary"
          />
          <MetricCard
            label="Transactions"
            value={transactionCount.toLocaleString('en-IN')}
            icon={Receipt}
            iconBg="bg-primary-light"
            iconColor="text-primary"
          />
          <FinoraCard>
            <KpiCardHeader label="Top Category" icon={Tag} iconBg="bg-success-bg" iconColor="text-success" />
            <p className="text-2xl font-bold mb-1 text-ink truncate">{topCategory ? topCategory.name : '—'}</p>
            {topCategory && (
              <p className="text-xs text-muted">
                {fmt(topCategory.spend)} ({totalSpend > 0 ? Math.round((topCategory.spend / totalSpend) * 100) : 0}%)
              </p>
            )}
          </FinoraCard>
          <FinoraCard>
            <KpiCardHeader label="This Month" icon={PiggyBank} iconBg="bg-warning-bg" iconColor="text-warning" />
            <p className="text-2xl font-bold mb-2 text-ink">{fmt(budgetSpend)}</p>
            <div className="h-1.5 bg-black/10 rounded-full overflow-hidden mb-1">
              <div className="h-full bg-primary" style={{ width: `${budgetPct}%` }} />
            </div>
            <p className="text-xs text-muted">{budgetPct}% of budget</p>
          </FinoraCard>
        </div>
      )}

      <MerchantGroupReviewCard />
      <CounterpartyGroupReviewCard />
      <AskOnceCard />

      {error && <p className="text-danger text-sm">{error}</p>}

      {/* Filters */}
      <FinoraCard padding="sm" className="space-y-4">
        <div className="grid grid-cols-2 md:grid-cols-6 gap-2">
          <input
            placeholder="Search description, merchant, category, bank, account, branch, IFSC…"
            value={keywordInput}
            onChange={(e) => setKeywordInput(e.target.value)}
            className="col-span-2 md:col-span-2 bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm"
          />
          <select
            value={filters.type ?? ''}
            className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm"
            onChange={(e) => setFilters((f) => ({ ...f, type: e.target.value || undefined, page: 0 }))}
          >
            <option value="">All Types</option>
            <option value="INCOME">Income</option>
            <option value="EXPENSE">Expense</option>
          </select>
          {/* Reported directly ("I need filter here") right alongside the confusion over the Status
              column itself -- reconciliationBadge/statusInfo (above in this file) are what makes
              each of these values mean something on the row; this is the same vocabulary, as a
              filter. */}
          <select
            value={filters.status ?? ''}
            className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm"
            onChange={(e) => setFilters((f) => ({ ...f, status: e.target.value || undefined, page: 0 }))}
          >
            <option value="">All Statuses</option>
            <option value="OK">Ordinary</option>
            <option value="DUPLICATE">Duplicate</option>
            <option value="TRANSFER">Transfer</option>
            <option value="REFUND">Refund</option>
            <option value="REVERSAL">Reversed</option>
            <option value="INVESTMENT_TRANSFER">Investment</option>
            <option value="SUPERSEDED">Superseded</option>
          </select>
          <input
            type="date"
            value={filters.dateFrom ?? ''}
            aria-label="From date"
            className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm"
            onChange={(e) => setFilters((f) => ({ ...f, dateFrom: e.target.value || undefined, page: 0 }))}
          />
          <div className="flex gap-2">
            <input
              type="date"
              value={filters.dateTo ?? ''}
              aria-label="To date"
              className="flex-1 min-w-0 bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm"
              onChange={(e) => setFilters((f) => ({ ...f, dateTo: e.target.value || undefined, page: 0 }))}
            />
            <button
              type="button"
              onClick={clearFilters}
              disabled={!hasActiveFilters}
              title="Clear all filters"
              className="flex-shrink-0 flex items-center gap-1.5 border border-border rounded-lg px-3 py-2 text-sm text-muted hover:text-ink hover:bg-bg disabled:opacity-40 disabled:cursor-not-allowed"
            >
              <FilterX size={14} /> Clear
            </button>
          </div>
        </div>

        {/* Category chips -- built from the user's own real categories (icon/color already wired
            into ICON_COMPONENTS/COLOR_HEX below), each count from `categoryChips` above. Only
            rendered once there's something to show; an all-empty ledger has nothing to chip. */}
        {categoryChips.length > 0 && (
          <div className="flex items-center gap-2 overflow-x-auto pb-1 -mx-1 px-1">
            <button
              type="button"
              onClick={() => setFilters((f) => ({ ...f, categoryId: undefined, page: 0 }))}
              className={`flex-shrink-0 flex items-center gap-1.5 rounded-full px-3.5 py-1.5 text-xs font-medium border transition-colors ${
                !filters.categoryId ? 'bg-primary text-on-primary border-primary' : 'bg-card text-ink border-border hover:bg-bg'
              }`}
            >
              All <span className="opacity-70">{transactionCount}</span>
            </button>
            {categoryChips.map((c) => {
              const cat = categoriesById.get(c.id);
              const Icon = ICON_COMPONENTS[cat?.icon ?? 'tag'] ?? Tag;
              const active = filters.categoryId === c.id;
              return (
                <button
                  key={c.id}
                  type="button"
                  onClick={() => setFilters((f) => ({ ...f, categoryId: active ? undefined : c.id, page: 0 }))}
                  className={`flex-shrink-0 flex items-center gap-1.5 rounded-full px-3.5 py-1.5 text-xs font-medium border transition-colors ${
                    active ? 'bg-primary text-on-primary border-primary' : 'bg-card text-ink border-border hover:bg-bg'
                  }`}
                >
                  <Icon size={13} style={!active ? { color: COLOR_HEX[cat?.color ?? 'gray'] } : undefined} />
                  {c.name} <span className="opacity-70">{c.count}</span>
                </button>
              );
            })}
          </div>
        )}
      </FinoraCard>

      <div className="bg-card rounded-xl2 border border-border shadow-card overflow-x-auto relative">
        {isFetching && !isLoading && (
          <div className="absolute top-2 right-3 text-[10px] uppercase text-primary flex items-center gap-1">
            <Loader2 size={11} className="animate-spin" aria-hidden="true" /> Refreshing…
          </div>
        )}
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-[10px] uppercase text-gray-500 border-b border-border">
              <th className="p-3">Date</th>
              <th className="p-3">Description</th>
              <th className="p-3">Category</th>
              <th className="p-3">Account</th>
              <th className="p-3 text-right">Amount</th>
              <th className="p-3">Status</th>
              <th className="p-3"></th>
            </tr>
          </thead>
          <tbody role={isLoading ? 'status' : undefined} aria-busy={isLoading || undefined} aria-live={isLoading ? 'polite' : undefined}>
            {isLoading ? (
              // Bug fix: the sr-only announcement must not wait out the same flash-prevention
              // window the visual skeleton does -- that window exists to avoid a sighted-user
              // flicker, which doesn't apply to a screen-reader announcement. Same reasoning
              // ChartContainer.tsx already applies to its own Skeleton.Region; this row was
              // wrongly nested inside the showTableSkeleton gate, leaving the live region with
              // zero children (nothing to announce) for the whole delay window.
              <>
                <tr className="sr-only"><td colSpan={7}>Loading transactions</td></tr>
                {showTableSkeleton && [0, 1, 2, 3, 4].map((i) => (
                  <tr key={i} className="border-b border-dashed border-border" aria-hidden="true">
                    <td colSpan={7} className="p-2">
                      <div className="flex items-center gap-4">
                        <Skeleton.Text width="w-16" />
                        <div className="flex items-center gap-2 flex-1">
                          <Skeleton.Circle size={22} />
                          <Skeleton.Text width="w-48" />
                        </div>
                        <Skeleton.Text width="w-16" />
                        <Skeleton.Text width="w-16" />
                        <Skeleton.Text width="w-14" />
                        <Skeleton.Text width="w-16" />
                      </div>
                    </td>
                  </tr>
                ))}
              </>
            ) : txns.length === 0 ? (
              <tr><td colSpan={7} className="p-4 text-center text-gray-500 italic">No transactions match these filters.</td></tr>
            ) : (
              txns.map((t) => {
                const { day, monthYear } = splitDate(t.date);
                const account = accountsById.get(t.accountId);
                const badges = statusBadges(t);
                const badge = reconciliationBadge(t.reconciliationStatus);
                return (
                  <tr key={t.id} className="border-b border-dashed border-border align-top">
                    <td className="p-3 whitespace-nowrap">
                      <p className="text-ink font-semibold leading-tight">{day}</p>
                      <p className="text-[11px] text-muted leading-tight">{monthYear}</p>
                    </td>
                    <td className="p-3">
                      <div className="flex items-start gap-2">
                        <MerchantLogo merchant={t.merchant} size={28} />
                        <div className="min-w-0">
                          <p className="text-ink font-medium truncate">{t.merchant || t.description}</p>
                          <p className="text-muted text-xs truncate">
                            {t.description && t.description !== t.merchant ? t.description : null}
                          </p>
                          <div className="flex flex-wrap items-center gap-1 mt-0.5">
                            {/* WHO, next to the narration it was derived from -- deliberately not
                                in the category cell, because "who" and "what for" are different
                                questions and putting the answer to one beside the answer to the
                                other is how they get conflated. Muted, not accented: this is
                                context, not a call to act. Nothing renders when unknown. */}
                            {(() => {
                              const cp = counterpartyLabel(t.counterpartyType, t.type);
                              return cp ? (
                                <span
                                  className="text-[10px] uppercase bg-gray-200 text-gray-500 px-1.5 py-0.5 rounded"
                                  title={cp.full}
                                >
                                  {cp.short}
                                </span>
                              ) : null;
                            })()}
                          </div>
                        </div>
                      </div>
                    </td>
                    <td className="p-3 text-gray-500">
                      {t.categoryName}
                      <span
                        className={`text-[9px] uppercase ml-1.5 px-1 py-0.5 rounded ${t.categoryManuallySet ? 'bg-primary/15 text-primary' : 'bg-gray-200 text-gray-500'}`}
                        title={t.categoryManuallySet ? 'You set this category' : 'Automatically assigned by Fynora'}
                      >
                        {t.categoryManuallySet ? 'Manual' : 'Auto'}
                      </span>
                      <button
                        type="button"
                        title="Why this category?"
                        onClick={() => setExplaining(t)}
                        className="inline-flex items-center justify-center w-4 h-4 ml-1 text-muted hover:text-ink align-middle"
                      >
                        <HelpCircle size={12} />
                      </button>
                    </td>
                    <td className="p-3">
                      {account ? (
                        <div className="flex items-center gap-2">
                          <BankLogo bank={account.bank} size={20} />
                          <div className="min-w-0">
                            <p className="text-ink text-xs font-medium truncate">{account.bank.shortName}</p>
                            {account.accountNumberMasked && (
                              <MaskedAccountNumber value={account.accountNumberMasked} className="text-muted text-[11px]" />
                            )}
                          </div>
                        </div>
                      ) : (
                        <span className="text-muted text-xs">—</span>
                      )}
                    </td>
                    <td className={`p-3 text-right font-medium whitespace-nowrap ${t.type === 'INCOME' ? 'text-success' : 'text-danger'}`}>
                      {t.type === 'INCOME' ? '+' : '-'}{fmt(t.amount)}
                    </td>
                    <td className="p-3">
                      <div className="flex flex-col items-start gap-1">
                        {badges.map((b) => <Badge key={b.label} tone={b.tone} label={b.label} />)}
                        {badge && (
                          <button
                            type="button"
                            title={badge.hint}
                            onClick={() => setExplaining(t)}
                            className={`text-[10px] uppercase px-1.5 py-0.5 rounded hover:opacity-80 ${badge.className}`}
                          >
                            {badge.label}
                          </button>
                        )}
                      </div>
                    </td>
                    <td className="p-3">
                      <div className="flex items-center gap-1 justify-end">
                        <IconButton
                          size="sm"
                          icon={<Pencil size={13} />}
                          aria-label="Edit transaction"
                          title="Edit transaction"
                          onClick={() => setEditing(t)}
                        />
                        <IconButton
                          size="sm"
                          variant="danger"
                          icon={<Trash2 size={13} />}
                          aria-label="Delete transaction"
                          title="Delete transaction"
                          loading={deletingId === t.id}
                          onClick={() => setConfirmDelete(t)}
                        />
                      </div>
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>

      {page && page.totalElements > 0 && (
        <div className="flex flex-wrap items-center justify-between gap-3 text-xs text-muted px-1">
          <p>
            Showing <span className="text-ink font-medium">{page.page * page.size + 1}</span>
            {'–'}
            <span className="text-ink font-medium">{Math.min((page.page + 1) * page.size, page.totalElements)}</span>
            {' of '}
            <span className="text-ink font-medium">{page.totalElements.toLocaleString('en-IN')}</span>
            {' transactions'}
          </p>
          <div className="flex items-center gap-2">
            <IconButton
              size="sm"
              icon={<ChevronLeft size={14} />}
              aria-label="Previous page"
              onClick={() => setFilters((f) => ({ ...f, page: Math.max(0, (f.page ?? 0) - 1) }))}
              disabled={page.page === 0 || isFetching}
            />
            {page.totalPages > 1 && pageNumbers(page.page, page.totalPages).map((p, i) =>
              p === '…' ? (
                <span key={`ellipsis-${i}`} className="text-muted px-1">…</span>
              ) : (
                <button
                  key={p}
                  type="button"
                  aria-label={`Page ${p + 1}`}
                  aria-current={p === page.page ? 'page' : undefined}
                  onClick={() => setFilters((f) => ({ ...f, page: p }))}
                  disabled={isFetching}
                  className={`w-7 h-7 rounded-lg text-xs font-medium flex-shrink-0 ${
                    p === page.page ? 'bg-primary text-on-primary' : 'text-ink hover:bg-bg'
                  }`}
                >
                  {p + 1}
                </button>
              )
            )}
            <IconButton
              size="sm"
              icon={<ChevronRight size={14} />}
              aria-label="Next page"
              onClick={() => setFilters((f) => ({ ...f, page: (f.page ?? 0) + 1 }))}
              disabled={page.page + 1 >= page.totalPages || isFetching}
            />
          </div>
          <label className="flex items-center gap-1.5">
            <span>Rows per page</span>
            <select
              value={filters.size}
              onChange={(e) => setFilters((f) => ({ ...f, size: Number(e.target.value), page: 0 }))}
              className="bg-card text-ink border border-border rounded-lg px-2 py-1"
            >
              {PAGE_SIZE_OPTIONS.map((n) => <option key={n} value={n}>{n}</option>)}
            </select>
          </label>
        </div>
      )}

      {editing && (
        <EditTransactionModal
          transaction={editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            invalidateEverything();
          }}
        />
      )}

      {explaining && (
        <ExplanationModal transaction={explaining} onClose={() => setExplaining(null)} />
      )}

      {confirmDelete && (
        <ConfirmDialog
          title={`Delete "${confirmDelete.description || confirmDelete.merchant}"?`}
          message={`${fmt(confirmDelete.amount)} — this can't be undone.`}
          confirmLabel="Delete"
          danger
          onConfirm={() => {
            const t = confirmDelete;
            setConfirmDelete(null);
            void handleDelete(t);
          }}
          onCancel={() => setConfirmDelete(null)}
        />
      )}
    </div>
  );
}

// "Why this category?" -- fetched on demand rather than carried on every row, since most rows
// are never expanded. Every branch below is Fynora's own real categorization decision read back
// out, not a new guess made for this panel -- see TransactionExplanationDto's own doc comment.
function ExplanationModal({ transaction, onClose }: { transaction: Transaction; onClose: () => void }) {
  const [explanation, setExplanation] = useState<TransactionExplanation | null>(null);
  const [loadError, setLoadError] = useState(false);

  useEffect(() => {
    let cancelled = false;
    transactionsApi.explanation(transaction.id)
      .then((result) => { if (!cancelled) setExplanation(result); })
      .catch(() => { if (!cancelled) setLoadError(true); });
    return () => { cancelled = true; };
  }, [transaction.id]);

  return (
    <>
      <div className="fixed inset-0 bg-black/40 z-30" onClick={onClose} />
      <div className="fixed inset-0 z-40 flex items-center justify-center p-4 pointer-events-none">
        <div className="bg-card border border-border rounded-xl2 shadow-soft w-full max-w-sm p-5 pointer-events-auto">
          <div className="flex items-center justify-between mb-3">
            <h3 className="font-semibold text-ink text-sm">Why this category?</h3>
            <button type="button" onClick={onClose} aria-label="Close" className="text-muted hover:text-ink">
              <X size={18} />
            </button>
          </div>

          <p className="text-xs text-muted mb-3">
            {transaction.description || transaction.merchant} · {transaction.categoryName}
          </p>

          {loadError ? (
            <p className="text-danger text-xs">Couldn't load this explanation — please try again.</p>
          ) : !explanation ? (
            <p className="text-muted text-xs">Loading…</p>
          ) : (
            <div className="space-y-4">
              {/* This section is what makes the Status column's badge (reconciliationBadge, above
                  in this file) clickable rather than a static label: transactionsApi.explanation
                  already computes the full reconciliation reasoning server-side
                  (TransactionExplanationService.reconciliationExplanationFor) -- it was simply
                  never rendered anywhere, so the fetch ran and the answer was thrown away.
                  `undefined` for an ordinary OK transaction (the common case, and the reason this
                  whole section is conditional rather than always present). */}
              {explanation.reconciliation && (() => {
                const badge = reconciliationBadge(explanation.reconciliation.status);
                return (
                  <div className="space-y-2 pb-4 border-b border-border">
                    {badge && (
                      <span className={`inline-block text-[10px] uppercase px-1.5 py-0.5 rounded ${badge.className}`}>
                        {badge.label}
                      </span>
                    )}
                    <p className="text-ink text-sm">{explanation.reconciliation.summary}</p>
                    {explanation.reconciliation.evidence.length > 0 && (
                      <ul className="list-disc list-inside space-y-1">
                        {explanation.reconciliation.evidence.map((line, i) => (
                          <li key={i} className="text-xs text-muted">{line}</li>
                        ))}
                      </ul>
                    )}
                  </div>
                );
              })()}

              <div className="space-y-2">
                <p className="text-ink text-sm">{explanation.summary}</p>
                {explanation.confidence != null && (
                  <p className="text-xs text-muted">{explanation.confidence}% confidence</p>
                )}
                {explanation.evidence.length > 0 && (
                  <ul className="list-disc list-inside space-y-1">
                    {explanation.evidence.map((line, i) => (
                      <li key={i} className="text-xs text-muted">{line}</li>
                    ))}
                  </ul>
                )}
              </div>
            </div>
          )}
        </div>
      </div>
    </>
  );
}

function EditTransactionModal({
  transaction, onClose, onSaved,
}: {
  transaction: Transaction;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [date, setDate] = useState(transaction.date);
  const [description, setDescription] = useState(transaction.description ?? '');
  const [merchant, setMerchant] = useState(transaction.merchant ?? '');
  const [amount, setAmount] = useState(String(transaction.amount));
  const [type, setType] = useState<'INCOME' | 'EXPENSE'>(transaction.type);
  const [category, setCategory] = useState(transaction.categoryName);
  const [creatingCategory, setCreatingCategory] = useState<string | null>(null);
  const [notes, setNotes] = useState(transaction.notes ?? '');
  const [tagsInput, setTagsInput] = useState((transaction.tags ?? []).join(', '));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function save() {
    setSaving(true);
    setError(null);
    try {
      const payload: UpdateTransactionPayload = {
        date,
        description,
        merchant,
        amount: parseFloat(amount),
        type,
        categoryName: category,
        // Sent as the literal current value (never funneled through `|| null`), same as
        // description/merchant above -- the backend's UpdateRequest treats a null field as
        // "leave unchanged," so using `|| null` here would make clearing existing notes down to
        // empty silently no-op instead of actually clearing them.
        notes: notes.trim(),
        tags: tagsInput.split(',').map((s) => s.trim()).filter(Boolean),
      };
      await transactionsApi.update(transaction.id, payload);
      onSaved();
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not save these changes.');
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      <div className="fixed inset-0 bg-black/40 z-30" onClick={onClose} />
      <div className="fixed inset-0 z-40 flex items-center justify-center p-4 pointer-events-none">
        <div className="bg-card border border-border rounded-xl2 shadow-soft w-full max-w-lg max-h-[85vh] overflow-y-auto p-5 pointer-events-auto">
          <div className="flex items-center justify-between mb-4">
            <h3 className="font-semibold text-ink text-sm">Edit Transaction</h3>
            <button type="button" onClick={onClose} aria-label="Close" className="text-muted hover:text-ink">
              <X size={18} />
            </button>
          </div>

          {error && <p className="text-danger text-xs mb-3">{error}</p>}

          <div className="grid grid-cols-2 gap-3 text-sm">
            <div>
              <label htmlFor="edit-txn-date" className="block text-[11px] uppercase text-muted mb-1">Date</label>
              <input id="edit-txn-date" type="date" value={date} onChange={(e) => setDate(e.target.value)} className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full" />
            </div>
            <div>
              <label htmlFor="edit-txn-type" className="block text-[11px] uppercase text-muted mb-1">Type</label>
              <select id="edit-txn-type" value={type} onChange={(e) => setType(e.target.value as 'INCOME' | 'EXPENSE')} className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full">
                <option value="INCOME">Income</option>
                <option value="EXPENSE">Expense</option>
              </select>
            </div>
            <div className="col-span-2">
              <label htmlFor="edit-txn-description" className="block text-[11px] uppercase text-muted mb-1">Description</label>
              <input id="edit-txn-description" value={description} onChange={(e) => setDescription(e.target.value)} className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full" />
            </div>
            <div className="col-span-2">
              <label htmlFor="edit-txn-merchant" className="block text-[11px] uppercase text-muted mb-1">Merchant</label>
              <input id="edit-txn-merchant" value={merchant} onChange={(e) => setMerchant(e.target.value)} className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full" />
            </div>
            <div>
              <label htmlFor="edit-txn-amount" className="block text-[11px] uppercase text-muted mb-1">Amount</label>
              <input id="edit-txn-amount" type="number" min="0.01" step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full" />
            </div>
            <div>
              <label htmlFor="edit-txn-category" className="block text-[11px] uppercase text-muted mb-1">Category</label>
              {creatingCategory !== null ? (
                <CategoryCreateEditPanel
                  mode="create"
                  initialName={creatingCategory}
                  onSaved={(c) => { setCategory(c.name); setCreatingCategory(null); }}
                  onCancel={() => setCreatingCategory(null)}
                />
              ) : (
                <CategoryCombobox
                  inputId="edit-txn-category"
                  value={category}
                  onChange={setCategory}
                  onCreateNew={setCreatingCategory}
                />
              )}
            </div>
            <div className="col-span-2">
              <label htmlFor="edit-txn-notes" className="block text-[11px] uppercase text-muted mb-1">Notes</label>
              <textarea id="edit-txn-notes" value={notes} onChange={(e) => setNotes(e.target.value)} rows={2} className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full" />
            </div>
            <div className="col-span-2">
              <label htmlFor="edit-txn-tags" className="block text-[11px] uppercase text-muted mb-1">Tags (comma-separated)</label>
              <input id="edit-txn-tags" value={tagsInput} onChange={(e) => setTagsInput(e.target.value)} placeholder="e.g. shared, recurring" className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full" />
            </div>
          </div>

          <div className="flex gap-3 mt-5">
            <Button
              onClick={save}
              loading={saving}
              disabled={!description.trim() || !amount || !(parseFloat(amount) > 0)}
            >
              Save changes
            </Button>
            <Button onClick={onClose} variant="secondary">
              Cancel
            </Button>
          </div>
        </div>
      </div>
    </>
  );
}
