import { NavLink, useNavigate } from 'react-router-dom';
import {
  LayoutDashboard, Users, ShieldCheck, ScrollText, HeartPulse, LogOut, Landmark, Settings,
  ListFilter, Store, FileCode, Sparkles, GitMerge, BarChart3, Stethoscope, FileSearch, ListRestart , BadgeCheck, Fingerprint, Route,
  CreditCard, Gift, Plug, Waypoints } from 'lucide-react';
import { useAdminAuth } from '../context/AdminAuthContext';
import { BrandMark } from './BrandMark';
import { initials } from '../lib/initials';

// Dashboard has no `group` -- it renders above every group, not inside one, same as it always
// rendered first in the old flat list. Every other entry carries the same shape (including
// `end`, even when false) -- a mixed shape where only some entries had an `end` key would make
// the destructuring in visibleGroups.map() below fail to type-check for the entries that omitted
// it. Grouping is pure presentation: the SAME 21 links, same permissions, same routes -- just
// under section headers instead of one long flat list. A link's group here has no bearing on
// which permission gates it or which page it points to.
const DASHBOARD_LINK = { to: '/', label: 'Dashboard', icon: LayoutDashboard, end: true, permission: null } as const;

const GROUPS = [
  {
    label: 'Core',
    links: [
      { to: '/users', label: 'Users', icon: Users, end: false, permission: 'USER_VIEW' },
      { to: '/banks', label: 'Banks', icon: Landmark, end: false, permission: 'BANK_MANAGE' },
      { to: '/merchants', label: 'Merchant Intelligence', icon: Store, end: false, permission: 'MERCHANT_MANAGE' },
      { to: '/merchant-templates', label: 'Merchant Templates', icon: FileCode, end: false, permission: 'MERCHANT_MANAGE' },
    ],
  },
  {
    label: 'Intelligence',
    links: [
      { to: '/rules', label: 'Global Rules', icon: ListFilter, end: false, permission: 'RULE_MANAGE' },
      { to: '/learning', label: 'Learning Engine', icon: Sparkles, end: false, permission: 'MERCHANT_MANAGE' },
      { to: '/merchant-review', label: 'Merchant Review', icon: BadgeCheck, end: false, permission: 'MERCHANT_REVIEW' },
      { to: '/learning-queue', label: 'Learning Queue', icon: ListRestart, end: false, permission: 'LEARNING_QUEUE_MANAGE' },
      { to: '/reconciliation', label: 'Reconciliation Monitor', icon: GitMerge, end: false, permission: 'RECONCILIATION_VIEW' },
      { to: '/reconciliation-explorer', label: 'Reconciliation Explorer', icon: Waypoints, end: false, permission: 'RECONCILIATION_VIEW' },
      { to: '/analytics', label: 'Platform Analytics', icon: BarChart3, end: false, permission: 'PLATFORM_ANALYTICS_VIEW' },
    ],
  },
  {
    label: 'Governance',
    links: [
      { to: '/roles', label: 'Roles & Permissions', icon: ShieldCheck, end: false, permission: 'ROLE_MANAGE' },
      { to: '/audit', label: 'Audit Log', icon: ScrollText, end: false, permission: 'AUDIT_VIEW' },
    ],
  },
  {
    label: 'Operations',
    links: [
      { to: '/subscriptions', label: 'Subscriptions', icon: CreditCard, end: false, permission: 'SUBSCRIPTION_MANAGEMENT_VIEW' },
      { to: '/referrals', label: 'Referrals', icon: Gift, end: false, permission: 'REFERRAL_MANAGEMENT_VIEW' },
    ],
  },
  {
    label: 'System',
    links: [
      { to: '/health', label: 'System Health', icon: HeartPulse, end: false, permission: 'PLATFORM_DIAGNOSTICS_VIEW' },
      { to: '/integrations', label: 'Integrations', icon: Plug, end: false, permission: 'PLATFORM_DIAGNOSTICS_VIEW' },
      { to: '/diagnostics', label: 'Platform Diagnostics', icon: Stethoscope, end: false, permission: 'PLATFORM_DIAGNOSTICS_VIEW' },
      { to: '/layout-intelligence', label: 'Layout Intelligence', icon: Fingerprint, end: false, permission: 'PLATFORM_DIAGNOSTICS_VIEW' },
      { to: '/layout-studio', label: 'Layout Studio', icon: FileSearch, end: false, permission: 'PLATFORM_DIAGNOSTICS_VIEW' },
      { to: '/import-trace', label: 'Import Trace', icon: Route, end: false, permission: 'PLATFORM_DIAGNOSTICS_VIEW' },
      { to: '/settings', label: 'Settings', icon: Settings, end: false, permission: 'SYSTEM_SETTINGS' },
    ],
  },
] as const;

