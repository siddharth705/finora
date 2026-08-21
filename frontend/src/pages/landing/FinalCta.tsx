import { Link } from 'react-router-dom';
import { ArrowRight } from 'lucide-react';
import { Reveal } from './primitives';
import { finalCta } from './landing-config';

/**
 * Closes on the object the visitor already has sitting in their inbox, rather than on an abstract
 * question. "Ready to understand your money?" asks them to want something; "your next statement
 * doesn't have to be another PDF" points at a thing that is about to happen anyway.
 *
 * Deliberately ONE action. This carried a second "Join Premium waitlist" button until it was
 * pointed out that nothing stores the interest -- see Pricing.tsx for the full reasoning. A CTA
 * that collects nothing is worse than the empty space it fills, and on a page whose entire
 * argument is trust, it is worse than that.
 */
export function FinalCta() {
  return (
    <section style={{ background: 'linear-gradient(135deg,var(--m-brand) 0%,var(--m-brand-deep) 100%)' }}>
      <div className="max-w-3xl mx-auto px-5 sm:px-6 py-24 text-center">
        <Reveal>
          <h2 className="m-h2 mb-4" style={{ color: '#fff' }}>
            {finalCta.title}
            <br />
            {finalCta.titleLine2}
          </h2>
          <p className="text-lg mb-9" style={{ color: 'rgb(255 255 255 / .82)' }}>{finalCta.blurb}</p>
          <div className="flex justify-center">
            <Link to="/register" className="m-btn w-full sm:w-auto bg-white text-[var(--m-brand-deep)] hover:bg-slate-50">
              {finalCta.primary} <ArrowRight size={16} />
            </Link>
          </div>
          <p className="text-sm mt-5" style={{ color: 'rgb(255 255 255 / .7)' }}>{finalCta.footnote}</p>
        </Reveal>
      </div>
    </section>
  );
}
