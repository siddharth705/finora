import { useQuery } from '@tanstack/react-query';
import { FinoraCard } from '../design-system';
import { onboardingApi } from '../api/endpoints';
import { CHECKLIST_ITEMS } from './checklistItems';

export function ChecklistWidget() {
  const { data } = useQuery({ queryKey: ['onboarding', 'checklist'], queryFn: onboardingApi.getChecklist });

  if (!data || data.completedCount >= data.totalCount) return null;

  const completedKeys = new Set(data.items.filter((i) => i.completed).map((i) => i.key));
  const percent = Math.round((data.completedCount / data.totalCount) * 100);

  return (
    <FinoraCard padding="lg" className="mb-6">
      <p className="text-sm font-semibold text-ink mb-1">Getting Started</p>
      <p className="text-xs text-muted mb-3">{data.completedCount} of {data.totalCount} completed</p>
      <div className="h-2 rounded-full bg-border mb-4 overflow-hidden">
        <div className="h-full bg-primary" style={{ width: `${percent}%` }} />
      </div>
      <ul className="space-y-1.5">
        {CHECKLIST_ITEMS.map((item) => (
          <li key={item.key} className="text-sm text-muted flex items-center gap-2">
            <span>{completedKeys.has(item.key) ? '✅' : '⬜'}</span>
            {item.label}
          </li>
        ))}
      </ul>
    </FinoraCard>
  );
}
