import { useEffect, useState } from 'react';
import { Activity } from 'lucide-react';
import { activityApi } from '../api/endpoints';
import type { AuditLogEntry } from '../types';

function fmtDateTime(d: string) {
  return new Date(d).toLocaleString('en-IN', { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

// ACTION values are UPPER_SNAKE_CASE strings written by AuditService.record() call sites across
// the app (RULE_CREATED, RELATIONSHIP_MERGED, STATEMENT_IMPORT_DELETED, ...) -- turned into a
// readable label here rather than maintaining a lookup table that would drift out of sync with
// every new call site.
function readableAction(action: string) {
  return action.toLowerCase().replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase());
}

function metadataSummary(metadata: Record<string, unknown> | null) {
  if (!metadata || Object.keys(metadata).length === 0) return null;
  return Object.entries(metadata)
    .map(([k, v]) => `${k}: ${typeof v === 'object' ? JSON.stringify(v) : String(v)}`)
    .join(' · ');
}

export default function ActivityTimeline() {
  const [entries, setEntries] = useState<AuditLogEntry[] | null>(null);

  useEffect(() => {
    activityApi.list().then(setEntries);
  }, []);

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <Activity size={20} className="text-primary" />
        <h1 className="text-lg font-semibold">Activity Timeline</h1>
      </div>
      <p className="text-sm text-gray-500">
        Every change Finora's tracked for your account -- rules, relationships, merchants,
        statement imports, and account/transaction edits. Not yet included: reconciliation runs
        and recurring-detection passes (see the Reconciliation Monitor page for those).
      </p>

      <div className="bg-card rounded-xl shadow overflow-hidden">
        {entries === null ? (
          <p className="text-sm text-gray-500 p-4">Loading…</p>
        ) : entries.length === 0 ? (
          <p className="text-sm italic text-gray-500 p-4">No activity recorded yet.</p>
        ) : (
          <div className="divide-y divide-black/5">
            {entries.map((e) => {
              const meta = metadataSummary(e.metadata);
              return (
                <div key={e.id} className="px-4 py-3">
                  <div className="flex items-center justify-between gap-3">
                    <p className="text-sm text-ink">
                      <span className="font-medium">{readableAction(e.action)}</span>
                      {e.entityType && <span className="text-gray-500"> · {e.entityType}</span>}
                    </p>
                    <span className="text-xs text-gray-400 flex-shrink-0">{fmtDateTime(e.createdAt)}</span>
                  </div>
                  {meta && <p className="text-xs text-gray-400 mt-0.5 truncate">{meta}</p>}
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
