import { useEffect, useState } from 'react';
import { Doughnut, Line } from 'react-chartjs-2';
import { Chart as ChartJS, ArcElement, LineElement, PointElement, LinearScale, CategoryScale, Tooltip, Legend } from 'chart.js';
import { accountsApi, networthApi, type NetWorthData } from '../api/endpoints';
import type { Account } from '../types';

ChartJS.register(ArcElement, LineElement, PointElement, LinearScale, CategoryScale, Tooltip, Legend);

function fmt(n: number) {
  // Negative amounts (e.g. a month where spend exceeded income) must render as "-₹500",
  // not "₹-500" -- string concatenation put the currency symbol before the sign.
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

const COLORS = ['#a9803a', '#2f6e5c', '#9c3f3f', '#5b7fa6', '#7a6248', '#8a6d9e'];

export default function Investments() {
  const [holdings, setHoldings] = useState<Account[]>([]);
  const [netWorth, setNetWorth] = useState<NetWorthData | null>(null);
  const [name, setName] = useState('');
  const [value, setValue] = useState('');
  const [kind, setKind] = useState('Mutual Fund');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);

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
    setAdding(true);
    try {
      await accountsApi.create({ name, accountType: 'INVESTMENT', balance: parseFloat(value), investmentKind: kind });
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
    const nw = await networthApi.saveSnapshot();
    setNetWorth(nw);
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
        <div className="bg-card rounded p-4 shadow border-l-4 border-primary">
          <p className="text-[10px] uppercase text-gray-500 mb-1">Total Investments</p>
          <p className="text-xl font-semibold">{fmt(totalInvestments)}</p>
        </div>
        <div className="bg-card rounded p-4 shadow border-l-4 border-success">
          <p className="text-[10px] uppercase text-gray-500 mb-1">Net Worth</p>
          <p className="text-xl font-semibold text-success">{fmt(netWorth?.netWorth ?? 0)}</p>
        </div>
        <div className="bg-card rounded p-4 shadow border-l-4 border-danger">
          <p className="text-[10px] uppercase text-gray-500 mb-1">Liabilities</p>
          <p className="text-xl font-semibold text-danger">{fmt(netWorth?.totalLiabilities ?? 0)}</p>
        </div>
      </div>

      <div className="grid md:grid-cols-2 gap-6">
        <div className="bg-card rounded p-5 shadow">
          <p className="text-xs uppercase text-gray-500 mb-3">Allocation</p>
          {holdings.length === 0 ? (
            <p className="text-sm italic text-gray-500">No investment accounts added yet.</p>
          ) : (
            <div className="max-w-xs mx-auto">
              <Doughnut
                data={{
                  labels: holdings.map((h) => h.name),
                  datasets: [{ data: holdings.map((h) => h.balance), backgroundColor: holdings.map((_, i) => COLORS[i % COLORS.length]) }],
                }}
              />
            </div>
          )}
        </div>
        <div className="bg-card rounded p-5 shadow">
          <div className="flex justify-between items-center mb-3">
            <p className="text-xs uppercase text-gray-500">Net Worth Trend</p>
            <button onClick={saveSnapshot} className="text-xs uppercase border border-border rounded px-3 py-1.5">
              Save Today's Snapshot
            </button>
          </div>
          {!netWorth || netWorth.history.length < 2 ? (
            <p className="text-sm italic text-gray-500">
              Save a snapshot periodically to build a net worth trend — history starts accumulating from when you begin saving snapshots.
            </p>
          ) : (
            <Line
              data={{
                labels: netWorth.history.map((h) => h.date),
                datasets: [{
                  label: 'Net Worth',
                  data: netWorth.history.map((h) => h.netWorth),
                  borderColor: '#a9803a',
                  backgroundColor: 'rgba(169,128,58,0.15)',
                  fill: true,
                  tension: 0.3,
                }],
              }}
              options={{ plugins: { legend: { display: false } } }}
            />
          )}
        </div>
      </div>

      <div className="bg-card rounded shadow p-5">
        <p className="text-xs uppercase text-gray-500 mb-4">Add Investment / Asset</p>
        <div className="grid md:grid-cols-4 gap-2 items-end mb-4">
          <div>
            <label className="block text-xs uppercase text-gray-500 mb-1">Name</label>
            <input value={name} onChange={(e) => setName(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full" />
          </div>
          <div>
            <label className="block text-xs uppercase text-gray-500 mb-1">Current value</label>
            <input type="number" value={value} onChange={(e) => setValue(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full" />
          </div>
          <div>
            <label className="block text-xs uppercase text-gray-500 mb-1">Type</label>
            <select value={kind} onChange={(e) => setKind(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full">
              <option>Mutual Fund</option><option>Stocks</option><option>FD</option><option>PPF/NPS</option><option>Other</option>
            </select>
          </div>
          <button onClick={addHolding} disabled={adding} className="bg-primary text-white hover:bg-primary-dark disabled:opacity-50 px-4 py-2 rounded text-xs uppercase">{adding ? 'Adding…' : 'Add'}</button>
        </div>

        {holdings.length === 0 ? (
          <p className="text-sm italic text-gray-500">No holdings yet.</p>
        ) : (
          <div className="space-y-2">
            {holdings.map((h) => (
              <div key={h.id} className="flex justify-between items-center text-sm border-b border-dashed py-2">
                <span>{h.name} <span className="text-[10px] uppercase text-gray-400 ml-2">{h.investmentKind}</span></span>
                <span className="flex items-center gap-3">
                  {fmt(h.balance)}
                  <button onClick={() => removeHolding(h.id)} className="text-danger border border-danger rounded px-2 py-0.5 text-[10px] uppercase">Delete</button>
                </span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
