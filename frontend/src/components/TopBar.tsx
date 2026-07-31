import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  Search, Bell, HelpCircle, Sun, Moon, Monitor, Check,
  Settings as SettingsIcon, Keyboard, LogOut, Mail, X, BellOff,
} from 'lucide-react';
import { useTheme, type ThemeSetting } from '../context/ThemeContext';
import { useAuth } from '../context/AuthContext';
import { dashboardApi } from '../api/endpoints';

// Notifications are recomputed fresh from the DB on every /dashboard/summary call (see
// DashboardService.buildNotifications) rather than being persisted rows with stable IDs, so
// there's nothing to mark-as-read server-side. Tracking read state by the notification's exact
// text, client-side, is the honest option here — it's also *correct* behavior, not a hack: if a
// bill's due-date message changes ("in 3 days" -> "in 2 days"), that's genuinely new information
// and should surface as unread again.
//
// Scoped per-account (by email) rather than one shared browser-wide key -- on a shared/family
// computer, a global key would let User A's "already read" state silently mark User B's
// brand-new notifications as read the moment they log in, purely because the two messages
// happen to share identical generic text (e.g. "Your account balance is low").
function readStorageKey(email: string | null): string {
  return `finora_read_notifications_${email ?? 'anonymous'}`;
}

function loadReadSet(email: string | null): Set<string> {
  try {
    return new Set(JSON.parse(localStorage.getItem(readStorageKey(email)) ?? '[]'));
  } catch {
    return new Set();
  }
}

function initials(name: string | null) {
  if (!name) return '?';
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? '') + (parts[1]?.[0] ?? '')).toUpperCase();
}

const THEME_OPTIONS: { value: ThemeSetting; label: string; icon: typeof Sun }[] = [
  { value: 'light', label: 'Light', icon: Sun },
  { value: 'dark', label: 'Dark', icon: Moon },
  { value: 'system', label: 'System', icon: Monitor },
];

type OpenMenu = 'theme' | 'notifications' | 'help' | null;

