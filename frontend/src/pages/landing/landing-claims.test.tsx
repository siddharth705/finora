import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import Landing from '../Landing';
import { AVAILABILITY_LABEL, PLANS } from './plans';
import { beforeAfter } from './landing-config';

/**
 * Enforces the mechanically-checkable half of docs/engineering/marketing-claims-checklist.md.
 *
 * It cannot decide whether a sentence is TRUE -- that is the reviewer's job, and the checklist is
 * the review. What it can do is make sure the specific mistakes this page has ALREADY SHIPPED
 * cannot come back silently: invented testimonials, fabricated usage counters, prices on tiers
 * nobody can buy, "bank-level encryption", and a subscribe box that discards the address.
 *
 * Each assertion below corresponds to a real regression, not a hypothetical one. If one starts
 * failing, the fix is almost always the copy, not the test.
 */
function renderLanding() {
  return render(
    <MemoryRouter>
      <Landing />
    </MemoryRouter>
  );
}

function pageText(): string {
  return document.body.innerText || document.body.textContent || '';
}

describe('landing page — marketing claims', () => {
  it('makes no fabricated social-proof claim', () => {
    renderLanding();
    const text = pageText();

    // Shipped once as "Trusted by thousands of users across India" and as three invented
    // testimonials. Both are unverifiable claims about real people.
    const patterns = [
      /trusted by [\d,]+/i,
      /trusted by (thousands|millions|hundreds)/i,
      /\b\d[\d,]*\+?\s*(users|customers|people)\b/i,
      /what (our |early )?users are saying/i,
      /join [\d,]+/i,
    ];
    const hits = patterns.filter((p) => p.test(text)).map(String);
    expect(hits).toEqual([]);
  });

  it('shows no usage counter presented as a live platform metric', () => {
    renderLanding();
    const text = pageText();

    // "486,000+ Transactions Processed" and friends were animated to look live and were invented.
    const patterns = [
      /[\d,]+\+?\s*(statements?|transactions?)\s*(imported|processed)/i,
      /[\d,]+\+?\s*(budgets?|goals?)\s*(managed|created)/i,
    ];
    expect(patterns.filter((p) => p.test(text)).map(String)).toEqual([]);
  });

  /**
   * The rule from plans.ts, asserted rather than trusted: a price may only appear on a plan that
   * can actually be bought. There is no billing in the backend, so today that is Free alone.
   */
  it('prices only the plans that are actually available', () => {
    for (const plan of PLANS) {
      if (plan.price !== null) {
        expect(
          plan.availability,
          `Plan "${plan.name}" carries a price but is not available for purchase. ` +
            'Show its status where the price would go instead — see plans.ts.'
        ).toBe('available');
      }
    }
    expect(PLANS.filter((p) => p.availability === 'available').map((p) => p.id)).toEqual(['free']);
  });

  it('renders no rupee price for an unreleased tier', () => {
    const { container } = renderLanding();

    // Scoped to the pricing section rather than the whole page. An earlier version of this test
    // scanned everything and tried to tell plan prices from the dashboard illustration's sample
    // amounts by magnitude -- which flagged a ₹480 Swiggy charge as a suspicious price. The
    // section boundary is the real distinction, so use it.
    const pricing = container.querySelector('#pricing');
    expect(pricing, 'The pricing section should have id="pricing"').not.toBeNull();

    const rendered = (pricing?.textContent ?? '').match(/₹[\d,]+/g) ?? [];
    const allowed = PLANS.filter((p) => p.price).map((p) => p.price as string);

    // ₹149 and ₹249 shipped here on tiers with no billing behind them.
    expect(
      rendered.filter((p) => !allowed.includes(p)),
      'A price is rendered in the pricing section for a plan that is not purchasable. ' +
        'Show its availability where the price would go instead — see plans.ts.'
    ).toEqual([]);
  });

  it('does not overstate encryption', () => {
    renderLanding();
    const text = pageText();

    // What is true: TLS in transit, bcrypt hashing, content-addressed integrity checks. These
    // three phrases claim more than the application implements.
    const overclaims = [/bank[- ]level encryption/i, /military[- ]grade/i, /end[- ]to[- ]end encrypt/i];
    expect(overclaims.filter((p) => p.test(text)).map(String)).toEqual([]);
  });

  it('exposes no form that submits nowhere', () => {
    const { container } = renderLanding();

    // The old newsletter box accepted an email, thanked the visitor, and discarded it. Any email
    // capture on this page must post somewhere real -- today the waitlist is a mailto, which does.
    const emailInputs = container.querySelectorAll('input[type="email"]');
    expect(
      emailInputs.length,
      'An email input on the landing page must submit somewhere real. The previous newsletter ' +
        'box discarded the address. Use the mailto waitlist, or wire a real endpoint.'
    ).toBe(0);
  });

  // The waitlist assertion that used to sit here required a waitlist link to EXIST. It was
  // removed with the CTA itself: nothing stores the interest, so the control could not keep the
  // promise in its own label. The inverse rule -- no signup control without a destination -- is
  // enforced below, and is the one that actually matters.

  it('labels the unreleased mobile apps as unreleased', () => {
    renderLanding();
    // Built through Phase 5, on no app store. The page may mention them; it may not imply a
    // download exists.
    expect(screen.getByText(/not on the app stores yet/i)).toBeTruthy();
  });

  it('opens external links safely', () => {
    const { container } = renderLanding();
    for (const link of container.querySelectorAll('a[target="_blank"]')) {
      const rel = link.getAttribute('rel') ?? '';
      expect(rel).toContain('noopener');
      expect(rel).toContain('noreferrer');
    }
  });
});

