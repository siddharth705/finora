# Subscription Billing V1 — Backend Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the backend API surface for Razorpay Subscriptions-backed billing — schema,
Razorpay client wrapper, idempotent webhook handling, checkout, renewal, cancellation, and the
reconciliation safety net — with no UI dependency. This is Plan 1 of 4 (Plan 2: upgrade/downgrade +
admin controls; Plan 3: web UI; Plan 4: mobile UI), and is fully testable on its own.

**Architecture:** Razorpay's Subscriptions API is the source of truth for recurring billing state;
Fynora reacts to signed, idempotent webhooks rather than trusting client-reported success. The
existing single-mutable-`subscriptions`-row-per-user model is preserved unchanged — checkout,
renewal, and cancellation all update that one row in place, protected by the existing
`idx_subscriptions_one_active_per_user` constraint.

**Tech Stack:** Spring Boot 3 (Java, Jakarta), Spring Data JPA, Flyway, PostgreSQL, `razorpay-java`
SDK, JUnit 5 + Mockito (unit), Testcontainers-backed `*IT` (integration), ArchUnit (already-enforced
invariants — see Global Constraints).

**Spec:** [`docs/superpowers/specs/2026-09-05-subscription-billing-v1-design.md`](../specs/2026-09-05-subscription-billing-v1-design.md)
— read it alongside this plan; task descriptions reference its section numbers (e.g. "§6.1") rather
than repeating its reasoning.

## Global Constraints

These are enforced today by ArchUnit tests in `backend/src/test/java/com/finora/architecture/` —
every task below already conforms, but if a step surprises you, this is why:

- Controllers are thin: parse request → call one service method → wrap the result. No repository
  access from a controller, no business logic in a controller (`LayerDependencyDirectionTest`).
- A `@RequestBody` DTO with `jakarta.validation.constraints` annotations must be paired with `@Valid`
  on the controller parameter, or the constraints silently never run (`ValidatedRequestBodyTest`).
- A class named `*Controller` must be `@RestController`, and vice versa (`StereotypeNamingConventionTest`).
- A class named `*Service` must be `@Service` or `@Component` (same test) — a single-purpose
  collaborator used by one caller should be named for what it does, not carry an unearned `-Service`
  suffix.
- A class named `*Repository` must be a Spring Data interface (same test file, separate rule).
- `@PreAuthorize` is required only under `/api/v1/admin/**` (`AdminEndpointAuthorizationTest`) — not
  applicable to anything in this plan (Plan 2 adds an admin endpoint and picks this up there).
- Flyway migration version numbers: before creating the migration file in Task 1, run
  `git fetch origin && git ls-tree -r --name-only origin/main -- backend/src/main/resources/db/migration | sort -t'V' -k2 -n | tail -3`
  to confirm `V154` is still free — other sessions may have landed a migration since this plan was
  written (2026-09-05, last confirmed free at `V154`; V153 was the latest on `origin/main` then).
- Currency is INR only, everywhere. No multi-currency handling anywhere in this plan.
- `AbstractIntegrationTest` subclasses require `-Dspring.profiles.active=test` (Maven's
  surefire/failsafe sets this automatically; an IDE "Run Test" button may not — see that class's own
  doc comment if a run fails immediately with a profile-guard `IllegalStateException`).

---

## Task 1: Schema migration + entities + repositories

**Files:**
- Create: `backend/src/main/resources/db/migration/V154__subscription_billing_v1.sql`
- Create: `backend/src/main/java/com/finora/entity/BillingPrice.java`
- Create: `backend/src/main/java/com/finora/entity/SubscriptionOrder.java`
- Create: `backend/src/main/java/com/finora/entity/WebhookEvent.java`
- Create: `backend/src/main/java/com/finora/repository/BillingPriceRepository.java`
- Create: `backend/src/main/java/com/finora/repository/SubscriptionOrderRepository.java`
- Create: `backend/src/main/java/com/finora/repository/WebhookEventRepository.java`
- Modify: `backend/src/main/java/com/finora/entity/Plan.java` (remove `price`, `billingCycle`)
- Modify: `backend/src/main/java/com/finora/entity/Subscription.java` (add `billingCycle`,
  `razorpaySubscriptionId`, `autoRenew`; add `STATUS_PAST_DUE` constant)
- Modify: `backend/src/main/java/com/finora/entity/PlanChange.java` (add
  `REASON_DOWNGRADE_SCHEDULED` constant)
- Modify: `backend/src/main/java/com/finora/repository/SubscriptionRepository.java` (add
  `findByRazorpaySubscriptionId`)
- Test: `backend/src/test/java/com/finora/repository/BillingPriceRepositoryIT.java`

**Interfaces:**
- Produces: `BillingPrice` entity with `getId()`, `getPlanId()`, `getBillingCycle()`, `getPrice()`,
  `getCurrency()`, `getRazorpayPlanId()`, `isActive()`. `BillingPriceRepository
  .findByPlanIdAndBillingCycleAndActiveTrue(UUID planId, String billingCycle) : Optional<BillingPrice>`.
- Produces: `SubscriptionOrder` entity with `getId()`, `getUserId()`, `getPlanId()`,
  `getBillingCycle()`, `getRazorpaySubscriptionId()`/`setRazorpaySubscriptionId(String)`,
  `getStatus()`/`setStatus(String)`, `getAmount()`, `getCreatedAt()`, `getCompletedAt()`/
  `setCompletedAt(Instant)`, plus `STATUS_PENDING`/`STATUS_COMPLETED`/`STATUS_FAILED`/
  `STATUS_ABANDONED` constants. `SubscriptionOrderRepository
  .findByRazorpaySubscriptionId(String) : Optional<SubscriptionOrder>`.
- Produces: `WebhookEvent` entity, `@Id private String eventId`, `getProvider()`, `getEventType()`,
  `getPayload()`, `getStatus()`/`setStatus(String)`, `getProcessedAt()`/`setProcessedAt(Instant)`,
  `STATUS_PROCESSED`/`STATUS_FAILED` constants. `WebhookEventRepository` — used directly only by
  Task 4's `WebhookEventService`, never by a controller.
- Produces: `Subscription.STATUS_PAST_DUE`, `.getBillingCycle()`/`.setBillingCycle(String)`,
  `.getRazorpaySubscriptionId()`/`.setRazorpaySubscriptionId(String)`, `.isAutoRenew()`/
  `.setAutoRenew(boolean)`. `SubscriptionRepository.findByRazorpaySubscriptionId(String) :
  Optional<Subscription>`.
- Produces: `PlanChange.REASON_DOWNGRADE_SCHEDULED`.

- [ ] **Step 1: Confirm the migration version is still free**

Run:
```bash
git fetch origin
git ls-tree -r --name-only origin/main -- backend/src/main/resources/db/migration | sort -t'V' -k2 -n | tail -3
```
Expected: `V153__held_statement_false_positive.sql` is still the latest. If a `V154` (or later)
already exists on `origin/main`, rename this migration to the next free number and update every
reference to `V154` in this task accordingly.

- [ ] **Step 2: Write the migration**

```sql
-- Subscription billing V1 (docs/superpowers/specs/2026-09-05-subscription-billing-v1-design.md).
-- Replaces admin-only manual plan changes with Razorpay Subscriptions-backed self-service billing.

-- price/billing_cycle move to billing_prices below -- price is cycle-dependent for paid tiers,
-- so a single column on plans can no longer represent it.
ALTER TABLE plans DROP COLUMN price;
ALTER TABLE plans DROP COLUMN billing_cycle;

CREATE TABLE billing_prices (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id           UUID NOT NULL REFERENCES plans(id),
    billing_cycle     VARCHAR(10) NOT NULL,
    price             NUMERIC(10, 2) NOT NULL,
    currency          VARCHAR(3) NOT NULL DEFAULT 'INR',
    razorpay_plan_id  VARCHAR(50),
    active            BOOLEAN NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_billing_prices_plan_cycle_active ON billing_prices(plan_id, billing_cycle)
    WHERE active;

INSERT INTO billing_prices (plan_id, billing_cycle, price)
    SELECT id, 'MONTHLY', 399.00 FROM plans WHERE code = 'PLUS';
INSERT INTO billing_prices (plan_id, billing_cycle, price)
    SELECT id, 'YEARLY', 3500.00 FROM plans WHERE code = 'PLUS';
INSERT INTO billing_prices (plan_id, billing_cycle, price)
    SELECT id, 'MONTHLY', 799.00 FROM plans WHERE code = 'PREMIUM';
INSERT INTO billing_prices (plan_id, billing_cycle, price)
    SELECT id, 'YEARLY', 8000.00 FROM plans WHERE code = 'PREMIUM';
-- razorpay_plan_id stays NULL until the one-time Razorpay-account setup step (spec §10) populates
-- it -- checkout refuses with a clear error until then (Task 6), not a silent failure.

ALTER TABLE subscriptions ADD COLUMN billing_cycle VARCHAR(10);
ALTER TABLE subscriptions ADD COLUMN razorpay_subscription_id VARCHAR(50);
ALTER TABLE subscriptions ADD COLUMN auto_renew BOOLEAN NOT NULL DEFAULT true;
CREATE INDEX idx_subscriptions_razorpay_subscription_id ON subscriptions(razorpay_subscription_id)
    WHERE razorpay_subscription_id IS NOT NULL;

CREATE TABLE subscription_orders (
    id                         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                    UUID NOT NULL REFERENCES users(id),
    plan_id                    UUID NOT NULL REFERENCES plans(id),
    billing_cycle              VARCHAR(10) NOT NULL,
    razorpay_subscription_id   VARCHAR(50),
    status                     VARCHAR(20) NOT NULL,
    amount                     NUMERIC(10, 2) NOT NULL,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at               TIMESTAMPTZ
);
CREATE INDEX idx_subscription_orders_user_id ON subscription_orders(user_id);
CREATE INDEX idx_subscription_orders_razorpay_subscription_id
    ON subscription_orders(razorpay_subscription_id) WHERE razorpay_subscription_id IS NOT NULL;

-- Idempotency ledger (spec §4.7). PK is Razorpay's own event id, not a generated UUID -- the whole
-- point is a natural key the ON CONFLICT clause can target.
CREATE TABLE webhook_events (
    event_id      VARCHAR(50) PRIMARY KEY,
    provider      VARCHAR(20) NOT NULL,
    event_type    VARCHAR(50) NOT NULL,
    payload       JSONB,
    status        VARCHAR(20),
    processed_at  TIMESTAMPTZ
);
```

- [ ] **Step 3: Update `Plan.java`**