export function TopBar() {
  const navigate = useNavigate();
  const { fullName, email, logout } = useAuth();
  const { theme, resolvedTheme, setTheme } = useTheme();

  const [openMenu, setOpenMenu] = useState<OpenMenu>(null);
  const [shortcutsOpen, setShortcutsOpen] = useState(false);
  const [readIds, setReadIds] = useState<Set<string>>(() => loadReadSet(email));
  const [searchValue, setSearchValue] = useState('');
  const searchRef = useRef<HTMLInputElement>(null);

  // Re-load the read-set whenever the signed-in account changes (e.g. one user logs out and a
  // different user logs into the same browser) so stale read-state from a prior account never
  // bleeds into the newly signed-in one.
  useEffect(() => {
    setReadIds(loadReadSet(email));
  }, [email]);

  // Same query key Dashboard.tsx uses for its own summary fetch — TanStack Query dedupes by
  // key, so mounting both on /app shares one in-flight request/cache entry instead of firing a
  // second network call for the same data.
  const { data: summary } = useQuery({
    queryKey: ['dashboard-summary'],
    queryFn: () => dashboardApi.summary(),
  });
  const notifications = summary?.notifications ?? [];
  const unreadCount = notifications.filter((n) => !readIds.has(n)).length;

  function markAllRead() {
    const next = new Set(notifications);
    setReadIds(next);
    localStorage.setItem(readStorageKey(email), JSON.stringify([...next]));
  }

  function toggleMenu(menu: Exclude<OpenMenu, null>) {
    setOpenMenu((current) => (current === menu ? null : menu));
  }

  function runSearch() {
    const q = searchValue.trim();
    if (!q) return;
    setOpenMenu(null);
    navigate(`/app/transactions?q=${encodeURIComponent(q)}`);
  }

  function handleLogout() {
    setOpenMenu(null);
    logout();
    navigate('/login');
  }

  // Ctrl/Cmd+K focuses search from anywhere in the app; Escape closes whatever's open — both
  // are also what the Keyboard Shortcuts panel documents, so the list stays honest.
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        searchRef.current?.focus();
      } else if (e.key === 'Escape') {
        setOpenMenu(null);
        setShortcutsOpen(false);
      }
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  const ThemeIcon = theme === 'light' ? Sun : theme === 'dark' ? Moon : Monitor;

  return (
    <div className="flex items-center justify-between gap-4 mb-8">
      <div className="relative flex-1 max-w-md">
        <Search size={16} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-muted" />
        <input
          ref={searchRef}
          placeholder="Search transactions…"
          value={searchValue}
          onChange={(e) => setSearchValue(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && runSearch()}
          className="w-full bg-card border border-border rounded-lg pl-10 pr-16 py-2.5 text-sm shadow-card focus:outline-none focus:ring-2 focus:ring-primary/30"
        />
        <span className="absolute right-3 top-1/2 -translate-y-1/2 text-[11px] text-muted bg-bg border border-border rounded px-1.5 py-0.5">
          Ctrl + K
        </span>
      </div>
      <div className="flex items-center gap-3 flex-shrink-0">
        {/* Theme */}
        <div className="relative">
          <button
            type="button"
            onClick={() => toggleMenu('theme')}
            title="Theme"
            className="w-10 h-10 rounded-full bg-card border border-border shadow-card flex items-center justify-center text-muted hover:text-ink"
          >
            <ThemeIcon size={17} />
          </button>
          {openMenu === 'theme' && (
            <Dropdown onClose={() => setOpenMenu(null)}>
              <p className="px-3.5 py-2 text-[11px] uppercase tracking-wide text-muted">Theme</p>
              {THEME_OPTIONS.map(({ value, label, icon: Icon }) => (
                <button
                  key={value}
                  type="button"
                  onClick={() => {
                    setTheme(value);
                    setOpenMenu(null);
                  }}
                  className="w-full flex items-center gap-2.5 px-3.5 py-2.5 text-sm text-ink hover:bg-bg"
                >
                  <Icon size={15} className="text-muted" />
                  <span className="flex-1 text-left">{label}</span>
                  {theme === value && <Check size={15} className="text-primary" />}
                </button>
              ))}
              <p className="px-3.5 pt-1 pb-2 text-[11px] text-muted">
                Currently showing {resolvedTheme === 'dark' ? 'dark' : 'light'}
                {theme === 'system' ? ' (following your device)' : ''}.
              </p>
            </Dropdown>
          )}
        </div>

        {/* Notifications */}
        <div className="relative">
          <button
            type="button"
            onClick={() => toggleMenu('notifications')}
            title="Notifications"
            className="relative w-10 h-10 rounded-full bg-card border border-border shadow-card flex items-center justify-center text-muted hover:text-ink"
          >
            <Bell size={17} />
            {unreadCount > 0 && (
              <span className="absolute -top-1 -right-1 w-4.5 h-4.5 min-w-[18px] px-1 rounded-full bg-danger text-white text-[10px] font-semibold flex items-center justify-center">
                {unreadCount}
              </span>
            )}
          </button>
          {openMenu === 'notifications' && (
            <Dropdown onClose={() => setOpenMenu(null)} width="w-80">
              <div className="flex items-center justify-between px-3.5 py-2.5 border-b border-border">
                <p className="text-sm font-semibold text-ink">Notifications</p>
                {unreadCount > 0 && (
                  <button type="button" onClick={markAllRead} className="text-xs text-primary font-medium">
                    Mark all as read
                  </button>
                )}
              </div>
              <div className="max-h-80 overflow-y-auto">
                {notifications.length === 0 ? (
                  <div className="px-3.5 py-8 flex flex-col items-center text-center gap-2">
                    <BellOff size={20} className="text-muted" />
                    <p className="text-xs text-muted">No notifications right now.</p>
                  </div>
                ) : (
                  notifications.map((n, i) => {
                    const isUnread = !readIds.has(n);
                    return (
                      <div key={i} className="flex items-start gap-2.5 px-3.5 py-2.5 border-b border-border last:border-b-0">
                        <span className={`mt-1.5 w-1.5 h-1.5 rounded-full flex-shrink-0 ${isUnread ? 'bg-primary' : 'bg-transparent'}`} />
                        <p className={`text-xs leading-relaxed ${isUnread ? 'text-ink font-medium' : 'text-muted'}`}>{n}</p>
                      </div>
                    );
                  })
                )}
              </div>
            </Dropdown>
          )}
        </div>

        {/* Profile / Help */}
        <div className="relative">
          <button
            type="button"
            onClick={() => toggleMenu('help')}
            title="Profile & help"
            className="w-10 h-10 rounded-full bg-card border border-border shadow-card flex items-center justify-center text-muted hover:text-ink"
          >
            <HelpCircle size={17} />
          </button>
          {openMenu === 'help' && (
            <Dropdown onClose={() => setOpenMenu(null)} width="w-64">
              <div className="flex items-center gap-2.5 px-3.5 py-3 border-b border-border">
                <div className="w-8 h-8 rounded-full bg-primary flex items-center justify-center text-white text-xs font-semibold flex-shrink-0">
                  {initials(fullName)}
                </div>
                <div className="min-w-0">
                  <p className="text-sm font-medium text-ink truncate">{fullName ?? 'Account'}</p>
                  <p className="text-xs text-muted truncate">{email}</p>
                </div>
              </div>
              <button
                type="button"
                onClick={() => {
                  setOpenMenu(null);
                  navigate('/app/settings');
                }}
                className="w-full flex items-center gap-2.5 px-3.5 py-2.5 text-sm text-ink hover:bg-bg"
              >
                <SettingsIcon size={15} className="text-muted" /> Settings
              </button>
              <button
                type="button"
                onClick={() => {
                  setOpenMenu(null);
                  setShortcutsOpen(true);
                }}
                className="w-full flex items-center gap-2.5 px-3.5 py-2.5 text-sm text-ink hover:bg-bg"
              >
                <Keyboard size={15} className="text-muted" /> Keyboard shortcuts
              </button>
              <a
                href="mailto:support@finora.app?subject=Finora%20feedback"
                onClick={() => setOpenMenu(null)}
                className="w-full flex items-center gap-2.5 px-3.5 py-2.5 text-sm text-ink hover:bg-bg"
              >
                <Mail size={15} className="text-muted" /> Send feedback
              </a>
              <div className="border-t border-border" />
              <button
                type="button"
                onClick={handleLogout}
                className="w-full flex items-center gap-2.5 px-3.5 py-2.5 text-sm text-danger hover:bg-danger-bg"
              >
                <LogOut size={15} /> Log out
              </button>
            </Dropdown>
          )}
        </div>
      </div>

      {shortcutsOpen && <ShortcutsModal onClose={() => setShortcutsOpen(false)} />}
    </div>
  );
}

