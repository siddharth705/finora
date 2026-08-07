import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Search, Route, Clock, ShieldQuestion, GraduationCap, CheckCircle2, AlertTriangle, SkipForward } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { adminImportTraceApi } from '../api/endpoints';
import type { ImportTrace, ImportTraceFinding, ImportTraceStage } from '../types';

/**
 * One import, end to end — Milestone 2's sixth success criterion.
 *
 * <h2>Why this page exists</h2>
 * The backend for it shipped complete and unreachable, which is the second time that has happened
 * in this subsystem: `/admin/imports/traces` joined three tables nothing had ever joined and no
 * client called it, exactly as `LayoutIntelligenceService` was a service nothing called. Answering
 * "what happened to this import" still meant three queries against `import_jobs`,
 * `statement_analysis_sessions` and `merchant_learning_events`, plus knowing all three existed. The
 * criterion says *without a log or an engineer*, and a REST endpoint an engineer has to curl is
 * still an engineer.
 *
 * <h2>Assembled, not scored</h2>
 * No health badge, no overall verdict, no "this import looks fine". Each panel reports what its own
 * table recorded and the operator draws the conclusion — the position `VerificationReport` and
 * `LayoutIntelligence` both take, and for the same reason: a summary judgement needs a weighting
 * policy nothing here can calibrate, and a number that quietly becomes a verdict is the failure mode
 * those were written to avoid.
 *
 * <h2>An absent panel is an answer</h2>
 * A synchronous import has no job and no stage timings. An asynchronous one records no analysis row.
 * A staged-but-unconfirmed import has no completion, because staging and importing are different
 * events and confirming is still the user's decision. Each of those renders as a stated absence
 * rather than as blank space or a zero — "this path does not record that" and "that did not happen"
 * are different facts, and an operator who cannot tell them apart will read the wrong one.
 */

function duration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return 'Not measured';
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
}

