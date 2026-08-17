# CI concurrency/cancellation policy — decided 2026-08-17

**Status: Closed.** No engineering work required — the repo already matches the agreed policy.

## Decision

| Workflow | Cancel previous in-progress run |
|---|---|
| Pull request CI | ✅ Enabled |
| Feature branch CI | ✅ Enabled |
| Main branch CI | ❌ Disabled |
| Production deployment | ❌ Disabled |
| Nightly regression runs | ❌ Disabled |

Rationale: for PRs and feature branches, an older run's result stops being useful the moment a
newer commit lands, so cancelling it saves runner time with no lost signal. For main, a production
deployment, and a scheduled regression run, every run's result is a validation record worth keeping
on its own — cancelling one of those trades a real confidence signal for CI time, which is the
wrong side of that trade for those three.

## Verification

Checked directly against the repo, not assumed:

- [`.github/workflows/ci.yml:30-32`](../../../.github/workflows/ci.yml) — single `concurrency`
  block, `cancel-in-progress: ${{ github.ref != 'refs/heads/main' }}`. One workflow serves both PR
  and main-branch runs, so this ternary is what encodes both the "PR/feature branch: enabled" and
  "main: disabled" rows — there's no separate main-only workflow file to check independently. The
  file's own header comment already states this reasoning ("never for main, where every commit's
  result is a record worth keeping rather than something to discard when the next one lands").
- [`.github/workflows/e2e-nightly.yml:47-49`](../../../.github/workflows/e2e-nightly.yml) — its own
  `concurrency` group (`e2e-full`), `cancel-in-progress: false`. Matches the nightly-regression row.
- Production deployment: no deployment workflow exists under `.github/workflows/` at all — Railway
  and Cloudflare Pages build and deploy from their own pipelines, outside GitHub Actions entirely.
  There's nothing here that *could* cancel an in-progress deploy, so the "disabled" row is satisfied
  by there being no GitHub Actions surface to violate it, not by an explicit setting.

No gaps found; no changes made.
