# Finora Remote Configuration & Feature Management — Design Proposal

**Status:** Proposal only. **Nothing here is implemented (beyond what already exists — see §2).**
Design now, build after current release blockers are closed:

- C-8 Track B completion
- 56 open bug-hunt findings resolved
- Pre-launch production-safety work (backup/recovery, Sentry monitoring)

Same sequencing rationale as the other proposals in this directory
(`support-help-feedback-proposal.md`, `notification-communication-platform-proposal.md`). Not a
current GA requirement — a platform capability for future safe rollout.

**Major correction to the originating draft's premise:** it proposes building feature flags from
scratch (`feature_flags` entity, admin console, audit logging) as new work. **This already exists,**
built and audited: `FeatureFlag` entity, `FeatureFlagService`, `FeatureFlagRepository`,
`AdminFeatureFlagController` at `/api/v1/admin/feature-flags`, gated behind the `SYSTEM_SETTINGS`
permission, already wired to one real call site (`RecurringService.java:73`,
`RECURRING_DETECTION_ENABLED`). Re-reading §3.1/§5.1/§7 (Feature Management) of the original draft
against this: the backend half of that section is not a proposal, it's a description of code that's
already merged. This document reframes scope around what's actually still missing.

---

## 1. Objective

Extend Finora's *existing* feature-flag system into a complete remote-control layer: generic
non-boolean configuration values (not just on/off), and application version/force-update management
— the two pieces confirmed genuinely absent from the codebase. Not a rebuild of feature flags.

## 2. What exists today (baseline)

- **Feature flags — real and generic.** `feature_flags` table (UUID-keyed, so genuinely
  multi-row/generic, unlike `platform_settings` below): `key`, `description`, `enabled`,
  `updated_at`. `FeatureFlagService` provides list/setEnabled with audit logging via the standard
  `AuditService` convention (see §5.3). One live call site today (`RecurringService`).
  **Important existing behavior to design around, not silently inherit:**
  `FeatureFlagRepository.isEnabled(key)` **fails open** — an unknown/typo'd key evaluates to
  `enabled`. That's a defensible default for a feature like recurring-transaction detection (missing
  flag → feature works, degrade gracefully). It is the wrong default for anything security- or
  cost-sensitive, and specifically wrong for gating Fino (§7) — a typo'd or not-yet-created
  `fino_enabled` key should not silently turn Fino on for everyone. This needs an explicit decision
  at implementation time: either a per-flag `defaultWhenMissing` value, or a documented convention
  that risk-bearing flags are seeded at creation time and never looked up before they exist.
- **No dedicated admin UI found for feature flags today** — the controller exists
  (`/api/v1/admin/feature-flags`), but no confirmed React page in `admin-portal/` consumes it yet
  (`SystemHealth.tsx` is read-only health, not a flag editor). This is real remaining work, just not
  backend work.
- **`platform_settings` is not a generic config store — don't reuse it as one.** It's a fixed-column
  singleton (`registrations_enabled`, `max_failed_login_attempts`, `lockout_duration_minutes`), read
  live with no cache (`PlatformSettingsService` deliberately rejects caching — an admin change takes
  effect on the very next request). A new generic `app_config` table is genuinely needed for
  arbitrary key/value settings like `max_statement_size` — this part of the original draft's
  reasoning was right, it just needs to not be confused with `platform_settings`.
- **App version / force-update — confirmed absent.** No minimum-version check, update-prompt, or
  version-gating logic anywhere in `mobile/` or the backend. This is genuinely new work.
- **No environment column anywhere, and none should be added.** Finora runs one Railway instance per
  environment (dev/test/prod Spring profiles, no staging — confirmed repeatedly elsewhere in this
  proposal set). Both `platform_settings` and `feature_flags` are single-row-per-deployment already,
  which is correct for this architecture. The original draft's "Development / Staging / Production"
  environment-scoping requirement (§8) doesn't map onto anything Finora actually has — a config row
  in the prod database is already prod-only by construction, because prod is a separate database
  from dev. No new environment-separation mechanism is needed.

