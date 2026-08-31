import type { NavigateFunction } from 'react-router-dom';
import type { ReimportResult } from '../types';

/**
 * The three shapes `/app/import` can be arrived at with, and the only place that constructs them.
 *
 * Lives in its own module rather than inline in Import.tsx (where it originated) for two reasons.
 * First, the same circular-import risk PageLoading was moved for: Import.tsx, StatementHistory.tsx,
 * and ImportDetail.tsx are three independently route-split chunks (see App.tsx), and the producers
 * of this state (StatementHistory.tsx, ImportDetail.tsx) are different pages than the consumer
 * (Import.tsx) -- a page statically importing another page would pull that page's whole chunk in.
 * Second, and the reason the factory functions exist at all: `location.state` is typed `any` by
 * react-router, so nothing previously stopped a producer from constructing this state by hand with
 * a mistyped or missing `kind` tag -- which wouldn't crash, just silently drop the person onto a
 * blank, contextless upload screen (strictly worse than the truthiness-cast bug this whole `kind`
 * discriminant was introduced to fix, which at least crashed loudly). These functions are the only
 * sanctioned way to navigate here with context, so getting the shape right is a compile error at
 * the call site instead of a silent runtime fallback three files away.
 */

export interface ReimportNavState {
  kind: 'reimport';
  reimportId: string;
  staging: ReimportResult['staging'];
  accountId: string;
  accountName: string;
  // Present only when the statement needed one to stage. confirmReimport() re-parses the same
  // stored bytes server-side to check the reviewed rows against, and for a protected PDF that
  // re-parse needs the password again -- see StatementImportService.confirmReimport's doc comment
  // for the incident that happens when this is dropped instead of carried through to confirm.
  password?: string;
}

/** Arrival state from the import detail page's "Review" action (Premium Import Reliability v1,
 *  §3.2) -- a completed queued job already has a staged session, the exact same one "Continue
 *  previous import" already knows how to open, so this reuses `resumeSession` rather than
 *  reimplementing it for a second arrival route. */
interface ResumeSessionNavState {
  kind: 'resume';
  resumeSessionId: string;
}

/** Arrival state from the Failed Imports section's "Try again" action (Premium Import
 *  Reliability v1, §2.5) -- unlike a reimport or a resumed session, a failed sync import has no
 *  bytes retained anywhere (that's Sprint 4's still-gated retry-without-re-upload work), so there
 *  is nothing to hydrate and no staged review to jump to. This is purely informational: the page
 *  lands on the ordinary upload step, with enough context that the person doesn't have to
 *  remember which file they were retrying or why it failed last time.
 *
 *  Carries the raw `retryFailureCode`, not a pre-rendered message -- Import.tsx already imports
 *  `importFailureMessage` to curate a different message elsewhere, and baking the curated STRING
 *  into the nav-state contract instead of the CODE would let this and that other call site drift
 *  in wording for the same failure code, or fall back to two different generic messages. One
 *  curation site (Import.tsx, at render time) instead of the producer pre-formatting for the
 *  consumer to blindly trust.
 *
 *  Sprint 4's byte-retention retry should extend this union with a new sibling `kind` (e.g.
 *  `'retryWithBytes'`) once a retained session exists server-side, rather than growing optional
 *  fields onto this one -- that would reintroduce the exact "field sometimes present" ambiguity
 *  the `kind` tag exists to eliminate. */
interface RetryFailedImportNavState {
  kind: 'retry';
  retryFileName: string;
  retryFailureCode: string | null;
}

export type ImportNavState = ReimportNavState | ResumeSessionNavState | RetryFailedImportNavState;

export function navigateToReimport(
  navigate: NavigateFunction,
  args: Omit<ReimportNavState, 'kind'>
): void {
  void navigate('/app/import', { state: { kind: 'reimport', ...args } });
}

export function navigateToResumeSession(navigate: NavigateFunction, resumeSessionId: string): void {
  void navigate('/app/import', { state: { kind: 'resume', resumeSessionId } });
}

export function navigateToRetryFailedImport(
  navigate: NavigateFunction,
  retryFileName: string,
  retryFailureCode: string | null
): void {
  void navigate('/app/import', { state: { kind: 'retry', retryFileName, retryFailureCode } });
}
