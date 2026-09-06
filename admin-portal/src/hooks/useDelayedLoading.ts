import { useEffect, useRef, useState } from 'react';

interface UseDelayedLoadingOptions {
  /** How long a fetch must stay in flight before a skeleton appears. A fetch that resolves inside
   *  this window shows nothing instead of flashing a skeleton in and out. */
  showAfter?: number;
  /** Once a skeleton has appeared, the minimum time it stays visible -- so a fetch that finishes
   *  just after `showAfter` doesn't produce its own flicker in the other direction. */
  minVisible?: number;
}

const DEFAULT_SHOW_AFTER = 200;
const DEFAULT_MIN_VISIBLE = 300;

/**
 * Turns a raw `isLoading` flag into "should a skeleton actually be on screen right now." See
 * `frontend/src/hooks/useDelayedLoading.ts` for the identical implementation and the roadmap's
 * anti-drift rule, and docs/proposals/animation-polish-roadmap-proposal.md §1 for the reasoning.
 *
 * Pass a query's `isLoading` (first fetch, no data yet) -- never `isFetching`/`isRefetching`
 * (data already present, quietly updating). A background refetch keeps showing its stale content
 * with a small spinner instead.
 */
export function useDelayedLoading(
  isLoading: boolean,
  { showAfter = DEFAULT_SHOW_AFTER, minVisible = DEFAULT_MIN_VISIBLE }: UseDelayedLoadingOptions = {}
): boolean {
  const [visible, setVisible] = useState(false);
  const shownAtRef = useRef<number | null>(null);
  const isLoadingRef = useRef(isLoading);
  isLoadingRef.current = isLoading;

  useEffect(() => {
    if (isLoading) {
      const showTimer = setTimeout(() => {
        // Bug fix: a brief true -> false -> true flicker (the hide timer never got to fire, so
        // the skeleton was never actually taken off screen) re-enters this branch and schedules a
        // fresh showTimer. Unconditionally stamping shownAtRef here would reset minVisible's clock
        // on every such flicker, letting the skeleton overstay by however long the flicker lasted.
        // Only stamp it if this is a genuine fresh show (shownAtRef cleared by the hide timer
        // actually firing) -- a flicker just reconfirms visibility against the original timestamp.
        if (shownAtRef.current == null) {
          shownAtRef.current = Date.now();
        }
        setVisible(true);
      }, showAfter);
      return () => clearTimeout(showTimer);
    }

    if (shownAtRef.current == null) {
      setVisible(false);
      return undefined;
    }

    const elapsed = Date.now() - shownAtRef.current;
    const remaining = Math.max(0, minVisible - elapsed);
    const hideTimer = setTimeout(() => {
      if (!isLoadingRef.current) {
        setVisible(false);
        shownAtRef.current = null;
      }
    }, remaining);
    return () => clearTimeout(hideTimer);
  }, [isLoading, showAfter, minVisible]);

  return visible;
}
