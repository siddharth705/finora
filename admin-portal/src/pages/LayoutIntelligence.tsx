import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { RefreshCw, AlertTriangle, Columns3, ScrollText, Scale } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { adminLayoutsApi } from '../api/endpoints';
import type { LayoutSummary, LayoutEvidenceReport, UnknownHeaderSummary } from '../types';

/**
 * Layout Intelligence — the read side of the layout fingerprint every import has recorded since
 * V39 (docs/engineering/layout-intelligence-proposal.md).
 *
 * <h2>Why this page exists</h2>
 * The backend for this shipped complete — aggregation, drift detection, unknown-header rollups and
 * an evidence report — and no client ever called it. The proposal was written because the
 * fingerprint column was write-only; the service that fixed that then became a service nothing
 * called, one layer up. This is the reader.
 *
 * <h2>What it is for</h2>
 * The proposal gates structural learning ("teach the parser to reuse layouts") on three
 * preconditions, and the third is <em>evidence that recurring layouts would benefit from reuse</em>.
 * That evidence is computed by `LayoutIntelligenceService.evidenceReport()` and, until now, could
 * not be read by anyone. The Evidence panel below is therefore not a nice-to-have summary — it is
 * the instrument that decides whether the next phase of import work happens at all.
 *
 * <h2>What it deliberately does not do</h2>
 * No thresholds, no scoring, no "healthy/unhealthy" badges, no recommended actions. `Confidence` as
 * a live metric does not exist yet and is gated behind Phase 3 of the principles doc. A drift row
 * here says "this changed", never "this is wrong" — because a layout legitimately varies between
 * statements, and calling that a fault would train an operator to ignore the signal.
 */

/** ms → a duration a human reads without counting digits. Null is "not measured", never 0. */
function duration(ms: number | null): string {
  if (ms === null || ms === undefined) return 'Not measured';
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
}

/**
 * Renders a nullable average without inventing a value.
 *
 * `?? 0` here would be the whole failure this page guards against: the backend omits a figure when
 * there is not enough data to compute it, and a UI that renders that omission as "0.0" closes a
 * question with a number nobody earned. `EvidenceReport`'s own doc comment says so explicitly.
 */
function average(value: number | null): string {
  if (value === null || value === undefined) return 'Not measured';
  return value.toFixed(2);
}

function shortDate(iso: string): string {
  return new Date(iso).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
}

/**
 * The panel this page was built for.
 *
 * Leads with the verdict in words rather than the table, because the conclusion this report most
 * often licenses is "no evidence for reuse" — and a bare table of near-identical numbers invites
 * whoever reads it to supply a more encouraging conclusion than the data supports.
 */