Remove the `price` and `billingCycle` fields and their getters/setters entirely — do not leave dead
fields behind. The class comment's reference to "seeded from `frontend/src/pages/landing/plans.ts`"
now applies only to `name`/`code`, not price (price's new source of truth is `billing_prices`).

- [ ] **Step 4: Create `BillingPrice.java`**

```java
package com.finora.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Subscription billing V1. Price is keyed by (plan, billing cycle), not by plan alone --
 * entitlements stay billing-cycle-agnostic (EntitlementService never reads this table), only
 * checkout and renewal do. FREE has no row here; it is never checked out through Razorpay.
 */
@Entity
@Table(name = "billing_prices")
public class BillingPrice {

    public static final String CYCLE_MONTHLY = "MONTHLY";
    public static final String CYCLE_YEARLY = "YEARLY";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "billing_cycle", nullable = false, length = 10)
    private String billingCycle;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "razorpay_plan_id", length = 50)
    private String razorpayPlanId;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getPlanId() { return planId; }
    public void setPlanId(UUID planId) { this.planId = planId; }
    public String getBillingCycle() { return billingCycle; }
    public void setBillingCycle(String billingCycle) { this.billingCycle = billingCycle; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getRazorpayPlanId() { return razorpayPlanId; }
    public void setRazorpayPlanId(String razorpayPlanId) { this.razorpayPlanId = razorpayPlanId; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 5: Create `SubscriptionOrder.java`**

```java
package com.finora.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Subscription billing V1 (design spec §4.4). Business/audit layer, independent of {@code payments}
 * -- exists for funnel/support visibility (abandoned checkouts, upgrade-in-progress correlation),
 * never read by entitlements or {@code payments}. {@code razorpaySubscriptionId} is set at creation
 * time, before any webhook arrives -- it is how the webhook handler correlates an incoming
 * {@code subscription.activated} event back to the order that requested it, which matters most
 * during an upgrade, where the user's existing {@code subscriptions} row already points at a
 * different Razorpay subscription id.
 */
@Entity
@Table(name = "subscription_orders")
public class SubscriptionOrder {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_ABANDONED = "ABANDONED";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "billing_cycle", nullable = false, length = 10)
    private String billingCycle;

    @Column(name = "razorpay_subscription_id", length = 50)
    private String razorpaySubscriptionId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getPlanId() { return planId; }
    public void setPlanId(UUID planId) { this.planId = planId; }
    public String getBillingCycle() { return billingCycle; }
    public void setBillingCycle(String billingCycle) { this.billingCycle = billingCycle; }
    public String getRazorpaySubscriptionId() { return razorpaySubscriptionId; }
    public void setRazorpaySubscriptionId(String razorpaySubscriptionId) { this.razorpaySubscriptionId = razorpaySubscriptionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
```

- [ ] **Step 6: Create `WebhookEvent.java`**

```java
package com.finora.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * Subscription billing V1 (design spec §4.7). Mandatory webhook idempotency ledger -- Razorpay can
 * and does resend events, and duplicate processing can corrupt subscription state. PK is Razorpay's
 * own event id, a natural key the claim-flow's {@code INSERT ... ON CONFLICT DO NOTHING} targets
 * (see WebhookEventService). {@code status} distinguishes "we saw this and handled it" from "we saw
 * it and our handler threw" for production debugging -- a webhook that errors mid-processing still
 * gets its row (so a Razorpay retry of the same event is still recognized as a duplicate), marked
 * FAILED rather than looking identical to a success.
 */
@Entity
@Table(name = "webhook_events")
public class WebhookEvent {

    public static final String STATUS_PROCESSED = "PROCESSED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @Column(name = "event_id", length = 50)
    private String eventId;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(length = 20)
    private String status;

    @Column(name = "processed_at")
    private Instant processedAt;

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
```

- [ ] **Step 7: Update `Subscription.java`**

Add the new fields, getters/setters, and the `STATUS_PAST_DUE` constant to the existing class
(`backend/src/main/java/com/finora/entity/Subscription.java`):

```java
    public static final String STATUS_PAST_DUE = "PAST_DUE";
```

```java
    @Column(name = "billing_cycle", length = 10)
    private String billingCycle;

    @Column(name = "razorpay_subscription_id", length = 50)
    private String razorpaySubscriptionId;

    @Column(name = "auto_renew", nullable = false)
    private boolean autoRenew = true;
```

```java
    public String getBillingCycle() { return billingCycle; }
    public void setBillingCycle(String billingCycle) { this.billingCycle = billingCycle; }
    public String getRazorpaySubscriptionId() { return razorpaySubscriptionId; }
    public void setRazorpaySubscriptionId(String razorpaySubscriptionId) { this.razorpaySubscriptionId = razorpaySubscriptionId; }
    public boolean isAutoRenew() { return autoRenew; }
    public void setAutoRenew(boolean autoRenew) { this.autoRenew = autoRenew; }
```

- [ ] **Step 8: Update `PlanChange.java`**

Add one constant alongside the existing three:

```java
    public static final String REASON_DOWNGRADE_SCHEDULED = "DOWNGRADE_SCHEDULED";
```

- [ ] **Step 9: Create the three new repositories**

```java
package com.finora.repository;

import com.finora.entity.BillingPrice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BillingPriceRepository extends JpaRepository<BillingPrice, UUID> {
    Optional<BillingPrice> findByPlanIdAndBillingCycleAndActiveTrue(UUID planId, String billingCycle);
}
```

```java
package com.finora.repository;

import com.finora.entity.SubscriptionOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SubscriptionOrderRepository extends JpaRepository<SubscriptionOrder, UUID> {
    Optional<SubscriptionOrder> findByRazorpaySubscriptionId(String razorpaySubscriptionId);
}
```

```java
package com.finora.repository;

import com.finora.entity.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, String> {
}
```

- [ ] **Step 10: Add the new finder to `SubscriptionRepository`**

```java
    Optional<Subscription> findByRazorpaySubscriptionId(String razorpaySubscriptionId);
```

- [ ] **Step 11: Write the failing repository test**

```java
package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.BillingPrice;
import com.finora.entity.Plan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BillingPriceRepositoryIT extends AbstractIntegrationTest {

    @Autowired private BillingPriceRepository billingPriceRepository;
    @Autowired private PlanRepository planRepository;

    @Test
    void findsTheActivePriceForAPlanAndCycle() {
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();

        Optional<BillingPrice> monthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(premium.getId(), BillingPrice.CYCLE_MONTHLY);

        assertThat(monthly).isPresent();
        assertThat(monthly.get().getPrice()).isEqualByComparingTo(new BigDecimal("799.00"));
    }

    @Test
    void freePlanHasNoBillingPriceRow() {
        Plan free = planRepository.findByCode("FREE").orElseThrow();

        Optional<BillingPrice> monthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(free.getId(), BillingPrice.CYCLE_MONTHLY);

        assertThat(monthly).isEmpty();
    }
}
```

- [ ] **Step 12: Run the migration and the test**

```bash
cd backend && ./mvnw test -Dtest=BillingPriceRepositoryIT
```
Expected: PASS. Flyway applies `V154` as part of Testcontainers startup; if it fails with a
migration checksum/version error, re-check Step 1's version-freshness assumption first.

- [ ] **Step 13: Commit**

```bash
git add backend/src/main/resources/db/migration/V154__subscription_billing_v1.sql \
        backend/src/main/java/com/finora/entity/BillingPrice.java \
        backend/src/main/java/com/finora/entity/SubscriptionOrder.java \
        backend/src/main/java/com/finora/entity/WebhookEvent.java \
        backend/src/main/java/com/finora/entity/Plan.java \
        backend/src/main/java/com/finora/entity/Subscription.java \
        backend/src/main/java/com/finora/entity/PlanChange.java \
        backend/src/main/java/com/finora/repository/BillingPriceRepository.java \
        backend/src/main/java/com/finora/repository/SubscriptionOrderRepository.java \
        backend/src/main/java/com/finora/repository/WebhookEventRepository.java \
        backend/src/main/java/com/finora/repository/SubscriptionRepository.java \
        backend/src/test/java/com/finora/repository/BillingPriceRepositoryIT.java
git commit -m "feat(db): add subscription billing V1 schema (billing_prices, subscription_orders, webhook_events)"
```

---

## Task 2: Razorpay dependency + configuration

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/com/finora/integrations/razorpay/RazorpayProperties.java`
- Test: `backend/src/test/java/com/finora/integrations/razorpay/RazorpayPropertiesTest.java`
- Modify: `backend/src/main/resources/application.yml`

**Interfaces:**
- Produces: `RazorpayProperties` with `getKeyId()`, `getKeySecret()`, `getWebhookSecret()`,
  `isConfigured() : boolean`. Consumed by Task 3's gateway and Task 5's webhook controller.

- [ ] **Step 1: Add the dependency**

In `backend/pom.xml`, immediately before the closing `</dependencies>` tag (after the
`micrometer-registry-prometheus` block):

```xml
    <!-- Subscription billing V1 (docs/superpowers/specs/2026-09-05-subscription-billing-v1-design.md).
         Official Razorpay Java SDK -- Subscriptions API client and webhook signature verification.
         Version pinned: Razorpay does not participate in spring-boot-starter-parent's dependency
         management. -->
    <dependency>
      <groupId>com.razorpay</groupId>
      <artifactId>razorpay-java</artifactId>
      <version>1.4.10</version>
    </dependency>
```

- [ ] **Step 2: Write the failing test**

```java
package com.finora.integrations.razorpay;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpayPropertiesTest {

    @Test
    void unconfiguredWhenAnyFieldIsMissing() {
        RazorpayProperties properties = new RazorpayProperties();
        assertThat(properties.isConfigured()).isFalse();

        properties.setKeyId("rzp_test_123");
        assertThat(properties.isConfigured()).isFalse();

        properties.setKeySecret("secret");
        assertThat(properties.isConfigured()).isFalse();

        properties.setWebhookSecret("whsec");
        assertThat(properties.isConfigured()).isTrue();
    }

    @Test
    void blankIsTreatedAsMissing() {
        RazorpayProperties properties = new RazorpayProperties();
        properties.setKeyId("");
        properties.setKeySecret("secret");
        properties.setWebhookSecret("whsec");

        assertThat(properties.isConfigured()).isFalse();
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=RazorpayPropertiesTest
```
Expected: FAIL — `RazorpayProperties` does not exist.

- [ ] **Step 4: Create `RazorpayProperties.java`**

```java
package com.finora.integrations.razorpay;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Subscription billing V1. Same "unconfigured is a supported state" posture as
 * {@code GoogleOAuthProperties} — no Razorpay account exists yet (design spec §10), so every
 * consumer of this class must degrade cleanly (a 503, not a NullPointerException) rather than
 * assume these are always present. Not a boot-time requirement the way the JWT signing key is: a
 * missing Razorpay credential disables one payment integration, it does not risk a security
 * control.
 */
@Configuration
@ConfigurationProperties(prefix = "app.integrations.razorpay")
public class RazorpayProperties {

    private String keyId;
    private String keySecret;
    private String webhookSecret;

    public boolean isConfigured() {
        return notBlank(keyId) && notBlank(keySecret) && notBlank(webhookSecret);
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
    public String getKeySecret() { return keySecret; }
    public void setKeySecret(String keySecret) { this.keySecret = keySecret; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=RazorpayPropertiesTest
```
Expected: PASS.

- [ ] **Step 6: Wire the config block into `application.yml`**

Add alongside the existing `integrations:` block (near `google:`, `backend/src/main/resources/application.yml`
line ~635):

```yaml
    # Subscription billing V1 (docs/superpowers/specs/2026-09-05-subscription-billing-v1-design.md).
    # Unset is a supported state, same posture as google above -- checkout/webhook endpoints answer
    # 503 until a real Razorpay account exists (spec §10), nothing else in the application depends
    # on this being present.
    razorpay:
      key-id: ${RAZORPAY_KEY_ID:}
      key-secret: ${RAZORPAY_KEY_SECRET:}
      webhook-secret: ${RAZORPAY_WEBHOOK_SECRET:}
```

- [ ] **Step 7: Commit**

```bash
git add backend/pom.xml \
        backend/src/main/java/com/finora/integrations/razorpay/RazorpayProperties.java \
        backend/src/test/java/com/finora/integrations/razorpay/RazorpayPropertiesTest.java \
        backend/src/main/resources/application.yml
git commit -m "feat(billing): add Razorpay SDK dependency and unconfigured-safe properties"
```

---

## Task 3: Razorpay subscription gateway

**Files:**
- Create: `backend/src/main/java/com/finora/integrations/razorpay/RazorpaySubscriptionGateway.java` (interface)
- Create: `backend/src/main/java/com/finora/integrations/razorpay/RazorpaySubscriptionDto.java`
- Create: `backend/src/main/java/com/finora/integrations/razorpay/RazorpaySubscriptionGatewayImpl.java`
- Test: `backend/src/test/java/com/finora/integrations/razorpay/RazorpaySubscriptionGatewayImplTest.java`

**Interfaces:**
- Consumes: `RazorpayProperties` (Task 2).
- Produces: `RazorpaySubscriptionGateway` — the interface every later task (checkout, cancel,
  upgrade/downgrade in Plan 2) depends on, never the concrete implementation or the raw SDK
  directly:
  ```java
  boolean isConfigured();
  RazorpaySubscriptionDto createSubscription(String razorpayPlanId, String billingCycle, Map<String, String> notes);
  RazorpaySubscriptionDto fetchSubscription(String razorpaySubscriptionId);
  void cancelSubscription(String razorpaySubscriptionId, boolean cancelAtCycleEnd);
  void updateSubscription(String razorpaySubscriptionId, String newRazorpayPlanId, boolean scheduleAtCycleEnd);
  ```
  `RazorpaySubscriptionDto(String id, String status)`.

Wrapping the SDK behind our own interface (rather than injecting `com.razorpay.RazorpayClient`
directly into services) is what makes every later service unit-testable with a plain Mockito mock —
`com.razorpay.RazorpayClient` and its nested `Subscription` resource are concrete SDK classes with
constructors that require real credentials, not interfaces designed for mocking.

- [ ] **Step 1: Write the failing test**

```java
package com.finora.integrations.razorpay;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpaySubscriptionGatewayImplTest {

    @Test
    void isConfiguredDelegatesToProperties() {
        RazorpayProperties properties = new RazorpayProperties();
        RazorpaySubscriptionGatewayImpl gateway = new RazorpaySubscriptionGatewayImpl(properties);

        assertThat(gateway.isConfigured()).isFalse();

        properties.setKeyId("rzp_test_123");
        properties.setKeySecret("secret");
        properties.setWebhookSecret("whsec");

        assertThat(gateway.isConfigured()).isTrue();
    }

    @Test
    void createSubscriptionRefusesWhenUnconfigured() {
        RazorpaySubscriptionGatewayImpl gateway = new RazorpaySubscriptionGatewayImpl(new RazorpayProperties());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> gateway.createSubscription("plan_123", "MONTHLY", java.util.Map.of()));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=RazorpaySubscriptionGatewayImplTest
```
Expected: FAIL — classes don't exist yet.

- [ ] **Step 3: Create `RazorpaySubscriptionDto.java`**

```java
package com.finora.integrations.razorpay;

/** Minimal projection of Razorpay's Subscription resource -- callers never see the SDK's
 *  {@code com.razorpay.Subscription} (a thin wrapper over a raw {@code JSONObject}) directly. */
public record RazorpaySubscriptionDto(String id, String status) {
}
```

- [ ] **Step 4: Create `RazorpaySubscriptionGateway.java`**

```java
package com.finora.integrations.razorpay;

import java.util.Map;

/**
 * Subscription billing V1 (design spec §6). The only Razorpay-facing seam every billing service
 * depends on -- checkout (Task 6), cancellation (Task 11), and upgrade/downgrade (Plan 2) all
 * program against this interface, never the SDK's {@code RazorpayClient} directly, so they can be
 * unit-tested with a plain mock.
 */
public interface RazorpaySubscriptionGateway {

    boolean isConfigured();

    /** Creates a new Razorpay Subscription against an already-provisioned Razorpay Plan (see
     *  {@code billing_prices.razorpayPlanId}). {@code notes} is stored on the Razorpay side for
     *  support/debugging correlation, not read back by this application. */
    RazorpaySubscriptionDto createSubscription(String razorpayPlanId, String billingCycle, Map<String, String> notes);

    RazorpaySubscriptionDto fetchSubscription(String razorpaySubscriptionId);

    /** {@code cancelAtCycleEnd=true} for every user-initiated cancellation (spec §6.3); {@code false}
     *  is reserved for the admin support action in Plan 2 (spec §6.6), which needs an immediate stop. */
    void cancelSubscription(String razorpaySubscriptionId, boolean cancelAtCycleEnd);

    /** {@code scheduleAtCycleEnd=true} defers the change to the next billing cycle (spec §6.4,
     *  downgrade); {@code false} applies it now (spec §6.5, upgrade — used by Plan 2, not this plan). */
    void updateSubscription(String razorpaySubscriptionId, String newRazorpayPlanId, boolean scheduleAtCycleEnd);
}
```

- [ ] **Step 5: Create `RazorpaySubscriptionGatewayImpl.java`**

```java
package com.finora.integrations.razorpay;

import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Subscription billing V1. Thin, mostly-untested-at-the-unit-level adapter over the Razorpay SDK --
 * the interface it implements ({@link RazorpaySubscriptionGateway}) is what everything else in this
 * codebase depends on and mocks; this class is exercised for real only against a live Razorpay test
 * account, matching how {@code GoogleOAuthClient} and {@code GmailApiClient} are thin real-network
 * wrappers verified at the IT/stub level rather than heavily unit tested internally.
 *
 * <p>{@code totalCount}: Razorpay's create-subscription API requires a finite cycle count even for
 * what is conceptually an indefinitely-recurring plan. 120 monthly cycles (10 years) / 20 yearly
 * cycles (20 years) are engineering defaults with no product-visible effect — {@code
 * subscription.completed} is explicitly not expected to fire in V1 (design spec §5), and Razorpay
 * subscriptions auto-renew on their own schedule regardless of this number until cancelled.
 */
@Component
public class RazorpaySubscriptionGatewayImpl implements RazorpaySubscriptionGateway {

    private static final int MONTHLY_TOTAL_COUNT = 120;
    private static final int YEARLY_TOTAL_COUNT = 20;

    private final RazorpayProperties properties;

    public RazorpaySubscriptionGatewayImpl(RazorpayProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    private RazorpayClient client() {
        if (!isConfigured()) {
            throw new IllegalStateException("Razorpay is not configured (RAZORPAY_KEY_ID/KEY_SECRET/WEBHOOK_SECRET unset).");
        }
        try {
            return new RazorpayClient(properties.getKeyId(), properties.getKeySecret());
        } catch (RazorpayException e) {
            throw new IllegalStateException("Failed to initialize Razorpay client.", e);
        }
    }

    @Override
    public RazorpaySubscriptionDto createSubscription(String razorpayPlanId, String billingCycle, Map<String, String> notes) {
        try {
            JSONObject request = new JSONObject();
            request.put("plan_id", razorpayPlanId);
            request.put("total_count", "YEARLY".equals(billingCycle) ? YEARLY_TOTAL_COUNT : MONTHLY_TOTAL_COUNT);
            request.put("quantity", 1);
            request.put("customer_notify", 1);
            JSONObject notesJson = new JSONObject();
            notes.forEach(notesJson::put);
            request.put("notes", notesJson);

            com.razorpay.Subscription subscription = client().subscriptions.create(request);
            return new RazorpaySubscriptionDto(subscription.get("id"), subscription.get("status"));
        } catch (RazorpayException e) {
            throw new IllegalStateException("Razorpay createSubscription failed.", e);
        }
    }

    @Override
    public RazorpaySubscriptionDto fetchSubscription(String razorpaySubscriptionId) {
        try {
            com.razorpay.Subscription subscription = client().subscriptions.fetch(razorpaySubscriptionId);
            return new RazorpaySubscriptionDto(subscription.get("id"), subscription.get("status"));
        } catch (RazorpayException e) {
            throw new IllegalStateException("Razorpay fetchSubscription failed.", e);
        }
    }

    @Override
    public void cancelSubscription(String razorpaySubscriptionId, boolean cancelAtCycleEnd) {
        try {
            JSONObject request = new JSONObject();
            request.put("cancel_at_cycle_end", cancelAtCycleEnd);
            client().subscription.cancel(razorpaySubscriptionId, request);
        } catch (RazorpayException e) {
            throw new IllegalStateException("Razorpay cancelSubscription failed.", e);
        }
    }

    @Override
    public void updateSubscription(String razorpaySubscriptionId, String newRazorpayPlanId, boolean scheduleAtCycleEnd) {
        try {
            JSONObject request = new JSONObject();
            request.put("plan_id", newRazorpayPlanId);
            request.put("schedule_change_at", scheduleAtCycleEnd ? "cycle_end" : "now");
            client().subscriptions.update(razorpaySubscriptionId, request);
        } catch (RazorpayException e) {
            throw new IllegalStateException("Razorpay updateSubscription failed.", e);
        }
    }
}
```

*(`instance.subscription.cancel` — singular `subscription` — matches the SDK's own inconsistent
naming for that one method; every other call is `instance.subscriptions.*`, plural. Verified against
the SDK's published usage docs, not a typo.)*

- [ ] **Step 6: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=RazorpaySubscriptionGatewayImplTest
```
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/integrations/razorpay/RazorpaySubscriptionGateway.java \
        backend/src/main/java/com/finora/integrations/razorpay/RazorpaySubscriptionDto.java \
        backend/src/main/java/com/finora/integrations/razorpay/RazorpaySubscriptionGatewayImpl.java \
        backend/src/test/java/com/finora/integrations/razorpay/RazorpaySubscriptionGatewayImplTest.java
git commit -m "feat(billing): add Razorpay subscription gateway wrapping the SDK"
```

---

## Task 4: Webhook idempotency ledger

**Files:**
- Create: `backend/src/main/java/com/finora/service/WebhookEventService.java`
- Test: `backend/src/test/java/com/finora/service/WebhookEventServiceIT.java`

**Interfaces:**
- Consumes: `WebhookEventRepository` (Task 1).
- Produces: `WebhookEventService.claim(String eventId, String provider, String eventType, Map<String,Object> payload) : boolean`
  (true = newly claimed, caller must process; false = duplicate, caller must skip),
  `markProcessed(String eventId) : void`, `markFailed(String eventId) : void`. Consumed by Task 5's
  webhook controller.

This needs an IT (real Postgres), not a unit test with a mocked repository — the whole point is the
database-level `ON CONFLICT` race behavior, which a mock cannot exercise honestly.

- [ ] **Step 1: Write the failing test**

```java
package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.WebhookEvent;
import com.finora.repository.WebhookEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookEventServiceIT extends AbstractIntegrationTest {

    @Autowired private WebhookEventService webhookEventService;
    @Autowired private WebhookEventRepository webhookEventRepository;

    @Test
    void firstClaimSucceedsSecondClaimOfSameEventIdIsRejected() {
        String eventId = "evt_" + UUID.randomUUID();

        boolean first = webhookEventService.claim(eventId, "RAZORPAY", "subscription.activated", Map.of("k", "v"));
        boolean second = webhookEventService.claim(eventId, "RAZORPAY", "subscription.activated", Map.of("k", "v"));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    @Test
    void markProcessedAndMarkFailedSetStatusAndTimestamp() {
        String eventId = "evt_" + UUID.randomUUID();
        webhookEventService.claim(eventId, "RAZORPAY", "subscription.charged", Map.of());

        webhookEventService.markProcessed(eventId);

        WebhookEvent processed = webhookEventRepository.findById(eventId).orElseThrow();
        assertThat(processed.getStatus()).isEqualTo(WebhookEvent.STATUS_PROCESSED);
        assertThat(processed.getProcessedAt()).isNotNull();

        String failedEventId = "evt_" + UUID.randomUUID();
        webhookEventService.claim(failedEventId, "RAZORPAY", "subscription.halted", Map.of());
        webhookEventService.markFailed(failedEventId);

        WebhookEvent failed = webhookEventRepository.findById(failedEventId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(WebhookEvent.STATUS_FAILED);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=WebhookEventServiceIT
```
Expected: FAIL — `WebhookEventService` doesn't exist.

- [ ] **Step 3: Create `WebhookEventService.java`**

```java
package com.finora.service;

import com.finora.entity.WebhookEvent;
import com.finora.repository.WebhookEventRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Subscription billing V1 (design spec §4.7). {@link #claim} must run, and succeed or fail, BEFORE
 * any subscription state change -- that ordering is what closes the race between two concurrent
 * deliveries of the same Razorpay event id. Relies on {@code webhook_events.event_id} being the
 * primary key: a second {@code save()} of the same id throws {@link DataIntegrityViolationException}
 * (a duplicate-key violation), which this method treats as "already claimed", not an error.
 */
@Service
public class WebhookEventService {

    private final WebhookEventRepository webhookEventRepository;

    public WebhookEventService(WebhookEventRepository webhookEventRepository) {
        this.webhookEventRepository = webhookEventRepository;
    }

    @Transactional
    public boolean claim(String eventId, String provider, String eventType, Map<String, Object> payload) {
        WebhookEvent event = new WebhookEvent();
        event.setEventId(eventId);
        event.setProvider(provider);
        event.setEventType(eventType);
        event.setPayload(payload);
        try {
            webhookEventRepository.saveAndFlush(event);
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    @Transactional
    public void markProcessed(String eventId) {
        webhookEventRepository.findById(eventId).ifPresent(event -> {
            event.setStatus(WebhookEvent.STATUS_PROCESSED);
            event.setProcessedAt(Instant.now());
        });
    }

    @Transactional
    public void markFailed(String eventId) {
        webhookEventRepository.findById(eventId).ifPresent(event -> {
            event.setStatus(WebhookEvent.STATUS_FAILED);
            event.setProcessedAt(Instant.now());
        });
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=WebhookEventServiceIT
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/service/WebhookEventService.java \
        backend/src/test/java/com/finora/service/WebhookEventServiceIT.java
git commit -m "feat(billing): add webhook idempotency ledger"
```

---

## Task 5: Webhook receiver — signature verification, dispatch skeleton, security allowlist

**Files:**
- Create: `backend/src/main/java/com/finora/service/RazorpayWebhookDispatcher.java`
- Create: `backend/src/main/java/com/finora/controller/RazorpayWebhookController.java`
- Modify: `backend/src/main/java/com/finora/config/SecurityConfig.java`
- Test: `backend/src/test/java/com/finora/controller/RazorpayWebhookControllerIT.java`

**Interfaces:**
- Consumes: `WebhookEventService` (Task 4), `RazorpayProperties` (Task 2).
- Produces: `RazorpayWebhookDispatcher.dispatch(String eventType, Map<String,Object> payload) : void`
  — Tasks 7–10 each add one `case` here. Unhandled event types are accepted without error (Razorpay
  sends more event types than this plan wires up — `subscription.paused`/`.resumed`/`.updated` are
  explicitly out of scope per the spec).

- [ ] **Step 1: Write the failing test**

```java
package com.finora.controller;

import com.finora.AbstractIntegrationTest;
import com.finora.repository.WebhookEventRepository;
import com.razorpay.Utils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpayWebhookControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private WebhookEventRepository webhookEventRepository;

    @Value("${app.integrations.razorpay.webhook-secret}")
    private String webhookSecret;

    @Test
    void validSignatureIsAcceptedAndRecorded() throws Exception {
        String body = "{\"event\":\"subscription.updated\",\"payload\":{}}";
        String signature = Utils.getHash(body, webhookSecret);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Razorpay-Signature", signature);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/webhooks/razorpay", new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void invalidSignatureIsRejectedBeforeAnyStateChange() {
        String body = "{\"event\":\"subscription.updated\",\"payload\":{}}";

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Razorpay-Signature", "not-a-real-signature");
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/webhooks/razorpay", new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
```

Add all three Razorpay properties to `application-test.yml` alongside the other test-profile
overrides — `RazorpayProperties.isConfigured()` requires `keyId`, `keySecret`, AND `webhookSecret`
all non-blank, so setting only `webhook-secret` would leave `isConfigured()` false under test and
every request would 503 before the signature check ever ran:
```yaml
    razorpay:
      key-id: test-key-id
      key-secret: test-key-secret
      webhook-secret: test-webhook-secret
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=RazorpayWebhookControllerIT
```
Expected: FAIL — `RazorpayWebhookController` doesn't exist; also confirm this 404s rather than
401s once you add it unauthenticated-but-missing, so you know Step 5's SecurityConfig change is the
one actually needed (not just a routing miss).

- [ ] **Step 3: Create `RazorpayWebhookDispatcher.java`**

```java
package com.finora.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Subscription billing V1 (design spec §5). One method per Razorpay event type this application
 * acts on -- Tasks 7-10 (this plan) and Plan 2's downgrade/upgrade reconciliation each add one
 * {@code case}. Named for what it does, not {@code *Service}: a single-purpose collaborator used
 * only by {@link com.finora.controller.RazorpayWebhookController}, per CODING_STANDARDS.md's naming
 * rule.
 */
@Component
public class RazorpayWebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookDispatcher.class);

    public void dispatch(String eventType, Map<String, Object> payload) {
        switch (eventType) {
            // Tasks 7-10 add cases here: "subscription.authenticated"/"subscription.activated",
            // "subscription.charged", "subscription.pending", "subscription.halted",
            // "subscription.cancelled".
            default -> log.info("Razorpay webhook event '{}' received but not handled in V1.", eventType);
        }
    }
}
```

- [ ] **Step 4: Create `RazorpayWebhookController.java`**

```java
package com.finora.controller;

import com.finora.integrations.razorpay.RazorpayProperties;
import com.finora.service.RazorpayWebhookDispatcher;
import com.finora.service.WebhookEventService;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
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

import java.util.Map;

/**
 * Subscription billing V1 (design spec §4.7, §5). Unauthenticated by necessity -- Razorpay calls
 * this directly, carrying no Finora session -- same posture as
 * {@code GoogleOAuthController}'s callback endpoint. What replaces authentication is the signature
 * header, verified before anything else runs.
 *
 * <p>Takes the raw body as a {@code String}, not a typed DTO: signature verification is over the
 * exact bytes Razorpay sent, and re-serializing a deserialized object is not guaranteed to produce
 * byte-identical output.
 */
@RestController
@RequestMapping("/api/v1/webhooks/razorpay")
public class RazorpayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookController.class);

    private final RazorpayProperties properties;
    private final WebhookEventService webhookEventService;
    private final RazorpayWebhookDispatcher dispatcher;

    public RazorpayWebhookController(RazorpayProperties properties, WebhookEventService webhookEventService,
                                      RazorpayWebhookDispatcher dispatcher) {
        this.properties = properties;
        this.webhookEventService = webhookEventService;
        this.dispatcher = dispatcher;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestHeader("X-Razorpay-Signature") String signature,
                                         @RequestBody String rawBody) {
        if (!properties.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        boolean valid;
        try {
            valid = Utils.verifyWebhookSignature(rawBody, signature, properties.getWebhookSecret());
        } catch (RazorpayException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        if (!valid) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        JSONObject json = new JSONObject(rawBody);
        String eventId = json.optString("id", null);
        String eventType = json.optString("event", "unknown");
        Map<String, Object> payload = json.toMap();

        // Razorpay's test-mode "send test webhook" tool does not always include an id -- fall back
        // to accepting (and not recording) rather than NPEing on a null primary key. A real
        // production webhook always carries one.
        if (eventId == null) {
            dispatcher.dispatch(eventType, payload);
            return ResponseEntity.ok().build();
        }

        if (!webhookEventService.claim(eventId, "RAZORPAY", eventType, payload)) {
            log.info("Duplicate Razorpay webhook event {} ({}), ignoring.", eventId, eventType);
            return ResponseEntity.ok().build();
        }

        try {
            dispatcher.dispatch(eventType, payload);
            webhookEventService.markProcessed(eventId);
        } catch (RuntimeException e) {
            webhookEventService.markFailed(eventId);
            log.error("Failed to process Razorpay webhook event {} ({}).", eventId, eventType, e);
            throw e;
        }
        return ResponseEntity.ok().build();
    }
}
```

- [ ] **Step 5: Allow the path in `SecurityConfig.java`**

In `backend/src/main/java/com/finora/config/SecurityConfig.java`, add one `requestMatchers` line to
the `authorizeHttpRequests` block (right after the Gmail callback line, ~line 146):

```java
                    // Razorpay calls this directly -- no Finora session, no Authorization header.
                    // The webhook signature (verified in RazorpayWebhookController) replaces
                    // authentication here, same reasoning as the Gmail OAuth callback above.
                    .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/razorpay").permitAll()
```

- [ ] **Step 6: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=RazorpayWebhookControllerIT
```
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/service/RazorpayWebhookDispatcher.java \
        backend/src/main/java/com/finora/controller/RazorpayWebhookController.java \
        backend/src/main/java/com/finora/config/SecurityConfig.java \
        backend/src/test/java/com/finora/controller/RazorpayWebhookControllerIT.java \
        backend/src/main/resources/application-test.yml
git commit -m "feat(billing): add signature-verified, idempotent Razorpay webhook receiver"
```

---

## Task 6: Checkout endpoint

**Files:**
- Create: `backend/src/main/java/com/finora/service/BillingCheckoutService.java`
- Create: `backend/src/main/java/com/finora/controller/BillingController.java`
- Modify: `backend/src/main/java/com/finora/dto/BillingDtos.java` (add `CheckoutRequest`, `CheckoutResponseDto`)
- Test: `backend/src/test/java/com/finora/service/BillingCheckoutServiceTest.java`
- Test: `backend/src/test/java/com/finora/controller/BillingControllerIT.java`

**Interfaces:**
- Consumes: `RazorpaySubscriptionGateway` (Task 3), `BillingPriceRepository`,
  `SubscriptionOrderRepository` (Task 1), `RazorpayProperties` (Task 2).
- Produces: `BillingCheckoutService.checkout(UUID userId, String planCode, String billingCycle) : CheckoutResponseDto`.
  `POST /api/v1/billing/checkout`.

- [ ] **Step 1: Add the DTOs**

In `backend/src/main/java/com/finora/dto/BillingDtos.java`, alongside the existing records:

```java
    /** POST /api/v1/billing/checkout (design spec §6.1). */
    public record CheckoutRequest(
            @NotBlank(message = "Plan code is required") String planCode,
            @NotBlank(message = "Billing cycle is required") String billingCycle
    ) {}

    /** What the frontend/mobile Razorpay Checkout widget needs to open. {@code keyId} is
     *  Razorpay's public key -- safe to expose to a client, it authenticates nothing on its own. */
    public record CheckoutResponseDto(String razorpaySubscriptionId, String keyId) {}
```

- [ ] **Step 2: Write the failing unit test**

```java
package com.finora.service;

import com.finora.dto.BillingDtos.CheckoutResponseDto;
import com.finora.entity.BillingPrice;
import com.finora.entity.Plan;
import com.finora.exception.ApiException;
import com.finora.integrations.razorpay.RazorpayProperties;
import com.finora.integrations.razorpay.RazorpaySubscriptionDto;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import com.finora.repository.BillingPriceRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BillingCheckoutServiceTest {

    private final PlanRepository planRepository = mock(PlanRepository.class);
    private final BillingPriceRepository billingPriceRepository = mock(BillingPriceRepository.class);
    private final SubscriptionOrderRepository subscriptionOrderRepository = mock(SubscriptionOrderRepository.class);
    private final RazorpaySubscriptionGateway gateway = mock(RazorpaySubscriptionGateway.class);
    private final RazorpayProperties properties = new RazorpayProperties();
    private BillingCheckoutService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties.setKeyId("rzp_test_123");
        service = new BillingCheckoutService(planRepository, billingPriceRepository,
                subscriptionOrderRepository, gateway, properties);

        // Plan.id has no public setter (@GeneratedValue) -- same reflection-based construction
        // EntitlementServiceTest already uses for the same reason.
        Plan premium = new Plan();
        ReflectionTestUtils.setField(premium, "id", planId);
        premium.setCode("PREMIUM");
        when(planRepository.findByCode("PREMIUM")).thenReturn(Optional.of(premium));
        when(gateway.isConfigured()).thenReturn(true);
    }

    @Test
    void createsRazorpaySubscriptionAndWritesAPendingOrder() {
        BillingPrice price = new BillingPrice();
        price.setPlanId(planId);
        price.setBillingCycle(BillingPrice.CYCLE_MONTHLY);
        price.setPrice(new BigDecimal("799.00"));
        price.setRazorpayPlanId("plan_razorpay_123");
        when(billingPriceRepository.findByPlanIdAndBillingCycleAndActiveTrue(eq(planId), eq("MONTHLY")))
                .thenReturn(Optional.of(price));
        when(gateway.createSubscription(eq("plan_razorpay_123"), eq("MONTHLY"), anyMap()))
                .thenReturn(new RazorpaySubscriptionDto("sub_razorpay_123", "created"));

        CheckoutResponseDto response = service.checkout(userId, "PREMIUM", "MONTHLY");

        assertThat(response.razorpaySubscriptionId()).isEqualTo("sub_razorpay_123");
        assertThat(response.keyId()).isEqualTo("rzp_test_123");

        ArgumentCaptor<com.finora.entity.SubscriptionOrder> orderCaptor =
                ArgumentCaptor.forClass(com.finora.entity.SubscriptionOrder.class);
        verify(subscriptionOrderRepository).save(orderCaptor.capture());
        com.finora.entity.SubscriptionOrder saved = orderCaptor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getStatus()).isEqualTo(com.finora.entity.SubscriptionOrder.STATUS_PENDING);
        assertThat(saved.getRazorpaySubscriptionId()).isEqualTo("sub_razorpay_123");
        assertThat(saved.getAmount()).isEqualByComparingTo(new BigDecimal("799.00"));
    }

    @Test
    void refusesWhenNoBillingPriceExistsForThePlanAndCycle() {
        when(billingPriceRepository.findByPlanIdAndBillingCycleAndActiveTrue(eq(planId), eq("MONTHLY")))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.checkout(userId, "PREMIUM", "MONTHLY"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void refusesWhenRazorpayIsNotConfigured() {
        when(gateway.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.checkout(userId, "PREMIUM", "MONTHLY"))
                .isInstanceOf(ApiException.class);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=BillingCheckoutServiceTest
```
Expected: FAIL — `BillingCheckoutService` doesn't exist.

- [ ] **Step 4: Create `BillingCheckoutService.java`**

```java
package com.finora.service;

import com.finora.dto.BillingDtos.CheckoutResponseDto;
import com.finora.entity.BillingPrice;
import com.finora.entity.Plan;
import com.finora.entity.SubscriptionOrder;
import com.finora.exception.ApiException;
import com.finora.integrations.razorpay.RazorpayProperties;
import com.finora.integrations.razorpay.RazorpaySubscriptionDto;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import com.finora.repository.BillingPriceRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionOrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Subscription billing V1 (design spec §6.1). Initiates a Razorpay Subscription checkout — never
 * activates anything itself. Activation happens only from a verified webhook
 * (see {@code RazorpayWebhookDispatcher}, Task 7), never from this call's own return value.
 */
@Service
public class BillingCheckoutService {

    private final PlanRepository planRepository;
    private final BillingPriceRepository billingPriceRepository;
    private final SubscriptionOrderRepository subscriptionOrderRepository;
    private final RazorpaySubscriptionGateway gateway;
    private final RazorpayProperties properties;

    public BillingCheckoutService(PlanRepository planRepository, BillingPriceRepository billingPriceRepository,
                                   SubscriptionOrderRepository subscriptionOrderRepository,
                                   RazorpaySubscriptionGateway gateway, RazorpayProperties properties) {
        this.planRepository = planRepository;
        this.billingPriceRepository = billingPriceRepository;
        this.subscriptionOrderRepository = subscriptionOrderRepository;
        this.gateway = gateway;
        this.properties = properties;
    }

    @Transactional
    public CheckoutResponseDto checkout(UUID userId, String planCode, String billingCycle) {
        if (!gateway.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Billing is not available yet.");
        }

        Plan plan = planRepository.findByCode(planCode)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Unknown plan code: " + planCode));
        BillingPrice price = billingPriceRepository.findByPlanIdAndBillingCycleAndActiveTrue(plan.getId(), billingCycle)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "No active price for " + planCode + "/" + billingCycle));
        if (price.getRazorpayPlanId() == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "This plan is not yet set up for checkout (missing Razorpay plan id).");
        }

        RazorpaySubscriptionDto razorpaySubscription = gateway.createSubscription(
                price.getRazorpayPlanId(), billingCycle,
                Map.of("fynoraUserId", userId.toString(), "planCode", planCode, "billingCycle", billingCycle));

        SubscriptionOrder order = new SubscriptionOrder();
        order.setUserId(userId);
        order.setPlanId(plan.getId());
        order.setBillingCycle(billingCycle);
        order.setRazorpaySubscriptionId(razorpaySubscription.id());
        order.setStatus(SubscriptionOrder.STATUS_PENDING);
        order.setAmount(price.getPrice());
        subscriptionOrderRepository.save(order);

        return new CheckoutResponseDto(razorpaySubscription.id(), properties.getKeyId());
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=BillingCheckoutServiceTest
```
Expected: PASS.

- [ ] **Step 6: Create `BillingController.java`**

```java
package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.BillingDtos.CheckoutRequest;
import com.finora.dto.BillingDtos.CheckoutResponseDto;
import com.finora.security.CurrentUser;
import com.finora.service.BillingCheckoutService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Subscription billing V1 (design spec §7). User-initiated billing actions -- distinct from
 * {@code AdminSubscriptionController} (admin-initiated) and {@code BillingHistoryController}
 * (read-only history), matching this codebase's existing view/manage/history separation.
 */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private final BillingCheckoutService billingCheckoutService;
    private final CurrentUser currentUser;

    public BillingController(BillingCheckoutService billingCheckoutService, CurrentUser currentUser) {
        this.billingCheckoutService = billingCheckoutService;
        this.currentUser = currentUser;
    }

    @PostMapping("/checkout")
    public ApiResponse<CheckoutResponseDto> checkout(@Valid @RequestBody CheckoutRequest request) {
        return ApiResponse.ok(billingCheckoutService.checkout(currentUser.id(), request.planCode(), request.billingCycle()));
    }
}
```

- [ ] **Step 7: Write the failing integration test**

```java
package com.finora.controller;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.BillingPrice;
import com.finora.entity.Plan;
import com.finora.entity.User;
import com.finora.integrations.razorpay.RazorpaySubscriptionDto;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import com.finora.repository.BillingPriceRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class BillingControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private BillingPriceRepository billingPriceRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;

    @MockitoBean private RazorpaySubscriptionGateway gateway;

    private User createUser() {
        User user = new User();
        user.setEmail("billing-checkout-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Checkout IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void checkoutCreatesARazorpaySubscriptionAndReturnsItsId() {
        User user = createUser();
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        BillingPrice price = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(premium.getId(), BillingPrice.CYCLE_MONTHLY)
                .orElseThrow();
        price.setRazorpayPlanId("plan_test_" + UUID.randomUUID());
        billingPriceRepository.save(price);

        when(gateway.isConfigured()).thenReturn(true);
        when(gateway.createSubscription(eq(price.getRazorpayPlanId()), eq("MONTHLY"), anyMap()))
                .thenReturn(new RazorpaySubscriptionDto("sub_test_123", "created"));

        HttpEntity<String> request = new HttpEntity<>(
                "{\"planCode\":\"PREMIUM\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/billing/checkout", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("sub_test_123");
    }
}
```

- [ ] **Step 8: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=BillingControllerIT
```
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/finora/service/BillingCheckoutService.java \
        backend/src/main/java/com/finora/controller/BillingController.java \
        backend/src/main/java/com/finora/dto/BillingDtos.java \
        backend/src/test/java/com/finora/service/BillingCheckoutServiceTest.java \
        backend/src/test/java/com/finora/controller/BillingControllerIT.java
git commit -m "feat(billing): add self-service checkout endpoint"
```

---

## Task 7: Webhook — activation completes checkout

**Files:**
- Modify: `backend/src/main/java/com/finora/service/RazorpayWebhookDispatcher.java`
- Test: `backend/src/test/java/com/finora/service/RazorpayWebhookDispatcherIT.java`

**Interfaces:**
- Consumes: `SubscriptionOrderRepository`, `SubscriptionRepository`, `PlanRepository` (all Task 1/existing),
  `SubscriptionEventRepository` (existing).
- Produces: activation handling wired into `dispatch()` — this and Tasks 8-10 build up the same test
  file and the same `dispatch()` method, one event type each.

This is the point where `RazorpayWebhookDispatcher` needs real dependencies — refactor its
constructor now rather than in Task 8, since every remaining webhook-handling task adds to the same
class.

- [ ] **Step 1: Write the failing test**

```java
package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.*;
import com.finora.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpayWebhookDispatcherIT extends AbstractIntegrationTest {

    @Autowired private RazorpayWebhookDispatcher dispatcher;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private SubscriptionOrderRepository subscriptionOrderRepository;
    @Autowired private SubscriptionEventRepository subscriptionEventRepository;
    @Autowired private SubscriptionService subscriptionService;

    private User createUser() {
        User user = new User();
        user.setEmail("webhook-activate-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Webhook Activation IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    @Test
    void activationCompletesTheMatchingPendingOrderAndActivatesTheUsersSubscription() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId()); // every user already has one
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();

        SubscriptionOrder order = new SubscriptionOrder();
        order.setUserId(user.getId());
        order.setPlanId(premium.getId());
        order.setBillingCycle("MONTHLY");
        order.setRazorpaySubscriptionId(razorpaySubscriptionId);
        order.setStatus(SubscriptionOrder.STATUS_PENDING);
        order.setAmount(new BigDecimal("799.00"));
        subscriptionOrderRepository.save(order);

        Map<String, Object> payload = Map.of(
                "subscription", Map.of("entity", Map.of(
                        "id", razorpaySubscriptionId,
                        "current_end", 1893456000L))); // synthetic-ok: arbitrary future epoch second, not a real identifier

        dispatcher.dispatch("subscription.activated", payload);

        SubscriptionOrder completed = subscriptionOrderRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(SubscriptionOrder.STATUS_COMPLETED);
        assertThat(completed.getCompletedAt()).isNotNull();

        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        assertThat(subscription.getPlanId()).isEqualTo(premium.getId());
        assertThat(subscription.getBillingCycle()).isEqualTo("MONTHLY");
        assertThat(subscription.getRazorpaySubscriptionId()).isEqualTo(razorpaySubscriptionId);
        assertThat(subscription.getPaymentProvider()).isEqualTo("RAZORPAY");
        assertThat(subscription.getStatus()).isEqualTo(Subscription.STATUS_ACTIVE);

        List<SubscriptionEvent> events = subscriptionEventRepository.findAll().stream()
                .filter(e -> e.getSubscriptionId().equals(subscription.getId())).toList();
        assertThat(events).anyMatch(e -> e.getEventType().equals(SubscriptionEvent.SUBSCRIPTION_CREATED));
    }

    @Test
    void activationForAnUnknownRazorpaySubscriptionIdIsIgnoredNotThrown() {
        Map<String, Object> payload = Map.of(
                "subscription", Map.of("entity", Map.of("id", "sub_never_created", "current_end", 0L)));

        dispatcher.dispatch("subscription.activated", payload); // must not throw
    }
}
```

*(`SubscriptionEventRepository` needs a `findAll()` — it already extends `JpaRepository`, which
provides it; no change needed there.)*

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=RazorpayWebhookDispatcherIT
```
Expected: FAIL.

- [ ] **Step 3: Update `RazorpayWebhookDispatcher.java`**

Replace the whole class:

```java
package com.finora.service;

import com.finora.entity.Plan;
import com.finora.entity.Subscription;
import com.finora.entity.SubscriptionEvent;
import com.finora.entity.SubscriptionOrder;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionEventRepository;
import com.finora.repository.SubscriptionOrderRepository;
import com.finora.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

/**
 * Subscription billing V1 (design spec §5). One method per Razorpay event type this application
 * acts on. Named for what it does, not {@code *Service}: a single-purpose collaborator used only by
 * {@link com.finora.controller.RazorpayWebhookController}.
 */
@Component
public class RazorpayWebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookDispatcher.class);

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionOrderRepository subscriptionOrderRepository;
    private final SubscriptionEventRepository subscriptionEventRepository;
    private final PlanRepository planRepository;

    public RazorpayWebhookDispatcher(SubscriptionRepository subscriptionRepository,
                                      SubscriptionOrderRepository subscriptionOrderRepository,
                                      SubscriptionEventRepository subscriptionEventRepository,
                                      PlanRepository planRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionOrderRepository = subscriptionOrderRepository;
        this.subscriptionEventRepository = subscriptionEventRepository;
        this.planRepository = planRepository;
    }

    public void dispatch(String eventType, Map<String, Object> payload) {
        switch (eventType) {
            case "subscription.authenticated", "subscription.activated" -> handleActivated(payload);
            default -> log.info("Razorpay webhook event '{}' received but not handled in V1.", eventType);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> subscriptionEntity(Map<String, Object> payload) {
        Map<String, Object> subscription = (Map<String, Object>) payload.get("subscription");
        return subscription == null ? Map.of() : (Map<String, Object>) subscription.get("entity");
    }

    /** spec §6.1 step 5 / §5. Completes checkout: marks the matching {@link SubscriptionOrder}
     *  COMPLETED and mutates the user's single {@link Subscription} row in place — the same
     *  mutate-in-place model {@code SubscriptionService.changePlan} already uses, never a second
     *  row (see design spec §6.5's DB-constraint discussion). */
    @Transactional
    void handleActivated(Map<String, Object> payload) {
        Map<String, Object> entity = subscriptionEntity(payload);
        String razorpaySubscriptionId = (String) entity.get("id");
        if (razorpaySubscriptionId == null) return;

        Optional<SubscriptionOrder> maybeOrder = subscriptionOrderRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId);
        if (maybeOrder.isEmpty()) {
            log.warn("subscription.activated for unknown razorpaySubscriptionId {}, ignoring.", razorpaySubscriptionId);
            return;
        }
        SubscriptionOrder order = maybeOrder.get();
        order.setStatus(SubscriptionOrder.STATUS_COMPLETED);
        order.setCompletedAt(Instant.now());

        Subscription subscription = subscriptionRepository.findActiveOrTrial(order.getUserId())
                .orElseThrow(() -> new IllegalStateException(
                        "User " + order.getUserId() + " has a pending order but no subscription row " +
                        "-- provisionFreeSubscription should have created one at signup."));
        Plan plan = planRepository.findById(order.getPlanId()).orElseThrow();

        subscription.setPlanId(plan.getId());
        subscription.setBillingCycle(order.getBillingCycle());
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setAutoRenew(true);
        Object currentEnd = entity.get("current_end");
        if (currentEnd instanceof Number n) {
            subscription.setRenewalDate(LocalDate.ofInstant(Instant.ofEpochSecond(n.longValue()), ZoneOffset.UTC));
        }
        subscriptionRepository.save(subscription);

        SubscriptionEvent event = new SubscriptionEvent();
        event.setSubscriptionId(subscription.getId());
        event.setEventType(SubscriptionEvent.SUBSCRIPTION_CREATED);
        event.setMetadata(Map.of("planCode", plan.getCode(), "billingCycle", order.getBillingCycle(),
                "razorpaySubscriptionId", razorpaySubscriptionId));
        subscriptionEventRepository.save(event);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=RazorpayWebhookDispatcherIT
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/service/RazorpayWebhookDispatcher.java \
        backend/src/test/java/com/finora/service/RazorpayWebhookDispatcherIT.java
git commit -m "feat(billing): activate subscription and complete checkout on Razorpay activation webhook"
```

---

## Task 8: Webhook — renewal (`subscription.charged`)

**Files:**
- Modify: `backend/src/main/java/com/finora/service/RazorpayWebhookDispatcher.java`
- Modify: `backend/src/test/java/com/finora/service/RazorpayWebhookDispatcherIT.java`

**Interfaces:**
- Consumes: `PaymentRepository` (existing), `BillingPriceRepository` (Task 1).
- Produces: `handleCharged` wired into `dispatch()`.

- [ ] **Step 1: Add the failing test to `RazorpayWebhookDispatcherIT`**

```java
    @Autowired private BillingPriceRepository billingPriceRepository;
    @Autowired private PaymentRepository paymentRepository;

    @Test
    void chargedInsertsAPaymentRowAndExtendsTheRenewalDate() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        BillingPrice plusMonthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(plus.getId(), "MONTHLY").orElseThrow();
        String razorpayPlanId = "plan_test_" + UUID.randomUUID();
        plusMonthly.setRazorpayPlanId(razorpayPlanId);
        billingPriceRepository.save(plusMonthly);
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();

        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPlanId(plus.getId());
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        Map<String, Object> payload = Map.of(
                "payment", Map.of("entity", Map.of("id", "pay_test_123", "amount", 79900)),
                "subscription", Map.of("entity", Map.of(
                        "id", razorpaySubscriptionId, "plan_id", razorpayPlanId, "current_end", 1893456000L))); // synthetic-ok: fixture epoch second

        dispatcher.dispatch("subscription.charged", payload);

        List<Payment> payments = paymentRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).getStatus()).isEqualTo(Payment.STATUS_SUCCESS);
        assertThat(payments.get(0).getProviderTransactionId()).isEqualTo("pay_test_123");
        assertThat(payments.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("799.00"));

        Subscription reloaded = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Subscription.STATUS_ACTIVE);
    }

    @Test
    void chargedReconcilesPlanWhenTheChargedRazorpayPlanIdDiffersFromTheLocalPlan() {
        // Simulates a scheduled downgrade (Plan 2, spec §6.4) taking effect: Razorpay charges the
        // NEW (lower) plan's razorpay_plan_id at cycle end, and this webhook is what notices.
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        BillingPrice plusMonthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(plus.getId(), "MONTHLY").orElseThrow();
        String newRazorpayPlanId = "plan_test_" + UUID.randomUUID();
        plusMonthly.setRazorpayPlanId(newRazorpayPlanId);
        billingPriceRepository.save(plusMonthly);
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();

        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPlanId(premium.getId()); // still Premium locally
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        Map<String, Object> payload = Map.of(
                "payment", Map.of("entity", Map.of("id", "pay_test_456", "amount", 39900)),
                "subscription", Map.of("entity", Map.of(
                        "id", razorpaySubscriptionId, "plan_id", newRazorpayPlanId, "current_end", 1893456000L))); // synthetic-ok: fixture epoch second

        dispatcher.dispatch("subscription.charged", payload);

        Subscription reloaded = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        assertThat(reloaded.getPlanId()).isEqualTo(plus.getId());
    }
```

Add the matching imports (`Payment`, `PaymentRepository`, `BillingPrice`, `BillingPriceRepository`) to
the test file's existing `import com.finora.entity.*;` / `import com.finora.repository.*;` wildcard
imports — no change needed there since they're already wildcarded.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=RazorpayWebhookDispatcherIT
```
Expected: FAIL on the two new tests.

- [ ] **Step 3: Add `handleCharged` to `RazorpayWebhookDispatcher.java`**

Add the two new fields, update the constructor to accept and assign them, add the new `case`, and
add the new method.

Fields (alongside the existing four):
```java
    private final BillingPriceRepository billingPriceRepository;
    private final PaymentRepository paymentRepository;
```

Constructor (replace the whole thing — two new parameters at the end, matched by two new
assignments):
```java
    public RazorpayWebhookDispatcher(SubscriptionRepository subscriptionRepository,
                                      SubscriptionOrderRepository subscriptionOrderRepository,
                                      SubscriptionEventRepository subscriptionEventRepository,
                                      PlanRepository planRepository,
                                      BillingPriceRepository billingPriceRepository,
                                      PaymentRepository paymentRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionOrderRepository = subscriptionOrderRepository;
        this.subscriptionEventRepository = subscriptionEventRepository;
        this.planRepository = planRepository;
        this.billingPriceRepository = billingPriceRepository;
        this.paymentRepository = paymentRepository;
    }
```

New `case`, added to the existing `switch` in `dispatch()` right before `default`:
```java
            case "subscription.charged" -> handleCharged(payload);
```

```java
    /** spec §5, §6.4. Renewal is otherwise fully passive -- this is also the reconciliation point
     *  that makes a scheduled downgrade (Plan 2) actually take effect: if the charged Razorpay plan
     *  id no longer matches what BillingPrice says the local plan should be billed under, the local
     *  plan_id is corrected to match. */
    @SuppressWarnings("unchecked")
    @Transactional
    void handleCharged(Map<String, Object> payload) {
        Map<String, Object> subscriptionEntity = subscriptionEntity(payload);
        String razorpaySubscriptionId = (String) subscriptionEntity.get("id");
        if (razorpaySubscriptionId == null) return;

        Optional<Subscription> maybeSubscription = subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId);
        if (maybeSubscription.isEmpty()) {
            log.warn("subscription.charged for unknown razorpaySubscriptionId {}, ignoring.", razorpaySubscriptionId);
            return;
        }
        Subscription subscription = maybeSubscription.get();

        String chargedRazorpayPlanId = (String) subscriptionEntity.get("plan_id");
        if (chargedRazorpayPlanId != null) {
            billingPriceRepository.findAll().stream()
                    .filter(bp -> chargedRazorpayPlanId.equals(bp.getRazorpayPlanId()))
                    .findFirst()
                    .ifPresent(bp -> {
                        subscription.setPlanId(bp.getPlanId());
                        subscription.setBillingCycle(bp.getBillingCycle());
                    });
        }
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        Object currentEnd = subscriptionEntity.get("current_end");
        if (currentEnd instanceof Number n) {
            subscription.setRenewalDate(LocalDate.ofInstant(Instant.ofEpochSecond(n.longValue()), ZoneOffset.UTC));
        }
        subscriptionRepository.save(subscription);

        Map<String, Object> paymentEntity = (Map<String, Object>) payload.get("payment");
        paymentEntity = paymentEntity == null ? Map.of() : (Map<String, Object>) paymentEntity.get("entity");
        Payment payment = new Payment();
        payment.setUserId(subscription.getUserId());
        payment.setSubscriptionId(subscription.getId());
        payment.setProvider("RAZORPAY");
        payment.setStatus(Payment.STATUS_SUCCESS);
        Object amountPaise = paymentEntity.get("amount");
        payment.setAmount(amountPaise instanceof Number n
                ? java.math.BigDecimal.valueOf(n.longValue(), 2)
                : java.math.BigDecimal.ZERO);
        payment.setCurrency("INR");
        payment.setProviderTransactionId((String) paymentEntity.get("id"));
        paymentRepository.save(payment);

        SubscriptionEvent event = new SubscriptionEvent();
        event.setSubscriptionId(subscription.getId());
        event.setEventType(SubscriptionEvent.SUBSCRIPTION_RENEWED);
        event.setMetadata(Map.of("razorpaySubscriptionId", razorpaySubscriptionId));
        subscriptionEventRepository.save(event);
    }
```

Add the new imports at the top: `com.finora.entity.Payment`, `com.finora.entity.BillingPrice` (only
if referenced by type — it isn't here, `billingPriceRepository.findAll()` returns it inferred),
`com.finora.repository.BillingPriceRepository`, `com.finora.repository.PaymentRepository`.

*Razorpay's webhook payload nests the payment amount in minor units (paise) — `BigDecimal.valueOf(n.longValue(), 2)`
converts e.g. `79900` to `799.00`. Verify this against a real sandbox payload during the §3/§10
sandbox validation pass; the payload shape here is Razorpay's documented convention, not yet
confirmed against a live event.*

- [ ] **Step 4: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=RazorpayWebhookDispatcherIT
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/service/RazorpayWebhookDispatcher.java \
        backend/src/test/java/com/finora/service/RazorpayWebhookDispatcherIT.java
git commit -m "feat(billing): record payments and reconcile plan on Razorpay renewal webhook"
```

---

## Task 9: Webhook — `subscription.pending`

**Files:**
- Modify: `backend/src/main/java/com/finora/service/RazorpayWebhookDispatcher.java`
- Modify: `backend/src/test/java/com/finora/service/RazorpayWebhookDispatcherIT.java`

**Interfaces:**
- Produces: `handlePending` wired into `dispatch()`.

- [ ] **Step 1: Add the failing test**

```java
    @Test
    void pendingSetsStatusToPastDueButDoesNotRevokeAccess() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();
        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        Map<String, Object> payload = Map.of(
                "subscription", Map.of("entity", Map.of("id", razorpaySubscriptionId)));

        dispatcher.dispatch("subscription.pending", payload);

        Subscription reloaded = subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Subscription.STATUS_PAST_DUE);

        List<Payment> payments = paymentRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).getStatus()).isEqualTo(Payment.STATUS_PENDING);
    }
