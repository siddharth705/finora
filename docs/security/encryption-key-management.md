# Encryption key management

**Scope:** operating `FINORA_ENCRYPTION_KEY` — the key protecting third-party credentials at rest.
For *why* the design is what it is, see
[ADR-007](../architecture/adr/adr-007-sensitive-token-encryption.md); this document is what to do.

**Read this before the first integration stores a credential.** This key becomes a hard production
dependency the moment anything is encrypted under it, and its principal failure mode is
unrecoverable.

---

## 1. What this key protects

Third-party OAuth refresh tokens and, later, any credential Finora must present back to an external
provider (bank APIs, brokers, account aggregators). These are **live credentials to a user's
external account**, not merely records.

**What it does not protect:** user passwords and Finora's own session tokens. Those are hashed via
`TokenHasher` and are unaffected by anything in this document. If you find yourself reaching for
this key to protect a password, stop — see ADR-007's Context.

**The property this buys:** a stolen database dump is inert. Ciphertext without the key is
unusable, which is why the key must live somewhere the database backups do not (§6).

---

## 2. Creating a production key

```bash
openssl rand -base64 32
```

Must decode to **exactly 32 bytes** (AES-256). The application refuses to start otherwise —
including on a 16-byte value, which is a valid AES-128 key and would silently halve the strength if
unchecked.

Generate it on a trusted machine. Do not reuse a key between environments: a dev key that leaks
should never be able to decrypt production data.

---

## 3. Setting it in Railway

```
Railway → Finora Tech → Backend service → Variables

FINORA_ENCRYPTION_KEY = <the base64 value>
```

Then redeploy once and confirm the service starts. Two possible outcomes:

| Startup result | Meaning |
|---|---|
| Starts normally | Key is present, well-formed, and not the dev placeholder |
| `Refusing to start with the prod profile active…` naming `FINORA_ENCRYPTION_KEY` | Unset, or still the local-dev placeholder |
| `Encryption key 'v1' must decode to 32 bytes…` | Wrong length — regenerate per §2 |
| `Encryption key 'v1' is not valid base64` | Value was mangled in transit (line wrap, quotes) |

All four are startup-time. There is deliberately no path where a misconfigured key is discovered
later, at a user's first integration connect.

### Local development

Nothing to set. `application.yml` carries an obviously-fake placeholder so the test suite and local
runs work with no setup. That value is public, in git, identical for every developer, and protects
nothing — which is exactly why production refuses to boot on it.

---

## 4. If the key is lost

**Encrypted credentials cannot be recovered. There is no backdoor, and this is by design** — a key
that could be reconstructed would not be protecting anything.

What is and is not lost:

| Data | Status |
|---|---|
| Transactions, accounts, statements, budgets, goals | **Unaffected** — never encrypted with this key |
| User passwords and sessions | **Unaffected** — hashed, not encrypted |
| Stored OAuth refresh tokens | **Unrecoverable** |

### Recovery procedure

1. Generate a new key (§2) and set it in Railway (§3).
2. Mark every affected connection as needing reauthentication — the same
   `REAUTH_REQUIRED` state used when a provider revokes a token externally.
3. Notify affected users that they must reconnect the integration.
4. Do **not** attempt to decrypt old rows. They are noise now; delete or ignore them.

The user-visible cost is a reconnect, not lost financial data. That containment is deliberate:
Finora stores extracted fields, never the raw source material, so an unrecoverable token costs
future sync, not history.

---

## 5. Rotation

Supported by design: each ciphertext records the key id that wrote it, so old and new keys coexist
and rows re-encrypt incrementally instead of in one flag-day migration.

**Never exercised in production.** `rotate()` and the two-key path are unit-tested; no production
sweep exists, because nothing is encrypted yet. The first integration to store credentials should
ship the sweep alongside it, or rotation stays theoretical exactly until the emergency that needs it.

### Procedure

1. Generate a new key (§2).
2. Add it **alongside** the existing one, keeping the old entry:
   ```
   FINORA_ENCRYPTION_KEY        = <old key>     # keys.v1 — still needed to read existing rows
   FINORA_ENCRYPTION_KEY_V2     = <new key>     # keys.v2
   FINORA_ENCRYPTION_ACTIVE_KEY_ID = v2
   ```
   (`application.yml` currently wires only `v1`; adding a second entry is a config change that
   ships with the rotation.)
3. Deploy. New writes go under `v2`; existing `v1` rows keep decrypting.
4. Run the re-encryption sweep — `EncryptionService.rotate()` per row, which returns rows already on
   the active key untouched, so the sweep is safe to re-run.
5. Only once **no row references `v1`**, remove it.

**Removing the old key too early strands every row still written under it.** `keyById` fails loudly
rather than silently returning nothing, so this surfaces as a hard error rather than corrupted data
— but the data is still unreadable until the key is restored. Verify before removing.

---

## 6. Backups and suspected compromise

**Where the key lives matters more than whether it is backed up.** Keep a copy somewhere recoverable
(a password manager), because §4 is the alternative. But **never store it alongside database
backups** — a backup archive containing both the dump and the key is a plaintext credential store
with extra steps, and it forfeits the one property §1 buys.

On suspected compromise — leaked environment, compromised machine, departed contractor with Railway
access:

1. Rotate immediately (§5). Do not wait for a maintenance window.
2. Treat every stored third-party credential as exposed. Rotation re-encrypts them; it does not
   un-expose a value an attacker may already hold.
3. Force reauthentication of affected integrations, and revoke tokens provider-side where the
   provider supports it (Google's revoke endpoint, for Gmail).

Step 2 is the one people skip. Rotation protects future reads of the database; it does nothing about
a token already extracted.

---

## 7. Known gaps

Stated rather than implied, because both affect how much this document is worth:

- **The key lives in an environment variable.** A process-memory dump or a leaked Railway
  environment exposes it. Strictly better than plaintext-in-database; strictly worse than a KMS that
  never hands the key to the application. `KeyProvider` is the seam for that migration — see
  ADR-007.
- **Rotation is untested against real data** (§5). The first credential-storing integration should
  fix that.

---

## Related

- [ADR-007: Encryption at Rest for Third-Party Credentials](../architecture/adr/adr-007-sensitive-token-encryption.md)
- [Secrets and IAM audit](secrets-and-iam-audit.md) — where every other credential lives
- `com.finora.security.crypto` — the implementation
