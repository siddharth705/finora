import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ArrowLeft, Sparkles } from 'lucide-react';
import { BrandMark } from './BrandMark';

/**
 * Shared shell for the public/legal pages linked from Landing.tsx's footer (Terms, Privacy,
 * About, Careers, Help Center). Kept visually consistent with Landing.tsx's dark theme rather
 * than the app's light dashboard theme — these are marketing-site pages a logged-out visitor
 * reaches from the footer, not authenticated app pages.
 */
export function PublicLayout({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  return (
    <div className="min-h-screen bg-[#0a0b16] text-gray-200">
      <header className="sticky top-0 z-30 bg-[#0a0b16]/90 backdrop-blur border-b border-white/5">
        <div className="max-w-4xl mx-auto px-6 py-4 flex items-center justify-between">
          <Link to="/" className="flex items-center gap-2">
            <BrandMark size={32} invert className="rounded-lg" />
            <span className="font-extrabold tracking-wide text-white">Fynora</span>
          </Link>
          <Link to="/" className="flex items-center gap-1.5 text-sm text-gray-400 hover:text-white transition-colors">
            <ArrowLeft size={15} /> Back to home
          </Link>
        </div>
      </header>

      <section className="border-b border-white/5">
        <div className="max-w-4xl mx-auto px-6 pt-16 pb-10">
          {/* This page is dark by design regardless of the app's own light/dark toggle, so it
              can't use the toggling `primary` token (which is dark graphite in light mode) --
              needs the fixed, always-light accent this fixed-dark surface actually requires. */}
          <span className="inline-flex items-center gap-1.5 text-xs font-medium text-[#F4F1EC] bg-[#F4F1EC]/10 border border-[#F4F1EC]/20 rounded-full px-3 py-1 mb-4">
            <Sparkles size={12} /> Fynora
          </span>
          <h1 className="text-3xl md:text-4xl font-extrabold text-white mb-3">{title}</h1>
          {subtitle && <p className="text-gray-400 text-base max-w-2xl">{subtitle}</p>}
        </div>
      </section>

      <main className="max-w-4xl mx-auto px-6 py-14">{children}</main>

      <footer className="border-t border-white/5">
        <div className="max-w-4xl mx-auto px-6 py-8 flex flex-col sm:flex-row justify-between items-center gap-3 text-xs text-gray-500">
          <span>© {new Date().getFullYear()} Fynora. Not a bank. Not investment advice.</span>
          <div className="flex items-center gap-4">
            <Link to="/terms" className="hover:text-gray-300">Terms</Link>
            <Link to="/privacy" className="hover:text-gray-300">Privacy</Link>
            <Link to="/refund-policy" className="hover:text-gray-300">Refunds</Link>
            <Link to="/shipping-policy" className="hover:text-gray-300">Shipping</Link>
            <Link to="/contact" className="hover:text-gray-300">Contact</Link>
            <Link to="/about" className="hover:text-gray-300">About</Link>
            <Link to="/help" className="hover:text-gray-300">Help</Link>
          </div>
        </div>
      </footer>
    </div>
  );
}

/** A titled prose section — consistent spacing/typography for every legal/info page built on
 *  PublicLayout, so Terms/Privacy/About don't each reinvent heading styles. */
export function PublicSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="mb-10">
      <h2 className="text-xl font-bold text-white mb-3">{title}</h2>
      <div className="text-sm text-gray-400 leading-relaxed space-y-3">{children}</div>
    </section>
  );
}
