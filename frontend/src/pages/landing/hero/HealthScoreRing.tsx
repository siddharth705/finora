import { useEffect, useRef, useState } from 'react';
import { CountUp } from '../primitives';
import { heroScore } from '../landing-config';

const RADIUS = 54;
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;

/**
 * Circular score dial. Mirrors CountUp's own contract (see primitives.tsx): starts already at
 * the final ring position, so a browser without IntersectionObserver -- or a test -- shows the
 * real score rather than a permanently empty ring, and only animates the draw once the ring is
 * actually scrolled into view (and the visitor hasn't asked for reduced motion).
 */
export function HealthScoreRing() {
  const ref = useRef<SVGSVGElement | null>(null);
  const [drawn, setDrawn] = useState(true);

  useEffect(() => {
    const node = ref.current;
    if (!node || typeof IntersectionObserver === 'undefined') return;
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return;

    setDrawn(false);
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (!entry.isIntersecting) return;
        observer.disconnect();
        // Two frames so the browser paints the 0% state before transitioning -- a same-frame
        // change to a CSS-transitioned property doesn't transition at all.
        requestAnimationFrame(() => requestAnimationFrame(() => setDrawn(true)));
      },
      { threshold: 0.4 }
    );
    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  const target = (heroScore.value / 100) * CIRCUMFERENCE;
  const dash = drawn ? target : 0;

  return (
    <div className="relative inline-flex flex-col items-center">
      <svg
        ref={ref}
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
      <div className="absolute inset-0 flex flex-col items-center justify-center">
        <span className="text-3xl font-bold text-white">
          <CountUp value={heroScore.value} />
        </span>
        <span className="text-[10px] uppercase tracking-wide text-white/60">{heroScore.label}</span>
      </div>
      <p className="mt-2 text-xs" style={{ color: 'var(--m-success)' }}>
        ↑ {heroScore.delta}
      </p>
    </div>
  );
}
