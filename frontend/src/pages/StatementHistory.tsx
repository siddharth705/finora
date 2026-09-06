import { useMemo, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ChevronDown, ChevronRight, FileText, Download, RefreshCw, Trash2, Eye, ListChecks, X, AlertTriangle, Clock,
  Search, UploadCloud, CalendarDays, History, Landmark, Sparkles, FilterX, type LucideIcon,
} from 'lucide-react';
import { importApi, importJobsApi, statementImportsApi, type ImportFailureSummary, type ImportJobProgress } from '../api/endpoints';
import { PDF_PASSWORD_INVALID, PDF_PASSWORD_REQUIRED } from '../api/errorCodes';
import { importFailureMessage } from '../api/importFailureMessages';
import { BankLogo } from '../components/BankLogo';
import { recentImportsRefetchIntervalMs, label as jobLabel } from '../lib/importJob';
import { navigateToReimport, navigateToRetryFailedImport } from '../lib/importNavState';
import type { AccountStatementGroup, StatementSummary, Transaction } from '../types';
import { formatDate } from '../utils/date';
import { FinoraCard, EmptyState, ConfirmDialog, QuickActionCard, Skeleton } from '../design-system';
import heroIllustration from '../assets/statement-history/statement-history-hero.png';

// Reused from the same failure UX contract Import.tsx's live upload flow already draws on
// (Premium Import Reliability v1, §6) -- a failure a user comes back to later reads the same way
// one they hit live does. A code the contract doesn't own (or none at all) gets one safe,
// generic fallback rather than "undefined" or an internal code -- unlike Import.tsx's fallback,
// there is no server `message` available for a historical record to fall back to first.
function messageFor(failureCode: string | null): string {
  return importFailureMessage(failureCode) ?? "Fynora couldn't complete this import.";
}

