import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Stethoscope, RefreshCw, FileStack, AlertTriangle, CheckCircle2, GitCommit, Tag,
  Cpu, Database, ToggleLeft, ExternalLink, Clipboard, Check,
} from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { adminDiagnosticsApi } from '../api/endpoints';
import type { PlatformDiagnosticsDto } from '../types';

function formatUptime(seconds: number) {
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  if (days > 0) return `${days}d ${hours}h`;
  const minutes = Math.floor((seconds % 3600) / 60);
  if (hours > 0) return `${hours}h ${minutes}m`;
  return `${minutes}m`;
}

function statusColor(status: string) {
  if (status === 'UP') return 'text-success bg-success-bg';
  if (status === 'DOWN') return 'text-danger bg-danger-bg';
  return 'text-warning bg-warning-bg';
}

// Neither of these goes through Vite's dev-server proxy (that only forwards /api/** to the
// backend -- see vite.config.ts), and swagger-ui.html/actuator both live outside /api entirely
// on the backend itself, so these need the backend's own origin, not a relative path.
// VITE_BACKEND_ORIGIN is a new env var (not used anywhere else in this app -- every other call
// goes through relative /api paths, which works in production because everything's assumed to
// sit behind the same reverse-proxy origin there). Falls back to localhost:8080 for local dev
// when unset, matching vite.config.ts's own proxy target default.
const BACKEND_ORIGIN = import.meta.env.VITE_BACKEND_ORIGIN ?? 'http://localhost:8080';
const EXTERNAL_LINKS = [
  { href: `${BACKEND_ORIGIN}/swagger-ui.html`, label: 'Swagger / OpenAPI' },
  { href: `${BACKEND_ORIGIN}/actuator/health`, label: 'Spring Boot Actuator' },
];

/** Plain-text summary formatted for pasting into Slack/a support ticket -- exactly the "what's
 *  your Spring profile / Flyway version / uptime" back-and-forth this page exists to shortcut.
 *  Deliberately excludes recentImports (per-user emails) -- everything here is
 *  environment/build metadata, nothing that identifies a specific person's data. */
function formatDiagnosticsSummary(data: PlatformDiagnosticsDto): string {
  return [
    'Finora Platform Diagnostics',
    `Version: ${data.application.version ?? 'Not available'}`,
    `Git commit: ${data.application.gitCommit ?? 'Not available'}`,
    `Spring profile: ${data.application.springProfile}`,
    `Uptime: ${formatUptime(data.runtime.uptimeSeconds)}`,
    `Flyway version: ${data.runtime.flywayVersion}`,
    `Cache: ${data.runtime.cacheEnabled ? 'Enabled' : 'Disabled'}`,
    `Overall health: ${data.health.overallStatus}`,
    ...data.health.providers.map((p) => `  - ${p.name}: ${p.status}`),
    `Registrations enabled: ${data.configuration.registrationsEnabled ? 'Yes' : 'No'}`,
    `Setup completed: ${data.configuration.setupCompleted ? 'Yes' : 'No'}`,
    `Phone verification: ${data.configuration.phoneVerificationPolicy}`,
  ].join('\n');
}

