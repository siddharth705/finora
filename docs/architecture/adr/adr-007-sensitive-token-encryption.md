# ADR-007: Encryption at Rest for Third-Party Credentials

## Status

Accepted — 2026-08-14

Implemented: `com.finora.security.crypto`. No caller yet — Gmail sync (the first) is designed but
not built.

## Context

Finora is about to store its first credential it must be able to **read back**: a Google OAuth
refresh token, which is presented to Google in plaintext on every sync to mint a new access token.
Bank APIs, brokers, and account aggregators all need the same thing.

The codebase had no way to do this. A repo-wide search for `Cipher`, KMS, or Vault usage found
nothing outside PDF password-unlock, which is an unrelated concept (opening a user's
password-protected statement, not protecting our own secrets).

**What existed instead was a hashing utility, and reaching for it would have been the natural
mistake.** `TokenHasher` is one-way SHA-256, and it is correct for what it does: Finora's own
session tokens only ever need to be *compared* against a stored digest. Applying it to a
third-party refresh token destroys the token — the value can never be recovered, so it can never
be presented to Google, so the integration cannot work at all. The failure would surface not at
write time but at the first refresh, hours later, as an integration that silently stops.

The symmetric mistake is worse and quieter: encrypting something that should have been hashed. A
password stored reversibly is a password an attacker recovers from a database dump plus a key.
Both directions are one-line decisions with very different blast radii, so the distinction needed
to be written down rather than left to whoever writes the next integration.

Two further constraints shaped this:

- **Key management is a decision Finora has not yet earned.** There is no KMS, no Vault, and no
  appetite to add operational infrastructure before the first integration exists. But the choice of
  *where keys live* must not be baked into every call site, or moving it later touches everything.
- **Rotation is not hypothetical.** The scenario that forces a rotation is suspected key
  compromise, which is exactly when a slow, all-or-nothing re-encryption migration is least
  affordable.

## Decision

### AES-256-GCM, not CBC, not a hash

Authenticated encryption. GCM produces a tag that makes tampering detectable, so a ciphertext
altered in the database fails to decrypt rather than silently yielding different plaintext.

AES-CBC would keep the value just as secret and detect nothing. For a value that is handed
straight to a third party as a credential, undetected modification is the failure worth engineering
against — an attacker with write access to one column should not be able to change what Finora
presents to Google without that being loud.

### A fresh random IV per encryption, stored with the ciphertext

12 bytes from `SecureRandom` per call, prepended to the ciphertext, base64 of the whole blob.

This is load-bearing, not hygiene. **Reusing an IV under the same key breaks GCM outright** — two
messages sharing a key and IV leak the XOR of their plaintexts and can expose the authentication
subkey, forfeiting integrity for every message under that key. The IV is not secret; it only has to
be unique, which is why travelling with the ciphertext is fine and deriving it once is not.

`EncryptionServiceTest.theSamePlaintextNeverProducesTheSameCiphertextTwice` pins this with 50
iterations rather than 2, so a generator with a small period would still fail.

### Every ciphertext records the key that wrote it

`EncryptedValue(keyId, ciphertext)`. Callers persist both.

This is what makes rotation incremental. With a key id, old and new keys coexist: new writes go
under the active key, existing rows stay readable under the previous one, and re-encryption
proceeds a row at a time or lazily on next write. Without it, rotation is a flag day — every row
rewritten in one migration, under time pressure, during a suspected compromise.

`KeyProvider` therefore exposes two distinct lookups: `activeKey()` for writes, `keyById()` for
reads. A key is retirable only once nothing references its id.

### `KeyProvider` is the seam; keys come from the environment for now

`EnvironmentKeyProvider` reads base64 keys from configuration, supplied as Railway environment
variables — the same way every other secret in this deployment is handled, including
`GOOGLE_APPLICATION_CREDENTIALS`.

When this becomes KMS or Vault, a second `KeyProvider` implementation is the entire change. No
caller, no persisted ciphertext, and no column moves. That is the whole reason the interface exists
with one implementation.

