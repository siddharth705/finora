import type { ReactNode } from 'react';
import { ErrorBoundary } from './ErrorBoundary';
import { Sidebar } from './Sidebar';
import { GlobalSearch } from './GlobalSearch';
import { ThemeToggle } from './ThemeToggle';
import { useAdminAuth } from '../context/AdminAuthContext';

/**
 * Deliberately simpler than the user app's AppShell + TopBar (finora/frontend/src/App.tsx,
 * TopBar.tsx) -- no notification bell. This app now has a header search box (GlobalSearch, Admin
 * Portal Phase 2) fanning out across Users/Merchants/Banks/Global Rules server-side
 * (AdminSearchController) plus a theme toggle (ThemeContext, local-only -- no dashboard-query
 * dependencies this app's dependency footprint has deliberately kept separate from the user app's).
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
            <ThemeToggle />
            <span className="text-xs text-muted bg-card border border-border rounded-full px-3 py-1.5 shadow-card whitespace-nowrap">
              {email}
            </span>
          </div>
        </div>
        {/* Inside the layout, not around it: a page that throws is contained to the content area
            while the sidebar and global search keep rendering, so an admin can navigate away
            instead of being stranded on a blank screen. See App.tsx for the outer boundary that
            covers the routes which don't use this layout (login, setup, reset-password). */}
        <ErrorBoundary context="admin-page">{children}</ErrorBoundary>
      </main>
    </div>
  );
}
