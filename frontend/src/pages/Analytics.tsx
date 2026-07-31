import { useEffect, useState, type ReactNode } from 'react';
import { Line } from 'react-chartjs-2';
import {
  Chart as ChartJS, LineElement, PointElement, LinearScale, CategoryScale, Tooltip, Legend, Filler,
} from 'chart.js';
import { LineChart } from 'lucide-react';
import { analyticsApi } from '../api/endpoints';
import type {
  TopMerchantPoint, TrendPoint, CategoryConfidencePoint, TopCategoryPoint, ImportStatistics, LearningGrowthPoint,
} from '../api/endpoints';

ChartJS.register(LineElement, PointElement, LinearScale, CategoryScale, Tooltip, Legend, Filler);

function fmt(n: number) {
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

function fmtDate(d: string | null) {
  if (!d) return 'Never';
  return new Date(d).toLocaleDateString('en-IN', { year: 'numeric', month: 'short', day: 'numeric' });
}

function monthLabel(monthStr: string) {
  const [y, m] = monthStr.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('en-US', { month: 'short', year: '2-digit' });
}

function BarRow({ label, value, max, formatValue, sublabel }: {
  label: string; value: number; max: number; formatValue: (v: number) => string; sublabel?: string;
}) {
  const pct = max > 0 ? Math.min(100, (value / max) * 100) : 0;
  return (
    <div>
      <div className="flex justify-between items-baseline mb-1">
        <span className="text-sm text-ink truncate">{label}</span>
        <span className="text-xs text-gray-500 flex-shrink-0 ml-2">{formatValue(value)}{sublabel && <span className="text-gray-400"> · {sublabel}</span>}</span>
      </div>
      <div className="h-1.5 bg-black/5 rounded-full overflow-hidden">
        <div className="h-full bg-primary rounded-full" style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}

function Card({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="bg-card rounded-xl shadow p-5">
      <h2 className="text-sm font-semibold text-ink mb-3">{title}</h2>
      {children}
    </div>
  );
}

export default function Analytics() {
  const [topMerchants, setTopMerchants] = useState<TopMerchantPoint[] | null>(null);
  const [trend, setTrend] = useState<TrendPoint[] | null>(null);
  const [categoryConfidence, setCategoryConfidence] = useState<CategoryConfidencePoint[] | null>(null);
  const [topCategories, setTopCategories] = useState<TopCategoryPoint[] | null>(null);
  const [importStats, setImportStats] = useState<ImportStatistics | null>(null);
  const [learningGrowth, setLearningGrowth] = useState<LearningGrowthPoint[] | null>(null);

  useEffect(() => {
    analyticsApi.topMerchants().then(setTopMerchants);
    analyticsApi.trend().then(setTrend);
    analyticsApi.categoryConfidence().then(setCategoryConfidence);
    analyticsApi.topCategories().then(setTopCategories);
    analyticsApi.importStatistics().then(setImportStats);
    analyticsApi.learningGrowth().then(setLearningGrowth);
  }, []);

  const maxMerchantSpend = Math.max(1, ...(topMerchants ?? []).map((m) => m.totalSpend));
  const maxCategorySpend = Math.max(1, ...(topCategories ?? []).map((c) => c.totalSpend));

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <LineChart size={20} className="text-primary" />
        <h1 className="text-lg font-semibold">Analytics</h1>
      </div>
      <p className="text-sm text-gray-500">
        How the categorization engine is doing across your merchants, categories, and imports.
      </p>

      {importStats && (
        <div className="grid grid-cols-4 gap-3">
          <div className="bg-card rounded-xl shadow p-4">
            <p className="text-xs uppercase text-gray-500 font-medium">Statements imported</p>
            <p className="text-2xl font-bold text-ink mt-1">{importStats.totalStatements.toLocaleString('en-IN')}</p>
          </div>
          <div className="bg-card rounded-xl shadow p-4">
            <p className="text-xs uppercase text-gray-500 font-medium">Transactions imported</p>
            <p className="text-2xl font-bold text-ink mt-1">{importStats.totalTransactionsImported.toLocaleString('en-IN')}</p>
          </div>
          <div className="bg-card rounded-xl shadow p-4">
            <p className="text-xs uppercase text-gray-500 font-medium">Rows skipped</p>
            <p className="text-2xl font-bold text-ink mt-1">{importStats.totalTransactionsSkipped.toLocaleString('en-IN')}</p>
          </div>
          <div className="bg-card rounded-xl shadow p-4">
            <p className="text-xs uppercase text-gray-500 font-medium">Last import</p>
            <p className="text-2xl font-bold text-ink mt-1">{fmtDate(importStats.lastImportedAt)}</p>
          </div>
        </div>
      )}

      <div className="grid lg:grid-cols-2 gap-4">
        <Card title="Spend Trend">
          {trend === null ? (
            <p className="text-sm text-gray-500">Loading…</p>
          ) : trend.length === 0 ? (
            <p className="text-sm italic text-gray-500">Not enough history yet.</p>
          ) : (
            <div className="h-56">
              <Line
                data={{
                  labels: trend.map((t) => monthLabel(t.month)),
                  datasets: [{
                    label: 'Total spend', data: trend.map((t) => t.totalSpend),
                    borderColor: '#6366f1', backgroundColor: 'rgba(99,102,241,0.08)', fill: true, tension: 0.3,
                  }],
                }}
                options={{
                  responsive: true, maintainAspectRatio: false,
                  plugins: { legend: { display: false } },
                  scales: { y: { ticks: { callback: (v) => '₹' + Number(v).toLocaleString('en-IN') } } },
                }}
              />
            </div>
          )}
        </Card>

        <Card title="Learning Growth">
          {learningGrowth === null ? (
            <p className="text-sm text-gray-500">Loading…</p>
          ) : learningGrowth.length === 0 ? (
            <p className="text-sm italic text-gray-500">No learning history yet.</p>
          ) : (
            <div className="h-56">
              <Line
                data={{
                  labels: learningGrowth.map((g) => monthLabel(g.month)),
                  datasets: [
                    { label: 'Learned', data: learningGrowth.map((g) => g.learnedCount), borderColor: '#16a34a', backgroundColor: 'rgba(22,163,74,0.08)', fill: true, tension: 0.3 },
                    { label: 'Corrected', data: learningGrowth.map((g) => g.correctedCount), borderColor: '#f59e0b', backgroundColor: 'rgba(245,158,11,0.08)', fill: true, tension: 0.3 },
                  ],
                }}
                options={{
                  responsive: true, maintainAspectRatio: false,
                  plugins: { legend: { position: 'bottom', labels: { boxWidth: 8, boxHeight: 8, usePointStyle: true } } },
                }}
              />
            </div>
          )}
        </Card>

        <Card title="Top Merchants">
          {topMerchants === null ? (
            <p className="text-sm text-gray-500">Loading…</p>
          ) : topMerchants.length === 0 ? (
            <p className="text-sm italic text-gray-500">No merchant spend yet.</p>
          ) : (
            <div className="space-y-3">
              {topMerchants.slice(0, 8).map((m) => (
                <BarRow key={m.merchantId} label={m.merchantName} value={m.totalSpend} max={maxMerchantSpend}
                  formatValue={fmt} sublabel={`${m.transactionCount} txn${m.transactionCount === 1 ? '' : 's'}`} />
              ))}
            </div>
          )}
        </Card>

        <Card title="Top Categories">
          {topCategories === null ? (
            <p className="text-sm text-gray-500">Loading…</p>
          ) : topCategories.length === 0 ? (
            <p className="text-sm italic text-gray-500">No category spend yet.</p>
          ) : (
            <div className="space-y-3">
              {topCategories.slice(0, 8).map((c) => (
                <BarRow key={c.categoryId} label={c.categoryName} value={c.totalSpend} max={maxCategorySpend}
                  formatValue={fmt} sublabel={`${c.transactionCount} txn${c.transactionCount === 1 ? '' : 's'}`} />
              ))}
            </div>
          )}
        </Card>

        <Card title="Confidence by Category">
          {categoryConfidence === null ? (
            <p className="text-sm text-gray-500">Loading…</p>
          ) : categoryConfidence.length === 0 ? (
            <p className="text-sm italic text-gray-500">No learned categories yet.</p>
          ) : (
            <div className="space-y-3">
              {categoryConfidence.map((c) => (
                <BarRow key={c.category} label={c.category} value={c.avgConfidence} max={100}
                  formatValue={(v) => `${v}%`} sublabel={`${c.merchantCount} merchant${c.merchantCount === 1 ? '' : 's'}`} />
              ))}
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}