```

Note: `findActiveOrTrial` won't find a `PAST_DUE` subscription after the transition (it only matches
`ACTIVE`/`TRIAL`) — the reload above deliberately uses `findByRazorpaySubscriptionId`, which has no
such filter, matching how the real webhook handlers themselves must look subscriptions up (they
can't assume `ACTIVE`/`TRIAL` going in).

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=RazorpayWebhookDispatcherIT
```
Expected: FAIL.

- [ ] **Step 3: Add `handlePending`**

```java
            case "subscription.pending" -> handlePending(payload);
```

```java
    /** spec §5. PAST_DUE, not a revoked state -- Razorpay's own retry is in progress and, per its
     *  documented behavior, does not itself affect access (design spec §3). */
    @Transactional
    void handlePending(Map<String, Object> payload) {
        Map<String, Object> entity = subscriptionEntity(payload);
        String razorpaySubscriptionId = (String) entity.get("id");
        if (razorpaySubscriptionId == null) return;

        Optional<Subscription> maybeSubscription = subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId);
        if (maybeSubscription.isEmpty()) return;
        Subscription subscription = maybeSubscription.get();
        subscription.setStatus(Subscription.STATUS_PAST_DUE);
        subscriptionRepository.save(subscription);

        Payment payment = new Payment();
        payment.setUserId(subscription.getUserId());
        payment.setSubscriptionId(subscription.getId());
        payment.setProvider("RAZORPAY");
        payment.setStatus(Payment.STATUS_PENDING);
        payment.setAmount(java.math.BigDecimal.ZERO); // retry attempt, amount not in this webhook's payload
        payment.setCurrency("INR");
        paymentRepository.save(payment);
    }
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=RazorpayWebhookDispatcherIT
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/service/RazorpayWebhookDispatcher.java \
        backend/src/test/java/com/finora/service/RazorpayWebhookDispatcherIT.java
git commit -m "feat(billing): mark subscription PAST_DUE on Razorpay pending webhook, access unchanged"
```

