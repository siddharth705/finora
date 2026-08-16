import { Link } from 'react-router-dom';
import { Check, Minus } from 'lucide-react';
import { Reveal, Section, SectionHeading } from './primitives';
import { AVAILABILITY_LABEL, AVAILABILITY_STYLE, COMPARISON, PRICING_CARDS } from './plans';

/**
 * Pricing, with a paid tier advertised but not pretended into existence.
 *
 * The rule this section is built on: it is fine to advertise a future paid plan, and not fine to
 * imply it is already available. So Plus and Premium carry a status badge WHERE THE PRICE WOULD
 * GO, rather than a number. That placement is deliberate -- a price with a small
 * "coming soon" tag beside it still reads as a price, and the earlier version of this page
 * displayed ₹149 and ₹249 that nobody had actually decided on. Inventing a number you later have
 * to change is its own kind of dishonesty, and the first people to notice are the ones who
 * screenshotted it.
 *
 * There is no billing anywhere in the backend -- no plan field on User, no payment integration --
 * so nothing here can be purchased today by design, not by oversight.
 *
 * THERE IS NO WAITLIST CTA, and that is a decision rather than an omission. This carried a
 * "Join the waitlist" mailto, which did deliver -- but nothing STORES the interest, nobody is
 * queued, and no notification fires when Premium launches. A button that cannot keep the promise
 * in its own label is the same failure as the newsletter box this page used to carry, which
 * thanked you and discarded the address. Unavailable tiers state their status and stop there.
 * Add the CTA the day an endpoint exists to receive it.
 *
 * The cards lead with what a plan is FOR rather than what it contains. Feature-by-feature belongs
 * in the comparison table below, where someone deliberately comparing can find it.
 */

