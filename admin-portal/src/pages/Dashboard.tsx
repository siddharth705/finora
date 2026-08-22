import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import {
  Users, UserCheck, ArrowLeftRight, FileStack, AlertTriangle, ShieldAlert,
  Wallet, TrendingUp, RefreshCw, UserPlus, Landmark, KeyRound,
  ScrollText, SlidersHorizontal, Lock, Tag, Copy, CheckCircle2,
} from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { StatCard } from '../components/StatCard';
import { RecentImportsPanel } from '../components/RecentImportsPanel';
import { useAdminAuth } from '../context/AdminAuthContext';
import { adminDashboardApi, adminStatsApi, adminSystemApi } from '../api/endpoints';
import type { AlertDto, ProviderStatusDto, NeedsAttentionDto, ActivationFunnelDto } from '../types';

const STATUS_DOT: Record<string, string> = {
  UP: 'bg-success',
  DEGRADED: 'bg-warning',
  DOWN: 'bg-danger',
};

const STATUS_TEXT: Record<string, string> = {
  UP: 'text-success',
  DEGRADED: 'text-warning',
  DOWN: 'text-danger',
};

/**
 * Single repeating waveform unit, duplicated once and scrolled by exactly one unit-width
 * (see .animate-heartbeat in index.css) for a seamless loop. currentColor so it inherits
 * whichever status color wraps it -- calm success-green when healthy, danger-red when not.
 */
function PulseLine({ tone }: { tone: 'success' | 'warning' | 'danger' }) {
  const colorClass = tone === 'success' ? 'text-success' : tone === 'warning' ? 'text-warning' : 'text-danger';
  return (
    <div className={`overflow-hidden h-8 w-32 flex-shrink-0 ${colorClass}`}>
      <svg viewBox="0 0 400 40" className="h-full w-[200%] animate-heartbeat" preserveAspectRatio="none">
        <path
          d="M0,20 L80,20 L92,6 L104,34 L116,14 L128,20 L200,20 L280,20 L292,6 L304,34 L316,14 L328,20 L400,20"
          fill="none"
          stroke="currentColor"
          strokeWidth="2.5"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
    </div>
  );
}

function formatRelativeTime(ms: number) {
  const seconds = Math.round((Date.now() - ms) / 1000);
  if (seconds < 5) return 'just now';
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  return `${Math.round(minutes / 60)}h ago`;
}

function formatUptime(seconds: number) {
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  if (days > 0) return `${days}d ${hours}h`;
  const minutes = Math.floor((seconds % 3600) / 60);
  if (hours > 0) return `${hours}h ${minutes}m`;
  return `${minutes}m`;
}

/**
 * Every tile here maps to one field on NeedsAttentionDto -- see that record's backend doc
 * comment. Deliberately no "Pending Reconciliation" or generic "Security Alerts" tile: neither
 * concept exists in this codebase today (reconciliation runs fully automatically with no
 * approval queue; there's no security-event tracking beyond the health registry's own alerts,
 * already shown above this section). A tile only renders when its count is > 0; if every count
 * is zero, the section shows a calm "nothing needs attention" line instead of empty tiles.
 */
function NeedsAttentionSection({ data }: { data: NeedsAttentionDto }) {
  const items = [
    {
      count: data.importsWithSkippedRowsToday,
      icon: AlertTriangle,
      label: 'imports had skipped rows today',
      to: '/diagnostics',
      linkLabel: 'View in Diagnostics',
    },
    {
      count: data.lockedAccounts,
      icon: Lock,
      label: 'accounts are currently locked out',
      to: '/users',
      linkLabel: 'Go to Users',
    },
    {
      count: data.transactionsNeedingCategoryReview,
      icon: Tag,
      label: 'transactions still need category review',
      to: null,
      linkLabel: null,
    },
    {
      count: data.transactionsFlaggedAsDuplicates,
      icon: Copy,
      label: 'transactions are flagged as potential duplicates',
      to: null,
      linkLabel: null,
    },
  ].filter((item) => item.count > 0);

  if (items.length === 0) {
    return (
      <div className="flex items-center gap-2 text-sm text-success bg-success-bg rounded-lg px-3.5 py-2.5">
        <CheckCircle2 size={15} />
        <span>Nothing needs attention right now.</span>
      </div>
    );
  }

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
      {items.map(({ count, icon: Icon, label, to, linkLabel }) => (
        <div key={label} className="flex items-start gap-3 bg-warning-bg rounded-lg px-3.5 py-3">
          <Icon size={16} className="text-warning flex-shrink-0 mt-0.5" />
          <div className="min-w-0">
            <p className="text-sm text-warning">
              <span className="font-mono font-bold">{count}</span> {label}
            </p>
            {to && (
              <Link to={to} className="text-xs text-warning font-medium underline underline-offset-2">
                {linkLabel} →
              </Link>
            )}
          </div>
        </div>
      ))}
    </div>
  );
}

