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
 * Turns a raw `isLoading` flag into "should a skeleton actually be on screen right now," with the
 * two timing rules a skeleton needs to avoid feeling worse than no loading indicator at all: don't
 * show one for a fetch that's about to finish anyway, and don't yank one away the instant it
 * appears. See docs/proposals/animation-polish-roadmap-proposal.md §1.
 *
 * Pass a query's `isLoading` (first fetch, no data yet) here -- never `isFetching`/`isRefetching`
 * (data already present, quietly updating in the background). A background refetch keeps showing
 * its stale content with a small spinner instead (the UX convention table in that same doc);
 * feeding this hook `isFetching` would replace visible content with a skeleton on every refresh,
 * which is the opposite of what a refetch should do.
 */
export function useDelayedLoading(
  isLoading: boolean,
  { showAfter = DEFAULT_SHOW_AFTER, minVisible = DEFAULT_MIN_VISIBLE }: UseDelayedLoadingOptions = {}
): boolean {
  const [visible, setVisible] = useState(false);
  const shownAtRef = useRef<number | null>(null);
  // Read inside the pending hide-timer callback below, not the effect that scheduled it -- closing
  // over `isLoading` directly would use the value from the render that scheduled the timer, not
  // whatever it is by the time the timer actually fires.
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
      // Never actually shown (resolved inside the showAfter window) -- nothing to hide.
      setVisible(false);
      return undefined;
    }

    const elapsed = Date.now() - shownAtRef.current;
    const remaining = Math.max(0, minVisible - elapsed);
    const hideTimer = setTimeout(() => {
      // Guards a fast loading -> done -> loading-again sequence: don't hide on a stale timer if
      // a new load has started since this one was scheduled.
      if (!isLoadingRef.current) {
        setVisible(false);
        shownAtRef.current = null;
      }
    }, remaining);
    return () => clearTimeout(hideTimer);
  }, [isLoading, showAfter, minVisible]);

  return visible;
}
