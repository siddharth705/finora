import { Link } from 'react-router-dom';
import { ArrowRight, Check } from 'lucide-react';
import { motion, useReducedMotion } from 'framer-motion';
import { hero } from './landing-config';
import { AmbientCanvas } from './hero/AmbientCanvas';
import { FloatingDashboardCard } from './hero/FloatingDashboardCard';
import { HealthScoreRing } from './hero/HealthScoreRing';
import { IntelligenceScan } from './hero/IntelligenceScan';
import { FloatingBadges } from './hero/FloatingBadges';

const EASE = [0.16, 1, 0.3, 1] as const;

/**
 * Cinematic reveal for the dark hero band. See
 * docs/superpowers/specs/2026-08-22-hero-cinematic-reveal-design.md -- the mount sequence
 * ("background/particles -> content -> dashboard -> score/insights") is staggered via a fixed
 * per-section `delay` on each motion.div's own `animate`, not via Framer Motion's
 * staggerChildren/variant-propagation mechanism: that mechanism relies on child motion
 * components inheriting their parent's `animate` label through React context, which -- verified
 * against a real browser during implementation, not just jsdom -- got stuck permanently at each
 * child's `initial` state under this app's React.StrictMode in main.tsx (children never reached
 * "show"). Explicit per-instance `initial`/`animate`/`transition` sidesteps that failure mode
 * entirely and is what every sub-component below (FloatingDashboardCard, FloatingBadges) already
 * does for the same reason.
 *
 * The ambient WebGL layer, the score ring and the intelligence-scan checklist are separate
 * components reusing (not replacing) the existing Reveal/CountUp/useStagedReveal primitives. The
 * dashboard preview itself is always real DOM -- never rendered inside WebGL -- so it stays crisp,
 * accessible and never depends on animation state to be understood.
 *
 * Copy lives in ./landing-config, unchanged from before this rewrite -- this file decides how the
 * hero looks, not what it says. See landing-config.ts's own note on the claim-review discipline
 * that applies to every sentence here.
 *
 * The fade from this section's dark background into white belongs to Landing.tsx's <Transition>
 * band immediately after <Hero />, like every other section boundary on this page -- Hero does
 * NOT own its own exit fade.
 */
export function Hero() {
  const prefersReducedMotion = useReducedMotion();

  function reveal(delay: number) {
    return prefersReducedMotion
      ? { initial: false as const, animate: { opacity: 1, y: 0 }, transition: { duration: 0 } }
      : {
          initial: { opacity: 0, y: 24 },
          animate: { opacity: 1, y: 0 },
          transition: { duration: 0.6, ease: EASE, delay },
        };
  }

  return (
    <section
      className="relative overflow-hidden"
      style={{
        background:
          'radial-gradient(120% 100% at 50% -10%, #16202E 0%, #0B1220 55%, #05070C 100%)',
      }}
    >
      <AmbientCanvas />

      <div className="relative z-10 max-w-6xl mx-auto px-5 sm:px-6 pt-28 pb-24 lg:pt-36 lg:pb-32">
        <div className="grid lg:grid-cols-[minmax(0,1fr)_minmax(0,1.15fr)] gap-14 items-start lg:items-center">
          <motion.div {...reveal(0)}>
            <h1 className="m-display mb-5" style={{ color: '#F8FAFC' }}>
              {hero.headline}
              <br />
              <span style={{ color: 'var(--m-success)' }}>{hero.headlineAccent}</span>
            </h1>
            <p className="m-lead mb-8 max-w-lg" style={{ color: '#94A3B8' }}>
              {hero.blurb}
            </p>
            <div className="flex flex-col sm:flex-row gap-3 mb-8">
              <Link to="/register" className="m-btn m-btn-primary w-full sm:w-auto">
                {hero.primaryCta} <ArrowRight size={16} />
              </Link>
              <a
                href="#how"
                className="m-btn m-btn-ghost w-full sm:w-auto"
                style={{ background: 'transparent', color: '#F8FAFC', borderColor: 'rgba(255,255,255,0.25)' }}
              >
                {hero.secondaryCta}
              </a>
            </div>
            <ul className="grid sm:grid-cols-2 gap-x-6 gap-y-2.5">
              {hero.assurances.map((t) => (
                <li key={t} className="flex items-center gap-2 text-sm" style={{ color: '#94A3B8' }}>
                  <Check size={15} className="shrink-0" style={{ color: 'var(--m-success)' }} />
                  {t}
                </li>
              ))}
            </ul>
          </motion.div>

          {/* Health score + intelligence scan live under the dashboard, not the text column --
              they're a claim ABOUT the product in the image above them, so they read as
              disconnected filler when stacked under the CTA/checklist instead. */}
          <div>
            <motion.div {...reveal(0.25)} className="relative">
              <FloatingDashboardCard />
              <FloatingBadges />
            </motion.div>

            <motion.div {...reveal(0.5)} className="mt-10 flex flex-wrap items-center gap-6">
              <HealthScoreRing />
              <IntelligenceScan />
            </motion.div>
          </div>
        </div>
      </div>
    </section>
  );
}
