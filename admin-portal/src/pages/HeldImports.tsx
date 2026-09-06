import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Clock, Download, PlayCircle, RefreshCw } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { Pagination } from '../components/Pagination';
import { useAdminAuth } from '../context/AdminAuthContext';
import { adminHeldImportApi } from '../api/endpoints';
import { formatWhen } from '../lib/formatWhen';
import type { HeldImportRow, HeldImportDetail } from '../types';

const PAGE_SIZE = 25;

/**
 * The held-imports triage queue.
 *
 * An import that failed for a reason nothing in the pipeline recognised lands here instead of being
 * shown to its owner as a bare failure. In practice that means a parser gap on a statement layout
 * this codebase has not seen. The workflow is: open one, read the failure, fix the parser, reprocess
 * -- the user never re-uploads, because the statement bytes were retained for exactly this.
 *
 * **Opening a row is an audited act.** The list carries no customer content; the detail view carries
 * the raw parser error, which routinely quotes the statement that defeated it. Every call to it
 * writes a HELD_IMPORT_VIEWED entry against the admin who made it. That is why the diagnostics sit
 * behind a Details click rather than being inlined into the table -- browsing the queue should not
 * silently open twenty-five people's bank statements.
 *
 * Nothing here re-derives a server-owned rule. Whether a job can be reprocessed is the backend's
 * answer (it returns 409 with the reason), not a status check duplicated in this file.
 */
