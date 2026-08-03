import { useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ChevronDown, ChevronRight, FileText, Download, RefreshCw, Trash2, Eye, ListChecks, X,
} from 'lucide-react';
import { statementImportsApi } from '../api/endpoints';
import { BankLogo } from '../components/BankLogo';
import type { AccountStatementGroup, StatementSummary, Transaction } from '../types';
import { formatDate } from '../utils/date';

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
  const [error, setError] = useState<string | null>(null);

  const { data: groups, isLoading } = useQuery({
    queryKey: ['statement-imports'],
    queryFn: () => statementImportsApi.listGroupedByAccount(),
  });

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

  async function handleReimport(statement: StatementSummary) {
    setBusyId(statement.id);
    setError(null);
    try {
      const result = await statementImportsApi.reimport(statement.id);
      navigate('/app/import', {
        state: { reimportId: statement.id, staging: result.staging, accountId: result.accountId, accountName: result.accountName },
      });
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not re-import this statement.');
    } finally {
      setBusyId(null);
    }
  }

  async function handleDelete(statement: StatementSummary) {
    if (!confirm(`Delete "${statement.fileName}"? This removes only the ${statement.transactionsImported} transaction(s) it imported — nothing else.`)) {
      return;
    }
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

  if (isLoading) return <p className="text-muted">Loading…</p>;

  const accountGroups = groups ?? [];

  return (
    <div className="space-y-4">
      <div className="mb-2">
        <h1 className="text-xl font-bold text-ink">Statement History</h1>
        <p className="text-sm text-muted">
          Every imported statement, organized by account — not by which file you uploaded.
        </p>
      </div>

      {error && <p className="text-danger text-sm">{error}</p>}

      {accountGroups.length === 0 ? (
        <div className="bg-card rounded-xl2 shadow-card border border-border p-8 text-center">
          <FileText size={24} className="mx-auto mb-2 text-muted" />
          <p className="text-sm text-muted">No statements imported yet.</p>
          <button
            onClick={() => navigate('/app/import')}
            className="mt-3 bg-primary text-white text-xs font-semibold rounded-lg px-4 py-2"
          >
            Import a Statement
          </button>
        </div>
      ) : (
        accountGroups.map((group: AccountStatementGroup) => {
          const isOpen = openAccounts.has(group.accountId) || accountGroups.length === 1;
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
                          {' · '}<span className="uppercase text-[10px]">{s.status}</span>
                          {s.duplicateCount > 0 && (
                            <span className="text-warning">
                              {' · '}{s.duplicateCount} duplicate{s.duplicateCount === 1 ? '' : 's'} flagged
                            </span>
                          )}
                        </p>
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
                        <ActionButton title="Delete Statement Import" onClick={() => handleDelete(s)} busy={busyId === s.id} danger>
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

      {viewing && <StatementDetailModal viewing={viewing} onClose={() => setViewing(null)} />}
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
              <Field label="Transactions imported">{viewing.statement.transactionsImported}</Field>
              <Field label="Transactions skipped">{viewing.statement.transactionsSkipped}</Field>
              <Field label="Duplicates flagged">{viewing.statement.duplicateCount}</Field>
              <Field label="Status">{viewing.statement.status}</Field>
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
