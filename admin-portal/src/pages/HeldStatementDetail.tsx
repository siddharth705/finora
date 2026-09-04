import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Download } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { useAdminAuth } from '../context/AdminAuthContext';
import { adminHeldStatementApi } from '../api/endpoints';
import { formatWhen } from '../lib/formatWhen';
import type { HeldStatementFinding, HeldStatementRerunResult } from '../types';

const RESOLVED_STATUSES = new Set(['IMPORTED', 'REJECTED']);

function onActionErrorMessage(error: unknown): string {
  const response = (error as { response?: { data?: { message?: string } } })?.response;
  return response?.data?.message ?? 'That action could not be completed.';
}

/** A detail value is a count, a boolean, a string, or -- for one rule's histogram
 *  (`droppedTransactionCandidateReasons`) -- a reason-code-to-count object; `mismatches` is a
 *  string list. `String(value)` renders an object as the literal, useless "[object Object]", so
 *  anything non-primitive is JSON-stringified instead -- not pretty, but legible, which is what a
 *  diagnostic view over an intentionally-unshaped allowlisted map needs. */
function formatDetailValue(value: unknown): string {
  if (value === null || value === undefined) return '—';
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}

/** One finding's evidence as key: value pairs -- the numbers behind the trigger, not a sentence
 *  summarising them. `details` is an arbitrary allowlisted map from the backend, so this renders
 *  whatever keys are actually present rather than assuming a fixed shape. */
function FindingCard({ finding }: { finding: HeldStatementFinding }) {
  const entries = Object.entries(finding.details);
  return (
    <div className="rounded-lg border border-border bg-bg p-3">
      <div className="flex items-center justify-between">
        <span className="text-ink font-mono text-xs font-medium">{finding.rule}</span>
        <span className="text-xs text-amber-400">{finding.outcome}</span>
      </div>
      {entries.length > 0 && (
        <dl className="grid grid-cols-2 gap-x-4 gap-y-1 mt-2 text-xs">
          {entries.map(([key, value]) => (
            <div key={key} className="flex justify-between gap-2">
              <dt className="text-muted">{key}</dt>
              <dd className="text-ink font-mono break-all">{formatDetailValue(value)}</dd>
            </div>
          ))}
        </dl>
      )}
    </div>
  );
}

