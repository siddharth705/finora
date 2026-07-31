# Brandfetch fix

## What was actually wrong

Checked against Brandfetch's **current, official documentation** (fetched live — API URL formats
aren't something to assume from memory, and this one has a "legacy" vs. "recommended" distinction
worth getting right). Two real problems with the constructed URL:

1. **Size parameters were in the wrong order.** Every single documented example —
   basic sizing, retina, combined with type or theme — uses `/h/{height}/w/{width}/`, height
   before width. The code had `/w/{size}/h/{size}/`, reversed. Since this component only ever
   requests a square logo (width always equals height), this specific ordering bug wouldn't have
   been visible in the actual rendered pixels — but there was never any confirmation the API's
   parser is order-independent, so it's worth matching the documented order exactly rather than
   relying on that.

2. **Missing the explicit `domain/` type prefix.** Brandfetch's current docs: *"To avoid potential
   naming collisions between identifier types, you can use explicit type routes with the pattern
   `{type}/{identifier}`."* The code used a bare domain (`cdn.brandfetch.io/{domain}/...`), which
   still works today via their auto-detection fallback (domain is checked first, before ticker/
   ISIN/crypto) — explicitly called out in their own docs as the **legacy** format, not what they
   currently recommend.

Fixed both — the URL is now built exactly per their current documented format:
```
https://cdn.brandfetch.io/domain/{domain}/h/{size}/w/{size}/logo?c={clientId}
```

## Also worth checking on your end

Brandfetch is opt-in in this app — if `VITE_BRANDFETCH_CLIENT_ID` was never actually set in your
Cloudflare Pages build environment, this whole stage is silently skipped by design (falls straight
to the local-SVG-then-colored-initials fallback, which is exactly what "not working" would look
like from the outside, even with a perfectly correct URL). **Please confirm that env var is
actually set** — if you don't have a client ID yet, they're free to register for at
[developers.brandfetch.com/register](https://developers.brandfetch.com/register).

## Refactor for testability

`brandfetchUrl()` used to read the client ID from module-scope state (computed once from
`import.meta.env` at module load) — made it a pure function taking `clientId` as an explicit
parameter instead, so it (and `extractDomain()`) can be tested directly without fighting Vite's
env-var mocking. Both are now exported from `BankLogo.tsx`.

## New: `BankLogo.test.tsx`

This component had **no test coverage at all** before. 8 tests: the corrected URL format exactly,
both null-return guard clauses (no client ID, no domain), domain extraction, `www.` stripping,
and a malformed-URL case not throwing.

## Verification

`tsc -b` clean. Couldn't run the actual test suite myself (same native Vitest binary constraint as
every frontend round this session) — please run `npm test` in `frontend/` to confirm.
