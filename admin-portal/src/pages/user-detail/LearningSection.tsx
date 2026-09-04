import { useQuery } from '@tanstack/react-query';
import { Sparkles } from 'lucide-react';
import { adminUserLearningApi } from '../../api/endpoints';
import type { LearningSummaryDto, LearningTimelineEntry } from '../../types';

export function LearningSection({ userId }: { userId: string }) {
  const { data: summary, isLoading: summaryLoading } = useQuery<LearningSummaryDto>({
    queryKey: ['admin-user-learning-summary', userId],
    queryFn: () => adminUserLearningApi.summary(userId),
  });
  const { data: timeline, isLoading: timelineLoading } = useQuery({
    queryKey: ['admin-user-learning-timeline', userId],
    queryFn: () => adminUserLearningApi.timeline(userId),
  });

  return (
    <div className="bg-card border border-border rounded-xl2 shadow-card p-6">
      <div className="flex items-center gap-2 mb-3">
        <Sparkles size={15} className="text-primary" />
        <h3 className="text-sm font-semibold text-ink">Learning Engine</h3>
      </div>

      {!summaryLoading && summary && (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-4">
          <div className="bg-bg border border-border rounded-lg p-3">
            <p className="text-lg font-bold text-ink">{summary.learnedMerchants}</p>
            <p className="text-xs text-muted">Learned merchants</p>
          </div>
          <div className="bg-bg border border-border rounded-lg p-3">
            <p className="text-lg font-bold text-ink">{summary.totalConfirmations}</p>
            <p className="text-xs text-muted">Confirmations</p>
          </div>
          <div className="bg-bg border border-border rounded-lg p-3">
            <p className="text-lg font-bold text-warning">{summary.correctedCount}</p>
            <p className="text-xs text-muted">Corrections</p>
          </div>
          <div className="bg-bg border border-border rounded-lg p-3">
            <p className="text-lg font-bold text-ink">{summary.resetCount}</p>
            <p className="text-xs text-muted">Resets</p>
          </div>
        </div>
      )}

      {timelineLoading && <p className="text-sm text-muted">Loading…</p>}
      {!timelineLoading && (timeline ?? []).length === 0 && (
        <p className="text-sm text-muted">No learning activity recorded for this user yet.</p>
      )}
      <div>
        {timeline?.slice(0, 10).map((entry: LearningTimelineEntry) => (
          <div key={entry.id} className="flex items-center justify-between text-sm py-2 border-b border-border last:border-b-0 gap-3">
            <div className="min-w-0 flex-1">
              <p className="text-ink truncate">
                <span className="font-medium">{entry.merchantName}</span> -- {entry.action.toLowerCase()}
                {entry.newCategoryName ? ` -> ${entry.newCategoryName}` : ''}
              </p>
              <p className="text-xs text-muted">{new Date(entry.createdAt).toLocaleString()}</p>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
