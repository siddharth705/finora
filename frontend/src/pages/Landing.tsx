import { Link } from 'react-router-dom';
import { ArrowRight } from 'lucide-react';
import { Nav } from './landing/Nav';
import { Hero } from './landing/Hero';
import { Problem } from './landing/Problem';
import { ImportSection } from './landing/ImportSection';
import { LearningSection } from './landing/LearningSection';
import { BeforeAfter } from './landing/BeforeAfter';
import { Journey } from './landing/Journey';
import { Trust } from './landing/Trust';
import { Security } from './landing/Security';
import { DashboardShowcase } from './landing/DashboardShowcase';
import { Everywhere } from './landing/Everywhere';
import { UseCases } from './landing/UseCases';
import { WhyUpgrade } from './landing/WhyUpgrade';
import { Pricing } from './landing/Pricing';
import { Faq } from './landing/Faq';
import { FinalCta } from './landing/FinalCta';
import { SiteFooter } from './landing/SiteFooter';
import { Transition } from './landing/primitives';

/**
 * The landing page, as composition only.
 *
 * Every section is its own file under ./landing so copy, layout and animation can be changed --
 * or A/B tested, or replaced entirely -- without opening a thousand-line component. This file is
 * the running order and nothing else; if you are here to edit words, you are in the wrong file.
 *
 * THE RUNNING ORDER IS THE ARGUMENT. It is not a list of things the product does, it is one
 * continuous claim, and each section only makes sense in its position:
 *
 *   Hero .............. money tells a story
 *   Problem ........... reading it is the hard part          <- their month, named
 *   Import ............ so hand it over once                 <- the mechanism
 *   Learning .......... and it stops needing you             <- why it compounds
 *   Before / After .... this is what changes                 <- the payoff, stated plainly
 *   Journey ........... and it keeps changing                <- why they stay
 *   Trust ............. here is why we won't abuse it        <- the objection, met
 *   Security .......... and here is how it is protected
 *   Showcase .......... all of it, in one place
 *   Everywhere ........ wherever you are
 *   Use cases ......... whoever you are
 *   Why upgrade ....... and it grows with you                <- earns the price list
 *   Pricing ........... free now, paid later, honestly
 *   FAQ ............... the last few doubts
 *   Final CTA ......... your next statement is already coming
 *
 * Reordering sections breaks the argument even though nothing will error. Before moving one, work
 * out which question it answers and whether that question has been raised yet.
 *
 * The <Transition> bands are why this reads as one page rather than fourteen. Each interpolates
 * the surface it leaves into the surface it enters, so no boundary lands as a hard edge -- most
 * of all where the page falls into a dark band, which without the bleed reads as a slide change.
 * Their colours must match the adjoining sections' `tone`; a mismatch shows as a visible seam.
 *
 * Claim discipline, carried forward and non-negotiable on a financial product: no invented
 * testimonials, no fabricated counters, no customer logos, and nothing described as available
 * that isn't. Where a capability is real but unreleased -- the native apps, the paid tiers -- the
 * copy says so. Several sections carry a comment recording exactly what was verified; keep those
 * up to date rather than deleting them.
 */

const WHITE = '#FFFFFF';
const ALT = '#F8FAFC';
const DEEP = '#0B1220';

export default function Landing() {
  return (
    <div className="marketing">
      {/* Keyboard/screen-reader users otherwise have to tab through the entire nav (5 anchors +
          2 CTAs) before reaching any page content, on a page with 15 sections below it. Visually
          hidden until focused, per the standard sr-only/focus:not-sr-only pattern. */}
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:z-50 focus:rounded-lg focus:bg-[#2563EB] focus:px-4 focus:py-2.5 focus:text-sm focus:font-semibold focus:text-white"
      >
        Skip to content
      </a>
      <Nav />

      <main id="main-content">
        <Hero />
        {/* Hero already fades toward #FBFCFE, so this picks up close to where it ends. */}
        <Transition from="#FBFCFE" to={WHITE} height={48} />

        <Problem />
        <Transition from={WHITE} to={WHITE} height={0} />

        <ImportSection />
        <Transition from={WHITE} to={DEEP} height={112} />

        <LearningSection />
        <Transition from={DEEP} to={ALT} height={112} />

        <BeforeAfter />
        <Transition from={ALT} to={WHITE} />

        <Journey />
        <Transition from={WHITE} to={DEEP} height={112} />

        <Trust />
        <Transition from={DEEP} to={ALT} height={112} />

        <Security />
        <Transition from={ALT} to={WHITE} />

        <DashboardShowcase />
        <Transition from={WHITE} to={WHITE} height={0} />

        <Everywhere />
        <Transition from={WHITE} to={ALT} />

        <UseCases />
        <Transition from={ALT} to={WHITE} />

        {/* Sits between "who it's for" and the price list on purpose: a price list provokes the
            question "why would I pay?" but cannot answer it. This does, before it is asked. */}
        <WhyUpgrade />
        <Transition from={WHITE} to={ALT} />

        <Pricing />
        <Transition from={ALT} to={WHITE} />

        <Faq />
        <Transition from={WHITE} to="#2563EB" height={72} />

        <FinalCta />
      </main>
      <SiteFooter />

      {/* Sticky mobile action bar. Phone-only: at md+ the hero CTAs and the nav button are both
          still in reach, so a permanent bar would only cover content. */}
      <div className="m-mobile-cta md:hidden">
        <Link to="/register" className="m-btn m-btn-primary w-full">
          Import your first statement <ArrowRight size={16} />
        </Link>
      </div>
      {/* Reserves the space the fixed bar covers so the footer's last line stays reachable. */}
      <div className="h-20 md:hidden" aria-hidden="true" />
    </div>
  );
}