function HeldImportsContent() {
  const [page, setPage] = useState(0);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const queryClient = useQueryClient();

  const summary = useQuery({
    queryKey: ['held-imports-summary'],
    queryFn: () => adminHeldImportApi.summary(),
  });

  const list = useQuery({
    queryKey: ['held-imports-list', page],
    queryFn: () => adminHeldImportApi.list({ page, size: PAGE_SIZE }),
  });

  const detail = useQuery({
    queryKey: ['held-imports-detail', selectedId],
    queryFn: () => adminHeldImportApi.get(selectedId as string),
    enabled: selectedId !== null,
  });

  /** Both queries, because an action changes the counts as well as the page. */
  function invalidateQueue() {
    void queryClient.invalidateQueries({ queryKey: ['held-imports-list'] });
    void queryClient.invalidateQueries({ queryKey: ['held-imports-summary'] });
  }

  // The server's message is shown verbatim: a 409 here says something specific and actionable
  // ("the user already re-uploaded this"), and replacing it with a generic failure toast would
  // throw away the only part an operator can act on.
  function onActionError(error: unknown) {
    const response = (error as { response?: { data?: { message?: string } } })?.response;
    setActionError(response?.data?.message ?? 'That action could not be completed.');
  }

  const reprocess = useMutation({
    mutationFn: (jobId: string) => adminHeldImportApi.reprocess(jobId),
    onSuccess: () => {
      setActionError(null);
      setSelectedId(null);
      invalidateQueue();
    },
    onError: onActionError,
  });

  const reprocessAll = useMutation({
    mutationFn: () => adminHeldImportApi.reprocessAll(),
    onSuccess: () => {
      setActionError(null);
      invalidateQueue();
    },
    onError: onActionError,
  });

  const resolve = useMutation({
    mutationFn: (vars: { jobId: string; reason: string }) =>
      adminHeldImportApi.resolve(vars.jobId, vars.reason),
    onSuccess: () => {
      setActionError(null);
      setSelectedId(null);
      invalidateQueue();
    },
    onError: onActionError,
  });

  const columns: DataTableColumn<HeldImportRow>[] = [
    {
      header: 'Statement',
      render: (row) => (
        <div>
          <p className="text-ink">{row.fileName}</p>
          <p className="text-muted text-xs">{row.sourceFormat}</p>
        </div>
      ),
    },
    {
      header: 'Failure',
      // The curated code only. The raw error lives behind Details, where reading it is audited.
      render: (row) => (
        <span className="font-mono text-xs text-amber-400">{row.failureCode ?? '—'}</span>
      ),
    },
    {
      header: 'User',
      // A bare id, deliberately: this controller never joins to a user's contact details, so no
      // email or phone number can reach this screen even indirectly.
      render: (row) => <span className="text-muted text-xs font-mono">{row.userId}</span>,
    },
    { header: 'Attempts', render: (row) => <span className="text-ink">{row.attemptCount}</span> },
    { header: 'Uploaded', render: (row) => formatWhen(row.createdAt) },
    { header: 'Held', render: (row) => formatWhen(row.heldAt) },
    {
      header: '',
      render: (row) => (
        <button className="text-xs text-accent hover:underline" onClick={() => setSelectedId(row.id)}>
          Details
        </button>
      ),
    },
  ];

  const counts = summary.data;

  return (
    <div className="space-y-6">
      <p className="text-muted text-sm">
        Imports that failed for a reason nothing recognised — almost always a parser gap on a
        statement layout Fynora has not seen. The user has been told we are running additional
        checks and has not been asked to do anything. Fix the parser, then reprocess: the statement
        was retained, so nobody re-uploads.
      </p>

      {counts && (
        <div className="grid grid-cols-2 gap-3 sm:max-w-md">
          <div className="bg-card border border-border rounded-xl2 p-4">
            <p className="text-muted text-xs flex items-center gap-1">
              <Clock className="h-3.5 w-3.5" /> Waiting
            </p>
            <p className="text-2xl font-semibold text-ink mt-1">{counts.held}</p>
          </div>
          <div className="bg-card border border-border rounded-xl2 p-4">
            <p className="text-muted text-xs flex items-center gap-1">
              <RefreshCw className="h-3.5 w-3.5" /> Reprocessing
            </p>
            <p className="text-2xl font-semibold text-ink mt-1">{counts.reprocessing}</p>
          </div>
        </div>
      )}

      {actionError && (
        <div className="rounded-lg border border-red-500/20 bg-red-500/5 p-3">
          <p className="text-sm text-red-400">{actionError}</p>
        </div>
      )}

      {(counts?.held ?? 0) > 0 && (
        <button
          type="button"
          onClick={() => reprocessAll.mutate()}
          disabled={reprocessAll.isPending}
          className="inline-flex items-center gap-2 rounded-lg border border-border px-3 py-1.5 text-xs text-ink hover:bg-card disabled:opacity-50"
        >
          <PlayCircle className="h-3.5 w-3.5" />
          {reprocessAll.isPending ? 'Reprocessing…' : 'Reprocess all'}
        </button>
      )}

      <DataTable
        columns={columns}
        rows={list.data?.content ?? []}
        keyFor={(row) => row.id}
        loading={list.isLoading}
        emptyMessage="Nothing is held for review. Every import either succeeded or failed for a reason we recognise."
      />

      {list.data && (
        <Pagination
          page={page}
          totalPages={list.data.totalPages}
          totalElements={list.data.totalElements}
          pageSize={PAGE_SIZE}
          onPageChange={setPage}
        />
      )}

      {selectedId && (
        <HeldImportDetailPanel
          // Keyed on selectedId, not just conditionally rendered: the table rows stay clickable
          // with the panel open (this isn't a modal), so clicking "Details" on a different row
          // changes selectedId without ever passing through null -- the panel would otherwise keep
          // its same component instance and carry the previous job's `downloading`/`downloadError`
          // local state into the newly-selected job's view. Found in review.
          key={selectedId}
          detail={detail.data}
          loading={detail.isLoading}
          busy={reprocess.isPending || resolve.isPending}
          onReprocess={() => reprocess.mutate(selectedId)}
          onResolve={(reason) => resolve.mutate({ jobId: selectedId, reason })}
          onClose={() => setSelectedId(null)}
        />
      )}
    </div>
  );
}

/**
 * One held import's diagnostics and the two things an operator can do with it.
 *
 * Rendering this panel is what the HELD_IMPORT_VIEWED audit entry records, so the notice saying so
 * is shown to the operator rather than left implicit in a policy document.
 */