---

## Task 10: Webhook — `subscription.halted`

**Files:**
- Modify: `backend/src/main/java/com/finora/service/RazorpayWebhookDispatcher.java`
- Modify: `backend/src/test/java/com/finora/service/RazorpayWebhookDispatcherIT.java`

**Interfaces:**
- Produces: `handleHalted` wired into `dispatch()`.

- [ ] **Step 1: Add the failing test**

```java
    @Test
    void haltedDowngradesToFreeAndMarksTheOutstandingPaymentFailed() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        Plan free = planRepository.findByCode("FREE").orElseThrow();
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();
        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPlanId(premium.getId());
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscription.setStatus(Subscription.STATUS_PAST_DUE);
        subscriptionRepository.save(subscription);

        Payment pendingPayment = new Payment();
        pendingPayment.setUserId(user.getId());
        pendingPayment.setSubscriptionId(subscription.getId());
        pendingPayment.setProvider("RAZORPAY");
        pendingPayment.setStatus(Payment.STATUS_PENDING);
        pendingPayment.setAmount(new BigDecimal("799.00"));
        pendingPayment.setCurrency("INR");
        paymentRepository.save(pendingPayment);

        Map<String, Object> payload = Map.of(
                "subscription", Map.of("entity", Map.of("id", razorpaySubscriptionId)));

        dispatcher.dispatch("subscription.halted", payload);

        Subscription reloaded = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        assertThat(reloaded.getPlanId()).isEqualTo(free.getId());
        assertThat(reloaded.getStatus()).isEqualTo(Subscription.STATUS_ACTIVE);
        assertThat(reloaded.getPaymentProvider()).isNull();
        assertThat(reloaded.getRazorpaySubscriptionId()).isNull();

        List<Payment> payments = paymentRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        assertThat(payments).anyMatch(p -> p.getStatus().equals(Payment.STATUS_FAILED));
    }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=RazorpayWebhookDispatcherIT
```
Expected: FAIL.

