import { useEffect, useState } from 'react';

/**
 * Ported from the hook defined inline in frontend/src/pages/Ledger.tsx. The debounced value
 * becomes part of the TanStack Query key, so typing fires one query once input settles rather
 * than one per keystroke, and each distinct filter combination stays cached.
 *
 * The web version's first draft used useState's lazy initializer instead of useEffect to schedule
 * the timer -- that only runs once on mount, so it never actually debounced anything after the
 * first render. Keeping the useEffect form here deliberately.
 */
export function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}
