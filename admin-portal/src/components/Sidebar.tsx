import { NavLink, useNavigate } from 'react-router-dom';
import {
  LayoutDashboard, Users, ShieldCheck, ScrollText, HeartPulse, LogOut, ShieldAlert, Landmark, Settings,
  ListFilter, Store, Sparkles, GitMerge, BarChart3, Flag, Stethoscope,
} from 'lucide-react';
import { useAdminAuth } from '../context/AdminAuthContext';

// Every entry carries the same shape (including `end`, even when false) -- a mixed shape where
// only some entries had an `end` key would make the destructuring in visibleLinks.map() below
// fail to type-check for the entries that omitted it.
const links = [
  { to: '/', label: 'Dashboard', icon: LayoutDashboard, end: true, permission: null },
  { to: '/users', label: 'Users', icon: Users, end: false, permission: 'USER_VIEW' },
  { to: '/roles', label: 'Roles & Permissions', icon: ShieldCheck, end: false, permission: 'ROLE_MANAGE' },
  { to: '/banks', label: 'Banks', icon: Landmark, end: false, permission: 'BANK_MANAGE' },
  { to: '/merchants', label: 'Merchant Intelligence', icon: Store, end: false, permission: 'MERCHANT_MANAGE' },
  { to: '/rules', label: 'Global Rules', icon: ListFilter, end: false, permission: 'RULE_MANAGE' },
  { to: '/learning', label: 'Learning Engine', icon: Sparkles, end: false, permission: 'MERCHANT_MANAGE' },
  { to: '/reconciliation', label: 'Reconciliation Monitor', icon: GitMerge, end: false, permission: 'RECONCILIATION_VIEW' },
  { to: '/analytics', label: 'Platform Analytics', icon: BarChart3, end: false, permission: 'PLATFORM_ANALYTICS_VIEW' },
  { to: '/audit', label: 'Audit Log', icon: ScrollText, end: false, permission: 'AUDIT_VIEW' },
  { to: '/health', label: 'System Health', icon: HeartPulse, end: false, permission: 'PLATFORM_DIAGNOSTICS_VIEW' },
  { to: '/diagnostics', label: 'Platform Diagnostics', icon: Stethoscope, end: false, permission: 'PLATFORM_DIAGNOSTICS_VIEW' },
  { to: '/feature-flags', label: 'Feature Flags', icon: Flag, end: false, permission: 'SYSTEM_SETTINGS' },
  { to: '/settings', label: 'Settings', icon: Settings, end: false, permission: 'SYSTEM_SETTINGS' },
] as const;

function initials(name: string | null) {
  if (!name) return '?';
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? '') + (parts[1]?.[0] ?? '')).toUpperCase();
}

export function Sidebar() {
  const { fullName, permissions, logout } = useAdminAuth();
  const navigate = useNavigate();

  function handleLogout() {
    logout();
    navigate('/login');
  }

  // Nav items whose permission (if any) the account actually holds -- a support-scoped admin
  // (AUDIT_VIEW only, say) sees just Dashboard + Audit Log, not every section that exists.
  const visibleLinks = links.filter((l) => l.permission === null || permissions.includes(l.permission));

  return (
    <aside className="w-64 flex-shrink-0 bg-sidebar min-h-screen flex flex-col py-6 px-4">
      <div className="flex items-center gap-2.5 px-2 mb-1">
        <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-rose-400 to-primary-dark flex items-center justify-center flex-shrink-0">
          <ShieldAlert size={16} className="text-white" strokeWidth={2.5} />
        </div>
        <span className="text-white font-extrabold tracking-wide text-lg">FINORA</span>
      </div>
      <div className="px-2 mb-8">
        <span className="inline-block bg-warning-bg text-warning text-[10px] font-bold tracking-widest uppercase rounded px-2 py-0.5">
          Admin
        </span>
      </div>

      <nav className="flex-1 space-y-1">
        {visibleLinks.map(({ to, label, icon: Icon, end }) => (
          <NavLink
            key={to}
            to={to}
            end={end}
            className={({ isActive }) =>
              `flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors ${
                isActive ? 'bg-primary text-white' : 'text-gray-400 hover:bg-sidebar-hover hover:text-gray-200'
              }`
            }
          >
            <Icon size={18} strokeWidth={2} />
            {label}
          </NavLink>
        ))}
      </nav>

      <div className="pt-3 border-t border-white/10">
        <div className="flex items-center gap-2.5 px-2 pb-3">
          <div className="w-8 h-8 rounded-full bg-primary flex items-center justify-center text-white text-xs font-semibold flex-shrink-0">
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
