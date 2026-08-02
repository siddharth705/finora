import { useCallback, useState } from 'react';
import { safeStorage } from '../lib/safeStorage';

export interface SavedView<T extends Record<string, string>> {
  name: string;
  values: T;
}

function readViews<T extends Record<string, string>>(storageKey: string): SavedView<T>[] {
  try {
    const raw = safeStorage.getItem(storageKey);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    // Corrupt/foreign localStorage value under this key -- treat as "no saved views" rather than
    // throwing and breaking the whole page over a client-side-only convenience feature.
    return [];
  }
}

/**
 * Admin Portal Phase 5 (Shared Filtering Framework) -- persists a named list of filter-value
 * snapshots per page in localStorage, keyed by `storageKey` (e.g. "finora-admin-views-users") so
 * different pages' saved views never collide with each other. Generic over T so each page defines
 * its own filter shape (Users: { q, status }; Activity Feed: { q, dateFrom, dateTo, sortDir }) --
 * this hook doesn't know or care what the keys mean, it only stores/retrieves/deletes named
 * snapshots of whatever flat string-keyed object the caller gives it. Pairs with FilterBar's
 * `savedViews` prop, which is the only place this hook is consumed from today (Users.tsx,
 * AuditLog.tsx) -- nothing about the hook itself is FilterBar-specific though.
 *
 * Deliberately client-side only, no backend table -- these are personal shortcuts stored in one
 * admin's own browser, not a shared team resource. A future phase could promote this to a real
 * `saved_views` table if cross-device/cross-admin sharing turns out to matter; nothing here
 * blocks that later.
 */
export function useSavedViews<T extends Record<string, string>>(storageKey: string) {
  const [views, setViews] = useState<SavedView<T>[]>(() => readViews<T>(storageKey));

  const persist = useCallback((next: SavedView<T>[]) => {
    setViews(next);
    // Bug fix: this used to call localStorage.setItem directly, unlike readViews() above --
    // in a storage-restricted browser (private browsing with 0 quota, a policy blocking site
    // data), setItem throws INSIDE the click handler after setViews(next) already ran: the UI
    // shows the view as saved, but nothing actually persisted, and it silently disappears on the
    // next reload with no error shown. safeStorage.setItem swallows that the same way
    // readViews()'s own try/catch already does for reads.
    safeStorage.setItem(storageKey, JSON.stringify(next));
  }, [storageKey]);

  /** Overwrites any existing view with the same name -- saving under a name you've already used
   *  updates it in place rather than creating a confusing duplicate. */
  const save = useCallback((name: string, values: T) => {
    const trimmed = name.trim();
    if (!trimmed) return;
    const withoutExisting = views.filter((v) => v.name !== trimmed);
    persist([...withoutExisting, { name: trimmed, values }]);
  }, [views, persist]);

  const remove = useCallback((name: string) => {
    persist(views.filter((v) => v.name !== name));
  }, [views, persist]);

  return { views, save, remove };
}
