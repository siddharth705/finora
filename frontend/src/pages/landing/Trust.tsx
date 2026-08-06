import { Check, X } from 'lucide-react';
import { Reveal, Section, SectionHeading } from './primitives';
import { ShieldMark } from './diagrams';

/**
 * The signature section.
 *
 * Every line on the left is a commitment the product can be held to today: there are no affiliate
 * links, referral commissions or sponsored placements anywhere in the codebase, and no route that
 * sells or shares user data. Do not add a line here that isn't equally checkable.
 *
 * The "why" block underneath is the part that makes the list credible. A list of promises is
 * cheap; a business reason those promises hold is not. Finora has no revenue that depends on
 * which product a user picks, and saying so plainly is stronger than any badge.
 */
const NEVER = [
  'Sell your financial data',
  'Push loans at you',
  'Push credit cards at you',
  'Recommend a product because someone paid us',
  'Hide how a decision was made',
];

const ALWAYS = [
  'Your data stays yours',
  'Every automatic decision can be reviewed',
  'You make the final call',
  'Your corrections improve future imports',
  'Built on trust, not commissions',
];

export function Trust() {
  return (
    <Section id="trust" tone="deep">
      <SectionHeading invert eyebrow="Complete transparency" title="What Finora Will Never Do." />

      <div className="grid lg:grid-cols-[1fr_auto_1fr] gap-10 items-center">
        <Reveal>
          <ul className="space-y-3.5">
            {NEVER.map((t) => (
              <li key={t} className="flex items-start gap-3">
                <span className="w-5 h-5 rounded-full grid place-items-center shrink-0 mt-0.5" style={{ background: 'rgb(239 68 68 / .16)' }}>
                  <X size={12} className="text-[#F87171]" />
                </span>
                <span className="text-[15px] text-slate-300">{t}</span>
              </li>
            ))}
          </ul>
        </Reveal>

        <Reveal delayMs={120} className="hidden lg:block"><ShieldMark /></Reveal>

        <Reveal delayMs={200}>
          <ul className="space-y-3.5">
            {ALWAYS.map((t) => (
              <li key={t} className="flex items-start gap-3">
                <span className="w-5 h-5 rounded-full grid place-items-center shrink-0 mt-0.5" style={{ background: 'rgb(22 163 74 / .18)' }}>
                  <Check size={12} className="text-[#4ADE80]" />
                </span>
                <span className="text-[15px] text-slate-300">{t}</span>
              </li>
            ))}
          </ul>
        </Reveal>
      </div>

      <Reveal delayMs={260}>
        <div
          className="max-w-2xl mx-auto mt-14 rounded-2xl px-7 py-6 text-center"
          style={{ background: 'rgb(255 255 255 / .05)', border: '1px solid rgb(255 255 255 / .10)' }}
        >
          <p className="m-eyebrow mb-2">Why?</p>
          <p className="text-lg leading-relaxed text-slate-200">
            Because we don&apos;t make money selling financial products.
          </p>
          <p className="text-[15px] leading-relaxed text-slate-400 mt-2">
            No commissions, no referral fees, no sponsored placements. Our success depends entirely
            on building software people trust — which only works if the advice was never for sale.
          </p>
        </div>
      </Reveal>
    </Section>
  );
}