function fmt(n: number | null) {
  if (n === null || n === undefined) return '—';
  // Negative amounts must render as "-₹500", not "₹-500".
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

function fmtDate(d: string | null) {
  // This page formats BOTH a date-only statement period and an importedAt timestamp through here;
  // formatDate distinguishes them by shape so only the former is parsed as a local calendar date.
  if (!d) return '—';
  return formatDate(d);
}

// Mirrors the backend's 7-day retention window (see StatementImportService.DELETED_ACCOUNT_RETENTION)
// purely for display — the backend is what actually stops returning the group once it expires.
function daysUntilRemoved(deletedAt: string): string {
  const deletedMs = new Date(deletedAt).getTime();
  const removedAtMs = deletedMs + 7 * 24 * 60 * 60 * 1000;
  const daysLeft = Math.max(0, Math.ceil((removedAtMs - Date.now()) / (24 * 60 * 60 * 1000)));
  if (daysLeft === 0) return 'removing today';
  return `removing in ${daysLeft} day${daysLeft === 1 ? '' : 's'}`;
}

export default function StatementHistory() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [openAccounts, setOpenAccounts] = useState<Set<string>>(new Set());
  const [viewing, setViewing] = useState<{ mode: 'summary' | 'transactions'; statement: StatementSummary } | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  // Set only once the server has told us this statement needs a password. `wrong` distinguishes
  // "we haven't asked yet" from "you answered and the document rejected it".
  const [passwordPrompt, setPasswordPrompt] = useState<{ statement: StatementSummary; wrong: boolean } | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Which statement's delete confirmation is showing, if any -- a custom in-app modal
  // (ConfirmDialog) instead of the browser's own confirm(), which rendered as unstyled OS chrome.
  const [confirmDelete, setConfirmDelete] = useState<StatementSummary | null>(null);

  const { data: groups, isLoading } = useQuery({
    queryKey: ['statement-imports'],
    queryFn: () => statementImportsApi.listGroupedByAccount(),
  });

  // Independent of the query above on purpose -- a failed statement never became an account group
  // at all, so there is nothing to join them on, and this section must not gate or be gated by the
  // successful-imports list. Failures rarely happen and this call is cheap, so no explicit loading
  // state: the section simply appears once the query resolves rather than reserving space for it.
  // React Query does not throw or surface this query's own errors to the page by default (that
  // needs an explicit opt-in this call never makes) -- fails closed, on purpose: a broken failures
  // panel must never block or blank the statements a user DID successfully import, the far more
  // important thing on this page.
  const { data: failures } = useQuery({
    queryKey: ['import-failures'],
    queryFn: () => importApi.listFailures(),
  });

  // Premium Import Reliability v1, §3.2 -- the entry point to the import detail page. Independent
  // of the queries above for the same reason `failures` is: a broken queued-imports list must
  // never block or blank the statements a user DID successfully import. Failing closed (React
  // Query does not surface this query's own error to the page without an explicit opt-in this
  // call never makes) rather than showing an error banner for a section that's allowed to just be
  // empty.
  // Bug fix, caught by review: with the global 30s staleTime and refetchOnWindowFocus off, a job
  // that finished or failed while the user was elsewhere kept showing its last-fetched in-flight
  // status indefinitely -- even revisiting this page inside that 30s window served the stale
  // cache. staleTime: 0 means every visit to this page re-checks; refetchInterval covers the
  // "stayed on this page the whole time, watching, never remounted" case a revisit alone can't
  // reach -- it only runs while at least one listed job is still non-terminal, so a page with
  // nothing in flight (the common case) pays no ongoing cost.
  const { data: recentJobs } = useQuery({
    queryKey: ['import-jobs-recent'],
    queryFn: () => importJobsApi.recent(),
    staleTime: 0,
    refetchInterval: (query) => recentImportsRefetchIntervalMs(query.state.data ?? []),
  });

  // COMPLETED is deliberately excluded, not just de-emphasized: a completed queued job already has
  // a real staged ImportSession row, created through the exact same code path the synchronous
  // upload endpoints use, and that session is already correctly surfaced by "Continue previous
  // import" (Import.tsx's unfinishedSessions list). Listing it again here would offer the same
  // staged review in two different sections with two different shapes. Every OTHER status
  // (QUEUED/PARSING/ANALYZING/DEDUPING/IMPORTING/LEARNING/FAILED/CANCELLED) is genuinely invisible
  // anywhere else today -- that's this section's actual reason to exist.
  const inProgressJobs = (recentJobs ?? []).filter((j) => j.status !== 'COMPLETED');

  // Hooks below must run on every render regardless of the isLoading early-return further down,
  // so accountGroups (and everything derived from it) is computed here rather than after that
  // return -- a conditional hook call would violate the Rules of Hooks the moment `groups`
  // resolves and isLoading flips from true to false mid-session.
  const accountGroups = useMemo(() => groups ?? [], [groups]);

  const [search, setSearch] = useState('');
  const [bankFilter, setBankFilter] = useState('');
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');
  const hasActiveFilters = !!(search || bankFilter || dateFrom || dateTo);

  function clearFilters() {
    setSearch(''); setBankFilter(''); setDateFrom(''); setDateTo('');
  }

  // KPI row figures -- every one of these is a real aggregate over the statements actually
  // returned by the API, never a fabricated or hardcoded number (there is, for instance, no
  // storage-quota concept anywhere in Fynora's backend or billing model, so that mockup element
  // has no equivalent here).
  const stats = useMemo(() => {
    const totalStatements = accountGroups.reduce((sum, g) => sum + g.statements.length, 0);
    const bankCount = new Set(accountGroups.map((g) => g.bank.id)).size;
    const totalTransactions = accountGroups.reduce(
      (sum, g) => sum + g.statements.reduce((s, st) => s + st.transactionsImported, 0), 0,
    );

    let oldest: { date: string; bankName: string } | null = null;
    let latest: { date: string; bankName: string } | null = null;
    for (const g of accountGroups) {
      const bankName = g.bank.officialName ?? g.bank.shortName;
      for (const s of g.statements) {
        if (!s.statementPeriodStart) continue;
        if (!oldest || s.statementPeriodStart < oldest.date) oldest = { date: s.statementPeriodStart, bankName };
        if (!latest || s.statementPeriodStart > latest.date) latest = { date: s.statementPeriodStart, bankName };
      }
    }

    return { totalStatements, bankCount, totalTransactions, oldest, latest };
  }, [accountGroups]);

  const bankOptions = useMemo(() => {
    const map = new Map<string, string>();
    accountGroups.forEach((g) => {
      if (!map.has(g.bank.id)) map.set(g.bank.id, g.bank.officialName ?? g.bank.shortName);
    });
    return Array.from(map.entries());
  }, [accountGroups]);

  // Filters narrow which statements show inside each account's accordion -- they never flatten
  // the grouping itself. Statement History groups by account on purpose (see AccountStatementGroup
  // above): that's how users actually think about their statements, not as one undifferentiated
  // pile of files. An account with zero statements left after filtering just doesn't render.
  const filteredGroups = useMemo(() => {
    const term = search.trim().toLowerCase();
    return accountGroups
      .map((g) => ({
        ...g,
        statements: g.statements.filter((s) => {
          if (bankFilter && g.bank.id !== bankFilter) return false;
          if (dateFrom && (!s.statementPeriodStart || s.statementPeriodStart < dateFrom)) return false;
          if (dateTo && (!s.statementPeriodEnd || s.statementPeriodEnd > dateTo)) return false;
          if (term) {
            const haystack = `${s.fileName} ${g.accountName} ${g.bank.officialName ?? ''} ${g.bank.shortName}`.toLowerCase();
            if (!haystack.includes(term)) return false;
          }
          return true;
        }),
      }))
      .filter((g) => g.statements.length > 0);
  }, [accountGroups, search, bankFilter, dateFrom, dateTo]);

  function toggleAccount(accountId: string) {
    setOpenAccounts((prev) => {
      const next = new Set(prev);
      if (next.has(accountId)) next.delete(accountId);
      else next.add(accountId);
      return next;
    });
  }

  function invalidateEverything() {
    // Same set Import.tsx refreshes after a confirm — deleting or replaying a statement changes
    // exactly the same downstream data a fresh import would, including the Dashboard's Cash Flow
    // Overview chart ('report'/'report-months').
    ['statement-imports', 'dashboard-summary', 'accounts', 'transactions', 'recent-transactions', 'goals', 'insights', 'budgets', 'report-months', 'report']
      .forEach((key) => { void queryClient.invalidateQueries({ queryKey: [key] }); });
  }

  /**
   * Re-import replays the ORIGINAL stored bytes, which for a protected PDF are still encrypted --
   * and the password used at upload is deliberately never persisted, so it has to be given again.
   *
   * This tries without one first, unlike the upload flow, which offers the field up front. The
   * difference is what a failed attempt costs: on upload it means sending the whole file over the
   * network for nothing, so asking first is cheaper; here the bytes are already on the server, so
   * "just try it" is one small request. Every statement that never needed a password — the
   * majority — keeps its single-click re-import, and only a protected one sees the prompt.
   */
  async function handleReimport(statement: StatementSummary, password?: string) {
    setBusyId(statement.id);
    setError(null);
    try {
      const result = await statementImportsApi.reimport(statement.id, password);
      setPasswordPrompt(null);
      navigateToReimport(navigate, {
        reimportId: statement.id, staging: result.staging, accountId: result.accountId,
        accountName: result.accountName, password,
      });
    } catch (e: any) {
      const code = e.response?.data?.errorCode;
      if (code === PDF_PASSWORD_REQUIRED || code === PDF_PASSWORD_INVALID) {
        // Not a re-import failure and not reported as one -- the statement is intact, it just
        // hasn't been unlocked. Keeping the prompt open on INVALID preserves what was typed, so a
        // one-character typo is a correction rather than a retype.
        setPasswordPrompt({ statement, wrong: code === PDF_PASSWORD_INVALID });
      } else {
        setError(e.response?.data?.message ?? 'Could not re-import this statement.');
      }
    } finally {
      setBusyId(null);
    }
  }

  async function handleDelete(statement: StatementSummary) {
    setBusyId(statement.id);
    setError(null);
    try {
      await statementImportsApi.remove(statement.id);
      invalidateEverything();
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not delete this statement import.');
    } finally {
      setBusyId(null);
    }
  }

  async function handleDownload(statement: StatementSummary) {
    try {
      await statementImportsApi.downloadFile(statement.id, statement.fileName);
    } catch {
      setError('Could not download the original file.');
    }
  }

  if (isLoading) {
    return (
      <div className="space-y-6">
        <Hero />
        <Skeleton.Region label="Loading statement history" className="grid grid-cols-2 lg:grid-cols-4 gap-4">
          {[0, 1, 2, 3].map((i) => <Skeleton.Card key={i} />)}
        </Skeleton.Region>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <Hero />

      {error && <p className="text-danger text-sm">{error}</p>}

      {accountGroups.length > 0 && (
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
          <StatCard
            label="Total Statements"
            value={String(stats.totalStatements)}
            caption={`Across ${stats.bankCount} bank${stats.bankCount === 1 ? '' : 's'}`}
            icon={FileText}
            iconBg="bg-green-100"
            iconColor="text-green-600"
          />
          <StatCard
            label="Total Transactions"
            value={stats.totalTransactions.toLocaleString('en-IN')}
            caption="Extracted from statements"
            icon={UploadCloud}
            iconBg="bg-blue-100"
            iconColor="text-blue-600"
          />
          <StatCard
            label="Oldest Statement"
            value={stats.oldest ? formatDate(stats.oldest.date, { year: 'numeric', month: 'short' }) : '—'}
            caption={stats.oldest ? stats.oldest.bankName : 'No dated statements yet'}
            icon={CalendarDays}
            iconBg="bg-purple-100"
            iconColor="text-purple-600"
          />
          <StatCard
            label="Latest Statement"
            value={stats.latest ? formatDate(stats.latest.date, { year: 'numeric', month: 'short' }) : '—'}
            caption={stats.latest ? stats.latest.bankName : 'No dated statements yet'}
            icon={History}
            iconBg="bg-orange-100"
            iconColor="text-orange-600"
          />
        </div>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-[1fr_300px] gap-6 items-start">
        <div className="min-w-0 space-y-4">
          {accountGroups.length > 0 && (
            <FinoraCard padding="sm">
              <div className="grid grid-cols-1 md:grid-cols-5 gap-2">
                <div className="relative md:col-span-2">
                  <Search size={15} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted pointer-events-none" />
                  <input
                    placeholder="Search by bank, file name, or period…"
                    value={search}
                    onChange={(e) => setSearch(e.target.value)}
                    className="w-full bg-card text-ink border border-border rounded-lg pl-8 pr-3 py-2 text-sm"
                  />
                </div>
                <select
                  value={bankFilter}
                  onChange={(e) => setBankFilter(e.target.value)}
                  className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm"
                >
                  <option value="">All Banks</option>
                  {bankOptions.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
                </select>
                <input
                  type="date"
                  value={dateFrom}
                  aria-label="From date"
                  onChange={(e) => setDateFrom(e.target.value)}
                  className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm"
                />
                <div className="flex gap-2">
                  <input
                    type="date"
                    value={dateTo}
                    aria-label="To date"
                    onChange={(e) => setDateTo(e.target.value)}
                    className="flex-1 min-w-0 bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm"
                  />
                  <button
                    type="button"
                    onClick={clearFilters}
                    disabled={!hasActiveFilters}
                    title="Clear all filters"
                    className="flex-shrink-0 flex items-center gap-1.5 border border-border rounded-lg px-3 py-2 text-sm text-muted hover:text-ink hover:bg-bg disabled:opacity-40 disabled:cursor-not-allowed"
                  >
                    <FilterX size={14} />
                  </button>
                </div>
              </div>
            </FinoraCard>
          )}

          {!!failures?.length && <FailedImportsSection failures={failures} />}

          {!!inProgressJobs.length && <RecentImportsSection jobs={inProgressJobs} />}

          {accountGroups.length === 0 ? (
            <FinoraCard padding="lg">
              <EmptyState
                icon={FileText}
                iconBg="bg-blue-100"
                iconColor="text-blue-600"
                title="No statements imported yet"
                desc="Import a bank or credit card statement to get started."
                cta={
                  <button
                    onClick={() => navigate('/app/import')}
                    className="bg-primary text-on-primary text-xs font-semibold rounded-lg px-4 py-2"
                  >
                    Import a Statement
                  </button>
                }
              />
            </FinoraCard>
          ) : filteredGroups.length === 0 ? (
            <FinoraCard padding="lg">
              <EmptyState
                icon={Search}
                iconBg="bg-bg"
                iconColor="text-muted"
                title="No statements match your filters"
                desc="Try a different bank, search term, or date range."
                cta={
                  <button
                    onClick={clearFilters}
                    className="border border-border rounded-lg px-4 py-2 text-xs font-semibold text-ink hover:bg-bg"
                  >
                    Clear Filters
                  </button>
                }
              />
            </FinoraCard>
          ) : (
            filteredGroups.map((group: AccountStatementGroup) => {
              const isOpen = openAccounts.has(group.accountId) || filteredGroups.length === 1;
          return (
            <div key={group.accountId} className="bg-card rounded-xl2 shadow-card border border-border overflow-hidden">
              <button
                type="button"
                onClick={() => toggleAccount(group.accountId)}
                className="w-full flex items-center justify-between px-5 py-4 text-left"
              >
                <div className="flex items-center gap-2.5">
                  {isOpen ? <ChevronDown size={16} className="text-muted" /> : <ChevronRight size={16} className="text-muted" />}
                  <BankLogo bank={group.bank} size={24} />
                  <span className="font-semibold text-ink text-sm">{group.accountName}</span>
                  <span className="text-[10px] uppercase text-muted bg-bg border border-border rounded px-1.5 py-0.5">
                    {group.accountType.replace('_', ' ')}
                  </span>
                  {group.deleted && (
                    <span
                      className="text-[10px] uppercase text-danger bg-danger-bg border border-danger/30 rounded px-1.5 py-0.5"
                      title="This account was deleted. Its statement history stays visible for 7 days from the deletion date, then disappears."
                    >
                      Deleted{group.deletedAt ? ` · ${daysUntilRemoved(group.deletedAt)}` : ''}
                    </span>
                  )}
                </div>
                <span className="text-xs text-muted">{group.statements.length} statement{group.statements.length === 1 ? '' : 's'}</span>
              </button>

              {isOpen && (
                <div className="border-t border-border divide-y divide-border">
                  {group.statements.map((s) => (
                    <div key={s.id} className="px-5 py-3.5 flex items-center justify-between gap-4 flex-wrap">
                      <div className="min-w-0">
                        <p className="text-sm font-medium text-ink truncate">{s.fileName}</p>
                        <p className="text-xs text-muted">
                          {s.statementPeriodStart ? `${fmtDate(s.statementPeriodStart)} – ${fmtDate(s.statementPeriodEnd)}` : 'Period unknown'}
                          {' · '}Imported {fmtDate(s.importedAt)}
                          {' · '}{s.transactionsImported} txn{s.transactionsImported === 1 ? '' : 's'}
                          {s.transactionsSkipped > 0 && ` (${s.transactionsSkipped} skipped)`}
                        </p>
                        <p className="text-xs text-muted">
                          Opening {fmt(s.openingBalance)} → Closing {fmt(s.closingBalance)}
                          {s.duplicateCount > 0 && (
                            <span className="text-warning">
                              {' · '}{s.duplicateCount} duplicate{s.duplicateCount === 1 ? '' : 's'} flagged
                            </span>
                          )}
                        </p>
                        {/* Credit-card statement entity, roadmap item 6 -- only present for a
                            credit-card statement whose payment-summary panel was found. */}
                        {s.totalAmountDue !== null && (
                          <p className="text-xs text-muted">
                            Total due {fmt(s.totalAmountDue)}
                            {s.paymentDueDate && ` · Due ${fmtDate(s.paymentDueDate)}`}
                          </p>
                        )}
                      </div>
                      <div className="flex items-center gap-1.5 flex-shrink-0">
                        <ActionButton title="View Import Summary" onClick={() => setViewing({ mode: 'summary', statement: s })}>
                          <Eye size={14} />
                        </ActionButton>
                        <ActionButton title="View Imported Transactions" onClick={() => setViewing({ mode: 'transactions', statement: s })}>
                          <ListChecks size={14} />
                        </ActionButton>
                        <ActionButton
                          title={group.deleted ? "Re-import isn't available — the account this statement belongs to has been deleted" : 'Re-import Statement'}
                          onClick={() => handleReimport(s)}
                          busy={busyId === s.id}
                          disabled={group.deleted}
                        >
                          <RefreshCw size={14} />
                        </ActionButton>
                        <ActionButton title="Download Original File" onClick={() => handleDownload(s)}>
                          <Download size={14} />
                        </ActionButton>
                        <ActionButton title="Delete Statement Import" onClick={() => setConfirmDelete(s)} busy={busyId === s.id} danger>
                          <Trash2 size={14} />
                        </ActionButton>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          );
        })
          )}
        </div>

        <div className="space-y-4">
          <FinoraCard>
            <h2 className="font-semibold text-ink text-sm mb-4">Quick Actions</h2>
            <div className="grid grid-cols-2 gap-3">
              <QuickActionCard icon={UploadCloud} label="Import Statement" to="/app/import" />
              <QuickActionCard icon={Landmark} label="Manage Banks" to="/app/accounts" />
            </div>
          </FinoraCard>

          <FinoraCard className="text-center">
            <div className="w-10 h-10 rounded-full bg-primary-light flex items-center justify-center mx-auto mb-3">
              <Sparkles size={16} className="text-primary" />
            </div>
            <p className="text-sm font-semibold text-ink mb-1">Your financial history, always ready.</p>
            <p className="text-xs text-muted">Revisit, analyze, and make smarter decisions.</p>
          </FinoraCard>
        </div>
      </div>

      {viewing && <StatementDetailModal viewing={viewing} onClose={() => setViewing(null)} />}

      {confirmDelete && (
        <ConfirmDialog
          title={`Delete "${confirmDelete.fileName}"?`}
          message={`This removes only the ${confirmDelete.transactionsImported} transaction(s) it imported — nothing else.`}
          confirmLabel="Delete"
          danger
          onConfirm={() => {
            const statement = confirmDelete;
            setConfirmDelete(null);
            void handleDelete(statement);
          }}
          onCancel={() => setConfirmDelete(null)}
        />
      )}

      {passwordPrompt && (
        <ReimportPasswordModal
          prompt={passwordPrompt}
          busy={busyId === passwordPrompt.statement.id}
          onSubmit={(password) => void handleReimport(passwordPrompt.statement, password)}
          onClose={() => setPasswordPrompt(null)}
        />
      )}
    </div>
  );
}

/** Page header -- eyebrow + headline + illustration, same pattern Ledger.tsx's redesign already
 *  established. Unlike Ledger, this page DOES have a real illustration asset (generated for this
 *  redesign, in the app's actual graphite/cream/navy palette, not the mockup's purple) -- so
 *  unlike Ledger's own "no invented decoration" call, showing it here isn't fabricating anything. */
function Hero() {
  return (
    <div className="flex items-center justify-between gap-6 flex-wrap">
      <div className="max-w-xl">
        <p className="text-[11px] font-semibold uppercase tracking-widest text-muted mb-1">Statement History</p>
        <h1 className="text-2xl md:text-3xl font-bold text-ink font-display">
          All your statements, <span className="text-primary">in one place</span>
        </h1>
        <p className="text-sm text-muted mt-1">
          View, download, and manage all your imported statements. Revisit anytime, stay organized.
        </p>
      </div>
      <img
        src={heroIllustration}
        alt=""
        aria-hidden="true"
        className="hidden md:block w-40 h-auto flex-shrink-0"
      />
    </div>
  );
}

/**
 * Dashboard's/Ledger's KPI-tile shape (icon badge, label, big value) reused here with a plain
 * caption line instead of a delta -- MetricCard's delta/deltaLabel contract renders a leading "—"
 * for a KPI with no delta concept, which reads as "not available" rather than a normal subtitle
 * like "Across 5 banks", so a plain variant fits this row better than forcing that contract.
 */
function StatCard({
  label, value, caption, icon: Icon, iconBg, iconColor,
}: {
  label: string; value: string; caption: string; icon: LucideIcon; iconBg: string; iconColor: string;
}) {
  return (
    <FinoraCard>
      <div className="flex items-start justify-between mb-3">
        <p className="text-sm text-muted">{label}</p>
        <div className={`w-9 h-9 rounded-full ${iconBg} flex items-center justify-center flex-shrink-0`}>
          <Icon size={17} className={iconColor} />
        </div>
      </div>
      <p className="text-2xl font-bold text-ink mb-1">{value}</p>
      <p className="text-xs text-muted">{caption}</p>
    </FinoraCard>
  );
}

/**
 * Asks for the document password of a protected PDF being re-imported.
 *
 * Mounted only after the server has said one is needed, so unlike the upload flow's panel there is
 * no "leave blank" case to explain -- reaching this modal already means blank was tried and
 * rejected. Submitting is therefore gated on a non-empty value.
 */
function ReimportPasswordModal({
  prompt, busy, onSubmit, onClose,
}: {
  prompt: { statement: StatementSummary; wrong: boolean };
  busy: boolean;
  onSubmit: (password: string) => void;
  onClose: () => void;
}) {
  const [password, setPassword] = useState('');

  return (
    <>
      <div className="fixed inset-0 bg-black/40 z-30" onClick={onClose} />
      <div className="fixed inset-0 z-40 flex items-center justify-center p-4 pointer-events-none">
        <form
          data-testid="reimport-password-modal"
          className="bg-card border border-border rounded-xl2 shadow-soft w-full max-w-sm p-5 pointer-events-auto space-y-4"
          onSubmit={(e) => {
            e.preventDefault();
            if (password && !busy) onSubmit(password);
          }}
        >
          <div className="flex items-start justify-between gap-3">
            <h3 className="font-semibold text-ink text-sm">Unlock this statement</h3>
            <button type="button" onClick={onClose} className="text-muted hover:text-ink shrink-0">
              <X size={18} />
            </button>
          </div>

          <p className="text-xs text-muted">
            <span className="text-ink font-medium break-all">{prompt.statement.fileName}</span> is
            password protected. Fynora doesn't store statement passwords, so re-importing needs it again.
          </p>

          <div>
            <label htmlFor="reimport-password" className="block text-sm font-medium text-ink mb-1">
              Statement password
            </label>
            <input
              id="reimport-password"
              type="password"
              // The bank's password for one document, not a Fynora credential -- it doesn't belong
              // in the user's password manager next to real logins.
              autoComplete="off"
              autoFocus
              className="w-full border border-border rounded px-3 py-2 text-sm"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              disabled={busy}
              aria-describedby="reimport-password-help"
            />
            <p
              id="reimport-password-help"
              className={`text-xs mt-1 ${prompt.wrong ? 'text-danger' : 'text-muted'}`}
              role={prompt.wrong ? 'alert' : undefined}
            >
              {prompt.wrong
                ? "That password didn't open this statement — check it and try again."
                : 'The password your bank uses for this statement.'}
            </p>
          </div>

          <button
            type="submit"
            disabled={!password || busy}
            className="w-full bg-primary text-on-primary rounded px-4 py-2 text-sm font-medium disabled:opacity-40"
          >
            {busy ? 'Unlocking…' : 'Re-import statement'}
          </button>
        </form>
      </div>
    </>
  );
}

/**
 * "Your recent failed imports" -- Premium Import Reliability v1, §2.1, with §2.5's "Try again"
 * action. A failed sync import has no bytes retained (that's Sprint 4's still-gated
 * retry-without-re-upload work), so this cannot replay the original upload the way confirmed
 * "Reimport" does -- it can only send the person back to Import with the file name and curated
 * failure reason as context, to pick the file again themselves. The `RefreshCw` icon on "Try
 * again" is §3.3's consistency pass -- the same icon confirmed reimport's `ActionButton` already
 * uses, and now Import.tsx's staged "Continue Import" too, so all three retry paths read as one
 * pattern rather than three unrelated features.
 */
function FailedImportsSection({ failures }: { failures: ImportFailureSummary[] }) {
  const navigate = useNavigate();
  const [expanded, setExpanded] = useState(true);

  return (
    <div className="bg-card rounded-xl2 shadow-card border border-danger/30 overflow-hidden">
      <button
        type="button"
        onClick={() => setExpanded((open) => !open)}
        aria-expanded={expanded}
        className={`w-full flex items-center justify-between gap-2 px-5 py-4 text-left ${expanded ? 'border-b border-border' : ''}`}
      >
        <div className="flex items-center gap-2">
          <AlertTriangle size={16} className="text-danger" />
          <div>
            <h2 className="font-semibold text-ink text-sm">Failed Imports</h2>
            <p className="text-xs text-muted">Statements Fynora could not import.</p>
          </div>
        </div>
        {expanded
          ? <ChevronDown size={16} className="text-muted flex-shrink-0" />
          : <ChevronRight size={16} className="text-muted flex-shrink-0" />}
      </button>
      {expanded && (
      <div className="divide-y divide-border">
        {failures.map((f) => (
          <div key={f.reference} className="px-5 py-3.5">
            <div className="flex items-center justify-between gap-4 flex-wrap">
              <p className="text-sm font-medium text-ink truncate">{f.fileName}</p>
              <p className="text-xs text-muted flex-shrink-0">{fmtDate(f.createdAt)}</p>
            </div>
            <p className="text-xs text-muted mt-1">{messageFor(f.failureCode)}</p>
            <button
              type="button"
              onClick={() => navigateToRetryFailedImport(navigate, f.fileName, f.failureCode)}
              className="mt-1.5 text-xs font-medium text-primary hover:underline flex items-center gap-1"
            >
              <RefreshCw size={12} />
              Try again
            </button>
          </div>
        ))}
      </div>
      )}
    </div>
  );
}

/**
 * The entry point to the self-service import detail page (Premium Import Reliability v1, §3.2) --
 * without this, `/app/imports/:jobId` is reachable only by typing a UUID into the address bar,
 * which does not answer "what happened to my import yesterday". `jobs` here has already excluded
 * COMPLETED (see the caller's own comment on why).
 */
function RecentImportsSection({ jobs }: { jobs: ImportJobProgress[] }) {
  // A hook call, not a prop threaded down from the page -- StatementHistory already has its own
  // useNavigate() for its own buttons, and this one needs nothing from the caller that reaching
  // for the hook directly doesn't already give it.
  const navigate = useNavigate();
  const [expanded, setExpanded] = useState(true);

  return (
    <div className="bg-card rounded-xl2 shadow-card border border-border overflow-hidden">
      <button
        type="button"
        onClick={() => setExpanded((open) => !open)}
        aria-expanded={expanded}
        className={`w-full flex items-center justify-between gap-2 px-5 py-4 text-left ${expanded ? 'border-b border-border' : ''}`}
      >
        <div className="flex items-center gap-2">
          <Clock size={16} className="text-muted" />
          <div>
            <h2 className="font-semibold text-ink text-sm">Recent Imports</h2>
            <p className="text-xs text-muted">Statements still processing, or that didn't finish.</p>
          </div>
        </div>
        {expanded
          ? <ChevronDown size={16} className="text-muted flex-shrink-0" />
          : <ChevronRight size={16} className="text-muted flex-shrink-0" />}
      </button>
      {expanded && (
      <div className="divide-y divide-border">
        {jobs.map((job) => (
          <button
            key={job.jobId}
            type="button"
            onClick={() => void navigate(`/app/imports/${job.jobId}`)}
            className="w-full text-left px-5 py-3.5 hover:bg-bg"
          >
            <div className="flex items-center justify-between gap-4 flex-wrap">
              <p className="text-sm font-medium text-ink truncate">{job.fileName}</p>
              <p className="text-xs text-muted flex-shrink-0">{fmtDate(job.createdAt)}</p>
            </div>
            <p className="text-xs text-muted mt-1">{jobLabel(job)}</p>
          </button>
        ))}
      </div>
      )}
    </div>
  );
}

function ActionButton({
  title, onClick, children, busy, danger, disabled,
}: {
  title: string; onClick: () => void; children: ReactNode; busy?: boolean; danger?: boolean; disabled?: boolean;
}) {
  return (
    <button
      type="button"
      title={title}
      onClick={onClick}
      disabled={busy || disabled}
      className={`w-8 h-8 rounded-lg border border-border flex items-center justify-center hover:bg-bg disabled:opacity-40 ${
        danger ? 'text-danger hover:bg-danger-bg' : 'text-muted hover:text-ink'
      }`}
    >
      {children}
    </button>
  );
}

function StatementDetailModal({
  viewing, onClose,
}: {
  viewing: { mode: 'summary' | 'transactions'; statement: StatementSummary };
  onClose: () => void;
}) {
  const { data: transactions, isLoading } = useQuery({
    queryKey: ['statement-import-transactions', viewing.statement.id],
    queryFn: () => statementImportsApi.transactions(viewing.statement.id),
    enabled: viewing.mode === 'transactions',
  });

  return (
    <>
      <div className="fixed inset-0 bg-black/40 z-30" onClick={onClose} />
      <div className="fixed inset-0 z-40 flex items-center justify-center p-4 pointer-events-none">
        <div className="bg-card border border-border rounded-xl2 shadow-soft w-full max-w-lg max-h-[80vh] overflow-y-auto p-5 pointer-events-auto">
          <div className="flex items-center justify-between mb-4">
            <h3 className="font-semibold text-ink text-sm">
              {viewing.mode === 'summary' ? 'Import Summary' : 'Imported Transactions'} — {viewing.statement.fileName}
            </h3>
            <button type="button" onClick={onClose} className="text-muted hover:text-ink">
              <X size={18} />
            </button>
          </div>

          {viewing.mode === 'summary' ? (
            <div className="grid grid-cols-2 gap-3 text-sm">
              <Field label="Statement period">
                {viewing.statement.statementPeriodStart
                  ? `${fmtDate(viewing.statement.statementPeriodStart)} – ${fmtDate(viewing.statement.statementPeriodEnd)}`
                  : 'Unknown'}
              </Field>
              <Field label="Imported">{fmtDate(viewing.statement.importedAt)}</Field>
              <Field label="Opening balance">{fmt(viewing.statement.openingBalance)}</Field>
              <Field label="Closing balance">{fmt(viewing.statement.closingBalance)}</Field>
              {viewing.statement.totalAmountDue !== null && (
                <Field label="Total amount due">{fmt(viewing.statement.totalAmountDue)}</Field>
              )}
              {viewing.statement.paymentDueDate && (
                <Field label="Payment due date">{fmtDate(viewing.statement.paymentDueDate)}</Field>
              )}
              <Field label="Transactions imported">{viewing.statement.transactionsImported}</Field>
              <Field label="Transactions skipped">{viewing.statement.transactionsSkipped}</Field>
              <Field label="Duplicates flagged">{viewing.statement.duplicateCount}</Field>
            </div>
          ) : isLoading ? (
            <p className="text-sm text-muted">Loading…</p>
          ) : !transactions || transactions.length === 0 ? (
            <p className="text-sm text-muted italic">No transactions found for this statement.</p>
          ) : (
            <table className="w-full text-xs">
              <thead>
                <tr className="text-left text-[10px] uppercase text-muted border-b border-border">
                  <th className="py-1.5">Date</th><th className="py-1.5">Description</th>
                  <th className="py-1.5">Category</th><th className="py-1.5 text-right">Amount</th>
                </tr>
              </thead>
              <tbody>
                {transactions.map((t: Transaction) => (
                  <tr key={t.id} className="border-b border-border">
                    <td className="py-1.5">{t.date}</td>
                    <td className="py-1.5">{t.description || t.merchant}</td>
                    <td className="py-1.5 text-muted">{t.categoryName}</td>
                    <td className={`py-1.5 text-right font-medium ${t.type === 'INCOME' ? 'text-success' : 'text-danger'}`}>
                      {t.type === 'INCOME' ? '+' : '-'}{fmt(t.amount)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <p className="text-[11px] uppercase text-muted">{label}</p>
      <p className="text-ink font-medium">{children}</p>
    </div>
  );
}
