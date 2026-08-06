import { Link } from 'react-router-dom';
import { ArrowRight, Check } from 'lucide-react';
import { DashboardMock } from './DashboardMock';
import { Reveal } from './primitives';

/**
 * Opens on the idea, not the feature list.
 *
 * "Money tells a story. Finora helps you read it." earns the scroll because it poses something
 * rather than announcing something -- and it is a promise the product can actually keep, which
 * rules out the more tempting version ("Finora tells you where your life is going"). A finance
 * app that claims to know where your life is going has started lying in its first sentence.
 *
 * The brand line survives as the subhead: it is in the README, the footer and the app, and
 * throwing it away for a fresh headline would cost more than it gains.
 */
export function Hero() {
  return (
    <section className="relative overflow-hidden" style={{ background: 'linear-gradient(180deg,#FFFFFF 0%,#FBFCFE 100%)' }}>
      <div
        aria-hidden="true"
        className="absolute -top-40 -right-40 w-[640px] h-[640px] rounded-full blur-3xl pointer-events-none"
        style={{ background: 'radial-gradient(circle, rgba(37,99,235,.14), transparent 68%)' }}
      />
      <div className="relative max-w-6xl mx-auto px-5 sm:px-6 pt-16 pb-14 lg:pt-24 lg:pb-20">
        <div className="grid lg:grid-cols-[minmax(0,1fr)_minmax(0,1.15fr)] gap-14 items-center">
          <Reveal>
            <h1 className="m-display mb-5">
              Money tells a story.
              <br />
              <span style={{ color: 'var(--m-brand)' }}>Finora helps you read it.</span>
            </h1>
            <p className="m-lead mb-8 max-w-lg">
              Understand every rupee, not just your balance. Upload a statement — Finora does the
              rest.
            </p>
            <div className="flex flex-col sm:flex-row gap-3 mb-8">
              <Link to="/register" className="m-btn m-btn-primary w-full sm:w-auto">
                Import your first statement <ArrowRight size={16} />
              </Link>
              <a href="#how" className="m-btn m-btn-ghost w-full sm:w-auto">See how it works</a>
            </div>
            <ul className="grid sm:grid-cols-2 gap-x-6 gap-y-2.5">
              {['Secure by design', 'Learns from your corrections', 'Explains its decisions', 'No upsells, ever'].map((t) => (
                <li key={t} className="flex items-center gap-2 text-sm" style={{ color: 'var(--m-ink-2)' }}>
                  <Check size={15} className="text-[#16A34A] shrink-0" />
                  {t}
                </li>
              ))}
            </ul>
          </Reveal>

          {/* Simplest of the three depths, and it builds itself as the visitor arrives. The page
              shows progressively more of the product further down -- see DashboardMock's note. */}
          <Reveal delayMs={120}>
            <DashboardMock level="simple" progressive />
          </Reveal>
        </div>
      </div>
    </section>
  );
}
