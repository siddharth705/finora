# White-on-white sweep, pagination, and import concurrency limiter

This is specifically what's new since the Brandfetch fix bundle — not a re-package of earlier
work (register/login CORS fix, admin base URL fix, auth UX fixes, `.gitignore`, `RecurringService`,
Brandfetch), which you already have from separate bundles.

## 1. White-on-white text — comprehensive app-wide sweep

The Login/Register fix from earlier only covered those two pages. Your screenshots showed the
same bug on the account-creation form and category dropdowns during import, which made clear this
needed a proper sweep, not another one-page patch.

**Root cause, confirmed across every instance:** an `<input>`/`<select>`/`<textarea>` with no
explicit `text-` color of its own inherits color normally through the CSS cascade —
`body { color: rgb(var(--color-ink)) }` — which resolves to near-white the moment Finora's own
dark mode is active. The earlier `color-scheme: light` fix (still correct and still in place)
stops the *browser's* native auto-dark-styling of unstyled inputs; it doesn't touch this — a
distinct mechanism producing the identical visible symptom.

**Found and fixed:** 45 `<input>` elements and 23 `<select>`/`<textarea>` elements missing
explicit text color, across 20 files. Auth-flow pages (`ForgotPassword`, `ResetPassword`,
`VerifyPhone`) get `bg-white text-gray-900`, matching the established Login/Register pattern.
Every in-app page (`Ledger`, `Budgets`, `Goals`, `Investments`, `Merchants`, `Rules`, `Settings`,
`Import`, `Setup`, `Dashboard`, `Reports`, `TopBar`, `AskOnceCard`) gets `bg-card text-ink` — the
app's own theme-reactive tokens, consistent with how the rest of each of these pages is already
styled, rather than a hardcoded color that wouldn't follow the user's selected theme.

**Caught and fixed my own mistakes along the way, rather than shipping them silently:**
- A regex pitfall where `=>` (arrow functions in `onChange={...}`) was truncating matches at the
  wrong `>` — the same class of bug from the earlier Login/Register fix, this time affecting the
  broader scan. Rewrote the scanner to anchor on the actual tag boundary instead.
- 5 places where the scan didn't recognize `text-muted` as a valid, already-correct color (it only
  checked for `gray/ink/white/black/slate`), so it double-injected `bg-card text-ink` on top of
  fields that were already intentionally styled for a disabled/muted state (`Import.tsx`'s 4
  "detected" read-only fields, `Dashboard.tsx`'s range-picker select). Cleaned all 5 up.
- 2 places where the same class (`bg-card`) or a conflicting background (`bg-black/5`) ended up
  duplicated in the same `className` (`Settings.tsx`'s read-only fields, `TopBar.tsx`'s search
  input). Cleaned up.

Verified comprehensively at the end: a scripted check across every `.tsx` file confirms no
`className` string anywhere still has more than one `bg-` or text-color class (the one remaining
hit, `Landing.tsx`'s `bg-gradient-to-r` + `bg-clip-text`, is the standard gradient-text pattern,
not a bug). `tsc -b` clean.

## 2. Pagination for "A few transactions need your input"

`AskOnceCard.tsx` now shows 10 per page with Previous/Next controls and a "Showing X-Y of Z"
line, matching the pattern already established in `Ledger.tsx`. Includes a clamp so the current
page can't drift out of range if items shrink below it (e.g. confirming everything on the last
page). New test file, 4 tests.

## 3. Import concurrency limiter (the "queue system" ask)

**Honest framing first:** a full external message-queue system (Redis, RabbitMQ) would be real
over-engineering for what this actually is — a single Railway instance with a 10-connection DB
pool, not a distributed system. The genuine crash risk under a burst of simultaneous imports isn't
"no queue exists" — it's that `ImportController.stage()`/`stagePdf()` run the *entire* parse
(PDFBox text extraction, categorization, duplicate detection) synchronously on the request thread,
with zero cap on how many can run at once. Enough simultaneous heavy imports competing for only 10
DB connections and however much memory PDFBox needs per file is a realistic path to the JVM
running out of memory — the whole app going down, every user's request included, not just the
import ones.

**What was actually built:** `ImportConcurrencyLimiter` — a bounded, **fair** (FIFO) `Semaphore`
gating how many imports can be mid-parse simultaneously. Fair ordering is what makes this a genuine
queue, not just a bare cap: excess requests wait in arrival order for a slot, exactly like a real
request queue, just implemented as an in-process primitive sized correctly for this app's actual
architecture rather than reaching for infrastructure that would only earn its complexity if this
ever became a genuinely multi-instance deployment.

- Wired into both `stage()` and `stagePdf()` in `ImportController` — the two heavy endpoints;
  session listing/lookup/delete are untouched, since they're cheap DB reads, not parsing work.
- `IMPORT_MAX_CONCURRENT` (default 6, deliberately under `DB_POOL_MAX_SIZE=10` so imports alone
  can never starve every other endpoint) and `IMPORT_ACQUIRE_TIMEOUT_MS` (default 20s) — new env
  vars in `application.yml`.
- A request that's still waiting past the timeout gets a clear, immediate response instead of
  hanging indefinitely — added `IMPORT_SYSTEM_BUSY` (`IMPORT_006`, HTTP 503) to the existing
  `ErrorCode` enum, following the app's own established error-code system rather than inventing a
  parallel exception hierarchy.
- `ImportConcurrencyLimiterTest.java` — 5 tests using **real threads**, not mocks, since the whole
  point is proving actual concurrent behavior: basic execution, permit release after completion,
  permit release even when work throws, the busy/503 path under real contention (verified via
  `CountDownLatch`, not a sleep-and-hope), and a genuine concurrency-bound test running 12 tasks
  through a limit of 3 and asserting the observed simultaneous count never exceeds it.

## Verification

`tsc -b` clean on the frontend. Couldn't compile the Java changes myself (no Maven, and this
sandbox only has a JRE, not even a bare `javac` for a syntax check) — traced everything by hand,
including fixing an `awaitility` dependency I initially reached for that isn't actually in this
project's `pom.xml`. Please run `mvn test` and `npm test` to confirm.
