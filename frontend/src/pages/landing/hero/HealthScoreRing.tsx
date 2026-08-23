import { CountUp } from '../primitives';
import { heroScore } from '../landing-config';

const RADIUS = 54;
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;

interface HealthScoreRingProps {
  /**
   * Whether the ring should be drawn to its final value. Owned by the caller (AnalysisSequence)
   * rather than an internal IntersectionObserver, so the ring's draw can be sequenced against
   * something else -- specifically, staying undrawn until IntelligenceScan's checklist finishes,
   * so "84" reads as the checklist's conclusion rather than a coincidence that happens to land
   * around the same time. Defaults to true so the ring still shows its real score immediately
   * when rendered standalone (tests, or any future non-sequenced usage).
   */
  drawn?: boolean;
}

/**
 * Circular score dial. Mirrors CountUp's own contract (see primitives.tsx): starts already at
 * the final ring position, so a browser without IntersectionObserver -- or a test -- shows the
 * real score rather than a permanently empty ring.
 */
export function HealthScoreRing({ drawn = true }: HealthScoreRingProps) {
  const target = (heroScore.value / 100) * CIRCUMFERENCE;
  const dash = drawn ? target : 0;

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
              transition: 'stroke-dasharray 1200ms cubic-bezier(0.16,1,0.3,1)',
              filter: 'drop-shadow(0 0 8px rgb(22 163 74 / .6))',
            }}
          />
        </svg>
        <div className="absolute inset-0 flex items-center justify-center">
          <span className="text-3xl font-bold text-white">
            <CountUp value={heroScore.value} trigger={drawn} />
          </span>
        </div>
      </div>
      <span className="mt-3 text-[10px] uppercase tracking-wide text-white/60">{heroScore.label}</span>
      <p className="mt-1 text-xs" style={{ color: 'var(--m-success)' }}>
        ↑ {heroScore.delta}
      </p>
    </div>
  );
}
