import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Pencil, Trash2 } from 'lucide-react';
import { categoriesApi, type CategoryOption } from '../api/endpoints';
import { similarityRatio } from '../lib/similarity';
import { CategoryCreateEditPanel } from './CategoryCreateEditPanel';
import { CategoryDeleteDialog } from './CategoryDeleteDialog';

const FUZZY_THRESHOLD = 0.6;
const FUZZY_MAX_SUGGESTIONS = 3;

interface CategoryComboboxProps {
  value: string;
  onChange: (categoryName: string) => void;
  /**
   * Optional. When provided, "+ Create" hands the typed text back to the parent, which renders
   * the create panel where it wants it (Ledger's edit modal and the two review cards all swap the
   * whole field for the panel). When omitted, the combobox opens the panel itself — that fallback
   * is what makes the reassignment picker inside CategoryDeleteDialog able to create a target,
   * instead of its "+ Create" row doing nothing at all.
   */
  onCreateNew?: (typedText: string) => void;
  /**
   * Optional. Fires alongside onChange whenever a selection resolves to a concrete category --
   * an existing row picked from the list, a fuzzy "did you mean" suggestion, or (notably) a
   * brand-new category just created through this combobox's own inline create panel. onChange
   * only ever carries a name, which is not enough to identify the row unambiguously (a stale or
   * not-yet-refetched ['categories'] cache can't be re-searched by name for a category that was
   * just created in this same interaction). Consumers that need the id -- CategoryDeleteDialog's
   * reassignment-target picker chiefly -- should use this instead of re-deriving it themselves.
   */
  onSelect?: (category: CategoryOption) => void;
  excludeCategoryId?: string;
  /**
   * Associates an external `<label htmlFor>` with the real input. Ledger's edit modal has carried
   * `<label htmlFor="edit-txn-category">` since long before this component existed; when the
   * `<select>` that owned that id was replaced, the label stopped pointing at any control at all.
   */
  inputId?: string;
}

type Panel =
  | { kind: 'create'; name: string }
  | { kind: 'edit'; category: CategoryOption }
  | { kind: 'delete'; category: CategoryOption };

