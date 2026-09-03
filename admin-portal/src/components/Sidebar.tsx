import { useEffect, useState } from 'react';
import { NavLink, useLocation, useNavigate } from 'react-router-dom';
import {
  LayoutDashboard, Users, ShieldCheck, ScrollText, HeartPulse, LogOut, Landmark, Settings,
  ListFilter, Store, FileCode, Sparkles, GitMerge, BarChart3, Stethoscope, FileSearch, ListRestart , BadgeCheck, Fingerprint, Route,
  CreditCard, Gift, Plug, Waypoints, Lightbulb, ListOrdered, ChevronDown, ChevronRight, Bell, Clock } from 'lucide-react';
import { useAdminAuth } from '../context/AdminAuthContext';
import { BrandMark } from './BrandMark';
import { initials } from '../lib/initials';

// Dashboard has no `group` -- it renders above every group, not inside one, same as it always
// rendered first in the old flat list. Every other entry carries the same shape (including
// `end`, even when false) -- a mixed shape where only some entries had an `end` key would make
// the destructuring in visibleGroups.map() below fail to type-check for the entries that omitted
// it. Grouping is pure presentation: the SAME 24 links, same permissions, same routes -- just
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
      { to: '/insights-explorer', label: 'Insight Explorer', icon: Lightbulb, end: false, permission: 'INSIGHTS_EXPLORER_VIEW' },
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
      { to: '/notifications', label: 'Notifications', icon: Bell, end: false, permission: 'NOTIFICATION_MANAGE' },
      { to: '/health', label: 'System Health', icon: HeartPulse, end: false, permission: 'PLATFORM_DIAGNOSTICS_VIEW' },
      { to: '/integrations', label: 'Integrations', icon: Plug, end: false, permission: 'PLATFORM_DIAGNOSTICS_VIEW' },
      { to: '/diagnostics', label: 'Platform Diagnostics', icon: Stethoscope, end: false, permission: 'PLATFORM_DIAGNOSTICS_VIEW' },
      { to: '/layout-intelligence', label: 'Layout Intelligence', icon: Fingerprint, end: false, permission: 'PLATFORM_DIAGNOSTICS_VIEW' },
      { to: '/layout-studio', label: 'Layout Studio', icon: FileSearch, end: false, permission: 'PLATFORM_DIAGNOSTICS_VIEW' },
      { to: '/held-imports', label: 'Held Imports', icon: Clock, end: false, permission: 'IMPORT_TRIAGE_MANAGE' },
      { to: '/import-trace', label: 'Import Trace', icon: Route, end: false, permission: 'PLATFORM_DIAGNOSTICS_VIEW' },
      { to: '/import-row-trace', label: 'Import Row Trace', icon: ListOrdered, end: false, permission: 'PLATFORM_DIAGNOSTICS_VIEW' },
      { to: '/settings', label: 'Settings', icon: Settings, end: false, permission: 'SYSTEM_SETTINGS' },
    ],
  },
] as const;

// 24 links across 5 groups is a lot to scan at once -- collapsing groups the admin isn't
// currently using is the whole point of this being persisted, not reset every login the way a
// dashboard card's default-expanded state is (see FinancialJourney.tsx's own comment on why that
// one deliberately does the opposite). Best-effort only: a private-browsing tab or a blocked
// localStorage falls back to "everything expanded", not a crash.
const COLLAPSED_GROUPS_STORAGE_KEY = 'finora-admin-sidebar-collapsed-groups';

function loadCollapsedGroups(): Set<string> {
  try {
    const raw = localStorage.getItem(COLLAPSED_GROUPS_STORAGE_KEY);
    return raw ? new Set(JSON.parse(raw)) : new Set();
  } catch {
    return new Set();
  }
}

function saveCollapsedGroups(collapsed: Set<string>) {
  try {
    localStorage.setItem(COLLAPSED_GROUPS_STORAGE_KEY, JSON.stringify([...collapsed]));
  } catch {
    // Best-effort persistence -- a group toggle still works for the rest of this session either way.
  }
}

// Matches NavLink's own `end` semantics (exact vs. prefix) so a group auto-opens for whichever
// route is actually current, not just whichever route it was on when first mounted -- landing
// directly on e.g. /audit via a bookmark must reveal Governance even if the admin had it
// collapsed from a previous session. `+ '/'` guards against '/reconciliation' prefix-matching
// '/reconciliation-explorer', a real collision in this exact link set.
function linkIsActive(pathname: string, link: { to: string; end: boolean }): boolean {
  return link.end ? pathname === link.to : pathname === link.to || pathname.startsWith(`${link.to}/`);
}

export function Sidebar() {
  const { fullName, permissions, logout } = useAdminAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(loadCollapsedGroups);

  function handleLogout() {
    logout();
    void navigate('/login');
  }

  function toggleGroup(label: string) {
    setCollapsedGroups((prev) => {
      const next = new Set(prev);
      if (next.has(label)) next.delete(label); else next.add(label);
      saveCollapsedGroups(next);
      return next;
    });
  }

  // Nav items whose permission (if any) the account actually holds -- a support-scoped admin
  // (AUDIT_VIEW only, say) sees just Dashboard + Audit Log, not every section that exists. A
  // group with zero visible links renders no header at all, so an account with a narrow
  // permission set doesn't see empty "Operations"/"System" section labels above nothing.
  const visibleGroups = GROUPS
    .map((group) => ({ ...group, links: group.links.filter((l) => permissions.includes(l.permission)) }))
    .filter((group) => group.links.length > 0);

  // Landing directly on a route (bookmark, deep link, a previous toggle) whose group is
  // currently collapsed must still open that group -- otherwise the active highlight would be
  // hidden with no way to tell where you are. Keyed on location.pathname, not run on every
  // render: a render-time check here (rather than an effect gated on navigation) would refire the
  // instant the admin manually collapses the group they're currently standing in, since that
  // group is still "active" on the very next render -- the toggle would silently undo itself.
  // Only an actual route change should re-force it open.
  useEffect(() => {
    const activeGroupLabel = visibleGroups.find((g) => g.links.some((l) => linkIsActive(location.pathname, l)))?.label;
    if (!activeGroupLabel) return;
    setCollapsedGroups((prev) => {
      if (!prev.has(activeGroupLabel)) return prev;
      const next = new Set(prev);
      next.delete(activeGroupLabel);
      saveCollapsedGroups(next);
      return next;
    });
    // Only navigation should re-evaluate this -- visibleGroups is a fresh array every render
    // (permissions rarely change mid-session) and must not retrigger the effect on its own.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.pathname]);

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

        {visibleGroups.map((group) => {
          const isOpen = !collapsedGroups.has(group.label);
          return (
            <div key={group.label}>
              <button
                type="button"
                onClick={() => toggleGroup(group.label)}
                aria-expanded={isOpen}
                className="w-full flex items-center justify-between px-3 mb-1.5 text-[11px] font-semibold text-gray-500 uppercase tracking-widest hover:text-gray-300"
              >
                {group.label}
                {isOpen
                  ? <ChevronDown size={13} className="flex-shrink-0" />
                  : <ChevronRight size={13} className="flex-shrink-0" />}
              </button>
              {isOpen && (
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
              )}
            </div>
          );
        })}
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
