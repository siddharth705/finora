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

const BRANDFETCH_CLIENT_ID = import.meta.env.VITE_BRANDFETCH_CLIENT_ID;
// A bit more generous than the "1-2 seconds" asked for -- long enough that a normal CDN response
// isn't cut off early, short enough that a slow/unreachable Brandfetch never leaves a user
// staring at an empty spot on the page.
const BRANDFETCH_TIMEOUT_MS = 1500;

export function extractDomain(websiteUrl: string | null): string | null {
  if (!websiteUrl) return null;
  try {
    return new URL(websiteUrl).hostname.replace(/^www\./, '');
  } catch {
    return null; // a malformed websiteUrl in the registry shouldn't ever throw at render time
  }
}

export function brandfetchUrl(domain: string | null, sizePx: number, clientId: string | undefined): string | null {
  if (!clientId || !domain) return null;
  // Bug fix: verified against Brandfetch's current docs (docs.brandfetch.com/logo-api/overview).
  // Two things were off from their actual, current URL format:
  // 1. Missing the explicit `domain/` type prefix -- their docs: "To avoid potential naming
  //    collisions between identifier types, you can use explicit type routes with the pattern
  //    {type}/{identifier}." A bare domain still works today via their auto-detection fallback
  //    (domain is checked first), but isn't the format they actually recommend.
  // 2. `/w/{size}/h/{size}/` had width before height -- every single documented example
  //    (sizing, retina, combined with type/theme) uses `/h/{h}/w/{w}/`, never the reverse. Since
  //    this app always requests a square logo (w === h), a positional parser bug here wouldn't
  //    have been visible in the actual pixels either way -- but there's no reason to rely on
  //    order-independence that was never actually confirmed, when the documented order is known.
  return `https://cdn.brandfetch.io/domain/${domain}/h/${sizePx}/w/${sizePx}/logo?c=${clientId}`;
}

type Stage = 'brandfetch' | 'local' | 'initials';

/**
 * Circuit breaker: once Brandfetch has actually rejected a request, stop asking it for the rest of
 * this page session.
 *
 * Every logo on a page resolves independently, so a Brandfetch outage or a rejected client ID cost
 * one failed round-trip PER BANK -- an accounts list with eight banks fired eight requests that
 * were all going to fail for the same reason, each one burning its own 1.5s timeout before falling
 * back, and each one logging its own console error. Observed in production as a wall of
 * `403 (Forbidden)` entries.
 *
 * Deliberately tripped only by a real error response, not by the timeout: a timeout is one slow
 * request and may not repeat, while a 403/404 is the CDN telling us this configuration will not
 * work. Module-level rather than React state because it is a fact about the CDN, not about any one
 * component, and it must outlive every unmount.
 *
 * Not persisted beyond the page session on purpose. Whatever caused the rejection -- an expired
 * client ID, a domain not on the key's allowlist -- is fixable server-side, and a reload should
 * pick that up rather than a stale localStorage flag suppressing it for days.
 */
let brandfetchRejected = false;

/**
 * Provider-chain logo resolution, per the brief: Brandfetch (a real, always-current official
 * logo, via their free Logo API) -> a locally dropped-in SVG (see src/assets/banks/README.md)
 * -> a colored-initials badge. Every page just renders <BankLogo bank={x.bank} /> exactly as
 * before -- which provider actually served the pixels is entirely this component's business, so
 * no page needs its own logo-loading logic.
 *
 * `bank` (not a bare `bankId`) stays the prop here rather than resolving metadata from an id
 * internally: every caller already has the full, server-resolved BankInfo sitting on the
 * Account/DetectedAccountInfo/etc. object it's rendering (see AccountDto.BankDto on the
 * backend) -- that's the app's actual single source of truth for a bank's name/color/domain.
 * Accepting a bare id here would mean duplicating that same registry data into a second,
 * client-side cache just to look it back up, which is more moving parts for no real benefit.
 *
 * Brandfetch is opt-in via VITE_BRANDFETCH_CLIENT_ID (see .env.example). Their Logo API is free
 * (500k requests/month, no attribution required) but does mean a runtime call to a third-party
 * CDN -- a real change from this app's previous "everything local" posture, called out
 * explicitly since it's not something to slip in unannounced. Without a client ID configured,
 * this stage is skipped entirely and the component behaves exactly as it did before Brandfetch
 * existed (local asset, then initials) -- nothing breaks for anyone who hasn't set it up.
 */
export function BankLogo({ bank, size = 40, className = '' }: BankLogoProps) {
  const isUnknown = bank.id === 'OTHER' || !bank.officialName;
  const domain = !isUnknown ? extractDomain(bank.websiteUrl) : null;
  // Fetched at 2x the rendered size (min 64px) so it stays crisp on high-DPI screens without
  // the caller having to think about it.
  const sizePx = Math.max(64, Math.round(size * 2));
  // Null once the circuit breaker has tripped, which skips the Brandfetch stage entirely for every
  // logo mounted afterwards -- straight to the local asset or initials, no request, no timeout.
  const brandfetchSrc = brandfetchRejected ? null : brandfetchUrl(domain, sizePx, BRANDFETCH_CLIENT_ID);

  const [stage, setStage] = useState<Stage>(() => (brandfetchSrc ? 'brandfetch' : 'local'));
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Reset to the top of the provider chain whenever the bank itself changes -- e.g. scrolling
  // through a list of account cards, each a different bank -- otherwise a card that previously
  // fell all the way back to "initials" for one bank would incorrectly start there for the next.
  useEffect(() => {
    setStage(brandfetchSrc ? 'brandfetch' : 'local');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bank.id]);

  // Brandfetch timeout: if it hasn't loaded (or failed) within BRANDFETCH_TIMEOUT_MS, don't keep
  // the user waiting on a slow/unreachable third-party CDN -- move on to the local/initials
  // fallback. Cleared by onLoad/onError below if Brandfetch responds first either way.
  useEffect(() => {
    if (stage !== 'brandfetch') return undefined;
    timeoutRef.current = setTimeout(() => setStage('local'), BRANDFETCH_TIMEOUT_MS);
    return () => { if (timeoutRef.current) clearTimeout(timeoutRef.current); };
  }, [stage, bank.id]);

  function clearBrandfetchTimeout() {
    if (timeoutRef.current) { clearTimeout(timeoutRef.current); timeoutRef.current = null; }
  }

  if (stage === 'brandfetch' && brandfetchSrc) {
    return (
      <img
        src={brandfetchSrc}
        alt={bank.officialName ?? bank.shortName}
        title={bank.officialName ?? bank.shortName}
        className={`rounded-xl object-contain flex-shrink-0 ${className}`}
        style={{ width: size, height: size }}
        onLoad={clearBrandfetchTimeout}
        onError={() => {
          clearBrandfetchTimeout();
          // A real rejection (403 for a bad/domain-restricted client ID, 404 for an unknown
          // domain) means every other logo on this page is about to fail the same way. Trip the
          // breaker so they don't all have to find that out individually.
          brandfetchRejected = true;
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
