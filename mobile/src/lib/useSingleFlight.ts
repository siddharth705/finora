import { useCallback, useRef } from 'react';

/**
 * Runs an async action, and ignores further calls until it settles.
 *
 * Every write on these screens already sets a `saving` flag that disables its button -- but that
 * flag is STATE, and state only reaches the button on the next render. Two taps dispatched in the
 * same frame both read `saving === false` and both fire. On a savings goal that is a contribution
 * recorded twice; on a holding, a duplicate account. Neither is recoverable in-app.
 *
 * A ref closes that window, because it updates synchronously -- the second call sees the flag the
 * first one just set, in the same tick. The `saving` state stays: it is what the user SEES (a
 * disabled, "Saving…" button). This is what actually enforces it.
 *
 * Returns whatever the action returned, or undefined when the call was dropped.
 */
export function useSingleFlight() {
  const inFlight = useRef(false);

  return useCallback(async <T>(action: () => Promise<T>): Promise<T | undefined> => {
    if (inFlight.current) return undefined;
    inFlight.current = true;
    try {
      return await action();
    } finally {
      inFlight.current = false;
    }
  }, []);
}

/**
 * The same guard as {@link useSingleFlight}, scoped per key instead of to the whole hook instance.
 *
 * A list screen has one button per row, not one button per screen -- a plain `useSingleFlight`
 * shared across every row would drop a tap on row B just because row A's request is still in
 * flight, which is not a double-tap and must not be treated as one. That mistake shipped once (a
 * review queue where fixing one merchant's category silently discarded a concurrent fix to a
 * different one) and was reverted; this is the correctly-shaped tool for "same item, not just same
 * screen".
 */
export function useKeyedSingleFlight() {
  const inFlight = useRef<Set<string>>(new Set());

  return useCallback(async <T>(key: string, action: () => Promise<T>): Promise<T | undefined> => {
    if (inFlight.current.has(key)) return undefined;
    inFlight.current.add(key);
    try {
      return await action();
    } finally {
      inFlight.current.delete(key);
    }
  }, []);
}