/**
 * Controls that promise something must be able to deliver it. Every rule here corresponds to a
 * dead control that actually shipped -- a nav item that scrolled nowhere, a waitlist button that
 * stored nothing. A control the page cannot honour is the same broken promise as a false claim,
 * just wearing a border.
 */
describe('landing page — nothing that promises what it cannot do', () => {
  it('points every in-page link at a section that exists', () => {
    const { container } = renderLanding();

    // The nav's "Before & after" linked to #difference while no section carried that id, so the
    // item silently did nothing. Checked for every anchor rather than that one.
    const broken = [...container.querySelectorAll('a[href^="#"]')]
      .map((a) => a.getAttribute('href') as string)
      .filter((href) => href.length > 1)
      .filter((href) => !container.querySelector(`[id="${href.slice(1)}"]`));

    expect([...new Set(broken)], 'These anchors scroll nowhere — the target id does not exist.').toEqual([]);
  });

  /**
   * No waitlist, notify-me or subscribe control unless something receives it. A mailto counts
   * (it genuinely delivers); a button wired to nothing does not, and neither does a form with no
   * action -- that is what the old newsletter box was.
   */
  it('offers no signup control without a destination', () => {
    const { container } = renderLanding();

    const suspicious = [...container.querySelectorAll('a,button')]
      .filter((el) => /waitlist|notify me|subscribe|join the list/i.test(el.textContent ?? ''))
      .filter((el) => {
        const href = el.getAttribute('href') ?? '';
        return !(href.startsWith('mailto:') || href.startsWith('http') || href.startsWith('/'));
      })
      .map((el) => (el.textContent ?? '').trim().slice(0, 40));

    expect(
      suspicious,
      'A signup control must lead somewhere that receives it. Remove it, or wire a real endpoint.'
    ).toEqual([]);
  });

  it('never marks an available plan as coming soon, or an unavailable one as available', () => {
    for (const plan of PLANS) {
      const label = AVAILABILITY_LABEL[plan.availability];
      if (plan.availability === 'available') {
        expect(label, `"${plan.name}" is available but labelled "${label}".`).toMatch(/available/i);
      } else {
        expect(label, `"${plan.name}" is not available but labelled "${label}".`).not.toMatch(/available/i);
      }
    }
  });

  it('offers a purchase action only on a plan that can be purchased', () => {
    const { container } = renderLanding();
    const pricing = container.querySelector('#pricing');

    const buyish = [...(pricing?.querySelectorAll('a,button') ?? [])]
      .filter((el) => /start free|get started|subscribe|buy|upgrade now/i.test(el.textContent ?? ''));

    // Exactly one purchasable plan today, so exactly one call to action in this section.
    expect(buyish).toHaveLength(PLANS.filter((p) => p.availability === 'available').length);
  });

  // The parallel structure IS the argument of that section; unequal columns break the comparison.
  it('keeps the before and after columns the same length', () => {
    expect(beforeAfter.before).toHaveLength(beforeAfter.after.length);
  });
});
