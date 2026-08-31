import { useQuery } from '@tanstack/react-query';
import { ShieldCheck, ShieldAlert, GitMerge } from 'lucide-react';
import { adminUserWorkspaceApi } from '../../api/endpoints';
import type { WorkspaceSummaryDto } from '../../types';

const WORKSPACE_HEALTH_SIGNALS: { key: keyof WorkspaceSummaryDto['health']; label: string }[] = [
  { key: 'rulesEnabled', label: 'Rules Enabled' },
  { key: 'merchantLearningActive', label: 'Merchant Learning Active' },
  { key: 'reconciliationHealthy', label: 'Reconciliation Healthy' },
  { key: 'recurringDetectionHealthy', label: 'Recurring Detection Healthy' },
  { key: 'auditLoggingHealthy', label: 'Audit Logging Healthy' },
];

const WORKSPACE_CONFIDENCE_TIER_COLOR: Record<string, string> = {
  HIGH: '#16a34a', MEDIUM: '#f59e0b', LOW: '#ef4444', UNCONFIRMED: '#94a3b8',
};

function fmtWorkspaceActivityAction(action: string) {
  return action.toLowerCase().replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase());
}

/** Consolidated Intelligence Workspace card for a specific user -- the exact same panel the
 *  self-service User Portal Dashboard used to show (health badges + Snapshot / Merchant
 *  Confidence / Recent Activity), before it moved off that app's main nav. AdminUserWorkspaceController
 *  proxies the exact same WorkspaceDashboardService.summarize() the self-service version called, and
 *  already returns every field this card needs in one response -- nothing here is a second fetch of
 *  data another section on this page already has. Deliberately its own card rather than folded into
 *  MerchantsSection/RulesSection/LearningSection above: those are action surfaces (create a rule,
 *  rename a merchant, review a learning event); this is purely "is Finora's own engine working for
 *  this account", the same distinction the original User Portal draws between its KPI cards and this
 *  section (see the removed frontend/src/pages/Dashboard.tsx's WorkspaceSection doc comment). */
export function WorkspaceSection({ userId }: { userId: string }) {
  const { data: workspace, isLoading } = useQuery<WorkspaceSummaryDto>({
    queryKey: ['admin-user-workspace', userId],
    queryFn: () => adminUserWorkspaceApi.get(userId),
  });

  const confidenceTotal = workspace
    ? Object.values(workspace.confidenceDistribution).reduce((s, v) => s + v, 0)
    : 0;

  return (
    <div className="bg-card border border-border rounded-xl2 shadow-card p-6">
      <div className="flex items-center gap-2 mb-3">
        <GitMerge size={15} className="text-primary" />
        <h3 className="text-sm font-semibold text-ink">Intelligence Workspace</h3>
      </div>

      {isLoading && <p className="text-sm text-muted">Loading…</p>}

      {!isLoading && workspace && (
        <>
          <div className="flex flex-wrap gap-2 mb-4">
            {WORKSPACE_HEALTH_SIGNALS.map(({ key, label }) => {
              const healthy = workspace.health[key];
              const Icon = healthy ? ShieldCheck : ShieldAlert;
              return (
                <span
                  key={key}
                  className={`inline-flex items-center gap-1.5 text-xs font-semibold rounded-full px-2.5 py-1 ${
                    healthy ? 'bg-success-bg text-success' : 'bg-danger-bg text-danger'
                  }`}
                >
                  <Icon size={13} /> {label}
                </span>
              );
            })}
          </div>

          <div className="grid md:grid-cols-3 gap-x-8 gap-y-4">
            <div>
              <p className="text-[11px] uppercase tracking-wide text-muted mb-2">Snapshot</p>
              <dl className="space-y-1.5 text-sm">
                <div className="flex justify-between"><dt className="text-muted">Merchants learned</dt><dd className="text-ink font-medium">{workspace.learnedMerchants} / {workspace.totalMerchants}</dd></div>
                <div className="flex justify-between"><dt className="text-muted">Active rules</dt><dd className="text-ink font-medium">{workspace.activeRules}</dd></div>
                <div className="flex justify-between"><dt className="text-muted">Relationships</dt><dd className="text-ink font-medium">{workspace.relationships}</dd></div>
                <div className="flex justify-between">
                  <dt className="text-muted">Auto-categorized</dt>
                  <dd className="text-ink font-medium">
                    {workspace.categorizationAccuracy !== null ? `${workspace.categorizationAccuracy.toFixed(0)}%` : '—'}
                  </dd>
                </div>
                <div className="flex justify-between"><dt className="text-muted">Duplicates / Transfers / Refunds</dt><dd className="text-ink font-medium">{workspace.duplicateMatches} / {workspace.transferMatches} / {workspace.refundMatches}</dd></div>
                <div className="flex justify-between"><dt className="text-muted">Recurring transactions</dt><dd className="text-ink font-medium">{workspace.recurringTransactions}</dd></div>
              </dl>
            </div>

            <div>
              <p className="text-[11px] uppercase tracking-wide text-muted mb-2">Merchant confidence</p>
              {confidenceTotal === 0 ? (
                <p className="text-xs text-muted">No merchants learned yet.</p>
              ) : (
                <div className="space-y-1.5">
                  <div className="h-2 rounded-full overflow-hidden flex bg-black/5">
                    {Object.entries(workspace.confidenceDistribution).map(([tier, count]) => (
                      count > 0 && (
                        <div key={tier} style={{ width: `${(count / confidenceTotal) * 100}%`, background: WORKSPACE_CONFIDENCE_TIER_COLOR[tier] ?? '#94a3b8' }} />
                      )
                    ))}
                  </div>
                  <div className="flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-muted">
                    {Object.entries(workspace.confidenceDistribution).map(([tier, count]) => (
                      <span key={tier} className="flex items-center gap-1">
                        <span className="w-1.5 h-1.5 rounded-full" style={{ background: WORKSPACE_CONFIDENCE_TIER_COLOR[tier] ?? '#94a3b8' }} />
                        {tier} ({count})
                      </span>
                    ))}
                  </div>
                </div>
              )}
            </div>

            <div>
              <p className="text-[11px] uppercase tracking-wide text-muted mb-2">Recent activity</p>
              {workspace.recentActivity.length === 0 ? (
                <p className="text-xs text-muted">No activity recorded yet.</p>
              ) : (
                <ul className="space-y-1.5">
                  {workspace.recentActivity.slice(0, 5).map((a) => (
                    <li key={a.id} className="text-xs text-ink truncate">{fmtWorkspaceActivityAction(a.action)}</li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        </>
      )}
    </div>
  );
}

// Bug fix: negative amounts (a refund-heavy category, a merchant net-credited overall) rendered
// as "₹-500" instead of "-₹500" -- same string-concatenation-order bug already fixed in both
// frontends' own fmt() helpers (see e.g. Dashboard.tsx), just not carried over to this one.