function HeldImportDetailPanel({
  detail,
  loading,
  busy,
  onReprocess,
  onResolve,
  onClose,
}: {
  detail: HeldImportDetail | undefined;
  loading: boolean;
  busy: boolean;
  onReprocess: () => void;
  onResolve: (reason: string) => void;
  onClose: () => void;
}) {
  const [reason, setReason] = useState('');
  const { roles } = useAdminAuth();
  const canDownload = roles.includes('ADMIN') || roles.includes('SUPER_ADMIN');
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  async function handleDownload() {
    if (!detail) return;
    setDownloading(true);
    setDownloadError(null);
    try {
      await adminHeldImportApi.download(detail.job.id, detail.job.fileName);
    } catch (err) {
      // Same reasoning as onActionError above: adminHeldImportApi.download runs its failures
      // through withBlobErrorMessage, which reshapes a blob error response into a plain
      // {message, errorCode} object -- so a specific, actionable server message (e.g. this job
      // was resolved out from under the admin) is available here too, not just a generic string.
      // Found in review: this used to discard it unconditionally.
      const response = (err as { response?: { data?: { message?: string } } })?.response;
      setDownloadError(response?.data?.message ?? 'Could not download this statement.');
    } finally {
      setDownloading(false);
    }
  }

  return (
    <div className="bg-card border border-border rounded-xl2 p-6 space-y-5">
      <div className="flex items-start justify-between">
        <h2 className="text-lg font-semibold text-ink">Held import</h2>
        <div className="flex items-center gap-3">
          {detail && canDownload && (
            <button
              type="button"
              onClick={() => void handleDownload()}
              disabled={downloading}
              className="inline-flex items-center gap-2 rounded-lg border border-border px-3 py-1.5 text-xs text-ink hover:bg-card disabled:opacity-50"
            >
              <Download className="h-3.5 w-3.5" />
              {downloading ? 'Downloading…' : 'Download statement'}
            </button>
          )}
          <button className="text-muted hover:text-ink text-sm" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
      {downloadError && <p className="text-xs text-red-400">{downloadError}</p>}

      {loading && <p className="text-muted text-sm">Loading…</p>}

      {detail && (
        <>
          <p className="text-xs text-muted">
            Opening this record has been logged against your account, because it shows content from
            a customer&apos;s bank statement.
          </p>

          <div className="rounded-lg border border-border bg-bg p-3">
            <p className="text-ink font-medium">{detail.job.fileName}</p>
            <p className="text-muted text-xs mt-1 font-mono">{detail.job.failureCode ?? '—'}</p>
          </div>

          {detail.lastError && (
            <div className="rounded-lg border border-amber-500/20 bg-amber-500/5 p-3">
              <p className="text-xs text-amber-400 font-medium">Parser error</p>
              <p className="text-sm text-ink mt-1 font-mono break-words">{detail.lastError}</p>
            </div>
          )}

          <dl className="grid grid-cols-1 sm:grid-cols-2 gap-4 text-sm">
            <div>
              <dt className="text-muted text-xs">User</dt>
              <dd className="text-ink font-mono text-xs">{detail.job.userId}</dd>
            </div>
            <div>
              <dt className="text-muted text-xs">Correlation id</dt>
              <dd className="text-ink font-mono text-xs">{detail.correlationId ?? '—'}</dd>
            </div>
            <div>
              <dt className="text-muted text-xs">Stored object</dt>
              <dd className="text-ink font-mono text-xs break-all">{detail.objectKey ?? '—'}</dd>
            </div>
            <div>
              <dt className="text-muted text-xs">Attempts / recoveries</dt>
              <dd className="text-ink">
                {detail.job.attemptCount} / {detail.job.recoveryCount}
              </dd>
            </div>
          </dl>

          <div className="flex flex-wrap items-center gap-3 border-t border-border pt-4">
            <button
              type="button"
              onClick={onReprocess}
              disabled={busy}
              className="inline-flex items-center gap-2 rounded-lg bg-accent px-3 py-1.5 text-xs font-medium text-white disabled:opacity-50"
            >
              <PlayCircle className="h-3.5 w-3.5" />
              Reprocess
            </button>
            <span className="text-xs text-muted">
              Safe to try: if the parser still cannot read it, the job comes straight back here.
            </span>
          </div>

          <div className="space-y-2 border-t border-border pt-4">
            <label className="text-xs text-muted" htmlFor="held-import-resolve-reason">
              Give up on this one (the user sees the ordinary failure). Reason is recorded on the
              audit entry.
            </label>
            <div className="flex flex-wrap gap-2">
              <input
                id="held-import-resolve-reason"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder="e.g. scanned image with no text layer"
                className="flex-1 min-w-[16rem] rounded-lg border border-border bg-bg px-3 py-1.5 text-sm text-ink"
              />
              <button
                type="button"
                onClick={() => onResolve(reason)}
                disabled={busy}
                className="rounded-lg border border-border px-3 py-1.5 text-xs text-muted hover:text-ink disabled:opacity-50"
              >
                Resolve without fixing
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

export default function HeldImports() {
  return (
    <AdminLayout
      title="Held Imports"
      subtitle="Statements waiting on a parser fix. Every detail view is audited."
    >
      <RequirePermission permission="IMPORT_TRIAGE_MANAGE">
        <HeldImportsContent />
      </RequirePermission>
    </AdminLayout>
  );
}
