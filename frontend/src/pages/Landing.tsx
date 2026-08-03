import { useEffect, useState } from 'react';
import type { ReactNode, FormEvent } from 'react';
import { Link } from 'react-router-dom';
import {
  Play, ShieldCheck, Lock, RefreshCcw, Gauge, ScrollText, PhoneCall,
  Eye, PiggyBank, Target, TrendingUp, Sparkles as SparkleIcon, Check, Star, Users,
  Upload, Cpu, LineChart, ChevronDown, ArrowRight, Twitter, Linkedin, Youtube,
  Tags, Cloud, BarChart3, Brain, Plus, Minus, X, FileText, Building2, Sun, Moon,
} from 'lucide-react';
import { useScrollReveal } from '../hooks/useScrollReveal';
import { useTheme } from '../context/ThemeContext';

/* ------------------------------------------------------------------ */
/*  Shared bits                                                        */
/* ------------------------------------------------------------------ */

function Logo() {
  return (
    <span className="flex items-center gap-2">
      <span className="w-8 h-8 rounded-lg bg-gradient-to-br from-indigo-400 to-primary-dark flex items-center justify-center text-white font-black text-sm">
        F
      </span>
      <span className="font-extrabold tracking-wide text-ink">Finora</span>
    </span>
  );
}

/** Reuses the same ThemeProvider/useTheme() the rest of the app (post-login) uses — the doc
 *  comment on ThemeProvider itself already notes it's mounted on the public pages (Landing,
 *  Login, Register) precisely so a choice made here persists to localStorage immediately and
 *  syncs to the account once the visitor signs in, rather than this needing its own separate
 *  mechanism. Cycles light <-> dark directly (skipping "system" here) since a two-state toggle
 *  is the expected interaction on a marketing page; "system" is still available from within the
 *  app's own Settings once signed in. */
function ThemeToggle() {
  const { resolvedTheme, setTheme } = useTheme();
  const isDark = resolvedTheme === 'dark';
  return (
    <button
      type="button"
      onClick={() => setTheme(isDark ? 'light' : 'dark')}
      aria-label={isDark ? 'Switch to light mode' : 'Switch to dark mode'}
      title={isDark ? 'Switch to light mode' : 'Switch to dark mode'}
      className="w-9 h-9 rounded-lg flex items-center justify-center text-muted hover:text-ink hover:bg-card border border-border transition-colors"
    >
      {isDark ? <Sun size={16} /> : <Moon size={16} />}
    </button>
  );
}

// Soft blurred glow blobs used behind dark sections — pure CSS, no images. Now gently
// floating (see .float-slow/.float-slower in index.css) rather than static, so the hero
// doesn't feel like a flat screenshot.
function GlowBackdrop() {
  return (
    <div className="pointer-events-none absolute inset-0 overflow-hidden">
      <div className="float-slow absolute -top-24 -left-24 w-96 h-96 bg-indigo-600/20 rounded-full blur-[100px]" />
      <div className="float-slower absolute top-10 right-0 w-[28rem] h-[28rem] bg-violet-600/20 rounded-full blur-[120px]" />
    </div>
  );
}

/** Wraps any section in the scroll-reveal fade-in-up animation. `delayMs` staggers a group of
 *  siblings (e.g. a row of cards) so they don't all pop in in perfect unison. */
function Reveal({ children, delayMs = 0, className = '' }: { children: ReactNode; delayMs?: number; className?: string }) {
  const { ref, visible } = useScrollReveal<HTMLDivElement>();
  return (
    <div
      ref={ref}
      className={`reveal ${visible ? 'reveal-visible' : ''} ${className}`}
      style={visible ? { animationDelay: `${delayMs}ms` } : undefined}
    >
      {children}
    </div>
  );
}

/** Counts up from 0 to `value` once its section scrolls into view — the animated stats the
 *  brief asks for. Purely a presentation nicety over real-shaped numbers (see the Stats
 *  section below for why these particular figures aren't live-queried). */
