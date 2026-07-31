# Login/Register UX fixes

## 1. Finora logo now links back to the landing page

Both `Login.tsx` and `Register.tsx` — the logo/wordmark was a plain `<div>`, not a link. Wrapped
both instances in each file (the desktop marketing-panel one and the mobile-only one) in
`<Link to="/">`.

## 2. Invisible typed text in dark mode — root cause fixed globally, not patched per-field

This wasn't actually about Finora's own light/dark toggle — it's a real, separate browser
behavior: an `<input>` with no explicit background/text color of its own falls back to the
browser's **native auto-dark rendering** whenever the visitor's OS/browser prefers dark
(`prefers-color-scheme: dark`), independent of what Finora's own toggle is set to. Chromium
browsers apply this inconsistently — an auto-injected light text color, but not necessarily a
matching dark background — which is exactly "invisible white-on-white while typing."

**Fixed at the root, in `index.css`:**
```css
input, textarea, select {
  color-scheme: light;
}
```
This opts every native form control in the app out of that browser-level auto-restyling — the
app's own theme is handled explicitly elsewhere (Tailwind classes, the CSS variables above), not
left to the browser to guess independently. Fixes this for every current *and future* input in
the app, not just the two pages you noticed it on.

**Also fixed directly** on every input across `Login.tsx`, `Register.tsx`, and `PasswordInput`'s
own default styling (`bg-white text-gray-900` explicitly) — belt-and-suspenders alongside the
global fix, and consistent with the white-pill-input look already used everywhere in the
screenshots.

## 3. Mobile number — 10 digits only, with a fixed 🇮🇳 +91 prefix

Previously accepted 10-15 digits with an optional typed `+` — now:
- The `+91` and 🇮🇳 flag are a **fixed, non-editable prefix** next to the field, not something
  typed (with `pointer-events-none` so clicking it still focuses the actual input right next to
  it).
- The field itself only ever holds the 10-digit local number, auto-stripped of anything
  non-numeric as you type, capped at 10 characters.
- **Paste handling**: if you paste a full number that already includes the country code
  (`+919876543210`, `919876543210`, with spaces/dashes either way), it strips the leading `91` so
  you land on just the 10-digit local part — but a genuine 10-digit number that happens to
  legitimately start with `91` (e.g. `9187654321`) is left alone, since the strip only fires when
  there'd otherwise be more than 10 digits.
- **Validation**: real Indian mobile numbers always start 6–9 — added that check (not just the
  length), so an obviously-wrong number like `1234567890` gets caught before it ever reaches the
  backend.
- `+91` is prepended once, at the moment of actual submission — not stored in state with it.

**No backend changes needed** — the backend's own pattern (`^\+?[0-9]{10,15}$`) already accepts
`+91` followed by 10 digits (12 digits total after the `+`), so this was purely a frontend
tightening.

## Tests

New `Register.test.tsx` (this page had no test file before) — 8 tests: non-digit stripping,
10-digit cap, paste-strips-country-code, paste-doesn't-strip-a-genuine-91-prefix, the fixed
prefix always rendering, the actual submitted value having `+91` prepended correctly, the
6-9-leading-digit validation, and the logo link.

## Verification

`tsc -b` clean. Couldn't run the actual test suite myself (same native Vitest binary constraint
as every frontend round this session) — please run `npm test` in `frontend/` and confirm.

## About the `git commit` request

I can't run this in a way that touches your actual repository — my sandbox is a separate,
disconnected copy of the code you uploaded, not a live connection to your GitHub repo or local
machine. Once you've applied this bundle to your real checkout, here's a commit message that
actually describes what's in it:

```
git commit -m "fix(auth): logo links to landing page, fix invisible input text in dark mode, restrict mobile number to 10 digits with fixed +91 prefix"
```
