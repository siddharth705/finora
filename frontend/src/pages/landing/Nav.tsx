import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, Menu, X } from 'lucide-react';
import { BrandMark } from '../../components/BrandMark';
import { MagneticLink } from './MagneticLink';

const LINKS: [string, string][] = [
  ['How it works', '#how'],
  ['Before & after', '#difference'],
  ['Trust', '#trust'],
  ['Pricing', '#pricing'],
  ['FAQ', '#faq'],
];

export function Logo({ invert = false }: { invert?: boolean }) {
  return (
    <Link to="/" className="m-tap flex items-center gap-2">
      <BrandMark size={32} invert={invert} className="rounded-lg" />
      <span
        className="font-extrabold tracking-tight text-[17px]"
        style={{ fontFamily: "'Manrope', Inter, sans-serif", color: invert ? '#F8FAFC' : '#0F172A' }}
      >
        Finora
      </span>
    </Link>
  );
}

/**
 * `overHero` -- owned by Landing.tsx, driven by an IntersectionObserver watching the Hero
 * section (see the global chrome design spec).
 *
 * The header does NOT overlap Hero. While `overHero` is true it sits in normal document flow,
 * `position: static`, right above Hero -- it scrolls away with the page exactly like any other
 * content, dark to match Hero's own background so the light text stays legible against it. The
 * instant `overHero` flips false (Hero's bottom edge reaches the navbar's own height from the top
 * of the viewport -- see Landing.tsx's rootMargin), the header switches to `position: sticky` and
 * its normal white/opaque look, pinning at the top of the viewport for the rest of the page.
 *
 * This replaces an earlier "transparent header sticky-overlapping Hero's top 64px the whole time"
 * design (a negative-margin trick) that visitors reported as a real, reproducible overlap glitch
 * -- Hero's own floating elements (the dashboard preview card, badges) could end up visually
 * colliding with navbar text once scrolled near the top of the viewport. Static-then-sticky avoids
 * the whole class of bug: there is never a moment where the header and Hero's content occupy the
 * same screen space at full opacity.
 */
export function Nav({ overHero }: { overHero: boolean }) {
  const [open, setOpen] = useState(false);

  return (
    <header
      className="z-30 transition-all duration-300"
      style={{
        position: overHero ? 'static' : 'sticky',
        top: overHero ? undefined : 0,
        // #16202E matches Hero's own gradient (see Hero.tsx: 'radial-gradient(... #16202E 0% ...)')
        // at its top stop -- not an arbitrary dark tone. A mismatched flat color here (an earlier
        // version used #0B1220, the gradient's 55%-stop color) left a visible seam where the
        // navbar's flat box met Hero's actual top edge; matching the 0% stop makes the two read as
        // one continuous surface instead of a bar sitting on top of Hero.
        background: overHero ? '#16202E' : 'rgb(255 255 255 / .88)',
        backdropFilter: overHero ? 'none' : 'blur(12px)',
        borderBottom: overHero ? 'none' : '1px solid var(--m-line)',
        boxShadow: overHero ? 'none' : '0 1px 0 rgba(15,23,42,.06), 0 8px 24px -16px rgba(15,23,42,.25)',
      }}
    >
      <div className="max-w-6xl mx-auto px-5 sm:px-6 h-16 flex items-center justify-between">
        <Logo invert={overHero} />
        <nav className="hidden md:flex items-center gap-8 text-sm" style={{ color: overHero ? '#F8FAFC' : 'var(--m-ink-2)' }}>
          {LINKS.map(([label, href]) => (
            <a
              key={href}
              href={href}
              className="hover:text-[#0F172A] transition-colors"
              style={{ color: overHero ? 'rgba(248,250,252,0.85)' : undefined }}
              onMouseEnter={(e) => { if (overHero) e.currentTarget.style.color = '#F8FAFC'; }}
              onMouseLeave={(e) => { if (overHero) e.currentTarget.style.color = 'rgba(248,250,252,0.85)'; }}
            >
              {label}
            </a>
          ))}
        </nav>
        <div className="flex items-center gap-3">
          <Link
            to="/auth"
            className="hidden sm:block text-sm transition-colors"
            style={{ color: overHero ? 'rgba(248,250,252,0.85)' : 'var(--m-ink-2)' }}
            onMouseEnter={(e) => { e.currentTarget.style.color = overHero ? '#F8FAFC' : '#0F172A'; }}
            onMouseLeave={(e) => { e.currentTarget.style.color = overHero ? 'rgba(248,250,252,0.85)' : 'var(--m-ink-2)'; }}
          >
            Log in
          </Link>
          <MagneticLink to="/auth" className="m-btn m-btn-primary !min-h-[44px] !px-4 !text-sm">
            Get started <ArrowRight size={14} />
          </MagneticLink>
          <button
            type="button"
            onClick={() => setOpen((v) => !v)}
            className="md:hidden w-11 h-11 grid place-items-center rounded-lg border"
            style={{ borderColor: overHero ? 'rgba(255,255,255,0.35)' : 'var(--m-line)', color: overHero ? '#F8FAFC' : undefined }}
            aria-label={open ? 'Close menu' : 'Open menu'}
            aria-expanded={open}
          >
            {open ? <X size={18} /> : <Menu size={18} />}
          </button>
        </div>
      </div>
      {open ? (
        <div
          className="md:hidden border-t px-5 py-2"
          style={{
            borderColor: overHero ? 'rgba(255,255,255,0.15)' : 'var(--m-line)',
            background: overHero ? 'rgba(5,7,12,0.96)' : undefined,
          }}
        >
          {LINKS.map(([label, href]) => (
            <a
              key={href}
              href={href}
              onClick={() => setOpen(false)}
              className="m-tap block text-sm"
              style={{ color: overHero ? '#F8FAFC' : 'var(--m-ink-2)' }}
            >
              {label}
            </a>
          ))}
          <Link
            to="/auth"
            className="m-tap block text-sm"
            style={{ color: overHero ? '#F8FAFC' : 'var(--m-ink-2)' }}
          >
            Log in
          </Link>
        </div>
      ) : null}
    </header>
  );
}