- [ ] **Step 3: Add `handleHalted`**

```java
            case "subscription.halted" -> handleHalted(payload);
```

```java
    /** spec §5, §9. Retries exhausted -- the real access-revoking signal (unlike "pending"). Marks
     *  any outstanding PENDING payment for this subscription FAILED (the retry sequence is over,
     *  it never will succeed now) and downgrades straight to FREE -- V1 does not build a "resume a
     *  halted subscription" flow (spec §9); the user re-subscribes via ordinary checkout. */
    @Transactional
    void handleHalted(Map<String, Object> payload) {
        Map<String, Object> entity = subscriptionEntity(payload);
        String razorpaySubscriptionId = (String) entity.get("id");
        if (razorpaySubscriptionId == null) return;

        Optional<Subscription> maybeSubscription = subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId);
        if (maybeSubscription.isEmpty()) return;
        Subscription subscription = maybeSubscription.get();

        paymentRepository.findBySubscriptionIdOrderByCreatedAtDesc(subscription.getId()).stream()
                .filter(p -> Payment.STATUS_PENDING.equals(p.getStatus()))
                .forEach(p -> { p.setStatus(Payment.STATUS_FAILED); paymentRepository.save(p); });

        Plan free = planRepository.findByCode("FREE")
                .orElseThrow(() -> new IllegalStateException("FREE plan missing -- V99 seed data not applied"));
        subscription.setPlanId(free.getId());
        subscription.setBillingCycle(null);
        subscription.setRazorpaySubscriptionId(null);
        subscription.setPaymentProvider(null);
        subscription.setAutoRenew(true);
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscriptionRepository.save(subscription);

        SubscriptionEvent event = new SubscriptionEvent();
        event.setSubscriptionId(subscription.getId());
        event.setEventType(SubscriptionEvent.SUBSCRIPTION_CANCELLED);
        event.setMetadata(Map.of("reason", "PAYMENT_FAILURE"));
        subscriptionEventRepository.save(event);
    }
```

