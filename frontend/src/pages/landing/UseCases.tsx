import { Briefcase, GraduationCap, Users, Wallet } from 'lucide-react';
import { Reveal, Section, SectionHeading } from './primitives';
import { useCases } from './landing-config';

const ICONS = [<Briefcase size={18} />, <Users size={18} />, <Wallet size={18} />, <GraduationCap size={18} />];

export function UseCases() {
  return (
    <Section tone="alt">
      <SectionHeading eyebrow={useCases.eyebrow} title={useCases.title} blurb={useCases.blurb} />
      <div className="grid sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {useCases.audiences.map((a, i) => (
          <Reveal key={a.title} delayMs={i * 70}>
            <div className="m-card m-card-hover p-6 h-full">
              <span className="w-10 h-10 rounded-xl grid place-items-center mb-4" style={{ background: 'var(--m-brand-wash)', color: 'var(--m-brand)' }}>
                {ICONS[i]}
              </span>
              <h3 className="m-h3 text-[15px] mb-1.5">{a.title}</h3>
              <p className="text-sm leading-relaxed" style={{ color: 'var(--m-ink-2)' }}>{a.body}</p>
            </div>
          </Reveal>
        ))}
      </div>
    </Section>
  );
}