function EvidencePanel({ report }: { report: LayoutEvidenceReport }) {
  const rows: Array<{ label: string; first: string; recurring: string }> = [
    {
      label: 'Median import duration',
      first: duration(report.medianDurationFirstEncounter),
      recurring: duration(report.medianDurationRecurring),
    },
    {
      label: 'Avg. unknown headers',
      first: average(report.avgUnknownHeadersFirstEncounter),
      recurring: average(report.avgUnknownHeadersRecurring),
    },
    {
      label: 'Avg. skipped rows',
      first: average(report.avgSkippedRowsFirstEncounter),
      recurring: average(report.avgSkippedRowsRecurring),
    },
  ];

  return (
    <section className="bg-card border border-border rounded-xl2 p-5 mb-6">
      <div className="flex items-center gap-2 mb-1">
        <Scale size={16} className="text-primary" />
        <h2 className="font-semibold text-ink">Is layout reuse worth building?</h2>
      </div>
      <p className="text-xs text-muted mb-4">
        First encounters of a layout versus every later import of the same layout. If recurrence
        brought no benefit, the two columns look the same — which is a conclusive result, not a
        missing one.
      </p>

      <p className="text-sm text-ink bg-bg border border-border rounded-lg p-3 mb-4">{report.verdict}</p>

      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-4 text-sm">
        <div><p className="text-muted text-xs">Imports analysed</p><p className="font-semibold text-ink">{report.totalImportsAnalysed}</p></div>
        <div><p className="text-muted text-xs">Distinct layouts</p><p className="font-semibold text-ink">{report.distinctLayouts}</p></div>
        <div><p className="text-muted text-xs">Recurring layouts</p><p className="font-semibold text-ink">{report.recurringLayouts}</p></div>
        <div><p className="text-muted text-xs">Imports on those</p><p className="font-semibold text-ink">{report.importsOnRecurringLayouts}</p></div>
      </div>

      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-[10px] uppercase text-muted border-b border-border">
              <th className="py-2 pr-3">Measure</th>
              <th className="py-2 pr-3">First encounter</th>
              <th className="py-2">Recurrence</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.label} className="border-b border-border last:border-0">
                <td className="py-2 pr-3 text-muted">{r.label}</td>
                <td className="py-2 pr-3 text-ink">{r.first}</td>
                <td className="py-2 text-ink">{r.recurring}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function LayoutRow({ layout }: { layout: LayoutSummary }) {
  const recurring = layout.usageCount > 1;
  return (
    <tr className="border-b border-border last:border-0 align-top">
      <td className="py-2 pr-3 font-mono text-xs text-ink whitespace-nowrap">{layout.fingerprint}</td>
      <td className="py-2 pr-3 text-muted">{layout.sourceFormat}</td>
      <td className="py-2 pr-3 text-ink">{layout.columns}</td>
      <td className="py-2 pr-3 text-ink">
        {layout.usageCount}
        {/* A layout seen once teaches nothing about stability. Saying so beats presenting a single
            observation as a trend, which is what an unqualified count invites. */}
        {!recurring && <span className="text-muted text-xs"> (seen once)</span>}
      </td>
      <td className="py-2 pr-3 text-muted whitespace-nowrap">{duration(layout.medianDurationMs)}</td>
      <td className="py-2 pr-3">
        {layout.unstableCapabilities.length === 0 ? (
          <span className="text-muted text-xs">—</span>
        ) : (
          <span className="text-xs text-warning">{layout.unstableCapabilities.join(', ')}</span>
        )}
      </td>
      <td className="py-2 text-muted text-xs whitespace-nowrap">{shortDate(layout.lastSeen)}</td>
    </tr>
  );
}

function UnknownHeaderRow({ header }: { header: UnknownHeaderSummary }) {
  // layoutCount > 1 is the signal worth acting on: a header appearing across several DISTINCT
  // layouts is a gap in TransactionNormalizer's hint lists rather than one bank's quirk.
  const spansLayouts = header.layoutCount > 1;
  return (
    <tr className="border-b border-border last:border-0">
      <td className="py-2 pr-3 font-mono text-xs text-ink">{header.header}</td>
      <td className="py-2 pr-3 text-ink">{header.importCount}</td>
      <td className="py-2 pr-3">
        <span className={spansLayouts ? 'text-warning font-medium' : 'text-ink'}>{header.layoutCount}</span>
      </td>
      <td className="py-2 text-muted text-xs">
        {spansLayouts ? 'Spans layouts — likely a hint-list gap' : 'Single layout'}
      </td>
    </tr>
  );
}

export default function LayoutIntelligence() {
  const [tab, setTab] = useState<'layouts' | 'drifting' | 'headers'>('layouts');

  // Independent queries, so one failing report degrades to one empty section instead of blanking
  // the page -- same reasoning the dashboards already use.
  const evidenceQ = useQuery({ queryKey: ['layout-evidence'], queryFn: () => adminLayoutsApi.evidence() });
  const overviewQ = useQuery({ queryKey: ['layout-overview'], queryFn: () => adminLayoutsApi.overview() });
  const driftingQ = useQuery({ queryKey: ['layout-drifting'], queryFn: () => adminLayoutsApi.drifting() });
  const headersQ = useQuery({ queryKey: ['layout-unknown-headers'], queryFn: () => adminLayoutsApi.unknownHeaders() });

  const refetchAll = () => {
    void evidenceQ.refetch();
    void overviewQ.refetch();
    void driftingQ.refetch();
    void headersQ.refetch();
  };

  const layouts = tab === 'drifting' ? (driftingQ.data ?? []) : (overviewQ.data ?? []);
  const listQ = tab === 'drifting' ? driftingQ : overviewQ;

  return (
    <AdminLayout
      title="Layout Intelligence"
      subtitle="What the import engine has learned about the documents it reads. Aggregated by structural fingerprint — no user, account, bank or transaction appears here."
    >
      <RequirePermission permission="PLATFORM_DIAGNOSTICS_VIEW">
        <div className="flex items-center justify-end mb-4">
          <button
            type="button"
            onClick={refetchAll}
            className="flex items-center gap-1.5 text-sm text-primary"
          >
            <RefreshCw size={14} /> Refresh
          </button>
        </div>

        {evidenceQ.isError && (
          <p className="text-sm text-danger mb-4">Couldn't load the evidence report.</p>
        )}
        {evidenceQ.data && <EvidencePanel report={evidenceQ.data} />}

        <div className="flex gap-1 mb-3 border-b border-border">
          {([
            ['layouts', 'All layouts', Columns3],
            ['drifting', 'Drifting', AlertTriangle],
            ['headers', 'Unknown headers', ScrollText],
          ] as const).map(([key, label, Icon]) => (
            <button
              key={key}
              type="button"
              onClick={() => setTab(key)}
              className={`flex items-center gap-1.5 px-3 py-2 text-sm border-b-2 -mb-px ${
                tab === key ? 'border-primary text-primary font-medium' : 'border-transparent text-muted'
              }`}
            >
              <Icon size={14} /> {label}
            </button>
          ))}
        </div>

        {tab === 'drifting' && (
          <p className="text-xs text-muted mb-3">
            Layouts whose most recent import differs structurally from the pattern before it. This
            says something changed — not that it is wrong. A statement can legitimately vary between
            months.
          </p>
        )}

        <div className="bg-card border border-border rounded-xl2 overflow-x-auto">
          {tab === 'headers' ? (
            headersQ.isLoading ? (
              <p className="p-4 text-sm text-muted">Loading…</p>
            ) : (headersQ.data ?? []).length === 0 ? (
              <p className="p-4 text-sm text-muted italic">
                No unrecognised headers. Every column the engine has seen matched a hint list.
              </p>
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-[10px] uppercase text-muted border-b border-border">
                    <th className="p-3">Header</th>
                    <th className="p-3">Imports</th>
                    <th className="p-3">Layouts</th>
                    <th className="p-3">Signal</th>
                  </tr>
                </thead>
                <tbody>
                  {(headersQ.data ?? []).map((h) => <UnknownHeaderRow key={h.header} header={h} />)}
                </tbody>
              </table>
            )
          ) : listQ.isLoading ? (
            <p className="p-4 text-sm text-muted">Loading…</p>
          ) : layouts.length === 0 ? (
            <p className="p-4 text-sm text-muted italic">
              {tab === 'drifting'
                ? 'No layout has changed structurally between imports.'
                : 'No imports carry a layout fingerprint yet.'}
            </p>
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-[10px] uppercase text-muted border-b border-border">
                  <th className="p-3">Fingerprint</th>
                  <th className="p-3">Format</th>
                  <th className="p-3">Cols</th>
                  <th className="p-3">Imports</th>
                  <th className="p-3">Median</th>
                  <th className="p-3">Unstable capabilities</th>
                  <th className="p-3">Last seen</th>
                </tr>
              </thead>
              <tbody>
                {layouts.map((l) => <LayoutRow key={l.fingerprint} layout={l} />)}
              </tbody>
            </table>
          )}
        </div>
      </RequirePermission>
    </AdminLayout>
  );
}
