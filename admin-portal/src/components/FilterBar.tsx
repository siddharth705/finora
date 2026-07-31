import { useState } from 'react';
import type { ReactNode } from 'react';
import { Search, ChevronDown, Bookmark, X } from 'lucide-react';
import type { SavedView } from '../hooks/useSavedViews';

export type FilterField =
  | { type: 'search'; key: string; value: string; onChange: (v: string) => void; placeholder?: string }
  | { type: 'select'; key: string; value: string; onChange: (v: string) => void; options: { label: string; value: string }[]; placeholder?: string }
  | { type: 'date'; key: string; value: string; onChange: (v: string) => void; label: string };

export interface FilterBarSavedViewsProps<T extends Record<string, string>> {
  views: SavedView<T>[];
  /** The current, live filter values -- what gets written under a new name on "Save current". */
  currentValues: T;
  onApply: (values: T) => void;
  onSave: (name: string, values: T) => void;
  onDelete: (name: string) => void;
}

export interface FilterBarProps<T extends Record<string, string>> {
  fields: FilterField[];
  /** Called when the user presses Enter in a search field or clicks the Apply/Search button --
   *  omit for filters (like a plain select) that should apply immediately on change instead. */
  onApply?: () => void;
  applyLabel?: string;
  /** Extra controls rendered at the end of the bar (e.g. a page's own "New" button) -- kept
   *  separate from `fields` since these aren't filters, just adjacent page actions. */
  trailingActions?: ReactNode;
  savedViews?: FilterBarSavedViewsProps<T>;
}

/**
 * Admin Portal Phase 5 -- one reusable filter row (search / select / date range) plus an optional
 * saved-views dropdown backed by useSavedViews, applied first on Users.tsx (search + status) and
 * AuditLog.tsx (search + date range + sort) as this pattern's reference implementations. Every
 * field is fully controlled by the caller (value + onChange) -- this component holds no filter
 * state of its own, only the saved-views dropdown's own open/closed and "naming a new view" UI
 * state. A future page adopting this needs only: wire its own useState/useQuery filter values
 * into a `fields` array in the same shape as Users.tsx/AuditLog.tsx do, no changes here.
 */
export function FilterBar<T extends Record<string, string>>({
  fields, onApply, applyLabel = 'Search', trailingActions, savedViews,
}: FilterBarProps<T>) {
  const [viewsOpen, setViewsOpen] = useState(false);
  const [namingView, setNamingView] = useState(false);
  const [newViewName, setNewViewName] = useState('');

  function handleSaveCurrent() {
    if (!savedViews) return;
    const trimmed = newViewName.trim();
    if (!trimmed) return;
    savedViews.onSave(trimmed, savedViews.currentValues);
    setNewViewName('');
    setNamingView(false);
  }

  return (
    <div className="flex flex-wrap items-center gap-3 mb-5">
      {fields.map((field) => {
        if (field.type === 'search') {
          return (
            <div key={field.key} className="relative flex-1 min-w-[200px] max-w-sm">
              <Search size={16} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-muted" />
              <input
                placeholder={field.placeholder}
                value={field.value}
                onChange={(e) => field.onChange(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && onApply?.()}
                className="w-full bg-card border border-border rounded-lg pl-10 pr-3 py-2.5 text-sm shadow-card focus:outline-none focus:ring-2 focus:ring-primary/30"
              />
            </div>
          );
        }
        if (field.type === 'select') {
          return (
            <select
              key={field.key}
              value={field.value}
              onChange={(e) => field.onChange(e.target.value)}
              className="bg-card border border-border rounded-lg px-3 py-2.5 text-sm shadow-card"
            >
              {field.placeholder && <option value="">{field.placeholder}</option>}
              {field.options.map((opt) => <option key={opt.value} value={opt.value}>{opt.label}</option>)}
            </select>
          );
        }
        return (
          <div key={field.key} className="flex items-center gap-1.5">
            <label htmlFor={`filterbar-${field.key}`} className="text-xs font-medium text-muted whitespace-nowrap">
              {field.label}
            </label>
            <input
              id={`filterbar-${field.key}`}
              type="date"
              value={field.value}
              onChange={(e) => field.onChange(e.target.value)}
              className="bg-card border border-border rounded-lg px-2.5 py-2 text-sm shadow-card"
            />
          </div>
        );
      })}

      {onApply && (
        <button
          type="button"
          onClick={onApply}
          className="bg-primary hover:bg-primary-dark text-white text-sm font-semibold rounded-lg px-4 py-2.5"
        >
          {applyLabel}
        </button>
      )}

      {savedViews && (
        <div className="relative">
          <button
            type="button"
            onClick={() => setViewsOpen((o) => !o)}
            className="inline-flex items-center gap-1.5 bg-card border border-border rounded-lg px-3.5 py-2.5 text-sm font-medium text-ink shadow-card"
          >
            <Bookmark size={14} /> Views
            {savedViews.views.length > 0 && (
              <span className="text-xs text-muted">({savedViews.views.length})</span>
            )}
            <ChevronDown size={14} className="text-muted" />
          </button>
          {viewsOpen && (
            <div className="absolute right-0 mt-2 w-64 bg-card border border-border rounded-xl2 shadow-card z-50 py-1.5">
              {savedViews.views.length === 0 && (
                <p className="text-xs text-muted px-3.5 py-2">No saved views yet.</p>
              )}
              {savedViews.views.map((view) => (
                <div key={view.name} className="flex items-center justify-between px-3.5 py-1.5 hover:bg-bg">
                  <button
                    type="button"
                    onClick={() => {
                      savedViews.onApply(view.values);
                      setViewsOpen(false);
                    }}
                    className="text-sm text-ink text-left flex-1 truncate"
                  >
                    {view.name}
                  </button>
                  <button
                    type="button"
                    aria-label={`Delete view ${view.name}`}
                    onClick={() => savedViews.onDelete(view.name)}
                    className="text-muted hover:text-danger flex-shrink-0 ml-2"
                  >
                    <X size={13} />
                  </button>
                </div>
              ))}
              <div className="border-t border-border mt-1.5 pt-1.5 px-3.5">
                {namingView ? (
                  <div className="flex items-center gap-1.5">
                    <input
                      autoFocus
                      placeholder="View name"
                      value={newViewName}
                      onChange={(e) => setNewViewName(e.target.value)}
                      onKeyDown={(e) => e.key === 'Enter' && handleSaveCurrent()}
                      className="flex-1 bg-bg border border-border rounded-lg px-2 py-1.5 text-sm"
                    />
                    <button type="button" onClick={handleSaveCurrent} className="text-xs font-semibold text-primary flex-shrink-0">
                      Save
                    </button>
                  </div>
                ) : (
                  <button
                    type="button"
                    onClick={() => setNamingView(true)}
                    className="text-xs font-semibold text-primary py-1"
                  >
                    + Save current filters
                  </button>
                )}
              </div>
            </div>
          )}
        </div>
      )}

      {trailingActions && <div className="ml-auto flex-shrink-0">{trailingActions}</div>}
    </div>
  );
}
