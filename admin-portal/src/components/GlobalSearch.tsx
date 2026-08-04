import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Search, User, Store, Landmark, ListFilter, X } from 'lucide-react';
import { adminSearchApi } from '../api/endpoints';
import { useAdminAuth } from '../context/AdminAuthContext';
import type { SearchResultDto } from '../types';

const TYPE_META: Record<string, { label: string; icon: typeof User }> = {
  user: { label: 'Users', icon: User },
  merchant: { label: 'Merchants', icon: Store },
  bank: { label: 'Banks', icon: Landmark },
  rule: { label: 'Global Rules', icon: ListFilter },
};

/** Debounces keystrokes before firing the query -- short enough to feel live, long enough that
 *  typing a whole word doesn't fire one request per character. */
function useDebouncedValue(value: string, delayMs: number) {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const handle = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(handle);
  }, [value, delayMs]);
  return debounced;
}

/**
 * Header search box for the admin portal (Admin Portal Phase 2) -- one query fanned out
 * server-side across Users/Merchants/Banks/Global Rules (AdminSearchController).
 *
 * Bug fix: this used to have no permission gating beyond being signed in, on the theory that the
 * backend endpoint didn't gate it either. That's no longer true -- AdminSearchController.search()
 * now requires USER_VIEW (see that controller's own doc comment: it used to have no @PreAuthorize
 * at all, a real PII leak, fixed by gating it on the same permission the Users page already
 * requires). This component never picked up that change, so an admin without USER_VIEW (a
 * narrowly-scoped support role with only, say, AUDIT_VIEW) still got a fully-rendered search box
 * that 403'd on every query -- silently swallowed here, since neither `data` nor `isFetching`
 * distinguish "no matches" from "not allowed to search," so it read as "the platform has no
 * matching data" instead of "you can't search." Hidden entirely for such an account instead,
 * matching Sidebar.tsx's own pattern of not showing a control that can't do anything.
 */
export function GlobalSearch() {
  const { hasPermission } = useAdminAuth();
  const canSearch = hasPermission('USER_VIEW');
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const debouncedQuery = useDebouncedValue(query.trim(), 300);
  const navigate = useNavigate();
  const containerRef = useRef<HTMLDivElement>(null);

  const { data, isFetching } = useQuery({
    queryKey: ['admin-search', debouncedQuery],
    queryFn: () => adminSearchApi.search(debouncedQuery),
    enabled: canSearch && debouncedQuery.length >= 2,
  });

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  function handleSelect(result: SearchResultDto) {
    setOpen(false);
    setQuery('');
    void navigate(result.link);
  }

  function clear() {
    setQuery('');
    setOpen(false);
  }

  // After every hook above (rules-of-hooks requires the same hooks to run every render) -- an
  // account without USER_VIEW gets no search box at all, rather than one that renders and 403s.
  if (!canSearch) return null;

  const results = data ?? [];
  const grouped = results.reduce<Record<string, SearchResultDto[]>>((acc, r) => {
    if (!acc[r.type]) acc[r.type] = [];
    acc[r.type].push(r);
    return acc;
  }, {});
  const showDropdown = open && debouncedQuery.length >= 2;

  return (
    <div ref={containerRef} className="relative w-80">
      <div className="relative">
        <Search size={15} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
        <input
          type="text"
          value={query}
          onChange={(e) => { setQuery(e.target.value); setOpen(true); }}
          onFocus={() => setOpen(true)}
          placeholder="Search users, merchants, banks, rules…"
          className="w-full bg-card border border-border rounded-full pl-9 pr-8 py-1.5 text-sm text-ink placeholder:text-muted focus:outline-none focus:ring-2 focus:ring-primary/30"
        />
        {query && (
          <button
            type="button"
            onClick={clear}
            aria-label="Clear search"
            className="absolute right-2.5 top-1/2 -translate-y-1/2 text-muted hover:text-ink"
          >
            <X size={14} />
          </button>
        )}
      </div>

      {showDropdown && (
        <div className="absolute right-0 mt-2 w-96 bg-card border border-border rounded-xl2 shadow-card max-h-96 overflow-y-auto z-50">
          {isFetching && <p className="text-sm text-muted px-4 py-3">Searching…</p>}
          {!isFetching && results.length === 0 && (
            <p className="text-sm text-muted px-4 py-3">No matches for "{debouncedQuery}".</p>
          )}
          {!isFetching && Object.entries(grouped).map(([type, items]) => {
            const meta = TYPE_META[type] ?? { label: type, icon: Search };
            const Icon = meta.icon;
            return (
              <div key={type} className="py-1.5">
                <p className="px-4 py-1 text-[10px] font-bold uppercase tracking-wide text-muted">{meta.label}</p>
                {items.map((item) => (
                  <button
                    key={`${item.type}-${item.id}`}
                    type="button"
                    onClick={() => handleSelect(item)}
                    className="w-full flex items-center gap-3 px-4 py-2 text-left hover:bg-bg"
                  >
                    <Icon size={14} className="text-muted flex-shrink-0" />
                    <div className="min-w-0 flex-1">
                      <p className="text-sm font-medium text-ink truncate">{item.title}</p>
                      <p className="text-xs text-muted truncate">{item.subtitle}</p>
                    </div>
                  </button>
                ))}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