/**
 * D-27 PR3-D. Signup -> first import -> first budget -> first goal, the owner's own named
 * sequence -- a simple snapshot (each bar is "how many users have EVER reached this stage",
 * against the platform right now), not a cohort/time-series. See backend ActivationFunnelDto's
 * own doc comment for why stages aren't guaranteed to be strict subsets of each other -- Finora
 * doesn't require an import before a budget, so a later bar can in principle exceed an earlier
 * one, and that's shown as-is rather than corrected into a falsely monotonic funnel.
 */
function ActivationFunnelSection({ data }: { data: ActivationFunnelDto }) {
  const stages = [
    { label: 'Signed up', icon: Users, count: data.signedUp },
    { label: 'First import', icon: FileStack, count: data.firstImport },
    { label: 'First budget', icon: Wallet, count: data.firstBudget },
    { label: 'First goal', icon: TrendingUp, count: data.firstGoal },
  ];
  // Guards the very first admin ever seeing this on a platform with zero signups -- 0/0 must
  // read as an empty bar, not a NaN%.
  const base = data.signedUp || 1;

  return (
    <div className="bg-card border border-border rounded-xl2 shadow-card p-5 space-y-4">
      {stages.map(({ label, icon: Icon, count }) => {
        const pct = Math.round((count / base) * 100);
        return (
          <div key={label}>
            <div className="flex items-center justify-between mb-1.5">
              <span className="flex items-center gap-2 text-sm text-ink">
                <Icon size={14} className="text-muted flex-shrink-0" /> {label}
              </span>
              <span className="text-sm font-mono font-semibold text-ink">
                {count.toLocaleString()} <span className="text-muted font-normal">({pct}%)</span>
              </span>
            </div>
            <div className="h-2 bg-bg rounded-full overflow-hidden">
              <div className="h-full bg-primary rounded-full" style={{ width: `${Math.min(100, pct)}%` }} />
            </div>
          </div>
        );
      })}
    </div>
  );
}

function AlertRow({ alert }: { alert: AlertDto }) {
  const critical = alert.severity === 'critical';
  return (
    <div className={`flex items-start gap-2.5 rounded-lg px-3.5 py-2.5 ${critical ? 'bg-danger-bg' : 'bg-warning-bg'}`}>
      <ShieldAlert size={15} className={`flex-shrink-0 mt-0.5 ${critical ? 'text-danger' : 'text-warning'}`} />
      <div className="min-w-0">
        <p className={`text-sm font-semibold ${critical ? 'text-danger' : 'text-warning'}`}>{alert.title}</p>
        <p className={`text-xs mt-0.5 ${critical ? 'text-danger' : 'text-warning'}`}>{alert.detail}</p>
      </div>
    </div>
  );
}

function ProviderRow({ provider }: { provider: ProviderStatusDto }) {
  return (
    <div className="flex items-center gap-3 px-4 py-3">
      <span className={`w-2 h-2 rounded-full flex-shrink-0 ${STATUS_DOT[provider.status] ?? STATUS_DOT.UP}`} />
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium text-ink">{provider.name}</p>
        <p className="text-xs text-muted truncate">{provider.detail}</p>
      </div>
      <span className={`text-[11px] font-mono font-semibold uppercase tracking-wide ${STATUS_TEXT[provider.status] ?? STATUS_TEXT.UP}`}>
        {provider.status}
      </span>
    </div>
  );
}

const QUICK_ACTIONS = [
  { to: '/users', label: 'Create user', icon: UserPlus },
  { to: '/roles', label: 'Roles & permissions', icon: KeyRound },
  { to: '/banks', label: 'Banks', icon: Landmark },
  { to: '/audit', label: 'Audit log', icon: ScrollText },
  { to: '/settings', label: 'Settings', icon: SlidersHorizontal },
];

/**
 * The Operational Dashboard -- "Is Finora healthy?" as one screen, meant to be this app's actual
 * home rather than a launchpad into other pages. Alerts and system status come entirely from
 * AdminHealthRegistryService's extensible provider registry (see com.finora.health
 * .HealthProvider's class comment) -- adding a new module's observability later means the
 * backend gets one new @Component, and this page picks it up automatically with zero changes
 * here, since it just renders whatever the /admin/dashboard/overview response contains.
 *
 * Every figure on this screen is a real, live query result -- no fabricated charts or gauges.
 * Some panels a generic "ops dashboard" template might include (a 7-day transaction trend line,
 * infrastructure resource gauges) aren't here because there's no honest backing data for them
 * yet (see OperationalDashboardDto's own doc comment on importsWithSkippedRowsToday as the
 * deliberate substitute for a fabricated "failed imports" figure) -- the same discipline applies
 * to what this page chooses to visualize, not just what the backend chooses to compute.
 */
