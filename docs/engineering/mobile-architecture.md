# Mobile Architecture

How `mobile/` is put together, why it diverges from `frontend/` where it does, and the traps that
have already cost time. Read alongside `docs/engineering/mobile-setup.md`, which covers getting it
running on hardware.

## Contents

1. [Shape of the app](#shape-of-the-app)
2. [Route protection is navigator structure](#route-protection-is-navigator-structure)
3. [Porting rules](#porting-rules)
4. [Deliberate divergences from web](#deliberate-divergences-from-web)
5. [Platform traps](#platform-traps)
6. [Design tokens](#design-tokens)
7. [Testing](#testing)

---

## Shape of the app

```
mobile/
├── App.tsx                 providers only: QueryClient -> SafeArea -> Auth -> Offline -> Navigator
├── app.config.ts           bundle IDs, Firebase plugins, build properties
├── eas.json                build profiles
└── src/
    ├── api/                client.ts (axios + interceptors), endpoints.ts, queryClient.ts
    ├── context/            AuthContext -- the only global state
    ├── navigation/         RootNavigator (auth gate), AppTabs, types
    ├── screens/            one file per screen, no sub-routing
    ├── components/         shared primitives + charts/
    ├── lib/                pure functions: validation, format, apiError, chartGeometry, storage
    ├── theme/              palette and scale
    └── types/              backend DTO mirrors
```

Two rules keep this navigable:

- **`lib/` is pure.** No React, no navigation, no side effects beyond storage. This is what makes
  the validation and chart-geometry tests possible — logic trapped inside a component can only be
  tested by rendering it.
- **`api/` never imports upward.** `client.ts` is imported by context and screens, so it cannot
  import them back. Where it needs to trigger navigation (session expiry, phone-verification
  required) it calls registered callbacks instead — see `setSessionCallbacks`, which `AuthContext`
  wires to state changes the navigator already reacts to.

## Route protection is navigator structure

The web app guards routes with `ProtectedRoute`, which redirects. Mobile derives *which navigator
exists at all* from auth state (`RootNavigator`):

| State | Mounted |
|---|---|
| `bootstrapping` | Splash spinner |
| `token === null` | Auth stack (Login, Register, ForgotPassword) |
| token, `!phoneVerified` | VerifyPhone, alone |
| token, verified | `AppTabs` |

A signed-out user has no route to the app to navigate *to*, so there's no flash of authenticated
UI to defend against and no imperative `navigate()` after login, register, or verification —
setting state **is** the navigation. Screens never call `navigate()` on auth success; if you find
yourself wanting to, the state change is missing instead.

As on web, this is UX only. `PhoneVerificationFilter` on the backend is the real enforcement and
rejects unverified accounts regardless of what the client renders.

## Porting rules

Most of `frontend/src` transfers directly. When porting a screen:

1. **Types and endpoints port verbatim.** They're plain TypeScript over axios.
2. **Keep the query keys identical to web.** `invalidateFinancialData` depends on it, and so does
   anyone reasoning about both apps at once.
3. **Port the comments too.** The web codebase documents *why* — the bug behind a fix, the reason
   something was deliberately not built. Dropping that on the way over loses the expensive part.
4. **Check for a DOM assumption before assuming a straight port.** `window.prompt`, `window.print`,
   Blob downloads, `onPaste`, drag-and-drop, and `document.createElement` all appear in the web
   app and none exist here.

## Deliberate divergences from web

Each of these is a decision, not an oversight.

| Area | Web | Mobile | Why |
|---|---|---|---|
| Session storage | `localStorage`, synchronous | `expo-secure-store`, async | Encrypted at rest. Async is what forces `bootstrapping` below. |
| Session restore | `useState` initializer | effect + `bootstrapping` flag | SecureStore reads can't happen during render; without the flag a cold start shows Login to a signed-in user for a frame. |
| Phone auth | Firebase Web SDK + invisible reCAPTCHA | `@react-native-firebase/auth`, native | Native app verification (silent APNs / Play Integrity). No verifier, no container, no cleanup. |
| Ledger paging | Previous/Next | infinite scroll | Also removes a bug class: no current page to strand when a delete empties the last one. |
| Charts | Chart.js | hand-rolled `react-native-svg` | Canvas doesn't exist; every RN chart library is a native dep to re-validate each SDK bump, for one ring of arcs and two polylines. |
| Password reset | completes in-app | requests email, completes on web | The emailed link targets `APP_BASE_URL`. Deep-linking deferred until the hand-off is shown to be real friction. |
| `devResetLink` | displayed when unconfigured | never displayed | A live account-takeover primitive; no dev-convenience payoff in a shipped binary. |
| Refetch on focus | disabled | omitted | "Window focus" has no native equivalent, and web turned it off anyway. |
| Connectivity | assumed online | NetInfo → `onlineManager` | A phone goes through tunnels; a browser tab doesn't. |

## Platform traps

Real bugs already hit here. Each cost time; none are obvious from the code.

**Never put `maxLength` on an input whose `onChangeText` sanitizes.** React Native applies
`maxLength` to *pasted* text, truncating before the handler runs. A `maxLength={10}` phone field
turns a pasted `+919876543210` into the wrong number, and a `maxLength={6}` OTP field turns a
pasted SMS into an empty string. The web app dodges this with a separate `onPaste` reading the
clipboard directly — RN has no equivalent. Let the sanitizer cap instead; see
`lib/validation.ts` and its tests.

**Anything consuming safe-area insets above the navigator must re-provide them.** Screens each pad
by `insets.top`. `OfflineBoundary` sits above the navigator and consumes that inset itself, so it
re-provides `top: 0` through `SafeAreaInsetsContext` — without which everything jumps down ~47pt
the moment connectivity drops.

**`err.response` is undefined on a transport failure.** `err.response?.data?.message ?? fallback`
therefore reports the caller's domain fallback ("check your credentials") when the real cause is
no connectivity. Always go through `lib/apiError.ts`.

**Jest's `setupFiles` replaces the preset's array rather than merging.** jest-expo uses that slot
for React Native's environment setup; overriding it makes RNTL's `render` a silent no-op that
returns `{}`, and every query fails with "`render` function has not been called" — pointing
nowhere near the config. Put setup in `setupFilesAfterEnv`, which still runs before the test
file's imports.

**A 360° SVG arc draws nothing** — its start and end points are identical. A single spend category
owning 100% therefore needs a circle, not a path. See `buildArcs`.

## Design tokens

`src/theme/index.ts` carries the same palette as `frontend/src/index.css`, in both light and dark,
resolved through `useTheme()` rather than imported directly so the system scheme is honored for
free. `radius` and `spacing` scales live there too.

Screens should not hardcode colors. The one deliberate addition beyond web's palette is
`warningInk`: the shared `warning` tone reaches only 2.86:1 as text on `warningBg`, well under
WCAG AA's 4.5:1, so the offline banner uses a darker amber at 6.37:1. `warning` itself is
unchanged, since it's fine in the icon and border roles web uses it for.

**Three inherited pairs still fail AA in the light theme** and are shared with the web app, so
they need a product-level decision rather than a mobile-only fix:

| Pair | Ratio | Needs |
|---|---|---|
| `success` on card (income amounts) | 3.30:1 | 4.5:1 |
| `primary` on card (link buttons) | 4.47:1 | 4.5:1 |
| `muted` on page background | 4.44:1 | 4.5:1 |

Dark theme passes everywhere. Amounts also carry a `+`/`−` prefix, so color isn't the sole carrier
of meaning — but legibility is still below standard.

## Testing

`npm test` runs Jest with `jest-expo`. Native modules are mocked in `src/test/setup.ts`;
SecureStore is backed by a real in-memory map so `AuthContext`'s actual persistence and async
behavior is exercised rather than stubbed.

**`@testing-library/react-native` is pinned to 13.3.3, and `react-test-renderer` to 19.2.3.** Both
are exact, not ranges. RNTL 14 with its `test-renderer` v1 peer does not work under jest-expo 57 —
`render` silently returns an empty object rather than failing loudly. `react-test-renderer` must
match React's exact version, which Expo pins at 19.2.3.

What's covered today: validation and paste-sanitization (regression tests for both paste bugs
above), chart geometry including every degenerate case, `toUserMessage` across transport
failures / server envelopes / 5xx / Firebase codes, and `AuthContext` bootstrap, login, logout,
and verification.

What isn't: screen rendering, navigation, and anything requiring hardware. CI type-checks, tests,
and bundles — it does not compile native code. **Nothing in CI substitutes for the on-device
checklist in `mobile-setup.md`.**