`PaymentRepository` needs one more finder — add it now:

```java
    List<Payment> findBySubscriptionIdOrderByCreatedAtDesc(UUID subscriptionId);
```

(In `backend/src/main/java/com/finora/repository/PaymentRepository.java`, alongside its existing
`findByUserIdOrderByCreatedAtDesc`.)

- [ ] **Step 4: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=RazorpayWebhookDispatcherIT
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/service/RazorpayWebhookDispatcher.java \
        backend/src/main/java/com/finora/repository/PaymentRepository.java \
        backend/src/test/java/com/finora/service/RazorpayWebhookDispatcherIT.java
git commit -m "feat(billing): downgrade to Free and fail outstanding payment on Razorpay halted webhook"
```

---

## Task 11: Cancellation

**Files:**
- Modify: `backend/src/main/java/com/finora/service/RazorpayWebhookDispatcher.java`
- Modify: `backend/src/main/java/com/finora/controller/BillingController.java`
- Modify: `backend/src/main/java/com/finora/dto/BillingDtos.java` (nothing new needed — no request body)
- Test: extend `BillingControllerIT`
- Test: extend `RazorpayWebhookDispatcherIT`

**Interfaces:**
- Produces: `BillingCheckoutService`-adjacent method (added to that class, not a new one — it already
  owns the Razorpay-facing user billing actions) `cancel(UUID userId)`. `POST /api/v1/billing/cancel`.
  `handleCancelled` wired into `dispatch()`.

- [ ] **Step 1: Add the failing webhook test to `RazorpayWebhookDispatcherIT`**

```java
    @Test
    void cancelledSetsStatusToCancelledWithoutRevokingAccessYet() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();
        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscription.setAutoRenew(false);
        subscriptionRepository.save(subscription);

        Map<String, Object> payload = Map.of(
                "subscription", Map.of("entity", Map.of("id", razorpaySubscriptionId)));

        dispatcher.dispatch("subscription.cancelled", payload);

        Subscription reloaded = subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Subscription.STATUS_CANCELLED);
    }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=RazorpayWebhookDispatcherIT
