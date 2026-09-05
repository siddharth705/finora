import { useEffect, useRef, useState } from 'react';
import { Landmark } from 'lucide-react';
import type { BankInfo } from '../types';

interface BankLogoProps {
  bank: BankInfo;
  size?: number;
  className?: string;
}

// Vite's import.meta.glob, resolved at build time, not per-render -- every real SVG file that
// exists under src/assets/banks/ at build time shows up here as a URL (eager + `import: 'default'`
// gives back the resolved asset URL directly, not a lazy loader). This directory ships EMPTY in
// this build (see src/assets/banks/README.md) unless you've dropped files in yourself.
const LOGO_FILES = import.meta.glob<string>('../assets/banks/*.svg', { eager: true, import: 'default' });

function resolveLocalLogoUrl(logoPath: string): string | null {
  // logoPath from the backend looks like "/assets/banks/pnb.svg" -- glob keys look like
  // "../assets/banks/pnb.svg" (relative to this file). Comparing by filename alone keeps this
  // resilient to either path convention rather than requiring an exact string match.
  const filename = logoPath.split('/').pop();
  if (!filename) return null;
  for (const [key, url] of Object.entries(LOGO_FILES)) {
    if (key.endsWith('/' + filename)) return url;
  }
  return null;
}

const LOGODEV_TOKEN = import.meta.env.VITE_LOGODEV_TOKEN;
// A bit more generous than the "1-2 seconds" asked for -- long enough that a normal CDN response
// isn't cut off early, short enough that a slow/unreachable Logo.dev never leaves a user staring
// at an empty spot on the page.
const LOGODEV_TIMEOUT_MS = 1500;

export function extractDomain(websiteUrl: string | null): string | null {
  if (!websiteUrl) return null;
  try {
    return new URL(websiteUrl).hostname.replace(/^www\./, '');
  } catch {
    return null; // a malformed websiteUrl in the registry shouldn't ever throw at render time
  }
}

export function logoDevUrl(domain: string | null, sizePx: number, token: string | undefined): string | null {
  if (!token || !domain) return null;
  // https://www.logo.dev/docs/logo-images/get -- a bare domain is the default identifier type
  // (no `domain/` prefix, unlike `name/`, `ticker/`, `crypto/`, `isin/`). `format=png` rather than
  // the default `jpg` so a logo with a transparent background actually shows this component's own
  // background/initials through it instead of an opaque rectangle. `fallback=404` turns a miss
  // into a real `onError` -- Logo.dev's own default (`fallback=monogram`) returns 200 with a
  // generic monogram, which would look like a real logo and pre-empt the local-SVG/initials
  // chain below, throwing away the bank's actual brand color for a monogram in Logo.dev's own
  // gray theme instead.
  return `https://img.logo.dev/${domain}?token=${token}&size=${sizePx}&format=png&fallback=404`;
}

type Stage = 'logodev' | 'local' | 'initials';

/**
 * Circuit breaker: once Logo.dev has actually rejected a DOMAIN, stop asking it for that domain
 * for the rest of this page session.
 *
 * Every logo on a page resolves independently, so re-mounting the same bank (e.g. scrolling an
 * accounts list) would otherwise cost a repeated failed round-trip for a domain already known bad
 * -- each one burning its own 1.5s timeout before falling back, and each one logging its own
 * console error. Observed in production (under the previous Brandfetch integration this replaced)
 * as a wall of `403 (Forbidden)` entries -- the same failure shape applies to any third-party logo
 * CDN, which is why this breaker carried over rather than being re-derived from scratch.
 *
 * Bug fix: this used to be a single page-wide flag, tripped by ANY bank's rejection and then
 * skipping Logo.dev for EVERY bank for the rest of the session. That conflated two different
 * failure shapes a plain `<img>`'s onError cannot tell apart: a 403 (bad/domain-restricted token
 * -- genuinely a fact about every domain equally) and a 404 with fallback=404 (this ONE domain
 * simply isn't in Logo.dev's catalog -- the ordinary, expected outcome for an obscure or
 * small-regional bank, not evidence anything is broken). One such 404 was silently disabling
 * Logo.dev for every OTHER bank shown afterwards on the same page -- including ones that would
 * have resolved fine -- which showed up as bank logos loading inconsistently depending on account
 * list order. Scoping the breaker per domain keeps the repeat-mount protection above while
 * removing that cross-bank collateral damage.
 *
 * Deliberately tripped only by a real error response, not by the timeout: a timeout is one slow
 * request and may not repeat, while a rejection is the CDN telling us this domain will not work.
 * Module-level rather than React state because it is a fact about the CDN, not about any one
 * component, and it must outlive every unmount.
 *
 * `MerchantLogo` deliberately does NOT carry the same breaker, per-domain or otherwise: a
 * transaction's merchant name missing from Logo.dev's catalog is the ordinary, expected outcome
 * for most rows (cash withdrawals, UPI references, unrecognized local vendors), not a signal that
 * the whole integration is broken -- see that component's own comment.
 *
 * Not persisted beyond the page session on purpose. Whatever caused a rejection -- an expired
 * token, a domain not on the key's allowlist -- is fixable server-side, and a reload should pick
 * that up rather than a stale localStorage flag suppressing it for days.
 */
const logoDevRejectedDomains = new Set<string>();

