import { useState } from 'react';
import { Minus, Plus } from 'lucide-react';
import { Reveal, Section, SectionHeading } from './primitives';
import { faq } from './landing-config';

export function Faq() {
  const [open, setOpen] = useState<number | null>(0);

  return (
    <Section id="faq" tone="alt">
      <SectionHeading eyebrow={faq.eyebrow} title={faq.title} />
      <div className="max-w-3xl mx-auto">
        {faq.items.map(([q, a], i) => {
          const isOpen = open === i;
          return (
            <Reveal key={q} delayMs={i * 40}>
              <div className="border-b" style={{ borderColor: 'var(--m-line)' }}>
                <button
                  type="button"
                  onClick={() => setOpen(isOpen ? null : i)}
                  aria-expanded={isOpen}
                  className="w-full flex items-center justify-between gap-4 py-5 text-left"
                >
                  <span className="text-[15px] font-semibold" style={{ color: 'var(--m-ink)' }}>{q}</span>
                  <span className="shrink-0" style={{ color: 'var(--m-ink-3)' }}>
                    {isOpen ? <Minus size={17} /> : <Plus size={17} />}
                  </span>
                </button>
                {isOpen ? (
                  <p className="pb-5 text-[15px] leading-relaxed max-w-2xl" style={{ color: 'var(--m-ink-2)' }}>{a}</p>
                ) : null}
              </div>
            </Reveal>
          );
        })}
      </div>
    </Section>
  );
}
