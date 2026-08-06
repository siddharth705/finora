import { Laptop, Smartphone, Tablet } from 'lucide-react';
import { Reveal, Section, SectionHeading } from './primitives';

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
const MOMENTS = [
  { icon: <Laptop size={18} />, when: 'Morning', what: 'Import last month\'s statement over coffee. Two minutes, done.' },
  { icon: <Smartphone size={18} />, when: 'Afternoon', what: 'Check what\'s left in the food budget before ordering lunch.' },
  { icon: <Tablet size={18} />, when: 'Evening', what: 'Nudge a goal after the salary lands. Same numbers, same account.' },
];

export function Everywhere() {
  return (
    <Section>
      <SectionHeading
        eyebrow="Anywhere"
        title="One picture. Every device."
        blurb="The same account, the same numbers, wherever you happen to be looking."
      />

      <div className="grid md:grid-cols-3 gap-4 max-w-4xl mx-auto">
        {MOMENTS.map((m, i) => (
          <Reveal key={m.when} delayMs={i * 90}>
            <div className="m-card m-card-hover p-6 h-full">
              <span className="w-10 h-10 rounded-xl grid place-items-center mb-4" style={{ background: 'var(--m-brand-wash)', color: 'var(--m-brand)' }}>
                {m.icon}
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
            In development
          </span>
          Finora runs in any browser today. Native iOS and Android apps are being built and are not
          on the app stores yet.
        </p>
      </Reveal>
    </Section>
  );
}
