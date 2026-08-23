import { heroIntelligence } from '../landing-config';

interface IntelligenceScanProps {
  /**
   * How many checklist items are revealed so far, owned by the caller (AnalysisSequence) rather
   * than an internal useStagedReveal -- so HealthScoreRing can share the exact same counter and
   * only draw its ring once this checklist finishes. Defaults to fully-revealed so the checklist
   * still shows its real content immediately when rendered standalone (tests, or any future
   * non-sequenced usage).
   */
  step?: number;
}

/** The "Analyzing your finances…" checklist. */
export function IntelligenceScan({ step = heroIntelligence.steps.length }: IntelligenceScanProps) {
  return (
    <div className="rounded-2xl border border-white/10 bg-white/5 backdrop-blur-md px-5 py-4 w-full max-w-xs">
      <p className="text-xs font-medium text-white/70 mb-3">{heroIntelligence.heading}</p>
      <ul className="space-y-2">
        {heroIntelligence.steps.map((label, i) => {
          const revealed = step > i;
          return (
            <li
              key={label}
              className="flex items-center gap-2 text-xs text-white/90"
              style={{
                opacity: revealed ? 1 : 0.25,
                transform: revealed ? 'none' : 'translateX(-6px)',
                transition: 'opacity 360ms ease, transform 360ms ease',
              }}
            >
              <span
                aria-hidden="true"
                className="flex h-4 w-4 shrink-0 items-center justify-center rounded-full text-[10px]"
                style={{
                  background: revealed ? 'var(--m-success)' : 'rgba(255,255,255,0.15)',
                  color: revealed ? '#fff' : 'transparent',
                  transition: 'background 360ms ease',
                }}
              >
                ✓
              </span>
              {label}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
