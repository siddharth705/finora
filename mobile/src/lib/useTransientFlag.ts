import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * A boolean that turns itself off after a moment -- the "Saved" confirmation that appears next to
 * a form and then quietly goes away.
 *
 * Exists because the hand-rolled version leaks. `setTimeout(() => setSaved(false), 2000)` with no
 * cleanup keeps a timer alive past unmount: it fires into a tree that is gone, and it holds the JS
 * runtime awake until it does (visible as Jest's "a worker process has failed to exit gracefully").
 * That was written, found, and fixed once on the Budgets screen -- then written again the same way
 * on two more screens, which is the signal it should not be hand-rolled at all.
 *
 * Re-triggering restarts the countdown rather than stacking a second timer, so saving twice in
 * quick succession shows one confirmation that lasts the full duration from the last save.
 */
export function useTransientFlag(durationMs = 2000): [boolean, () => void] {
  const [active, setActive] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => () => {
    if (timer.current) clearTimeout(timer.current);
  }, []);

  const trigger = useCallback(() => {
    if (timer.current) clearTimeout(timer.current);
    setActive(true);
    timer.current = setTimeout(() => {
      setActive(false);
      timer.current = null;
    }, durationMs);
  }, [durationMs]);

  return [active, trigger];
}
