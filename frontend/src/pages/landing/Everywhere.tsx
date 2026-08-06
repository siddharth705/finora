import { Laptop, Smartphone, Tablet } from 'lucide-react';
import { Reveal, Section, SectionHeading } from './primitives';
import { everywhere } from './landing-config';

/**
 * A day, not a device matrix.
 *
 * "Works on desktop and mobile" is a specification. "Reviewed the month over coffee, checked a
 * balance before buying lunch" is a picture the reader puts themselves into, which is the point
 * of the section.
 *
 * The honesty constraint here is specific and easy to lose in a redesign: the WEB app is
 * responsive and genuinely runs on any device today, so the three moments below are all true.
 * The native iOS/Android apps are built but released to neither store, so the note at the bottom
 * stays until they are. Update the note and the wording together, not just the note.
 */
const ICONS = [<Laptop size={18} />, <Smartphone size={18} />, <Tablet size={18} />];

export function Everywhere() {
  return (
    <Section>
      <SectionHeading
        eyebrow={everywhere.eyebrow}
        title={everywhere.title}
        blurb={everywhere.blurb}
      />

      <div className="grid md:grid-cols-3 gap-4 max-w-4xl mx-auto">
        {everywhere.moments.map((m, i) => (
          <Reveal key={m.when} delayMs={i * 90}>
            <div className="m-card m-card-hover p-6 h-full">
              <span className="w-10 h-10 rounded-xl grid place-items-center mb-4" style={{ background: 'var(--m-brand-wash)', color: 'var(--m-brand)' }}>
                {ICONS[i]}
              </span>
              <p className="m-eyebrow mb-1.5">{m.when}</p>
              <p className="text-[15px] leading-relaxed" style={{ color: 'var(--m-ink-2)' }}>{m.what}</p>
            </div>
          </Reveal>
        ))}
      </div>

      <Reveal delayMs={260}>
        <p className="text-center text-sm mt-8" style={{ color: 'var(--m-ink-3)' }}>
          <span className="inline-block text-[11px] font-semibold px-2 py-1 rounded-md mr-2" style={{ background: '#FEF3C7', color: '#92400E' }}>
            {everywhere.nativeStatus}
          </span>
          {everywhere.nativeNote}
        </p>
      </Reveal>
    </Section>
  );
}
