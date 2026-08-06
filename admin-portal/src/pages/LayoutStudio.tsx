import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  RefreshCw, FileSearch, AlertTriangle, CheckCircle2, Layers, Ruler, ChevronRight, ChevronDown, Upload,
} from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { useAdminAuth } from '../context/AdminAuthContext';
import { useNotify } from '../context/NotificationContext';
import { adminStatementAnalysisApi, adminAnalysisRunApi } from '../api/endpoints';
import type {
  StatementAnalysisDto, StatementAnalysisSummaryDto, UnanchoredReasons,
} from '../types';

/**
 * Layout Studio — the workbench over the analysis evidence table.
 *
 * <h2>What this page will not do</h2>
 * Show a number it does not have. Every figure here is read from a stored field; where the
 * workbench is designed to show something that is not recorded yet — parser version, per-section
 * verification findings, approval state — it says so by name instead of rendering a plausible
 * placeholder. A dashboard that displays "0" for "never measured" is worse than one that displays
 * nothing, because the zero looks like a finding and gets acted on.
 *
 * That distinction is not theoretical here: a capability was built, debugged and then deleted
 * because a histogram proved it never fired. The value of that histogram came entirely from it
 * being a real count.
 */

function formatDuration(ms: number | null) {
  if (ms == null) return '—';
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
}

