import { useCallback, useRef } from 'react';

/**
 * Reusable guard against the same SPA race this codebase has already hit twice, hand-rolled two
 * different ways: Reports.tsx (an effect keyed on `month`) used a `cancelled` boolean closed over
 * in a cleanup function; Merchants.tsx (an imperative click handler, not an effect -- opening the
 * audit drawer for merchant A then B before A's slower response arrives) used a ref holding the
 * "current" merchant id. Both are the same problem: a newer async request must win over a slower,
 * older one that resolves after it. This hook is the one place that logic lives now, usable from
 * either an effect or a plain event handler.
 *
 * Usage:
 * ```tsx
 * const { beginRequest } = useAsyncGuard();
 *
 * // Inside a useEffect (Reports.tsx-shaped):
 * useEffect(() => {
 *   const isCurrent = beginRequest();
 *   api.forMonth(month).then((r) => { if (isCurrent()) setReport(r); });
 * }, [month]);
 *
 * // Inside an imperative handler (Merchants.tsx-shaped):
 * function openAudit(m: Merchant) {
 *   const isCurrent = beginRequest();
 *   api.audit(m.id).then((entries) => { if (isCurrent()) setEntries(entries); });
 * }
 * ```
 *
 * `beginRequest()` bumps a ref-held counter and hands back a closure over the value it bumped to
 * -- calling that closure later answers "was I still the most recent request when this ran?"
 * without needing a dependency array, an AbortController, or per-page bookkeeping. A ref (not
 * state) is deliberate: bumping it must never trigger a re-render on its own, and its only
 * consumer is the stale check itself, some time after the render that called `beginRequest()`.
 */
export function useAsyncGuard() {
  const latestToken = useRef(0);

  const beginRequest = useCallback((): (() => boolean) => {
    const myToken = ++latestToken.current;
    return () => latestToken.current === myToken;
  }, []);

  return { beginRequest };
}
