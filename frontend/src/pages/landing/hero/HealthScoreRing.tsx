import { useEffect, useState } from 'react';
import { heroScore } from '../landing-config';

const RADIUS = 54;
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;

interface HealthScoreRingProps {
  /**
   * How many of the checklist's steps have completed, and the total step count -- owned by the
   * caller (AnalysisSequence) so the ring starts filling the moment the checklist starts (step
   * reaches 1, its first checkmark) rather than waiting for it to finish. Both default to a
   * complete 1/1 so the ring still shows its real score immediately when rendered standalone
   * (tests, or any future non-sequenced usage).
   */
  step?: number;
  totalSteps?: number;
  /**
   * The checklist's own per-step interval (ms) -- used only to size the ring's continuous fill
   * duration so it lands on 84 at the exact moment the checklist's LAST item ticks, not before
   * and not after. Must match the caller's useStagedReveal intervalMs. The fill runs for
   * (totalSteps - 1) intervals: it starts when step first reaches 1 (the first checkmark, at
   * t=intervalMs) and finishes when step reaches totalSteps (the last checkmark, at
   * t=totalSteps*intervalMs) -- so its own duration is the gap between those two moments.
   */
  intervalMs?: number;
}

/**
 * Circular score dial. The stroke and the number both fill CONTINUOUSLY once the sequence
 * starts -- one smooth sweep from 0 to 84, not a value that jumps in steps synced to each
 * checkmark. An earlier version updated the ring in four discrete jumps (0 -> ~21 -> ~42 -> ~63
 * -> 84, one per checkmark); reported directly as not looking natural. This version starts the
 * fill once (when the checklist's first item ticks) and lets one continuous animation carry it to
 * 84 exactly as the last item ticks -- the SAME visual destination, reached by one motion instead
 * of four.
 */
export function HealthScoreRing({ step = 1, totalSteps = 1, intervalMs = 550 }: HealthScoreRingProps) {
  const started = step >= 1;
  const fillDurationMs = Math.max(0, totalSteps - 1) * intervalMs;
  const target = (heroScore.value / 100) * CIRCUMFERENCE;
  const dash = started ? target : 0;

  const [display, setDisplay] = useState(() => {
    if (!started) return 0;
    if (fillDurationMs === 0) return heroScore.value;
    return 0;
  });

  useEffect(() => {
    if (!started) {
      setDisplay(0);
      return;
    }
    const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    if (reduced || fillDurationMs === 0) {
      setDisplay(heroScore.value);
      return;
    }

    setDisplay(0);
    let frame = 0;
    const start = performance.now();
    const tick = (now: number) => {
      const t = Math.min(1, (now - start) / fillDurationMs);
      // easeOutCubic -- decelerates into the final value rather than stopping dead.
      setDisplay(Math.round(heroScore.value * (1 - Math.pow(1 - t, 3))));
      if (t < 1) frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [started, fillDurationMs]);

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
              transition: `stroke-dasharray ${fillDurationMs}ms cubic-bezier(0.16,1,0.3,1)`,
              filter: 'drop-shadow(0 0 8px rgb(22 163 74 / .6))',
            }}
          />
        </svg>
        <div className="absolute inset-0 flex items-center justify-center">
          <span className="text-3xl font-bold text-white">{display}</span>
        </div>
      </div>
      <span className="mt-3 text-[10px] uppercase tracking-wide text-white/60">{heroScore.label}</span>
      <p className="mt-1 text-xs" style={{ color: 'var(--m-success)' }}>
        ↑ {heroScore.delta}
      </p>
    </div>
  );
}