function DashboardContent() {
  const { fullName, hasPermission } = useAdminAuth();
  const { data, isLoading, dataUpdatedAt, refetch, isFetching } = useQuery({
    queryKey: ['admin-dashboard-overview'],
    queryFn: () => adminDashboardApi.overview(),
  });
  // Lifetime totals (accounts/statement imports/suspended users) aren't part of the "today"
  // operational view -- kept as a secondary panel below, still backed by the original
  // PLATFORM_STATS_VIEW-gated endpoint rather than duplicated into the new one.
  const { data: lifetimeStats } = useQuery({
    queryKey: ['admin-stats-overview'],
    queryFn: () => adminStatsApi.overview(),
  });
  // D-27 PR3-D. Same PLATFORM_STATS_VIEW gate as the rest of this page -- see the controller's
  // own class comment on why this reuses that permission rather than minting a new one.
  const { data: activationFunnel } = useQuery({
    queryKey: ['admin-activation-funnel'],
    queryFn: () => adminDashboardApi.activationFunnel(),
  });
  // Real uptime, not a fabricated percentage -- gated on PLATFORM_DIAGNOSTICS_VIEW (V34), the
  // same permission AdminSystemController's /admin/system/health endpoint now actually requires
  // -- this must track whatever the backend checks, or an admin with one but not the other would
  // either see a silent gap (permission present, check says no) or a 403 this component doesn't
  // handle (check says yes, permission actually absent). `enabled` skips the call entirely rather
  // than rendering a permission error on the home page.
  const canSeeUptime = hasPermission('PLATFORM_DIAGNOSTICS_VIEW');
  const { data: systemHealth } = useQuery({
    queryKey: ['admin-system-health'],
    queryFn: () => adminSystemApi.health(),
    enabled: canSeeUptime,
  });

  const overallStatus = data?.health.overallStatus;
  const tone: 'success' | 'warning' | 'danger' =
    overallStatus === 'DOWN' ? 'danger' : overallStatus === 'DEGRADED' ? 'warning' : 'success';
  const bannerBg = tone === 'success' ? 'bg-success-bg' : tone === 'warning' ? 'bg-warning-bg' : 'bg-danger-bg';
  const bannerText = tone === 'success' ? 'text-success' : tone === 'warning' ? 'text-warning' : 'text-danger';

  return (
    <div className="space-y-8">
      <div>
        <h2 className="text-sm font-semibold text-muted uppercase tracking-wide mb-3">
          {`Good to see you, ${fullName ?? 'Admin'}`}
        </h2>

        {/* Health hero: the pulse line is the one signature element on this page, and its color
            is the same overallStatus every other status indicator here derives from -- one
            source of truth, not a separately-judged decoration. */}
        <div className={`rounded-xl2 ${bannerBg} px-5 py-4 mb-6`}>
          <div className="flex items-center justify-between gap-4 flex-wrap">
            <div className="flex items-center gap-4 min-w-0">
              <PulseLine tone={tone} />
              <div className="min-w-0">
                <p className={`text-sm font-bold ${bannerText}`}>
                  {isLoading ? 'Checking systems…'
                    : overallStatus === 'UP' ? 'All systems operational'
                    : overallStatus === 'DEGRADED' ? 'Some systems are degraded'
                    : 'A monitored system is down'}
                </p>
                {canSeeUptime && systemHealth && (
                  <p className={`text-xs mt-0.5 font-mono ${bannerText}`}>
                    Uptime {formatUptime(systemHealth.uptimeSeconds)}
                  </p>
                )}
              </div>
            </div>
            <div className="flex items-center gap-3 flex-shrink-0">
              <span className="text-xs text-muted font-mono">
                {dataUpdatedAt ? `Updated ${formatRelativeTime(dataUpdatedAt)}` : ''}
              </span>
              <button
                onClick={() => refetch()}
                disabled={isFetching}
                className="w-7 h-7 rounded-lg flex items-center justify-center text-muted hover:text-ink hover:bg-black/5 disabled:opacity-50 transition-colors"
                title="Refresh"
              >
                <RefreshCw size={14} className={isFetching ? 'animate-spin' : ''} />
              </button>
            </div>
          </div>

          {!isLoading && data && data.alerts.length > 0 && (
            <div className="space-y-2 mt-4">
              {data.alerts.map((alert) => <AlertRow key={alert.title} alert={alert} />)}
            </div>
          )}
        </div>

        {!isLoading && data && (
          <div className="mb-8">
            <h2 className="text-sm font-semibold text-muted uppercase tracking-wide mb-3">Needs attention</h2>
            <NeedsAttentionSection data={data.needsAttention} />
          </div>
        )}

        <div className="grid grid-cols-2 md:grid-cols-5 gap-4 mb-8">
          <StatCard icon={Users} label="Total users" value={isLoading ? '…' : data?.totalUsers ?? 0} />
          <StatCard icon={UserCheck} label="Active today" value={isLoading ? '…' : data?.activeUsersToday ?? 0} />
          <StatCard icon={ArrowLeftRight} label="Transactions today" value={isLoading ? '…' : data?.transactionsToday ?? 0} />
          <StatCard icon={FileStack} label="Imports today" value={isLoading ? '…' : data?.importsToday ?? 0} />
          <StatCard
            icon={AlertTriangle}
            label="Imports w/ skipped rows"
            value={isLoading ? '…' : data?.importsWithSkippedRowsToday ?? 0}
            tone={(data?.importsWithSkippedRowsToday ?? 0) > 0 ? 'warning' : 'default'}
          />
        </div>

      </div>

      {/* Activation funnel + System status side by side -- both are "how is the platform doing
          overall" snapshots, same visual weight, same row. */}
      <div className="grid grid-cols-1 lg:grid-cols-5 gap-6">
        <div className="lg:col-span-3">
          {activationFunnel && (
            <>
              <h2 className="text-sm font-semibold text-muted uppercase tracking-wide mb-3">Activation funnel</h2>
              <ActivationFunnelSection data={activationFunnel} />
            </>
          )}
        </div>

        <div className="lg:col-span-2">
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-sm font-semibold text-muted uppercase tracking-wide">System status</h2>
            <Link to="/health" className="text-xs text-primary font-medium">View infrastructure details →</Link>
          </div>
          <div className="bg-card border border-border rounded-xl2 shadow-card divide-y divide-border">
            {isLoading && <p className="text-sm text-muted px-4 py-4">Loading…</p>}
            {data?.health.providers.map((p) => <ProviderRow key={p.name} provider={p} />)}
          </div>
        </div>
      </div>

      {/* Recent imports + lifetime totals/quick actions -- day-to-day activity next to the two
          things an admin most often wants to jump into from here. */}
      <div className="grid grid-cols-1 lg:grid-cols-5 gap-6">
        <div className="lg:col-span-3">
          <RecentImportsPanel limit={5} viewAllTo="/health" />
        </div>

        <div className="lg:col-span-2 space-y-6">
          {lifetimeStats && (
            <div>
              <h2 className="text-sm font-semibold text-muted uppercase tracking-wide mb-3">Lifetime totals</h2>
              <div className="bg-card border border-border rounded-xl2 shadow-card divide-y divide-border">
                {[
                  { icon: Wallet, label: 'Accounts', value: lifetimeStats.totalAccounts },
                  { icon: TrendingUp, label: 'New users (7d)', value: lifetimeStats.newUsersLast7Days },
                  { icon: FileStack, label: 'Statement imports', value: lifetimeStats.totalStatementImports },
                  { icon: Users, label: 'Suspended users', value: lifetimeStats.suspendedUsers },
                ].map(({ icon: Icon, label, value }) => (
                  <div key={label} className="flex items-center gap-3 px-4 py-3">
                    <Icon size={15} className="text-muted flex-shrink-0" />
                    <span className="text-sm text-ink flex-1">{label}</span>
                    <span className="text-sm font-mono font-semibold text-ink">{value}</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          <div>
            <h2 className="text-sm font-semibold text-muted uppercase tracking-wide mb-3">Quick actions</h2>
            <div className="bg-card border border-border rounded-xl2 shadow-card divide-y divide-border">
              {QUICK_ACTIONS.map(({ to, label, icon: Icon }) => (
                <Link key={to} to={to} className="flex items-center gap-3 px-4 py-3 hover:bg-primary-light transition-colors">
                  <Icon size={15} className="text-primary flex-shrink-0" />
                  <span className="text-sm text-ink flex-1">{label}</span>
                </Link>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

export default function Dashboard() {
  const { hasPermission } = useAdminAuth();
  const canSeeDashboard = hasPermission('PLATFORM_STATS_VIEW');

  return (
    <AdminLayout title="Operational Dashboard" subtitle="Is Finora healthy? Real-time platform status">
      {canSeeDashboard ? (
        <DashboardContent />
      ) : (
        <div className="bg-card border border-border rounded-xl2 p-8 text-center">
          <p className="text-ink font-semibold mb-1">You don't have access to this section</p>
          <p className="text-muted text-sm">This requires the PLATFORM_STATS_VIEW permission. Use the sidebar to reach the sections your permissions do unlock.</p>
        </div>
      )}
    </AdminLayout>
  );
}
