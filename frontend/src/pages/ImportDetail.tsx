import { useState } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ArrowLeft, FileQuestion, RefreshCw } from 'lucide-react';
import { importJobsApi } from '../api/endpoints';
import { ImportTimeline } from '../components/ImportTimeline';
import { PageLoading } from '../components/PageLoading';
import { isReviewable, label } from '../lib/importJob';
import { formatDate } from '../utils/date';

/**
 * The self-service landing page for one past import (Premium Import Reliability v1, §3.2) --
 * "what happened to the statement I uploaded yesterday", reachable from the Recent Imports list
 * on {@link import('./StatementHistory').default} or a direct link, rather than only inferable
 * from whichever transactions did or didn't show up in the ledger.
 *
 * <b>One fetch, not a poller.</b> `progress` and `timeline` are the exact same two calls
 * {@link import('../components/ImportProgress').ImportProgress} and {@link ImportTimeline} already
 * poll continuously for a *live* upload -- but a visit here is someone checking on a job that, in
 * the overwhelming common case, already finished. Polling by default would mean two more
 * independent background loops for the app to run for the entire time this page stays open,
 * exactly the double-polling cost already flagged (and deliberately deferred) on the live import
 * screen, and for no benefit here: this page has a Refresh button instead, so checking "is it done
 * yet" is a deliberate action instead of a standing cost.
 */
export default function ImportDetail() {
  const { jobId } = useParams<{ jobId: string }>();
  const navigate = useNavigate();
  const [refreshToken, setRefreshToken] = useState(0);

  const progressQuery = useQuery({
    queryKey: ['import-job-detail', jobId],
    queryFn: () => importJobsApi.progress(jobId as string),
    enabled: !!jobId,
    // A 404 here means "not yours, or doesn't exist" (the endpoint answers ownership failures and
    // missing jobs identically) -- a final, deterministic answer, not a blip worth the default
    // client-wide retry.
    retry: false,
  });

  // Deliberately not folded into progressQuery's queryKey: refetch() re-runs the same query in
  // place (isFetching flips true, the last-good job stays on screen), where a changing key would
  // make React Query treat every Refresh click as a brand new, uncached query and flash back to
  // the loading state instead.
  function refresh() {
    setRefreshToken((t) => t + 1);
    void progressQuery.refetch();
  }

  if (!jobId) return null;

  if (progressQuery.isLoading) {
    return <PageLoading />;
  }

  // Checked on `data`, not `isError`: React Query keeps the last-good `data` around across a
  // FAILED REFETCH (confirmed directly against this component -- load a job, click Refresh, have
  // the second request reject, and the page kept its already-rendered job exactly once this
  // guard was fixed to check for data instead of the error flag). `isError` alone doesn't
  // distinguish "never loaded" from "a refresh of an already-loaded job hit a blip" -- and this
  // page's only real interaction IS that Refresh button, so the failure this guards against is
  // not a corner case. A transient network blip or backend 500 during a routine refresh must not
  // replace a working, correctly-rendered import with "this doesn't exist, or isn't yours."
  if (!progressQuery.data) {
    return (
      <div className="bg-card rounded-xl2 shadow-card border border-border p-10 text-center">
        <FileQuestion size={28} className="mx-auto text-muted" />
        <p className="text-sm font-medium text-ink mt-3">Import not found</p>
        <p className="text-xs text-muted mt-1">
          This import doesn't exist, or isn't yours to view.
        </p>
        <Link
          to="/app/statements"
          className="mt-4 inline-block text-xs font-medium text-primary hover:underline"
        >
          Back to Statement History
        </Link>
      </div>
    );
  }

  const job = progressQuery.data;

  return (
    <div className="space-y-4 max-w-2xl">
      <Link
        to="/app/statements"
        className="text-xs text-muted hover:text-ink inline-flex items-center gap-1"
      >
        <ArrowLeft size={12} /> Back to Statement History
      </Link>

      <div className="bg-card rounded-xl2 shadow-card border border-border p-6">
        <div className="flex items-start justify-between gap-4 flex-wrap">
          <div>
            <h1 className="text-lg font-bold text-ink truncate">{job.fileName}</h1>
            <p className="text-sm text-muted mt-0.5">
              {label(job)} · Uploaded {formatDate(job.createdAt)}
            </p>
          </div>
          <button
            type="button"
            onClick={refresh}
            disabled={progressQuery.isFetching}
            className="flex-shrink-0 flex items-center gap-1.5 text-xs font-medium text-muted hover:text-ink border border-border rounded-lg px-3 py-1.5 disabled:opacity-50"
          >
            <RefreshCw size={12} className={progressQuery.isFetching ? 'animate-spin' : ''} />
            Refresh
          </button>
        </div>

        {progressQuery.isError && (
          <p className="text-xs text-danger mt-2">
            Couldn't refresh -- showing the last known status.
          </p>
        )}

        {isReviewable(job) && (
          <button
            type="button"
            onClick={() =>
              void navigate('/app/import', { state: { resumeSessionId: job.importSessionId } })
            }
            className="mt-4 bg-primary text-white text-sm font-semibold rounded-lg px-4 py-2 hover:opacity-90"
          >
            Review this import
          </button>
        )}

        {job.status === 'FAILED' && (
          <button
            type="button"
            onClick={() => void navigate('/app/import')}
            className="mt-4 bg-primary text-white text-sm font-semibold rounded-lg px-4 py-2 hover:opacity-90"
          >
            Upload a different file
          </button>
        )}
      </div>

      <ImportTimeline jobId={jobId} autoRefresh={false} refreshToken={refreshToken} />
    </div>
  );
}