## 3. Proposed scope (v1 — the only thing being designed here)

### 3.1 Generic remote configuration (new)

```
app_config
├── id
├── key            — e.g. max_statement_size, budget_warning_percentage, maintenance_message
├── value           — stored as text; typed on read
├── type            — STRING, INT, BOOLEAN, PERCENTAGE — informs client-side parsing/validation
├── description
├── updated_at
```

Live-read, no cache — matches `PlatformSettingsService`'s own reasoning exactly (an admin fixing a
bad config value under pressure should not have to wait for a cache TTL or restart the service).

**Validation by `type`, enforced on write, not just documented as a convention.** Without it, an
admin typo (`budget_warning_percentage = abc`) is stored successfully and fails downstream, at
runtime, in whichever caller reads it — a worse failure mode than rejecting it at the point of
entry:

```
INT         → numeric, parseable
BOOLEAN     → true/false only
PERCENTAGE  → numeric, 0–100 inclusive
STRING      → non-empty, reasonable max length (mirror UPLOAD_MAX_FILE_SIZE-style bounds
              elsewhere in the codebase rather than picking an arbitrary number)
```

Reject the write with a clear error rather than accepting free text and hoping the reader validates
— the same "fail at the boundary, not downstream" instinct `ProductionConfigValidator` already
applies to environment variables at boot.

### 3.2 App version management (new)

```
app_versions
├── id
├── platform          — ANDROID, IOS, WEB
├── minimum_version
├── latest_version
├── force_update
├── release_notes
├── updated_at
```

Mobile app checks this once at startup (and optionally on foreground-resume) against the config
endpoint (§4). A `force_update = true` with `minimum_version` above the installed version blocks
further use until updated — the mechanism the original draft's "security issue detected" scenario
actually needs.

