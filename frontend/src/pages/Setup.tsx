import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import {
  UploadCloud, Eye, EyeOff, User, CreditCard, Landmark, Wallet, Calendar, CheckCircle2,
  MoreVertical, Pencil, Trash2, FileText, Plus,
} from 'lucide-react';
import { accountsApi, banksApi } from '../api/endpoints';
import { BankLogo } from '../components/BankLogo';
import type { Account, BankInfo } from '../types';

const TYPE_LABEL: Record<Account['accountType'], string> = {
  SAVINGS: 'Savings Account',
  CREDIT_CARD: 'Credit Card',
  WALLET: 'Wallet',
  INVESTMENT: 'Investment',
};

function fmt(n: number | null | undefined) {
  if (n === null || n === undefined) return 'N/A';
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

function fmtDate(d: string | null) {
  if (!d) return null;
  return new Date(d).toLocaleDateString('en-IN', { year: 'numeric', month: 'short', day: 'numeric' });
}

// How long a revealed account number stays visible before auto-hiding again -- a common banking
// UX pattern (see brief: "automatically mask it again after a short timeout"). Also re-masks
// immediately on unmount, i.e. the instant the user navigates away from this page, since the
// `revealed` state itself simply stops existing at that point.
const AUTO_REMASK_MS = 8000;

/** A single label + value cell in the account card's info grid -- kept as one component so
 *  every field (holder name, balance, last imported, ...) gets identical spacing/typography
 *  regardless of which bank the card belongs to. */
function InfoField({ icon: Icon, label, children }: { icon: typeof User; label: string; children: ReactNode }) {
  return (
    <div className="flex items-start gap-2.5">
      <Icon size={15} className="text-muted mt-0.5 flex-shrink-0" />
      <div className="min-w-0">
        <p className="text-[11px] text-muted uppercase tracking-wide">{label}</p>
        <div className="text-sm font-semibold text-ink truncate">{children}</div>
      </div>
    </div>
  );
}

export default function Setup() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [banks, setBanks] = useState<BankInfo[]>([]);
  const [name, setName] = useState('');
  const [type, setType] = useState<Account['accountType']>('SAVINGS');
  const [balance, setBalance] = useState('');
  const [limit, setLimit] = useState('');
  const [dueDate, setDueDate] = useState('');
  const [holderName, setHolderName] = useState('');
  const [accountNumber, setAccountNumber] = useState('');
  const [bankId, setBankId] = useState('');
  const [bankSearch, setBankSearch] = useState('');
  const [branchName, setBranchName] = useState('');
  const [ifscCode, setIfscCode] = useState('');

  // Which accounts currently have their masked number revealed — a plain UI toggle, not a
  // security boundary: what's stored server-side (accountNumberMasked) was already masked
  // before it ever reached us, either by the bank's own export or by CsvImportService's
  // maskAccountNumber(). Finora never stores a true, full account number at all -- revealing
  // just swaps the hidden "•••• ••••" placeholder for that already-masked value (e.g.
  // "••••4802") instead of leaving even that much visible by default on a shared screen.
  const [revealed, setRevealed] = useState<Set<string>>(new Set());
  const remaskTimers = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());

  useEffect(() => {
    // Every pending auto-remask timer is dropped the instant this page unmounts -- combined
    // with `revealed` living only in this component's state, that's what satisfies "mask it
    // again ... when the user leaves the page" with no extra code.
    const timers = remaskTimers.current;
    return () => timers.forEach(clearTimeout);
  }, []);

  function toggleRevealed(id: string) {
    setRevealed((prev) => {
      const next = new Set(prev);
      const existingTimer = remaskTimers.current.get(id);
      if (existingTimer) { clearTimeout(existingTimer); remaskTimers.current.delete(id); }

      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
        const timer = setTimeout(() => {
          setRevealed((cur) => { const copy = new Set(cur); copy.delete(id); return copy; });
          remaskTimers.current.delete(id);
        }, AUTO_REMASK_MS);
        remaskTimers.current.set(id, timer);
      }
      return next;
    });
  }

  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [menuOpenFor, setMenuOpenFor] = useState<string | null>(null);
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameValue, setRenameValue] = useState('');

  function load() {
    accountsApi.list().then(setAccounts).catch(() => setError('Could not load accounts.'));
  }
  useEffect(load, []);
  useEffect(() => { banksApi.list().then(setBanks).catch(() => setBanks([])); }, []);

  async function addAccount() {
    if (!name) return;
    setError(null);
    setSaving(true);
    try {
      await accountsApi.create({
        name, accountType: type, balance: parseFloat(balance || '0'),
        creditLimit: type === 'CREDIT_CARD' ? parseFloat(limit || '0') : undefined,
        dueDate: type === 'CREDIT_CARD' ? dueDate || undefined : undefined,
        accountHolderName: holderName.trim() || undefined,
        accountNumberMasked: accountNumber.trim() || undefined,
        bankId: bankId || undefined,
        branchName: branchName.trim() || undefined,
        ifscCode: ifscCode.trim() || undefined,
      });
      setName(''); setBalance(''); setLimit(''); setDueDate(''); setHolderName(''); setAccountNumber('');
      setBankId(''); setBankSearch(''); setBranchName(''); setIfscCode('');
      load();
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not add this account. Try again.');
    } finally {
      setSaving(false);
    }
  }

  async function remove(id: string) {
    if (!confirm('Delete this account?')) return;
    setMenuOpenFor(null);
    try {
      await accountsApi.remove(id);
      load();
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not delete this account. Try again.');
    }
  }

  function startRename(a: Account) {
    setMenuOpenFor(null);
    setRenamingId(a.id);
    setRenameValue(a.name);
  }

  // Removing a focused element from the DOM (exactly what happens the instant setRenamingId(null)
  // swaps the input back to plain text) makes the browser fire a native blur on it -- which would
  // otherwise still reach this input's onBlur and silently re-commit the rename right after the
  // user explicitly pressed Escape to cancel it. This ref is set immediately before that
  // unmount so submitRename can tell "real blur" (commit) apart from "blur caused by Escape's own
  // unmount" (must not commit) and skip the latter.
  const cancellingRename = useRef(false);

  function cancelRename() {
    cancellingRename.current = true;
    setRenamingId(null);
  }

  async function submitRename(id: string) {
    if (cancellingRename.current) { cancellingRename.current = false; return; }
    const trimmed = renameValue.trim();
    if (!trimmed) { setRenamingId(null); return; }
    try {
      await accountsApi.update(id, { name: trimmed, accountType: accounts.find((a) => a.id === id)!.accountType });
      setRenamingId(null);
      load();
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not rename this account. Try again.');
      setRenamingId(null);
    }
  }

  return (
    <div className="space-y-4">
      {/* Statement import is the primary way an account is meant to get onto this page —
          Finora detects the account details from the file and creates it automatically.
          Everything below this is the manual fallback for accounts you'd rather set up by hand. */}
      <div className="bg-primary-light border border-primary/20 rounded-xl2 p-5 flex items-center justify-between gap-4 flex-wrap">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-full bg-card flex items-center justify-center flex-shrink-0">
            <UploadCloud size={18} className="text-primary" />
          </div>
          <div>
            <p className="text-sm font-semibold text-ink">Import a bank or credit card statement</p>
            <p className="text-xs text-muted">Finora detects the bank, account, and transactions automatically — no manual setup needed.</p>
          </div>
        </div>
        <Link to="/app/import" className="bg-primary text-white text-xs font-semibold rounded-lg px-4 py-2.5 flex-shrink-0">
          Import Statement
        </Link>
      </div>

      <div className="space-y-3">
        {accounts.length === 0 ? (
          <div className="bg-card rounded-xl2 border border-border shadow-card p-8 text-center">
            <p className="text-sm text-muted italic">No accounts yet — import a statement above, or add one manually below.</p>
          </div>
        ) : (
          accounts.map((a) => {
            const isRevealed = revealed.has(a.id);
            const lastImported = fmtDate(a.lastImportedAt);
            const periodStart = fmtDate(a.lastStatementPeriodStart);
            const periodEnd = fmtDate(a.lastStatementPeriodEnd);

            return (
              <div key={a.id} className="bg-card rounded-xl2 border border-border shadow-card overflow-hidden">
                <div className="p-5 flex items-center gap-4 flex-wrap border-b border-border">
                  <BankLogo bank={a.bank} size={44} />
                  <div className="flex-1 min-w-0">
                    {renamingId === a.id ? (
                      <input
                        autoFocus
                        value={renameValue}
                        onChange={(e) => setRenameValue(e.target.value)}
                        onBlur={() => submitRename(a.id)}
                        onKeyDown={(e) => { if (e.key === 'Enter') void submitRename(a.id); if (e.key === 'Escape') cancelRename(); }}
                        className="bg-card text-ink border border-primary/40 rounded-lg px-2 py-1 text-sm font-semibold w-full max-w-xs"
                      />
                    ) : (
                      <>
                        {/* Nickname on top, official bank name underneath -- exactly the
                            "Salary Account / Punjab National Bank" layout from the brief, so
                            accounts sharing a bank are still easy to tell apart at a glance. The
                            bank line is skipped when it's just the same text as the nickname
                            (a brand-new account nobody's renamed yet) or unrecognized (OTHER). */}
                        <h3 className="font-semibold text-ink truncate">{a.name}</h3>
                        {a.bank.officialName && a.bank.officialName !== a.name && (
                          <p className="text-xs text-muted truncate">{a.bank.officialName}</p>
                        )}
                      </>
                    )}
                    <p className="text-xs text-muted mt-0.5 flex items-center gap-1.5 flex-wrap">
                      <span className="text-[10px] uppercase tracking-wide bg-bg text-muted px-2 py-0.5 rounded-full border border-border">
                        {TYPE_LABEL[a.accountType]}
                      </span>
                      {/* Always the generic placeholder here, never the real masked digits --
                          the eye-toggle in the detail grid below is the only place those are
                          shown, and only after an explicit click. */}
                      {a.accountNumberMasked && <span>•••• ••••</span>}
                      <span className="inline-flex items-center gap-1">
                        <CheckCircle2 size={11} className="text-success" /> {a.status === 'ACTIVE' ? 'Active' : a.status}
                      </span>
                    </p>
                  </div>
                  <p className="text-lg font-bold text-ink flex-shrink-0">{fmt(a.balance)}</p>
                  <div className="flex items-center gap-2 flex-shrink-0">
                    <Link to="/app/statements" className="text-primary border border-primary/30 rounded-lg px-3 py-1.5 text-xs font-medium">
                      Statements
                    </Link>
                    <Link to="/app/import" className="bg-primary text-white rounded-lg px-3 py-1.5 text-xs font-medium">
                      Import New
                    </Link>
                    <div className="relative">
                      <button
                        onClick={() => setMenuOpenFor(menuOpenFor === a.id ? null : a.id)}
                        className="text-muted hover:text-ink p-1.5"
                        aria-label="More actions"
                      >
                        <MoreVertical size={16} />
                      </button>
                      {menuOpenFor === a.id && (
                        <>
                          {/* Bug fix: unlike the identical-purpose menus in Sidebar.tsx/TopBar.tsx,
                              this menu had no outside-click overlay and no Escape handling --
                              clicking anywhere else on the page (other than the toggle button or a
                              menu item) left it open indefinitely. */}
                          <div className="fixed inset-0 z-10" onClick={() => setMenuOpenFor(null)} />
                          <div className="absolute right-0 top-full mt-1 bg-card border border-border rounded-lg shadow-card z-20 w-40 py-1">
                            <button onClick={() => startRename(a)} className="w-full text-left px-3 py-2 text-xs text-ink hover:bg-bg flex items-center gap-2">
                              <Pencil size={13} /> Rename Account
                            </button>
                            <Link to="/app/statements" onClick={() => setMenuOpenFor(null)} className="w-full text-left px-3 py-2 text-xs text-ink hover:bg-bg flex items-center gap-2">
                              <FileText size={13} /> View Statements
                            </Link>
                            <button onClick={() => remove(a.id)} className="w-full text-left px-3 py-2 text-xs text-danger hover:bg-bg flex items-center gap-2">
                              <Trash2 size={13} /> Delete Account
                            </button>
                          </div>
                        </>
                      )}
                    </div>
                  </div>
                </div>

                <div className="p-5 grid sm:grid-cols-3 gap-4">
                  <InfoField icon={User} label="Account Holder Name">
                    {a.accountHolderName ?? 'Not Available'}
                  </InfoField>
                  <InfoField icon={Wallet} label="Current Balance">
                    {fmt(a.balance)}
                  </InfoField>
                  <InfoField icon={Calendar} label="Last Imported">
                    {lastImported ?? 'No statements yet'}
                  </InfoField>

                  <InfoField icon={CreditCard} label="Account Number">
                    {a.accountNumberMasked ? (
                      <span className="inline-flex items-center gap-1.5">
                        {isRevealed ? a.accountNumberMasked : '•••• ••••'}
                        <button
                          type="button"
                          onClick={() => toggleRevealed(a.id)}
                          title={isRevealed ? 'Hide account number' : 'Show account number'}
                          className="text-muted hover:text-ink align-middle"
                        >
                          {isRevealed ? <EyeOff size={13} /> : <Eye size={13} />}
                        </button>
                      </span>
                    ) : 'Not Available'}
                  </InfoField>
                  <InfoField icon={Landmark} label="Account Type">
                    {TYPE_LABEL[a.accountType]}
                  </InfoField>
                  <InfoField icon={Calendar} label="Statement Period">
                    {periodStart && periodEnd ? `${periodStart} – ${periodEnd}` : 'N/A'}
                  </InfoField>
                  <InfoField icon={FileText} label="Statements">
                    {a.statementsCount}
                  </InfoField>
                  <InfoField icon={FileText} label="Transactions">
                    {a.transactionsCount}
                  </InfoField>
                  {a.branchName && (
                    <InfoField icon={Landmark} label="Branch">
                      {a.branchName}
                    </InfoField>
                  )}
                  {a.ifscCode && (
                    <InfoField icon={Landmark} label="IFSC Code">
                      {a.ifscCode}
                    </InfoField>
                  )}
                </div>
              </div>
            );
          })
        )}
      </div>

      <details className="bg-card rounded-xl2 border border-border shadow-card">
        <summary className="cursor-pointer p-4 text-xs uppercase text-muted font-semibold flex items-center gap-1.5">
          <Plus size={13} /> Add an account manually
        </summary>
        <div className="p-4 pt-0 grid grid-cols-2 md:grid-cols-4 gap-3 items-end">
          <div>
            <label className="block text-xs uppercase text-gray-500 mb-1">Name</label>
            <input value={name} onChange={(e) => setName(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full" />
          </div>
          <div>
            {/* Search-then-select, per the brief -- filters the already-loaded registry
                client-side (banks.list() fetches all ~40 once on mount), rather than a network
                round-trip per keystroke. The backend's GET /api/v1/banks?q= supports server-side
                search too (used by anything that loads banks lazily instead). */}
            <label className="block text-xs uppercase text-gray-500 mb-1">Search Bank</label>
            <input
              value={bankSearch}
              onChange={(e) => setBankSearch(e.target.value)}
              placeholder="e.g. Punjab National Bank"
              className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full mb-1"
            />
            <select value={bankId} onChange={(e) => setBankId(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full">
              <option value="">Other / Not Listed</option>
              {banks
                .filter((b) => {
                  const q = bankSearch.trim().toLowerCase();
                  if (!q) return true;
                  return (b.officialName ?? b.shortName).toLowerCase().includes(q) || b.shortName.toLowerCase().includes(q);
                })
                .map((b) => <option key={b.id} value={b.id}>{b.officialName ?? b.shortName}</option>)}
            </select>
          </div>
          <div>
            <label className="block text-xs uppercase text-gray-500 mb-1">Type</label>
            <select value={type} onChange={(e) => setType(e.target.value as Account['accountType'])} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full">
              <option value="SAVINGS">Savings</option>
              <option value="CREDIT_CARD">Credit Card</option>
              <option value="WALLET">Wallet</option>
              <option value="INVESTMENT">Investment</option>
            </select>
          </div>
          <div>
            <label className="block text-xs uppercase text-gray-500 mb-1">Balance</label>
            <input type="number" value={balance} onChange={(e) => setBalance(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full" />
          </div>
          <div>
            <label className="block text-xs uppercase text-gray-500 mb-1">Account holder</label>
            <input value={holderName} onChange={(e) => setHolderName(e.target.value)} placeholder="Optional" className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full" />
          </div>
          <div>
            <label className="block text-xs uppercase text-gray-500 mb-1">Account number</label>
            <input value={accountNumber} onChange={(e) => setAccountNumber(e.target.value)} placeholder="Optional" className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full" />
          </div>
          <div>
            <label className="block text-xs uppercase text-gray-500 mb-1">Branch</label>
            <input value={branchName} onChange={(e) => setBranchName(e.target.value)} placeholder="Optional" className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full" />
          </div>
          <div>
            <label className="block text-xs uppercase text-gray-500 mb-1">IFSC code</label>
            <input value={ifscCode} onChange={(e) => setIfscCode(e.target.value.toUpperCase())} placeholder="Optional" className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full" />
          </div>
          {type === 'CREDIT_CARD' && (
            <>
              <div>
                <label className="block text-xs uppercase text-gray-500 mb-1">Limit</label>
                <input type="number" value={limit} onChange={(e) => setLimit(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full" />
              </div>
              <div>
                <label className="block text-xs uppercase text-gray-500 mb-1">Due date</label>
                <input type="date" value={dueDate} onChange={(e) => setDueDate(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full" />
              </div>
            </>
          )}
          <button onClick={addAccount} disabled={saving} className="bg-primary text-white hover:bg-primary-dark px-4 py-2 rounded text-xs uppercase disabled:opacity-50">
            {saving ? 'Adding…' : 'Add'}
          </button>
        </div>
      </details>
      {error && <p className="text-danger text-sm">{error}</p>}
    </div>
  );
}
