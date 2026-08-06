import type { CSSProperties } from 'react';
import { CountUp, useStagedReveal } from './primitives';

/**
 * The product illustration used in the hero and, at full width, in the Dashboard Showcase.
 *
 * Drawn in markup rather than shipped as a screenshot, deliberately: a PNG of the dashboard goes
 * stale the moment the product moves, and a stale screenshot on a landing page is a quiet lie
 * about what you get. This renders from the same design tokens the app uses, so it drifts far
 * less -- and it stays crisp at any density with no asset to optimise.
 *
 * The figures are illustrative, and they are internally consistent on purpose: income minus
 * expenses equals the savings figure, and the category percentages total 100. Numbers that don't
 * add up are the first thing a finance-literate visitor notices.
 */
const CATEGORIES = [
  { label: 'Food & Dining', pct: 28, amount: '₹21,400', color: '#2563EB' },
  { label: 'Shopping', pct: 20, amount: '₹15,300', color: '#7C3AED' },
  { label: 'Bills & Utilities', pct: 18, amount: '₹13,760', color: '#F59E0B' },
  { label: 'Travel', pct: 12, amount: '₹9,170', color: '#16A34A' },
  { label: 'Others', pct: 22, amount: '₹16,800', color: '#94A3B8' },
];

const NAV = ['Overview', 'Transactions', 'Accounts', 'Budgets', 'Goals', 'Insights', 'Reports', 'Settings'];

const TRANSACTIONS = [
  { name: 'Swiggy', meta: 'Food & Dining · 12 Apr', amount: '−₹480', tone: 'text-slate-900' },
  { name: 'Salary Credit', meta: 'Income · 01 Apr', amount: '+₹1,24,500', tone: 'text-[#16A34A]' },
  { name: 'Electricity Bill', meta: 'Bills & Utilities · 08 Apr', amount: '−₹2,310', tone: 'text-slate-900' },
  { name: 'Amazon', meta: 'Shopping · 06 Apr', amount: '−₹3,650', tone: 'text-slate-900' },
];

/** A donut built from one circle per slice using stroke-dasharray -- no chart library. */
function Donut() {
  const radius = 54;
  const circumference = 2 * Math.PI * radius;
  let offset = 0;
  return (
    <svg viewBox="0 0 140 140" className="w-[132px] h-[132px] shrink-0" role="img" aria-label="Spending by category">
      {CATEGORIES.map((c) => {
        const dash = (c.pct / 100) * circumference;
        const el = (
          <circle
            key={c.label}
            cx="70" cy="70" r={radius}
            fill="none"
            stroke={c.color}
            strokeWidth="16"
            strokeDasharray={`${dash} ${circumference - dash}`}
            strokeDashoffset={-offset}
            transform="rotate(-90 70 70)"
          />
        );
        offset += dash;
        return el;
      })}
      <text x="70" y="66" textAnchor="middle" className="fill-slate-900" style={{ fontSize: 15, fontWeight: 700 }}>₹76,430</text>
      <text x="70" y="82" textAnchor="middle" className="fill-slate-400" style={{ fontSize: 9 }}>Total spent</text>
    </svg>
  );
}

/** Monthly trend. Each series draws itself in on scroll via the .m-draw keyframe. */
function TrendChart() {
  const series = [
    { color: '#16A34A', points: '0,54 44,40 88,46 132,26 176,32 220,14' },
    { color: '#EF4444', points: '0,62 44,58 88,66 132,52 176,60 220,44' },
    { color: '#2563EB', points: '0,72 44,68 88,74 132,60 176,64 220,52' },
  ];
  return (
    <svg viewBox="0 0 220 92" className="w-full h-[92px]" role="img" aria-label="Monthly trend for income, expenses and savings">
      {[22, 44, 66].map((y) => (
        <line key={y} x1="0" y1={y} x2="220" y2={y} stroke="#EEF2F7" strokeWidth="1" />
      ))}
      {series.map((s, i) => (
        <polyline
          key={s.color}
          points={s.points}
          fill="none"
          stroke={s.color}
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          className="m-draw"
          // 600 comfortably exceeds the real path length, so the dash fully hides the line
          // before the animation reveals it. Staggered so the three don't draw as one stroke.
          style={{ strokeDasharray: 600, strokeDashoffset: 600, animationDelay: `${i * 180}ms` }}
        />
      ))}
    </svg>
  );
}

