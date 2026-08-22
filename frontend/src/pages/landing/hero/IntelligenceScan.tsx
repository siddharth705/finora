import { useStagedReveal } from '../primitives';
import { heroIntelligence } from '../landing-config';

/**
 * The "Analyzing your finances…" checklist. Built on the existing useStagedReveal primitive --
 * same jsdom/reduced-motion/no-IntersectionObserver fallback behavior as the rest of the page,
 * not reimplemented with Framer Motion.
 */
export function IntelligenceScan() {
  const { ref, step } = useStagedReveal(heroIntelligence.steps.length, 550);

  return (
    <div
      ref={ref}
      className="rounded-2xl border border-white/10 bg-white/5 backdrop-blur-md px-5 py-4 w-full max-w-xs"
    >
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
