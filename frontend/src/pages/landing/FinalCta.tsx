import { Link } from 'react-router-dom';
import { ArrowRight } from 'lucide-react';
import { SUPPORT_MAILTO } from '../../lib/contact';
import { Reveal } from './primitives';

/** Same address the pricing waitlist uses -- one place people land, one subject line to filter on. */
const WAITLIST_MAILTO = `${SUPPORT_MAILTO}?subject=${encodeURIComponent('Finora Premium waitlist')}`;

/**
 * Closes on the object the visitor already has sitting in their inbox, rather than on an abstract
 * question. "Ready to understand your money?" asks them to want something; "your next statement
 * doesn't have to be another PDF" points at a thing that is about to happen anyway.
 */
export function FinalCta() {
  return (
    <section style={{ background: 'linear-gradient(135deg,#2563EB 0%,#1D4ED8 100%)' }}>
      <div className="max-w-3xl mx-auto px-5 sm:px-6 py-24 text-center">
        <Reveal>
          <h2 className="m-h2 mb-4" style={{ color: '#fff' }}>
            Your next bank statement
            <br />
            doesn&apos;t have to be another PDF.
          </h2>
          <p className="text-lg mb-9" style={{ color: 'rgb(255 255 255 / .82)' }}>
            Let Finora turn it into clarity.
          </p>
          {/* Two genuinely different intents, not the same destination twice: start now, or
              register interest in the paid tier that doesn't exist yet. The second is a mailto
              rather than a form, for the reason given in Pricing.tsx -- it actually delivers. */}
          <div className="flex flex-col sm:flex-row gap-3 justify-center">
            <Link to="/register" className="m-btn w-full sm:w-auto bg-white text-[#1D4ED8] hover:bg-slate-50">
              Start free <ArrowRight size={16} />
            </Link>
            <a
              href={WAITLIST_MAILTO}
              className="m-btn w-full sm:w-auto text-white"
              style={{ border: '1px solid rgb(255 255 255 / .35)' }}
            >
              Join Premium waitlist
            </a>
          </div>
          <p className="text-sm mt-5" style={{ color: 'rgb(255 255 255 / .7)' }}>
            Free forever for the core product. No credit card required.
          </p>
        </Reveal>
      </div>
    </section>
  );
}
