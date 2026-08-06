import { useEffect, useRef, useState, type ReactNode } from 'react';

/**
 * Shared building blocks for the marketing page. Everything here is presentational and carries no
 * product claim of its own -- the copy lives in the sections, so a claim can be checked in one
 * place rather than hunted through layout code.
 *
 * Motion follows the brief: 200-400ms, fade plus a short upward move, and nothing that competes
 * with reading. Every animation below is decoration, so `prefers-reduced-motion` skips it outright
 * (see the media query in index.css) rather than degrading it.
 */

/** Fades content up as it scrolls into view. Runs once -- re-animating on scroll-back is noise. */
export function Reveal({ children, delayMs = 0, className = '' }: {
  children: ReactNode;
  delayMs?: number;
  className?: string;
}) {
  const ref = useRef<HTMLDivElement | null>(null);
  const [shown, setShown] = useState(false);

  useEffect(() => {
    const node = ref.current;
    if (!node) return;
    // Guards the jsdom/test path and any browser without IntersectionObserver: show immediately
    // rather than leaving content permanently invisible, which is the failure mode that matters.
    if (typeof IntersectionObserver === 'undefined') {
      setShown(true);
      return;
    }
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setShown(true);
          observer.disconnect();
        }
      },
      { threshold: 0.12, rootMargin: '0px 0px -8% 0px' }
    );
    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  return (
    <div
      ref={ref}
      className={className}
      style={{
        opacity: shown ? 1 : 0,
        transform: shown ? 'none' : 'translateY(14px)',
        transition: `opacity 420ms ease ${delayMs}ms, transform 420ms ease ${delayMs}ms`,
      }}
    >
      {children}
    </div>
  );
}

/**
 * Counts up to a value once visible.
 *
 * Starts from the final value and only animates after the observer fires, so a browser without
 * IntersectionObserver -- or a test renderer -- shows the real number instead of a permanent
 * zero. An earlier version of this component defaulted to 0 and animated on mount, which rendered
 * "0" whenever the observer never fired; that is the bug this shape avoids.
 */
export function CountUp({ value, prefix = '', suffix = '', durationMs = 900 }: {
  value: number;
  prefix?: string;
  suffix?: string;
  durationMs?: number;
}) {
  const ref = useRef<HTMLSpanElement | null>(null);
  const [display, setDisplay] = useState(value);

  useEffect(() => {
    const node = ref.current;
    if (!node || typeof IntersectionObserver === 'undefined') return;
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return;

    let frame = 0;
    const observer = new IntersectionObserver(([entry]) => {
      if (!entry.isIntersecting) return;
      observer.disconnect();
      const start = performance.now();
      setDisplay(0);
      const tick = (now: number) => {
        const t = Math.min(1, (now - start) / durationMs);
        // easeOutCubic -- decelerates into the final value rather than stopping dead.
        setDisplay(Math.round(value * (1 - Math.pow(1 - t, 3))));
        if (t < 1) frame = requestAnimationFrame(tick);
      };
      frame = requestAnimationFrame(tick);
    }, { threshold: 0.4 });

    observer.observe(node);
    return () => {
      observer.disconnect();
      cancelAnimationFrame(frame);
    };
  }, [value, durationMs]);

  return <span ref={ref}>{prefix}{display.toLocaleString('en-IN')}{suffix}</span>;
}

export function Eyebrow({ children }: { children: ReactNode }) {
  return <p className="m-eyebrow mb-3">{children}</p>;
}

/** Section shell. `tone` picks one of the three surfaces the page alternates between. */
export function Section({ id, tone = 'plain', className = '', children }: {
  id?: string;
  tone?: 'plain' | 'alt' | 'deep';
  className?: string;
  children: ReactNode;
}) {
  const toneClass = tone === 'alt' ? 'm-section-alt' : tone === 'deep' ? 'm-section-deep' : 'm-section';
  return (
    <section id={id} className={`${toneClass} ${className}`}>
      <div className="max-w-6xl mx-auto px-5 sm:px-6">{children}</div>
    </section>
  );
}

/** Centred heading block used by most sections. */
export function SectionHeading({ eyebrow, title, blurb, invert = false }: {
  eyebrow?: string;
  title: ReactNode;
  blurb?: ReactNode;
  invert?: boolean;
}) {
  return (
    <Reveal className="text-center max-w-2xl mx-auto mb-14">
      {eyebrow ? <Eyebrow>{eyebrow}</Eyebrow> : null}
      <h2 className="m-h2" style={invert ? { color: '#F8FAFC' } : undefined}>{title}</h2>
      {blurb ? (
        <p className="m-lead mt-4" style={invert ? { color: '#94A3B8' } : undefined}>{blurb}</p>
      ) : null}
    </Reveal>
  );
}

/**
 * A gradient bleed between two sections.
 *
 * Without this the page is a stack of abutting rectangles, and every boundary reads as "new
 * page". A short band interpolating one surface into the next removes the edge entirely, which is
 * most of what makes a long page feel continuous rather than assembled. Purely visual, so it is
 * hidden from assistive tech and contributes no content.
 */
export function Transition({ from, to, height = 96 }: { from: string; to: string; height?: number }) {
  return (
    <div
      aria-hidden="true"
      style={{ height, background: `linear-gradient(180deg, ${from} 0%, ${to} 100%)` }}
    />
  );
}

/**
 * Advances a counter 0..steps once the element scrolls into view, one tick every `intervalMs`.
 *
 * Used by the animations that have to be *watched* to land -- the learning loop, the progressive
 * dashboard. Both jump straight to the finished state when there's no IntersectionObserver (tests,
 * older browsers) or when the visitor prefers reduced motion, because a half-drawn diagram that
 * never completes is worse than no animation at all.
 */
export function useStagedReveal(steps: number, intervalMs = 620) {
  const ref = useRef<HTMLDivElement | null>(null);
  const [step, setStep] = useState(0);

  useEffect(() => {
    const node = ref.current;
    const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    if (!node || typeof IntersectionObserver === 'undefined' || reduced) {
      setStep(steps);
      return;
    }
    let timers: ReturnType<typeof setTimeout>[] = [];
    const observer = new IntersectionObserver(([entry]) => {
      if (!entry.isIntersecting) return;
      observer.disconnect();
      timers = Array.from({ length: steps }, (_, i) =>
        setTimeout(() => setStep(i + 1), (i + 1) * intervalMs)
      );
    }, { threshold: 0.3 });
    observer.observe(node);
    return () => {
      observer.disconnect();
      timers.forEach(clearTimeout);
    };
  }, [steps, intervalMs]);

  return { ref, step };
}

/** The → between flow-diagram nodes. Decorative: the reading order already implies sequence. */
export function FlowArrow({ vertical = false }: { vertical?: boolean }) {
  return (
    <span
      aria-hidden="true"
      className={`text-slate-300 select-none ${vertical ? 'py-1' : 'px-1'}`}
      style={{ lineHeight: 1 }}
    >
      {vertical ? '↓' : '→'}
    </span>
  );
}
