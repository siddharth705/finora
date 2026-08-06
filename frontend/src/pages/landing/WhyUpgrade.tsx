import { Reveal, Section, SectionHeading } from './primitives';
import { AVAILABILITY_LABEL, AVAILABILITY_STYLE, PLANS } from './plans';

/**
 * The beat between "who it's for" and the price list, answering the question a price list
 * provokes but cannot answer: why would I ever pay for this?
 *
 * It merges two asks that were specified as separate sections -- "Why Upgrade?" and "Built for
 * Today, Ready for Tomorrow" -- because run back to back they are the same section twice: both
 * open on start-free-grow-later and both resolve into a staged progression. Two adjacent sections
 * making one point is exactly the "feels like independent sections" problem this page was
 * restructured to fix. The tomorrow framing is the headline; the ladder is the visual.
 *
 * The ladder reads from ./plans, the same config the pricing cards below use, so the two cannot
 * drift into describing different products -- which is exactly what happened while each kept its
 * own hardcoded list.
 *
 * CLAIM DISCIPLINE, and this is the easiest section on the page to get wrong: only the Free column
 * describes software that exists. Everything else is intent, and each card carries a visible
 * status label so no reader has to infer which is which. Do not move an item into Free without
 * the feature actually shipping -- a roadmap on a public page is a promise people remember.
 */
export function WhyUpgrade() {
  return (
    <Section id="upgrade">
      <SectionHeading
        eyebrow="Growing with you"
        title={<>Built for today.<br />Ready for tomorrow.</>}
        blurb="Start with the core experience, free. As your financial life gets more complicated, Finora is built to get more capable — not to start charging for what already worked."
      />

      <div className="grid sm:grid-cols-2 lg:grid-cols-4 gap-4 max-w-5xl mx-auto">
        {PLANS.map((plan, i) => (
          <Reveal key={plan.id} delayMs={i * 90}>
            <div
              className="rounded-2xl p-6 h-full border"
              style={plan.availability === 'available'
                ? { borderColor: '#BFDBFE', background: 'linear-gradient(180deg,#F5F9FF 0%,#FFFFFF 100%)' }
                : { borderColor: 'var(--m-line)', background: '#fff' }}
            >
              <div className="flex items-center justify-between mb-4">
                <h3 className="m-h3 text-lg">{plan.name}</h3>
                <span className="text-[9px] uppercase tracking-wide font-semibold px-2 py-1 rounded-full" style={AVAILABILITY_STYLE[plan.availability]}>
                  {AVAILABILITY_LABEL[plan.availability]}
                </span>
              </div>
              <ul className="space-y-2">
                {plan.ladder.map((item) => (
                  <li key={item} className="flex items-start gap-2 text-sm" style={{ color: 'var(--m-ink-2)' }}>
                    <span
                      className="w-1.5 h-1.5 rounded-full shrink-0 mt-2"
                      style={{ background: plan.availability === 'available' ? '#16A34A' : plan.availability === 'coming-soon' ? '#2563EB' : '#CBD5E1' }}
                    />
                    {item}
                  </li>
                ))}
              </ul>
            </div>
          </Reveal>
        ))}
      </div>

      <Reveal delayMs={280}>
        <p className="text-center text-sm mt-8" style={{ color: 'var(--m-ink-3)' }}>
          Only the first column exists today. The rest is what we&apos;re building — published here
          so you can hold us to it.
        </p>
      </Reveal>
    </Section>
  );
}
