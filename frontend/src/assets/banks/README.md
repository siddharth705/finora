# Bank logo assets

This folder is intentionally empty in this build.

Finora's `BankLogo` component (`frontend/src/components/BankLogo.tsx`) resolves a bank's logo
through a provider chain, in this order:

1. **Logo.dev** (https://logo.dev) -- a real, always-current official logo, fetched from their
   Logo API using the bank's official domain (`BankInfo.websiteUrl`, already in the registry).
   Only active if you've set `VITE_LOGODEV_TOKEN` -- see `frontend/.env.example`, which also has
   the free-tier attribution caveat. Skipped entirely if unset.
2. **A local SVG dropped in here**, named after the bank's slug -- the same filename as the last
   path segment of its `logoPath` in `backend/src/main/java/com/finora/util/BankRegistry.java`,
   e.g.:
   - Punjab National Bank -> `pnb.svg`
   - HDFC Bank -> `hdfc.svg`
   - IDFC FIRST Bank -> `idfc-first.svg`
   - Standard Chartered -> `standard-chartered.svg`
3. **A colored-initials badge** -- the final fallback, and everything this app rendered before
   either of the above existed. Always available, never requires network access or a file drop.

No code changes are needed for either step 1 or step 2 to start working -- `BankLogo.tsx` picks
up a configured token or a matching local file automatically on the next build. See
`BankRegistry.all()` for the full list of slugs currently registered, and `BankLogo.tsx`'s own
comment for exactly how the three stages hand off to each other (including a timeout so a
slow/unreachable Logo.dev never blocks the local/initials fallback).

The same `VITE_LOGODEV_TOKEN` also drives `MerchantLogo.tsx` (`frontend/src/components/`),
which looks up a transaction's merchant by name rather than domain -- no local-asset stage for
that one (a per-merchant SVG catalog isn't practical the way a ~40-bank one is), just Logo.dev
then a colored-initials badge.