**`force_update` needs a higher bar than every other setting here, because it's the one that can
lock out every user on an older version immediately, with no staging environment to catch a mistake
first (§6).** Enabling it should require:
- An explicit admin confirmation step in the UI ("This will block users below version X from
  accessing Finora"), not a plain toggle identical to every other flag.
- A required `reason` string, captured alongside the change.
- An audit entry distinct enough to find quickly during an incident, e.g.
  `{"action": "FORCE_UPDATE_ENABLED", "reason": "Critical security patch", "minimumVersion": "2.1.0"}`
  — same `AuditService` convention as everything else (§5.3), just with `reason` always present in
  the metadata for this one action.

### 3.3 Feature flag additions (small, not a rebuild)

Given the system already exists, the only backend gaps are:
- A `default_when_missing` field addressing the fail-open concern in §2, defaulting to today's
  fail-open behavior for backward compatibility, settable per-flag for anything risk-bearing.
- The admin UI page that doesn't yet exist (§2) — this is the actual missing "Feature Management
  Console" from the original draft's §7, not new backend.

## 4. Unified config API (as originally proposed — this part was correct)

```
GET /api/v1/app/config
```

```json
{
  "features": { "fino": false, "investment_dashboard": true, "recurring_detection": true },
  "config": { "max_upload_size": "10MB", "budget_warning_percentage": "80" },
  "version": { "minimum": "1.0.0", "latest": "1.2.0", "forceUpdate": false }
}
```

One endpoint aggregating all three sources (existing `feature_flags`, new `app_config`, new
`app_versions|`) — apps never query the database directly, matching the original draft's
architecture diagram, which was correct and needs no change.

## 5. Admin portal requirements

- **Feature flag management page** — genuinely missing today; list/toggle/description, same
  `SYSTEM_SETTINGS` permission the controller already enforces.
- **Config value management page** — list/edit `app_config` rows.
- **Version management page** — edit `app_versions` per platform.

### 5.3 Audit logging — match the existing convention, don't invent a new one

The original draft specified a change record with explicit `Old value` / `New value` fields. The
codebase's actual `AuditService.record(actorId, action, entityType, entityId, metadata)` convention
(used identically by `PlatformSettingsService` and the existing `FeatureFlagService`) is
**new-state-only** — no old-value field exists anywhere else in the audit trail. Two options, not a
default: either follow the existing new-state-only convention for consistency (simplest, matches
everything else), or this is the first place old-value tracking gets added — if so, that's a
convention change affecting the whole audit system, not a config-feature detail, and should be
raised as its own decision rather than introduced quietly inside this proposal.

## 6. Security requirements

- Only `SYSTEM_SETTINGS`-permitted admins can modify config/flags/versions — already enforced for
  feature flags, extend the same check to the two new controllers.
- No environment-separation mechanism needed (§2) — but exactly *because* there's no staging, a
  malformed `app_config` value or an accidental `force_update = true` takes effect in prod
  immediately, live-read, with nothing to catch it first. Addressed directly rather than with full
  environment-promotion tooling: `app_config` writes are type-validated (§3.1), and `force_update`
  specifically requires a confirmation step + reason (§3.2) — the two concrete safeguards that
  matter given this architecture, not a general staging pipeline this deployment model doesn't have.

## 7. Future Fino compatibility

Fino should consume this platform (`fino_enabled` flag, `fino_rollout_percentage` config value) as
originally proposed — the phased example (internal → beta % → full) is sound and needs no change.
The one addition: given §2's fail-open finding, `fino_enabled` specifically must not be looked up
before its flag row exists, or Fino silently activates for everyone the moment any code references
the key. Sequence flag creation before any code path checks it.

**Percentage rollout requires deterministic user bucketing — not designed now, noted so it isn't
built naively later.** A rollout check that re-randomizes per request (`Math.random() < rollout%`)
gives each user a different answer on every call — Fino on today, off tomorrow, for the same person.
The correct approach when this is actually built (Phase 3, per §8 — not this proposal) is a stable
hash of user id against the rollout threshold (`hash(userId) % 100 < rollout_percentage`), so a
user's bucket is fixed once computed regardless of when or how often they're evaluated. Recording
the requirement here only so whoever builds percentage rollout doesn't have to rediscover it.

## 8. Explicitly out of scope

Unchanged from the original draft — still correct:

- Full OTA code replacement / remote binary updates
- AI feature implementation / Fino development itself
- Experiment analytics platform, A/B testing engine, customer segmentation
- Percentage rollout / user-group targeting — reserved as Phase 3 per the original draft, not
  designed here; needs its own proposal once flags + config + version management are live and there's
  a real feature to roll out gradually

**Also explicitly not needed:** rebuilding feature flags (§2), an environment-promotion pipeline
(§2), a message broker or workflow engine for config propagation — live-read from Postgres, same as
`platform_settings` already does, is sufficient at Finora's current scale and instance count.

## 9. Estimated effort

| Component | Estimate |
|---|---|
| ~~Feature flag backend~~ | Already built |
| `app_config` table + API + type validation | S–M |
| `app_versions` table + API + force-update confirmation/reason | S–M |
| Unified `/api/v1/app/config` endpoint | S |
| Feature flag admin UI (genuinely missing) | S–M |
| Config value admin UI | S–M |
| Version management admin UI | S |
| `default_when_missing` flag field | S |
| Mobile version-check integration | M |

Smaller in total than the original draft implied, because the highest-effort backend piece (feature
flags) is already done.

## 10. Approval request

Request approval to add this as a post-GA platform capability, scoped to §3 (config values + version
management + small flag additions) rather than the full feature-flag system the original draft
assumed needed building. Approval does not authorize immediate implementation.

**Decision requested:**
- ✅ Approve: Remote Configuration & Version Management as a roadmap item, scoped per §3
- ⏸ Defer: implementation until after GA stabilization (C-8, 56 findings, safety remediation)
- 🔲 Separate decision needed: audit old-value tracking (§5.3) — flagged as a system-wide convention
  question, not decided here