function formatBytes(bytes: number | null) {
  if (bytes == null) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function formatWhen(iso: string) {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString();
}

/** Reads a reason code as a phrase without inventing meaning: NO_DATE_IN_ANCHOR → "no date in anchor". */
function humanizeReason(reason: string) {
  return reason.toLowerCase().replace(/_/g, ' ');
}

function OutcomeBadge({ outcome, failureCode }: { outcome: string; failureCode: string | null }) {
  const failed = outcome === 'FAILED';
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ${
        failed ? 'text-danger bg-danger-bg' : 'text-success bg-success-bg'
      }`}
    >
      {failed ? <AlertTriangle size={11} /> : <CheckCircle2 size={11} />}
      {failed ? failureCode ?? 'FAILED' : 'PARSED'}
    </span>
  );
}

/**
 * The reason histogram, as bars.
 *
 * The single most useful panel on the page, and the reason it is a histogram rather than a list of
 * reason names: one occurrence in a 2500-line document and two thousand occurrences are the same
 * entry in a set, and only the proportion says whether a missing capability or one odd document is
 * responsible.
 */
function ReasonHistogram({ reasons, emptyLabel }: { reasons: UnanchoredReasons; emptyLabel: string }) {
  const entries = Object.entries(reasons);
  if (entries.length === 0) {
    return <p className="text-sm text-muted px-4 py-3">{emptyLabel}</p>;
  }
  const largest = Math.max(...entries.map(([, count]) => count));

  return (
    <ul className="divide-y divide-border">
      {entries.map(([reason, count]) => (
        <li key={reason} className="px-4 py-3">
          <div className="flex items-baseline justify-between gap-3 mb-1.5">
            <span className="text-sm text-ink font-mono truncate" title={reason}>{humanizeReason(reason)}</span>
            <span className="text-sm font-semibold text-ink tabular-nums flex-shrink-0">
              {count.toLocaleString()}
              <span className="sr-only"> rows</span>
            </span>
          </div>
          <div className="h-1.5 rounded-full bg-bg overflow-hidden" aria-hidden="true">
            <div className="h-full rounded-full bg-primary" style={{ width: `${(count / largest) * 100}%` }} />
          </div>
        </li>
      ))}
    </ul>
  );
}

function SummaryStrip({ summary }: { summary: StatementAnalysisSummaryDto }) {
  const cells: { label: string; value: string; hint?: string }[] = [
    { label: 'Analyses', value: summary.totalAnalysesEver.toLocaleString(), hint: 'Every upload attempt ever recorded.' },
    { label: 'Parsed', value: summary.parsed.toLocaleString() },
    { label: 'Failed', value: summary.failed.toLocaleString() },
    { label: 'Distinct layouts', value: summary.distinctLayouts.toLocaleString(), hint: 'Unique fingerprints seen.' },
    {
      label: 'Rows extracted',
      value: summary.rowsExtractedInWindow.toLocaleString(),
      hint: `Across the last ${summary.analysesInWindow} analyses.`,
    },
    {
      label: 'Rows unanchored',
      value: summary.unanchoredRowsInWindow.toLocaleString(),
      hint: 'Lines that could not become transactions. Read against rows extracted, not on its own.',
    },
  ];

  return (
    <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-px bg-border border border-border rounded-xl2 overflow-hidden shadow-card">
      {cells.map((cell) => (
        <div key={cell.label} className="bg-card px-4 py-3" title={cell.hint}>
          <p className="text-xs text-muted uppercase tracking-wide">{cell.label}</p>
          <p className="text-lg font-semibold text-ink tabular-nums mt-0.5">{cell.value}</p>
        </div>
      ))}
    </div>
  );
}

function AnalysisDetailPanel({ reference }: { reference: string }) {
  const [rawOpen, setRawOpen] = useState(false);
  const { data, isLoading, isError } = useQuery({
    queryKey: ['admin-analysis', reference],
    queryFn: () => adminStatementAnalysisApi.byReference(reference),
  });

  if (isLoading) return <p className="text-sm text-muted">Loading {reference}…</p>;
  if (isError || !data) {
    return (
      <p className="text-sm text-danger bg-danger-bg rounded-lg px-3.5 py-2.5">
        Couldn&apos;t load {reference} — please try again.
      </p>
    );
  }

  const { analysis, timesLayoutSeen, timesLayoutFailed } = data;

  return (
    <div className="space-y-4">
      <div className="bg-card border border-border rounded-xl2 shadow-card divide-y divide-border">
        <DetailRow label="Analysis" value={analysis.reference} mono />
        <DetailRow label="Fingerprint" value={analysis.layoutFingerprint ?? 'Not characterised'} mono
          hint={analysis.layoutFingerprint ? undefined : 'The document failed before its structure could be identified — an encrypted PDF with the wrong password never gets that far.'} />
        <DetailRow label="Format" value={analysis.sourceFormat ?? '—'} />
        <DetailRow label="Outcome" value={analysis.outcome === 'FAILED' ? (analysis.failureCode ?? 'FAILED') : 'PARSED'} />
        <DetailRow
          label="Rows extracted"
          value={analysis.rowCount == null ? 'Never measured' : analysis.rowCount.toLocaleString()}
          hint={analysis.rowCount == null
            ? 'Not the same as zero: this document failed before extraction was attempted.'
            : undefined}
        />
        <DetailRow label="Rows unanchored" value={analysis.unanchoredRowCount.toLocaleString()} />
        <DetailRow label="Sections" value={analysis.sectionCount == null ? '—' : String(analysis.sectionCount)} />
        <DetailRow label="Duration" value={formatDuration(analysis.durationMs)} />
        <DetailRow label="Size" value={formatBytes(analysis.byteSize)} />
        <DetailRow label="Recorded" value={formatWhen(analysis.createdAt)} />
      </div>

      {analysis.layoutFingerprint && (
        <section aria-labelledby="layout-history-heading" className="bg-card border border-border rounded-xl2 shadow-card">
          <h3 id="layout-history-heading" className="text-sm font-semibold text-ink px-4 pt-3">This layout</h3>
          <p className="text-sm text-muted px-4 pb-3 pt-1">
            Seen <strong className="text-ink tabular-nums">{timesLayoutSeen.toLocaleString()}</strong>{' '}
            {timesLayoutSeen === 1 ? 'time' : 'times'}, of which{' '}
            <strong className="text-ink tabular-nums">{timesLayoutFailed.toLocaleString()}</strong>{' '}
            defeated the parser. {timesLayoutSeen === 1
              ? 'First sighting — there is nothing yet to compare it against.'
              : 'Read the two together: eleven failures out of twelve is a layout the engine cannot read; one out of twelve is a single odd document.'}
          </p>
        </section>
      )}

      <section aria-labelledby="diagnostics-heading" className="bg-card border border-border rounded-xl2 shadow-card overflow-hidden">
        <h3 id="diagnostics-heading" className="text-sm font-semibold text-ink px-4 pt-3 pb-1">
          Why rows did not anchor
        </h3>
        <ReasonHistogram
          reasons={analysis.unanchoredReasons}
          emptyLabel="Every row anchored — nothing was left unexplained in this document."
        />
      </section>

      <section className="bg-card border border-border rounded-xl2 shadow-card overflow-hidden">
        <button
          type="button"
          onClick={() => setRawOpen((open) => !open)}
          aria-expanded={rawOpen}
          className="w-full flex items-center gap-2 px-4 py-3 text-sm font-medium text-ink hover:bg-bg"
        >
          {rawOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
          Raw evidence
        </button>
        {rawOpen && (
          <pre className="px-4 pb-4 text-xs font-mono text-muted overflow-x-auto">
            {JSON.stringify(data, null, 2)}
          </pre>
        )}
      </section>
    </div>
  );
}

function DetailRow({ label, value, mono, hint }: { label: string; value: string; mono?: boolean; hint?: string }) {
  return (
    <div className="flex items-center gap-3 px-4 py-2.5" title={hint}>
      <span className="text-sm text-muted flex-1">{label}</span>
      <span className={`text-sm text-ink text-right truncate max-w-[60%] ${mono ? 'font-mono' : ''}`}>{value}</span>
    </div>
  );
}

function AnalysisTable({
  analyses, selected, onSelect,
}: {
  analyses: StatementAnalysisDto[];
  selected: string | null;
  onSelect: (reference: string) => void;
}) {
  if (analyses.length === 0) {
    return (
      <p className="text-sm text-muted bg-card border border-border rounded-xl2 px-4 py-6 text-center">
        No statements have been analysed yet. Every upload — successful or not — appears here.
      </p>
    );
  }

  return (
    <div className="bg-card border border-border rounded-xl2 shadow-card overflow-x-auto">
      <table className="w-full text-sm">
        <caption className="sr-only">Recent statement analyses, newest first</caption>
        <thead>
          <tr className="text-left text-xs text-muted uppercase tracking-wide border-b border-border">
            <th scope="col" className="px-4 py-2.5 font-medium">Analysis</th>
            <th scope="col" className="px-4 py-2.5 font-medium">Layout</th>
            <th scope="col" className="px-4 py-2.5 font-medium">Outcome</th>
            <th scope="col" className="px-4 py-2.5 font-medium text-right">Rows</th>
            <th scope="col" className="px-4 py-2.5 font-medium text-right">Unanchored</th>
            <th scope="col" className="px-4 py-2.5 font-medium text-right">Duration</th>
            <th scope="col" className="px-4 py-2.5 font-medium">Recorded</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {analyses.map((analysis) => (
            <tr
              key={analysis.reference}
              className={selected === analysis.reference ? 'bg-bg' : undefined}
            >
              <th scope="row" className="px-4 py-2.5 font-normal text-left">
                <button
                  type="button"
                  onClick={() => onSelect(analysis.reference)}
                  aria-current={selected === analysis.reference ? 'true' : undefined}
                  className="font-mono text-primary hover:underline"
                >
                  {analysis.reference}
                </button>
              </th>
              <td className="px-4 py-2.5 font-mono text-muted">{analysis.layoutFingerprint ?? '—'}</td>
              <td className="px-4 py-2.5">
                <OutcomeBadge outcome={analysis.outcome} failureCode={analysis.failureCode} />
              </td>
              {/* "Never measured" is rendered as an em dash, never as 0 -- a document that failed
                  before extraction and one that extracted nothing lead to different investigations. */}
              <td className="px-4 py-2.5 text-right tabular-nums text-ink">
                {analysis.rowCount == null ? '—' : analysis.rowCount.toLocaleString()}
              </td>
              <td className="px-4 py-2.5 text-right tabular-nums text-muted">
                {analysis.unanchoredRowCount.toLocaleString()}
              </td>
              <td className="px-4 py-2.5 text-right tabular-nums text-muted">{formatDuration(analysis.durationMs)}</td>
              <td className="px-4 py-2.5 text-muted whitespace-nowrap">{formatWhen(analysis.createdAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * What the workbench is designed to show and cannot yet.
 *
 * Named explicitly rather than mocked up, because a placeholder that looks like data is the one
 * failure mode this page exists to avoid. Each line is a field nothing writes today.
 */
function NotYetRecorded() {
  const pending = [
    ['Parser version', 'No parse records which build produced it, so a before/after timeline across engine versions cannot be drawn.'],
    ['Verification findings', 'Balance chain, statement totals and summary totals are checked during import but the per-check results are not persisted.'],
    ['Per-section rows', 'Only the section COUNT is stored, so a two-section statement cannot show which section produced which rows.'],
    ['Largest cell', 'Not captured anywhere; it was measured with a temporary probe.'],
    ['Approval state', 'Approving a layout creates knowledge, which is a separate curated table that does not exist yet.'],
  ];

  return (
    <section aria-labelledby="pending-heading" className="bg-card border border-border rounded-xl2 shadow-card">
      <div className="flex items-center gap-2 px-4 pt-3">
        <Ruler size={14} className="text-muted" />
        <h2 id="pending-heading" className="text-sm font-semibold text-ink">Not recorded yet</h2>
      </div>
      <p className="text-sm text-muted px-4 pt-1">
        Listed by name rather than shown as empty tiles — a placeholder that looks like a
        measurement is worse than an absent one, because it gets acted on.
      </p>
      <dl className="divide-y divide-border mt-2">
        {pending.map(([field, why]) => (
          <div key={field} className="px-4 py-2.5">
            <dt className="text-sm text-ink">{field}</dt>
            <dd className="text-sm text-muted">{why}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

/** IMPORT_008 = encrypted, no password given. IMPORT_009 = password given and wrong. */
const PASSWORD_CODES = ['IMPORT_008', 'IMPORT_009'];

/**
 * Run the engine on a document without importing it.
 *
 * <p>The change that turns this page from a viewer into a workbench: until now every analysis in
 * the table arrived because a real customer uploaded something. An engineer studying a layout had
 * to find a statement, write a probe and read a console.
 *
 * <p>Nothing is imported and nothing is stored — not the file, not the transactions, not even the
 * merchants the parser resolves on the way through. See AdminAnalysisService for how that is
 * enforced, which is less obvious than it looks.
 */
function AnalysisUploadPanel({ onAnalysed }: { onAnalysed: (reference: string) => void }) {
  const [file, setFile] = useState<File | null>(null);
  const [password, setPassword] = useState('');
  const [needsPassword, setNeedsPassword] = useState(false);
  const queryClient = useQueryClient();
  const notify = useNotify();

  const run = useMutation({
    mutationFn: () => adminAnalysisRunApi.analyze(file as File, password || undefined),
    onSuccess: (detail) => {
      const { outcome, failureCode, reference } = detail.analysis;

      // A failed parse is a successful analysis -- the backend returns 200 with a FAILED row on
      // purpose, so the admin gets the evidence link precisely when the engine could not read the
      // document. The one failure worth interrupting for is a password, because that one the
      // admin can actually fix and retry.
      if (outcome === 'FAILED' && failureCode && PASSWORD_CODES.includes(failureCode)) {
        setNeedsPassword(true);
        notify.error('This document is encrypted. Enter its password and analyse again.');
      } else if (outcome === 'FAILED') {
        notify.error(`Analysed — the engine could not read this document (${failureCode}).`);
      } else {
        notify.success(`Analysed as ${reference}.`);
        setNeedsPassword(false);
      }

      setPassword('');
      void queryClient.invalidateQueries({ queryKey: ['admin-analyses'] });
      void queryClient.invalidateQueries({ queryKey: ['admin-analyses-summary'] });
      onAnalysed(reference);
    },
    onError: () => notify.error("Couldn't analyse that document — please try again."),
  });

  return (
    <section aria-labelledby="upload-heading" className="bg-card border border-border rounded-xl2 shadow-card p-4">
      <div className="flex items-center gap-2">
        <Upload size={14} className="text-primary" />
        <h2 id="upload-heading" className="text-sm font-semibold text-ink">Analyse a statement</h2>
      </div>
      <p className="text-sm text-muted mt-1">
        Runs the real engine and imports nothing — no account, no transactions, and the file itself
        is not stored. Leaves a permanent analysis you can link to.
      </p>

      <div className="flex flex-wrap items-end gap-3 mt-3">
        <div>
          <label htmlFor="analysis-file" className="block text-xs text-muted mb-1">Statement (PDF or CSV)</label>
          <input
            id="analysis-file"
            type="file"
            accept=".pdf,.csv"
            onChange={(event) => {
              setFile(event.target.files?.[0] ?? null);
              setNeedsPassword(false);
            }}
            className="text-sm text-ink file:mr-3 file:rounded-lg file:border file:border-border file:bg-bg file:px-3 file:py-1.5 file:text-sm file:text-ink"
          />
        </div>

        {needsPassword && (
          <div>
            <label htmlFor="analysis-password" className="block text-xs text-muted mb-1">Document password</label>
            <input
              id="analysis-password"
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete="off"
              className="text-sm text-ink border border-border rounded-lg px-3 py-1.5 bg-card"
            />
          </div>
        )}

        <button
          type="button"
          onClick={() => run.mutate()}
          disabled={!file || run.isPending}
          className="inline-flex items-center gap-1.5 text-sm font-medium text-white bg-primary rounded-lg px-3.5 py-2 disabled:opacity-50"
        >
          {run.isPending ? <RefreshCw size={14} className="animate-spin" /> : <Upload size={14} />}
          {run.isPending ? 'Analysing…' : 'Analyse'}
        </button>
      </div>
    </section>
  );
}

function LayoutStudioContent() {
  const [selected, setSelected] = useState<string | null>(null);
  const { hasPermission } = useAdminAuth();

  const summary = useQuery({
    queryKey: ['admin-analyses-summary'],
    queryFn: () => adminStatementAnalysisApi.summary(),
  });
  const analyses = useQuery({
    queryKey: ['admin-analyses'],
    queryFn: () => adminStatementAnalysisApi.recent(50),
  });

  const isLoading = summary.isLoading || analyses.isLoading;
  const isError = summary.isError || analyses.isError;
  const isFetching = summary.isFetching || analyses.isFetching;

  function refetchAll() {
    void summary.refetch();
    void analyses.refetch();
  }

  if (isLoading) return <p className="text-muted text-sm">Loading…</p>;
  if (isError || !summary.data || !analyses.data) {
    return (
      <p className="text-sm text-danger bg-danger-bg rounded-lg px-3.5 py-2.5">
        Couldn&apos;t load statement analyses — please try again later.
      </p>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex justify-end">
        <button
          type="button"
          onClick={refetchAll}
          disabled={isFetching}
          className="inline-flex items-center gap-1.5 text-sm font-medium text-ink border border-border rounded-lg px-3.5 py-2 hover:bg-bg disabled:opacity-50"
        >
          <RefreshCw size={14} className={isFetching ? 'animate-spin' : ''} /> Refresh
        </button>
      </div>

      {hasPermission('ENGINE_ANALYSIS_RUN') && <AnalysisUploadPanel onAnalysed={setSelected} />}

      <SummaryStrip summary={summary.data} />

      <div className="grid gap-6 lg:grid-cols-2">
        <section aria-labelledby="engine-reasons-heading" className="bg-card border border-border rounded-xl2 shadow-card overflow-hidden">
          <div className="flex items-center gap-2 px-4 pt-3">
            <Layers size={14} className="text-primary" />
            <h2 id="engine-reasons-heading" className="text-sm font-semibold text-ink">
              Why rows did not anchor, across the last {summary.data.analysesInWindow}
            </h2>
          </div>
          <p className="text-sm text-muted px-4 pt-1 pb-2">
            One reason dominating across many documents describes a missing capability. The same
            reason confined to a single document describes that document.
          </p>
          <ReasonHistogram
            reasons={summary.data.unanchoredReasons}
            emptyLabel="Every row anchored in every analysed document."
          />
        </section>

        <NotYetRecorded />
      </div>

      <section aria-labelledby="analyses-heading" className="space-y-3">
        <div className="flex items-center gap-2">
          <FileSearch size={16} className="text-primary" />
          <h2 id="analyses-heading" className="text-sm font-semibold text-muted uppercase tracking-wide">
            Recent analyses
          </h2>
        </div>
        <AnalysisTable analyses={analyses.data} selected={selected} onSelect={setSelected} />
      </section>

      {selected && (
        <section aria-labelledby="selected-heading" className="space-y-3">
          <h2 id="selected-heading" className="text-sm font-semibold text-muted uppercase tracking-wide">
            {selected}
          </h2>
          <AnalysisDetailPanel reference={selected} />
        </section>
      )}
    </div>
  );
}

export default function LayoutStudio() {
  return (
    <AdminLayout
      title="Layout Studio"
      subtitle="Every upload attempt, successful or not -- what the engine read, and why it could not read the rest"
    >
      <RequirePermission permission="PLATFORM_DIAGNOSTICS_VIEW">
        <LayoutStudioContent />
      </RequirePermission>
    </AdminLayout>
  );
}
