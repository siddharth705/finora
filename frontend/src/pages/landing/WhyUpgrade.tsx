import { Reveal, Section, SectionHeading } from './primitives';
import { AVAILABILITY_LABEL, AVAILABILITY_STYLE, PLANS } from './plans';

/**
 * The beat between "who it's for" and the price list, answering the question a price list
 * provokes but cannot answer: why would I ever pay for this?
 *
 * It sells PROGRESS, not plans. Each rung leads with what the reader gets to do -- organize,
 * understand, plan, share -- and the plan name is secondary. That ordering is the whole point:
 * "unlimited accounts" is a specification, "understand your spending patterns" is a reason.
 *
 * It also doubles as the product roadmap, which is why there is no separate vision section. A
 * second staged progression sitting next to this one would be the same content twice, and the
 * repetition is exactly what this page was restructured to remove.
 *
 * Reads from ./plans, the same config the pricing cards use, so the two cannot drift into
 * describing different products.
 *
 * CLAIM DISCIPLINE, and this is the easiest section on the page to get wrong: only the first rung
 * describes software that exists. Everything after it is intent, and every card carries a status
 * label so no reader has to infer which is which. Do not move an outcome earlier without the
 * feature actually shipping -- a roadmap on a public page is a promise people remember.
 */
export function WhyUpgrade() {
  return (
    <Section id="upgrade">
      <SectionHeading
        eyebrow="Growing with you"
        title={<>Built for today.<br />Ready for tomorrow.</>}
        blurb="Start with the core experience, free. As your financial life gets more complicated, Fynora is built to get more capable — not to start charging for what already worked."
      />

      <div className="grid sm:grid-cols-2 lg:grid-cols-3 gap-4 max-w-5xl mx-auto">
        {PLANS.map((plan, i) => {
          const live = plan.availability === 'available';
          return (
            <Reveal key={plan.id} delayMs={i * 90}>
              <div
                className="rounded-2xl p-6 h-full border flex flex-col"
                style={live
                  ? { borderColor: '#BFDBFE', background: 'linear-gradient(180deg,#F5F9FF 0%,#FFFFFF 100%)' }
                  : { borderColor: 'var(--m-line)', background: '#fff' }}
              >
                <p className="m-eyebrow mb-2">{plan.stage.when}</p>

                {/* The outcome is the headline. The plan name is a footnote to it. */}
                <h3 className="m-h3 text-lg leading-snug mb-3 flex-1">{plan.stage.outcome}</h3>

                <div className="flex items-center justify-between gap-2 pt-3 border-t" style={{ borderColor: 'var(--m-line)' }}>
                  <span className="text-xs font-semibold" style={{ color: 'var(--m-ink-3)' }}>{plan.name}</span>
                  <span
                    className="text-[9px] uppercase tracking-wide font-semibold px-2 py-1 rounded-full"
                    style={AVAILABILITY_STYLE[plan.availability]}
                  >
                    {AVAILABILITY_LABEL[plan.availability]}
                  </span>
                </div>
              </div>
            </Reveal>
          );
        })}
      </div>

      <Reveal delayMs={280}>
        <p className="text-center text-sm mt-8" style={{ color: 'var(--m-ink-3)' }}>
          Only the first step exists today. The rest is where Fynora is going — published here so
          you can hold us to it.
        </p>
      </Reveal>
    </Section>
  );
}
