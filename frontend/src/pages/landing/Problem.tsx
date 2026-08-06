import { BarChart3, Brain, FileSearch, Upload } from 'lucide-react';
import { Reveal, Section, SectionHeading } from './primitives';
import { problem } from './landing-config';

/**
 * Names the reader's actual Sunday afternoon before offering anything.
 *
 * The four cards are chores, not missing features -- that distinction is the whole section. A
 * visitor who recognises their own month here will read the rest; one who is handed a feature
 * list first has no reason to.
 */
const ICONS = [<Upload size={18} />, <FileSearch size={18} />, <Brain size={18} />, <BarChart3 size={18} />];

export function Problem() {
  return (
    <Section>
      <SectionHeading eyebrow={problem.eyebrow} title={problem.title} />
      <div className="grid sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {problem.chores.map((text, i) => (
          <Reveal key={text} delayMs={i * 70}>
            <div className="m-card m-card-hover p-6 h-full">
              <span className="w-10 h-10 rounded-xl grid place-items-center mb-4" style={{ background: '#F1F5F9', color: 'var(--m-ink-3)' }}>
                {ICONS[i]}
              </span>
              <p className="text-[15px] leading-relaxed" style={{ color: 'var(--m-ink-2)' }}>{text}</p>
            </div>
          </Reveal>
        ))}
      </div>
      <Reveal delayMs={200}>
        <p className="text-center mt-12 text-xl" style={{ color: 'var(--m-ink)', fontFamily: "'Manrope', Inter, sans-serif", fontWeight: 700, letterSpacing: '-.02em' }}>
          {problem.closer}
          <br className="hidden sm:block" />
          <span style={{ color: 'var(--m-ink-3)', fontWeight: 600 }}> {problem.closerMuted}</span>
        </p>
      </Reveal>
    </Section>
  );
}
