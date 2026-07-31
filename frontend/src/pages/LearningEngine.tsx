import { useEffect, useState } from 'react';
import { GraduationCap, RotateCcw } from 'lucide-react';
import { learningApi, merchantsApi } from '../api/endpoints';
import type { LearningSummary, LearningTimelineEntry } from '../api/endpoints';

function fmtDateTime(d: string) {
  return new Date(d).toLocaleString('en-IN', { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

const ACTION_STYLE: Record<string, string> = {
  LEARNED: 'bg-success/15 text-success',
  CORRECTED: 'bg-warning/15 text-warning',
  UNDONE: 'bg-black/10 text-gray-600',
  MERGED: 'bg-primary/15 text-primary-dark',
  RESET: 'bg-danger/15 text-danger',
};

function describeEntry(e: LearningTimelineEntry) {
  switch (e.action) {
    case 'LEARNED':
      return `learned as ${e.newCategoryName ?? 'Unknown'}`;
    case 'CORRECTED':
      return `corrected from ${e.previousCategoryName ?? 'Unknown'} to ${e.newCategoryName ?? 'Unknown'}`;
    case 'UNDONE':
      return `undid its ${e.previousCategoryName ?? 'last'} confirmation`;
    case 'MERGED':
      return 'absorbed into another merchant during a merge';
    case 'RESET':
      return `learning reset (was ${e.previousCategoryName ?? 'Unknown'})`;
    default:
      return e.action;
  }
}

function SummaryCard({ label, value, hint }: { label: string; value: number; hint?: string }) {
  return (
    <div className="bg-card rounded-xl shadow p-4">
      <p className="text-xs uppercase text-gray-500 font-medium">{label}</p>
      <p className="text-2xl font-bold text-ink mt-1">{value.toLocaleString('en-IN')}</p>
      {hint && <p className="text-[11px] text-gray-400 mt-0.5">{hint}</p>}
    </div>
  );
}

export default function LearningEngine() {
  const [summary, setSummary] = useState<LearningSummary | null>(null);
  const [timeline, setTimeline] = useState<LearningTimelineEntry[] | null>(null);
  const [resettingId, setResettingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  function load() {
    learningApi.summary().then(setSummary);
    learningApi.timeline().then(setTimeline);
  }
  useEffect(load, []);

  async function resetMerchant(merchantId: string, merchantName: string) {
    if (!confirm(`Reset all learning for "${merchantName}"? Future transactions from this merchant won't get a learned-category suggestion until you confirm one again. This can't be undone.`)) {
      return;
    }
    setResettingId(merchantId);
    setError(null);
    try {
      await merchantsApi.resetLearning(merchantId);
      load();
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not reset learning for this merchant.');
    } finally {
      setResettingId(null);
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <GraduationCap size={20} className="text-primary" />
        <h1 className="text-lg font-semibold">Learning Engine</h1>
      </div>
      <p className="text-sm text-gray-500">
        Every time you confirm or correct a merchant's category, Finora records it here and uses
        it to suggest categories for that merchant's future transactions. Category assignment
        itself lives on the Merchants page -- this is the history of what's been learned.
      </p>

      {summary && (
        <div className="grid grid-cols-4 gap-3">
          <SummaryCard label="Merchants with learning" value={summary.learnedMerchants} />
          <SummaryCard label="Confirmations, lifetime" value={summary.totalConfirmations} hint="LEARNED + CORRECTED" />
          <SummaryCard label="Corrections, lifetime" value={summary.correctedCount} hint="Times a guess was overridden" />
          <SummaryCard label="Resets" value={summary.resetCount} />
        </div>
      )}

      {error && <p className="text-danger text-sm">{error}</p>}

      <div className="bg-card rounded-xl shadow overflow-hidden">
        <div className="px-4 py-3 border-b border-black/10">
          <p className="text-sm font-semibold text-ink">Activity</p>
        </div>
        {timeline === null ? (
          <p className="text-sm text-gray-500 p-4">Loading…</p>
        ) : timeline.length === 0 ? (
          <p className="text-sm italic text-gray-500 p-4">
            No learning activity yet -- confirming a category on the Merchants page is what starts it.
          </p>
        ) : (
          <div className="divide-y divide-black/5">
            {timeline.map((e) => (
              <div key={e.id} className="px-4 py-3 flex items-center justify-between gap-3">
                <div className="flex items-center gap-2.5 min-w-0">
                  <span className={`text-[10px] uppercase font-semibold rounded px-1.5 py-0.5 flex-shrink-0 ${ACTION_STYLE[e.action] ?? 'bg-black/10 text-gray-600'}`}>
                    {e.action}
                  </span>
                  <p className="text-sm text-ink truncate">
                    <span className="font-medium">{e.merchantName}</span>{' '}
                    <span className="text-gray-500">{describeEntry(e)}</span>
                  </p>
                </div>
                <div className="flex items-center gap-3 flex-shrink-0">
                  <span className="text-xs text-gray-400">{fmtDateTime(e.createdAt)}</span>
                  {(e.action === 'LEARNED' || e.action === 'CORRECTED') && (
                    <button
                      onClick={() => resetMerchant(e.merchantId, e.merchantName)}
                      disabled={resettingId === e.merchantId}
                      title={`Reset all learning for ${e.merchantName}`}
                      className="text-gray-400 hover:text-danger disabled:opacity-40"
                    >
                      <RotateCcw size={14} />
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
