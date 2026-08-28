import { useEffect, useMemo, useRef, useState } from 'react';
import { categoriesApi, type CategoryOption } from '../api/endpoints';
import { similarityRatio } from '../lib/similarity';

const FUZZY_THRESHOLD = 0.6;
const FUZZY_MAX_SUGGESTIONS = 3;

interface CategoryComboboxProps {
  value: string;
  onChange: (categoryName: string) => void;
  onCreateNew?: (typedText: string) => void;
  excludeCategoryId?: string;
}

export function CategoryCombobox({ value, onChange, onCreateNew, excludeCategoryId }: CategoryComboboxProps) {
  const [categories, setCategories] = useState<CategoryOption[]>([]);
  const [query, setQuery] = useState(value);
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    categoriesApi.list().then(setCategories).catch(() => setCategories([]));
  }, []);

  useEffect(() => {
    const onClickOutside = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onClickOutside);
    return () => document.removeEventListener('mousedown', onClickOutside);
  }, []);

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

  const select = (name: string) => {
    onChange(name);
    setQuery(name);
    setOpen(false);
  };

  const create = () => {
    setOpen(false);
    onCreateNew?.(trimmedQuery);
  };

  return (
    <div ref={containerRef} className="relative">
      <input
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
      {open && (
        <div className="absolute z-10 mt-1 w-full bg-card border border-border rounded-lg shadow-lg max-h-64 overflow-y-auto">
          {exactMatches.map((c) => (
            <button
              key={c.id}
              type="button"
              className="w-full text-left px-3 py-2 text-sm hover:bg-sidebar-hover"
              onClick={() => select(c.name)}
            >
              {c.name}
            </button>
          ))}
          {fuzzySuggestions.length > 0 && (
            <div className="px-3 py-1 text-[11px] uppercase text-muted">
              Did you mean:
              {fuzzySuggestions.map((c) => (
                <button
                  key={c.id}
                  type="button"
                  className="ml-2 underline"
                  onClick={() => select(c.name)}
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
