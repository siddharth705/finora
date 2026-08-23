import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { lazy, Suspense, type ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from './context/AuthContext';
import { ThemeProvider } from './context/ThemeContext';
import { ErrorBoundary } from './components/ErrorBoundary';
import { PageLoading } from './components/PageLoading';
import { ProtectedRoute } from './components/ProtectedRoute';
import { Sidebar } from './components/Sidebar';
import { TopBar } from './components/TopBar';
// Landing stays eagerly imported: it is the first paint for an unauthenticated visitor, so making
// it lazy would ADD a round trip to the most common entry point rather than removing one.
import Landing from './pages/Landing';

// Everything else is route-split. Measured from the pre-split bundle's sourcemap, the two heaviest
// dependencies were chart.js (20.9% of source bytes) and firebase (~25%, counting @firebase/*) --
// and a visitor reading the landing page needs neither. chart.js is imported only by Dashboard and
// Investments, both authenticated; firebase is pulled in by lib/firebase.ts, which is a STATIC
// import, so the existing lazy getFirebaseAuth() deferred initialisation but never the download.
const Terms = lazy(() => import('./pages/Terms'));
const Privacy = lazy(() => import('./pages/Privacy'));
const RefundPolicy = lazy(() => import('./pages/RefundPolicy'));
const ShippingPolicy = lazy(() => import('./pages/ShippingPolicy'));
const Contact = lazy(() => import('./pages/Contact'));
const About = lazy(() => import('./pages/About'));
const Careers = lazy(() => import('./pages/Careers'));
const Help = lazy(() => import('./pages/Help'));
const Login = lazy(() => import('./pages/Login'));
const Register = lazy(() => import('./pages/Register'));
const ForgotPassword = lazy(() => import('./pages/ForgotPassword'));
const ResetPassword = lazy(() => import('./pages/ResetPassword'));
const VerifyEmail = lazy(() => import('./pages/VerifyEmail'));
const VerifyPhone = lazy(() => import('./pages/VerifyPhone'));
const Dashboard = lazy(() => import('./pages/Dashboard'));
const Ledger = lazy(() => import('./pages/Ledger'));
const Import = lazy(() => import('./pages/Import'));
const ImportDetail = lazy(() => import('./pages/ImportDetail'));
const StatementHistory = lazy(() => import('./pages/StatementHistory'));
const Budgets = lazy(() => import('./pages/Budgets'));
const Goals = lazy(() => import('./pages/Goals'));
const Investments = lazy(() => import('./pages/Investments'));
const Reports = lazy(() => import('./pages/Reports'));
const Insights = lazy(() => import('./pages/Insights'));
const Profile = lazy(() => import('./pages/Profile'));
const VerifyEmailChange = lazy(() => import('./pages/VerifyEmailChange'));
const Settings = lazy(() => import('./pages/Settings'));
const BillingHistory = lazy(() => import('./pages/BillingHistory'));
const Referrals = lazy(() => import('./pages/Referrals'));
const GmailReview = lazy(() => import('./pages/GmailReview'));
const Setup = lazy(() => import('./pages/Setup'));

function AppShell({ children }: { children: ReactNode }) {
  return (
    <div className="min-h-screen bg-bg flex">
      <Sidebar />
      <main className="flex-1 p-8 max-w-[1600px]">
        <TopBar />
        {/* Inside the shell, not around it: a page that throws is contained to the content area
            while the sidebar and top bar keep rendering, so the user can navigate away instead of
            being stranded. This is the boundary that does the real work -- see the outer one in
            App() for why there are two. */}
        <ErrorBoundary context="app-route">
          {/* Inside the shell alongside the error boundary, and for the same reason: while a route
              chunk downloads, the sidebar and top bar keep rendering, so a slow connection shows a
              loading app rather than a blank page. A blank page is the exact failure c33a859
              fixed, and lazy routes are the easiest way to reintroduce it. */}
          <Suspense fallback={<PageLoading />}>{children}</Suspense>
        </ErrorBoundary>
      </main>
    </div>
  );
}

function Protected({ children }: { children: ReactNode }) {
  return (
    <ProtectedRoute>
      <AppShell>{children}</AppShell>
    </ProtectedRoute>
  );
}

// A single shared client — sensible defaults for a dashboard app: don't refetch on every
// window focus (financial data doesn't change that fast), do retry once on transient failures.
const queryClient = new QueryClient({
  defaultOptions: {
    queries: { refetchOnWindowFocus: false, retry: 1, staleTime: 30_000 },
  },
});

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
    <ThemeProvider>
    <AuthProvider>
      {/* No `future` prop since the v7 upgrade: v7_startTransition and v7_relativeSplatPath were
          opt-in flags for exactly this migration and are now the only behaviour, so v7 removed the
          prop entirely. Opting into them early is what made this upgrade small.

          The other v7 change visible across this codebase: useNavigate()'s returned function now
          returns a Promise (it awaits loaders on a data router). Every navigate() call here is
          fire-and-forget, so they read `void navigate(...)` -- that is not decoration, it is
          @typescript-eslint/no-floating-promises being satisfied deliberately rather than
          suppressed. The rule flagged all 14 call sites across both web apps the moment the
          upgrade landed, which is the entire reason lint was wired into CI first. */}
      <BrowserRouter>
        {/* The outer of two boundaries, and the lesser one. Authenticated pages are caught by
            AppShell's inner boundary first (React unwinds to the nearest one), which is what keeps
            the navigation chrome alive. This one exists for the marketing and auth pages, which
            render standalone with no chrome to preserve -- there it is the difference between a
            recovery panel and the blank white page those routes would otherwise show. */}
        <ErrorBoundary context="root">
        {/* Covers the marketing and auth routes, which render standalone with no shell. */}
        <Suspense fallback={<PageLoading />}>
        <Routes>
          {/* Marketing site */}
          <Route path="/" element={<Landing />} />
          <Route path="/terms" element={<Terms />} />
          <Route path="/privacy" element={<Privacy />} />
          <Route path="/refund-policy" element={<RefundPolicy />} />
          <Route path="/shipping-policy" element={<ShippingPolicy />} />
          <Route path="/contact" element={<Contact />} />
          <Route path="/about" element={<About />} />
          <Route path="/careers" element={<Careers />} />
          <Route path="/help" element={<Help />} />

          {/* Auth */}
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />
          <Route path="/forgot-password" element={<ForgotPassword />} />
          <Route path="/reset-password" element={<ResetPassword />} />
          <Route path="/verify-email" element={<VerifyEmail />} />
          <Route path="/verify-phone" element={<ProtectedRoute allowUnverified><VerifyPhone /></ProtectedRoute>} />
          <Route path="/email-change-verify" element={<ProtectedRoute><VerifyEmailChange /></ProtectedRoute>} />

          {/* App (authenticated) */}
          <Route path="/app" element={<Protected><Dashboard /></Protected>} />
          <Route path="/app/accounts" element={<Protected><Setup /></Protected>} />
          <Route path="/app/transactions" element={<Protected><Ledger /></Protected>} />
          <Route path="/app/import" element={<Protected><Import /></Protected>} />
          {/* Premium Import Reliability v1, §3.2 -- the first :id-param route in this app.
              Deliberately flat (/app/imports/:jobId), matching every other route above rather
              than nesting under /app/import, since this is "look up one past import", a
              different concern from "start a new one". */}
          <Route path="/app/imports/:jobId" element={<Protected><ImportDetail /></Protected>} />
          <Route path="/app/statements" element={<Protected><StatementHistory /></Protected>} />
          <Route path="/app/budgets" element={<Protected><Budgets /></Protected>} />
          <Route path="/app/goals" element={<Protected><Goals /></Protected>} />
          <Route path="/app/investments" element={<Protected><Investments /></Protected>} />
          <Route path="/app/reports" element={<Protected><Reports /></Protected>} />
          <Route path="/app/insights" element={<Protected><Insights /></Protected>} />
          <Route path="/app/profile" element={<Protected><Profile /></Protected>} />
          <Route path="/app/settings" element={<Protected><Settings /></Protected>} />
          <Route path="/app/billing" element={<Protected><BillingHistory /></Protected>} />
          <Route path="/app/referrals" element={<Protected><Referrals /></Protected>} />
          <Route path="/app/settings/gmail/review" element={<Protected><GmailReview /></Protected>} />

          {/* Bug fix: there was no catch-all, and wrangler.json sets
              assets.not_found_handling = "single-page-application" -- so Cloudflare answers EVERY
              unmatched path with index.html, React Router then matches no <Route>, and <Routes>
              renders null. The result was a completely blank white page (verified: #root's
              innerHTML was empty) with no message and no way back, for any typo'd URL, any stale
              bookmark, and any link to a route that has since moved. Redirecting rather than
              rendering a 404 page keeps this a fix to broken routing rather than a new screen;
              `replace` keeps the bad URL out of history, so Back doesn't bounce straight into it
              again. */}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
        </Suspense>
        </ErrorBoundary>
      </BrowserRouter>
    </AuthProvider>
    </ThemeProvider>
    </QueryClientProvider>
  );
}