/** Dropdown panel + full-screen click-outside overlay — same pattern Sidebar.tsx uses for its
 *  account menu, kept consistent rather than introducing a second popover convention. */
function Dropdown({ children, onClose, width = 'w-56' }: { children: ReactNode; onClose: () => void; width?: string }) {
  return (
    <>
      <div className="fixed inset-0 z-10" onClick={onClose} />
      <div className={`absolute right-0 top-full mt-2 ${width} bg-card border border-border rounded-lg shadow-soft py-1.5 z-20`}>
        {children}
      </div>
    </>
  );
}

function ShortcutsModal({ onClose }: { onClose: () => void }) {
  const shortcuts = [
    { keys: 'Ctrl / Cmd + K', desc: 'Focus the search bar' },
    { keys: 'Enter', desc: 'Search transactions (while search is focused)' },
    { keys: 'Esc', desc: 'Close any open menu or dialog' },
  ];
  return (
    <>
      <div className="fixed inset-0 bg-black/40 z-30" onClick={onClose} />
      <div className="fixed inset-0 z-40 flex items-center justify-center p-4 pointer-events-none">
        <div className="bg-card border border-border rounded-xl2 shadow-soft w-full max-w-sm p-5 pointer-events-auto">
          <div className="flex items-center justify-between mb-4">
            <h3 className="font-semibold text-ink">Keyboard shortcuts</h3>
            <button type="button" onClick={onClose} className="text-muted hover:text-ink">
              <X size={18} />
            </button>
          </div>
          <div className="space-y-3">
            {shortcuts.map((s) => (
              <div key={s.keys} className="flex items-center justify-between gap-4">
                <span className="text-sm text-muted">{s.desc}</span>
                <kbd className="text-[11px] text-ink bg-bg border border-border rounded px-2 py-1 flex-shrink-0">{s.keys}</kbd>
              </div>
            ))}
          </div>
        </div>
      </div>
    </>
  );
}
