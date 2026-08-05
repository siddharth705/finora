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