/**
 * Provider-chain logo resolution, per the brief: Logo.dev (a real, always-current official logo,
 * via their Logo API) -> a locally dropped-in SVG (see src/assets/banks/README.md) -> a
 * colored-initials badge. Every page just renders <BankLogo bank={x.bank} /> exactly as before --
 * which provider actually served the pixels is entirely this component's business, so no page
 * needs its own logo-loading logic.
 *
 * `bank` (not a bare `bankId`) stays the prop here rather than resolving metadata from an id
 * internally: every caller already has the full, server-resolved BankInfo sitting on the
 * Account/DetectedAccountInfo/etc. object it's rendering (see AccountDto.BankDto on the
 * backend) -- that's the app's actual single source of truth for a bank's name/color/domain.
 * Accepting a bare id here would mean duplicating that same registry data into a second,
 * client-side cache just to look it back up, which is more moving parts for no real benefit.
 *
 * Logo.dev is opt-in via VITE_LOGODEV_TOKEN (see .env.example) -- a publishable key, safe to
 * ship client-side, but still a runtime call to a third-party CDN, a real change from this app's
 * previous "everything local" posture, called out explicitly since it's not something to slip in
 * unannounced. Without a token configured, this stage is skipped entirely and the component
 * behaves exactly as it did before any logo CDN existed (local asset, then initials) -- nothing
 * breaks for anyone who hasn't set it up.
 *
 * <b>Attribution.</b> Logo.dev's free tier requires a visible attribution link back to Logo.dev
 * for commercial use (their docs, not verifiable live as of this writing -- see this file's
 * git history / the PR that introduced this for the caveat). Finora is a commercial product, so
 * that link needs to exist somewhere in the shipped app before this goes to production on the
 * free tier; it does not exist yet as of this change. Flagged rather than silently assumed away.
 */
export function BankLogo({ bank, size = 40, className = '' }: BankLogoProps) {
  const isUnknown = bank.id === 'OTHER' || !bank.officialName;
  const domain = !isUnknown ? extractDomain(bank.websiteUrl) : null;
  // Fetched at 2x the rendered size (min 64px) so it stays crisp on high-DPI screens without
  // the caller having to think about it.
  const sizePx = Math.max(64, Math.round(size * 2));
  // Null once this domain's circuit breaker has tripped, which skips the Logo.dev stage entirely
  // for every logo mounted afterwards for THIS domain -- straight to the local asset or initials,
  // no request, no timeout. Other domains are unaffected.
  const domainRejected = !!domain && logoDevRejectedDomains.has(domain);
  const logoDevSrc = domainRejected ? null : logoDevUrl(domain, sizePx, LOGODEV_TOKEN);

  const [stage, setStage] = useState<Stage>(() => (logoDevSrc ? 'logodev' : 'local'));
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Reset to the top of the provider chain whenever the bank itself changes -- e.g. scrolling
  // through a list of account cards, each a different bank -- otherwise a card that previously
  // fell all the way back to "initials" for one bank would incorrectly start there for the next.
  useEffect(() => {
    setStage(logoDevSrc ? 'logodev' : 'local');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bank.id]);

  // Logo.dev timeout: if it hasn't loaded (or failed) within LOGODEV_TIMEOUT_MS, don't keep the
  // user waiting on a slow/unreachable third-party CDN -- move on to the local/initials fallback.
  // Cleared by onLoad/onError below if Logo.dev responds first either way.
  useEffect(() => {
    if (stage !== 'logodev') return undefined;
    timeoutRef.current = setTimeout(() => setStage('local'), LOGODEV_TIMEOUT_MS);
    return () => { if (timeoutRef.current) clearTimeout(timeoutRef.current); };
  }, [stage, bank.id]);

  function clearLogoDevTimeout() {
    if (timeoutRef.current) { clearTimeout(timeoutRef.current); timeoutRef.current = null; }
  }

  if (stage === 'logodev' && logoDevSrc) {
    return (
      <img
        src={logoDevSrc}
        alt={bank.officialName ?? bank.shortName}
        title={bank.officialName ?? bank.shortName}
        className={`rounded-xl object-contain flex-shrink-0 ${className}`}
        style={{ width: size, height: size }}
        onLoad={clearLogoDevTimeout}
        onError={() => {
          clearLogoDevTimeout();
          // A real rejection (403 for a bad/domain-restricted token, 404 with fallback=404 for an
          // unrecognized domain) means a re-mount of THIS SAME domain is about to fail the same
          // way. Trip the breaker for just this domain so a later re-mount doesn't have to find
          // that out again -- but leave every other domain's own first attempt untouched.
          if (domain) logoDevRejectedDomains.add(domain);
          setStage('local');
        }}
      />
    );
  }

  if (stage !== 'initials' && !isUnknown) {
    const localUrl = resolveLocalLogoUrl(bank.logoPath);
    if (localUrl) {
      return (
        <img
          src={localUrl}
          alt={bank.officialName ?? bank.shortName}
          title={bank.officialName ?? bank.shortName}
          className={`rounded-xl object-contain flex-shrink-0 ${className}`}
          style={{ width: size, height: size }}
          onError={() => setStage('initials')}
        />
      );
    }
  }

  return (
    <div
      className={`rounded-xl flex items-center justify-center flex-shrink-0 font-bold text-white ${className}`}
      style={{
        width: size,
        height: size,
        background: isUnknown ? '#64748B' : bank.colorHex,
        fontSize: Math.max(10, size * 0.34),
      }}
      title={bank.officialName ?? 'Bank not recognized'}
    >
      {isUnknown ? <Landmark size={size * 0.5} /> : bank.initials}
    </div>
  );
}
