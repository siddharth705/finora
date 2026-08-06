import { BarChart3, Brain, FileSearch, Upload } from 'lucide-react';
import { Reveal, Section, SectionHeading } from './primitives';

/**
 * Names the reader's actual Sunday afternoon before offering anything.
 *
 * The four cards are chores, not missing features -- that distinction is the whole section. A
 * visitor who recognises their own month here will read the rest; one who is handed a feature
 * list first has no reason to.
 */
const CHORES = [
  { icon: <Upload size={18} />, text: 'Downloading statements. Every month.' },
  { icon: <FileSearch size={18} />, text: 'Scrolling hundreds of rows for one charge.' },
  { icon: <Brain size={18} />, text: 'Guessing where the money actually went.' },
  { icon: <BarChart3 size={18} />, text: 'A spreadsheet that is stale by Tuesday.' },
];

export function Problem() {
  return (
    <Section>
      <SectionHeading eyebrow="Why this is hard" title="Managing money shouldn't feel like work." />
      <div className="grid sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {CHORES.map((c, i) => (
          <Reveal key={c.text} delayMs={i * 70}>
            <div className="m-card m-card-hover p-6 h-full">
              <span className="w-10 h-10 rounded-xl grid place-items-center mb-4" style={{ background: '#F1F5F9', color: 'var(--m-ink-3)' }}>
                {c.icon}
              </span>
              <p className="text-[15px] leading-relaxed" style={{ color: 'var(--m-ink-2)' }}>{c.text}</p>
            </div>
          </Reveal>
        ))}
      </div>
      <Reveal delayMs={200}>
        <p className="text-center mt-12 text-xl" style={{ color: 'var(--m-ink)', fontFamily: "'Manrope', Inter, sans-serif", fontWeight: 700, letterSpacing: '-.02em' }}>
          The data already exists.
          <br className="hidden sm:block" />
          <span style={{ color: 'var(--m-ink-3)', fontWeight: 600 }}> Understanding it is the hard part.</span>
        </p>
      </Reveal>
    </Section>
  );
}