function timestamp(iso: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('en-GB', {
    day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
}

/** The panel wrapper, so an empty block states its own absence rather than rendering nothing. */
function Panel({
  icon, title, hint, empty, children,
}: {
  icon: React.ReactNode;
  title: string;
  hint?: string;
  /** Shown instead of the children. A sentence about why there is nothing, never a bare dash. */
  empty?: string | null;
  children?: React.ReactNode;
}) {
  return (
    <section className="bg-card border border-border rounded-xl2 p-5 mb-4">
      <div className="flex items-center gap-2 mb-1">
        {icon}
        <h2 className="font-semibold text-ink text-sm">{title}</h2>
      </div>
      {hint && <p className="text-xs text-muted mb-3">{hint}</p>}
      {empty ? <p className="text-sm text-muted italic">{empty}</p> : children}
    </section>
  );
}

function Field({ label, value, mono }: { label: string; value: React.ReactNode; mono?: boolean }) {
  return (
    <div>
      <p className="text-muted text-[10px] uppercase tracking-wide">{label}</p>
      <p className={`text-ink text-sm ${mono ? 'font-mono text-xs break-all' : ''}`}>{value}</p>
    </div>
  );
}

const STAGE_ICON: Record<ImportTraceStage['outcome'], React.ReactNode> = {
  COMPLETED: <CheckCircle2 size={14} className="text-success" />,
  FAILED: <AlertTriangle size={14} className="text-danger" />,
  SKIPPED: <SkipForward size={14} className="text-muted" />,
  RUNNING: <Clock size={14} className="text-warning" />,
};

function StageRow({ stage }: { stage: ImportTraceStage }) {
  return (
    <tr className="border-b border-border last:border-0">
      <td className="py-2 pr-3">
        <span className="inline-flex items-center gap-1.5 text-ink">
          {STAGE_ICON[stage.outcome] ?? null}
          {stage.stage}
        </span>
      </td>
      <td className="py-2 pr-3 text-muted">{stage.attempt}</td>
      <td className="py-2 pr-3 text-muted">
        {stage.outcome}
        {/* The one reading an operator would otherwise have to work out for themselves. */}
        {stage.outcome === 'RUNNING' && stage.endedAt === null && (
          <span className="text-warning text-xs"> — worker died here</span>
        )}
      </td>
      <td className="py-2 pr-3 text-muted whitespace-nowrap">{timestamp(stage.startedAt)}</td>
      <td className="py-2 text-ink whitespace-nowrap">{duration(stage.durationMs)}</td>
    </tr>
  );
}

function FindingRow({ finding }: { finding: ImportTraceFinding }) {
  const tone = finding.outcome === 'FAILED' ? 'text-danger'
    : finding.outcome === 'WARNING' ? 'text-warning'
    : finding.outcome === 'VERIFIED' ? 'text-success'
    : 'text-muted';
  return (
    <tr className="border-b border-border last:border-0 align-top">
      <td className="py-2 pr-3 text-muted">{finding.sectionIndex}</td>
      <td className="py-2 pr-3 text-ink">{finding.rule}</td>
      <td className={`py-2 pr-3 font-medium ${tone}`}>{finding.outcome}</td>
      <td className="py-2 text-muted font-mono text-xs break-all">
        {Object.keys(finding.details ?? {}).length === 0
          ? '—'
          : Object.entries(finding.details).map(([k, v]) => `${k}=${String(v)}`).join('  ')}
      </td>
    </tr>
  );
}

function TraceView({ trace }: { trace: ImportTrace }) {
  const job = trace.job;
  const analysis = trace.analysis?.analysis ?? null;

  return (
    <>
      <Panel
        icon={<Route size={16} className="text-primary" />}
        title="Handles"
        hint="What ties this import together across the queue, the evidence table and the logs."
      >
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          <Field label="Analysis reference" value={trace.analysisReference ?? '—'} mono />
          <Field label="Job id" value={trace.importJobId ?? '—'} mono />
          <Field label="Session id" value={trace.importSessionId ?? '—'} mono />
          <Field label="Correlation id" value={trace.correlationId ?? '—'} mono />
        </div>
      </Panel>

      <Panel
        icon={<Clock size={16} className="text-primary" />}
        title="Queue"
        hint="The durable job. What the person who uploaded actually waited is queued-to-finished, which is not any single stage's duration."
        empty={job ? null : 'This import ran synchronously, so it never had a job. Nothing is missing.'}
      >
        {job && (
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <Field label="Status" value={job.status} />
            <Field label="Attempts" value={job.attemptCount} />
            <Field
              label="Rows"
              value={job.rowsTotal === null
                // Null is "not counted yet", which is not zero -- see ImportJobDto.Progress.
                ? 'Not counted'
                : `${job.rowsProcessed} of ${job.rowsTotal}`}
            />
            <Field label="Total wait" value={duration(job.totalDurationMs)} />
            <Field label="Queued" value={timestamp(job.queuedAt)} />
            <Field label="Started" value={timestamp(job.startedAt)} />
            <Field label="Finished" value={timestamp(job.finishedAt)} />
            {job.lastError && <Field label="Last error" value={<span className="text-danger">{job.lastError}</span>} />}
          </div>
        )}
      </Panel>

      <Panel
        icon={<Search size={16} className="text-primary" />}
        title="Parsing"
        hint="The evidence row for the upload attempt — the same one Layout Studio reads."
        empty={analysis ? null : 'No analysis row. Asynchronous jobs that failed before staging record none.'}
      >
        {analysis && (
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <Field label="Outcome" value={analysis.outcome} />
            <Field label="Format" value={analysis.sourceFormat ?? '—'} />
            <Field label="Layout fingerprint" value={analysis.layoutFingerprint ?? 'Never characterised'} mono />
            <Field label="Parse duration" value={duration(analysis.durationMs)} />
            <Field label="Sections" value={analysis.sectionCount ?? '—'} />
            {/* Null means never measured, which the backend keeps deliberately distinct from 0. */}
            <Field label="Rows extracted" value={analysis.rowCount ?? 'Not measured'} />
            <Field label="Unanchored rows" value={analysis.unanchoredRowCount} />
            {analysis.failureCode && (
              <Field label="Failure code" value={<span className="text-danger">{analysis.failureCode}</span>} />
            )}
          </div>
        )}
      </Panel>

      <Panel
        icon={<Clock size={16} className="text-primary" />}
        title="Stages"
        hint="Per-stage timing, in the order recorded. A stage still RUNNING on a finished job is where a worker died; SKIPPED is a stage that never ran at all."
        empty={trace.stages.length > 0 ? null : 'No stage timings. The synchronous path is not separately timed.'}
      >
        {trace.stages.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-[10px] uppercase text-muted border-b border-border">
                  <th className="py-2 pr-3">Stage</th><th className="py-2 pr-3">Attempt</th>
                  <th className="py-2 pr-3">Outcome</th><th className="py-2 pr-3">Started</th>
                  <th className="py-2">Duration</th>
                </tr>
              </thead>
              <tbody>
                {trace.stages.map((s, i) => <StageRow key={`${s.stage}-${s.attempt}-${i}`} stage={s} />)}
              </tbody>
            </table>
          </div>
        )}
      </Panel>

      <Panel
        icon={<ShieldQuestion size={16} className="text-primary" />}
        title="Verification"
        hint="Every rule that ran, with what it found. Details are structural facts only — no balances, no cell values."
        empty={trace.verification.length > 0 ? null
          : 'No verification recorded. That is not the same as every rule passing — it means nothing was written for this import.'}
      >
        {trace.verification.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-[10px] uppercase text-muted border-b border-border">
                  <th className="py-2 pr-3">Section</th><th className="py-2 pr-3">Rule</th>
                  <th className="py-2 pr-3">Outcome</th><th className="py-2">Details</th>
                </tr>
              </thead>
              <tbody>
                {trace.verification.map((f, i) => <FindingRow key={`${f.rule}-${f.sectionIndex}-${i}`} finding={f} />)}
              </tbody>
            </table>
          </div>
        )}
      </Panel>

      <Panel
        icon={<GraduationCap size={16} className="text-primary" />}
        title="Learning"
        hint="What the import taught the system. Zero is a legitimate answer — an import of merchants Finora already knew teaches it nothing."
      >
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-3">
          <Field label="Events" value={trace.learning.events} />
          {Object.entries(trace.learning.byStatus ?? {}).map(([status, count]) => (
            <Field key={status} label={status} value={count} />
          ))}
        </div>
        {trace.learning.outstanding.length > 0 && (
          <>
            <p className="text-xs text-muted mb-2">
              Outstanding — the only ones anyone acts on. Work them in the Learning Queue.
            </p>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-[10px] uppercase text-muted border-b border-border">
                    <th className="py-2 pr-3">Event</th><th className="py-2 pr-3">Status</th>
                    <th className="py-2 pr-3">Attempts</th><th className="py-2">Created</th>
                  </tr>
                </thead>
                <tbody>
                  {trace.learning.outstanding.map((e) => (
                    <tr key={e.id} className="border-b border-border last:border-0">
                      <td className="py-2 pr-3 font-mono text-xs text-ink break-all">{e.id}</td>
                      <td className="py-2 pr-3 text-muted">{e.status}</td>
                      <td className="py-2 pr-3 text-muted">{e.attemptCount}</td>
                      <td className="py-2 text-muted whitespace-nowrap">{timestamp(e.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </Panel>

      <Panel
        icon={<CheckCircle2 size={16} className="text-primary" />}
        title="Completion"
        hint="Whether it landed, and what landed."
        empty={trace.completion.statementImportId ? null
          : 'Nothing was confirmed. Staging successfully and importing are different events — confirming is still the user’s decision.'}
      >
        {trace.completion.statementImportId && (
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <Field label="Statement import" value={trace.completion.statementImportId} mono />
            <Field label="Imported" value={trace.completion.transactionsImported ?? '—'} />
            <Field label="Skipped" value={trace.completion.transactionsSkipped ?? '—'} />
            <Field label="Imported at" value={timestamp(trace.completion.importedAt)} />
          </div>
        )}
      </Panel>
    </>
  );
}

export default function ImportTracePage() {
  // Which handle the operator holds decides the route, and they say so rather than the page
  // guessing. A reference and a job id are distinguishable by shape, but a page that inspects its
  // input can inspect it wrong, and the failure arrives as an unhelpful "not found" rather than as
  // a visibly wrong choice.
  const [handleType, setHandleType] = useState<'analysis' | 'job'>('analysis');
  const [input, setInput] = useState('');
  const [submitted, setSubmitted] = useState<{ type: 'analysis' | 'job'; value: string } | null>(null);

  const { data: trace, isFetching, error } = useQuery({
    queryKey: ['import-trace', submitted?.type, submitted?.value],
    queryFn: () => submitted!.type === 'analysis'
      ? adminImportTraceApi.byAnalysis(submitted!.value)
      : adminImportTraceApi.byJob(submitted!.value),
    enabled: submitted !== null,
    retry: false,
  });

  return (
    <AdminLayout
      title="Import Trace"
      subtitle="One import from upload through parsing, verification and learning to completion. No file name, no user — a document is referred to by its handle."
    >
      <RequirePermission permission="PLATFORM_DIAGNOSTICS_VIEW">
        <form
          className="bg-card border border-border rounded-xl2 p-5 mb-4"
          onSubmit={(e) => {
            e.preventDefault();
            const value = input.trim();
            if (value) setSubmitted({ type: handleType, value });
          }}
        >
          <div className="flex flex-wrap items-end gap-3">
            <div>
              <label htmlFor="handle-type" className="block text-[10px] uppercase tracking-wide text-muted mb-1">
                Handle
              </label>
              <select
                id="handle-type"
                value={handleType}
                onChange={(e) => setHandleType(e.target.value as 'analysis' | 'job')}
                className="bg-bg border border-border rounded-lg px-3 py-2 text-sm text-ink"
              >
                <option value="analysis">Analysis reference</option>
                <option value="job">Job id</option>
              </select>
            </div>
            <div className="flex-1 min-w-[240px]">
              {/* The label names the field and stays put; the example moves to the placeholder. A
                  label whose text changes with the mode changes the field's accessible name under
                  the user, which reads to a screen reader as a different control appearing. */}
              <label htmlFor="handle-value" className="block text-[10px] uppercase tracking-wide text-muted mb-1">
                Reference or job id
              </label>
              <input
                id="handle-value"
                value={input}
                onChange={(e) => setInput(e.target.value)}
                placeholder={handleType === 'analysis' ? 'SA-20260806-0145' : '0f8b1c2d-…'}
                className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm text-ink font-mono"
              />
            </div>
            <button
              type="submit"
              disabled={!input.trim() || isFetching}
              className="flex items-center gap-1.5 bg-primary text-white rounded-lg px-4 py-2 text-sm disabled:opacity-50"
            >
              <Search size={15} />
              {isFetching ? 'Looking…' : 'Trace'}
            </button>
          </div>
          <p className="text-xs text-muted mt-3">
            A support conversation produces a reference; the queue produces a job id. Either reaches
            the same trace, so whichever one you have is the right one.
          </p>
        </form>

        {error && (
          <div className="bg-card border border-border rounded-xl2 p-5 mb-4">
            <p className="text-sm text-danger">
              No trace for that handle. Check it against Layout Studio (references) or the import
              queue (job ids) — a mistyped handle and a genuinely absent import look the same here.
            </p>
          </div>
        )}

        {trace && <TraceView trace={trace} />}

        {!submitted && !trace && (
          <div className="bg-card border border-border rounded-xl2 p-8 text-center">
            <Route size={22} className="text-muted mx-auto mb-2" />
            <p className="text-sm text-muted">
              Enter a handle above to follow one import all the way through.
            </p>
          </div>
        )}
      </RequirePermission>
    </AdminLayout>
  );
}