export function CategoryCombobox({
  value, onChange, onCreateNew, onSelect, excludeCategoryId, inputId,
}: CategoryComboboxProps) {
  // Shared cache key, deliberately: AskOnceCard renders one of these per row (up to ten) and
  // MerchantGroupReviewCard one per merchant group. With a local useState fetch that was N
  // identical uncached GET /categories calls per page, and — worse — N independent snapshots, so
  // a category created in one row's combobox was invisible to the next one, which then offered
  // "+ Create" for a name that already existed and 409ed. One query key fixes both halves.
  const categoriesQ = useQuery({ queryKey: ['categories'], queryFn: () => categoriesApi.list(), retry: false });
  const categories = useMemo(() => categoriesQ.data ?? [], [categoriesQ.data]);

  const [query, setQuery] = useState(value);
  const [open, setOpen] = useState(false);
  const [panel, setPanel] = useState<Panel | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    setQuery(value);
  }, [value]);

  useEffect(() => {
    const onClickOutside = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false);
        // Bug fix: this used to close the dropdown and leave whatever the user had typed sitting
        // in the input, even though nothing was selected and onChange never fired. Inside a form
        // — Ledger's edit modal — the user saw "Fuel" in the category field and saved, believing
        // that is what they saved; the old category went in instead. Typed-but-unselected text is
        // not a selection, so the field goes back to showing the actual value.
        setQuery(value);
      }
    };
    document.addEventListener('mousedown', onClickOutside);
    return () => document.removeEventListener('mousedown', onClickOutside);
  }, [value]);

  const pool = useMemo(
    () => categories.filter((c) => c.id !== excludeCategoryId),
    [categories, excludeCategoryId],
  );

  const trimmedQuery = query.trim();
  const exactMatches = useMemo(
    () => pool.filter((c) => c.name.toLowerCase().includes(trimmedQuery.toLowerCase())),
    [pool, trimmedQuery],
  );
  const exactNameMatch = pool.some((c) => c.name.toLowerCase() === trimmedQuery.toLowerCase());

  const fuzzySuggestions = useMemo(() => {
    if (!trimmedQuery || exactMatches.length > 0) return [];
    return pool
      .map((c) => ({ category: c, score: similarityRatio(c.name, trimmedQuery) }))
      .filter((s) => s.score >= FUZZY_THRESHOLD)
      .sort((a, b) => b.score - a.score)
      .slice(0, FUZZY_MAX_SUGGESTIONS)
      .map((s) => s.category);
  }, [pool, trimmedQuery, exactMatches.length]);

  const showCreateRow = trimmedQuery.length > 0 && !exactNameMatch;

  const select = (category: CategoryOption) => {
    onChange(category.name);
    onSelect?.(category);
    setQuery(category.name);
    setOpen(false);
  };

  const create = () => {
    setOpen(false);
    if (onCreateNew) onCreateNew(trimmedQuery);
    else setPanel({ kind: 'create', name: trimmedQuery });
  };

  const openPanel = (next: Panel) => {
    setOpen(false);
    setPanel(next);
  };

  if (panel?.kind === 'create' || panel?.kind === 'edit') {
    const isEdit = panel.kind === 'edit';
    return (
      <CategoryCreateEditPanel
        mode={isEdit ? 'edit' : 'create'}
        categoryId={isEdit ? panel.category.id : undefined}
        initialName={isEdit ? panel.category.name : panel.name}
        initialIcon={isEdit ? panel.category.icon : undefined}
        initialColor={isEdit ? panel.category.color : undefined}
        onSaved={(saved) => {
          // A rename has to follow through to the field's own value, or the parent keeps holding
          // a category name that no longer exists.
          if (!isEdit || panel.category.name === value) select(saved);
          setPanel(null);
        }}
        onCancel={() => setPanel(null)}
      />
    );
  }

  if (panel?.kind === 'delete') {
    return (
      <CategoryDeleteDialog
        category={panel.category}
        onDeleted={() => {
          if (panel.category.name === value) onChange('');
          setPanel(null);
        }}
        onCancel={() => setPanel(null)}
      />
    );
  }

  return (
    <div ref={containerRef} className="relative">
      <input
        id={inputId}
        role="combobox"
        aria-expanded={open}
        className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full"
        value={query}
        onFocus={() => setOpen(true)}
        onChange={(e) => {
          setQuery(e.target.value);
          setOpen(true);
        }}
      />
      {/* A failed fetch used to be indistinguishable from "you have no categories" — an empty
          dropdown either way — which invites the user to create categories they already have.
          Lives here rather than in each of the three consumers so all of them get it. */}
      {categoriesQ.isError && (
        <p className="text-[11px] text-warning mt-1">
          Couldn't load your categories — please try again.
        </p>
      )}
      {open && (
        <div className="absolute z-10 mt-1 w-full bg-card border border-border rounded-lg shadow-lg max-h-64 overflow-y-auto">
          {exactMatches.map((c) => (
            <div key={c.id} className="group flex items-center hover:bg-sidebar-hover">
              <button
                type="button"
                className="flex-1 min-w-0 text-left px-3 py-2 text-sm truncate"
                onClick={() => select(c)}
              >
                {c.name}
              </button>
              {/* System categories are immutable by design (see the design spec's system-vs-user
                  split), so they get neither control. opacity-0 + group-hover keeps the row clean
                  until pointed at; focus-within keeps them reachable by keyboard. */}
              {!c.isSystem && (
                <span className="flex items-center gap-1 pr-2 opacity-0 group-hover:opacity-100 focus-within:opacity-100">
                  <button
                    type="button"
                    aria-label={`Edit ${c.name}`}
                    className="p-1 rounded text-muted hover:text-ink"
                    onClick={() => openPanel({ kind: 'edit', category: c })}
                  >
                    <Pencil size={13} />
                  </button>
                  <button
                    type="button"
                    aria-label={`Delete ${c.name}`}
                    className="p-1 rounded text-muted hover:text-danger"
                    onClick={() => openPanel({ kind: 'delete', category: c })}
                  >
                    <Trash2 size={13} />
                  </button>
                </span>
              )}
            </div>
          ))}
          {fuzzySuggestions.length > 0 && (
            <div className="px-3 py-1 text-[11px] uppercase text-muted">
              Did you mean:
              {fuzzySuggestions.map((c) => (
                <button
                  key={c.id}
                  type="button"
                  className="ml-2 underline"
                  onClick={() => select(c)}
                >
                  {c.name}
                </button>
              ))}
            </div>
          )}
          {showCreateRow && (
            <button
              type="button"
              className="w-full text-left px-3 py-2 text-sm font-medium text-primary hover:bg-sidebar-hover border-t border-border"
              onClick={create}
            >
              Create "{trimmedQuery}"
            </button>
          )}
        </div>
      )}
    </div>
  );
}
