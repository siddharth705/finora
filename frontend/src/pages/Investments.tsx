import { useCallback, useEffect, useState } from 'react';
import { Doughnut, Line } from 'react-chartjs-2';
import { Chart as ChartJS, ArcElement, LineElement, PointElement, LinearScale, CategoryScale, Tooltip, Legend } from 'chart.js';
import { LineChart as LineChartIcon, TrendingUp, TrendingDown, Wallet, Loader2 } from 'lucide-react';
import { accountsApi, networthApi, type NetWorthData } from '../api/endpoints';
import type { Account } from '../types';
import { formatDate } from '../utils/date';
import { useAsyncGuard } from '../hooks/useAsyncGuard';
import { useDelayedLoading } from '../hooks/useDelayedLoading';
import { Button, FinoraCard, MetricCard, EmptyState, SectionHeader, ChartContainer, baseChartOptions, ConfirmDialog, Skeleton } from '../design-system';

ChartJS.register(ArcElement, LineElement, PointElement, LinearScale, CategoryScale, Tooltip, Legend);

function fmt(n: number) {
  // Negative amounts (e.g. a month where spend exceeded income) must render as "-₹500",
  // not "₹-500" -- string concatenation put the currency symbol before the sign.
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

const COLORS = ['#a9803a', '#2f6e5c', '#9c3f3f', '#5b7fa6', '#7a6248', '#8a6d9e'];

/**
 * The terms of a deposit -- what distinguishes an FD or RD from any other holding sitting in this
 * module. Renders nothing at all when there are no terms to show, which is every hand-created
 * holding and every mutual fund/stock, so this only appears where it actually says something.
 */
function DepositTerms({ holding }: { holding: Account }) {
  const terms: string[] = [];
  if (holding.principalAmount != null) terms.push(`Principal ${fmt(holding.principalAmount)}`);
  if (holding.installmentAmount != null) terms.push(`${fmt(holding.installmentAmount)}/month`);
  if (holding.installmentsPaid != null && holding.installmentsTotal != null) {
    terms.push(`${holding.installmentsPaid} of ${holding.installmentsTotal} paid`);
  }
  if (holding.interestRate != null) terms.push(`${holding.interestRate}% p.a.`);
  if (holding.maturityDate) {
    terms.push(`Matures ${formatDate(holding.maturityDate)}`);
  }
  if (holding.maturityAmount != null) terms.push(`Worth ${fmt(holding.maturityAmount)} at maturity`);

  if (terms.length === 0) return null;
  return <p className="text-[11px] text-gray-500 mt-0.5">{terms.join(' · ')}</p>;
}

export default function Investments() {
  const [holdings, setHoldings] = useState<Account[]>([]);
  const [netWorth, setNetWorth] = useState<NetWorthData | null>(null);
  const [name, setName] = useState('');
  const [value, setValue] = useState('');
  const [kind, setKind] = useState('Mutual Fund');
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  // Distinct from `error`, which is also set by add/delete failures while real data is on screen.
  // This one means "the data below is not trustworthy", and is what stops the empty states from
  // telling a user with holdings that they have none when the fetch simply failed.
  const [loadFailed, setLoadFailed] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [savingSnapshot, setSavingSnapshot] = useState(false);
  const [confirmRemoveId, setConfirmRemoveId] = useState<string | null>(null);
  const { beginRequest } = useAsyncGuard();

  /**
   * `mode` exists because this function serves two very different situations. On mount there is
   * nothing on screen, so a skeleton is right. After an add or a delete the page is fully rendered
   * and merely stale -- collapsing it back into a skeleton there would be the "replace visible
   * content with a skeleton on every refresh" behaviour the roadmap's UX table explicitly routes
   * to a spinner instead. Before this split, both went through one `loading` flag.
   *
   * Returns the promise so a caller can keep its own pending state up until the data is actually
   * fresh, rather than declaring itself done while the refetch is still running.
   */
  // useCallback, not a bare function: the old `useEffect(load, [])` passed `load` straight through
  // as the effect body, which exhaustive-deps does not flag. Calling it inside an arrow (needed now
  // that it takes a mode) does get flagged, and `npm run lint` runs with --max-warnings 0. Every
  // value this closes over is stable -- state setters, and beginRequest is itself a useCallback --
  // so an identity tied to [beginRequest] is honest rather than a suppression.
  const load = useCallback((mode: 'initial' | 'refresh' = 'refresh') => {
    // Guarded because keeping the list on screen during a refresh is exactly what makes a SECOND
    // load reachable: pre-Phase-5 the page-level gate replaced everything, so there were no Delete
    // buttons and no Add form to start one from. Now there are. Without this, deleting a second
    // holding while the first delete's refetch is still running lets the older response -- whose
    // GET was issued before the second delete -- land last and resurrect the deleted row as a live,
    // deletable entry. Same single-slot guard Reports uses for its month switches.
    const isCurrent = beginRequest();
    if (mode === 'initial') setLoading(true);
    else setRefreshing(true);
    return Promise.all([accountsApi.list(), networthApi.current()])
      .then(([accounts, nw]) => {
        if (!isCurrent()) return;
        setHoldings(accounts.filter((a) => a.accountType === 'INVESTMENT'));
        setNetWorth(nw);
        setLoadFailed(false);
      })
      .catch(() => {
        if (!isCurrent()) return;
        setError('Could not load investments.');
        setLoadFailed(true);
      })
      // Both flags clear together, and only for the newest request. Clearing per-mode would strand
      // `loading` true forever whenever a refresh superseded an in-flight initial load; clearing
      // unguarded would let a superseded request switch the indicator off while the request whose
      // data is actually awaited is still running.
      .finally(() => {
        if (!isCurrent()) return;
        setLoading(false);
        setRefreshing(false);
      });
  }, [beginRequest]);
  useEffect(() => { void load('initial'); }, [load]);

  async function addHolding() {
    if (!name || !value) return;
    // Bug fix: this only checked presence, not validity -- unlike Goals.tsx/Budgets.tsx's own
    // amount fields. "-500" or non-numeric text passed straight through: a negative balance
    // persisted and skewed the Allocation doughnut, or parseFloat returned NaN, which propagated
    // into fmt()'s Math.round(NaN) and rendered "₹NaN" across the Total Investments card.
    const currentValue = parseFloat(value);
    if (!(currentValue > 0)) {
      setError('Current value must be greater than zero.');
      return;
    }
    setAdding(true);
    try {
      await accountsApi.create({ name, accountType: 'INVESTMENT', balance: currentValue, investmentKind: kind });
      setName(''); setValue('');
      // Awaited, so the button keeps its spinner until the new holding is actually on screen.
      // Firing and forgetting would drop the spinner while the list was still the old one.
      await load();
    } catch {
      setError('Could not add this holding.');
    } finally {
      setAdding(false);
    }
  }

  async function removeHolding(id: string) {
    try {
      await accountsApi.remove(id);
      await load();
    } catch {
      setError('Could not delete this holding.');
    }
  }

  async function saveSnapshot() {
    // Bug fix: no try/catch and no pending state meant a failed save was an unhandled promise
    // rejection with zero user feedback, and since the button was never disabled mid-request, a
    // slow response let someone fire multiple concurrent snapshot-save requests by clicking again.
    if (savingSnapshot) return;
    setSavingSnapshot(true);
    try {
      const nw = await networthApi.saveSnapshot();
      setNetWorth(nw);
    } catch {
      setError('Could not save today’s snapshot.');
    } finally {
      setSavingSnapshot(false);
    }
  }

  // No page-level gate any more. Each section below shows its own skeleton, which is what lets the
  // two ChartContainers take a real `loading` prop -- behind an early return they would render only
  // after loading finished, making that prop dead code. It also fixes a flash the early return was
  // masking: both charts' `isEmpty` tests (`holdings.length === 0`, `!netWorth || history < 2`) and
  // the holdings list's own empty state are indistinguishable from "still loading", so without a
  // loading branch they would each announce an empty state to a user who has data. ChartContainer
  // checks `loading` before `isEmpty`, so passing it is the fix for the charts; the holdings list
  // gets the same treatment explicitly below.
  const showSkeleton = useDelayedLoading(loading);
  const totalInvestments = holdings.reduce((s, h) => s + h.balance, 0);

  return (
    <div className="space-y-6">
      {error && (
        <div className="bg-danger/10 text-danger text-sm rounded p-3 flex justify-between items-center">
          <span>{error}</span>
          <button onClick={() => setError(null)}>×</button>
        </div>
      )}
      {loading ? (
        <Skeleton.Region label="Loading your investment totals">
          {showSkeleton && (
            <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
              {[0, 1, 2].map((i) => <Skeleton.Card key={i} />)}
            </div>
          )}
        </Skeleton.Region>
      ) : (
        <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
          <MetricCard label="Total Investments" value={fmt(totalInvestments)} icon={LineChartIcon} iconBg="bg-primary-light" iconColor="text-primary" />
          <MetricCard label="Net Worth" value={fmt(netWorth?.netWorth ?? 0)} icon={TrendingUp} iconBg="bg-green-100" iconColor="text-green-600" valueColor="text-success" />
          <MetricCard label="Liabilities" value={fmt(netWorth?.totalLiabilities ?? 0)} icon={TrendingDown} iconBg="bg-red-100" iconColor="text-red-600" valueColor="text-danger" />
        </div>
      )}

      <div className="grid md:grid-cols-2 gap-6">
        <FinoraCard>
          <SectionHeader title="Allocation" />
          <ChartContainer
            height={224}
            loading={loading}
            loadingLabel="Loading your allocation"
            isEmpty={holdings.length === 0}
            emptyState={
              // "No data to chart" is true either way; WHY differs. Claiming "no investments yet"
              // to someone whose fetch just failed is the same false-empty message the loading
              // branch above exists to prevent, one state later.
              loadFailed ? (
                <p className="text-muted text-sm">Couldn't load your investments.</p>
              ) : (
                <EmptyState
                  icon={Wallet}
                  iconBg="bg-blue-100"
                  iconColor="text-blue-600"
                  title="No investments yet"
                  desc="Add your first investment or asset below to see its allocation."
                />
              )
            }
          >
            <Doughnut
              data={{
                labels: holdings.map((h) => h.name),
                datasets: [{ data: holdings.map((h) => h.balance), backgroundColor: holdings.map((_, i) => COLORS[i % COLORS.length]) }],
              }}
              options={{ ...baseChartOptions }}
            />
          </ChartContainer>
        </FinoraCard>
        <FinoraCard>
          <div className="flex justify-between items-center mb-4">
            <h2 className="font-semibold text-ink">Net Worth Trend</h2>
            {/* Static label + Button's own spinner, replacing the manual "Saving…" text swap --
                the convention every earlier phase adopted. The `if (savingSnapshot) return`
                re-entrancy guard in saveSnapshot() stays: Button disabling itself while `loading`
                covers the pointer path, not a programmatic second call. */}
            <Button variant="secondary" size="sm" onClick={saveSnapshot} loading={savingSnapshot} className="uppercase">
              Save Today's Snapshot
            </Button>
          </div>
          <ChartContainer
            height={224}
            loading={loading}
            loadingLabel="Loading your net worth trend"
            isEmpty={!netWorth || netWorth.history.length < 2}
            emptyState={
              loadFailed ? (
                <p className="text-muted text-sm">Couldn't load your net worth history.</p>
              ) : (
                <EmptyState
                  icon={TrendingUp}
                  iconBg="bg-primary-light"
                  iconColor="text-primary"
                  title="Building your net worth trend"
                  desc="Check back in a day or two, or save today's snapshot now to add a point right away."
                />
              )
            }
          >
            <Line
              data={{
                labels: netWorth?.history.map((h) => h.date) ?? [],
                datasets: [{
                  label: 'Net Worth',
                  data: netWorth?.history.map((h) => h.netWorth) ?? [],
                  borderColor: '#a9803a',
                  backgroundColor: 'rgba(169,128,58,0.15)',
                  fill: true,
                  tension: 0.3,
                }],
              }}
              options={{ ...baseChartOptions, plugins: { legend: { display: false } } }}
            />
          </ChartContainer>
        </FinoraCard>
      </div>

      {/* `relative` positions the refetch indicator below. A delete has no pending affordance of
          its own once its dialog closes -- the add button keeps its own spinner through the
          refetch, but a removal would otherwise leave stale rows on screen silently. */}
      <FinoraCard className="relative">
        {refreshing && (
          <div className="absolute top-3 right-4 text-[10px] uppercase text-primary flex items-center gap-1">
            <Loader2 size={11} className="animate-spin" aria-hidden="true" /> Refreshing…
          </div>
        )}
        <SectionHeader title="Add Investment / Asset" />
        <div className="grid md:grid-cols-4 gap-2 items-end mb-4">
          <div>
            <label htmlFor="investment-name" className="block text-xs uppercase text-gray-500 mb-1">Name</label>
            <input id="investment-name" value={name} onChange={(e) => setName(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full" />
          </div>
          <div>
            <label htmlFor="investment-value" className="block text-xs uppercase text-gray-500 mb-1">Current value</label>
            <input id="investment-value" type="number" value={value} onChange={(e) => setValue(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full" />
          </div>
          <div>
            <label htmlFor="investment-type" className="block text-xs uppercase text-gray-500 mb-1">Type</label>
            <select id="investment-type" value={kind} onChange={(e) => setKind(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full">
              <option>Mutual Fund</option><option>Stocks</option><option>FD</option><option>PPF/NPS</option><option>Other</option>
            </select>
          </div>
          <Button onClick={addHolding} loading={adding} className="uppercase">Add</Button>
        </div>

        {loading ? (
          <Skeleton.Region label="Loading your holdings">
            {showSkeleton && (
              <div className="space-y-2">
                {Array.from({ length: 3 }, (_, i) => (
                  <div key={i} className="flex justify-between items-center border-b border-dashed py-2">
                    <Skeleton.Text width="w-2/5" />
                    <Skeleton.Text width="w-20" className="h-2.5" />
                  </div>
                ))}
              </div>
            )}
          </Skeleton.Region>
        ) : loadFailed ? (
          <p className="text-muted text-sm">Couldn't load your holdings — please try again later.</p>
        ) : holdings.length === 0 ? (
          <EmptyState
            icon={Wallet}
            iconBg="bg-blue-100"
            iconColor="text-blue-600"
            title="No holdings yet"
            desc="Add your first investment or asset above."
          />
        ) : (
          <div className="space-y-2">
            {holdings.map((h) => (
              <div key={h.id} className="border-b border-dashed py-2">
                <div className="flex justify-between items-center text-sm">
                  <span>{h.name} <span className="text-[10px] uppercase text-gray-400 ml-2">{h.investmentKind}</span></span>
                  <span className="flex items-center gap-3">
                    {fmt(h.balance)}
                    {/* size="sm" rather than this row's old px-2 py-0.5 text-[10px]: it makes the
                        control identical to Goals.tsx's per-item Delete, which is the same action
                        opening the same ConfirmDialog. Normalising matches that precedent. */}
                    <Button variant="danger" size="sm" onClick={() => setConfirmRemoveId(h.id)} className="uppercase">Delete</Button>
                  </span>
                </div>
                <DepositTerms holding={h} />
              </div>
            ))}
          </div>
        )}
      </FinoraCard>

      {confirmRemoveId && (
        <ConfirmDialog
          title="Delete this holding?"
          message="This can't be undone."
          confirmLabel="Delete"
          danger
          onConfirm={() => {
            const id = confirmRemoveId;
            setConfirmRemoveId(null);
            void removeHolding(id);
          }}
          onCancel={() => setConfirmRemoveId(null)}
        />
      )}
    </div>
  );
}