export function Sidebar() {
  const { fullName, permissions, logout } = useAdminAuth();
  const navigate = useNavigate();

  function handleLogout() {
    logout();
    void navigate('/login');
  }

  // Nav items whose permission (if any) the account actually holds -- a support-scoped admin
  // (AUDIT_VIEW only, say) sees just Dashboard + Audit Log, not every section that exists. A
  // group with zero visible links renders no header at all, so an account with a narrow
  // permission set doesn't see empty "Operations"/"System" section labels above nothing.
  const visibleGroups = GROUPS
    .map((group) => ({ ...group, links: group.links.filter((l) => permissions.includes(l.permission)) }))
    .filter((group) => group.links.length > 0);

  return (
    <aside className="w-64 flex-shrink-0 bg-sidebar min-h-screen flex flex-col py-6 px-4">
      <div className="flex items-center gap-2.5 px-2 mb-1">
        <BrandMark size={32} invert className="rounded-lg flex-shrink-0" />
        <span className="text-white font-extrabold tracking-wide text-lg">FYNORA</span>
      </div>
      <div className="px-2 mb-8">
        <span className="inline-block bg-warning-bg text-warning text-[10px] font-bold tracking-widest uppercase rounded px-2 py-0.5">
          Admin
        </span>
      </div>

      <nav className="flex-1 space-y-5 overflow-y-auto">
        <NavLink
          to={DASHBOARD_LINK.to}
          end={DASHBOARD_LINK.end}
          className={({ isActive }) =>
            `flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors ${
              isActive ? 'bg-primary text-on-primary' : 'text-gray-400 hover:bg-sidebar-hover hover:text-gray-200'
            }`
          }
        >
          <DASHBOARD_LINK.icon size={18} strokeWidth={2} />
          {DASHBOARD_LINK.label}
        </NavLink>

        {visibleGroups.map((group) => (
          <div key={group.label}>
            <p className="px-3 mb-1.5 text-[11px] font-semibold text-gray-500 uppercase tracking-widest">
              {group.label}
            </p>
            <div className="space-y-1">
              {group.links.map(({ to, label, icon: Icon, end }) => (
                <NavLink
                  key={to}
                  to={to}
                  end={end}
                  className={({ isActive }) =>
                    `flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors ${
                      isActive ? 'bg-primary text-on-primary' : 'text-gray-400 hover:bg-sidebar-hover hover:text-gray-200'
                    }`
                  }
                >
                  <Icon size={18} strokeWidth={2} />
                  {label}
                </NavLink>
              ))}
            </div>
          </div>
        ))}
      </nav>

      <div className="pt-3 border-t border-white/10">
        <div className="flex items-center gap-2.5 px-2 pb-3">
          <div className="w-8 h-8 rounded-full bg-primary flex items-center justify-center text-on-primary text-xs font-semibold flex-shrink-0">
            {initials(fullName)}
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-white text-sm font-medium truncate">{fullName ?? 'Account'}</p>
          </div>
        </div>
        <button
          type="button"
          onClick={handleLogout}
          className="w-full flex items-center gap-2.5 px-3 py-2.5 rounded-lg text-sm text-red-400 hover:text-red-300 hover:bg-white/5"
        >
          <LogOut size={15} /> Log out
        </button>
      </div>
    </aside>
  );
}
