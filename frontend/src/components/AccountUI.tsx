import type { ReactNode } from 'react';
import { CheckCircle2 } from 'lucide-react';

// Shared between Profile.tsx and Settings.tsx -- the two pages are explicitly two halves of what
// used to be one Settings page (see docs/team-message... the Profile/Settings split), and need to
// stay visually consistent with each other, not two independent copies that drift apart.

export function initials(name: string | null | undefined): string {
  if (!name) return '?';
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? '') + (parts[1]?.[0] ?? '')).toUpperCase() || '?';
}

export function formatMonthYear(iso: string | null | undefined): string {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleDateString('en-IN', { month: 'long', year: 'numeric' });
  } catch {
    return '—';
  }
}

export function formatDayMonthYear(iso: string | null | undefined): string {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
  } catch {
    return '—';
  }
}

export function formatRelativeTime(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return null;
  const days = Math.floor((Date.now() - then) / (1000 * 60 * 60 * 24));
  if (days < 1) return 'today';
  if (days === 1) return 'yesterday';
  if (days < 30) return `${days} days ago`;
  const months = Math.floor(days / 30);
  if (months < 12) return `${months} month${months === 1 ? '' : 's'} ago`;
  const years = Math.floor(months / 12);
  return `${years} year${years === 1 ? '' : 's'} ago`;
}

export function SectionCard({ icon, title, subtitle, children }: { icon: ReactNode; title: string; subtitle: string; children: ReactNode }) {
  return (
    <section className="bg-card rounded-xl2 p-6 shadow-card border border-border">
      <div className="flex items-start gap-3 mb-5">
        <div className="w-9 h-9 rounded-lg bg-primary/10 text-primary flex items-center justify-center flex-shrink-0">{icon}</div>
        <div>
          <h2 className="font-serif text-lg font-semibold text-ink">{title}</h2>
          <p className="text-sm text-muted">{subtitle}</p>
        </div>
      </div>
      {children}
    </section>
  );
}

export function VerifiedBadge() {
  return (
    <span className="inline-flex items-center gap-1 text-xs font-medium text-success bg-success-bg rounded-full px-2 py-0.5 flex-shrink-0">
      <CheckCircle2 size={12} /> Verified
    </span>
  );
}

/** Per-section save state: a section is either clean (nothing to show), dirty (unsaved edits),
 *  mid-save, freshly saved (a brief confirmation), or errored -- one indicator, used identically
 *  across both pages so "did my change stick" always looks and behaves the same way. */
export function SaveStatus({ dirty, saving, justSaved, error }: { dirty: boolean; saving: boolean; justSaved: boolean; error: boolean }) {
  if (error) return <span className="text-danger text-xs">Couldn't save — please try again.</span>;
  if (saving) return <span className="text-muted text-xs">Saving…</span>;
  if (justSaved) return (
    <span className="text-success text-xs inline-flex items-center gap-1"><CheckCircle2 size={12} /> Saved</span>
  );
  if (dirty) return <span className="text-warning text-xs">Unsaved changes</span>;
  return null;
}

export function MetricTile({ label, value }: { label: string; value: string }) {
  return (
    <div className="bg-bg rounded-lg border border-border px-4 py-3">
      <p className="text-xs uppercase text-muted mb-1">{label}</p>
      <p className="text-lg font-semibold text-ink">{value}</p>
    </div>
  );
}
