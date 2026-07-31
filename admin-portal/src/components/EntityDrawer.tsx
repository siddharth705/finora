import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { X } from 'lucide-react';

export interface EntityDrawerTab {
  id: string;
  label: string;
  content: ReactNode;
}

export interface EntityDrawerProps {
  open: boolean;
  onClose: () => void;
  title: string;
  subtitle?: string;
  /** Rendered in the header, next to the close button -- e.g. an Edit/Delete button pair. Stays
   *  outside the tab content since these actions typically apply to the whole entity, not one tab. */
  headerActions?: ReactNode;
  tabs: EntityDrawerTab[];
}

/**
 * Admin Portal Phase 4 -- the reusable "List → Click → Right-side Detail Drawer" pattern, applied
 * first on the Banks page (BanksContent in Banks.tsx) as the reference implementation, replacing
 * that page's old inline edit form. Fully prop-driven: this component knows nothing about banks,
 * users, merchants, or any other entity -- it only renders whatever `tabs` it's given inside a
 * slide-in panel with a title/subtitle header and an internally-managed active-tab selector.
 *
 * HOW TO ADOPT THIS ON ANOTHER ENTITY (Users, Roles, Merchants, ...):
 *   1. Keep the page's existing list/table as-is (DataTable, Users.tsx, etc.) -- this drawer
 *      replaces the "click a row → navigate to a new page or open an inline form" step, not the
 *      list itself.
 *   2. Track which row is "open" with one piece of state in the page, e.g.
 *      `const [selected, setSelected] = useState<UserSummaryDto | null>(null)`. Open the drawer
 *      by setting it from a row click; `open={selected !== null}`, `onClose={() => setSelected(null)}`.
 *   3. Build a `tabs` array with whatever tabs make sense for that entity. The three this codebase
 *      converges on so far: "Summary" (the editable business fields, with an inline edit-mode
 *      toggle -- see BankSummaryTab below for the reference shape), "Metadata" (system fields:
 *      timestamps, immutable ids, raw/less-commonly-needed detail), and "Audit" (that entity's
 *      real audit trail, if one exists -- see AdminBankController.audit's doc comment for the
 *      honest fallback when an entity's audit events don't carry a queryable entity id yet: an
 *      empty-state message, never a fabricated one). Not every entity needs all three -- a tab
 *      array with just one entry (e.g. "Summary" only) is a perfectly valid use of this component.
 *   4. Each tab's `content` is plain ReactNode -- reuse whatever data-fetching hooks the page
 *      already has (useQuery keyed on the selected row's id), same as any other component.
 *
 * Deliberately unmounts (returns null) when closed rather than staying hidden in the DOM -- no
 * stale content to accidentally query in tests, and no drawer-specific state (like which tab was
 * open) leaking into the next entity opened.
 */
export function EntityDrawer({ open, onClose, title, subtitle, headerActions, tabs }: EntityDrawerProps) {
  const [activeTabId, setActiveTabId] = useState(tabs[0]?.id ?? '');
  const [entered, setEntered] = useState(false);

  // Resets to the first tab and re-triggers the slide-in transition every time a (possibly
  // different) entity is opened -- keyed on `open`/`title` rather than just `open` so opening a
  // second entity while the drawer is already open (e.g. clicking a different row without
  // closing first) still resets which tab is showing.
  useEffect(() => {
    if (!open) {
      setEntered(false);
      return;
    }
    setActiveTabId(tabs[0]?.id ?? '');
    const raf = requestAnimationFrame(() => setEntered(true));
    return () => cancelAnimationFrame(raf);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, title]);

  useEffect(() => {
    if (!open) return;
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
    }
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [open, onClose]);

  if (!open) return null;

  const activeTab = tabs.find((t) => t.id === activeTabId) ?? tabs[0];

  return (
    <div className="fixed inset-0 z-50 flex justify-end" role="dialog" aria-modal="true" aria-label={title}>
      <div
        className={`absolute inset-0 bg-black/30 transition-opacity duration-200 ${entered ? 'opacity-100' : 'opacity-0'}`}
        onClick={onClose}
      />
      <div
        className={`relative w-full max-w-md h-full bg-card border-l border-border shadow-soft flex flex-col transition-transform duration-200 ${
          entered ? 'translate-x-0' : 'translate-x-full'
        }`}
      >
        <div className="px-5 py-4 border-b border-border flex items-start justify-between gap-3 flex-shrink-0">
          <div className="min-w-0">
            <h3 className="font-semibold text-ink truncate">{title}</h3>
            {subtitle && <p className="text-xs text-muted mt-0.5 truncate">{subtitle}</p>}
          </div>
          <div className="flex items-center gap-2 flex-shrink-0">
            {headerActions}
            <button type="button" onClick={onClose} aria-label="Close" className="text-muted hover:text-ink p-1">
              <X size={18} />
            </button>
          </div>
        </div>

        {tabs.length > 1 && (
          <div className="flex border-b border-border flex-shrink-0 px-5">
            {tabs.map((tab) => (
              <button
                key={tab.id}
                type="button"
                onClick={() => setActiveTabId(tab.id)}
                className={`px-3 py-2.5 text-sm font-medium border-b-2 -mb-px transition-colors ${
                  tab.id === activeTabId ? 'border-primary text-primary' : 'border-transparent text-muted hover:text-ink'
                }`}
              >
                {tab.label}
              </button>
            ))}
          </div>
        )}

        <div className="flex-1 overflow-y-auto p-5">
          {activeTab?.content}
        </div>
      </div>
    </div>
  );
}
