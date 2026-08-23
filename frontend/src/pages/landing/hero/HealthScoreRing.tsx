import { heroScore } from '../landing-config';

const RADIUS = 54;
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;

interface HealthScoreRingProps {
  /**
   * How many of the checklist's steps have completed, and the total step count -- owned by the
   * caller (AnalysisSequence) so the ring fills IN STEP with the checklist instead of staying
   * empty until it finishes and then jumping straight to the final value. Two of four steps
   * checked shows roughly half the score, not 0 and not 84 -- each checkmark visibly moves the
   * ring and the number, so "84" reads as something the checklist built up to, not a coincidence.
   * Both default to a complete 1/1 so the ring still shows its real score immediately when
   * rendered standalone (tests, or any future non-sequenced usage).
   */
  step?: number;
  totalSteps?: number;
}

/**
 * Circular score dial. The stroke and the number both derive directly from `step/totalSteps` --
 * neither has an animation trigger of its own, so they can never race ahead of or lag behind
 * whatever is actually driving the sequence (see AnalysisSequence).
 */
export function HealthScoreRing({ step = 1, totalSteps = 1 }: HealthScoreRingProps) {
  const progress = totalSteps > 0 ? Math.min(1, step / totalSteps) : 1;
  const displayValue = Math.round(progress * heroScore.value);
  const target = (heroScore.value / 100) * CIRCUMFERENCE;
  const dash = progress * target;

  return (
    <div className="inline-flex flex-col items-center">
      {/* This wrapper -- not the outer flex-col column -- is what `inset-0` below sizes against.
          It must stay exactly 132x132 (the ring's own size) so the number overlay centers on the
          ring itself, not on the ring-plus-label-plus-delta column beneath it. Regressed once
          already: putting the overlay directly in the outer column let `inset-0` stretch across
          the whole (taller) column once the label/delta became flow siblings of the ring instead
          of overlay children, pulling the number down off-center. */}
      <div className="relative w-[132px] h-[132px]">
        <svg
          viewBox="0 0 140 140"
          className="w-[132px] h-[132px]"
          role="img"
          aria-label={`Financial health score ${heroScore.value} out of 100`}
        >
          <circle cx="70" cy="70" r={RADIUS} fill="none" stroke="rgba(255,255,255,0.12)" strokeWidth="10" />
          <circle
            cx="70"
            cy="70"
            r={RADIUS}
            fill="none"
            stroke="var(--m-success)"
            strokeWidth="10"
            strokeLinecap="round"
            strokeDasharray={`${dash} ${CIRCUMFERENCE - dash}`}
            transform="rotate(-90 70 70)"
            style={{
              // Shorter than the checklist's own 550ms-per-step interval (see AnalysisSequence) --
              // each increment finishes drawing before the next checkmark lands, rather than
              // stacking into one long blurred sweep.
              transition: 'stroke-dasharray 450ms cubic-bezier(0.16,1,0.3,1)',
              filter: 'drop-shadow(0 0 8px rgb(22 163 74 / .6))',
            }}
          />
        </svg>
        <div className="absolute inset-0 flex items-center justify-center">
          <span className="text-3xl font-bold text-white">{displayValue}</span>
        </div>
      </div>
      <span className="mt-3 text-[10px] uppercase tracking-wide text-white/60">{heroScore.label}</span>
      <p className="mt-1 text-xs" style={{ color: 'var(--m-success)' }}>
        ↑ {heroScore.delta}
      </p>
    </div>
  );
}
