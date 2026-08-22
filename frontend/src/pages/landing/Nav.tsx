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
 * section (see the global chrome design spec) -- replaces the old scroll-position-based
 * `scrolled` state entirely. While over Hero the header is transparent with light/inverted
 * text so it reads against Hero's dark background; once the user scrolls past Hero it becomes
 * today's translucent-glass look. The crossfade is CSS-only (`transition-all`), deliberately not
 * Framer Motion -- the navbar is infrastructure, not a decorative element.
 *
 * `-mb-16` (negative margin equal to the header's own h-16) is what actually makes "transparent
 * over Hero" read correctly: a plain `sticky` header still reserves its own box in normal flow at
 * scrollY 0, so Hero would start BELOW that box, not behind it -- a transparent header would then
 * reveal the page's own white background through that gap, not Hero's dark gradient. The negative
 * margin collapses the reserved space so Hero (and every later section once scrolled) renders
 * starting at y=0, with the header overlapping its top 64px -- Hero's own top padding (pt-28/
 * pt-36, well over 64px) already keeps the headline clear of that overlap.
 */
export function Nav({ overHero }: { overHero: boolean }) {
  const [open, setOpen] = useState(false);

  return (
    <header
      className="sticky top-0 z-30 -mb-16 transition-all duration-300"
      style={{
        background: overHero ? 'transparent' : 'rgb(255 255 255 / .88)',
        backdropFilter: overHero ? 'none' : 'blur(12px)',
        borderBottom: overHero ? '1px solid transparent' : '1px solid var(--m-line)',
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
            to="/login"
            className="hidden sm:block text-sm transition-colors"
            style={{ color: overHero ? 'rgba(248,250,252,0.85)' : 'var(--m-ink-2)' }}
            onMouseEnter={(e) => { e.currentTarget.style.color = overHero ? '#F8FAFC' : '#0F172A'; }}
            onMouseLeave={(e) => { e.currentTarget.style.color = overHero ? 'rgba(248,250,252,0.85)' : 'var(--m-ink-2)'; }}
          >
            Log in
          </Link>
          <MagneticLink to="/register" className="m-btn m-btn-primary !min-h-[44px] !px-4 !text-sm">
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
            to="/login"
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
