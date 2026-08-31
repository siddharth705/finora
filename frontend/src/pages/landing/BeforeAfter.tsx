import { ArrowDown } from 'lucide-react';
import { Reveal, Section, SectionHeading } from './primitives';
import { beforeAfter } from './landing-config';

/**
 * The section the page was missing, and the one that does the most work.
 *
 * Everything else describes what Fynora does. This is the only place that shows what changes --
 * two identical-shaped columns so the eye compares them directly, ending on "confusion" against
 * "confidence". The parallel structure is the argument; keep both columns the same length if this
 * is ever edited.
 */
// Five beats each, and they line up row for row on purpose: the reader compares step 3 to step 3,
// not paragraph to paragraph. Both columns must stay the same length -- asserted in the test.

function Column({ label, steps, tone }: {
  label: string;
  steps: string[];
  tone: 'before' | 'after';
}) {
  const after = tone === 'after';
  return (
    <div
      className="rounded-2xl p-6 sm:p-8 h-full border"
      style={after
        ? { borderColor: '#BFDBFE', background: 'linear-gradient(180deg,#F5F9FF 0%,#FFFFFF 100%)' }
        : { borderColor: 'var(--m-line)', background: '#FBFCFD' }}
    >
      <p
        className="text-[11px] font-bold uppercase tracking-[.09em] mb-6"
        style={{ color: after ? 'var(--m-brand)' : 'var(--m-ink-3)' }}
      >
        {label}
      </p>
      <ol className="space-y-1">
        {steps.map((s, i) => (
          <li key={s}>
            <div
              className="rounded-xl px-4 py-3 text-[15px] font-medium border"
              style={after
                ? { borderColor: '#DBEAFE', background: '#fff', color: 'var(--m-ink)' }
                : { borderColor: 'var(--m-line)', background: '#fff', color: 'var(--m-ink-2)' }}
            >
              {s}
            </div>
            {i < steps.length - 1 ? (
              <div className="flex justify-center py-1.5" aria-hidden="true">
                <ArrowDown size={15} style={{ color: after ? '#93C5FD' : '#CBD5E1' }} />
              </div>
            ) : null}
          </li>
        ))}
      </ol>
      <p className="mt-6 text-2xl" style={{ fontFamily: "'Manrope', Inter, sans-serif", fontWeight: 800, letterSpacing: '-.02em', color: after ? 'var(--m-brand)' : '#94A3B8' }}>
        {after ? beforeAfter.afterVerdict : beforeAfter.beforeVerdict}
      </p>
    </div>
  );
}

export function BeforeAfter() {
  return (
    // id is load-bearing: the nav links here as "Before & after". It was missing, so that nav
    // item silently did nothing. landing-claims.test.tsx now fails the build on any in-page
    // anchor with no matching element.
    <Section id="difference" tone="alt">
      <SectionHeading
        eyebrow={beforeAfter.eyebrow}
        title={beforeAfter.title}
        blurb={beforeAfter.blurb}
      />
      <div className="grid md:grid-cols-2 gap-5 max-w-4xl mx-auto">
        <Reveal><Column label="Before Fynora" steps={beforeAfter.before} tone="before" /></Reveal>
        <Reveal delayMs={140}><Column label="With Fynora" steps={beforeAfter.after} tone="after" /></Reveal>
      </div>
    </Section>
  );
}