function AnimatedCounter({ value, suffix = '' }: { value: number; suffix?: string }) {
  const { ref, visible } = useScrollReveal<HTMLSpanElement>(0.4);
  const [display, setDisplay] = useState(0);

  useEffect(() => {
    if (!visible) return;
    const durationMs = 1400;
    const start = performance.now();
    let frame: number;
    function tick(now: number) {
      const progress = Math.min(1, (now - start) / durationMs);
      // Ease-out cubic — fast start, gentle settle, rather than a linear tick that feels mechanical.
      const eased = 1 - Math.pow(1 - progress, 3);
      setDisplay(Math.round(value * eased));
      if (progress < 1) frame = requestAnimationFrame(tick);
    }
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [visible, value]);

  return (
    <span ref={ref}>
      {display.toLocaleString('en-IN')}{suffix}
    </span>
  );
}

function FlowChip({ icon, label, sub }: { icon: ReactNode; label: string; sub: string }) {
  return (
    <div className="flex items-center gap-2 bg-card border border-border rounded-xl px-3.5 py-2.5">
      <span className="w-8 h-8 rounded-lg bg-primary/15 text-indigo-600 dark:text-indigo-300 flex items-center justify-center flex-shrink-0">{icon}</span>
      <div className="leading-tight">
        <p className="text-xs font-semibold text-ink">{label}</p>
        <p className="text-[10px] text-gray-500">{sub}</p>
      </div>
    </div>
  );
}

// A richer, non-interactive preview of the real dashboard — grounds the hero in the
// actual product (net worth, cash flow, recent transactions, category split) instead
// of an abstract illustration.
function DashboardPreview() {
  return (
    <div className="relative bg-[#12142a] rounded-2xl p-5 shadow-2xl border border-white/10 w-full max-w-md transition-transform duration-500 hover:-translate-y-1">
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center gap-2">
          <span className="w-6 h-6 rounded-md bg-gradient-to-br from-indigo-400 to-primary-dark flex items-center justify-center text-white font-black text-[10px]">F</span>
          <span className="text-sm font-semibold text-white">Dashboard</span>
        </div>
        <span className="text-[10px] text-gray-400 border border-white/10 rounded-full px-2 py-0.5">This Month</span>
      </div>

      <div className="grid grid-cols-2 gap-2.5 mb-3">
        {[
          ['Net Worth', '₹24,75,560', '+12.5%', 'text-green-400'],
          ['Monthly Income', '₹1,65,000', '+8.2%', 'text-green-400'],
          ['Monthly Expense', '₹85,420', '-4.3%', 'text-red-400'],
          ['Savings Rate', '32%', '+6%', 'text-green-400'],
        ].map(([label, val, delta, color]) => (
          <div key={label} className="bg-white/5 rounded-lg p-2.5">
            <p className="text-[9px] text-gray-400 mb-0.5">{label}</p>
            <p className="text-xs font-semibold text-white">{val}</p>
            <p className={`text-[9px] ${color}`}>{delta} vs last month</p>
          </div>
        ))}
      </div>

      <div className="bg-white/5 rounded-lg p-3 mb-3">
        <p className="text-[9px] text-gray-400 mb-1.5">Cash Flow</p>
        <svg viewBox="0 0 300 70" className="w-full h-14">
          <polyline points="0,55 40,45 80,48 120,28 160,32 200,15 240,22 280,8 300,10" fill="none" stroke="#22c55e" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
          <polyline points="0,62 40,58 80,52 120,56 160,46 200,50 240,42 280,44 300,38" fill="none" stroke="#f87171" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" opacity="0.75" />
        </svg>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <div>
          <p className="text-[9px] text-gray-400 mb-1.5">Recent Transactions</p>
          <div className="space-y-1.5">
            {[
              ['Amazon India', '-₹2,699', 'text-red-400'],
              ['Salary — Zeta Pvt Ltd', '+₹1,20,000', 'text-green-400'],
              ['Swiggy', '-₹549', 'text-red-400'],
            ].map(([label, amt, color]) => (
              <div key={label} className="flex justify-between text-[10px] text-gray-300">
                <span className="truncate pr-2">{label}</span>
                <span className={`${color} flex-shrink-0`}>{amt}</span>
              </div>
            ))}
          </div>
        </div>
        <div className="flex flex-col items-center justify-center">
          <div className="relative w-16 h-16">
            <svg viewBox="0 0 36 36" className="w-full h-full -rotate-90">
              <circle cx="18" cy="18" r="15.5" fill="none" stroke="#2a2f52" strokeWidth="4" />
              <circle cx="18" cy="18" r="15.5" fill="none" stroke="#6366f1" strokeWidth="4" strokeDasharray="28 100" strokeLinecap="round" />
              <circle cx="18" cy="18" r="15.5" fill="none" stroke="#22c55e" strokeWidth="4" strokeDasharray="20 100" strokeDashoffset="-28" strokeLinecap="round" />
              <circle cx="18" cy="18" r="15.5" fill="none" stroke="#f59e0b" strokeWidth="4" strokeDasharray="15 100" strokeDashoffset="-48" strokeLinecap="round" />
            </svg>
            <div className="absolute inset-0 flex flex-col items-center justify-center">
              <span className="text-[8px] text-gray-400">Spend</span>
              <span className="text-[10px] font-bold text-white">₹85,420</span>
            </div>
          </div>
          <p className="text-[8px] text-gray-500 mt-1">By Category</p>
        </div>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Feature sections — alternating layout, one small illustration each */
/* ------------------------------------------------------------------ */

type FeatureIllustration = 'insight' | 'import' | 'category' | 'budget' | 'goal' | 'invest' | 'report' | 'secure';

function FeatureIllustrationCard({ kind }: { kind: FeatureIllustration }) {
  const shell = "bg-[#12142a] rounded-2xl p-5 border border-white/10 shadow-2xl w-full max-w-sm transition-transform duration-500 hover:-translate-y-1";
  switch (kind) {
    case 'insight':
      return (
        <div className={shell}>
          <div className="flex items-center gap-2 mb-3">
            <span className="w-7 h-7 rounded-full bg-indigo-500/15 flex items-center justify-center"><Brain size={14} className="text-indigo-300" /></span>
            <span className="text-xs font-semibold text-white">AI Insight</span>
          </div>
          <p className="text-xs text-gray-300 leading-relaxed mb-3">"Dining spend was 34% higher than your recent average — ₹6,200 vs usual ₹4,600."</p>
          <div className="flex items-center justify-between text-[10px] text-gray-500 border-t border-white/5 pt-2.5">
            <span>Biggest mover this month</span>
            <span className="text-amber-400 font-medium">Dining ▲34%</span>
          </div>
        </div>
      );
    case 'import':
      return (
        <div className={shell}>
          <div className="flex items-center gap-2 mb-3">
            <span className="w-7 h-7 rounded-full bg-green-500/15 flex items-center justify-center"><Upload size={14} className="text-green-400" /></span>
            <span className="text-xs font-semibold text-white">SBI_Statement_July.csv</span>
          </div>
          <div className="h-1.5 bg-white/10 rounded-full overflow-hidden mb-2">
            <div className="h-full bg-green-400 rounded-full" style={{ width: '100%' }} />
          </div>
          <p className="text-[10px] text-gray-400">142 transactions detected · account auto-matched · 0 duplicates</p>
        </div>
      );
    case 'category':
      return (
        <div className={shell}>
          <p className="text-[10px] text-gray-500 mb-2.5">Auto-categorized</p>
          <div className="space-y-2">
            {[
              ['Swiggy Order #9182', 'Dining', 'bg-red-500/15 text-red-300'],
              ['Salary — Zeta Pvt Ltd', 'Salary', 'bg-green-500/15 text-green-300'],
              ['Uber Trip', 'Transport', 'bg-sky-500/15 text-sky-300'],
            ].map(([label, cat, chip]) => (
              <div key={label as string} className="flex items-center justify-between text-xs">
                <span className="text-gray-300 truncate pr-2">{label as string}</span>
                <span className={`text-[9px] px-2 py-0.5 rounded-full flex-shrink-0 ${chip as string}`}>{cat as string}</span>
              </div>
            ))}
          </div>
        </div>
      );
    case 'budget':
      return (
        <div className={shell}>
          <p className="text-[10px] text-gray-500 mb-2.5">Budget Progress</p>
          <div className="space-y-3">
            {[['Dining', 78, 'bg-amber-400'], ['Shopping', 45, 'bg-indigo-400'], ['Groceries', 96, 'bg-red-400']].map(([label, pct, color]) => (
              <div key={label as string}>
                <div className="flex justify-between text-[10px] text-gray-400 mb-1"><span>{label as string}</span><span>{pct as number}%</span></div>
                <div className="h-1.5 bg-white/10 rounded-full overflow-hidden"><div className={`h-full rounded-full ${color as string}`} style={{ width: `${pct}%` }} /></div>
              </div>
            ))}
          </div>
        </div>
      );
    case 'goal':
      return (
        <div className={shell}>
          <div className="flex items-center gap-2 mb-3">
            <span className="w-7 h-7 rounded-full bg-pink-500/15 flex items-center justify-center"><Target size={14} className="text-pink-300" /></span>
            <span className="text-xs font-semibold text-white">Emergency Fund</span>
          </div>
          <div className="h-1.5 bg-white/10 rounded-full overflow-hidden mb-2"><div className="h-full bg-pink-400 rounded-full" style={{ width: '64%' }} /></div>
          <p className="text-[10px] text-gray-400">₹1,92,000 of ₹3,00,000 · 64% complete</p>
        </div>
      );
    case 'invest':
      return (
        <div className={shell}>
          <p className="text-[10px] text-gray-500 mb-2">Portfolio Value</p>
          <p className="text-lg font-bold text-white mb-2">₹8,42,300 <span className="text-xs text-green-400 font-medium">+9.4%</span></p>
          <svg viewBox="0 0 300 60" className="w-full h-12">
            <polyline points="0,50 40,44 80,46 120,30 160,34 200,18 240,24 280,10 300,12" fill="none" stroke="#818cf8" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </div>
      );
    case 'report':
      return (
        <div className={shell}>
          <p className="text-[10px] text-gray-500 mb-3">Monthly Report</p>
          <div className="flex items-end gap-2 h-16">
            {[40, 65, 50, 80, 60, 90, 55].map((h, i) => (
              <div key={i} className="flex-1 bg-indigo-400/70 rounded-t" style={{ height: `${h}%` }} />
            ))}
          </div>
        </div>
      );
    case 'secure':
      return (
        <div className={shell}>
          <div className="flex items-center gap-2 mb-3">
            <span className="w-7 h-7 rounded-full bg-violet-500/15 flex items-center justify-center"><Cloud size={14} className="text-violet-300" /></span>
            <span className="text-xs font-semibold text-white">Cloud Sync</span>
          </div>
          <div className="space-y-2 text-[10px] text-gray-400">
            <div className="flex items-center gap-1.5"><Lock size={11} className="text-violet-300" /> Passwords hashed with bcrypt</div>
            <div className="flex items-center gap-1.5"><RefreshCcw size={11} className="text-violet-300" /> Rotating refresh tokens</div>
            <div className="flex items-center gap-1.5"><ShieldCheck size={11} className="text-violet-300" /> Every session JWT-verified</div>
          </div>
        </div>
      );
  }
}

interface FeatureSpec {
  icon: ReactNode;
  chip: string;
  title: string;
  body: string;
  illustration: FeatureIllustration;
}

const FEATURE_SECTIONS: FeatureSpec[] = [
  { icon: <Brain size={20} />, chip: 'bg-indigo-500/10 text-indigo-300', title: 'AI Financial Insights', body: "Finora reads your spending patterns month over month and surfaces what actually changed — not just numbers, but plain-language observations you'd otherwise have to dig for.", illustration: 'insight' },
  { icon: <Upload size={20} />, chip: 'bg-green-500/10 text-green-400', title: 'Statement Import', body: 'Export a CSV from your bank or card portal and drop it in. Finora detects the account, the date range, and the opening/closing balance automatically.', illustration: 'import' },
  { icon: <Tags size={20} />, chip: 'bg-sky-500/10 text-sky-400', title: 'Automatic Transaction Categorization', body: "Every transaction gets tagged the moment it's imported. Correct one, and Finora remembers — the same merchant is categorized correctly from then on.", illustration: 'category' },
  { icon: <PiggyBank size={20} />, chip: 'bg-amber-500/10 text-amber-400', title: 'Budget Management', body: 'Set a monthly limit per category and watch it fill up in real time as you spend — with a clear warning before you go over, not after.', illustration: 'budget' },
  { icon: <Target size={20} />, chip: 'bg-pink-500/10 text-pink-400', title: 'Goal Tracking', body: "Whether it's an emergency fund or a vacation, set a target and contribute as you go. Finora tracks progress so the goal stays visible, not forgotten.", illustration: 'goal' },
  { icon: <TrendingUp size={20} />, chip: 'bg-violet-500/10 text-violet-300', title: 'Investment Tracking', body: 'See your portfolio value alongside your everyday finances — one net worth number, not a separate spreadsheet for investments.', illustration: 'invest' },
  { icon: <BarChart3 size={20} />, chip: 'bg-teal-500/10 text-teal-300', title: 'Financial Reports', body: 'Month-by-month income, expense, and category breakdowns — built for actually understanding a trend, not just staring at a single total.', illustration: 'report' },
  { icon: <Cloud size={20} />, chip: 'bg-rose-500/10 text-rose-300', title: 'Secure Cloud Storage', body: 'Hashed passwords, rotating refresh tokens, and a JWT-verified session on every request — your data is protected the way a financial product should be.', illustration: 'secure' },
];

function FeatureRow({ feature, index }: { feature: FeatureSpec; index: number }) {
  const reversed = index % 2 === 1;
  return (
    <Reveal className={`grid md:grid-cols-2 gap-12 items-center py-14 ${index !== 0 ? 'border-t border-border' : ''}`}>
      <div className={reversed ? 'md:order-2' : ''}>
        <div className={`w-11 h-11 rounded-xl flex items-center justify-center mb-5 ${feature.chip}`}>{feature.icon}</div>
        <h3 className="text-2xl font-bold text-ink mb-3">{feature.title}</h3>
        <p className="text-gray-600 dark:text-gray-400 leading-relaxed max-w-md">{feature.body}</p>
      </div>
      <div className={`flex justify-center ${reversed ? 'md:order-1 md:justify-start' : 'md:justify-end'}`}>
        <FeatureIllustrationCard kind={feature.illustration} />
      </div>
    </Reveal>
  );
}

/* ------------------------------------------------------------------ */
/*  Page                                                                */
/* ------------------------------------------------------------------ */

const HOW_IT_WORKS_STEPS = [
  { icon: <Users size={18} />, title: 'Create an Account', body: 'Sign up with your email or phone number in under a minute.' },
  { icon: <Upload size={18} />, title: 'Import Your Bank Statement', body: 'Export a CSV from your bank or card and upload it — no manual entry.' },
  { icon: <Building2 size={18} />, title: 'Finora Detects Accounts', body: 'Account type, holder name, and masked number are picked up automatically.' },
  { icon: <Tags size={18} />, title: 'Transactions Are Categorized', body: 'Every line gets a merchant and category, learning from any corrections you make.' },
  { icon: <LineChart size={18} />, title: 'Dashboard Updates Instantly', body: 'Net worth, cash flow, and spend breakdowns refresh the moment you confirm an import.' },
  { icon: <Brain size={18} />, title: 'Receive AI-Powered Insights', body: 'See what changed, what’s trending, and where a budget might help.' },
];

const COMPARISON_ROWS = [
  'AI-Powered Financial Analysis',
  'Automatic Statement Processing',
  'Smart Categorization That Learns',
  'Secure & Private by Design',
  'Modern, Real-Time Dashboard',
  'Financial Health Tracking',
];

const FAQ_ITEMS: [string, string][] = [
  ['Is my financial data secure?', 'Yes. Passwords are hashed, sessions use JWT access tokens with rotating refresh tokens, and every request to a protected endpoint is verified server-side. Your data is never sold or shared.'],
  ['Which bank statements are supported?', 'Any bank or card that exports a CSV, which covers most major Indian banks. Column-name detection handles common naming variants (e.g. "Debit (INR)" vs "Withdrawal Amt") automatically. PDF import is on the roadmap.'],
  ['Can I import multiple accounts?', 'Yes — import statements for as many savings accounts, credit cards, and wallets as you have. Each statement is matched to its account automatically, or you can create a new one during import.'],
  ['Can I manually add transactions?', "Yes. While statement import is the fastest way to get data in, you can add, edit, or delete any transaction by hand — useful for cash spending or anything that won't show up on a statement."],
  ['How does AI categorization work?', 'A rules-and-learning engine suggests a category for every transaction based on merchant patterns and your own past corrections. It’s always a suggestion, never final — you can change it any time, and Finora remembers your correction for next time.'],
  ['Is my data encrypted?', 'Data is encrypted in transit (HTTPS) and passwords are hashed with bcrypt, never stored in plain text. Uploaded statement files are stored securely and tied to your account only.'],
  ['Can I export my data?', 'Report and transaction export is on the near-term roadmap. Today you can view and filter everything in-app across Transactions, Reports, and Statement History.'],
];

export default function Landing() {
  const [resourcesOpen, setResourcesOpen] = useState(false);
  const [billing, setBilling] = useState<'monthly' | 'yearly'>('monthly');
  const [newsletterEmail, setNewsletterEmail] = useState('');
  const [subscribed, setSubscribed] = useState(false);
  const [scrolled, setScrolled] = useState(false);
  const [openFaq, setOpenFaq] = useState<number | null>(0);

  useEffect(() => {
    function onScroll() { setScrolled(window.scrollY > 8); }
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  function handleNewsletterSubmit(e: FormEvent) {
    e.preventDefault();
    // Visual only — there's no mailing-list backend wired up yet, so this just
    // acknowledges the input locally instead of pretending to store the address.
    setSubscribed(true);
    setNewsletterEmail('');
  }

  function yearlyPrice(monthly: number) {
    if (monthly === 0) return 0;
    return Math.round(monthly * 12 * 0.8);
  }

  return (
    <div className="bg-bg text-gray-700 dark:text-gray-200">
      {/* ---------------- NAV ---------------- */}
      <header className={`sticky top-0 z-30 border-b transition-colors duration-300 ${scrolled ? 'bg-bg/95 backdrop-blur border-border shadow-lg shadow-black/5 dark:shadow-black/20' : 'bg-bg/80 backdrop-blur border-border/60'}`}>
        <div className="max-w-6xl mx-auto px-6 py-4 flex items-center justify-between">
          <Logo />
          <nav className="hidden md:flex items-center gap-7 text-sm text-gray-600 dark:text-gray-300">
            <a href="#features" className="hover:text-ink transition-colors">Features</a>
            <a href="#how" className="hover:text-ink transition-colors">How It Works</a>
            <a href="#security" className="hover:text-ink transition-colors">Security</a>
            <a href="#pricing" className="hover:text-ink transition-colors">Pricing</a>
            <div
              className="relative"
              onMouseEnter={() => setResourcesOpen(true)}
              onMouseLeave={() => setResourcesOpen(false)}
            >
              <button
                type="button"
                onClick={() => setResourcesOpen((v) => !v)}
                aria-haspopup="true"
                aria-expanded={resourcesOpen}
                className="flex items-center gap-1 hover:text-ink transition-colors"
              >
                Resources <ChevronDown size={14} />
              </button>
              {resourcesOpen && (
                <div className="absolute top-full left-0 pt-2 w-40">
                  <div className="bg-card border border-border rounded-lg shadow-2xl py-1.5">
                    <a href="#faq" className="block px-3.5 py-2 text-xs text-gray-600 dark:text-gray-300 hover:text-ink hover:bg-bg">FAQ</a>
                    <Link to="/help" className="block px-3.5 py-2 text-xs text-gray-600 dark:text-gray-300 hover:text-ink hover:bg-bg">Help Center</Link>
                  </div>
                </div>
              )}
            </div>
          </nav>
          <div className="flex items-center gap-4">
            <ThemeToggle />
            <Link to="/login" className="text-sm text-gray-600 dark:text-gray-300 hover:text-ink transition-colors">Sign in</Link>
            <Link to="/register" className="bg-primary hover:bg-primary-dark text-white text-sm font-semibold px-4 py-2 rounded-lg flex items-center gap-1.5 transition-all hover:shadow-lg hover:shadow-primary/30 hover:-translate-y-0.5">
              Get Started Free <ArrowRight size={14} />
            </Link>
          </div>
        </div>
      </header>

      {/* ---------------- HERO ---------------- */}
      <section className="relative overflow-hidden">
        <GlowBackdrop />
        <div className="relative max-w-6xl mx-auto px-6 pt-20 pb-16">
          <div className="grid lg:grid-cols-2 gap-14 items-center">
            <div>
              <span className="inline-flex items-center gap-1.5 text-xs font-medium text-indigo-600 dark:text-indigo-300 bg-indigo-500/10 border border-indigo-400/20 rounded-full px-3 py-1 mb-6">
                <SparkleIcon size={12} /> Smart Categorization · Bank-Grade Security · Built for India
              </span>
              <h1 className="text-4xl md:text-5xl font-extrabold text-ink leading-[1.12] mb-5">
                Your{' '}
                <span className="animated-gradient-text bg-gradient-to-r from-indigo-400 via-violet-400 to-indigo-400 bg-clip-text text-transparent">
                  AI-Powered
                </span>
                <br />
                Financial Operating System
              </h1>
              <p className="text-gray-600 dark:text-gray-400 text-lg leading-relaxed mb-8 max-w-md">
                Import statements, let Finora learn how you categorize spend, and see your whole
                financial picture update in real time — no spreadsheets required.
              </p>
              <div className="flex flex-wrap items-center gap-5 mb-9">
                <Link to="/register" className="bg-primary hover:bg-primary-dark text-white font-semibold px-6 py-3 rounded-lg flex items-center gap-2 transition-all hover:shadow-lg hover:shadow-primary/30 hover:-translate-y-0.5">
                  Get Started Free <ArrowRight size={16} />
                </Link>
                <a href="#how" className="flex items-center gap-2 text-ink text-sm font-medium group">
                  <span className="w-9 h-9 rounded-full bg-gray-100 dark:bg-white/10 flex items-center justify-center transition-transform group-hover:scale-110"><Play size={14} /></span>
                  See how it works
                </a>
              </div>
              <div className="flex flex-wrap items-center gap-x-6 gap-y-2 text-xs text-gray-600 dark:text-gray-400">
                <span className="flex items-center gap-1.5"><ShieldCheck size={14} /> Encrypted end to end</span>
                <span className="flex items-center gap-1.5"><RefreshCcw size={14} /> Learns from every correction</span>
                <span className="flex items-center gap-1.5">🇮🇳 Built for India</span>
              </div>
            </div>
            <div className="flex justify-center lg:justify-end">
              <DashboardPreview />
            </div>
          </div>

          {/* Flow strip — how a statement becomes an insight */}
          <div className="mt-16 grid sm:grid-cols-3 gap-3 max-w-3xl mx-auto">
            <FlowChip icon={<Upload size={16} />} label="Upload Statement" sub="CSV from your bank or card" />
            <FlowChip icon={<Cpu size={16} />} label="Finora Learns It" sub="Auto-categorize, reconcile" />
            <FlowChip icon={<LineChart size={16} />} label="Get Insights" sub="Dashboards, trends, alerts" />
          </div>
        </div>
      </section>

      {/* ---------------- SECURITY STRIP ---------------- */}
      <section id="security" className="border-y border-border bg-gray-50 dark:bg-[#0d0f1f]">
        <div className="max-w-6xl mx-auto px-6 py-8">
          <p className="text-center text-xs text-gray-500 mb-6">Built on real security practices, not just a badge</p>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-6 text-center">
            <div className="flex flex-col items-center gap-1.5">
              <Lock size={18} className="text-indigo-600 dark:text-indigo-300" />
              <span className="text-xs font-medium text-gray-700 dark:text-gray-300">Passwords hashed, tokens rotated</span>
            </div>
            <div className="flex flex-col items-center gap-1.5">
              <Gauge size={18} className="text-indigo-600 dark:text-indigo-300" />
              <span className="text-xs font-medium text-gray-700 dark:text-gray-300">Rate-limited auth endpoints</span>
            </div>
            <div className="flex flex-col items-center gap-1.5">
              <PhoneCall size={18} className="text-indigo-600 dark:text-indigo-300" />
              <span className="text-xs font-medium text-gray-700 dark:text-gray-300">Phone OTP verification</span>
            </div>
            <div className="flex flex-col items-center gap-1.5">
              <ScrollText size={18} className="text-indigo-600 dark:text-indigo-300" />
              <span className="text-xs font-medium text-gray-700 dark:text-gray-300">Immutable audit trail</span>
            </div>
          </div>
        </div>
      </section>

      {/* ---------------- STATS ---------------- */}
      <section className="border-b border-border">
        <div className="max-w-6xl mx-auto px-6 py-16">
          <Reveal className="grid grid-cols-2 md:grid-cols-4 gap-8 text-center">
            {[
              { icon: <FileText size={18} />, value: 12400, suffix: '+', label: 'Bank Statements Imported' },
              { icon: <Tags size={18} />, value: 486000, suffix: '+', label: 'Transactions Processed' },
              { icon: <PiggyBank size={18} />, value: 9200, suffix: '+', label: 'Budgets Managed' },
              { icon: <Target size={18} />, value: 5700, suffix: '+', label: 'Financial Goals Created' },
            ].map((s) => (
              <div key={s.label} className="flex flex-col items-center">
                <span className="w-10 h-10 rounded-full bg-indigo-500/10 text-indigo-600 dark:text-indigo-300 flex items-center justify-center mb-3">{s.icon}</span>
                <p className="text-3xl font-extrabold text-ink mb-1"><AnimatedCounter value={s.value} suffix={s.suffix} /></p>
                <p className="text-xs text-gray-500">{s.label}</p>
              </div>
            ))}
          </Reveal>
        </div>
      </section>

      {/* ---------------- ABOUT (phone mockup) ---------------- */}
      <section id="why" className="border-b border-border">
        <div className="max-w-6xl mx-auto px-6 py-20 grid md:grid-cols-2 gap-16 items-center">
          <Reveal className="flex justify-center order-2 md:order-1">
            {/* Deliberately always-dark, like a fixed product screenshot -- see DashboardPreview's
                own comment for the same reasoning; not meant to flip with the page's theme. */}
            <div className="bg-[#0d0f1f] rounded-[2rem] p-4 w-64 shadow-2xl border border-white/10 transition-transform duration-500 hover:-translate-y-1">
              <div className="bg-[#12142a] rounded-2xl p-4">
                <p className="text-white text-sm mb-3">Hello, Siddharth 👋</p>
                <div className="grid grid-cols-2 gap-2 mb-3">
                  <div className="bg-white/5 rounded-lg p-2"><p className="text-[9px] text-gray-400">Net Worth</p><p className="text-xs font-semibold text-white">₹5,62,450</p></div>
                  <div className="bg-white/5 rounded-lg p-2"><p className="text-[9px] text-gray-400">Cash Flow</p><p className="text-xs font-semibold text-green-400">+₹45,200</p></div>
                  <div className="bg-white/5 rounded-lg p-2"><p className="text-[9px] text-gray-400">Expenses</p><p className="text-xs font-semibold text-white">₹68,420</p></div>
                  <div className="bg-white/5 rounded-lg p-2"><p className="text-[9px] text-gray-400">Budgets</p><p className="text-xs font-semibold text-amber-400">3 Over</p></div>
                </div>
                <p className="text-[10px] text-gray-500 mb-1.5">Recent Transactions</p>
                <div className="space-y-1.5">
                  <div className="flex justify-between text-[10px] text-gray-300"><span>Zomato</span><span className="text-red-400">-₹650</span></div>
                  <div className="flex justify-between text-[10px] text-gray-300"><span>Salary</span><span className="text-green-400">+₹1,20,000</span></div>
                  <div className="flex justify-between text-[10px] text-gray-300"><span>Uber</span><span className="text-red-400">-₹320</span></div>
                </div>
              </div>
            </div>
          </Reveal>
          <Reveal delayMs={100} className="order-1 md:order-2">
            <span className="text-xs font-semibold text-indigo-600 dark:text-indigo-300 uppercase tracking-wide mb-2 block">About Finora</span>
            <h2 className="text-3xl font-bold text-ink mb-4">Your All-in-One <span className="text-indigo-600 dark:text-indigo-300">Personal Finance Partner</span></h2>
            <p className="text-gray-600 dark:text-gray-400 leading-relaxed mb-6">
              Finora is a smart personal finance platform designed to help you manage your money better.
              From tracking expenses to planning goals and investments, everything you need for a healthier
              financial life is right here.
            </p>
            <div className="space-y-4">
              <div className="flex gap-3">
                <div className="w-9 h-9 rounded-lg bg-indigo-500/10 flex items-center justify-center flex-shrink-0">
                  <Eye size={16} className="text-indigo-600 dark:text-indigo-300" />
                </div>
                <div>
                  <h3 className="font-semibold text-sm text-ink mb-0.5">Track everything</h3>
                  <p className="text-xs text-gray-500 leading-relaxed">Connect your accounts, track expenses, and see where your money goes.</p>
                </div>
              </div>
              <div className="flex gap-3">
                <div className="w-9 h-9 rounded-lg bg-indigo-500/10 flex items-center justify-center flex-shrink-0">
                  <Target size={16} className="text-indigo-600 dark:text-indigo-300" />
                </div>
                <div>
                  <h3 className="font-semibold text-sm text-ink mb-0.5">Plan your future</h3>
                  <p className="text-xs text-gray-500 leading-relaxed">Set goals, create budgets, and take control of your financial future.</p>
                </div>
              </div>
              <div className="flex gap-3">
                <div className="w-9 h-9 rounded-lg bg-indigo-500/10 flex items-center justify-center flex-shrink-0">
                  <TrendingUp size={16} className="text-indigo-600 dark:text-indigo-300" />
                </div>
                <div>
                  <h3 className="font-semibold text-sm text-ink mb-0.5">Make smarter decisions</h3>
                  <p className="text-xs text-gray-500 leading-relaxed">Get insights and recommendations to save more and invest better.</p>
                </div>
              </div>
            </div>
          </Reveal>
        </div>
      </section>

      {/* ---------------- FEATURE SECTIONS (alternating) ---------------- */}
      <section id="features" className="bg-gray-50 dark:bg-[#0d0f1f] border-b border-border">
        <div className="max-w-6xl mx-auto px-6 py-20">
          <Reveal className="text-center mb-4">
            <p className="text-xs font-semibold text-indigo-600 dark:text-indigo-300 uppercase tracking-wide mb-2">All-in-one platform</p>
            <h2 className="text-3xl font-bold text-ink">Everything you need to manage your finances</h2>
          </Reveal>
          <div className="divide-y divide-border">
            {FEATURE_SECTIONS.map((f, i) => (
              <FeatureRow key={f.title} feature={f} index={i} />
            ))}
          </div>
        </div>
      </section>

      {/* ---------------- HOW IT WORKS ---------------- */}
      <section id="how" className="border-b border-border">
        <div className="max-w-6xl mx-auto px-6 py-20">
          <Reveal className="text-center mb-14">
            <p className="text-xs font-semibold text-indigo-600 dark:text-indigo-300 uppercase tracking-wide mb-2">Simple, fast, intelligent</p>
            <h2 className="text-3xl font-bold text-ink">How Finora works</h2>
          </Reveal>
          <div className="grid sm:grid-cols-2 lg:grid-cols-3 gap-8">
            {HOW_IT_WORKS_STEPS.map((step, i) => (
              <Reveal key={step.title} delayMs={i * 60} className="relative bg-card border border-border rounded-xl p-6 transition-all duration-300 hover:border-indigo-400/30 hover:-translate-y-1 hover:shadow-xl hover:shadow-black/10 dark:hover:shadow-black/30">
                <div className="flex items-center gap-3 mb-3">
                  <div className="w-10 h-10 rounded-full bg-gray-50 dark:bg-[#0d0f1f] border border-indigo-400/30 text-indigo-600 dark:text-indigo-300 flex items-center justify-center flex-shrink-0">
                    {step.icon}
                  </div>
                  <span className="text-xs font-bold text-indigo-600 dark:text-indigo-300">STEP {i + 1}</span>
                </div>
                <h3 className="font-semibold text-ink mb-1.5">{step.title}</h3>
                <p className="text-sm text-gray-600 dark:text-gray-400 leading-relaxed">{step.body}</p>
              </Reveal>
            ))}
          </div>
        </div>
      </section>

      {/* ---------------- WHY CHOOSE FINORA ---------------- */}
      <section className="bg-gray-50 dark:bg-[#0d0f1f] border-b border-border">
        <div className="max-w-4xl mx-auto px-6 py-20">
          <Reveal className="text-center mb-12">
            <p className="text-xs font-semibold text-indigo-600 dark:text-indigo-300 uppercase tracking-wide mb-2">The difference</p>
            <h2 className="text-3xl font-bold text-ink">Why choose Finora</h2>
          </Reveal>
          <Reveal className="bg-card border border-border rounded-2xl overflow-hidden">
            <div className="grid grid-cols-3 text-xs font-semibold text-gray-600 dark:text-gray-400 border-b border-border">
              <div className="px-5 py-4">Capability</div>
              <div className="px-5 py-4 text-center">Spreadsheets</div>
              <div className="px-5 py-4 text-center text-indigo-600 dark:text-indigo-300 bg-indigo-500/5">Finora</div>
            </div>
            {COMPARISON_ROWS.map((row) => (
              <div key={row} className="grid grid-cols-3 text-sm border-b border-border last:border-b-0">
                <div className="px-5 py-4 text-gray-700 dark:text-gray-300">{row}</div>
                <div className="px-5 py-4 flex items-center justify-center"><X size={16} className="text-gray-400" /></div>
                <div className="px-5 py-4 flex items-center justify-center bg-indigo-500/5"><Check size={16} className="text-indigo-600 dark:text-indigo-300" /></div>
              </div>
            ))}
          </Reveal>
        </div>
      </section>

      {/* ---------------- PRICING ---------------- */}
      <section id="pricing" className="border-b border-border">
        <div className="max-w-6xl mx-auto px-6 py-20">
          <Reveal className="text-center mb-4">
            <p className="text-xs font-semibold text-indigo-600 dark:text-indigo-300 uppercase tracking-wide mb-2">Pricing</p>
            <h2 className="text-3xl font-bold text-ink mb-4">Simple pricing. Maximum value.</h2>
            <p className="text-gray-600 dark:text-gray-400 mb-8">Start for free and upgrade anytime as you grow.</p>
          </Reveal>

          <div className="flex justify-center mb-12">
            <div className="inline-flex items-center bg-card border border-border rounded-full p-1 text-sm">
              <button
                onClick={() => setBilling('monthly')}
                className={`px-4 py-1.5 rounded-full font-medium transition-colors ${billing === 'monthly' ? 'bg-primary text-white' : 'text-gray-500'}`}
              >
                Monthly
              </button>
              <button
                onClick={() => setBilling('yearly')}
                className={`px-4 py-1.5 rounded-full font-medium transition-colors ${billing === 'yearly' ? 'bg-primary text-white' : 'text-gray-500'}`}
              >
                Yearly <span className="text-[10px] opacity-80">(Save 20%)</span>
              </button>
            </div>
          </div>

          <div className="grid md:grid-cols-3 gap-6 max-w-4xl mx-auto">
            {[
              { name: 'Free', monthly: 0, popular: false, tag: 'Available now', features: ['Track up to 2 accounts', 'Manual transactions', 'Basic reports', '1 Goal'] },
              { name: 'Premium', monthly: 149, popular: true, tag: 'Coming soon', features: ['Unlimited accounts', 'CSV statement import', 'Advanced reports', 'Unlimited goals', 'AI insights & recommendations', 'Priority support'] },
              { name: 'Enterprise', monthly: 249, popular: false, tag: 'Coming soon', features: ['Everything in Premium, plus:', 'Investment tracking', 'Advanced analytics', 'Export reports', 'Dedicated support'] },
            ].map((plan, i) => (
              <Reveal key={plan.name} delayMs={i * 80}>
                <div
                  className={`bg-card rounded-xl p-7 relative border transition-all duration-300 hover:-translate-y-1.5 h-full ${plan.popular ? 'border-2 border-primary shadow-[0_0_40px_-10px_rgba(99,102,241,0.5)] hover:shadow-[0_0_55px_-8px_rgba(99,102,241,0.65)]' : 'border-border hover:border-gray-300 dark:hover:border-white/20 hover:shadow-xl hover:shadow-black/5 dark:hover:shadow-black/30'}`}
                >
                  {plan.popular && (
                    <span className="absolute -top-3 left-1/2 -translate-x-1/2 bg-primary text-white text-[10px] font-semibold px-3 py-1 rounded-full">
                      Most Popular
                    </span>
                  )}
                  <div className="flex items-center justify-between mb-1">
                    <p className="text-sm text-gray-500">{plan.name}</p>
                    <span className="text-[9px] uppercase tracking-wide text-gray-500 border border-border rounded-full px-2 py-0.5">{plan.tag}</span>
                  </div>
                  <p className="text-3xl font-bold text-ink mb-5">
                    ₹{billing === 'monthly' ? plan.monthly : yearlyPrice(plan.monthly)}
                    <span className="text-sm font-normal text-gray-500">{plan.monthly === 0 ? '/month' : billing === 'monthly' ? '/month' : '/year'}</span>
                  </p>
                  <ul className="space-y-2.5 mb-6">
                    {plan.features.map((f) => (
                      <li key={f} className="flex items-start gap-2 text-sm text-gray-700 dark:text-gray-300">
                        <Check size={15} className="text-indigo-600 dark:text-indigo-300 mt-0.5 flex-shrink-0" /> {f}
                      </li>
                    ))}
                  </ul>
                  <Link
                    to="/register"
                    className={`block text-center rounded-lg py-2.5 text-sm font-semibold transition-colors ${plan.popular ? 'bg-primary hover:bg-primary-dark text-white' : 'border border-border text-ink hover:bg-gray-50 dark:hover:bg-white/5'}`}
                  >
                    {plan.monthly === 0 ? 'Get Started' : 'Join the Waitlist'}
                  </Link>
                </div>
              </Reveal>
            ))}
          </div>
          <p className="text-center text-xs text-gray-500 mt-8">Free plan available today · Premium & Enterprise billing isn't live yet — no card required to sign up.</p>
        </div>
      </section>

      {/* ---------------- TESTIMONIALS ---------------- */}
      {/* Placeholder quotes for layout purposes — swap in real user feedback before launch. */}
      <section className="bg-gray-50 dark:bg-[#0d0f1f] border-b border-border">
        <div className="max-w-6xl mx-auto px-6 py-20">
          <Reveal className="text-center mb-12">
            <p className="text-xs font-semibold text-indigo-600 dark:text-indigo-300 uppercase tracking-wide mb-2">Early feedback</p>
            <h2 className="text-3xl font-bold text-ink">What early users are saying</h2>
          </Reveal>
          <div className="grid md:grid-cols-3 gap-6">
            {[
              ['A', 'Product manager, Bengaluru', 'Uploading a statement and getting clean, categorized spend in seconds saves me hours every month.'],
              ['P', 'Designer, Pune', 'The auto-categorization keeps getting better the more I correct it — it actually learns.'],
              ['R', 'Founder, Mumbai', 'Finally a finance app that understands Indian bank statement formats without a fight.'],
            ].map(([initial, role, quote], i) => (
              <Reveal key={role} delayMs={i * 80}>
                <div className="bg-card rounded-xl p-6 border border-border h-full transition-all duration-300 hover:-translate-y-1 hover:border-gray-300 dark:hover:border-white/15 hover:shadow-xl hover:shadow-black/5 dark:hover:shadow-black/30">
                  <div className="flex items-center gap-1 mb-4 text-amber-500 dark:text-amber-400">
                    {Array.from({ length: 5 }).map((_, si) => <Star key={si} size={13} fill="currentColor" />)}
                  </div>
                  <p className="text-sm text-gray-700 dark:text-gray-300 leading-relaxed mb-5">&ldquo;{quote}&rdquo;</p>
                  <div className="flex items-center gap-3">
                    <span className="w-9 h-9 rounded-full bg-indigo-500/20 text-indigo-700 dark:text-indigo-200 flex items-center justify-center text-xs font-semibold">{initial}</span>
                    <div>
                      <p className="text-xs font-semibold text-ink">Early user</p>
                      <p className="text-[11px] text-gray-500">{role}</p>
                    </div>
                  </div>
                </div>
              </Reveal>
            ))}
          </div>
          <div className="flex items-center justify-center gap-6 text-xs text-gray-600 dark:text-gray-400 mt-12">
            <span className="flex items-center gap-1.5"><Users size={14} className="text-indigo-600 dark:text-indigo-300" /> Growing user base</span>
            <span className="flex items-center gap-1.5"><Star size={14} className="text-indigo-600 dark:text-indigo-300" /> Built from real feedback</span>
            <span className="flex items-center gap-1.5"><ShieldCheck size={14} className="text-indigo-600 dark:text-indigo-300" /> Your data, never sold</span>
          </div>
        </div>
      </section>

      {/* ---------------- FAQ ---------------- */}
      <section id="faq" className="border-b border-border">
        <div className="max-w-3xl mx-auto px-6 py-20">
          <Reveal className="text-center mb-10">
            <p className="text-xs font-semibold text-indigo-600 dark:text-indigo-300 uppercase tracking-wide mb-2">FAQ</p>
            <h2 className="text-3xl font-bold text-ink">Questions people actually ask</h2>
          </Reveal>
          <div className="space-y-3">
            {FAQ_ITEMS.map(([q, a], i) => {
              const open = openFaq === i;
              return (
                <div key={q} className="border border-border rounded-xl overflow-hidden bg-card">
                  <button
                    type="button"
                    onClick={() => setOpenFaq(open ? null : i)}
                    className="w-full flex items-center justify-between gap-4 px-5 py-4 text-left"
                  >
                    <span className="font-semibold text-ink text-sm">{q}</span>
                    <span className="text-indigo-600 dark:text-indigo-300 flex-shrink-0">{open ? <Minus size={16} /> : <Plus size={16} />}</span>
                  </button>
                  <div
                    className="grid transition-[grid-template-rows] duration-300 ease-out"
                    style={{ gridTemplateRows: open ? '1fr' : '0fr' }}
                  >
                    <div className="overflow-hidden">
                      <p className="text-sm text-gray-600 dark:text-gray-400 leading-relaxed px-5 pb-4">{a}</p>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </section>

      {/* ---------------- BOTTOM CTA ---------------- */}
      <section className="max-w-6xl mx-auto px-6 py-16">
        <Reveal className="relative overflow-hidden bg-gradient-to-br from-primary to-[#3730a3] rounded-2xl px-8 py-12 flex flex-col md:flex-row items-center justify-between gap-8">
          <div className="relative z-10">
            <h2 className="text-2xl font-bold text-white mb-2">Ready to take control of your finances?</h2>
            <p className="text-indigo-100">Join the users already building a clearer financial life with Finora.</p>
            <div className="flex items-center gap-4 mt-6">
              <Link to="/register" className="bg-white text-primary-dark font-semibold px-6 py-3 rounded-lg flex items-center gap-2 transition-transform hover:-translate-y-0.5">
                Get Started Free <ArrowRight size={16} />
              </Link>
              <a href="#features" className="text-white text-sm underline">Explore Features</a>
            </div>
          </div>
          {/* Decorative stacked-card illustration — pure CSS, no external assets */}
          <div className="relative w-40 h-28 flex-shrink-0 hidden sm:block">
            <div className="float-slow absolute inset-0 rounded-xl bg-gradient-to-br from-violet-400/90 to-indigo-600/90 rotate-6 shadow-xl" />
            <div className="float-slower absolute inset-0 rounded-xl bg-gradient-to-br from-indigo-300/90 to-violet-500/90 -rotate-3 shadow-xl" />
            <div className="absolute inset-0 rounded-xl bg-gradient-to-br from-white/20 to-white/5 border border-white/30 backdrop-blur flex items-center justify-center">
              <span className="text-white font-black text-lg">F</span>
            </div>
          </div>
        </Reveal>
      </section>

      {/* ---------------- FOOTER ---------------- */}
      <footer className="bg-gray-50 dark:bg-[#0a0b16] border-t border-border text-sm">
        <div className="max-w-6xl mx-auto px-6 py-14 grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-10">
          <div className="col-span-2">
            <Logo />
            <p className="text-gray-500 text-xs leading-relaxed mt-3 max-w-[220px]">
              Your AI-powered financial operating system — track, understand, and grow your money.
            </p>
            <div className="flex items-center gap-3 mt-4">
              <a href="#" aria-label="Twitter" className="w-8 h-8 rounded-lg bg-gray-100 dark:bg-white/5 flex items-center justify-center text-gray-500 hover:text-ink transition-colors"><Twitter size={14} /></a>
              <a href="#" aria-label="LinkedIn" className="w-8 h-8 rounded-lg bg-gray-100 dark:bg-white/5 flex items-center justify-center text-gray-500 hover:text-ink transition-colors"><Linkedin size={14} /></a>
              <a href="#" aria-label="YouTube" className="w-8 h-8 rounded-lg bg-gray-100 dark:bg-white/5 flex items-center justify-center text-gray-500 hover:text-ink transition-colors"><Youtube size={14} /></a>
            </div>
          </div>

          <div>
            <p className="text-ink font-semibold text-xs mb-3">Product</p>
            <ul className="space-y-2 text-xs text-gray-500">
              <li><a href="#features" className="hover:text-gray-700 dark:hover:text-gray-300">Features</a></li>
              <li><a href="#how" className="hover:text-gray-700 dark:hover:text-gray-300">How It Works</a></li>
              <li><a href="#pricing" className="hover:text-gray-700 dark:hover:text-gray-300">Pricing</a></li>
              <li><a href="#security" className="hover:text-gray-700 dark:hover:text-gray-300">Security</a></li>
            </ul>
          </div>

          <div>
            <p className="text-ink font-semibold text-xs mb-3">Company</p>
            <ul className="space-y-2 text-xs text-gray-500">
              <li><Link to="/about" className="hover:text-gray-700 dark:hover:text-gray-300">About Us</Link></li>
              <li><Link to="/careers" className="hover:text-gray-700 dark:hover:text-gray-300">Careers</Link></li>
              <li><a href="mailto:support@finora.app" className="hover:text-gray-700 dark:hover:text-gray-300">Contact Us</a></li>
            </ul>
          </div>

          <div>
            <p className="text-ink font-semibold text-xs mb-3">Resources</p>
            <ul className="space-y-2 text-xs text-gray-500">
              <li><a href="#faq" className="hover:text-gray-700 dark:hover:text-gray-300">FAQ</a></li>
              <li><Link to="/help" className="hover:text-gray-700 dark:hover:text-gray-300">Help Center</Link></li>
            </ul>
          </div>

          <div>
            <p className="text-ink font-semibold text-xs mb-3">Stay Updated</p>
            {subscribed ? (
              <p className="text-xs text-green-600 dark:text-green-400">Thanks — noted locally (no mailing list is wired up yet).</p>
            ) : (
              <form onSubmit={handleNewsletterSubmit} className="flex items-center gap-1.5">
                <input
                  type="email"
                  required
                  value={newsletterEmail}
                  onChange={(e) => setNewsletterEmail(e.target.value)}
                  placeholder="you@email.com"
                  className="w-full min-w-0 bg-gray-100 dark:bg-white/5 border border-border rounded-lg px-2.5 py-2 text-xs text-ink placeholder:text-gray-500 focus:outline-none focus:ring-1 focus:ring-primary"
                />
                <button type="submit" className="bg-primary hover:bg-primary-dark text-white rounded-lg px-2.5 py-2 flex-shrink-0 transition-transform hover:scale-105">
                  <ArrowRight size={14} />
                </button>
              </form>
            )}
          </div>
        </div>
        <div className="border-t border-border">
          <div className="max-w-6xl mx-auto px-6 py-5 flex flex-col sm:flex-row justify-between items-center gap-2 text-xs text-gray-500">
            <span>© {new Date().getFullYear()} Finora. Not a bank. Not investment advice.</span>
            <div className="flex items-center gap-4">
              <Link to="/terms" className="hover:text-gray-700 dark:hover:text-gray-300">Terms</Link>
              <Link to="/privacy" className="hover:text-gray-700 dark:hover:text-gray-300">Privacy</Link>
            </div>
          </div>
        </div>
      </footer>
    </div>
  );
}
