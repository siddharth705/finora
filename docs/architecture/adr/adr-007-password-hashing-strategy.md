# ADR-007: Password Hashing Strategy

## Status

Accepted — 2026-08-14

## Context

A review was requested of Finora's password security posture against production fintech
standards: hashing algorithm, salting, plaintext exposure, and peppering. This ADR records what
the review found and the resulting decision, so the reasoning doesn't have to be reconstructed
from a chat log the next time someone asks the same question.

The review read `SecurityConfig`, `AuthService`, `PasswordChangeService`,
`PasswordHistoryService`, and `BootstrapService`, and traced every write to `User.passwordHash`.
Findings:

- **Algorithm**: `BCryptPasswordEncoder(12)` — [`SecurityConfig.passwordEncoder()`](../../../backend/src/main/java/com/finora/config/SecurityConfig.java).
  Not an oversight: `docs/security/iam-priority2-implementation-notes.md` already records this as
  deliberate Priority-2 IAM work, and `docs/project-management/plans/engineering-directive-phase1.md`
  names "BCrypt/Argon2" as the accepted algorithm family. Work factor 12 is within OWASP's
  recommended range (≥10) for bcrypt.
- **Salting**: automatic and correct. `BCryptPasswordEncoder.encode()` generates a fresh random
  salt per call and embeds it in the stored hash (`$2b$12$<salt><hash>`); `User` correctly has no
  separate salt column.
- **Plaintext exposure**: none found. Every write path to `passwordHash` — registration,
  `PasswordChangeService.complete()`, `AuthService.resetPassword()`, `BootstrapService` — routes
  through `passwordEncoder.encode(...)`, with no exceptions.
- **Adjacent controls already in place**: BCrypt's 72-byte truncation limit is guarded by
  `@Size(max = 72)` on the registration/change DTOs; the last 5 password hashes are checked to
  block reuse (`PasswordHistoryService`, hashes only, never plaintext); login carries a
  timing-attack mitigation (BH-014) so a locked or nonexistent account still costs a real
  `passwordEncoder.matches()` call; 5 failed attempts trigger a 15-minute lockout.
- **Gap against a full "production fintech" checklist**: no pepper (a secret, held outside the
  database, combined with the password before hashing).

## Decision

Finora continues to use `BCryptPasswordEncoder(12)` with BCrypt's built-in per-password random
salt. **Peppering is intentionally not implemented at this stage.**

## Rationale

BCrypt-12 with a unique salt and zero plaintext exposure already satisfies the accepted bar
(OWASP lists bcrypt at work factor ≥10 as acceptable; Argon2id is the preferred first choice, not
a hard requirement over an already-adequate bcrypt configuration).

Adding a pepper now would trade a real, immediate operational risk for a marginal, currently
unneeded hardening gain:

- **No transparent migration path exists.** A pepper changes what gets hashed; applying it to
  every existing account requires the plaintext password, which Finora never has and never
  stores. Any pepper introduction needs either a "new passwords only" model or dual-hash
  verification during a transition window — not a drop-in change.
- **A new critical secret dependency.** The pepper would need to live outside the database
  (matching how `JWT_SECRET` is already held), and losing it breaks authentication for every
  account on the platform simultaneously — a single point of failure with a worse blast radius
  than the attack it defends against.
- **No dual-verification logic exists yet** to support a phased rollout, so shipping this
  correctly is a real feature, not a config flag.

None of this is urgent: the gap a pepper closes only matters if the password hash database is
exfiltrated *and* the attacker cannot also reach wherever the pepper is stored — a real but
secondary layer, not a substitute for the primary control (BCrypt-12 itself), which is already in
place.

## Consequences

- No code changes. `SecurityConfig`, `AuthService`, `PasswordChangeService`, and
  `PasswordHistoryService` are unchanged by this ADR.
- If the password-hash table is ever exfiltrated, offline brute-force resistance is bounded by
  BCrypt-12 alone, with no additional secret-based layer.
- Re-evaluation is scoped as deferred hardening (below), not an open decision — nothing further
  is owed on this before then.

## Future work — deferred, not scheduled

**IAM Hardening: Evaluate password peppering.** Post-v1.0 security hardening candidate; does not
gate the v1.0 launch.

Scope, when picked up:

- Define the pepper secret's storage strategy (same operational home as `JWT_SECRET` today).
- Decide the migration approach for existing users — dual-hash verification during a transition
  window is the likely shape, since no plaintext exists to rehash directly.
- Implement dual-hash verification if that approach is chosen.
- Define a secret rotation strategy.
- Test the impact on account-recovery flows (forgot-password, OTP-gated password change) under
  the new verification path.

## Related

- [ADR-002: Authentication Architecture](adr-002-authentication-architecture.md) — session/token
  lifecycle. A distinct concern from password storage, which is what this ADR covers.
- `docs/security/iam-priority2-implementation-notes.md` — where BCrypt(12) first landed, as part
  of the Priority 2 IAM pass.
- `docs/project-management/plans/engineering-directive-phase1.md` — names "BCrypt/Argon2" as the
  accepted algorithm family this decision satisfies.
