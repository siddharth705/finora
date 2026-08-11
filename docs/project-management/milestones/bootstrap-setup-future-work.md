# Bootstrap Setup: Deferred Future Work

The first-run setup flow (V33__bootstrap_admin.sql, BootstrapService, SetupService) is
implemented and working for a single local/dev deployment. This document tracks the items that
came up during design review but were deliberately deferred -- so they're documented rather than
forgotten, not because they're unimportant.

None of these are required for the current single-operator, single-instance, local-development
use case. Revisit before this flow is ever exposed to a real multi-operator or production
deployment.

---

## RFC-BOOTSTRAP-01: Production-safe credential delivery

**Status:** Future

**Problem:** The bootstrap password is currently logged via `log.warn(...)`, which is fine for
`docker compose logs backend` on a local machine, but would be captured by any centralized
logging pipeline (Splunk, ELK, CloudWatch) in a real deployment -- at which point everyone with
log access has the credential that grants `SYSTEM_INITIALIZE`.

**Why not fixed now:** This only matters once centralized logging exists, which it doesn't for
this project yet. There's also no clean fix that's actually *better* than logging, not just
differently-named -- routing the same secret through a URL query parameter (as one review
proposed) trades one leak vector (log aggregators) for others that are arguably worse
(reverse-proxy access logs, browser history, `Referer` header leakage to any external resource
the setup page happens to load).

**Recommended direction when this matters:** Write the credential to a local file on the
container's filesystem (e.g. `/app/data/bootstrap-credentials.txt`, readable only by whoever can
already `docker compose exec` into the container) instead of stdout/logs. That keeps the secret
off any log-shipping pipeline entirely, rather than just relabeling it.

**Scope note:** this isn't unique to the bootstrap flow. The same intentional pattern -- a secret
appears in both logs and the API response, gated behind "no real provider is configured" -- is
also how `NoOpSmsService` (OTP codes) and `NoOpEmailService` (password reset links) behave today.
All three exist for the same reason (no SMS/email provider wired up in this environment) and
should probably be hardened together, not as three separate fixes, whenever this is revisited.

---

## RFC-BOOTSTRAP-02: Secure recovery mode

**Status:** Future

**Problem:** Once `setup_completed = true`, `BootstrapService` never creates another bootstrap
account under any circumstance (by design -- see its own doc comment on why this is a floor, not
a bug). If every `SUPER_ADMIN` account is ever deleted or locked out, there is currently no
supported recovery path other than direct database access.

**Recommended direction:** An explicit, out-of-band recovery mode -- e.g. a `--recovery` CLI flag
or `FINORA_RECOVERY_MODE=true` environment variable that a human operator must deliberately set,
which temporarily re-enables bootstrap creation regardless of `setup_completed`. Deliberately not
automatic (e.g. not "no SUPER_ADMIN exists -> auto-bootstrap again"), since that would turn an
operational mistake (accidentally deleting all admins) into a standing security hole reachable by
anyone who can trigger that precondition.

---

## RFC-BOOTSTRAP-03: Immutable initialization record

**Status:** Future, not urgent

**Problem:** The audit log (`SETUP_COMPLETED`, `BOOTSTRAP_CREATED`) captures *who did what, when*
-- which is what audit logs are for. It doesn't capture platform/environment metadata at the
moment of initialization: application version, database (migration) version, or the initializing
operator's IP. Years from now, "the audit log says user X completed setup on date Y" is
available; "which build of Finora was running when this platform was initialized" is not,
unless it's cross-referenced against deployment history kept elsewhere.

**Recommended direction:** A single-row `platform_initialization` record (similar in spirit to
the existing `platform_settings` singleton), written once alongside `SETUP_COMPLETED`, holding
whatever of {app version, Flyway schema version, initializing IP} is easy to capture at that
point. Low priority: this is enrichment of an already-working audit trail, not a gap in it.
