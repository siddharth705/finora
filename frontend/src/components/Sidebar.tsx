import { useState } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import {
  LayoutDashboard, Wallet, ArrowLeftRight, PiggyBank, Target, UploadCloud, History,
  TrendingUp, BarChart3, Sparkles, User, Settings as SettingsIcon, MoreVertical, LogOut,
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import logoMark from '../assets/logo-mark.png';

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

  function handleLogout() {
    setMenuOpen(false);
    logout();
    // logout() already clears the token in context, which makes ProtectedRoute redirect
    // on its own re-render — this just makes the jump immediate and explicit.
    void navigate('/login');
  }

  return (
    <aside className="w-64 flex-shrink-0 bg-sidebar min-h-screen flex flex-col py-6 px-4">
      {/* Logo -- links back to the Dashboard, same as clicking the "Dashboard" nav item below. */}
      <NavLink to="/app" end className="flex items-center gap-2.5 px-2 mb-8">
        <div className="w-8 h-8 rounded-lg overflow-hidden flex-shrink-0">
          <img src={logoMark} alt="" className="w-full h-full object-cover" />
        </div>
        <span className="text-white font-extrabold tracking-wide text-lg">FINORA</span>
      </NavLink>

      {/* Nav */}
      <nav className="flex-1 space-y-1">
        {links.map(({ to, label, icon: Icon, end }) => (
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

      {/* User */}
      <div className="relative">
        <button
          type="button"
          onClick={() => setMenuOpen((v) => !v)}
          className="w-full flex items-center gap-2.5 px-2 pt-3 border-t border-white/10"
        >
          <div className="w-8 h-8 rounded-full bg-primary flex items-center justify-center text-white text-xs font-semibold flex-shrink-0">
            {initials(fullName)}
          </div>
          <div className="min-w-0 flex-1 text-left">
            <p className="text-white text-sm font-medium truncate">{fullName ?? 'Account'}</p>
            <p className="text-gray-500 text-xs">View Profile</p>
          </div>
          <MoreVertical size={16} className="text-gray-500 flex-shrink-0" />
        </button>

        {menuOpen && (
          <>
            {/* Invisible full-screen overlay so clicking anywhere outside closes the menu. */}
            <div className="fixed inset-0 z-10" onClick={() => setMenuOpen(false)} />
            <div className="absolute bottom-full left-0 right-0 mb-2 bg-sidebar-hover border border-white/10 rounded-lg shadow-xl py-1.5 z-20">
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
