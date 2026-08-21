/// <reference types="vite/client" />

// Bug fix: BankLogo.tsx already used `import.meta.glob` (added earlier this session) with no
// `vite/client` type reference anywhere in the project -- `tsc -b` (part of `npm run build`,
// see package.json) would have failed with "Property 'glob' does not exist on type 'ImportMeta'"
// the first time anyone actually ran a real build. This file is what every standard Vite+React
// scaffold ships with; it was simply missing here.
interface ImportMetaEnv {
  // Optional on purpose -- see BankLogo.tsx's own comment on why an unset token just skips the
  // Logo.dev stage of the logo provider chain rather than being treated as an error. Shared with
  // MerchantLogo.tsx, which reads the same var for the same reason.
  readonly VITE_LOGODEV_TOKEN?: string;
  // Optional, same reasoning as above -- unset falls back to a same-origin relative path, which
  // is exactly right for local dev (Vite's own dev-server proxy handles it, see vite.config.ts)
  // and wrong for any real deployment where the frontend and backend don't share an origin — see
  // client.ts's own doc comment for the full story.
  readonly VITE_API_BASE_URL?: string;
  // Optional, same reasoning again -- unset disables crash reporting entirely rather than being
  // an error, which is what keeps local dev and the test suite free of network calls. See
  // lib/monitoring.ts.
  readonly VITE_SENTRY_DSN?: string;
  // Optional, same "unconfigured is supported" reasoning -- unset hides the Google button
  // entirely (GoogleSignInButton.tsx) rather than rendering one that can't work. This is Google's
  // OAuth client ID for the web app (D-23), a public value safe to ship client-side, and a
  // separate registration from the Gmail-sync integration's own client id (server-side only,
  // never exposed here).
  readonly VITE_GOOGLE_LOGIN_CLIENT_ID?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
