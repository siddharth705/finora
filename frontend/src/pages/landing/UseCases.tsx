import { Briefcase, GraduationCap, Users, Wallet } from 'lucide-react';
import { Reveal, Section, SectionHeading } from './primitives';

const AUDIENCES = [
  { icon: <Briefcase size={18} />, title: 'Working professionals', body: 'Salary in, spending out, savings visible — without keeping a spreadsheet alive.' },
  { icon: <Users size={18} />, title: 'Families', body: 'Household money in one place, so it can be discussed instead of guessed at.' },
  { icon: <Wallet size={18} />, title: 'Freelancers', body: 'Irregular income made legible, and the cash flow that follows it.' },
  { icon: <GraduationCap size={18} />, title: 'Students', body: 'Build the habit early, while the numbers are still small enough to learn on.' },
];

export function UseCases() {
  return (
    <Section tone="alt">
      <SectionHeading eyebrow="Made for everyone" title="For every stage of your life." blurb="One platform. Endless clarity." />
      <div className="grid sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {AUDIENCES.map((a, i) => (
          <Reveal key={a.title} delayMs={i * 70}>
            <div className="m-card m-card-hover p-6 h-full">
              <span className="w-10 h-10 rounded-xl grid place-items-center mb-4" style={{ background: 'var(--m-brand-wash)', color: 'var(--m-brand)' }}>
                {a.icon}
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
