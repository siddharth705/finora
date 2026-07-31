import type { ReactNode } from 'react';
import { Sidebar } from './Sidebar';
import { GlobalSearch } from './GlobalSearch';
import { useAdminAuth } from '../context/AdminAuthContext';

/**
 * Deliberately simpler than the user app's AppShell + TopBar (finora/frontend/src/App.tsx,
 * TopBar.tsx) -- no theme toggle, no notification bell. This app now has a header search box
 * (GlobalSearch, Admin Portal Phase 2) fanning out across Users/Merchants/Banks/Global Rules
 * server-side (AdminSearchController) -- everything else stays a title + the signed-in account's
 * email, which is enough context on every screen without pulling in ThemeContext/dashboard-query
 * dependencies this app's dependency footprint has deliberately kept separate from the user app's.
 */
export function AdminLayout({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  const { email } = useAdminAuth();
  return (
    <div className="min-h-screen bg-bg flex">
      <Sidebar />
      <main className="flex-1 p-8 max-w-[1600px]">
        <div className="flex items-start justify-between mb-8 gap-6">
          <div className="min-w-0">
            <h1 className="text-xl font-bold text-ink">{title}</h1>
            {subtitle && <p className="text-sm text-muted mt-1">{subtitle}</p>}
          </div>
          <div className="flex items-center gap-3 flex-shrink-0">
            <GlobalSearch />
            <span className="text-xs text-muted bg-card border border-border rounded-full px-3 py-1.5 shadow-card whitespace-nowrap">
              {email}
            </span>
          </div>
        </div>
        {children}
      </main>
    </div>
  );
}
