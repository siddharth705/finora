import { useState } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import {
  LayoutDashboard, Wallet, ArrowLeftRight, PiggyBank, Target, UploadCloud, History,
  TrendingUp, BarChart3, Sparkles, User, Settings as SettingsIcon, MoreVertical, LogOut,
  ChevronsLeft, ChevronsRight, Receipt,
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { safeStorage } from '../lib/safeStorage';
import { BrandMark } from './BrandMark';

// Persisted so the choice survives a reload/new tab rather than resetting to expanded every
// time -- same reasoning TopBar's own read-notification tracking and ThemeContext already apply
// to their own preferences.
const COLLAPSED_STORAGE_KEY = 'finora_sidebar_collapsed';

const links = [
  { to: '/app', label: 'Dashboard', icon: LayoutDashboard, end: true },
  // The CSV import pipeline (CsvImportService, /app/import) has existed since early builds but
  // was never reachable from anywhere in the app's navigation — this was the actual reason it
  // looked like the feature didn't exist at all, not just that it needed polish.
  { to: '/app/import', label: 'Import Statement', icon: UploadCloud },
  { to: '/app/statements', label: 'Statement History', icon: History },
  { to: '/app/accounts', label: 'Accounts', icon: Wallet },
  { to: '/app/transactions', label: 'Transactions', icon: ArrowLeftRight },
  { to: '/app/budgets', label: 'Budgets', icon: PiggyBank },
  { to: '/app/goals', label: 'Goals', icon: Target },
  { to: '/app/investments', label: 'Investments', icon: TrendingUp },
  { to: '/app/reports', label: 'Reports', icon: BarChart3 },
  { to: '/app/insights', label: 'Insights', icon: Sparkles },
];

function initials(name: string | null) {
  if (!name) return '?';
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? '') + (parts[1]?.[0] ?? '')).toUpperCase();
}

export function Sidebar() {
  const { fullName, logout } = useAuth();
  const navigate = useNavigate();
  const [menuOpen, setMenuOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(() => safeStorage.getItem(COLLAPSED_STORAGE_KEY) === 'true');

  function toggleCollapsed() {
    setCollapsed((current) => {
      const next = !current;
      safeStorage.setItem(COLLAPSED_STORAGE_KEY, String(next));
      return next;
    });
  }

  function handleLogout() {
    setMenuOpen(false);
    logout();
    // logout() already clears the token in context, which makes ProtectedRoute redirect
    // on its own re-render — this just makes the jump immediate and explicit.
    void navigate('/login');
  }

  return (
    <aside className={`${collapsed ? 'w-20' : 'w-64'} flex-shrink-0 bg-sidebar min-h-screen flex flex-col py-6 px-3 transition-[width] duration-200`}>
      {/* Logo -- links back to the Dashboard, same as clicking the "Dashboard" nav item below.
          The collapse toggle sits next to it rather than floating separately, so there's one
          predictable place to look for it regardless of which state the sidebar is already in. */}
      <div className={`flex items-center mb-8 px-1 ${collapsed ? 'flex-col gap-3' : 'justify-between'}`}>
        <NavLink to="/app" end className="flex items-center gap-2.5 min-w-0">
          <div className="w-8 h-8 rounded-lg overflow-hidden flex-shrink-0">
            <BrandMark size={32} invert />
          </div>
          {!collapsed && <span className="text-white font-extrabold tracking-wide text-lg truncate">FINORA</span>}
        </NavLink>
        <button
          type="button"
          onClick={toggleCollapsed}
          title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          className="text-gray-500 hover:text-white hover:bg-sidebar-hover rounded-lg p-1.5 flex-shrink-0"
        >
          {collapsed ? <ChevronsRight size={16} /> : <ChevronsLeft size={16} />}
        </button>
      </div>

      {/* Nav -- collapsed drops the label text and centers the icon; title carries the label as
          a native tooltip so a collapsed item is still identifiable on hover, not just by icon
          shape alone. */}
      <nav className="flex-1 space-y-1">
        {links.map(({ to, label, icon: Icon, end }) => (
          <NavLink
            key={to}
            to={to}
            end={end}
            title={collapsed ? label : undefined}
            className={({ isActive }) =>
              // The sidebar is a fixed-dark surface regardless of the app's own light/dark
              // toggle, so the active state can't use the toggling `primary` token (it's dark
              // graphite in light mode — invisible against this always-dark background).
              `flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors ${collapsed ? 'justify-center' : ''} ${
                isActive ? 'bg-[#F4F1EC] text-[#15171C]' : 'text-gray-400 hover:bg-sidebar-hover hover:text-gray-200'
              }`
            }
          >
            <Icon size={18} strokeWidth={2} className="flex-shrink-0" />
            {!collapsed && label}
          </NavLink>
        ))}
      </nav>

      {/* User */}
      <div className="relative">
        <button
          type="button"
          onClick={() => setMenuOpen((v) => !v)}
          title={collapsed ? (fullName ?? 'Account') : undefined}
          className={`w-full flex items-center gap-2.5 px-2 pt-3 border-t border-white/10 ${collapsed ? 'justify-center' : ''}`}
        >
          <div className="w-8 h-8 rounded-full bg-[#F4F1EC] flex items-center justify-center text-[#15171C] text-xs font-semibold flex-shrink-0">
            {initials(fullName)}
          </div>
          {!collapsed && (
            <>
              <div className="min-w-0 flex-1 text-left">
                <p className="text-white text-sm font-medium truncate">{fullName ?? 'Account'}</p>
                <p className="text-gray-500 text-xs">View Profile</p>
              </div>
              <MoreVertical size={16} className="text-gray-500 flex-shrink-0" />
            </>
          )}
        </button>

        {menuOpen && (
          <>
            {/* Invisible full-screen overlay so clicking anywhere outside closes the menu. */}
            <div className="fixed inset-0 z-10" onClick={() => setMenuOpen(false)} />
            {/* Fixed width rather than left-0 right-0 stretch-fill -- in the collapsed (w-20)
                state that would squeeze the menu down to 80px, unreadable. Anchored to the left
                edge either way, so it can spill past the sidebar's own right edge onto the main
                content when collapsed -- the same tradeoff any collapsed-sidebar popup menu makes. */}
            <div className="absolute bottom-full left-0 mb-2 w-56 bg-sidebar-hover border border-white/10 rounded-lg shadow-xl py-1.5 z-20">
              <NavLink
                to="/app/profile"
                onClick={() => setMenuOpen(false)}
                className="flex items-center gap-2.5 px-3.5 py-2.5 text-sm text-gray-300 hover:text-white hover:bg-white/5"
              >
                <User size={15} /> Profile
              </NavLink>
              <NavLink
                to="/app/settings"
                onClick={() => setMenuOpen(false)}
                className="flex items-center gap-2.5 px-3.5 py-2.5 text-sm text-gray-300 hover:text-white hover:bg-white/5"
              >
                <SettingsIcon size={15} /> Settings
              </NavLink>
              <NavLink
                to="/app/billing"
                onClick={() => setMenuOpen(false)}
                className="flex items-center gap-2.5 px-3.5 py-2.5 text-sm text-gray-300 hover:text-white hover:bg-white/5"
              >
                <Receipt size={15} /> Billing History
              </NavLink>
              <button
                type="button"
                onClick={handleLogout}
                className="w-full flex items-center gap-2.5 px-3.5 py-2.5 text-sm text-red-400 hover:text-red-300 hover:bg-white/5"
              >
                <LogOut size={15} /> Log out
              </button>
            </div>
          </>
        )}
      </div>
    </aside>
  );
}