/**
 * Two independent dials, because the page needs the product to appear to grow twice over:
 *
 * `level` — how much of the application is shown. 'simple' is the headline numbers and the trend,
 * 'expanded' adds where the money went, 'complete' is the whole thing with a sidebar. Used at
 * increasing depth down the page, so the reader watches the product accumulate rather than seeing
 * one screenshot three times.
 *
 * `progressive` — whether THIS instance assembles itself as it scrolls into view. Used in the
 * hero, where the argument is that a statement becomes numbers and numbers become a pattern.
 * Showing the finished article states that; building it demonstrates it. Off elsewhere, because by
 * the showcase the point is completeness rather than sequence.
 */
export type MockLevel = 'simple' | 'expanded' | 'complete';

const LEVEL_LABEL: Record<MockLevel, string> = {
  simple: 'The Finora dashboard showing income, expenses, savings and cash flow for the month, with a monthly trend chart.',
  expanded: 'The Finora dashboard showing the month\'s income, expenses, savings and cash flow, a spending-by-category breakdown and a monthly trend chart.',
  complete: 'The full Finora dashboard: income, expenses, savings and cash flow, spending by category, a monthly trend chart, recent transactions, budget progress and an insight.',
};

export function DashboardMock({ level = 'complete', withSidebar = false, progressive = false }: {
  level?: MockLevel;
  withSidebar?: boolean;
  progressive?: boolean;
}) {
  const showSpending = level !== 'simple';
  const showLedger = level === 'complete';
  const { ref, step } = useStagedReveal(progressive ? 4 : 0, 520);

  // Panels appear in place rather than shifting layout: opacity and a small lift only, so nothing
  // below them jumps as each one arrives.
  const at = (n: number): CSSProperties => (!progressive || step >= n
    ? { opacity: 1, transform: 'none', transition: 'opacity 420ms ease, transform 420ms ease' }
    : { opacity: 0, transform: 'translateY(10px)', transition: 'opacity 420ms ease, transform 420ms ease' });

  return (
    <div
      ref={ref}
      className="rounded-[18px] border border-[#E6EAF2] bg-white overflow-hidden w-full"
      style={{ boxShadow: 'var(--m-shadow-hero)' }}
      // One image to assistive tech. Reading forty synthetic numbers aloud tells a screen-reader
      // user nothing about the product; the surrounding copy is what carries the meaning.
      role="img"
      // Described per level -- a fixed label would narrate panels this instance doesn't render.
      aria-label={LEVEL_LABEL[level]}
    >
      {/* Browser chrome. Sold as a screenshot, so it needs the frame to read as one. */}
      <div className="flex items-center gap-2 px-4 py-2.5 border-b border-[#E6EAF2] bg-[#F8FAFC]">
        <span className="w-2.5 h-2.5 rounded-full bg-[#F87171]" />
        <span className="w-2.5 h-2.5 rounded-full bg-[#FBBF24]" />
        <span className="w-2.5 h-2.5 rounded-full bg-[#34D399]" />
        <span className="ml-3 text-[10px] text-slate-400 truncate">app.finoratech.info/dashboard</span>
      </div>

      <div className="flex">
        {withSidebar ? (
          <aside className="hidden md:block w-40 shrink-0 border-r border-[#E6EAF2] bg-white py-4">
            <p className="px-4 mb-3 text-[11px] font-bold tracking-tight text-slate-900">Finora</p>
            {NAV.map((item, i) => (
              <p
                key={item}
                className={`px-4 py-1.5 text-[11px] ${i === 0 ? 'text-[#2563EB] bg-[#EFF5FF] font-semibold' : 'text-slate-500'}`}
              >
                {item}
              </p>
            ))}
          </aside>
        ) : null}

        <div className="flex-1 min-w-0 p-4 sm:p-5">
          <div className="flex items-center justify-between mb-4">
            <p className="text-sm font-bold text-slate-900">Overview</p>
            <span className="text-[10px] text-slate-400 border border-[#E6EAF2] rounded-md px-2 py-1">Apr 2026</span>
          </div>

          <div className="grid grid-cols-2 lg:grid-cols-4 gap-2.5 mb-4" style={at(1)}>
            {[
              { label: 'Total Income', value: 124500, delta: '+12% vs Mar', tone: 'text-[#16A34A]' },
              { label: 'Total Expenses', value: 76430, delta: '+4% vs Mar', tone: 'text-[#EF4444]' },
              { label: 'Net Savings', value: 48070, delta: '+26% vs Mar', tone: 'text-[#16A34A]' },
              { label: 'Net Cash Flow', value: 48070, delta: '+26% vs Mar', tone: 'text-[#16A34A]' },
            ].map((k) => (
              <div key={k.label} className="rounded-xl border border-[#E6EAF2] px-3 py-2.5">
                <p className="text-[9px] uppercase tracking-wide text-slate-400">{k.label}</p>
                <p className="text-[15px] font-bold text-slate-900 mt-1">
                  <CountUp value={k.value} prefix="₹" />
                </p>
                <p className={`text-[9px] mt-0.5 ${k.tone}`}>{k.delta}</p>
              </div>
            ))}
          </div>

          <div className={`grid gap-2.5 mb-2.5 ${showSpending ? 'lg:grid-cols-2' : ''}`}>
            {showSpending ? (
            <div className="rounded-xl border border-[#E6EAF2] p-3.5" style={at(2)}>
              <p className="text-[10px] uppercase tracking-wide text-slate-400 mb-2.5">Spending Overview</p>
              <div className="flex items-center gap-4">
                <Donut />
                <div className="flex-1 min-w-0 space-y-1.5">
                  {CATEGORIES.map((c) => (
                    <div key={c.label} className="flex items-center gap-2 text-[10px]">
                      <span className="w-2 h-2 rounded-full shrink-0" style={{ background: c.color }} />
                      <span className="text-slate-600 truncate flex-1">{c.label}</span>
                      <span className="text-slate-400">{c.pct}%</span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
            ) : null}

            <div className="rounded-xl border border-[#E6EAF2] p-3.5" style={at(3)}>
              <p className="text-[10px] uppercase tracking-wide text-slate-400 mb-2">Monthly Trend</p>
              <TrendChart />
              <div className="flex gap-3 mt-1.5">
                {[['Income', '#16A34A'], ['Expenses', '#EF4444'], ['Savings', '#2563EB']].map(([label, color]) => (
                  <span key={label} className="flex items-center gap-1 text-[9px] text-slate-500">
                    <span className="w-2 h-[2px] rounded-full" style={{ background: color }} />
                    {label}
                  </span>
                ))}
              </div>
            </div>
          </div>

          {showLedger ? (
          <div className="grid lg:grid-cols-2 gap-2.5" style={at(4)}>
            <div className="rounded-xl border border-[#E6EAF2] p-3.5">
              <p className="text-[10px] uppercase tracking-wide text-slate-400 mb-2">Recent Transactions</p>
              <div className="space-y-1.5">
                {TRANSACTIONS.map((t) => (
                  <div key={t.name} className="flex items-center justify-between gap-2">
                    <div className="min-w-0">
                      <p className="text-[11px] font-medium text-slate-800 truncate">{t.name}</p>
                      <p className="text-[9px] text-slate-400 truncate">{t.meta}</p>
                    </div>
                    <p className={`text-[11px] font-semibold shrink-0 ${t.tone}`}>{t.amount}</p>
                  </div>
                ))}
              </div>
            </div>

            <div className="space-y-2.5">
              <div className="rounded-xl border border-[#E6EAF2] p-3.5">
                <p className="text-[10px] uppercase tracking-wide text-slate-400 mb-2">Budget Progress</p>
                {[
                  { label: 'Food & Dining', pct: 78, color: '#16A34A' },
                  { label: 'Shopping', pct: 94, color: '#F59E0B' },
                  { label: 'Travel', pct: 112, color: '#EF4444' },
                ].map((b) => (
                  <div key={b.label} className="mb-2 last:mb-0">
                    <div className="flex justify-between text-[9px] text-slate-500 mb-1">
                      <span>{b.label}</span><span>{b.pct}%</span>
                    </div>
                    <div className="h-1.5 rounded-full bg-[#EEF2F7] overflow-hidden">
                      <div className="h-full rounded-full" style={{ width: `${Math.min(100, b.pct)}%`, background: b.color }} />
                    </div>
                  </div>
                ))}
              </div>
              <div className="rounded-xl border border-[#DBEAFE] bg-[#EFF5FF] p-3">
                <p className="text-[9px] uppercase tracking-wide text-[#2563EB] font-semibold mb-1">Insight</p>
                <p className="text-[10px] text-slate-600 leading-relaxed">
                  You spent 18% less on Food &amp; Dining than last month.
                </p>
              </div>
            </div>
          </div>
          ) : null}
        </div>
      </div>
    </div>
  );
}
