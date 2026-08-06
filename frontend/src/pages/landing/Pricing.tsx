import { Link } from 'react-router-dom';
import { Check, Minus } from 'lucide-react';
import { SUPPORT_MAILTO } from '../../lib/contact';
import { Reveal, Section, SectionHeading } from './primitives';
import { AVAILABILITY_LABEL, AVAILABILITY_STYLE, COMPARISON, PRICING_CARDS } from './plans';

/**
 * Pricing, with a paid tier advertised but not pretended into existence.
 *
 * The rule this section is built on: it is fine to advertise a future paid plan, and not fine to
 * imply it is already available. So Premium, Family and Enterprise carry a status badge WHERE THE
 * PRICE WOULD GO, rather than a number. That placement is deliberate -- a price with a small
 * "coming soon" tag beside it still reads as a price, and the earlier version of this page
 * displayed ₹149 and ₹249 that nobody had actually decided on. Inventing a number you later have
 * to change is its own kind of dishonesty, and the first people to notice are the ones who
 * screenshotted it.
 *
 * There is no billing anywhere in the backend -- no plan field on User, no payment integration --
 * so nothing here can be purchased today by design, not by oversight.
 *
 * The waitlist CTA is a mailto, using the same SUPPORT_MAILTO the rest of the app uses. That is
 * not a placeholder: a form posting to nothing would look more finished and collect nobody, which
 * is exactly what the old newsletter box on this page did. A mailto actually delivers.
 */
const WAITLIST_MAILTO = `${SUPPORT_MAILTO}?subject=${encodeURIComponent('Finora Premium waitlist')}`;

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

              <p className="text-sm mb-5" style={{ color: 'var(--m-ink-2)' }}>{plan.blurb}</p>

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
              ) : plan.availability === 'coming-soon' ? (
                <a href={WAITLIST_MAILTO} className="m-btn m-btn-ghost w-full">Join the waitlist</a>
              ) : (
                <span className="m-btn m-btn-ghost w-full opacity-55 cursor-default" aria-disabled="true">Not yet planned for release</span>
              )}
            </div>
          </Reveal>
        ))}
      </div>

      {/* ---- Comparison ---- */}
      <Reveal delayMs={160}>
        <div className="max-w-2xl mx-auto mt-14 m-card overflow-hidden">
          <table className="w-full text-sm">
            <caption className="sr-only">Feature comparison between the Free plan and the planned Premium plan</caption>
            <thead>
              <tr style={{ background: '#F8FAFC' }}>
                <th scope="col" className="text-left font-semibold px-5 py-3" style={{ color: 'var(--m-ink)' }}>Feature</th>
                <th scope="col" className="px-4 py-3 font-semibold w-24" style={{ color: 'var(--m-ink)' }}>Free</th>
                <th scope="col" className="px-4 py-3 font-semibold w-32" style={{ color: 'var(--m-ink)' }}>
                  Premium
                  <span className="block text-[9px] font-medium normal-case tracking-normal" style={{ color: 'var(--m-ink-3)' }}>
                    coming soon
                  </span>
                </th>
              </tr>
            </thead>
            <tbody>
              {COMPARISON.map(({ label, free, premium }) => (
                <tr key={label} className="border-t" style={{ borderColor: 'var(--m-line)' }}>
                  <th scope="row" className="text-left font-normal px-5 py-3" style={{ color: 'var(--m-ink-2)' }}>{label}</th>
                  <td className="text-center px-4 py-3">
                    {free
                      ? <Check size={16} className="inline text-[#16A34A]" aria-label="Included" />
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