function HeldStatementDetailContent({ heldId }: { heldId: string }) {
  const { roles } = useAdminAuth();
  const canDownload = roles.includes('ADMIN') || roles.includes('SUPER_ADMIN');
  const queryClient = useQueryClient();

  const [actionError, setActionError] = useState<string | null>(null);
  const [approveNote, setApproveNote] = useState('');
  const [rejectReason, setRejectReason] = useState('');
  const [engineerIdInput, setEngineerIdInput] = useState('');
  const [notesDraft, setNotesDraft] = useState('');
  const [rootCauseDraft, setRootCauseDraft] = useState('');
  const [fixReferenceDraft, setFixReferenceDraft] = useState('');
  const [rerunResult, setRerunResult] = useState<HeldStatementRerunResult | null>(null);

  const detail = useQuery({
    queryKey: ['held-statement-detail', heldId],
    queryFn: () => adminHeldStatementApi.get(heldId),
  });

  // Pre-fills the notes/findings editors with what is already on the row, once, when the row
  // first loads -- not on every refetch, or an operator's in-progress edit would be clobbered the
  // moment their own save triggers this same query to refresh.
  useEffect(() => {
    if (detail.data) {
      setNotesDraft(detail.data.summary.engineerNotes ?? '');
      setRootCauseDraft(detail.data.summary.rootCause ?? '');
      setFixReferenceDraft(detail.data.summary.fixReference ?? '');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [detail.data?.summary.id]);

  // A previous held statement's rerun result must never linger on screen once navigation moves to
  // a different one -- "clears under the current parser build" shown against the wrong row is
  // actively misleading, not just stale. Keyed on the route param itself (not detail.data), so the
  // reset happens the instant navigation occurs rather than waiting for the new query to resolve.
  useEffect(() => {
    setRerunResult(null);
  }, [heldId]);

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ['held-statement-detail', heldId] });
    void queryClient.invalidateQueries({ queryKey: ['held-statements-list'] });
  }

  function onError(error: unknown) {
    setActionError(onActionErrorMessage(error));
  }

  const approve = useMutation({
    mutationFn: () => adminHeldStatementApi.approve(heldId, approveNote || undefined),
    onSuccess: () => { setActionError(null); invalidate(); },
    onError,
  });
  const reject = useMutation({
    mutationFn: () => adminHeldStatementApi.reject(heldId, rejectReason || undefined),
    onSuccess: () => { setActionError(null); invalidate(); },
    onError,
  });
  const assignToMe = useMutation({
    mutationFn: () => adminHeldStatementApi.assign(heldId, undefined),
    onSuccess: () => { setActionError(null); invalidate(); },
    onError,
  });
  const assignToEngineer = useMutation({
    mutationFn: () => adminHeldStatementApi.assign(heldId, engineerIdInput.trim()),
    onSuccess: () => { setActionError(null); setEngineerIdInput(''); invalidate(); },
    onError,
  });
  const investigate = useMutation({
    mutationFn: () => adminHeldStatementApi.investigate(heldId),
    onSuccess: () => { setActionError(null); invalidate(); },
    onError,
  });
  const saveNotes = useMutation({
    mutationFn: () => adminHeldStatementApi.notes(heldId, notesDraft),
    onSuccess: () => { setActionError(null); invalidate(); },
    onError,
  });
  const saveFindings = useMutation({
    mutationFn: () => adminHeldStatementApi.saveFindings(heldId, rootCauseDraft || undefined,
      fixReferenceDraft || undefined),
    onSuccess: () => { setActionError(null); invalidate(); },
    onError,
  });
  const rerunParser = useMutation({
    mutationFn: () => adminHeldStatementApi.rerunParser(heldId),
    onSuccess: (result) => { setActionError(null); setRerunResult(result); invalidate(); },
    onError,
  });
  const download = useMutation({
    mutationFn: () => adminHeldStatementApi.download(heldId),
    onError,
  });

  if (detail.isLoading) {
    return <p className="text-muted text-sm">Loading…</p>;
  }
  if (detail.isError || !detail.data) {
    return <p className="text-muted text-sm">No such held statement.</p>;
  }

  const { summary, findings, timeline } = detail.data;
  const resolved = RESOLVED_STATUSES.has(summary.status);
  const busy = approve.isPending || reject.isPending || assignToMe.isPending
    || assignToEngineer.isPending || investigate.isPending || rerunParser.isPending;

  return (
    <div className="space-y-6">
      <Link to="/held-statements" className="text-xs text-accent hover:underline">
        &larr; Back to queue
      </Link>

      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-semibold text-ink font-mono">{summary.heldId}</h2>
          <p className="text-muted text-xs mt-1">{summary.status.replace(/_/g, ' ')}</p>
        </div>
        {canDownload && (
          <button
            type="button"
            onClick={() => download.mutate()}
            disabled={download.isPending}
            className="inline-flex items-center gap-2 rounded-lg border border-border px-3 py-1.5 text-xs text-ink hover:bg-card disabled:opacity-50"
          >
            <Download className="h-3.5 w-3.5" />
            {download.isPending ? 'Downloading…' : 'Download statement'}
          </button>
        )}
      </div>

      {actionError && (
        <div className="rounded-lg border border-red-500/20 bg-red-500/5 p-3">
          <p className="text-sm text-red-400">{actionError}</p>
        </div>
      )}

      {/* Trigger evidence */}
      <section className="bg-card border border-border rounded-xl2 p-6 space-y-3">
        <h3 className="text-sm font-semibold text-ink">Why this fired</h3>
        <p className="text-sm text-muted">{summary.triggerSummary ?? '—'}</p>
        {findings.length > 0 && (
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            {findings.map((finding) => (
              <FindingCard key={`${finding.sectionIndex}-${finding.rule}`} finding={finding} />
            ))}
          </div>
        )}
      </section>

      {/* Extraction snapshot */}
      <section className="bg-card border border-border rounded-xl2 p-6">
        <h3 className="text-sm font-semibold text-ink mb-3">Extraction snapshot</h3>
        <dl className="grid grid-cols-2 sm:grid-cols-3 gap-4 text-sm">
          <div>
            <dt className="text-muted text-xs">User</dt>
            <dd className="text-ink font-mono text-xs">{summary.userId}</dd>
          </div>
          <div>
            <dt className="text-muted text-xs">Bank</dt>
            <dd className="text-ink">{summary.bankName ?? '—'}</dd>
          </div>
          <div>
            <dt className="text-muted text-xs">Parser version</dt>
            <dd className="text-ink font-mono text-xs">{summary.parserVersion ?? '—'}</dd>
          </div>
          <div>
            <dt className="text-muted text-xs">Reliability</dt>
            <dd className="text-ink">{summary.reliabilityStatus ?? '—'}</dd>
          </div>
          <div>
            <dt className="text-muted text-xs">Text source</dt>
            <dd className="text-ink">{summary.textSource ?? '—'}</dd>
          </div>
          <div>
            <dt className="text-muted text-xs">Header reconstruction uncertain</dt>
            <dd className="text-ink">
              {summary.headerReconstructionUncertain === null ? '—'
                : summary.headerReconstructionUncertain ? 'Yes' : 'No'}
            </dd>
          </div>
        </dl>
      </section>

      {/* Parser re-run */}
      <section className="bg-card border border-border rounded-xl2 p-6 space-y-3">
        <h3 className="text-sm font-semibold text-ink">Re-run parser</h3>
        <p className="text-xs text-muted">
          Re-parses this statement's original bytes with the parser build running right now, and
          checks whether it would still be flagged. Writes nothing to the staged rows.
        </p>
        <button
          type="button"
          onClick={() => rerunParser.mutate()}
          disabled={busy || resolved}
          className="rounded-lg border border-border px-3 py-1.5 text-xs text-muted hover:text-ink disabled:opacity-50"
        >
          {rerunParser.isPending ? 'Re-running…' : 'Re-run parser'}
        </button>
        {rerunResult && (
          <div className="rounded-lg border border-border bg-bg p-3 text-xs space-y-1">
            <p className={rerunResult.stillHeld ? 'text-amber-400' : 'text-emerald-400'}>
              {rerunResult.stillHeld
                ? `Still held: ${rerunResult.reasons.join('; ')}`
                : 'Clears under the current parser build.'}
            </p>
            {rerunResult.parserVersionChanged && (
              <p className="text-muted font-mono">
                {rerunResult.previousParserVersion || '—'} &rarr; {rerunResult.currentParserVersion || '—'}
              </p>
            )}
          </div>
        )}
      </section>

      {/* Assignment */}
      <section className="bg-card border border-border rounded-xl2 p-6 space-y-4">
        <h3 className="text-sm font-semibold text-ink">Assignment</h3>
        <p className="text-xs text-muted">
          Assigned to: <span className="font-mono">{summary.assignedEngineerId ?? '—'}</span>
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={() => assignToMe.mutate()}
            disabled={busy || resolved}
            className="rounded-lg bg-accent px-3 py-1.5 text-xs font-medium text-white disabled:opacity-50"
          >
            Assign to me
          </button>
          <input
            value={engineerIdInput}
            onChange={(e) => setEngineerIdInput(e.target.value)}
            placeholder="Engineer id…"
            className="rounded-lg border border-border bg-bg px-3 py-1.5 text-sm text-ink"
          />
          <button
            type="button"
            onClick={() => assignToEngineer.mutate()}
            disabled={busy || resolved || !engineerIdInput.trim()}
            className="rounded-lg border border-border px-3 py-1.5 text-xs text-muted hover:text-ink disabled:opacity-50"
          >
            Assign
          </button>
          <button
            type="button"
            onClick={() => investigate.mutate()}
            disabled={busy || resolved}
            className="rounded-lg border border-border px-3 py-1.5 text-xs text-muted hover:text-ink disabled:opacity-50"
          >
            Start investigation
          </button>
        </div>

        <div className="space-y-2 border-t border-border pt-4">
          <label className="text-xs text-muted" htmlFor="held-statement-notes">
            Engineer notes -- replaces the whole write-up; the history of what it said before is
            in the timeline below.
          </label>
          <textarea
            id="held-statement-notes"
            value={notesDraft}
            onChange={(e) => setNotesDraft(e.target.value)}
            rows={3}
            className="w-full rounded-lg border border-border bg-bg px-3 py-1.5 text-sm text-ink"
          />
          <button
            type="button"
            onClick={() => saveNotes.mutate()}
            disabled={saveNotes.isPending}
            className="rounded-lg border border-border px-3 py-1.5 text-xs text-muted hover:text-ink disabled:opacity-50"
          >
            {saveNotes.isPending ? 'Saving…' : 'Save notes'}
          </button>
        </div>

        <div className="space-y-2 border-t border-border pt-4">
          <label className="text-xs text-muted" htmlFor="held-statement-root-cause">Root cause</label>
          <textarea
            id="held-statement-root-cause"
            value={rootCauseDraft}
            onChange={(e) => setRootCauseDraft(e.target.value)}
            rows={2}
            className="w-full rounded-lg border border-border bg-bg px-3 py-1.5 text-sm text-ink"
          />
          <label className="text-xs text-muted" htmlFor="held-statement-fix-reference">Fix reference</label>
          <input
            id="held-statement-fix-reference"
            value={fixReferenceDraft}
            onChange={(e) => setFixReferenceDraft(e.target.value)}
            placeholder="PR number or URL…"
            className="w-full rounded-lg border border-border bg-bg px-3 py-1.5 text-sm text-ink"
          />
          <button
            type="button"
            onClick={() => saveFindings.mutate()}
            disabled={saveFindings.isPending}
            className="rounded-lg border border-border px-3 py-1.5 text-xs text-muted hover:text-ink disabled:opacity-50"
          >
            {saveFindings.isPending ? 'Saving…' : 'Save findings'}
          </button>
        </div>
      </section>

      {/* Resolution */}
      <section className="bg-card border border-border rounded-xl2 p-6 space-y-4">
        <h3 className="text-sm font-semibold text-ink">Resolve</h3>
        {resolved && (
          <p className="text-xs text-muted">
            This hold was already {summary.status.replace(/_/g, ' ')}; it cannot be resolved again.
          </p>
        )}
        <div className="flex flex-wrap gap-2">
          <input
            value={approveNote}
            onChange={(e) => setApproveNote(e.target.value)}
            placeholder="Note (optional)…"
            disabled={resolved}
            className="flex-1 min-w-[16rem] rounded-lg border border-border bg-bg px-3 py-1.5 text-sm text-ink disabled:opacity-50"
          />
          <button
            type="button"
            onClick={() => approve.mutate()}
            disabled={busy || resolved}
            className="rounded-lg bg-accent px-3 py-1.5 text-xs font-medium text-white disabled:opacity-50"
          >
            Approve
          </button>
        </div>
        <div className="flex flex-wrap gap-2">
          <input
            value={rejectReason}
            onChange={(e) => setRejectReason(e.target.value)}
            placeholder="Reason (optional)…"
            disabled={resolved}
            className="flex-1 min-w-[16rem] rounded-lg border border-border bg-bg px-3 py-1.5 text-sm text-ink disabled:opacity-50"
          />
          <button
            type="button"
            onClick={() => reject.mutate()}
            disabled={busy || resolved}
            className="rounded-lg border border-red-500/30 text-red-400 px-3 py-1.5 text-xs font-medium disabled:opacity-50"
          >
            Reject
          </button>
        </div>
      </section>

      {/* Timeline */}
      <section className="bg-card border border-border rounded-xl2 p-6">
        <h3 className="text-sm font-semibold text-ink mb-3">History</h3>
        <ul className="space-y-2">
          {timeline.map((event, index) => (
            <li key={index} className="text-xs border-l-2 border-border pl-3">
              <span className="text-ink font-medium">{event.eventType}</span>{' '}
              <span className="text-muted">{formatWhen(event.createdAt)}</span>
              {event.notes && <p className="text-muted mt-0.5">{event.notes}</p>}
            </li>
          ))}
          {timeline.length === 0 && <p className="text-muted text-xs">No history yet.</p>}
        </ul>
      </section>
    </div>
  );
}

export default function HeldStatementDetail() {
  const { heldId } = useParams<{ heldId: string }>();
  return (
    <AdminLayout title="Held Statement" subtitle="Trigger evidence, extraction snapshot, and audit history.">
      <RequirePermission permission="TRUST_REVIEW_MANAGE">
        {heldId ? <HeldStatementDetailContent heldId={heldId} /> : <p className="text-muted text-sm">No held statement id.</p>}
      </RequirePermission>
    </AdminLayout>
  );
}
