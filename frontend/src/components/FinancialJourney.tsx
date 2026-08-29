import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Check, Sprout, PartyPopper, ChevronDown, ChevronRight } from 'lucide-react';
import { dashboardApi } from '../api/endpoints';
import { FinoraCard } from '../design-system';

const MILESTONE_LABELS: Record<string, string> = {
  ACCOUNT_CREATED: 'Account created',
  FIRST_IMPORT: 'Imported your first statement',
  FIRST_BUDGET: 'Created your first budget',
  FIRST_GOAL: 'Created your first goal',
  FIRST_GOAL_ACHIEVED: 'Achieved your first goal',
};

// D-25: event-based, real elapsed time -- never a fixed Day-N schedule every user is measured
// against. Each milestone reports how long ago IT actually happened.
export function journeyDateLabel(iso: string): string {
  const days = Math.floor((Date.now() - new Date(iso).getTime()) / 86_400_000);
  if (days <= 0) return 'Completed today';
  if (days === 1) return 'Completed yesterday';
  if (days < 30) return `Completed ${days} days ago`;
  return `Completed on ${new Date(iso).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })}`;
}

/**
 * D-25 PR3-C. Unlike Financial Health Score (Dashboard.tsx's own card, hidden while isEmpty),
 * this is deliberately always rendered -- ACCOUNT_CREATED is true from the moment a user signs
 * up, so a brand-new account is exactly the case this is most useful for, not one to hide it in.
 * Fails quiet (renders nothing) while the query is loading or if it errors, rather than showing a
 * loading skeleton or error banner for what is a nice-to-have progress narrative, not core data.
 */
export function FinancialJourney() {
  const { data } = useQuery({ queryKey: ['financial-journey'], queryFn: () => dashboardApi.journey() });
  // Expanded by default -- this is a primary onboarding widget, not a detail panel like
  // VerificationPanel (which collapses by default because its detail is noise in the common
  // case). Collapsing is purely a "get it out of my way" affordance for a returning user who
  // has already seen the milestones, not the default first impression.
  const [expanded, setExpanded] = useState(true);
  const milestones = data?.milestones ?? [];
  if (milestones.length === 0) return null;

  const completedCount = milestones.filter((m) => m.completed).length;
  const allDone = completedCount === milestones.length;

  return (
    <FinoraCard padding="lg" className="mb-6">
      <button
        type="button"
        onClick={() => setExpanded((open) => !open)}
        aria-expanded={expanded}
        className={`w-full flex items-center justify-between text-left ${expanded ? 'mb-5' : ''}`}
      >
        <div className="flex items-center gap-2">
          <div className="w-8 h-8 rounded-full bg-primary-light flex items-center justify-center">
            {allDone
              ? <PartyPopper size={15} className="text-primary" />
              : <Sprout size={15} className="text-primary" />}
          </div>
          <h2 className="font-semibold text-ink">Your Financial Journey</h2>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-xs font-medium text-muted bg-bg rounded-full px-2.5 py-1">
            {completedCount} of {milestones.length} complete
          </span>
          {expanded
            ? <ChevronDown size={16} className="text-muted flex-shrink-0" />
            : <ChevronRight size={16} className="text-muted flex-shrink-0" />}
        </div>
      </button>
      {expanded && (
      <ol>
        {milestones.map((m, i) => {
          const isLast = i === milestones.length - 1;
          return (
            <li
              key={m.type}
              className="journey-reveal-item flex gap-3"
              style={{ animationDelay: `${i * 80}ms` }}
            >
              <div className="flex flex-col items-center">
                <span
                  className={`w-6 h-6 rounded-full flex items-center justify-center flex-shrink-0 ${
                    m.completed ? 'bg-primary text-on-primary' : 'bg-bg border-2 border-border'
                  }`}
                >
                  {m.completed && <Check size={13} strokeWidth={3} />}
                </span>
                {!isLast && (
                  <span
                    className={`w-0.5 flex-1 my-1 ${m.completed ? 'bg-primary' : 'bg-border'}`}
                    style={{ minHeight: '1.5rem' }}
                  />
                )}
              </div>
              <div className={isLast ? '' : 'pb-5'}>
                <p className={`text-sm font-medium ${m.completed ? 'text-ink' : 'text-muted'}`}>
                  {MILESTONE_LABELS[m.type] ?? m.type}
                </p>
                {m.completed && m.completedAt && (
                  <p className="text-xs text-muted mt-0.5">{journeyDateLabel(m.completedAt)}</p>
                )}
              </div>
            </li>
          );
        })}
      </ol>
      )}
    </FinoraCard>
  );
}