export function Pricing() {
  return (
    <Section id="pricing" tone="alt">
      <SectionHeading eyebrow="Simple pricing" title="Simple pricing. No hidden costs." />

      <div className="grid md:grid-cols-3 gap-5 max-w-5xl mx-auto">
        {PRICING_CARDS.map((plan, i) => (
          <Reveal key={plan.name} delayMs={i * 80}>
            <div className={`m-card p-6 h-full flex flex-col ${plan.availability === 'available' ? 'ring-2 ring-[#2563EB]' : ''}`}>
              <div className="flex items-center justify-between mb-2">
                <p className="text-sm font-semibold" style={{ color: 'var(--m-ink)' }}>{plan.name}</p>
                <span className="text-[9px] uppercase tracking-wide font-semibold px-2 py-1 rounded-full" style={AVAILABILITY_STYLE[plan.availability]}>
                  {AVAILABILITY_LABEL[plan.availability]}
                </span>
              </div>

              {/* A status where the price goes, for anything that cannot be bought. */}
              {plan.price ? (
                <p className="text-3xl font-extrabold mb-1" style={{ fontFamily: "'Manrope', Inter, sans-serif", color: 'var(--m-ink)' }}>
                  {plan.price}
                  <span className="text-sm font-medium" style={{ color: 'var(--m-ink-3)' }}>{plan.cadence}</span>
                </p>
              ) : (
                <p className="text-2xl font-extrabold mb-1" style={{ fontFamily: "'Manrope', Inter, sans-serif", color: 'var(--m-ink-3)' }}>
                  Pricing TBD
                </p>
              )}

              <p className="text-[15px] font-medium mb-5" style={{ color: 'var(--m-ink)' }}>{plan.promise}</p>

              <ul className="space-y-2 mb-6 flex-1">
                {plan.features.map((f) => (
                  <li key={f} className="flex items-start gap-2 text-sm" style={{ color: 'var(--m-ink-2)' }}>
                    <Check size={14} className={plan.availability === 'available' ? 'text-[#16A34A] shrink-0 mt-1' : 'text-slate-300 shrink-0 mt-1'} />
                    {f}
                  </li>
                ))}
              </ul>

              {plan.availability === 'available' ? (
                <Link to="/register" className="m-btn m-btn-primary w-full">Start free</Link>
              ) : (
                // A statement, not a control. See the note at the top of this file.
                <p className="text-center text-sm py-3" style={{ color: 'var(--m-ink-3)' }}>
                  {plan.availability === 'coming-soon' ? 'Launching soon.' : 'Not yet scheduled.'}
                </p>
              )}
            </div>
          </Reveal>
        ))}
      </div>

      {/* ---- Comparison ---- */}
      <Reveal delayMs={160}>
        <div className="max-w-2xl mx-auto mt-14 m-card overflow-hidden">
          <table className="w-full text-sm">
            <caption className="sr-only">Feature comparison between the Free, Plus and Premium plans</caption>
            <thead>
              <tr style={{ background: '#F8FAFC' }}>
                <th scope="col" className="text-left font-semibold px-5 py-3" style={{ color: 'var(--m-ink)' }}>Feature</th>
                <th scope="col" className="px-4 py-3 font-semibold w-24" style={{ color: 'var(--m-ink)' }}>Free</th>
                <th scope="col" className="px-4 py-3 font-semibold w-28" style={{ color: 'var(--m-ink)' }}>
                  Plus
                  <span className="block text-[9px] font-medium normal-case tracking-normal" style={{ color: 'var(--m-ink-3)' }}>
                    coming soon
                  </span>
                </th>
                <th scope="col" className="px-4 py-3 font-semibold w-28" style={{ color: 'var(--m-ink)' }}>
                  Premium
                  <span className="block text-[9px] font-medium normal-case tracking-normal" style={{ color: 'var(--m-ink-3)' }}>
                    coming soon
                  </span>
                </th>
              </tr>
            </thead>
            <tbody>
              {COMPARISON.map(({ label, free, plus, premium }) => (
                <tr key={label} className="border-t" style={{ borderColor: 'var(--m-line)' }}>
                  <th scope="row" className="text-left font-normal px-5 py-3" style={{ color: 'var(--m-ink-2)' }}>{label}</th>
                  <td className="text-center px-4 py-3">
                    {free
                      ? <Check size={16} className="inline text-[#16A34A]" aria-label="Included" />
                      : <Minus size={16} className="inline text-slate-300" aria-label="Not included" />}
                  </td>
                  <td className="text-center px-4 py-3">
                    {plus
                      ? <Check size={16} className="inline text-[#2563EB]" aria-label="Included" />
                      : <Minus size={16} className="inline text-slate-300" aria-label="Not included" />}
                  </td>
                  <td className="text-center px-4 py-3">
                    {premium
                      ? <Check size={16} className="inline text-[#2563EB]" aria-label="Included" />
                      : <Minus size={16} className="inline text-slate-300" aria-label="Not included" />}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Reveal>

      {/* ---- Why a subscription at all ---- */}
      <Reveal delayMs={220}>
        <div className="max-w-2xl mx-auto mt-8 rounded-2xl p-6 sm:p-7" style={{ background: '#fff', border: '1px solid var(--m-line)' }}>
          <p className="m-eyebrow mb-2">Why a subscription?</p>
          <p className="text-[15px] leading-relaxed" style={{ color: 'var(--m-ink-2)' }}>
            Everyone should be able to understand their own money, so the core experience stays
            free. Subscriptions are how the rest gets funded — new features, security work, and the
            infrastructure behind them.
          </p>
          <p className="text-[15px] leading-relaxed mt-3" style={{ color: 'var(--m-ink-2)' }}>
            It is also the only funding model that keeps the incentives straight. A product paid
            for by advertisers or lenders eventually works for them.{' '}
            <span style={{ color: 'var(--m-ink)', fontWeight: 600 }}>
              One paid for by its users only has to be worth paying for.
            </span>
          </p>
        </div>
      </Reveal>
    </Section>
  );
}
