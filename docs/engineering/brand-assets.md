# Brand assets

Where every logo file lives, what constrains its format, and which of those constraints are
silent — i.e. produce a wrong-looking icon rather than an error.

Source material (generation drafts, alternate lockups) is **not** committed: `logo/` is gitignored.
Only the finalised assets below are in the repository.

---

## The mark

One mark, one plate colour, used everywhere: the gradient "F" on brand navy **`#020E32`**. That hex
is sampled from the artwork itself rather than chosen separately, so the Android foreground layer
meets its background with no seam when a launcher masks the icon to a circle.

## Where the files are

| File | Size | Alpha | Used by |
|---|---|---|---|
| `mobile/assets/icon.png` | 1024² | **none** | iOS + Android app icon (`icon` in `app.config.ts`) |
| `mobile/assets/android-icon-foreground.png` | 512² | yes | `android.adaptiveIcon.foregroundImage` |
| `mobile/assets/android-icon-monochrome.png` | 432² | yes | `android.adaptiveIcon.monochromeImage` (Android 13+ themed icons) |
| `mobile/assets/favicon.png` | 48² | none | `web.favicon` for Expo web |
| `mobile/assets/splash-icon.png` | 1024² | yes | nothing — see "Loose ends" |
| `frontend/src/assets/logo-mark.png` | 512² | none | Sidebar, Login, Register |
| `frontend/public/favicon.png` | 64² | none | `index.html` |
| `frontend/src/assets/logo.png` | 1200×436 | none | nothing — see "Loose ends" |
| `admin-portal/…` | — | — | identical set, same files |

## The constraints that bite silently

**An iOS app icon must have no alpha channel at all.** Not "no transparent pixels" — no channel.
App Store submission rejects one that carries a fully opaque alpha channel, and nothing local warns
you. `sips -g hasAlpha mobile/assets/icon.png` must say `no`.

**`android.adaptiveIcon.backgroundImage` overrides `backgroundColor`.** This is the one that was
already wrong: the config set `backgroundColor: '#E6F4FE'` *and* a `backgroundImage` pointing at
`android-icon-background.png` — the pale blue grid from the Expo template. Android used the image,
so the colour on that line had no effect and every launcher drew the template's background behind
the Finora mark. The key is now absent and the file deleted; if you reintroduce `backgroundImage`,
the colour stops mattering again.

**The Android foreground layer only shows its inner 66%.** Android reserves the outer third for mask
shape and parallax, so `android-icon-foreground.png` is drawn at ~52% of its canvas. Artwork taken
to the edge loses its extremities to whatever shape the launcher picks — and looks correct in every
preview that does not apply a mask.

**A favicon is not a logo.** `frontend/public/favicon.png` and `admin-portal/public/favicon.png` were
byte-identical copies of `logo-mark.png` at 638² and **470 KB**, served on every page load of both
apps. They are now 64² and about 4 KB.

## Regenerating

The committed assets were composited from the mark rather than cropped from a rendered variant, so
padding and safe zones are exact. If the mark changes, the recipe is: key the white background out
to alpha, un-premultiplying so anti-aliased edges do not keep a pale fringe when placed on navy;
then centre it on each canvas at the percentage in the table above; then flatten onto `#020E32` for
the opaque assets and write those as PNG colour type 2 so no alpha channel survives.

Verify afterwards with `sips -g pixelWidth -g pixelHeight -g hasAlpha <file>` against the table.

## Loose ends

Two committed assets are referenced by nothing, and are left in place rather than deleted so the
decision is explicit rather than accidental:

- **`splash-icon.png`** — there is no `expo-splash-screen` plugin and no `splash` key in
  `app.config.ts`, so the app currently has no configured splash screen. The file is branded and
  ready if that is wired up.
- **`logo.png`** — the horizontal lockup. Imported by no source file in either web app; only
  `logo-mark.png` is. Useful for a README or email template, dead weight otherwise.

Not done: `ios.icon` supports `light`/`dark`/`tinted` variants in SDK 57, which would let the icon
respond to iOS appearance settings. That needs designed variants, not generated ones.
