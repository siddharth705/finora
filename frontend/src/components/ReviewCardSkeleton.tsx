/**
 * Shared loading placeholder for AskOnceCard and MerchantGroupReviewCard. Both used to render
 * nothing at all while their first fetch was in flight (`if (loading) return null`), so the card
 * simply popped into existence once the data arrived -- a visible jump on the Ledger page. Sized to
 * match each real row's actual layout (title + subtitle line, a category-field-shaped block, a
 * button-shaped block) rather than a generic bar, so replacing the skeleton with real content
 * doesn't itself cause a second layout shift.
 */
export function ReviewCardSkeleton({ rows = 2 }: { rows?: number }) {
  return (
    <div className="space-y-3" aria-hidden="true">
      {Array.from({ length: rows }).map((_, i) => (
        <div key={i} className="flex gap-3 items-center animate-pulse">
          <div className="min-w-0 flex-1 space-y-1.5">
            <div className="h-3.5 w-3/5 rounded bg-surface" />
            <div className="h-2.5 w-1/4 rounded bg-surface" />
          </div>
          <div className="flex-shrink-0 w-40 h-9 rounded-lg bg-surface" />
          <div className="flex-shrink-0 w-24 h-7 rounded-lg bg-surface" />
        </div>
      ))}
    </div>
  );
}