```
Expected: FAIL.

- [ ] **Step 3: Add `handleCancelled`**

```java
            case "subscription.cancelled" -> handleCancelled(payload);
```

```java
    /** spec §5, §6.3. Does not itself downgrade to Free -- that happens at
     * {@code current_period_end}, via {@code SubscriptionReconciliationSweepService} (Task 12), not
     * from this webhook alone (a missed webhook must not leave paid access active forever). */
    @Transactional
    void handleCancelled(Map<String, Object> payload) {
        Map<String, Object> entity = subscriptionEntity(payload);
        String razorpaySubscriptionId = (String) entity.get("id");
        if (razorpaySubscriptionId == null) return;

        subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).ifPresent(subscription -> {
            subscription.setStatus(Subscription.STATUS_CANCELLED);
            subscriptionRepository.save(subscription);

            SubscriptionEvent event = new SubscriptionEvent();
            event.setSubscriptionId(subscription.getId());
            event.setEventType(SubscriptionEvent.SUBSCRIPTION_CANCELLED);
            event.setMetadata(Map.of("reason", "USER_INITIATED"));
            subscriptionEventRepository.save(event);
        });
    }
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=RazorpayWebhookDispatcherIT
```
Expected: PASS.

- [ ] **Step 5: Write the failing endpoint test**

Add to `BillingControllerIT.java`:

```java
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private SubscriptionRepository subscriptionRepository;

    @Test
    void cancelCallsRazorpayAndSetsAutoRenewFalse() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();
        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/billing/cancel", new HttpEntity<>(null, bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(gateway).cancelSubscription(eq(razorpaySubscriptionId), eq(true));

        var reloaded = subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).orElseThrow();
        assertThat(reloaded.isAutoRenew()).isFalse();
    }
```

- [ ] **Step 6: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=BillingControllerIT
```
Expected: FAIL — no `/cancel` endpoint yet.

- [ ] **Step 7: Add `cancel` to `BillingCheckoutService` and `BillingController`**

In `BillingCheckoutService.java`, add:

```java
    @Transactional
    public void cancel(UUID userId) {
        Subscription subscription = subscriptionRepository.findActiveOrTrial(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No active subscription."));
        if (subscription.getRazorpaySubscriptionId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This subscription has no billing to cancel.");
        }
        gateway.cancelSubscription(subscription.getRazorpaySubscriptionId(), true);
        subscription.setAutoRenew(false);
        subscriptionRepository.save(subscription);
    }
```

This needs `SubscriptionRepository` injected. Update `BillingCheckoutService`'s field list and
constructor (it did not need this dependency for checkout alone):

```java
    private final PlanRepository planRepository;
    private final BillingPriceRepository billingPriceRepository;
    private final SubscriptionOrderRepository subscriptionOrderRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final RazorpaySubscriptionGateway gateway;
    private final RazorpayProperties properties;

    public BillingCheckoutService(PlanRepository planRepository, BillingPriceRepository billingPriceRepository,
                                   SubscriptionOrderRepository subscriptionOrderRepository,
                                   SubscriptionRepository subscriptionRepository,
                                   RazorpaySubscriptionGateway gateway, RazorpayProperties properties) {
        this.planRepository = planRepository;
        this.billingPriceRepository = billingPriceRepository;
        this.subscriptionOrderRepository = subscriptionOrderRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.gateway = gateway;
        this.properties = properties;
    }
```

Add `import com.finora.repository.SubscriptionRepository;` at the top of the file.

In `BillingController.java`, add:

```java
    @PostMapping("/cancel")
    public ApiResponse<Void> cancel() {
        billingCheckoutService.cancel(currentUser.id());
        return ApiResponse.ok(null, "Cancelled");
    }
```

- [ ] **Step 8: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=BillingControllerIT
```
Expected: PASS.

- [ ] **Step 9: Update `BillingCheckoutServiceTest`'s constructor call**

In `BillingCheckoutServiceTest.java`, add the new mock field and pass it in the one
`new BillingCheckoutService(...)` call (in `setUp()`) — none of Task 6's tests exercise `cancel`, so
the mock needs no stubbing:

```java
    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
```

```java
        service = new BillingCheckoutService(planRepository, billingPriceRepository,
                subscriptionOrderRepository, subscriptionRepository, gateway, properties);
```

Add `import com.finora.repository.SubscriptionRepository;` to the test file's imports.

- [ ] **Step 10: Run the full test file to confirm nothing broke**

```bash
cd backend && ./mvnw test -Dtest=BillingCheckoutServiceTest,BillingControllerIT,RazorpayWebhookDispatcherIT
```
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/java/com/finora/service/BillingCheckoutService.java \
        backend/src/main/java/com/finora/service/RazorpayWebhookDispatcher.java \
        backend/src/main/java/com/finora/controller/BillingController.java \
        backend/src/test/java/com/finora/service/BillingCheckoutServiceTest.java \
        backend/src/test/java/com/finora/controller/BillingControllerIT.java \
        backend/src/test/java/com/finora/service/RazorpayWebhookDispatcherIT.java
git commit -m "feat(billing): add user-initiated cancellation, access continues to period end"
```

---

## Task 12: Reconciliation sweep

**Files:**
- Create: `backend/src/main/java/com/finora/service/SubscriptionReconciliationSweepService.java`
- Test: `backend/src/test/java/com/finora/service/SubscriptionReconciliationSweepServiceIT.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/resources/application-test.yml`
- Modify: `backend/src/main/java/com/finora/repository/SubscriptionRepository.java`

**Interfaces:**
- Produces: `SubscriptionReconciliationSweepService.sweep() : int` (count downgraded) — tests call
  this directly, matching every other `*SweepService` in this codebase. `scheduledSweep()` is the
  `@Scheduled` wrapper, gated by a flag, disabled under test.

- [ ] **Step 1: Add the repository query**

In `SubscriptionRepository.java`:

```java
    /** SubscriptionReconciliationSweepService (design spec §6.3) -- the safety net for a missed
     *  {@code subscription.cancelled} webhook. Not scoped to {@code ACTIVE}/{@code TRIAL} on
     *  purpose: {@code status='CANCELLED'} is exactly the state a cancellation already reached. */
    @Query("SELECT s FROM Subscription s WHERE s.autoRenew = false AND s.status = 'CANCELLED' " +
           "AND s.renewalDate < :cutoff")
    List<Subscription> findCancelledSubscriptionsPastPeriodEnd(@Param("cutoff") LocalDate cutoff);
```

Add `import java.time.LocalDate;` if not already present in that file (it is not — check before
adding, to avoid a duplicate-import compile error).

- [ ] **Step 2: Write the failing test**

```java
package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Plan;
import com.finora.entity.Subscription;
import com.finora.entity.User;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionReconciliationSweepServiceIT extends AbstractIntegrationTest {

    @Autowired private SubscriptionReconciliationSweepService sweepService;
    @Autowired private UserRepository userRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private PlanRepository planRepository;

    private User createUser() {
        User user = new User();
        user.setEmail("reconcile-sweep-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Reconciliation Sweep IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    @Test
    void downgradesACancelledSubscriptionPastItsPeriodEndToFree() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPlanId(premium.getId());
        subscription.setStatus(Subscription.STATUS_CANCELLED);
        subscription.setAutoRenew(false);
        subscription.setRenewalDate(LocalDate.now().minusDays(1));
        subscriptionRepository.save(subscription);

        int downgraded = sweepService.sweep();

        assertThat(downgraded).isGreaterThanOrEqualTo(1);
        Subscription reloaded = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        Plan free = planRepository.findByCode("FREE").orElseThrow();
        assertThat(reloaded.getPlanId()).isEqualTo(free.getId());
        assertThat(reloaded.getStatus()).isEqualTo(Subscription.STATUS_ACTIVE);
    }

    @Test
    void leavesACancelledSubscriptionAloneBeforeItsPeriodEnd() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPlanId(premium.getId());
        subscription.setStatus(Subscription.STATUS_CANCELLED);
        subscription.setAutoRenew(false);
        subscription.setRenewalDate(LocalDate.now().plusDays(5));
        subscriptionRepository.save(subscription);

        sweepService.sweep();

        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getPlanId()).isEqualTo(premium.getId());
        assertThat(reloaded.getStatus()).isEqualTo(Subscription.STATUS_CANCELLED);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=SubscriptionReconciliationSweepServiceIT
```
Expected: FAIL — class doesn't exist.