function CopyDiagnosticsButton({ data }: { data: PlatformDiagnosticsDto }) {
  const [copied, setCopied] = useState(false);

  async function handleCopy() {
    await navigator.clipboard.writeText(formatDiagnosticsSummary(data));
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  return (
    <button
      type="button"
      onClick={handleCopy}
      className="inline-flex items-center gap-1.5 text-sm font-medium text-ink border border-border rounded-lg px-3.5 py-2 hover:bg-bg"
    >
      {copied ? <Check size={14} className="text-success" /> : <Clipboard size={14} />}
      {copied ? 'Copied' : 'Copy diagnostics'}
    </button>
  );
}

/**
 * Platform Diagnostics -- a lightweight page for developers/support, explicitly not an
 * observability platform. Every figure here is either reused as-is from an existing real
 * endpoint (health providers, recent imports) or a small genuine backend addition (build/git
 * info, Flyway version, cache presence) -- see DiagnosticsDto's backend doc comment for the full
 * reasoning and what's deliberately out of scope (log aggregation, distributed tracing, exception
 * management -- all remain the job of dedicated tools like Grafana/Loki/Sentry when the platform
 * reaches that phase of the roadmap, not something this page tries to replace).
 */
function DiagnosticsContent() {
  const { data, isLoading, refetch, isFetching } = useQuery({
    queryKey: ['admin-diagnostics'],
    queryFn: () => adminDiagnosticsApi.overview(),
  });

  if (isLoading) return <p className="text-muted text-sm">Loading…</p>;
  if (!data) return null;

  return (
    <div className="space-y-6">
      <div className="flex justify-end gap-2">
        <CopyDiagnosticsButton data={data} />
        <button
          type="button"
          onClick={() => refetch()}
          disabled={isFetching}
          className="inline-flex items-center gap-1.5 text-sm font-medium text-ink border border-border rounded-lg px-3.5 py-2 hover:bg-bg disabled:opacity-50"
        >
          <RefreshCw size={14} className={isFetching ? 'animate-spin' : ''} /> Refresh
        </button>
      </div>

      <div className="grid gap-6 md:grid-cols-2">
        <div>
          <div className="flex items-center gap-2 mb-3">
            <Tag size={16} className="text-primary" />
            <h2 className="text-sm font-semibold text-muted uppercase tracking-wide">Application</h2>
          </div>
          <div className="bg-card border border-border rounded-xl2 shadow-card divide-y divide-border">
            <Row
              label="Version"
              value={data.application.version ?? 'Not available'}
              hint={data.application.version ? undefined : 'Build metadata unavailable for this runtime -- see pom.xml\'s build-info plugin.'}
            />
            <Row
              label="Git commit"
              value={data.application.gitCommit ?? 'Not available'}
              icon={GitCommit}
              hint={data.application.gitCommit ? undefined : 'Git metadata unavailable for this runtime -- see pom.xml\'s git-commit-id plugin.'}
            />
            <Row label="Spring profile" value={data.application.springProfile} />
          </div>
        </div>

        <div>
          <div className="flex items-center gap-2 mb-3">
            <Cpu size={16} className="text-primary" />
            <h2 className="text-sm font-semibold text-muted uppercase tracking-wide">Runtime</h2>
          </div>
          <div className="bg-card border border-border rounded-xl2 shadow-card divide-y divide-border">
            <Row label="Uptime" value={formatUptime(data.runtime.uptimeSeconds)} />
            <Row label="Flyway migration version" value={data.runtime.flywayVersion} icon={Database} />
            <Row label="Cache" value={data.runtime.cacheEnabled ? 'Enabled' : 'Disabled'} />
          </div>
        </div>

        <div>
          <div className="flex items-center gap-2 mb-3">
            <ToggleLeft size={16} className="text-primary" />
            <h2 className="text-sm font-semibold text-muted uppercase tracking-wide">Configuration</h2>
          </div>
          <div className="bg-card border border-border rounded-xl2 shadow-card divide-y divide-border">
            <Row label="Registrations enabled" value={data.configuration.registrationsEnabled ? 'Yes' : 'No'} />
            <Row label="Setup completed" value={data.configuration.setupCompleted ? 'Yes' : 'No'} />
            {/* Deliberately not rendered as a Yes/No toggle -- see ADR-0001: this is a fixed
                policy today, not a configurable setting, and the value itself says so. */}
            <Row label="Phone verification" value={data.configuration.phoneVerificationPolicy} />
          </div>
        </div>

        <div>
          <div className="flex items-center gap-2 mb-3">
            <Stethoscope size={16} className="text-primary" />
            <h2 className="text-sm font-semibold text-muted uppercase tracking-wide">Platform health</h2>
          </div>
          <div className="bg-card border border-border rounded-xl2 shadow-card divide-y divide-border">
            {data.health.providers.map((p) => (
              <div key={p.name} className="flex items-center gap-3 px-4 py-3">
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-medium text-ink">{p.name}</p>
                  <p className="text-xs text-muted truncate">{p.detail}</p>
                </div>
                <span className={`text-xs font-semibold rounded-full px-2.5 py-1 flex-shrink-0 ${statusColor(p.status)}`}>
                  {p.status}
                </span>
              </div>
            ))}
          </div>
        </div>
      </div>

      <div>
        <div className="flex items-center gap-2 mb-3">
          <FileStack size={16} className="text-primary" />
          <h2 className="text-sm font-semibold text-muted uppercase tracking-wide">Recent imports</h2>
        </div>
        <div className="bg-card border border-border rounded-xl2 shadow-card divide-y divide-border">
          {data.recentImports.length === 0 && (
            <p className="text-sm text-muted px-4 py-4">No statement imports recorded yet.</p>
          )}
          {data.recentImports.map((imp) => (
            <div key={imp.id} className="flex items-center gap-3 px-4 py-3">
              {imp.hadSkippedRows
                ? <AlertTriangle size={15} className="text-warning flex-shrink-0" />
                : <CheckCircle2 size={15} className="text-success flex-shrink-0" />}
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium text-ink truncate">{imp.fileName}</p>
                <p className="text-xs text-muted">
                  {imp.userEmail} · {imp.transactionsImported} imported
                  {imp.hadSkippedRows ? `, ${imp.transactionsSkipped} skipped` : ''} · {new Date(imp.importedAt).toLocaleString()}
                </p>
              </div>
            </div>
          ))}
        </div>
      </div>

      <div>
        <h2 className="text-sm font-semibold text-muted uppercase tracking-wide mb-3">More detail</h2>
        <div className="bg-card border border-border rounded-xl2 shadow-card divide-y divide-border">
          {EXTERNAL_LINKS.map(({ href, label }) => (
            <a
              key={href}
              href={href}
              target="_blank"
              rel="noreferrer"
              className="flex items-center gap-3 px-4 py-3 hover:bg-primary-light transition-colors"
            >
              <ExternalLink size={15} className="text-primary flex-shrink-0" />
              <span className="text-sm text-ink flex-1">{label}</span>
            </a>
          ))}
        </div>
      </div>
    </div>
  );
}

function Row({ label, value, icon: Icon, hint }: { label: string; value: string; icon?: typeof GitCommit; hint?: string }) {
  return (
    <div className="flex items-center gap-3 px-4 py-3" title={hint}>
      {Icon && <Icon size={14} className="text-muted flex-shrink-0" />}
      <span className="text-sm text-ink flex-1">{label}</span>
      <span className="text-sm font-mono text-muted truncate max-w-[55%] text-right">{value}</span>
    </div>
  );
}

export default function Diagnostics() {
  return (
    <AdminLayout title="Platform Diagnostics" subtitle="Application state for developers and support -- not a replacement for Grafana/Loki/Sentry">
      <RequirePermission permission="PLATFORM_DIAGNOSTICS_VIEW">
        <DiagnosticsContent />
      </RequirePermission>
    </AdminLayout>
  );
}
