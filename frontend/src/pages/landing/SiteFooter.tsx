import { Link } from 'react-router-dom';
import { Instagram } from 'lucide-react';
import { Logo } from './Nav';
import { footer } from './landing-config';



const COLUMNS: { title: string; links: [string, string][] }[] = [
  { title: 'Product', links: [['How it works', '#how'], ['Import', '#import'], ['Security', '#security'], ['Pricing', '#pricing'], ['FAQ', '#faq']] },
  { title: 'Company', links: [['About', '/about'], ['Careers', '/careers'], ['Contact', '/contact'], ['Help Center', '/help']] },
  { title: 'Legal', links: [['Privacy Policy', '/privacy'], ['Terms of Service', '/terms'], ['Refund Policy', '/refund-policy'], ['Shipping Policy', '/shipping-policy']] },
];

/**
 * The three lines under the mark are the page's argument compressed to six words, and they are
 * the last thing read.
 *
 * Instagram is the only social link, because it is the only account that exists. A row of icons
 * where most lead nowhere reads as abandoned; one that goes somebody real reads as a company.
 * `rel="noopener noreferrer"` on the external link is not optional -- `target="_blank"` without
 * it hands the opened tab a reference back to this one.
 */
export function SiteFooter() {
  return (
    <footer style={{ background: 'var(--m-surface-deep)' }}>
      <div className="max-w-6xl mx-auto px-5 sm:px-6 py-14">
        <div className="grid md:grid-cols-[1.4fr_1fr_1fr_1fr] gap-10 mb-10">
          <div>
            <Logo invert />
            <p className="text-sm mt-3 max-w-xs leading-relaxed text-slate-400">
              {footer.mission}
            </p>
            <div className="mt-5 space-y-1">
              {footer.principles.map((line) => (
                <p key={line} className="text-[13px] font-medium text-slate-500">{line}</p>
              ))}
            </div>

            <a
              href={footer.instagram}
              target="_blank"
              rel="noopener noreferrer"
              className="m-tap inline-flex items-center gap-2 mt-5 text-sm text-slate-400 hover:text-white transition-colors"
              aria-label="Finora on Instagram (opens in a new tab)"
            >
              <Instagram size={17} />
              {footer.instagramHandle}
            </a>
          </div>

          {COLUMNS.map((col) => (
            <div key={col.title}>
              <p className="text-xs font-semibold uppercase tracking-wide text-slate-300 mb-3">{col.title}</p>
              <ul className="space-y-1">
                {col.links.map(([label, href]) => (
                  <li key={label}>
                    {href.startsWith('#') ? (
                      <a href={href} className="m-tap text-sm text-slate-400 hover:text-white transition-colors">{label}</a>
                    ) : (
                      <Link to={href} className="m-tap text-sm text-slate-400 hover:text-white transition-colors">{label}</Link>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>

        <div className="pt-7 border-t border-white/10 flex flex-col sm:flex-row items-center justify-between gap-3">
          <p className="text-xs text-slate-500">© {new Date().getFullYear()} Finora. All rights reserved.</p>
          <p className="text-xs text-slate-500">{footer.tagline}</p>
        </div>
      </div>
    </footer>
  );
}
