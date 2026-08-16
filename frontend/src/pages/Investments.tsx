import { useEffect, useState } from 'react';
import { Doughnut, Line } from 'react-chartjs-2';
import { Chart as ChartJS, ArcElement, LineElement, PointElement, LinearScale, CategoryScale, Tooltip, Legend } from 'chart.js';
import { LineChart as LineChartIcon, TrendingUp, TrendingDown, Wallet } from 'lucide-react';
import { accountsApi, networthApi, type NetWorthData } from '../api/endpoints';
import type { Account } from '../types';
import { formatDate } from '../utils/date';
import { FinoraCard, MetricCard, EmptyState, SectionHeader, ChartContainer, baseChartOptions } from '../design-system';

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
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [savingSnapshot, setSavingSnapshot] = useState(false);

  function load() {
    setLoading(true);
    Promise.all([accountsApi.list(), networthApi.current()])
      .then(([accounts, nw]) => {
        setHoldings(accounts.filter((a) => a.accountType === 'INVESTMENT'));
        setNetWorth(nw);
      })
      .catch(() => setError('Could not load investments.'))
      .finally(() => setLoading(false));
  }
  useEffect(load, []);

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
      load();
    } catch {
      setError('Could not add this holding.');
    } finally {
      setAdding(false);
    }
  }

  async function removeHolding(id: string) {
    if (!confirm('Delete this holding?')) return;
    try {
      await accountsApi.remove(id);
      load();
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

  if (loading) return <p className="text-muted">Loading…</p>;

  const totalInvestments = holdings.reduce((s, h) => s + h.balance, 0);

  return (
    <div className="space-y-6">
      {error && (
        <div className="bg-danger/10 text-danger text-sm rounded p-3 flex justify-between items-center">
          <span>{error}</span>
          <button onClick={() => setError(null)}>×</button>
        </div>
      )}
      <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
        <MetricCard label="Total Investments" value={fmt(totalInvestments)} icon={LineChartIcon} iconBg="bg-primary-light" iconColor="text-primary" />
        <MetricCard label="Net Worth" value={fmt(netWorth?.netWorth ?? 0)} icon={TrendingUp} iconBg="bg-green-100" iconColor="text-green-600" valueColor="text-success" />
        <MetricCard label="Liabilities" value={fmt(netWorth?.totalLiabilities ?? 0)} icon={TrendingDown} iconBg="bg-red-100" iconColor="text-red-600" valueColor="text-danger" />
      </div>

      <div className="grid md:grid-cols-2 gap-6">
        <FinoraCard>
          <SectionHeader title="Allocation" />
          <ChartContainer
            height={224}
            isEmpty={holdings.length === 0}
            emptyState={
              <EmptyState
                icon={Wallet}
                iconBg="bg-blue-100"
                iconColor="text-blue-600"
                title="No investments yet"
                desc="Add your first investment or asset below to see its allocation."
              />
            }
          >
            <Doughnut
              data={{
                labels: holdings.map((h) => h.name),
                datasets: [{ data: holdings.map((h) => h.balance), backgroundColor: holdings.map((_, i) => COLORS[i % COLORS.length]) }],
              }}
              options={baseChartOptions}
            />
          </ChartContainer>
        </FinoraCard>
        <FinoraCard>
          <div className="flex justify-between items-center mb-4">
            <h2 className="font-semibold text-ink">Net Worth Trend</h2>
            <button
              onClick={saveSnapshot}
              disabled={savingSnapshot}
              className="text-xs uppercase border border-border rounded px-3 py-1.5 disabled:opacity-50"
            >
              {savingSnapshot ? 'Saving…' : "Save Today's Snapshot"}
            </button>
          </div>
          <ChartContainer
            height={224}
            isEmpty={!netWorth || netWorth.history.length < 2}
            emptyState={
              <EmptyState
                icon={TrendingUp}
                iconBg="bg-primary-light"
                iconColor="text-primary"
                title="Start tracking your trend"
                desc="Save a snapshot periodically to build a net worth trend — history starts accumulating from when you begin saving snapshots."
              />
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

      <FinoraCard>
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
          <button onClick={addHolding} disabled={adding} className="bg-primary text-white hover:bg-primary-dark disabled:opacity-50 px-4 py-2 rounded text-xs uppercase">{adding ? 'Adding…' : 'Add'}</button>
        </div>

        {holdings.length === 0 ? (
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
                    <button onClick={() => removeHolding(h.id)} className="text-danger border border-danger rounded px-2 py-0.5 text-[10px] uppercase">Delete</button>
                  </span>
                </div>
                <DepositTerms holding={h} />
              </div>
            ))}
          </div>
        )}
      </FinoraCard>
    </div>
  );
}
