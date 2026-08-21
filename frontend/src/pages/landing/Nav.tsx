import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, Menu, X } from 'lucide-react';
import { BrandMark } from '../../components/BrandMark';

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

export function Nav() {
  const [scrolled, setScrolled] = useState(false);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    function onScroll() { setScrolled(window.scrollY > 8); }
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  return (
    <header
      className={`sticky top-0 z-30 transition-shadow duration-300 ${scrolled ? 'shadow-[0_1px_0_rgba(15,23,42,.06),0_8px_24px_-16px_rgba(15,23,42,.25)]' : ''}`}
      style={{ background: 'rgb(255 255 255 / .88)', backdropFilter: 'blur(12px)', borderBottom: '1px solid var(--m-line)' }}
    >
      <div className="max-w-6xl mx-auto px-5 sm:px-6 h-16 flex items-center justify-between">
        <Logo />
        <nav className="hidden md:flex items-center gap-8 text-sm" style={{ color: 'var(--m-ink-2)' }}>
          {LINKS.map(([label, href]) => (
            <a key={href} href={href} className="hover:text-[#0F172A] transition-colors">{label}</a>
          ))}
        </nav>
        <div className="flex items-center gap-3">
          <Link to="/login" className="hidden sm:block text-sm hover:text-[#0F172A] transition-colors" style={{ color: 'var(--m-ink-2)' }}>
            Log in
          </Link>
          <Link to="/register" className="m-btn m-btn-primary !min-h-[44px] !px-4 !text-sm">
            Get started <ArrowRight size={14} />
          </Link>
          <button
            type="button"
            onClick={() => setOpen((v) => !v)}
            className="md:hidden w-11 h-11 grid place-items-center rounded-lg border"
            style={{ borderColor: 'var(--m-line)' }}
            aria-label={open ? 'Close menu' : 'Open menu'}
            aria-expanded={open}
          >
            {open ? <X size={18} /> : <Menu size={18} />}
          </button>
        </div>
      </div>
      {open ? (
        <div className="md:hidden border-t px-5 py-2" style={{ borderColor: 'var(--m-line)' }}>
          {LINKS.map(([label, href]) => (
            <a key={href} href={href} onClick={() => setOpen(false)} className="m-tap block text-sm" style={{ color: 'var(--m-ink-2)' }}>
              {label}
            </a>
          ))}
          <Link to="/login" className="m-tap block text-sm" style={{ color: 'var(--m-ink-2)' }}>Log in</Link>
        </div>
      ) : null}
    </header>
  );
}
