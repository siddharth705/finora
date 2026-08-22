import { forwardRef } from 'react';

const STAGES = [
  { key: 'extraction', label: 'Extraction' },
  { key: 'categorization', label: 'Categorization' },
  { key: 'insights', label: 'Insights' },
] as const;

/**
 * Beat 2 of the ImportSection scroll story: Finora reading and organizing the statement.
 * Deliberately a labeled pipeline (extraction -> categorization -> insights), not a generic
 * glowing "AI orb" -- see the scroll-storytelling design spec's rationale. Presentational only,
 * same data-target contract as DocumentStack.
 */
export const ProcessingCore = forwardRef<HTMLDivElement>(function ProcessingCore(_props, ref) {
  return (
    <div ref={ref} className="relative w-full h-full flex flex-col items-center justify-center gap-4">
      <div
        data-target="core-mark"
        className="w-16 h-16 rounded-2xl bg-[var(--m-brand)] text-white grid place-items-center font-extrabold text-2xl"
        style={{ boxShadow: '0 16px 32px -12px rgba(38,42,51,.9)' }}
      >
        F
      </div>
      <div className="flex flex-col gap-2">
        {STAGES.map((stage) => (
          <div
            key={stage.key}
            data-target={`stage-${stage.key}`}
            className="text-[11px] font-medium text-slate-500 flex items-center gap-2"
          >
            <span className="w-1.5 h-1.5 rounded-full" style={{ background: 'var(--m-success)' }} />
            {stage.label}
          </div>
        ))}
      </div>
    </div>
  );
});
