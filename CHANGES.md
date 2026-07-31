# Thorough bug-fix pass — files not previously touched

Read in full, none touched before this session: `JwtService`, `RefreshTokenService`,
`OtpService`, `NetWorthService`, `RecurringService`, `WorkspaceDashboardService`,
`BankManagementService`, `WorkspaceSettingsService`.

## Real bug found and fixed

### `RecurringService` — false "recurring" flag on unrelated no-merchant transactions

Transactions with no merchant identified (manual cash entries, anything the categorization
pipeline couldn't extract a merchant from) were all bucketed together under the literal string
`"unknown"` for pattern-based recurring detection. That means two entirely **unrelated**
transactions with no merchant — say, two separate manual cash withdrawals — that happen to land
at a roughly regular interval with a similar amount could get falsely flagged as a recurring
merchant literally named "unknown," which would then show up as a nonsense entry in Reports/the
Financial Intelligence Workspace.

There's no real merchant pattern to detect without a merchant at all, so these are now excluded
from grouping entirely rather than defaulted into a shared bucket. They still go through the
separate `MARK_SUBSCRIPTION` rule-based pass unaffected (that one matches on description text, not
merchant, so it doesn't have this problem).

**Added a regression test** — this class had test coverage before, but nothing exercised the
no-merchant case at all.

## Clean bill of health

- **`JwtService`** — token generation/validation, expiry check. Correct.
- **`RefreshTokenService`** — rotation + reuse-detection (revoke-all-sessions on a replayed
  token) is a genuinely well-implemented security feature, correctly done.
- **`OtpService`** — attempt-limiting, expiry, replay protection via `verifiedAt`. Correct. (The
  "only the latest OTP is checkable" behavior looked worth a second look, but it's explicitly the
  intended design per the class's own doc comment — a resend intentionally invalidates the
  previous code — not a bug.)
- **`NetWorthService`** — already carries its own documented fixes (timezone, a save-snapshot
  race condition); nothing further found.
- **`WorkspaceDashboardService`** — thorough, honest about which health signals are real vs.
  placeholder. Nothing found.
- **`BankManagementService`** — one thing double-checked and ruled out: `search()` passes the raw
  (non-pre-normalized) query into `BankRegistry.search()`, which looked suspicious at a glance,
  but `BankRegistry.search()` does its own internal trim/lowercase normalization — redundant, not
  incorrect.
- **`WorkspaceSettingsService`** — small, correct, honest about what isn't wired up yet.

## Verification

Same constraint as every backend round — no Maven in this sandbox. Traced the fix and its test by
hand against the actual repository logic. Please run `mvn test`.

## About the `git commit` command

Same as last time — I can't run this against your actual repository from here; my sandbox is a
disconnected copy of the uploaded code, not a live connection to your machine or GitHub. Once
you've applied this bundle to your real checkout:

```
git commit -m "fix(recurring): exclude no-merchant transactions from pattern grouping to prevent false recurring flags"
```