- [ ] **Step 4: Create `SubscriptionReconciliationSweepService.java`**

```java
package com.finora.service;

import com.finora.entity.Plan;
import com.finora.entity.Subscription;
import com.finora.entity.SubscriptionEvent;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionEventRepository;
import com.finora.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Subscription billing V1 (design spec §6.3). Safety net, not the primary mechanism — a cancelled
 * subscription is normally downgraded to Free by {@code RazorpayWebhookDispatcher.handleCancelled}'s
 * webhook path reaching {@code current_period_end} naturally (no further {@code subscription.charged}
 * arrives). This sweep exists because "we stopped hearing an event" is not itself a reliable signal:
 * Razorpay disables a webhook endpoint after 24h of failed deliveries, so a missed webhook could
 * otherwise leave paid access active indefinitely with nothing to notice. Same shape as
 * {@code AccountPurgeSweepService}/{@code NetWorthSnapshotSweepService}: {@code fixedDelay}, gated by
 * a flag {@code application-test.yml} turns off, tests call {@link #sweep()} directly.
 */
@Service
public class SubscriptionReconciliationSweepService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionReconciliationSweepService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionEventRepository subscriptionEventRepository;
    private final PlanRepository planRepository;

    @Value("${app.subscription-reconciliation.sweep.enabled:true}")
    private boolean sweepEnabled;

    public SubscriptionReconciliationSweepService(SubscriptionRepository subscriptionRepository,
                                                   SubscriptionEventRepository subscriptionEventRepository,
                                                   PlanRepository planRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionEventRepository = subscriptionEventRepository;
        this.planRepository = planRepository;
    }

    @Scheduled(fixedDelayString = "${app.subscription-reconciliation.sweep.interval-ms:3600000}",
            initialDelayString = "${app.subscription-reconciliation.sweep.initial-delay-ms:300000}")
    public void scheduledSweep() {
        if (!sweepEnabled) return;
        int downgraded = sweep();
        if (downgraded > 0) {
            log.info("Subscription reconciliation sweep: {} subscription(s) downgraded to Free.", downgraded);
        }
    }

    @Transactional
    public int sweep() {
        Plan free = planRepository.findByCode("FREE")
                .orElseThrow(() -> new IllegalStateException("FREE plan missing -- V99 seed data not applied"));
        List<Subscription> overdue = subscriptionRepository.findCancelledSubscriptionsPastPeriodEnd(LocalDate.now());

        for (Subscription subscription : overdue) {
            subscription.setPlanId(free.getId());
            subscription.setBillingCycle(null);
            subscription.setRazorpaySubscriptionId(null);
            subscription.setPaymentProvider(null);
            subscription.setStatus(Subscription.STATUS_ACTIVE);
            subscription.setAutoRenew(true);
            subscriptionRepository.save(subscription);

            SubscriptionEvent event = new SubscriptionEvent();
            event.setSubscriptionId(subscription.getId());
            event.setEventType(SubscriptionEvent.PLAN_CHANGED);
            event.setMetadata(Map.of("reason", "CANCELLATION_PERIOD_ENDED"));
            subscriptionEventRepository.save(event);
        }
        return overdue.size();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=SubscriptionReconciliationSweepServiceIT
```
Expected: PASS.

- [ ] **Step 6: Disable the sweep under test**

In `application-test.yml`, alongside the other disabled sweeps:

```yaml
  subscription-reconciliation:
    sweep:
      enabled: false
```

In `application.yml`, alongside the other sweep configs (near `statement-storage.sweep`):

```yaml
  # Subscription billing V1 -- safety net for a missed subscription.cancelled webhook (design spec
  # §6.3). Nothing depends on this running promptly: a subscription sits CANCELLED-past-period-end
  # for at most one sweep interval before downgrading, and the webhook path is expected to beat it
  # to the downgrade in the overwhelming majority of cases.
  subscription-reconciliation:
    sweep:
      enabled: ${SUBSCRIPTION_RECONCILIATION_SWEEP_ENABLED:true}
      interval-ms: ${SUBSCRIPTION_RECONCILIATION_SWEEP_INTERVAL_MS:3600000}
      initial-delay-ms: ${SUBSCRIPTION_RECONCILIATION_SWEEP_INITIAL_DELAY_MS:300000}
```

- [ ] **Step 7: Run the full test file once more to confirm the flag change didn't break anything**

```bash
cd backend && ./mvnw test -Dtest=SubscriptionReconciliationSweepServiceIT
```
Expected: PASS (the test calls `sweep()` directly, bypassing the flag entirely, matching every other
sweep test in this codebase).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/finora/service/SubscriptionReconciliationSweepService.java \
        backend/src/main/java/com/finora/repository/SubscriptionRepository.java \
        backend/src/test/java/com/finora/service/SubscriptionReconciliationSweepServiceIT.java \
        backend/src/main/resources/application.yml \
        backend/src/main/resources/application-test.yml
git commit -m "feat(billing): add reconciliation sweep for missed cancellation webhooks"
```

---

## Task 13: End-to-end integration test

**Files:**
- Create: `backend/src/test/java/com/finora/controller/SubscriptionBillingEndToEndIT.java`

**Interfaces:**
- Consumes everything built in Tasks 1–12. No new production code.

This is the capstone test the spec's §11 testing strategy calls for: checkout → activation webhook →
entitlements reflect the new plan → renewal webhook → billing history reflects the payment. All
through real HTTP calls against the real Spring context, with only the Razorpay gateway mocked (no
real network calls in CI, matching this codebase's existing IT conventions).

- [ ] **Step 1: Write the test**

```java
package com.finora.controller;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.BillingPrice;
import com.finora.entity.Plan;
import com.finora.entity.User;
import com.finora.integrations.razorpay.RazorpaySubscriptionDto;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import com.finora.repository.BillingPriceRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.RazorpayWebhookDispatcher;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * The full checkout -> webhook -> entitlements -> renewal -> billing-history path, end to end.
 * Design spec §11's "every state transition has an explicit test, not just the happy path" is
 * satisfied by RazorpayWebhookDispatcherIT's per-event coverage; this test's job is different --
 * proving the pieces actually compose through real HTTP and a real Spring context, which no
 * single-service test can show.
 */
class SubscriptionBillingEndToEndIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private BillingPriceRepository billingPriceRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private RazorpayWebhookDispatcher dispatcher; // webhook signature verification is
                                                              // covered by RazorpayWebhookControllerIT;
                                                              // this test drives the dispatcher
                                                              // directly to keep focus on state, not
                                                              // signature plumbing.

    @MockitoBean private RazorpaySubscriptionGateway gateway;

    private User createUser() {
        User user = new User();
        user.setEmail("e2e-billing-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("End To End Billing IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void checkoutActivationRenewalAndBillingHistoryAllComposeCorrectly() {
        User user = createUser();
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        BillingPrice price = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(premium.getId(), BillingPrice.CYCLE_MONTHLY)
                .orElseThrow();
        String razorpayPlanId = "plan_e2e_" + UUID.randomUUID();
        price.setRazorpayPlanId(razorpayPlanId);
        billingPriceRepository.save(price);
        String razorpaySubscriptionId = "sub_e2e_" + UUID.randomUUID();

        when(gateway.isConfigured()).thenReturn(true);
        when(gateway.createSubscription(eq(razorpayPlanId), eq("MONTHLY"), anyMap()))
                .thenReturn(new RazorpaySubscriptionDto(razorpaySubscriptionId, "created"));

        // 1. Checkout.
        ResponseEntity<String> checkoutResponse = restTemplate.postForEntity("/api/v1/billing/checkout",
                new HttpEntity<>("{\"planCode\":\"PREMIUM\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user)),
                String.class);
        assertThat(checkoutResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 2. Entitlements still reflect Free -- the frontend success page never activates anything.
        ResponseEntity<String> entitlementsBeforeActivation = restTemplate.exchange(
                "/api/v1/entitlements", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(entitlementsBeforeActivation.getBody()).contains("\"planCode\":\"FREE\"");

        // 3. Activation webhook arrives.
        dispatcher.dispatch("subscription.activated", Map.of(
                "subscription", Map.of("entity", Map.of("id", razorpaySubscriptionId, "current_end", 1893456000L)))); // synthetic-ok: fixture epoch second

        // 4. Entitlements now reflect Premium.
        ResponseEntity<String> entitlementsAfterActivation = restTemplate.exchange(
                "/api/v1/entitlements", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(entitlementsAfterActivation.getBody()).contains("\"planCode\":\"PREMIUM\"");
        assertThat(entitlementsAfterActivation.getBody()).contains("\"FINO_AI\":true");

        // 5. A renewal webhook arrives a cycle later.
        dispatcher.dispatch("subscription.charged", Map.of(
                "payment", Map.of("entity", Map.of("id", "pay_e2e_1", "amount", 79900)),
                "subscription", Map.of("entity", Map.of(
                        "id", razorpaySubscriptionId, "plan_id", razorpayPlanId, "current_end", 1896134400L)))); // synthetic-ok: fixture epoch second

        // 6. Billing history now shows the payment -- BillingHistoryService/Controller needed no
        // changes of their own for this; they were always correct, just fed by nothing until now.
        ResponseEntity<String> history = restTemplate.exchange(
                "/api/v1/billing/history", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(history.getBody()).contains("799.0");
        assertThat(history.getBody()).contains("SUCCESS");
    }
}
```

- [ ] **Step 2: Run the test**

```bash
cd backend && ./mvnw test -Dtest=SubscriptionBillingEndToEndIT
```
Expected: PASS. If entitlements JSON field ordering/formatting differs from the literal substrings
asserted above, adjust the assertions to match the real serialized shape rather than the response
format itself — these are `contains` checks precisely so minor formatting doesn't matter, but the
exact key/value spelling must match `EntitlementsDto`'s actual Jackson serialization.

- [ ] **Step 3: Run the entire backend test suite once, to confirm nothing in Tasks 1–13 regressed anything else**

```bash
cd backend && ./mvnw test
```
Expected: PASS. Pay particular attention to `ArchUnit` tests
(`backend/src/test/java/com/finora/architecture/`) and `DataExportServiceTest`/`DataExportServiceIT`
— `Plan.java` lost its `price` field in Task 1, and anything still reading `Plan.getPrice()` (data
export's `PlanExportDto`-equivalent, if one exists, or any other stray reference) will fail to
compile, not just fail at runtime. Grep for `getPrice()` calls on a `Plan` object specifically
(`grep -rn "\.getPrice()" backend/src/main/java` and check each call site's receiver type) before
declaring this task done.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/finora/controller/SubscriptionBillingEndToEndIT.java
git commit -m "test(billing): add end-to-end checkout/webhook/entitlements/history integration test"
```

---

## What this plan does not cover

Per the spec's own decomposition (§12) and this plan's scope split at the top:

- Upgrade/downgrade (`change-plan` endpoint, Razorpay's `schedule_change_at`), admin-override guard,
  and the referral-trigger move (spec §6.4–§6.7) — **Plan 2**.
- Web pricing page going live, billing portal UI — **Plan 3**.
- Mobile screens, `react-native-razorpay` — **Plan 4**.
- The two items flagged in spec §3 as needing live-sandbox verification (which webhook fires when a
  `cycle_end` change applies; whether a saved mandate can be reused) — these matter for **Plan 2**
  (upgrade/downgrade), not this plan; Task 8's plan-reconciliation logic here is written defensively
  enough to be correct once Plan 2 exercises it for real, but that exercise itself is out of scope
  here.
- Creating the 4 Razorpay Plan objects and populating `billing_prices.razorpay_plan_id` — blocked on
  the Razorpay account existing (spec §10); Task 6's checkout refuses cleanly (503) until that's done,
  it does not assume it.
