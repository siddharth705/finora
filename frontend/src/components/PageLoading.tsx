/** Deliberately not a spinner. A route chunk (or, for ImportDetail.tsx's use, an initial data
 *  fetch) is usually done in well under a frame's worth of time on a warm connection, and a
 *  spinner that flashes for 30ms reads as jank; a quiet line of text does not. It exists so the
 *  fallback is never an empty screen.
 *
 *  Lives here rather than inline in App.tsx (where it originated) so a page with its own async
 *  loading state -- ImportDetail.tsx, Premium Import Reliability v1 §3.2, is the first -- can
 *  show the identical affordance without either hand-rolling a copy that silently drifts from
 *  this one, or statically importing App.tsx itself: App.tsx lazy-loads every page including this
 *  one, so a page importing back from App.tsx would be a circular dependency between the route
 *  shell and one of the chunks it code-splits. */
export function PageLoading() {
  return <p className="text-muted text-sm p-8" role="status">Loading…</p>;
}
