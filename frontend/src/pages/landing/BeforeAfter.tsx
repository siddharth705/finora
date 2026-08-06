import { ArrowDown } from 'lucide-react';
import { Reveal, Section, SectionHeading } from './primitives';

/**
 * The section the page was missing, and the one that does the most work.
 *
 * Everything else describes what Finora does. This is the only place that shows what changes --
 * two identical-shaped columns so the eye compares them directly, ending on "confusion" against
 * "confidence". The parallel structure is the argument; keep both columns the same length if this
 * is ever edited.
 */
// Five beats each, and they line up row for row on purpose: the reader compares step 3 to step 3,
// not paragraph to paragraph. Both columns must stay the same length if this is ever edited.
const BEFORE = [
  'Download the statement',
  'Scroll hundreds of rows',
  'Categorize by hand',
  'Paste into a spreadsheet',
  'Still not certain',
];
const AFTER = [
  'Upload the statement',
  'Organized automatically',
  'Categorized, and it learns',
  'A dashboard, already current',
  'You actually know',
];

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
        {after ? 'Confidence.' : 'Confusion.'}
      </p>
    </div>
  );
}

export function BeforeAfter() {
  return (
    <Section tone="alt">
      <SectionHeading
        eyebrow="The difference"
        title="Same statement. Different month."
        blurb="Nothing about your bank changes. What changes is how much of your Sunday it costs."
      />
      <div className="grid md:grid-cols-2 gap-5 max-w-4xl mx-auto">
        <Reveal><Column label="Before Finora" steps={BEFORE} tone="before" /></Reveal>
        <Reveal delayMs={140}><Column label="With Finora" steps={AFTER} tone="after" /></Reveal>
      </div>
    </Section>
  );
}