### Validation at startup, and two different validators

Key **shape** is checked in `EnvironmentKeyProvider`'s constructor: base64-decodable, exactly 32
bytes. The length check is not pedantry — 16 bytes is a perfectly valid AES-128 key, so without it
a short key would work silently at half the intended strength.

Key **identity** is checked in `ProductionConfigValidator`: the prod profile refuses to boot while
the local-dev placeholder is still in place. This is a separate check because the placeholder is
perfectly well-formed — only the validator knows the difference between a valid key and the right
one. Same posture as `JWT_SECRET`, with a sharper consequence: on the dev key, anyone with the
repository can decrypt every stored integration credential.

## Consequences

**Good**

- Gmail (and every integration after it) has a supported way to store credentials, and one place to
  look for how secrets are protected.
- Rotation is a background sweep, not an outage.
- A tampered ciphertext fails loudly instead of producing a wrong credential.
- Misconfiguration fails at boot with a message naming the fix, not at a user's first connect.

**Costs and limits, stated plainly**

- **The key lives in an environment variable.** A process-memory dump or a leaked Railway
  environment exposes it. This is strictly better than plaintext-in-database (a database dump alone
  is now inert) and strictly worse than a KMS that never hands the key to the application. It is an
  accepted interim position, not an end state.
- **Rotation is designed but not exercised.** `rotate()` and the two-key path are unit-tested; no
  production sweep exists because nothing is encrypted yet. The first integration to store
  credentials should ship the sweep alongside it, or rotation stays theoretical.
- **No envelope encryption / per-record data keys.** One key encrypts everything. Adequate at this
  scale; revisit if the number of credential types grows or a compliance regime asks for
  per-tenant isolation.
- **Nothing enforces the hash-vs-encrypt choice.** The distinction is documented here and in the
  package doc, and a future reviewer still has to notice it. A Guardian/ArchUnit rule forbidding
  `EncryptionService` in auth-password paths would make it structural — not built, worth
  considering.

## How this is held

| Test | What it would catch |
|---|---|
| `EncryptionServiceTest.aValueSurvivesARoundTrip` | The baseline — that this is reversible at all, which is the entire difference from `TokenHasher`. |
| `EncryptionServiceTest.theSamePlaintextNeverProducesTheSameCiphertextTwice` | IV reuse, which silently breaks GCM's integrity guarantee while everything still appears to work. |
| `EncryptionServiceTest.tamperingIsDetected` | A regression to an unauthenticated mode. Passes trivially under GCM; fails under CBC. |
| `EncryptionServiceTest.rotationDoesNotStrandExistingValues` | The expensive mistake: a rotation that renders every previously stored credential unreadable. |
| `EncryptionServiceTest.aValueEncryptedUnderOneKeyCannotBeReadWithAnother` | That `keyId` is actually consulted rather than decorative. |
| `EnvironmentKeyProviderTest.aKeyOfTheWrongLengthIsRejected` | A 16-byte key silently downgrading the deployment to AES-128. |
| `EnvironmentKeyProviderTest.anInactiveKeyIsStillResolvableById` | Retiring a key too eagerly and stranding data mid-rotation. |
| `ProductionConfigValidatorTest.run_inProdProfile_withTheLocalDevEncryptionKey_throws` | Shipping to production on a key that is public in git. |

**A note on what these deliberately do not assert.** None of them tests that AES works — that is
the JDK's job. They test the properties *this codebase* can get wrong: mode choice, IV handling,
key identity, and configuration. `theSamePlaintextNeverProducesTheSameCiphertextTwice` is the one
worth keeping honest, since it is satisfied by a correct implementation and by no other.

## Related

- [ADR-002: Authentication Architecture](adr-002-authentication-architecture.md) — the session
  model whose tokens are *hashed*, and the reason the distinction in Context matters.
- `docs/proposals/gmail-transaction-sync-proposal.md` §12.1 — the first caller, and where the
  requirement originated.
- `com.finora.security.crypto` package doc — the hash-vs-encrypt rule at the point of use.
