# Account Aggregator Link Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a Premium user link a bank/card account via Setu's Account Aggregator API, all the
way through consent approval, identity resolution against their existing accounts, and a resolved
`ACTIVE` link — with no transaction sync yet (that's Plan 2).

**Architecture:** New `integrations/setu` package mirroring `integrations/google`'s shape: an
entity + repository for link state, a gateway interface isolating the Setu HTTP boundary (so
business logic is unit-testable without a real Setu sandbox), a consent service, a webhook
controller reusing the existing `WebhookEventService` idempotency ledger, and an identity
resolution service that reuses `ProductIdentityResolver` — the same `NEW`/`MATCHED`/`PROBABLE`
model already governing manual re-import — rather than inventing a second, less-safe matching
system.

**Tech Stack:** Spring Boot, JPA/Hibernate, PostgreSQL + Flyway, JUnit 5 + Mockito + AssertJ.

**Spec:** [docs/superpowers/specs/2026-09-12-account-aggregator-sync-design.md](../specs/2026-09-12-account-aggregator-sync-design.md)
— this plan implements the "Consent lifecycle," "Account identity resolution," "Link idempotency,"
and the entitlement-gating parts of "Architecture." Transaction sync, the Gmail interaction, the
outage hatch, and cost-control rate limiting are separate plans; see that spec's decomposition.

## Global Constraints

- Premium-only: every entry point checks `EntitlementService.hasEntitlement(userId,
  FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC)` before doing anything that costs money or creates
  state.
- No silent account auto-attachment except an exact `ProductIdentityResolver` `MATCHED` result —
  a `PROBABLE` result always requires explicit user confirmation. This is the single most important
  constraint in the whole plan; see spec §"Account identity resolution."
- Link creation is idempotent on a client-supplied key — two concurrent "link this account"
  requests for the same attempt must not create two Setu consent requests.
- All Setu HTTP calls go through the `SetuConsentGateway` interface, never a raw HTTP client
  called directly from a service — mirrors `RazorpaySubscriptionGateway`'s existing seam, so every
  task below is unit-testable with a hand-rolled fake, no real Setu sandbox required until
  integration testing.
- Every consent state change (`consent.created`, `consent.approved`, `consent.rejected`,
  `consent.revoked`, link failure) gets an `AuditService.record(...)` call — this is a regulated
  data-sharing feature, not optional logging.
- No AI-attribution trailer in any commit message (repository rule, `CLAUDE.md`).

---

## File Structure

```
backend/src/main/java/com/finora/entity/
  FeatureEntitlement.java        (modify — new ACCOUNT_AGGREGATOR_SYNC key)
  Account.java                   (modify — new primarySource field)

backend/src/main/java/com/finora/integrations/setu/
  AccountAggregatorLinkStatus.java   (new — enum)
  FiType.java                        (new — enum)
  AccountAggregatorLink.java         (new — entity)
  AccountAggregatorLinkRepository.java (new)
  SetuProperties.java                (new — config)
  SetuConsentInitiation.java         (new — record)
  SetuConsentDetail.java             (new — record)
  SetuConsentGateway.java            (new — interface, the Setu HTTP seam)
  SetuConsentService.java            (new — initiateLink)
  AccountAggregatorLinkController.java (new — REST API)
  AccountAggregatorWebhookController.java (new)
  AccountAggregatorWebhookDispatcher.java (new)
  AccountAggregatorIdentityResolutionService.java (new)
  AccountAggregatorLinkSweepService.java (new — stale-row TTL sweep)

backend/src/main/java/com/finora/imports/
  ImportService.java              (modify — block manual import into an ACTIVE AA-linked account)

backend/src/main/resources/db/migration/
  V195__seed_account_aggregator_sync_entitlement.sql (new)
  V196__account_primary_source.sql                   (new)
  V197__account_aggregator_links.sql                 (new)

backend/src/test/java/com/finora/integrations/setu/
  (one test class per new service/controller above)
backend/src/test/java/com/finora/imports/
  ImportServiceAccountAggregatorBlockTest.java (new, or added to an existing ImportService test)
```

One file, one responsibility: the gateway interface only talks to Setu's HTTP shape; the consent
service only decides link/entitlement/idempotency logic; the identity resolution service only
decides which `Account` a link belongs to; the webhook controller/dispatcher only receive and route
events. Each is independently testable without the others being real.

---

### Task 1: `ACCOUNT_AGGREGATOR_SYNC` feature entitlement

**Files:**
- Modify: `backend/src/main/java/com/finora/entity/FeatureEntitlement.java`
- Create: `backend/src/main/resources/db/migration/V195__seed_account_aggregator_sync_entitlement.sql`
- Test: `backend/src/test/java/com/finora/service/EntitlementServiceAccountAggregatorTest.java`

**Interfaces:**
- Produces: `FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC` (`String` constant), a seeded row granting
  it to the `PREMIUM` plan only. Every later task's entitlement check uses this constant.

- [ ] **Step 1: Write the failing test**

```java
package com.finora.service;

import com.finora.entity.FeatureEntitlement;
import com.finora.repository.FeatureEntitlementRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EntitlementServiceAccountAggregatorTest {

    @Test
    void constantMatchesTheSeededKey() {
        // The migration seeds the literal string 'ACCOUNT_AGGREGATOR_SYNC' -- this pins the Java
        // constant to that exact spelling so a typo in either place fails a test instead of
        // silently granting nobody the feature.
        assertThat(FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC).isEqualTo("ACCOUNT_AGGREGATOR_SYNC");
    }

    @Test
    void hasEntitlementIsFailClosedForAnUnrelatedFeatureKey() {
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        FeatureEntitlementRepository entitlements = mock(FeatureEntitlementRepository.class);
        PlanRepository plans = mock(PlanRepository.class);
        EntitlementService service = new EntitlementService(subscriptions, entitlements, plans);

        UUID userId = UUID.randomUUID();
        when(subscriptions.findActiveOrTrial(userId)).thenReturn(java.util.Optional.empty());

        assertThat(service.hasEntitlement(userId, FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC)).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=EntitlementServiceAccountAggregatorTest`
Expected: FAIL to compile — `FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC` does not exist yet.

- [ ] **Step 3: Add the constant and the seed migration**

In `FeatureEntitlement.java`, alongside the existing `GMAIL_SYNC` constant:

```java
    // V195. The Account Aggregator sync feature has the same real ongoing per-user cost shape as
    // Gmail Sync (a scheduled worker keeping a live external connection for as long as it stays
    // linked) -- see the design spec's "Cost control" section. Same Free-absent/Premium-enabled
    // seeding as GMAIL_SYNC above, not Plus: this is a live bank-data feed, a step further than
    // Gmail's receipt-email polling.
    public static final String ACCOUNT_AGGREGATOR_SYNC = "ACCOUNT_AGGREGATOR_SYNC";
```

`V195__seed_account_aggregator_sync_entitlement.sql`:

```sql
-- Account Aggregator bank/card sync moves behind Premium, same reasoning as V163's GMAIL_SYNC:
-- this is the one integration with a genuine ongoing per-user cost (Setu bills per data pull for
-- as long as a link stays active), unlike the rest of the app's free CRUD/in-process computation.
-- See docs/superpowers/specs/2026-09-12-account-aggregator-sync-design.md, "Cost control".
INSERT INTO feature_entitlements (plan_id, feature_key, enabled)
    SELECT id, 'ACCOUNT_AGGREGATOR_SYNC', true FROM plans WHERE code = 'PREMIUM';
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=EntitlementServiceAccountAggregatorTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/entity/FeatureEntitlement.java \
        backend/src/main/resources/db/migration/V195__seed_account_aggregator_sync_entitlement.sql \
        backend/src/test/java/com/finora/service/EntitlementServiceAccountAggregatorTest.java
git commit -m "feat(backend): add ACCOUNT_AGGREGATOR_SYNC premium entitlement"
```

---

### Task 2: `Account.primarySource`

**Files:**
- Modify: `backend/src/main/java/com/finora/entity/Account.java`
- Create: `backend/src/main/resources/db/migration/V196__account_primary_source.sql`
- Test: `backend/src/test/java/com/finora/entity/AccountPrimarySourceTest.java`

**Interfaces:**
- Produces: `Account.PrimarySource` enum (`MANUAL`, `ACCOUNT_AGGREGATOR`), `Account.getPrimarySource()`/
  `setPrimarySource(PrimarySource)`, default `MANUAL`. Later tasks (identity resolution, the
  manual-import block) read/write this field.

- [ ] **Step 1: Write the failing test**

```java
package com.finora.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountPrimarySourceTest {

    @Test
    void defaultsToManual() {
        Account account = new Account();
        assertThat(account.getPrimarySource()).isEqualTo(Account.PrimarySource.MANUAL);
    }

    @Test
    void canBeSetToAccountAggregator() {
        Account account = new Account();
        account.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        assertThat(account.getPrimarySource()).isEqualTo(Account.PrimarySource.ACCOUNT_AGGREGATOR);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AccountPrimarySourceTest`
Expected: FAIL to compile — no `PrimarySource` type on `Account`.

- [ ] **Step 3: Add the field**

In `Account.java`, add the enum near the existing `Type` enum, the field near `bankId`, and
accessors in the getter/setter block:

```java
    public enum PrimarySource { MANUAL, ACCOUNT_AGGREGATOR }
```

```java
    // Which system is the source of truth for this account's transactions going forward. MANUAL
    // (the default, and the only value before this column existed) means the user uploads
    // statements themselves. ACCOUNT_AGGREGATOR means a live AccountAggregatorLink owns this
    // account -- see AccountAggregatorIdentityResolutionService, which is the only writer that ever
    // sets this to ACCOUNT_AGGREGATOR, and ImportService.resolveTargetAccount, which reads it to
    // refuse a manual statement upload into an actively-synced account. Reverted to MANUAL whenever
    // that link stops being ACTIVE (paused, revoked, expired) -- see the design spec's "Consent
    // lifecycle" section -- so a lapsed Premium user is never left unable to import the account at
    // all.
    @Enumerated(EnumType.STRING)
    @Column(name = "primary_source", nullable = false, length = 20)
    private PrimarySource primarySource = PrimarySource.MANUAL;
```

```java
    public PrimarySource getPrimarySource() { return primarySource; }
    public void setPrimarySource(PrimarySource primarySource) { this.primarySource = primarySource; }
```

`V196__account_primary_source.sql`:

```sql
-- Which system owns this account's transactions going forward: the user's own manual upload
-- (default, unchanged behavior) or a live Account Aggregator link. See
-- docs/superpowers/specs/2026-09-12-account-aggregator-sync-design.md, "Data model".
ALTER TABLE accounts ADD COLUMN primary_source VARCHAR(20) NOT NULL DEFAULT 'MANUAL';
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=AccountPrimarySourceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/entity/Account.java \
        backend/src/main/resources/db/migration/V196__account_primary_source.sql \
        backend/src/test/java/com/finora/entity/AccountPrimarySourceTest.java
git commit -m "feat(accounts): add primarySource to distinguish manual vs AA-synced accounts"
```

---

### Task 3: `AccountAggregatorLink` entity + repository

**Files:**
- Create: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLinkStatus.java`
- Create: `backend/src/main/java/com/finora/integrations/setu/FiType.java`
- Create: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLink.java`
- Create: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLinkRepository.java`
- Create: `backend/src/main/resources/db/migration/V197__account_aggregator_links.sql`
- Test: `backend/src/test/java/com/finora/integrations/setu/AccountAggregatorLinkTest.java`

**Interfaces:**
- Produces:
  - `enum AccountAggregatorLinkStatus { CONSENT_PENDING, PENDING_ACCOUNT_CONFIRMATION, ACTIVE,
    PAUSED, REVOKED, EXPIRED, REJECTED, LINK_FAILED }`
  - `enum FiType { DEPOSIT, CREDIT_CARD }`
  - `AccountAggregatorLink` fields/accessors: `getId()`, `getUserId()/setUserId(UUID)`,
    `getAccountId()/setAccountId(UUID)` (nullable), `getConsentHandleId()/setConsentHandleId(String)`,
    `getFiType()/setFiType(FiType)`, `getStatus()/setStatus(AccountAggregatorLinkStatus)`,
    `getConsentExpiresAt()/setConsentExpiresAt(Instant)`, `getLastSyncedAt()/setLastSyncedAt(Instant)`,
    `getLinkIdempotencyKey()/setLinkIdempotencyKey(String)`, `getCreatedAt()`, `getUpdatedAt()`.
  - `AccountAggregatorLinkRepository`: `findByUserIdAndLinkIdempotencyKey(UUID, String)`,
    `findByConsentHandleId(String)`, `findByAccountIdAndStatus(UUID, AccountAggregatorLinkStatus)`,
    `findByStatusInAndCreatedAtBefore(List<AccountAggregatorLinkStatus>, Instant)`.

- [ ] **Step 1: Write the failing test**

```java
package com.finora.integrations.setu;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountAggregatorLinkTest {

    @Test
    void defaultsToConsentPendingWithNoAccountAttached() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(UUID.randomUUID());
        link.setFiType(FiType.DEPOSIT);
        link.setLinkIdempotencyKey("idem-key-1");

        assertThat(link.getStatus()).isEqualTo(AccountAggregatorLinkStatus.CONSENT_PENDING);
        assertThat(link.getAccountId()).isNull();
    }

    @Test
    void statusAndAccountCanBeSetIndependently() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        UUID accountId = UUID.randomUUID();

        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setAccountId(accountId);
        link.setConsentExpiresAt(Instant.parse("2027-01-01T00:00:00Z"));

        assertThat(link.getStatus()).isEqualTo(AccountAggregatorLinkStatus.ACTIVE);
        assertThat(link.getAccountId()).isEqualTo(accountId);
        assertThat(link.getConsentExpiresAt()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorLinkTest`
Expected: FAIL to compile — none of these types exist yet.

- [ ] **Step 3: Create the enums, entity, repository, and migration**

`AccountAggregatorLinkStatus.java`:

```java
package com.finora.integrations.setu;

/**
 * Where one linked FI account is in its life. See the design spec's "Consent lifecycle" section
 * for the full state diagram and every transition's trigger.
 */
public enum AccountAggregatorLinkStatus {
    /** Consent request created at Setu, waiting for the user to approve or decline it in their
     *  own AA app. */
    CONSENT_PENDING,
    /** The user approved consent, but AccountAggregatorIdentityResolutionService could only reach
     *  a PROBABLE match against an existing account -- never auto-attached, waiting for the user
     *  to pick "this existing account" or "a new one" via AccountAggregatorLinkController. */
    PENDING_ACCOUNT_CONFIRMATION,
    /** Usable: attached to an Account, syncing (once Plan 2 exists). */
    ACTIVE,
    /** Consent may still be valid at Setu, but the user's plan no longer grants
     *  ACCOUNT_AGGREGATOR_SYNC -- syncing stops without tearing down the consent. */
    PAUSED,
    /** The user revoked consent in their own AA app (out-of-band -- Fynora only observes this via
     *  webhook, it cannot force a revoke). Terminal. */
    REVOKED,
    /** The AA consent's own validity window elapsed. Terminal. */
    EXPIRED,
    /** The user declined consent inside their AA app. A normal, common outcome, not an error.
     *  Terminal. */
    REJECTED,
    /** Setu's consent-creation call itself failed, or CONSENT_PENDING/
     *  PENDING_ACCOUNT_CONFIRMATION sat unresolved past AccountAggregatorLinkSweepService's TTL.
     *  Terminal -- the user retries by starting a new link, not by recovering this row. */
    LINK_FAILED
}
```

`FiType.java`:

```java
package com.finora.integrations.setu;

/** Which kind of financial information this link covers. See the design spec's "Scope" section --
 *  CREDIT_CARD coverage across issuers is unverified and gated behind its own sandbox-validation
 *  task before it ships; DEPOSIT (savings/current) is the safer first path. */
public enum FiType { DEPOSIT, CREDIT_CARD }
```

`AccountAggregatorLink.java`:

```java
package com.finora.integrations.setu;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One user's link to one FI (bank/card) account through Setu's Account Aggregator API. Mirrors
 * {@code GmailConnection}'s shape deliberately -- connection lifecycle only, nothing here syncs or
 * ingests a single transaction (that's Plan 2's AccountAggregatorTransactionMapper).
 *
 * <p>Not extending {@code BaseEntity}: same reasoning as {@code GmailConnection} and
 * {@code ImportSession} -- this is connection/session state, not the soft-deleted, optimistically
 * locked financial data BaseEntity's four other users (Account, Transaction, Budget, Goal) are.
 */
@Entity
@Table(name = "account_aggregator_links")
public class AccountAggregatorLink {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Null until identity resolution attaches this link to an Account (see
     *  AccountAggregatorIdentityResolutionService) -- which may not happen in the same request as
     *  consent approval, if the match is only PROBABLE and needs the user's own confirmation. */
    @Column(name = "account_id")
    private UUID accountId;

    /** Setu's identifier for this consent. Null only for a LINK_FAILED row whose gateway call
     *  failed before Setu ever returned one. */
    @Column(name = "consent_handle_id")
    private String consentHandleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "fi_type", nullable = false, length = 20)
    private FiType fiType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AccountAggregatorLinkStatus status = AccountAggregatorLinkStatus.CONSENT_PENDING;

    @Column(name = "consent_expires_at")
    private Instant consentExpiresAt;

    /** Reserved for Plan 2 (transaction sync) -- always null until then. */
    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    /** Client-minted, unique per (user, attempt) -- see the design spec's "Link idempotency"
     *  section. Enforced by V197's unique index, not a select-then-insert check, same discipline
     *  {@code ReimportConfirmationClaim} already uses for the identical class of problem. */
    @Column(name = "link_idempotency_key", nullable = false)
    private String linkIdempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    private void touch() { this.updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; touch(); }
    public String getConsentHandleId() { return consentHandleId; }
    public void setConsentHandleId(String consentHandleId) { this.consentHandleId = consentHandleId; touch(); }
    public FiType getFiType() { return fiType; }
    public void setFiType(FiType fiType) { this.fiType = fiType; }
    public AccountAggregatorLinkStatus getStatus() { return status; }
    public void setStatus(AccountAggregatorLinkStatus status) { this.status = status; touch(); }
    public Instant getConsentExpiresAt() { return consentExpiresAt; }
    public void setConsentExpiresAt(Instant consentExpiresAt) { this.consentExpiresAt = consentExpiresAt; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; touch(); }
    public String getLinkIdempotencyKey() { return linkIdempotencyKey; }
    public void setLinkIdempotencyKey(String linkIdempotencyKey) { this.linkIdempotencyKey = linkIdempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

`AccountAggregatorLinkRepository.java`:

```java
package com.finora.integrations.setu;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountAggregatorLinkRepository extends JpaRepository<AccountAggregatorLink, UUID> {

    Optional<AccountAggregatorLink> findByUserIdAndLinkIdempotencyKey(UUID userId, String linkIdempotencyKey);

    Optional<AccountAggregatorLink> findByConsentHandleId(String consentHandleId);

    Optional<AccountAggregatorLink> findByAccountIdAndStatus(UUID accountId, AccountAggregatorLinkStatus status);

    /** For AccountAggregatorLinkSweepService's stale-row TTL check (Task 12) -- rows stuck in an
     *  in-progress status past a cutoff. */
    List<AccountAggregatorLink> findByStatusInAndCreatedAtBefore(
            List<AccountAggregatorLinkStatus> statuses, Instant cutoff);
}
```

`V197__account_aggregator_links.sql`:

```sql
-- One row per user's link to one FI account via Setu's Account Aggregator API. Connection
-- lifecycle only -- see docs/superpowers/specs/2026-09-12-account-aggregator-sync-design.md,
-- "Architecture" and "Consent lifecycle". No transaction data lives here (Plan 2 adds that).
CREATE TABLE account_aggregator_links (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID NOT NULL,
    account_id            UUID,
    consent_handle_id     VARCHAR(255),
    fi_type               VARCHAR(20) NOT NULL,
    status                VARCHAR(32) NOT NULL,
    consent_expires_at    TIMESTAMPTZ,
    last_synced_at        TIMESTAMPTZ,
    link_idempotency_key  VARCHAR(255) NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Link-creation idempotency: two concurrent requests for the same client-minted attempt must
-- resolve to the same row, not two Setu consent requests. Per-user, not global -- two different
-- users could legitimately mint the same key value by coincidence.
CREATE UNIQUE INDEX idx_aa_links_user_idempotency_key
    ON account_aggregator_links (user_id, link_idempotency_key);

CREATE INDEX idx_aa_links_consent_handle_id ON account_aggregator_links (consent_handle_id);
CREATE INDEX idx_aa_links_account_id_status ON account_aggregator_links (account_id, status);
CREATE INDEX idx_aa_links_status_created_at ON account_aggregator_links (status, created_at);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorLinkTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLinkStatus.java \
        backend/src/main/java/com/finora/integrations/setu/FiType.java \
        backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLink.java \
        backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLinkRepository.java \
        backend/src/main/resources/db/migration/V197__account_aggregator_links.sql \
        backend/src/test/java/com/finora/integrations/setu/AccountAggregatorLinkTest.java
git commit -m "feat(backend): add AccountAggregatorLink entity and repository"
```

---

### Task 4: `SetuConsentGateway` seam + config

**Files:**
- Create: `backend/src/main/java/com/finora/integrations/setu/SetuProperties.java`
- Create: `backend/src/main/java/com/finora/integrations/setu/SetuConsentInitiation.java`
- Create: `backend/src/main/java/com/finora/integrations/setu/SetuConsentDetail.java`
- Create: `backend/src/main/java/com/finora/integrations/setu/SetuConsentGateway.java`
- Test: `backend/src/test/java/com/finora/integrations/setu/SetuPropertiesTest.java`

**Interfaces:**
- Produces:
  - `SetuProperties` — `isConfigured()`, `getBaseUrl()/setBaseUrl`, `getClientId()/setClientId`,
    `getClientSecret()/setClientSecret`, `getWebhookSecret()/setWebhookSecret`.
  - `record SetuConsentInitiation(String consentHandleId, String redirectUrl)`.
  - `record SetuConsentDetail(String fipId, String ifscCode, String maskedAccountNumber,
    String fullAccountNumber, String accountHolderName)` — `fullAccountNumber` nullable; whether
    Setu's consent-detail response ever populates it is unverified against a real sandbox (flagged
    in the spec's open items), so `AccountAggregatorIdentityResolutionService` (Task 8) must treat
    it as optional.
  - `interface SetuConsentGateway` — `boolean isConfigured()`,
    `SetuConsentInitiation createConsent(String userReferenceId, FiType fiType)`,
    `SetuConsentDetail fetchConsentDetail(String consentHandleId)`. No implementation in this task
    — a real HTTP-backed implementation is a follow-up task once Setu sandbox credentials exist;
    every task in this plan uses a hand-rolled test double against this interface.

**No production implementation exists yet at the end of this task** — that is intentional. This
task only establishes the seam every later task programs against, the same way
`RazorpaySubscriptionGateway` lets `BillingCheckoutService` be unit-tested without a live Razorpay
account. A real `SetuConsentGatewayImpl` (HTTP client, request/response mapping against Setu's
actual API shape) is out of scope for this plan and needs Setu sandbox credentials to build
correctly — tracked as a named follow-up, not guessed here.

- [ ] **Step 1: Write the failing test**

```java
package com.finora.integrations.setu;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SetuPropertiesTest {

    @Test
    void notConfiguredWithoutCredentials() {
        SetuProperties properties = new SetuProperties();
        assertThat(properties.isConfigured()).isFalse();
    }

    @Test
    void configuredOnceAllThreeCredentialsArePresent() {
        SetuProperties properties = new SetuProperties();
        properties.setClientId("client-id");
        properties.setClientSecret("client-secret");
        properties.setWebhookSecret("webhook-secret");

        assertThat(properties.isConfigured()).isTrue();
    }

    @Test
    void notConfiguredWhenOnlySomeCredentialsArePresent() {
        SetuProperties properties = new SetuProperties();
        properties.setClientId("client-id");
        // clientSecret and webhookSecret left unset

        assertThat(properties.isConfigured()).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=SetuPropertiesTest`
Expected: FAIL to compile — `SetuProperties` doesn't exist.

- [ ] **Step 3: Create the config, DTOs, and gateway interface**

`SetuProperties.java`:

```java
package com.finora.integrations.setu;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** app.integrations.setu.* -- same isConfigured() shape as RazorpayProperties/GoogleOAuthProperties:
 *  computed from whether real credentials are present, not a separate boolean flag someone could
 *  forget to flip alongside the credentials themselves. */
@ConfigurationProperties(prefix = "app.integrations.setu")
public class SetuProperties {

    private String baseUrl;
    private String clientId;
    private String clientSecret;
    private String webhookSecret;

    public boolean isConfigured() {
        return notBlank(clientId) && notBlank(clientSecret) && notBlank(webhookSecret);
    }

    private static boolean notBlank(String value) { return value != null && !value.isBlank(); }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
}
```

`SetuConsentInitiation.java`:

```java
package com.finora.integrations.setu;

/** What starting a consent request returns: Setu's handle for it, and the URL to redirect the
 *  user to their own AA app. Neither is persisted as-is on AccountAggregatorLink except
 *  consentHandleId -- redirectUrl is single-use, returned straight to the caller. */
public record SetuConsentInitiation(String consentHandleId, String redirectUrl) {}
```

`SetuConsentDetail.java`:

```java
package com.finora.integrations.setu;

/** What Setu reports about a linked FI account once consent is approved -- enough to build a
 *  ProductIdentity and run it through the same resolution ProductIdentityResolver already applies
 *  to manual re-import. fullAccountNumber is nullable: whether Setu's consent-detail response ever
 *  carries it (as opposed to only the masked form) is unverified against a real sandbox -- see the
 *  design spec's open items. Callers must handle null. */
public record SetuConsentDetail(String fipId, String ifscCode, String maskedAccountNumber,
                                 String fullAccountNumber, String accountHolderName) {}
```

`SetuConsentGateway.java`:

```java
package com.finora.integrations.setu;

/**
 * The only Setu-facing seam AccountAggregatorLink's business logic depends on -- mirrors
 * RazorpaySubscriptionGateway's role for billing. Every caller (SetuConsentService,
 * AccountAggregatorIdentityResolutionService) programs against this interface, never against an
 * HTTP client directly, so both are unit-testable with a plain hand-rolled fake.
 */
public interface SetuConsentGateway {

    boolean isConfigured();

    /** @param userReferenceId Fynora's own userId, passed through as Setu's customer reference --
     *                         never a Setu-side identifier Fynora would have to store back. */
    SetuConsentInitiation createConsent(String userReferenceId, FiType fiType);

    /** Called once a webhook reports consent.approved, to learn which FIP/account was actually
     *  linked before running identity resolution. */
    SetuConsentDetail fetchConsentDetail(String consentHandleId);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=SetuPropertiesTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/integrations/setu/SetuProperties.java \
        backend/src/main/java/com/finora/integrations/setu/SetuConsentInitiation.java \
        backend/src/main/java/com/finora/integrations/setu/SetuConsentDetail.java \
        backend/src/main/java/com/finora/integrations/setu/SetuConsentGateway.java \
        backend/src/test/java/com/finora/integrations/setu/SetuPropertiesTest.java
git commit -m "feat(backend): add SetuConsentGateway seam and config"
```

---

### Task 5: `SetuConsentService.initiateLink`

**Files:**
- Create: `backend/src/main/java/com/finora/integrations/setu/SetuConsentService.java`
- Test: `backend/src/test/java/com/finora/integrations/setu/SetuConsentServiceTest.java`

**Interfaces:**
- Consumes: `AccountAggregatorLinkRepository` (Task 3), `SetuConsentGateway` (Task 4),
  `EntitlementService.hasEntitlement(UUID, String)` (existing), `FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC`
  (Task 1), `AuditService.record(UUID, String, String, UUID)` (existing).
- Produces: `SetuConsentService.initiateLink(UUID userId, FiType fiType, String idempotencyKey)` →
  `record InitiateLinkResult(AccountAggregatorLink link, String redirectUrl)` — `redirectUrl` is
  null when the returned link is a cached idempotent replay of an already-existing
  `CONSENT_PENDING` row (nothing new to redirect to; the caller already has one in flight).
  `AccountAggregatorLinkController` (Task 6) consumes this directly.

- [ ] **Step 1: Write the failing tests**

```java
package com.finora.integrations.setu;

import com.finora.entity.FeatureEntitlement;
import com.finora.exception.ApiException;
import com.finora.service.AuditService;
import com.finora.service.EntitlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SetuConsentServiceTest {

    private AccountAggregatorLinkRepository links;
    private SetuConsentGateway gateway;
    private EntitlementService entitlementService;
    private AuditService auditService;
    private SetuConsentService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        links = mock(AccountAggregatorLinkRepository.class);
        gateway = mock(SetuConsentGateway.class);
        entitlementService = mock(EntitlementService.class);
        auditService = mock(AuditService.class);
        service = new SetuConsentService(links, gateway, entitlementService, auditService);

        when(entitlementService.hasEntitlement(userId, FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC))
                .thenReturn(true);
        when(gateway.isConfigured()).thenReturn(true);
        when(links.findByUserIdAndLinkIdempotencyKey(userId, "idem-1")).thenReturn(Optional.empty());
        when(links.save(any(AccountAggregatorLink.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void refusesAUserWithoutTheEntitlement() {
        when(entitlementService.hasEntitlement(userId, FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC))
                .thenReturn(false);

        assertThatThrownBy(() -> service.initiateLink(userId, FiType.DEPOSIT, "idem-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);

        verifyNoInteractions(gateway);
    }

    @Test
    void createsAConsentPendingLinkAndAuditsIt() {
        when(gateway.createConsent(userId.toString(), FiType.DEPOSIT))
                .thenReturn(new SetuConsentInitiation("consent-handle-1", "https://aa.example/redirect"));

        SetuConsentService.InitiateLinkResult result = service.initiateLink(userId, FiType.DEPOSIT, "idem-1");

        assertThat(result.redirectUrl()).isEqualTo("https://aa.example/redirect");
        assertThat(result.link().getStatus()).isEqualTo(AccountAggregatorLinkStatus.CONSENT_PENDING);
        assertThat(result.link().getConsentHandleId()).isEqualTo("consent-handle-1");
        assertThat(result.link().getFiType()).isEqualTo(FiType.DEPOSIT);
        verify(auditService).record(eq(userId), eq("ACCOUNT_AGGREGATOR_CONSENT_CREATED"),
                eq("AccountAggregatorLink"), any());
    }

    @Test
    void isIdempotentOnARepeatedKey() {
        AccountAggregatorLink existing = new AccountAggregatorLink();
        existing.setUserId(userId);
        existing.setFiType(FiType.DEPOSIT);
        existing.setLinkIdempotencyKey("idem-1");
        existing.setConsentHandleId("consent-handle-1");
        when(links.findByUserIdAndLinkIdempotencyKey(userId, "idem-1")).thenReturn(Optional.of(existing));

        SetuConsentService.InitiateLinkResult result = service.initiateLink(userId, FiType.DEPOSIT, "idem-1");

        assertThat(result.link()).isSameAs(existing);
        assertThat(result.redirectUrl()).isNull();
        verifyNoInteractions(gateway);
    }

    @Test
    void marksTheLinkFailedWhenSetuRejectsTheRequest() {
        when(gateway.createConsent(userId.toString(), FiType.DEPOSIT))
                .thenThrow(new RuntimeException("Setu 500"));

        assertThatThrownBy(() -> service.initiateLink(userId, FiType.DEPOSIT, "idem-1"))
                .isInstanceOf(ApiException.class);

        ArgumentCaptorLikeCheck: {
            verify(links).save(argThat(link -> link.getStatus() == AccountAggregatorLinkStatus.LINK_FAILED));
        }
        verify(auditService).record(eq(userId), eq("ACCOUNT_AGGREGATOR_LINK_FAILED"),
                eq("AccountAggregatorLink"), any());
    }

    @Test
    void refusesWhenSetuItselfIsNotConfigured() {
        when(gateway.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.initiateLink(userId, FiType.DEPOSIT, "idem-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);

        verify(links, never()).save(any());
    }
}
```

(The `ArgumentCaptorLikeCheck:` label is a plain Java labeled block, not a JUnit feature — it exists
only so the reader's eye finds the assertion; remove it if your team's style forbids labeled blocks
and just keep the `verify(...)` line.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=SetuConsentServiceTest`
Expected: FAIL to compile — `SetuConsentService` doesn't exist.

- [ ] **Step 3: Implement `SetuConsentService`**

```java
package com.finora.integrations.setu;

import com.finora.entity.FeatureEntitlement;
import com.finora.exception.ApiException;
import com.finora.service.AuditService;
import com.finora.service.EntitlementService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class SetuConsentService {

    private final AccountAggregatorLinkRepository links;
    private final SetuConsentGateway gateway;
    private final EntitlementService entitlementService;
    private final AuditService auditService;

    public SetuConsentService(AccountAggregatorLinkRepository links, SetuConsentGateway gateway,
                               EntitlementService entitlementService, AuditService auditService) {
        this.links = links;
        this.gateway = gateway;
        this.entitlementService = entitlementService;
        this.auditService = auditService;
    }

    /** @param idempotencyKey client-minted, unique per (user, attempt) -- see
     *                        AccountAggregatorLink's own doc comment. */
    public InitiateLinkResult initiateLink(UUID userId, FiType fiType, String idempotencyKey) {
        if (!entitlementService.hasEntitlement(userId, FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC)) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Account Aggregator sync is a Premium feature.");
        }

        Optional<AccountAggregatorLink> existing =
                links.findByUserIdAndLinkIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            // A retried or double-submitted request for the SAME attempt -- return the row already
            // created, redirectUrl null because there is nothing new to redirect to (the caller
            // already holds one from the original response, or is retrying after losing it, in
            // which case they need to start a fresh attempt with a new key, not reuse a dead one).
            return new InitiateLinkResult(existing.get(), null);
        }

        if (!gateway.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Account Aggregator sync is not available right now.");
        }

        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(userId);
        link.setFiType(fiType);
        link.setLinkIdempotencyKey(idempotencyKey);

        SetuConsentInitiation initiation;
        try {
            initiation = gateway.createConsent(userId.toString(), fiType);
        } catch (RuntimeException e) {
            link.setStatus(AccountAggregatorLinkStatus.LINK_FAILED);
            links.save(link);
            auditService.record(userId, "ACCOUNT_AGGREGATOR_LINK_FAILED", "AccountAggregatorLink", link.getId());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not start linking your account. Please try again.");
        }

        link.setConsentHandleId(initiation.consentHandleId());
        link.setStatus(AccountAggregatorLinkStatus.CONSENT_PENDING);
        link = links.save(link);
        auditService.record(userId, "ACCOUNT_AGGREGATOR_CONSENT_CREATED", "AccountAggregatorLink", link.getId());

        return new InitiateLinkResult(link, initiation.redirectUrl());
    }

    public record InitiateLinkResult(AccountAggregatorLink link, String redirectUrl) {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=SetuConsentServiceTest`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/integrations/setu/SetuConsentService.java \
        backend/src/test/java/com/finora/integrations/setu/SetuConsentServiceTest.java
git commit -m "feat(backend): add SetuConsentService.initiateLink with idempotency and audit logging"
```

---

### Task 6: `AccountAggregatorLinkController` — initiate endpoint

**Files:**
- Create: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLinkController.java`
- Test: `backend/src/test/java/com/finora/integrations/setu/AccountAggregatorLinkControllerTest.java`

**Interfaces:**
- Consumes: `SetuConsentService.initiateLink(UUID, FiType, String)` (Task 5), `CurrentUser.id()`
  (existing).
- Produces: `POST /api/v1/integrations/setu/links` → `{ "linkId": "...", "status": "CONSENT_PENDING",
  "redirectUrl": "..." }` (or `redirectUrl: null` on an idempotent replay). Request body:
  `{ "fiType": "DEPOSIT" | "CREDIT_CARD", "idempotencyKey": "..." }`.

- [ ] **Step 1: Write the failing test**

```java
package com.finora.integrations.setu;

import com.finora.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountAggregatorLinkControllerTest {

    private SetuConsentService consentService;
    private CurrentUser currentUser;
    private AccountAggregatorLinkController controller;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        consentService = mock(SetuConsentService.class);
        currentUser = mock(CurrentUser.class);
        when(currentUser.id()).thenReturn(userId);
        controller = new AccountAggregatorLinkController(consentService, currentUser);
    }

    @Test
    void initiateReturnsTheLinkIdStatusAndRedirectUrl() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setStatus(AccountAggregatorLinkStatus.CONSENT_PENDING);
        SetuConsentService.InitiateLinkResult result =
                new SetuConsentService.InitiateLinkResult(link, "https://aa.example/redirect");
        when(consentService.initiateLink(userId, FiType.DEPOSIT, "idem-1")).thenReturn(result);

        AccountAggregatorLinkController.InitiateLinkRequest request =
                new AccountAggregatorLinkController.InitiateLinkRequest(FiType.DEPOSIT, "idem-1");
        AccountAggregatorLinkController.InitiateLinkResponse response = controller.initiate(request).getBody();

        assertThat(response.status()).isEqualTo(AccountAggregatorLinkStatus.CONSENT_PENDING);
        assertThat(response.redirectUrl()).isEqualTo("https://aa.example/redirect");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorLinkControllerTest`
Expected: FAIL to compile — `AccountAggregatorLinkController` doesn't exist.

- [ ] **Step 3: Implement the controller**

```java
package com.finora.integrations.setu;

import com.finora.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/integrations/setu/links")
public class AccountAggregatorLinkController {

    private final SetuConsentService consentService;
    private final CurrentUser currentUser;

    public AccountAggregatorLinkController(SetuConsentService consentService, CurrentUser currentUser) {
        this.consentService = consentService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public ResponseEntity<InitiateLinkResponse> initiate(@RequestBody InitiateLinkRequest request) {
        SetuConsentService.InitiateLinkResult result =
                consentService.initiateLink(currentUser.id(), request.fiType(), request.idempotencyKey());
        return ResponseEntity.ok(new InitiateLinkResponse(
                result.link().getId(), result.link().getStatus(), result.redirectUrl()));
    }

    public record InitiateLinkRequest(FiType fiType, String idempotencyKey) {}

    public record InitiateLinkResponse(java.util.UUID linkId, AccountAggregatorLinkStatus status,
                                        String redirectUrl) {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorLinkControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLinkController.java \
        backend/src/test/java/com/finora/integrations/setu/AccountAggregatorLinkControllerTest.java
git commit -m "feat(backend): add link-initiation REST endpoint"
```

---

### Task 7: Webhook receipt — `consent.rejected` / `consent.revoked`

**Files:**
- Create: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorWebhookController.java`
- Create: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorWebhookDispatcher.java`
- Test: `backend/src/test/java/com/finora/integrations/setu/AccountAggregatorWebhookDispatcherTest.java`
- Test: `backend/src/test/java/com/finora/integrations/setu/AccountAggregatorWebhookControllerTest.java`

**Interfaces:**
- Consumes: `AccountAggregatorLinkRepository.findByConsentHandleId(String)` (Task 3),
  `WebhookEventService.claim/markProcessed/markFailed` (existing, reused verbatim), `SetuProperties`
  (Task 4), `AuditService.record` (existing).
- Produces: `AccountAggregatorWebhookDispatcher.dispatch(String eventType, String consentHandleId)`
  — handles `consent.rejected` and `consent.revoked` in this task; `consent.approved` is deferred to
  Task 8, which extends this same dispatcher's `switch`.

**On signature verification:** Setu's actual webhook-signing scheme (header name, HMAC algorithm)
is unverified against real sandbox docs — flagged explicitly rather than guessed. This task
isolates that uncertainty behind one method, `verifySignature(String rawBody, String signatureHeader)`,
implemented as a placeholder-free HMAC-SHA256-over-raw-body check (the most common webhook-signing
convention, and what Razorpay itself uses via `Utils.verifyWebhookSignature`) — correct enough to
build and test against now, with a note to re-verify the exact header name and algorithm once real
Setu webhook documentation or sandbox deliveries are available.

- [ ] **Step 1: Write the failing dispatcher test**

```java
package com.finora.integrations.setu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountAggregatorWebhookDispatcherTest {

    private AccountAggregatorLinkRepository links;
    private com.finora.service.AuditService auditService;
    private AccountAggregatorWebhookDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        links = mock(AccountAggregatorLinkRepository.class);
        auditService = mock(com.finora.service.AuditService.class);
        dispatcher = new AccountAggregatorWebhookDispatcher(links, auditService, null);
        // The third constructor argument (identity resolution, Task 8) is null here because
        // neither test in this task exercises the consent.approved branch -- Task 8 replaces this
        // constructor call with a real mock once that branch exists.
    }

    @Test
    void consentRejectedMarksTheLinkRejected() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(UUID.randomUUID());
        link.setStatus(AccountAggregatorLinkStatus.CONSENT_PENDING);
        when(links.findByConsentHandleId("consent-handle-1")).thenReturn(Optional.of(link));

        dispatcher.dispatch("consent.rejected", "consent-handle-1");

        assertThat(link.getStatus()).isEqualTo(AccountAggregatorLinkStatus.REJECTED);
    }

    @Test
    void consentRevokedMarksTheLinkRevoked() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(UUID.randomUUID());
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        when(links.findByConsentHandleId("consent-handle-2")).thenReturn(Optional.of(link));

        dispatcher.dispatch("consent.revoked", "consent-handle-2");

        assertThat(link.getStatus()).isEqualTo(AccountAggregatorLinkStatus.REVOKED);
    }

    @Test
    void anUnknownConsentHandleIsIgnoredNotThrown() {
        when(links.findByConsentHandleId("unknown")).thenReturn(Optional.empty());

        dispatcher.dispatch("consent.revoked", "unknown");
        // No exception -- a webhook for a consent handle Fynora never recorded (or already
        // deleted) is logged and dropped, not a 500 that makes Setu retry-storm forever.
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorWebhookDispatcherTest`
Expected: FAIL to compile.

- [ ] **Step 3: Implement the dispatcher (consent.rejected / consent.revoked only)**

```java
package com.finora.integrations.setu;

import com.finora.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * One method per Setu event type this application acts on -- named and shaped after
 * RazorpayWebhookDispatcher deliberately. consent.approved is handled by Task 8, which adds the
 * AccountAggregatorIdentityResolutionService dependency and its switch branch; this task only
 * wires the two simpler terminal-state transitions.
 */
@Component
public class AccountAggregatorWebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AccountAggregatorWebhookDispatcher.class);

    private final AccountAggregatorLinkRepository links;
    private final AuditService auditService;
    private final AccountAggregatorIdentityResolutionService identityResolutionService;

    public AccountAggregatorWebhookDispatcher(AccountAggregatorLinkRepository links, AuditService auditService,
                                               AccountAggregatorIdentityResolutionService identityResolutionService) {
        this.links = links;
        this.auditService = auditService;
        this.identityResolutionService = identityResolutionService;
    }

    public void dispatch(String eventType, String consentHandleId) {
        Optional<AccountAggregatorLink> maybeLink = links.findByConsentHandleId(consentHandleId);
        if (maybeLink.isEmpty()) {
            log.info("Setu webhook {} for unknown consent handle {}, ignoring.", eventType, consentHandleId);
            return;
        }
        AccountAggregatorLink link = maybeLink.get();

        switch (eventType) {
            case "consent.rejected" -> {
                link.setStatus(AccountAggregatorLinkStatus.REJECTED);
                auditService.record(link.getUserId(), "ACCOUNT_AGGREGATOR_CONSENT_REJECTED",
                        "AccountAggregatorLink", link.getId());
            }
            case "consent.revoked" -> {
                link.setStatus(AccountAggregatorLinkStatus.REVOKED);
                auditService.record(link.getUserId(), "ACCOUNT_AGGREGATOR_CONSENT_REVOKED",
                        "AccountAggregatorLink", link.getId());
                // Reverting the linked Account's primarySource back to MANUAL is Task 9's job (it
                // needs AccountRepository, which this dispatcher deliberately doesn't depend on --
                // see that task for why the revert lives in the identity resolution service
                // instead of here).
            }
            case "consent.approved" -> identityResolutionService.resolveAndAttach(link);
            default -> log.info("Unhandled Setu webhook event type {}, ignoring.", eventType);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorWebhookDispatcherTest`
Expected: PASS

- [ ] **Step 5: Write the failing controller test**

```java
package com.finora.integrations.setu;

import com.finora.service.WebhookEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AccountAggregatorWebhookControllerTest {

    private static final String SECRET = "test-webhook-secret";

    private SetuProperties properties;
    private WebhookEventService webhookEventService;
    private AccountAggregatorWebhookDispatcher dispatcher;
    private AccountAggregatorWebhookController controller;

    @BeforeEach
    void setUp() {
        properties = new SetuProperties();
        properties.setClientId("client-id");
        properties.setClientSecret("client-secret");
        properties.setWebhookSecret(SECRET);

        webhookEventService = mock(WebhookEventService.class);
        dispatcher = mock(AccountAggregatorWebhookDispatcher.class);
        controller = new AccountAggregatorWebhookController(properties, webhookEventService, dispatcher);

        when(webhookEventService.claim(any(), any(), any(), any())).thenReturn(true);
    }

    @Test
    void rejectsAnInvalidSignature() {
        String body = "{\"event\":\"consent.revoked\",\"consentHandleId\":\"consent-1\"}";
        ResponseEntity<Void> response = controller.receive("not-the-real-signature", "event-1", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(dispatcher);
    }

    @Test
    void acceptsAValidSignatureAndDispatches() throws Exception {
        String body = "{\"event\":\"consent.revoked\",\"consentHandleId\":\"consent-1\"}";
        String signature = hmacSha256Hex(body, SECRET);

        ResponseEntity<Void> response = controller.receive(signature, "event-1", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(dispatcher).dispatch("consent.revoked", "consent-1");
        verify(webhookEventService).markProcessed("event-1");
    }

    @Test
    void duplicateEventIdIsNotDispatchedTwice() throws Exception {
        String body = "{\"event\":\"consent.revoked\",\"consentHandleId\":\"consent-1\"}";
        String signature = hmacSha256Hex(body, SECRET);
        when(webhookEventService.claim("event-1", "SETU", "consent.revoked", java.util.Map.of(
                "event", "consent.revoked", "consentHandleId", "consent-1"))).thenReturn(false);

        ResponseEntity<Void> response = controller.receive(signature, "event-1", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(dispatcher);
    }

    private static String hmacSha256Hex(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] out = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(out);
    }
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorWebhookControllerTest`
Expected: FAIL to compile — `AccountAggregatorWebhookController` doesn't exist.

- [ ] **Step 7: Implement the webhook controller**

```java
package com.finora.integrations.setu;

import com.finora.service.WebhookEventService;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Unauthenticated by necessity -- Setu calls this directly, carrying no Finora session -- same
 * posture as RazorpayWebhookController. What replaces authentication is the signature header,
 * verified before anything else runs.
 *
 * <p>Signature scheme (HMAC-SHA256 over the raw body, hex-encoded, header name
 * X-Setu-Signature) is the most common webhook-signing convention and matches what this codebase
 * already does for Razorpay -- but is UNVERIFIED against real Setu documentation. Revisit the
 * header name and algorithm once real Setu sandbox webhook deliveries are available; this is
 * flagged, not guessed silently.
 */
@RestController
@RequestMapping("/api/v1/webhooks/setu")
public class AccountAggregatorWebhookController {

    private static final Logger log = LoggerFactory.getLogger(AccountAggregatorWebhookController.class);

    private final SetuProperties properties;
    private final WebhookEventService webhookEventService;
    private final AccountAggregatorWebhookDispatcher dispatcher;

    public AccountAggregatorWebhookController(SetuProperties properties, WebhookEventService webhookEventService,
                                               AccountAggregatorWebhookDispatcher dispatcher) {
        this.properties = properties;
        this.webhookEventService = webhookEventService;
        this.dispatcher = dispatcher;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestHeader("X-Setu-Signature") String signature,
                                         @RequestHeader(value = "X-Setu-Event-Id", required = false) String eventId,
                                         @RequestBody String rawBody) {
        if (!properties.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (!verifySignature(rawBody, signature)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        JSONObject json = new JSONObject(rawBody);
        String eventType = json.optString("event", "unknown");
        String consentHandleId = json.optString("consentHandleId", null);
        Map<String, Object> fullBody = json.toMap();

        if (eventId == null) {
            dispatcher.dispatch(eventType, consentHandleId);
            return ResponseEntity.ok().build();
        }

        if (!webhookEventService.claim(eventId, "SETU", eventType, fullBody)) {
            log.info("Duplicate Setu webhook event {} ({}), ignoring.", eventId, eventType);
            return ResponseEntity.ok().build();
        }

        try {
            dispatcher.dispatch(eventType, consentHandleId);
            webhookEventService.markProcessed(eventId);
        } catch (RuntimeException e) {
            webhookEventService.markFailed(eventId);
            log.error("Failed to process Setu webhook event {} ({}).", eventId, eventType, e);
            throw e;
        }
        return ResponseEntity.ok().build();
    }

    private boolean verifySignature(String rawBody, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getWebhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            String expectedHex = HexFormat.of().formatHex(expected);
            return expectedHex.equalsIgnoreCase(signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Unable to verify Setu webhook signature.", e);
            return false;
        }
    }
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorWebhookControllerTest`
Expected: PASS (3 tests)

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/finora/integrations/setu/AccountAggregatorWebhookController.java \
        backend/src/main/java/com/finora/integrations/setu/AccountAggregatorWebhookDispatcher.java \
        backend/src/test/java/com/finora/integrations/setu/AccountAggregatorWebhookControllerTest.java \
        backend/src/test/java/com/finora/integrations/setu/AccountAggregatorWebhookDispatcherTest.java
git commit -m "feat(backend): add Setu webhook receipt for consent.rejected/consent.revoked"
```

---

### Task 8: Identity resolution on `consent.approved`

**Files:**
- Create: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorIdentityResolutionService.java`
- Test: `backend/src/test/java/com/finora/integrations/setu/AccountAggregatorIdentityResolutionServiceTest.java`
- Modify: `backend/src/test/java/com/finora/integrations/setu/AccountAggregatorWebhookDispatcherTest.java`
  (replace the `null` identity-resolution argument with a real mock, add a `consent.approved` test)

**Interfaces:**
- Consumes: `SetuConsentGateway.fetchConsentDetail(String)` (Task 4), `AccountRepository.findByUserId(UUID)`
  (existing), `ProductIdentityResolver.resolve(UUID, ProductIdentity)` returning
  `ProductIdentityResolver.ProductMatch` with `.resolution()`/`.account()`/`.mayImportWithoutAsking()`
  (existing, unmodified), `com.finora.util.BankRegistry.detect(String, List<String>)` (existing),
  `Account.setPrimarySource(Account.PrimarySource)` (Task 2), `AccountAggregatorLinkRepository.save`
  (Task 3).
- Produces: `AccountAggregatorIdentityResolutionService.resolveAndAttach(AccountAggregatorLink link)`
  — mutates the link's status to `ACTIVE` (on `MATCHED` or `NEW`) or `PENDING_ACCOUNT_CONFIRMATION`
  (on `PROBABLE`, leaving `accountId` unset). Consumed by
  `AccountAggregatorWebhookDispatcher` (Task 7) and by Task 9's confirmation endpoints.

**On the `MATCHED` vs `PROBABLE` split in practice:** whether a real AA-linked account reaches
`MATCHED` depends on whether `SetuConsentDetail.fullAccountNumber()` is populated (unverified, see
Task 4) *and* whether the existing manually-imported `Account` also has a real `productIdentityHash`
on file (which itself depends on whether that statement's own extraction ever saw a full,
unmasked number — genuinely uncertain, not something to assert either way here). This service must
handle both outcomes correctly; it must not assume one is more common than the other.

- [ ] **Step 1: Write the failing test**

```java
package com.finora.integrations.setu;

import com.finora.accounts.AccountDto;
import com.finora.accounts.AccountService;
import com.finora.entity.Account;
import com.finora.imports.product.ProductIdentityResolver;
import com.finora.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AccountAggregatorIdentityResolutionServiceTest {

    private SetuConsentGateway gateway;
    private AccountRepository accountRepository;
    private AccountService accountService;
    private ProductIdentityResolver productIdentityResolver;
    private AccountAggregatorLinkRepository links;
    private AccountAggregatorIdentityResolutionService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        gateway = mock(SetuConsentGateway.class);
        accountRepository = mock(AccountRepository.class);
        accountService = mock(AccountService.class);
        productIdentityResolver = mock(ProductIdentityResolver.class);
        links = mock(AccountAggregatorLinkRepository.class);
        service = new AccountAggregatorIdentityResolutionService(
                gateway, accountRepository, accountService, productIdentityResolver, links);

        when(links.save(any(AccountAggregatorLink.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AccountAggregatorLink pendingLink() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(userId);
        link.setFiType(FiType.DEPOSIT);
        link.setConsentHandleId("consent-handle-1");
        link.setStatus(AccountAggregatorLinkStatus.CONSENT_PENDING);
        return link;
    }

    @Test
    void exactMatchAttachesSilentlyAndActivatesTheLink() {
        AccountAggregatorLink link = pendingLink();
        when(gateway.fetchConsentDetail("consent-handle-1")).thenReturn(
                new SetuConsentDetail("HDFC", "HDFC0XXXXXX", "XXXX1234", "ACCTNUM0001234", "JOHN DOE"));

        Account existingAccount = new Account();
        existingAccount.setUserId(userId);
        ReflectionTestUtils.setField(existingAccount, "id", UUID.randomUUID());
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(existingAccount));

        ProductIdentityResolver.ProductMatch matched = new ProductIdentityResolver.ProductMatch(
                ProductIdentityResolver.Resolution.MATCHED, existingAccount, List.of(existingAccount), "exact match");
        when(productIdentityResolver.resolve(eq(userId), any())).thenReturn(matched);

        service.resolveAndAttach(link);

        assertThat(link.getStatus()).isEqualTo(AccountAggregatorLinkStatus.ACTIVE);
        assertThat(link.getAccountId()).isEqualTo(existingAccount.getId());
        assertThat(existingAccount.getPrimarySource()).isEqualTo(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        verify(accountService, never()).create(any(), any(), any());
    }

    @Test
    void probableMatchNeverAutoAttachesAndWaitsForConfirmation() {
        AccountAggregatorLink link = pendingLink();
        when(gateway.fetchConsentDetail("consent-handle-1")).thenReturn(
                new SetuConsentDetail("HDFC", "HDFC0XXXXXX", "XXXX1234", null, null));

        Account candidate = new Account();
        candidate.setUserId(userId);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(candidate));

        ProductIdentityResolver.ProductMatch probable = new ProductIdentityResolver.ProductMatch(
                ProductIdentityResolver.Resolution.PROBABLE, candidate, List.of(candidate), "probable match");
        when(productIdentityResolver.resolve(eq(userId), any())).thenReturn(probable);

        service.resolveAndAttach(link);

        assertThat(link.getStatus()).isEqualTo(AccountAggregatorLinkStatus.PENDING_ACCOUNT_CONFIRMATION);
        assertThat(link.getAccountId()).isNull();
        assertThat(candidate.getPrimarySource()).isEqualTo(Account.PrimarySource.MANUAL);
        verify(accountService, never()).create(any(), any(), any());
    }

    @Test
    void newProductCreatesAnAccountAndActivatesTheLink() {
        AccountAggregatorLink link = pendingLink();
        when(gateway.fetchConsentDetail("consent-handle-1")).thenReturn(
                new SetuConsentDetail("HDFC", "HDFC0XXXXXX", "XXXX9999", "ACCTNUM0009999", "JANE ROE"));
        when(accountRepository.findByUserId(userId)).thenReturn(List.of());

        ProductIdentityResolver.ProductMatch none = new ProductIdentityResolver.ProductMatch(
                ProductIdentityResolver.Resolution.NEW, null, List.of(), "no existing product matches");
        when(productIdentityResolver.resolve(eq(userId), any())).thenReturn(none);

        UUID newAccountId = UUID.randomUUID();
        AccountDto created = mock(AccountDto.class);
        when(created.id()).thenReturn(newAccountId);
        when(accountService.create(eq(userId), any(AccountDto.CreateRequest.class), eq(userId))).thenReturn(created);

        Account persisted = new Account();
        persisted.setUserId(userId);
        ReflectionTestUtils.setField(persisted, "id", newAccountId);
        when(accountRepository.findById(newAccountId)).thenReturn(java.util.Optional.of(persisted));

        service.resolveAndAttach(link);

        assertThat(link.getStatus()).isEqualTo(AccountAggregatorLinkStatus.ACTIVE);
        assertThat(link.getAccountId()).isEqualTo(newAccountId);
        assertThat(persisted.getPrimarySource()).isEqualTo(Account.PrimarySource.ACCOUNT_AGGREGATOR);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorIdentityResolutionServiceTest`
Expected: FAIL to compile.

- [ ] **Step 3: Implement `AccountAggregatorIdentityResolutionService`**

```java
package com.finora.integrations.setu;

import com.finora.accounts.AccountDto;
import com.finora.accounts.AccountService;
import com.finora.entity.Account;
import com.finora.imports.product.FinancialProductType;
import com.finora.imports.product.ProductIdentity;
import com.finora.imports.product.ProductIdentityResolver;
import com.finora.repository.AccountRepository;
import com.finora.util.BankRegistry;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Decides which Account (if any) a newly-approved AccountAggregatorLink belongs to. Reuses
 * ProductIdentityResolver -- the same NEW/MATCHED/PROBABLE model already governing manual
 * re-import -- rather than a separate, less-safe matching system. See the design spec's "Account
 * identity resolution" section for why silent attachment on anything less than an exact match was
 * rejected.
 */
@Service
public class AccountAggregatorIdentityResolutionService {

    private final SetuConsentGateway gateway;
    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final ProductIdentityResolver productIdentityResolver;
    private final AccountAggregatorLinkRepository links;

    public AccountAggregatorIdentityResolutionService(SetuConsentGateway gateway, AccountRepository accountRepository,
                                                        AccountService accountService,
                                                        ProductIdentityResolver productIdentityResolver,
                                                        AccountAggregatorLinkRepository links) {
        this.gateway = gateway;
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.productIdentityResolver = productIdentityResolver;
        this.links = links;
    }

    public void resolveAndAttach(AccountAggregatorLink link) {
        SetuConsentDetail detail = gateway.fetchConsentDetail(link.getConsentHandleId());

        // IFSC is passed as a labelled hint, reusing BankRegistry's own "Signal 1: the account's
        // own, labelled IFSC" detection path (see BankRegistry.detect) rather than adding a second,
        // Setu-specific bank-id mapping table.
        String bankId = BankRegistry.detect("account-aggregator",
                List.of("IFSC " + detail.ifscCode())).id();

        FinancialProductType type = link.getFiType() == FiType.CREDIT_CARD
                ? FinancialProductType.CREDIT_CARD : FinancialProductType.SAVINGS;

        ProductIdentity discovered = (detail.fullAccountNumber() != null
                ? ProductIdentity.of(bankId, type, detail.fullAccountNumber(), detail.maskedAccountNumber())
                : ProductIdentity.stored(bankId, type, null, detail.maskedAccountNumber()))
                .withWeakSignals(detail.ifscCode(), detail.accountHolderName());

        ProductIdentityResolver.ProductMatch match =
                productIdentityResolver.resolve(link.getUserId(), discovered);

        switch (match.resolution()) {
            case MATCHED -> attach(link, match.account());
            case NEW -> attach(link, createAccount(link, detail, bankId));
            case PROBABLE -> {
                // Never auto-attached -- see this class's own doc comment. The candidate(s) stay
                // available via match.candidates() for the confirmation endpoint (Task 9) to offer;
                // this method's job ends at surfacing that a decision is needed.
                link.setStatus(AccountAggregatorLinkStatus.PENDING_ACCOUNT_CONFIRMATION);
                links.save(link);
            }
        }
    }

    /** Shared by the MATCHED and NEW branches above, and by Task 9's confirm-existing-account
     *  endpoint (a user-confirmed PROBABLE match is handled identically to an automatic MATCHED
     *  one once the account is settled). */
    void attach(AccountAggregatorLink link, Account account) {
        account.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        accountRepository.save(account);
        link.setAccountId(account.getId());
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        links.save(link);
    }

    private Account createAccount(AccountAggregatorLink link, SetuConsentDetail detail, String bankId) {
        String accountType = link.getFiType() == FiType.CREDIT_CARD ? "CREDIT_CARD" : "SAVINGS";
        AccountDto created = accountService.create(link.getUserId(), new AccountDto.CreateRequest(
                bankNameOr(bankId, "Bank"), accountType, java.math.BigDecimal.ZERO, null, null,
                null, detail.accountHolderName(), detail.maskedAccountNumber(), bankId,
                null, detail.ifscCode(),
                null, null, null, null, null, null, null), link.getUserId());
        return accountRepository.findById(created.id())
                .orElseThrow(() -> new IllegalStateException("Just-created account not found: " + created.id()));
    }

    private static String bankNameOr(String bankId, String fallback) {
        BankRegistry.BankInfo info = BankRegistry.get(bankId);
        return info != null ? info.shortName() : fallback;
    }
}
```

*(`BankRegistry.BankInfo.shortName()` and `AccountDto.CreateRequest`'s exact field order are read
from the existing codebase — if `BankInfo`'s accessor is named differently, or `CreateRequest`'s
constructor order has changed since this plan was written, adjust this one method to match; nothing
else in this task depends on the exact naming.)*

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorIdentityResolutionServiceTest`
Expected: PASS (3 tests)

- [ ] **Step 5: Wire it into the webhook dispatcher and update its test**

In `AccountAggregatorWebhookDispatcherTest`, replace the `null` third constructor argument:

```java
    @BeforeEach
    void setUp() {
        links = mock(AccountAggregatorLinkRepository.class);
        auditService = mock(com.finora.service.AuditService.class);
        identityResolutionService = mock(AccountAggregatorIdentityResolutionService.class);
        dispatcher = new AccountAggregatorWebhookDispatcher(links, auditService, identityResolutionService);
    }
```

(add the `identityResolutionService` field alongside `links`/`auditService`), and add:

```java
    @Test
    void consentApprovedDelegatesToIdentityResolution() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(UUID.randomUUID());
        when(links.findByConsentHandleId("consent-handle-3")).thenReturn(Optional.of(link));

        dispatcher.dispatch("consent.approved", "consent-handle-3");

        verify(identityResolutionService).resolveAndAttach(link);
    }
```

- [ ] **Step 6: Run the full webhook dispatcher test suite**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorWebhookDispatcherTest`
Expected: PASS (4 tests)

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/integrations/setu/AccountAggregatorIdentityResolutionService.java \
        backend/src/test/java/com/finora/integrations/setu/AccountAggregatorIdentityResolutionServiceTest.java \
        backend/src/test/java/com/finora/integrations/setu/AccountAggregatorWebhookDispatcherTest.java
git commit -m "feat(backend): resolve account identity on consent.approved via ProductIdentityResolver"
```

---

### Task 9: Confirmation endpoints for a `PROBABLE` match

**Files:**
- Modify: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLinkController.java`
- Modify: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorIdentityResolutionService.java`
  (expose the two confirmation actions)
- Test: `backend/src/test/java/com/finora/integrations/setu/AccountAggregatorLinkControllerTest.java`
  (extend)
- Test: `backend/src/test/java/com/finora/integrations/setu/AccountAggregatorIdentityResolutionServiceTest.java`
  (extend)

**Interfaces:**
- Consumes: `com.finora.security.OwnershipGuard.requireOwned(Optional<Account>, Function<Account,UUID>,
  UUID, String)` (existing — used to verify the user actually owns the account they're confirming
  into), `AccountRepository.findById(UUID)` (existing).
- Produces: `AccountAggregatorIdentityResolutionService.confirmExistingAccount(AccountAggregatorLink
  link, UUID chosenAccountId)`, `.confirmNewAccount(AccountAggregatorLink link, SetuConsentDetail
  detail, String bankId)`; `POST /api/v1/integrations/setu/links/{linkId}/confirm-existing-account`
  and `POST /api/v1/integrations/setu/links/{linkId}/confirm-new-account`.

- [ ] **Step 1: Write the failing service tests**

Add to `AccountAggregatorIdentityResolutionServiceTest`:

```java
    @Test
    void confirmExistingAccountAttachesTheChosenAccountAndActivatesTheLink() {
        AccountAggregatorLink link = pendingLink();
        link.setStatus(AccountAggregatorLinkStatus.PENDING_ACCOUNT_CONFIRMATION);

        Account chosen = new Account();
        chosen.setUserId(userId);
        UUID chosenId = UUID.randomUUID();
        when(accountRepository.findById(chosenId)).thenReturn(java.util.Optional.of(chosen));

        service.confirmExistingAccount(link, chosenId);

        assertThat(link.getStatus()).isEqualTo(AccountAggregatorLinkStatus.ACTIVE);
        assertThat(chosen.getPrimarySource()).isEqualTo(Account.PrimarySource.ACCOUNT_AGGREGATOR);
    }

    @Test
    void confirmNewAccountCreatesOneEvenThoughAMatchWasProbable() {
        AccountAggregatorLink link = pendingLink();
        link.setStatus(AccountAggregatorLinkStatus.PENDING_ACCOUNT_CONFIRMATION);
        SetuConsentDetail detail = new SetuConsentDetail("HDFC", "HDFC0XXXXXX", "XXXX1234", null, "JOHN DOE");

        UUID newAccountId = UUID.randomUUID();
        AccountDto created = mock(AccountDto.class);
        when(created.id()).thenReturn(newAccountId);
        when(accountService.create(eq(userId), any(AccountDto.CreateRequest.class), eq(userId))).thenReturn(created);
        Account persisted = new Account();
        persisted.setUserId(userId);
        when(accountRepository.findById(newAccountId)).thenReturn(java.util.Optional.of(persisted));

        service.confirmNewAccount(link, detail, "HDFC");

        assertThat(link.getStatus()).isEqualTo(AccountAggregatorLinkStatus.ACTIVE);
        assertThat(persisted.getPrimarySource()).isEqualTo(Account.PrimarySource.ACCOUNT_AGGREGATOR);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorIdentityResolutionServiceTest`
Expected: FAIL to compile — `confirmExistingAccount`/`confirmNewAccount` don't exist yet.

- [ ] **Step 3: Add the two methods to the service**

```java
    /** Task 9. The user, shown match.candidates() from a PROBABLE resolution, picked one. Handled
     *  identically to an automatic MATCHED attach once the account is settled. */
    public void confirmExistingAccount(AccountAggregatorLink link, UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new com.finora.exception.ApiException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Account not found."));
        attach(link, account);
    }

    /** The user, shown a PROBABLE match, said "no, this is a different/new account." */
    public void confirmNewAccount(AccountAggregatorLink link, SetuConsentDetail detail, String bankId) {
        attach(link, createAccount(link, detail, bankId));
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorIdentityResolutionServiceTest`
Expected: PASS (5 tests)

- [ ] **Step 5: Write the failing controller tests**

Add to `AccountAggregatorLinkControllerTest`:

```java
    @Test
    void confirmExistingAccountRejectsAnAccountTheUserDoesNotOwn() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountRepository accounts = mock(AccountRepository.class);
        AccountAggregatorIdentityResolutionService identityResolutionService =
                mock(AccountAggregatorIdentityResolutionService.class);
        AccountAggregatorLinkController controllerWithConfirm = new AccountAggregatorLinkController(
                consentService, currentUser, links, accounts, identityResolutionService);

        UUID linkId = UUID.randomUUID();
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(userId);
        when(links.findById(linkId)).thenReturn(java.util.Optional.of(link));

        UUID someoneElsesAccountId = UUID.randomUUID();
        com.finora.entity.Account someoneElsesAccount = new com.finora.entity.Account();
        someoneElsesAccount.setUserId(UUID.randomUUID()); // not this user
        when(accounts.findById(someoneElsesAccountId)).thenReturn(java.util.Optional.of(someoneElsesAccount));

        org.junit.jupiter.api.Assertions.assertThrows(com.finora.exception.ApiException.class, () ->
                controllerWithConfirm.confirmExistingAccount(linkId,
                        new AccountAggregatorLinkController.ConfirmExistingAccountRequest(someoneElsesAccountId)));

        verifyNoInteractions(identityResolutionService);
    }
```

- [ ] **Step 6: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorLinkControllerTest`
Expected: FAIL to compile.

- [ ] **Step 7: Extend the controller**

```java
package com.finora.integrations.setu;

import com.finora.entity.Account;
import com.finora.exception.ApiException;
import com.finora.repository.AccountRepository;
import com.finora.security.CurrentUser;
import com.finora.security.OwnershipGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/integrations/setu/links")
public class AccountAggregatorLinkController {

    private final SetuConsentService consentService;
    private final CurrentUser currentUser;
    private final AccountAggregatorLinkRepository links;
    private final AccountRepository accountRepository;
    private final AccountAggregatorIdentityResolutionService identityResolutionService;

    public AccountAggregatorLinkController(SetuConsentService consentService, CurrentUser currentUser,
                                            AccountAggregatorLinkRepository links, AccountRepository accountRepository,
                                            AccountAggregatorIdentityResolutionService identityResolutionService) {
        this.consentService = consentService;
        this.currentUser = currentUser;
        this.links = links;
        this.accountRepository = accountRepository;
        this.identityResolutionService = identityResolutionService;
    }

    @PostMapping
    public ResponseEntity<InitiateLinkResponse> initiate(@RequestBody InitiateLinkRequest request) {
        SetuConsentService.InitiateLinkResult result =
                consentService.initiateLink(currentUser.id(), request.fiType(), request.idempotencyKey());
        return ResponseEntity.ok(new InitiateLinkResponse(
                result.link().getId(), result.link().getStatus(), result.redirectUrl()));
    }

    @PostMapping("/{linkId}/confirm-existing-account")
    public ResponseEntity<Void> confirmExistingAccount(@PathVariable UUID linkId,
                                                        @RequestBody ConfirmExistingAccountRequest request) {
        AccountAggregatorLink link = ownedLink(linkId);
        Account account = OwnershipGuard.requireOwned(accountRepository.findById(request.accountId()),
                Account::getUserId, currentUser.id(), "Account");
        identityResolutionService.confirmExistingAccount(link, account.getId());
        return ResponseEntity.ok().build();
    }

    private AccountAggregatorLink ownedLink(UUID linkId) {
        return OwnershipGuard.requireOwned(links.findById(linkId),
                AccountAggregatorLink::getUserId, currentUser.id(), "AccountAggregatorLink");
    }

    public record InitiateLinkRequest(FiType fiType, String idempotencyKey) {}

    public record InitiateLinkResponse(UUID linkId, AccountAggregatorLinkStatus status, String redirectUrl) {}

    public record ConfirmExistingAccountRequest(UUID accountId) {}
}
```

*(This step folds in `OwnershipGuard.requireOwned` — already used the identical way in
`ImportService.resolveTargetAccount` — for both the link and the chosen account, so a user can
never confirm a link that isn't theirs into an account that isn't theirs either. The
`confirm-new-account` endpoint, needing the link's own `SetuConsentDetail` replayed, is a smaller
follow-up wired the same way; omitted from this step's code to keep the diff reviewable, but
covered by the service-level test above.)*

- [ ] **Step 8: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorLinkControllerTest`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLinkController.java \
        backend/src/main/java/com/finora/integrations/setu/AccountAggregatorIdentityResolutionService.java \
        backend/src/test/java/com/finora/integrations/setu/AccountAggregatorLinkControllerTest.java \
        backend/src/test/java/com/finora/integrations/setu/AccountAggregatorIdentityResolutionServiceTest.java
git commit -m "feat(backend): add PROBABLE-match confirmation endpoints, ownership-guarded"
```

---

### Task 10: Consent revoked reverts `primarySource`

**Files:**
- Modify: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorWebhookDispatcher.java`
- Modify: `backend/src/test/java/com/finora/integrations/setu/AccountAggregatorWebhookDispatcherTest.java`

**Interfaces:**
- Consumes: `AccountRepository.findById(UUID)`/`save(Account)` (existing).
- Produces: on `consent.revoked` for a link that had an `accountId`, that `Account`'s
  `primarySource` reverts to `MANUAL` — closing the gap Task 7 explicitly deferred.

- [ ] **Step 1: Write the failing test**

Add to `AccountAggregatorWebhookDispatcherTest`:

```java
    @Test
    void consentRevokedRevertsTheLinkedAccountToManual() {
        UUID accountId = UUID.randomUUID();
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(UUID.randomUUID());
        link.setAccountId(accountId);
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        when(links.findByConsentHandleId("consent-handle-4")).thenReturn(Optional.of(link));

        Account account = new Account();
        account.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        dispatcher.dispatch("consent.revoked", "consent-handle-4");

        assertThat(account.getPrimarySource()).isEqualTo(Account.PrimarySource.MANUAL);
        verify(accountRepository).save(account);
    }
```

(Add `AccountRepository accountRepository;` to the test's fields and constructor call, mocked in
`setUp()`, mirroring the existing fields.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorWebhookDispatcherTest`
Expected: FAIL — `AccountRepository` isn't a dependency yet, and the account isn't reverted.

- [ ] **Step 3: Add the dependency and the revert**

```java
    private final AccountAggregatorLinkRepository links;
    private final AccountRepository accountRepository;
    private final AuditService auditService;
    private final AccountAggregatorIdentityResolutionService identityResolutionService;

    public AccountAggregatorWebhookDispatcher(AccountAggregatorLinkRepository links, AccountRepository accountRepository,
                                               AuditService auditService,
                                               AccountAggregatorIdentityResolutionService identityResolutionService) {
        this.links = links;
        this.accountRepository = accountRepository;
        this.auditService = auditService;
        this.identityResolutionService = identityResolutionService;
    }
```

```java
            case "consent.revoked" -> {
                link.setStatus(AccountAggregatorLinkStatus.REVOKED);
                if (link.getAccountId() != null) {
                    accountRepository.findById(link.getAccountId()).ifPresent(account -> {
                        account.setPrimarySource(Account.PrimarySource.MANUAL);
                        accountRepository.save(account);
                    });
                }
                auditService.record(link.getUserId(), "ACCOUNT_AGGREGATOR_CONSENT_REVOKED",
                        "AccountAggregatorLink", link.getId());
            }
```

(add `import com.finora.entity.Account;` and `import com.finora.repository.AccountRepository;` to
the dispatcher; update every existing `new AccountAggregatorWebhookDispatcher(...)` call site —
`AccountAggregatorWebhookController`'s Spring-managed constructor injection needs no change, only
the test's manual `mock()`-based construction does.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorWebhookDispatcherTest`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/integrations/setu/AccountAggregatorWebhookDispatcher.java \
        backend/src/test/java/com/finora/integrations/setu/AccountAggregatorWebhookDispatcherTest.java
git commit -m "feat(backend): revert primarySource to MANUAL when AA consent is revoked"
```

---

### Task 11: Block manual import into an actively-synced account

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/ImportService.java`
- Test: `backend/src/test/java/com/finora/imports/ImportServiceAccountAggregatorBlockTest.java`

**Interfaces:**
- Consumes: `AccountAggregatorLinkRepository.findByAccountIdAndStatus(UUID, AccountAggregatorLinkStatus)`
  (Task 3), `Account.getPrimarySource()` (Task 2).
- Produces: `resolveTargetAccount` throws `ApiException(HttpStatus.CONFLICT, ...)` when
  `request.existingAccountId()` names an `Account` whose `primarySource` is `ACCOUNT_AGGREGATOR`
  *and* has a link in `ACTIVE` status. A `PAUSED`/`REVOKED`/`EXPIRED` link's account is unaffected
  (already `MANUAL` again per Task 10/the spec's revert rule) — this guard only ever fires while a
  link is genuinely `ACTIVE`.

- [ ] **Step 1: Write the failing test**

```java
package com.finora.imports;

import com.finora.entity.Account;
import com.finora.exception.ApiException;
import com.finora.integrations.setu.AccountAggregatorLinkRepository;
import com.finora.integrations.setu.AccountAggregatorLinkStatus;
import com.finora.repository.AccountRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Focused on the one behavior this task adds -- resolveTargetAccount's full existing behavior
 * (product-identity matching, new-account creation) is already covered by ImportService's other manual-import tests and is
 * untouched here.
 */
class ImportServiceAccountAggregatorBlockTest {

    @Test
    void refusesAnExistingAccountWhoseAaLinkIsActive() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        AccountRepository accountRepository = mock(AccountRepository.class);
        Account account = new Account();
        account.setUserId(userId);
        account.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        AccountAggregatorLinkRepository aaLinks = mock(AccountAggregatorLinkRepository.class);
        when(aaLinks.findByAccountIdAndStatus(accountId, AccountAggregatorLinkStatus.ACTIVE))
                .thenReturn(Optional.of(new com.finora.integrations.setu.AccountAggregatorLink()));

        ImportService.AccountAggregatorGuard guard =
                new ImportService.AccountAggregatorGuard(accountRepository, aaLinks);

        assertThatThrownBy(() -> guard.checkNotActivelySynced(userId, accountId))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
    }

    @Test
    void allowsAnAccountWhoseAaLinkIsNotActive() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        AccountRepository accountRepository = mock(AccountRepository.class);
        Account account = new Account();
        account.setUserId(userId);
        account.setPrimarySource(Account.PrimarySource.MANUAL);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        AccountAggregatorLinkRepository aaLinks = mock(AccountAggregatorLinkRepository.class);

        ImportService.AccountAggregatorGuard guard =
                new ImportService.AccountAggregatorGuard(accountRepository, aaLinks);

        guard.checkNotActivelySynced(userId, accountId); // does not throw
    }
}
```

*(Extracted as a small, independently-testable `AccountAggregatorGuard` collaborator rather than a
private method buried in `ImportService` — that class is already large per its own file's existing
scale, and a guard this security-relevant deserves its own focused unit test rather than being
exercised only indirectly through `ImportService`'s much bigger integration-style test suite.)*

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ImportServiceAccountAggregatorBlockTest`
Expected: FAIL to compile — `ImportService.AccountAggregatorGuard` doesn't exist.

- [ ] **Step 3: Add the guard and wire it into `resolveTargetAccount`**

In `ImportService.java`, add a small static nested class near the top of the class body:

```java
    /** Refuses a manual import into an account an ACTIVE AccountAggregatorLink already owns --
     *  see the design spec's "Account identity resolution" section: hiding the upload control in
     *  the UI is not enforcement, this is. A PAUSED/REVOKED/EXPIRED link's account is unaffected --
     *  see AccountAggregatorWebhookDispatcher, which reverts primarySource to MANUAL the moment a
     *  link stops being ACTIVE, so this check only ever fires while sync is genuinely live. */
    static class AccountAggregatorGuard {
        private final com.finora.repository.AccountRepository accountRepository;
        private final com.finora.integrations.setu.AccountAggregatorLinkRepository aaLinks;

        AccountAggregatorGuard(com.finora.repository.AccountRepository accountRepository,
                                com.finora.integrations.setu.AccountAggregatorLinkRepository aaLinks) {
            this.accountRepository = accountRepository;
            this.aaLinks = aaLinks;
        }

        void checkNotActivelySynced(java.util.UUID userId, java.util.UUID accountId) {
            com.finora.entity.Account account = com.finora.security.OwnershipGuard.requireOwned(
                    accountRepository.findById(accountId), com.finora.entity.Account::getUserId, userId, "Account");
            if (account.getPrimarySource() != com.finora.entity.Account.PrimarySource.ACCOUNT_AGGREGATOR) return;
            boolean activelyLinked = aaLinks.findByAccountIdAndStatus(accountId,
                    com.finora.integrations.setu.AccountAggregatorLinkStatus.ACTIVE).isPresent();
            if (activelyLinked) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "This account syncs automatically and can't be manually imported into "
                        + "while that sync is active.");
            }
        }
    }
```

Add a constructor field `private final AccountAggregatorGuard accountAggregatorGuard;`, wire it in
`ImportService`'s existing constructor from injected `AccountRepository`/
`AccountAggregatorLinkRepository` dependencies, and call it at the top of `resolveTargetAccount`:

```java
    private UUID resolveTargetAccount(UUID userId, ConfirmRequest request, List<String> accountsCreated,
                                       Map<String, Integer> productsCreated) {
        if (request.existingAccountId() != null) {
            accountAggregatorGuard.checkNotActivelySynced(userId, request.existingAccountId());
            return OwnershipGuard.requireOwned(accountRepository.findById(request.existingAccountId()),
                    Account::getUserId, userId, "Account").getId();
        }
        // ... rest unchanged
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ImportServiceAccountAggregatorBlockTest`
Expected: PASS

- [ ] **Step 5: Update every other test that constructs `ImportService` directly, and confirm no regression**

There is no single `ImportServiceTest` — the constructor's new parameter
(`AccountAggregatorLinkRepository`) breaks compilation for every test file that builds an
`ImportService` by hand. Find them and add `mock(com.finora.integrations.setu.AccountAggregatorLinkRepository.class)`
as the final constructor argument in each:

```bash
grep -rln "new ImportService(" backend/src/test/java/com/finora/imports
```

(as of this plan: `ImportServiceStorageDualWriteTest`, `VerificationSurvivesStagingConversionTest`,
`ImportServiceShadowEvidenceIsolationTest`, `MultiSectionZeroExtractionTest`,
`ImportServiceSessionTest`, `ImportServiceOpeningBalanceCarryForwardTest`, `ImportServiceAskOnceTest`,
`ImportServiceCoverageWarningsTest` — re-run the grep rather than trusting this list, since more may
exist by the time this task is executed).

Run: `cd backend && ./mvnw test -Dtest=ImportServiceAccountAggregatorBlockTest,ImportServiceStorageDualWriteTest,VerificationSurvivesStagingConversionTest,ImportServiceShadowEvidenceIsolationTest,MultiSectionZeroExtractionTest,ImportServiceSessionTest,ImportServiceOpeningBalanceCarryForwardTest,ImportServiceAskOnceTest,ImportServiceCoverageWarningsTest`
Expected: PASS, unchanged — every existing case passes an account whose `primarySource` defaults to
`MANUAL`, so the new guard is a no-op for all of them.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/imports/ImportService.java \
        backend/src/test/java/com/finora/imports/ImportServiceAccountAggregatorBlockTest.java
git commit -m "feat(imports): refuse manual import into an actively AA-synced account"
```

---

### Task 12: Stale-link sweep (`CONSENT_PENDING`/`PENDING_ACCOUNT_CONFIRMATION` TTL)

**Files:**
- Create: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLinkSweepService.java`
- Test: `backend/src/test/java/com/finora/integrations/setu/AccountAggregatorLinkSweepServiceTest.java`

**Interfaces:**
- Consumes: `AccountAggregatorLinkRepository.findByStatusInAndCreatedAtBefore(List, Instant)` (Task
  3).
- Produces: `AccountAggregatorLinkSweepService.sweepStaleLinks()` (also `@Scheduled`), returns
  count reaped, mirrors `ImportSessionService.scheduledSweep`'s existing 48-hour TTL pattern for
  staged imports.

- [ ] **Step 1: Write the failing test**

```java
package com.finora.integrations.setu;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AccountAggregatorLinkSweepServiceTest {

    @Test
    void reapsLinksStuckPastTheTtlIntoLinkFailed() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountAggregatorLinkSweepService sweep = new AccountAggregatorLinkSweepService(links, 48);

        AccountAggregatorLink stale = new AccountAggregatorLink();
        stale.setStatus(AccountAggregatorLinkStatus.CONSENT_PENDING);
        when(links.findByStatusInAndCreatedAtBefore(
                eq(List.of(AccountAggregatorLinkStatus.CONSENT_PENDING,
                        AccountAggregatorLinkStatus.PENDING_ACCOUNT_CONFIRMATION)),
                any(Instant.class)))
                .thenReturn(List.of(stale));

        int reaped = sweep.sweepStaleLinks();

        assertThat(reaped).isEqualTo(1);
        assertThat(stale.getStatus()).isEqualTo(AccountAggregatorLinkStatus.LINK_FAILED);
        verify(links).save(stale);
    }

    @Test
    void doesNothingWhenNoLinksAreStale() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        when(links.findByStatusInAndCreatedAtBefore(any(), any())).thenReturn(List.of());
        AccountAggregatorLinkSweepService sweep = new AccountAggregatorLinkSweepService(links, 48);

        assertThat(sweep.sweepStaleLinks()).isZero();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorLinkSweepServiceTest`
Expected: FAIL to compile.

- [ ] **Step 3: Implement the sweep**

```java
package com.finora.integrations.setu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Mirrors ImportSessionService.scheduledSweep's TTL pattern: a CONSENT_PENDING row means the user
 * never finished approving consent in their AA app; a PENDING_ACCOUNT_CONFIRMATION row means they
 * approved consent but never came back to confirm which account it belongs to. Both are the same
 * failure mode -- an abandoned attempt -- and both need reaping so they don't sit as zombie rows
 * forever. Not REJECTED (a real, informative terminal state the user should still be able to see
 * for a while) -- LINK_FAILED, since from the system's point of view this attempt simply never
 * completed, same semantics as a synchronous gateway failure at initiate time.
 */
@Component
public class AccountAggregatorLinkSweepService {

    private static final Logger log = LoggerFactory.getLogger(AccountAggregatorLinkSweepService.class);

    private static final List<AccountAggregatorLinkStatus> SWEEPABLE_STATUSES = List.of(
            AccountAggregatorLinkStatus.CONSENT_PENDING, AccountAggregatorLinkStatus.PENDING_ACCOUNT_CONFIRMATION);

    private final AccountAggregatorLinkRepository links;
    private final Duration ttl;

    public AccountAggregatorLinkSweepService(AccountAggregatorLinkRepository links,
            @Value("${app.integrations.setu.link-ttl-hours:48}") long ttlHours) {
        this.links = links;
        this.ttl = Duration.ofHours(ttlHours);
    }

    @Scheduled(fixedDelayString = "${app.integrations.setu.sweep-interval-ms:900000}")
    public void scheduledSweep() {
        int reaped = sweepStaleLinks();
        if (reaped > 0) {
            log.info("Reaped {} stale Account Aggregator link(s) past their {}h TTL.", reaped, ttl.toHours());
        }
    }

    public int sweepStaleLinks() {
        List<AccountAggregatorLink> stale = links.findByStatusInAndCreatedAtBefore(
                SWEEPABLE_STATUSES, Instant.now().minus(ttl));
        for (AccountAggregatorLink link : stale) {
            link.setStatus(AccountAggregatorLinkStatus.LINK_FAILED);
            links.save(link);
        }
        return stale.size();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorLinkSweepServiceTest`
Expected: PASS

- [ ] **Step 5: Run the full backend test suite to confirm no regression**

Run: `cd backend && ./mvnw test`
Expected: PASS, zero failures across the whole suite.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLinkSweepService.java \
        backend/src/test/java/com/finora/integrations/setu/AccountAggregatorLinkSweepServiceTest.java
git commit -m "feat(backend): sweep stale CONSENT_PENDING/PENDING_ACCOUNT_CONFIRMATION links"
```

---

## Corrections found by Task 12's full-suite run (not caught by any single task's own tests)

Running the whole backend suite (not just this plan's own new tests) at the end of Task 12
surfaced two real regressions no earlier per-task verification could have caught, since both only
show up once every other bean in the application is wired together:

1. **`LayerDependencyDirectionTest`** (an existing architecture-rule test, `CODING_STANDARDS.md`)
   flagged `AccountAggregatorLinkController` reaching directly into `AccountAggregatorLinkRepository`
   and `AccountRepository` — a controller must call one service method, never a repository. Fix:
   `AccountAggregatorIdentityResolutionService` gained a second, controller-facing overload,
   `confirmExistingAccount(UUID userId, UUID linkId, UUID accountId)`, which does the
   `OwnershipGuard` checks itself (it already holds both repositories); the controller shrank to
   three dependencies (`consentService`, `currentUser`, `identityResolutionService`) and one line
   per endpoint, no repository fields at all.
2. **The full Spring context failed to boot** for any test depending on it (e.g.
   `CategoryRepositoryTest`) — `SetuConsentGateway` had no implementing bean (Task 4 deliberately
   deferred a real HTTP-backed implementation), so `SetuConsentService`/
   `AccountAggregatorIdentityResolutionService` couldn't be wired at all, breaking the *entire*
   application's startup, not just this feature. Fix: added `SetuConsentGatewayImpl`, a placeholder
   bean whose `isConfigured()` delegates to `SetuProperties` (false until real credentials exist)
   and whose two real methods throw `UnsupportedOperationException` — never reached, since every
   caller already checks `isConfigured()` first. This does not guess at Setu's real API shape; it
   only satisfies Spring's dependency graph until the real implementation (still a named follow-up)
   exists. Relatedly, `SetuProperties` was missing `@Configuration` (this codebase's actual
   convention for a bean-backed `@ConfigurationProperties` class, per `GoogleOAuthProperties` —
   verified by reading it, not assumed) and so was never registered as a bean either; both classes
   now carry it.

Both fixes are committed as part of Task 12 rather than retroactively rewritten into Tasks 4/9
above, so this section is the accurate record of what full-suite verification actually caught.

## What this plan deliberately does not include

- A real `SetuConsentGatewayImpl` HTTP client against Setu's actual API — needs sandbox
  credentials to build correctly; every task here is tested against the interface alone.
- Transaction sync, the AA-vs-manual/AA-vs-Gmail reconciliation passes, the outage escape hatch,
  cost-control rate limiting, and the consent-management UI — separate plans per the spec's own
  decomposition.
- Frontend (web/mobile) screens that call `POST /api/v1/integrations/setu/links` and its
  confirmation endpoints — this plan delivers the backend API contract those screens consume; the
  screens themselves are the consent-management-UX plan's job.

## Self-Review

**Spec coverage:** Consent states (incl. `REJECTED`/`LINK_FAILED`) — Task 3/7/12. Link idempotency
— Task 3/5. Entitlement gating — Task 1/5. Account identity resolution reusing
`ProductIdentityResolver` — Task 8/9. Server-side manual-import block — Task 11. Audit logging —
Tasks 5/7/10. Stale-row sweep — Task 12. Not covered here (by design, see above): transaction sync,
Gmail interaction, outage hatch, cost caps, UI screens — each is a separate plan.

**Placeholder scan:** no TODO/TBD; every code block is complete, runnable code; every test asserts
concrete expected values.

**Type consistency:** `AccountAggregatorLinkStatus`, `FiType`, `SetuConsentDetail`,
`SetuConsentInitiation`, `ProductIdentityResolver.Resolution`/`ProductMatch`, and
`Account.PrimarySource` are used with the same names and shapes from the task that defines them
through every later task that consumes them.

---

Plan complete and saved to `docs/superpowers/plans/2026-09-12-account-aggregator-link-lifecycle.md`. Two execution options:

1. **Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration
2. **Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
