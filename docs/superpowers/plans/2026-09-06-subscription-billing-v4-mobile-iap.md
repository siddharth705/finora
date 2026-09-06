# Subscription billing V4 — mobile in-app purchase (RevenueCat) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user subscribe to Plus/Premium from inside the iOS and Android apps through each
store's native purchase flow (via RevenueCat), while the backend's existing `subscriptions` table
stays the single source of truth for entitlements across web, iOS, and Android.

**Architecture:** RevenueCat (`react-native-purchases`) owns receipt validation and cross-store
subscriber identity; a new `RevenueCatWebhookController`/`RevenueCatWebhookDispatcher` pair — built
the same shape as the existing Razorpay pair — is the only thing allowed to write subscription
state. Mobile gets its own entitlement-gating client (ported from web, since none exists there
today) and a Paywall/My-Subscription screen pair. Two small generalizations close a
double-billing/admin-override gap in existing (web) code.

**Tech Stack:** Spring Boot (backend, unchanged), React Native/Expo (mobile), `react-native-purchases`.

**Spec:** [`docs/superpowers/specs/2026-09-06-subscription-billing-v4-mobile-iap-design.md`](../specs/2026-09-06-subscription-billing-v4-mobile-iap-design.md)

## Global Constraints

- RevenueCat `appUserID` is always the real Fynora `User.id` (a UUID string) — never RevenueCat's
  own anonymous id. Purchase flow is unreachable before authentication.
- No free trial. `FREE → PAID` only, matching web.
- Same nominal pricing as web: Plus ₹399/₹3,500, Premium ₹799/₹8,000. Fynora absorbs the store
  commission — no mobile markup.
- Webhook signature verification uses HMAC-SHA256 over `"{timestamp}.{raw_body}"`
  (`X-RevenueCat-Webhook-Signature: t=<unix_ts>,v1=<hex>`), computed over the **raw, unparsed**
  request body — never a re-serialized object.
- `payment_provider` is non-null if and only if a real external mandate still exists or is still
  winding down (spec §2.1, invariant 7) — never treat it as a historical stamp to be left in place.
- Cancellation/plan-change UI is never built for a RevenueCat-owned subscription — both stores
  require that go through their own native UI. Fynora only ever deep-links to it.
- Every webhook effect must be driven by the verified webhook, never a client-reported purchase
  success (spec §2.1, invariant 4).

---

### Task 1: Data model — `subscriptions` columns + `iap_products` table

**Files:**
- Create: `backend/src/main/resources/db/migration/V158__subscription_billing_v4_mobile_iap.sql`
- Modify: `backend/src/main/java/com/finora/entity/Subscription.java`
- Create: `backend/src/main/java/com/finora/entity/IapProduct.java`
- Create: `backend/src/main/java/com/finora/repository/IapProductRepository.java`
- Test: `backend/src/test/java/com/finora/repository/IapProductRepositoryIT.java`

**Interfaces:**
- Produces: `Subscription.getStorePlatform()/setStorePlatform(String)`,
  `Subscription.getRevenuecatOriginalTransactionId()/setRevenuecatOriginalTransactionId(String)`;
  `IapProductRepository.findByProviderProductIdAndPlatform(String providerProductId, String platform)
  -> Optional<IapProduct>`. Task 4 (the dispatcher) consumes both directly.

- [ ] **Step 1: Write the failing test**

```java
package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.IapProduct;
import com.finora.entity.Plan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class IapProductRepositoryIT extends AbstractIntegrationTest {

    @Autowired private IapProductRepository iapProductRepository;
    @Autowired private PlanRepository planRepository;

    @Test
    void resolvesTheSameNominalProductIdOnTwoDifferentPlatformsAsTwoDistinctRows() {
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();

        IapProduct ios = new IapProduct();
        ios.setProviderProductId("plus_monthly");
        ios.setPlanId(plus.getId());
        ios.setBillingCycle("MONTHLY");
        ios.setPlatform("IOS");
        iapProductRepository.save(ios);

        IapProduct android = new IapProduct();
        android.setProviderProductId("plus_monthly");
        android.setPlanId(plus.getId());
        android.setBillingCycle("MONTHLY");
        android.setPlatform("ANDROID");
        iapProductRepository.save(android);

        assertThat(iapProductRepository.findByProviderProductIdAndPlatform("plus_monthly", "IOS"))
                .isPresent().get().extracting(IapProduct::getPlatform).isEqualTo("IOS");
        assertThat(iapProductRepository.findByProviderProductIdAndPlatform("plus_monthly", "ANDROID"))
                .isPresent().get().extracting(IapProduct::getPlatform).isEqualTo("ANDROID");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=IapProductRepositoryIT`
Expected: FAIL — `IapProduct`/`IapProductRepository` don't exist yet (compile error).

- [ ] **Step 3: Write the migration, entity, and repository**

```sql
-- V158__subscription_billing_v4_mobile_iap.sql
-- Subscription billing V4 (docs/superpowers/specs/2026-09-06-subscription-billing-v4-mobile-iap-design.md).
-- Mobile in-app purchase via RevenueCat.

ALTER TABLE subscriptions ADD COLUMN store_platform VARCHAR(10);
ALTER TABLE subscriptions ADD COLUMN revenuecat_original_transaction_id VARCHAR(100);

CREATE TABLE iap_products (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_product_id  VARCHAR(100) NOT NULL,
    plan_id              UUID NOT NULL REFERENCES plans(id),
    billing_cycle        VARCHAR(10) NOT NULL,
    platform             VARCHAR(10) NOT NULL,
    active               BOOLEAN NOT NULL DEFAULT true,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (provider_product_id, platform)
);
-- Rows populated by a one-time setup step (spec §10) once App Store Connect / Play Console
-- products exist -- same posture as billing_prices.razorpay_plan_id staying NULL until Razorpay's
-- own one-time setup (V154's own comment).
```

```java
// backend/src/main/java/com/finora/entity/IapProduct.java
package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Subscription billing V4. Resolves a RevenueCat product_id (+ platform, since nothing requires
 * App Store Connect and Play Console product ids to be globally distinct from each other) to a
 * Fynora plan/cycle -- the same lookup-not-branch role billing_prices plays for Razorpay's
 * plan_id/billing_cycle -> razorpay_plan_id mapping.
 */
@Entity
@Table(name = "iap_products")
public class IapProduct {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "provider_product_id", nullable = false, length = 100)
    private String providerProductId;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "billing_cycle", nullable = false, length = 10)
    private String billingCycle;

    @Column(nullable = false, length = 10)
    private String platform;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public String getProviderProductId() { return providerProductId; }
    public void setProviderProductId(String providerProductId) { this.providerProductId = providerProductId; }
    public UUID getPlanId() { return planId; }
    public void setPlanId(UUID planId) { this.planId = planId; }
    public String getBillingCycle() { return billingCycle; }
    public void setBillingCycle(String billingCycle) { this.billingCycle = billingCycle; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

```java
// backend/src/main/java/com/finora/repository/IapProductRepository.java
package com.finora.repository;

import com.finora.entity.IapProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IapProductRepository extends JpaRepository<IapProduct, UUID> {
    Optional<IapProduct> findByProviderProductIdAndPlatform(String providerProductId, String platform);
}
```

Add to `Subscription.java` (alongside the existing `razorpaySubscriptionId` field/accessors):

```java
    @Column(name = "store_platform", length = 10)
    private String storePlatform;

    @Column(name = "revenuecat_original_transaction_id", length = 100)
    private String revenuecatOriginalTransactionId;

    public String getStorePlatform() { return storePlatform; }
    public void setStorePlatform(String storePlatform) { this.storePlatform = storePlatform; }
    public String getRevenuecatOriginalTransactionId() { return revenuecatOriginalTransactionId; }
    public void setRevenuecatOriginalTransactionId(String revenuecatOriginalTransactionId) { this.revenuecatOriginalTransactionId = revenuecatOriginalTransactionId; }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=IapProductRepositoryIT`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V158__subscription_billing_v4_mobile_iap.sql \
        backend/src/main/java/com/finora/entity/Subscription.java \
        backend/src/main/java/com/finora/entity/IapProduct.java \
        backend/src/main/java/com/finora/repository/IapProductRepository.java \
        backend/src/test/java/com/finora/repository/IapProductRepositoryIT.java
git commit -m "feat(backend): add store_platform/revenuecat columns and iap_products mapping table"
```

---

### Task 2: `RevenueCatProperties` + webhook signature verification

**Files:**
- Create: `backend/src/main/java/com/finora/integrations/revenuecat/RevenueCatProperties.java`
- Create: `backend/src/main/java/com/finora/integrations/revenuecat/RevenueCatSignatureVerifier.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/finora/integrations/revenuecat/RevenueCatSignatureVerifierTest.java`

**Interfaces:**
- Produces: `RevenueCatProperties.getWebhookSigningSecret()`, `RevenueCatProperties.isConfigured()`;
  `RevenueCatSignatureVerifier.verify(String rawBody, String signatureHeader, String secret,
  Duration tolerance) -> boolean`. Task 3 (the controller) consumes both directly.

- [ ] **Step 1: Write the failing test**

```java
package com.finora.integrations.revenuecat;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RevenueCatSignatureVerifierTest {

    private static final String SECRET = "test-signing-secret";

    private String header(String body, long unixTimestamp, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal((unixTimestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));
        return "t=" + unixTimestamp + ",v1=" + hex;
    }

    @Test
    void aCorrectlySignedRecentBodyIsAccepted() throws Exception {
        String body = "{\"event\":{\"type\":\"INITIAL_PURCHASE\"}}";
        long now = Instant.now().getEpochSecond();

        boolean valid = RevenueCatSignatureVerifier.verify(body, header(body, now, SECRET), SECRET, Duration.ofMinutes(5));

        assertThat(valid).isTrue();
    }

    @Test
    void aTamperedBodyIsRejected() throws Exception {
        String signedBody = "{\"event\":{\"type\":\"INITIAL_PURCHASE\"}}";
        String tamperedBody = "{\"event\":{\"type\":\"EXPIRATION\"}}";
        long now = Instant.now().getEpochSecond();

        boolean valid = RevenueCatSignatureVerifier.verify(tamperedBody, header(signedBody, now, SECRET), SECRET, Duration.ofMinutes(5));

        assertThat(valid).isFalse();
    }

    @Test
    void aSignatureFromTheWrongSecretIsRejected() throws Exception {
        String body = "{\"event\":{\"type\":\"INITIAL_PURCHASE\"}}";
        long now = Instant.now().getEpochSecond();

        boolean valid = RevenueCatSignatureVerifier.verify(body, header(body, now, "wrong-secret"), SECRET, Duration.ofMinutes(5));

        assertThat(valid).isFalse();
    }

    @Test
    void aTimestampOutsideToleranceIsRejectedEvenWithACorrectSignature() throws Exception {
        String body = "{\"event\":{\"type\":\"INITIAL_PURCHASE\"}}";
        long tenMinutesAgo = Instant.now().getEpochSecond() - 600;

        boolean valid = RevenueCatSignatureVerifier.verify(body, header(body, tenMinutesAgo, SECRET), SECRET, Duration.ofMinutes(5));

        assertThat(valid).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=RevenueCatSignatureVerifierTest`
Expected: FAIL — `RevenueCatSignatureVerifier` doesn't exist yet (compile error).

- [ ] **Step 3: Write the minimal implementation**

```java
// backend/src/main/java/com/finora/integrations/revenuecat/RevenueCatProperties.java
package com.finora.integrations.revenuecat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Subscription billing V4. Same "unconfigured is a supported state" posture as RazorpayProperties
 *  -- a missing RevenueCat credential disables mobile IAP, nothing else. */
@Configuration
@ConfigurationProperties(prefix = "app.integrations.revenuecat")
public class RevenueCatProperties {

    private String webhookSigningSecret;

    public boolean isConfigured() {
        return webhookSigningSecret != null && !webhookSigningSecret.isBlank();
    }

    public String getWebhookSigningSecret() { return webhookSigningSecret; }
    public void setWebhookSigningSecret(String webhookSigningSecret) { this.webhookSigningSecret = webhookSigningSecret; }
}
```

```java
// backend/src/main/java/com/finora/integrations/revenuecat/RevenueCatSignatureVerifier.java
package com.finora.integrations.revenuecat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Subscription billing V4. Verifies RevenueCat's HMAC webhook signature
 *  ({@code X-RevenueCat-Webhook-Signature: t=<unix_ts>,v1=<hex>}, HMAC-SHA256 over
 *  "{timestamp}.{raw_body}") -- verified against RevenueCat's own current docs, not assumed (design
 *  spec §3). {@code rawBody} must be the exact bytes RevenueCat sent, never a re-serialized object,
 *  same requirement Razorpay's own verification has (Utils.verifyWebhookSignature). */
public final class RevenueCatSignatureVerifier {

    private RevenueCatSignatureVerifier() {}

    public static boolean verify(String rawBody, String signatureHeader, String secret, Duration tolerance) {
        if (signatureHeader == null) return false;
        Map<String, String> parts = new HashMap<>();
        for (String part : signatureHeader.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) parts.put(kv[0], kv[1]);
        }
        String timestampPart = parts.get("t");
        String signaturePart = parts.get("v1");
        if (timestampPart == null || signaturePart == null) return false;

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampPart);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(Instant.now().getEpochSecond() - timestamp) > tolerance.toSeconds()) return false;

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal((timestampPart + "." + rawBody).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return MessageDigest.isEqual(hex.toString().getBytes(StandardCharsets.UTF_8),
                    signaturePart.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
```

Add to `application.yml`, alongside the existing `razorpay:` block:

```yaml
    revenuecat:
      webhook-signing-secret: ${REVENUECAT_WEBHOOK_SIGNING_SECRET:}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=RevenueCatSignatureVerifierTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/integrations/revenuecat/RevenueCatProperties.java \
        backend/src/main/java/com/finora/integrations/revenuecat/RevenueCatSignatureVerifier.java \
        backend/src/main/resources/application.yml \
        backend/src/test/java/com/finora/integrations/revenuecat/RevenueCatSignatureVerifierTest.java
git commit -m "feat(backend): add RevenueCat webhook signature verification"
```

---

### Task 3: `RevenueCatWebhookController` + dispatcher skeleton + `INITIAL_PURCHASE`/`RENEWAL`

**Files:**
- Create: `backend/src/main/java/com/finora/controller/RevenueCatWebhookController.java`
- Create: `backend/src/main/java/com/finora/service/RevenueCatWebhookDispatcher.java`
- Test: `backend/src/test/java/com/finora/controller/RevenueCatWebhookControllerIT.java`

**Interfaces:**
- Consumes: `RevenueCatSignatureVerifier.verify(...)` (Task 2), `WebhookEventService.claim/markProcessed/markFailed`
  (existing), `IapProductRepository.findByProviderProductIdAndPlatform` (Task 1),
  `Subscription.setStorePlatform/setRevenuecatOriginalTransactionId` (Task 1).
- Produces: `POST /api/v1/webhooks/revenuecat`; `RevenueCatWebhookDispatcher.dispatch(String
  eventType, Map<String, Object> eventPayload)` — Task 4 extends this same class with the
  remaining event handlers.

- [ ] **Step 1: Write the failing test**

The header-signing helper (`TestHmac`) is written first — `RevenueCatWebhookControllerIT` needs it
here and in Task 4's tests:

```java
// backend/src/test/java/com/finora/controller/TestHmac.java
package com.finora.controller;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/** Shared by RevenueCatWebhookControllerIT's tests -- computes a real signature the same way
 *  RevenueCatSignatureVerifier checks one, mirroring RazorpayWebhookControllerIT's own
 *  signedHeaders() helper (that one delegates to the Razorpay SDK's Utils.getHash instead, since
 *  Razorpay ships one; RevenueCat's SDK does not expose an equivalent, so this is hand-rolled). */
final class TestHmac {
    private TestHmac() {}

    static String header(String body, long unixTimestamp, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal((unixTimestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return "t=" + unixTimestamp + ",v1=" + hex;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
```

```java
package com.finora.controller;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.IapProduct;
import com.finora.entity.Plan;
import com.finora.entity.User;
import com.finora.repository.IapProductRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RevenueCatWebhookControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private IapProductRepository iapProductRepository;

    @Value("${app.integrations.revenuecat.webhook-signing-secret}")
    private String webhookSecret;

    private void postSigned(String body) {
        long now = Instant.now().getEpochSecond();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-RevenueCat-Webhook-Signature", TestHmac.header(body, now, webhookSecret));
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/webhooks/revenuecat", new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aRealisticInitialPurchaseActivatesTheSubscriptionAtTheMappedPlan() {
        User user = new User();
        user.setEmail("revenuecat-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("RevenueCat IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        subscriptionService.provisionFreeSubscription(user.getId());

        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        IapProduct product = new IapProduct();
        product.setProviderProductId("plus_monthly_it_" + UUID.randomUUID());
        product.setPlanId(plus.getId());
        product.setBillingCycle("MONTHLY");
        product.setPlatform("IOS");
        product = iapProductRepository.save(product);

        long expirationEpochMs = Instant.now().plusSeconds(2_592_000).toEpochMilli();
        String body = """
                {"event":{"type":"INITIAL_PURCHASE","app_user_id":"%s","product_id":"%s",
                 "store":"APP_STORE","original_transaction_id":"txn_it_1",
                 "expiration_at_ms":%d}}
                """.formatted(user.getId(), product.getProviderProductId(), expirationEpochMs);

        postSigned(body);

        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        assertThat(subscription.getPlanId()).isEqualTo(plus.getId());
        assertThat(subscription.getPaymentProvider()).isEqualTo("REVENUECAT");
        assertThat(subscription.getStorePlatform()).isEqualTo("IOS");
        assertThat(subscription.getRevenuecatOriginalTransactionId()).isEqualTo("txn_it_1");
        assertThat(subscription.isAutoRenew()).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=RevenueCatWebhookControllerIT`
Expected: FAIL — `RevenueCatWebhookController` doesn't exist yet (compile error).

- [ ] **Step 3: Write the minimal implementation**

```java
// backend/src/main/java/com/finora/controller/RevenueCatWebhookController.java
package com.finora.controller;

import com.finora.integrations.revenuecat.RevenueCatProperties;
import com.finora.integrations.revenuecat.RevenueCatSignatureVerifier;
import com.finora.service.RevenueCatWebhookDispatcher;
import com.finora.service.WebhookEventService;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Subscription billing V4 (design spec §3/§4.3/§7). Unauthenticated by necessity, same posture as
 * RazorpayWebhookController -- what replaces authentication is the HMAC signature, verified before
 * anything else runs, over the RAW body (never a re-parsed object).
 *
 * <p>Event ids are prefixed ("revenuecat:...") before being handed to the shared webhook_events
 * ledger -- see this design's own §4.3: a composite (provider, event_id) primary key would need a
 * composite JPA key and new WebhookEventService signatures; a prefix gets the identical
 * collision-safety with neither. RevenueCat's own event carries no top-level "id" in the minimal
 * shape used in this design's own tests, so a random id is generated when absent, exactly matching
 * RazorpayWebhookController's own "no event id header -- accept, don't record" fallback.
 */
@RestController
@RequestMapping("/api/v1/webhooks/revenuecat")
public class RevenueCatWebhookController {

    private static final Logger log = LoggerFactory.getLogger(RevenueCatWebhookController.class);
    private static final Duration SIGNATURE_TOLERANCE = Duration.ofMinutes(5);

    private final RevenueCatProperties properties;
    private final WebhookEventService webhookEventService;
    private final RevenueCatWebhookDispatcher dispatcher;

    public RevenueCatWebhookController(RevenueCatProperties properties, WebhookEventService webhookEventService,
                                        RevenueCatWebhookDispatcher dispatcher) {
        this.properties = properties;
        this.webhookEventService = webhookEventService;
        this.dispatcher = dispatcher;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestHeader("X-RevenueCat-Webhook-Signature") String signature,
                                         @RequestBody String rawBody) {
        if (!properties.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (!RevenueCatSignatureVerifier.verify(rawBody, signature, properties.getWebhookSigningSecret(), SIGNATURE_TOLERANCE)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        JSONObject json = new JSONObject(rawBody);
        JSONObject event = json.optJSONObject("event");
        if (event == null) return ResponseEntity.ok().build();
        String eventType = event.optString("type", "unknown");
        Map<String, Object> eventPayload = event.toMap();

        String rawEventId = event.optString("id", null);
        String eventId = "revenuecat:" + (rawEventId != null ? rawEventId : UUID.randomUUID());

        if (!webhookEventService.claim(eventId, "REVENUECAT", eventType, eventPayload)) {
            log.info("Duplicate RevenueCat webhook event {} ({}), ignoring.", eventId, eventType);
            return ResponseEntity.ok().build();
        }

        try {
            dispatcher.dispatch(eventType, eventPayload);
            webhookEventService.markProcessed(eventId);
        } catch (RuntimeException e) {
            webhookEventService.markFailed(eventId);
            log.error("Failed to process RevenueCat webhook event {} ({}).", eventId, eventType, e);
            throw e;
        }
        return ResponseEntity.ok().build();
    }
}
```

```java
// backend/src/main/java/com/finora/service/RevenueCatWebhookDispatcher.java
package com.finora.service;

import com.finora.entity.IapProduct;
import com.finora.entity.Plan;
import com.finora.entity.Subscription;
import com.finora.repository.IapProductRepository;
import com.finora.repository.PlanRepository;
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
import java.util.UUID;

/**
 * Subscription billing V4 (design spec §5). One method per RevenueCat event type this application
 * acts on, mirroring RazorpayWebhookDispatcher's own shape and self-invocation caveat
 * (@Transactional lives on dispatch(), not the individual handlers, for the identical reason --
 * see that class's own doc comment).
 */
@Component
public class RevenueCatWebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RevenueCatWebhookDispatcher.class);

    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final IapProductRepository iapProductRepository;

    public RevenueCatWebhookDispatcher(SubscriptionRepository subscriptionRepository, PlanRepository planRepository,
                                        IapProductRepository iapProductRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.iapProductRepository = iapProductRepository;
    }

    @Transactional
    public void dispatch(String eventType, Map<String, Object> eventPayload) {
        switch (eventType) {
            case "INITIAL_PURCHASE" -> handleInitialPurchase(eventPayload);
            case "RENEWAL" -> handleRenewal(eventPayload);
            default -> log.info("RevenueCat webhook event '{}' received but not handled in V4 yet.", eventType);
        }
    }

    private Optional<Subscription> subscriptionForAppUserId(Map<String, Object> eventPayload) {
        String appUserId = (String) eventPayload.get("app_user_id");
        if (appUserId == null) return Optional.empty();
        try {
            return subscriptionRepository.findActiveOrTrial(UUID.fromString(appUserId));
        } catch (IllegalArgumentException e) {
            log.warn("RevenueCat webhook app_user_id '{}' is not a valid Fynora user id, ignoring.", appUserId);
            return Optional.empty();
        }
    }

    /** spec §6.1 step 4 / §5. app_user_id is always the real Fynora user id (spec §2's "purchase
     *  requires authentication" decision) -- never RevenueCat's own anonymous id -- so this is a
     *  direct lookup, no mapping table. */
    void handleInitialPurchase(Map<String, Object> eventPayload) {
        Subscription subscription = subscriptionForAppUserId(eventPayload).orElse(null);
        if (subscription == null) return;

        String productId = (String) eventPayload.get("product_id");
        String store = (String) eventPayload.get("store");
        String platform = "PLAY_STORE".equals(store) ? "ANDROID" : "IOS";
        IapProduct product = iapProductRepository.findByProviderProductIdAndPlatform(productId, platform).orElse(null);
        if (product == null) {
            log.warn("RevenueCat product_id '{}' ({}) has no iap_products mapping, ignoring.", productId, platform);
            return;
        }
        Plan plan = planRepository.findById(product.getPlanId()).orElseThrow();

        subscription.setPlanId(plan.getId());
        subscription.setBillingCycle(product.getBillingCycle());
        subscription.setPaymentProvider("REVENUECAT");
        subscription.setStorePlatform(platform);
        subscription.setRevenuecatOriginalTransactionId((String) eventPayload.get("original_transaction_id"));
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setAutoRenew(true);
        applyExpiration(subscription, eventPayload);
        subscriptionRepository.save(subscription);
    }

    /** spec §5. Renewal is passive -- just refresh the expiration date. */
    void handleRenewal(Map<String, Object> eventPayload) {
        Subscription subscription = subscriptionForAppUserId(eventPayload).orElse(null);
        if (subscription == null) return;
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        applyExpiration(subscription, eventPayload);
        subscriptionRepository.save(subscription);
    }

    private void applyExpiration(Subscription subscription, Map<String, Object> eventPayload) {
        Object expirationAtMs = eventPayload.get("expiration_at_ms");
        if (expirationAtMs instanceof Number n) {
            subscription.setRenewalDate(LocalDate.ofInstant(Instant.ofEpochMilli(n.longValue()), ZoneOffset.UTC));
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=RevenueCatWebhookControllerIT`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/controller/RevenueCatWebhookController.java \
        backend/src/main/java/com/finora/service/RevenueCatWebhookDispatcher.java \
        backend/src/test/java/com/finora/controller/RevenueCatWebhookControllerIT.java \
        backend/src/test/java/com/finora/controller/TestHmac.java
git commit -m "feat(backend): add RevenueCat webhook receiver -- INITIAL_PURCHASE and RENEWAL"
```

---

### Task 4: `RevenueCatWebhookDispatcher` — remaining lifecycle events

**Files:**
- Modify: `backend/src/main/java/com/finora/service/RevenueCatWebhookDispatcher.java`
- Modify: `backend/src/test/java/com/finora/controller/RevenueCatWebhookControllerIT.java`

**Interfaces:**
- Consumes: everything from Task 3.
- Produces: full `dispatch()` coverage for `CANCELLATION`, `UNCANCELLATION`, `EXPIRATION`,
  `BILLING_ISSUE`, `PRODUCT_CHANGE`. No new public interface — Task 6+ only ever calls the
  unauthenticated webhook endpoint, never these handlers directly.

- [ ] **Step 1: Write the failing tests**

Add `import java.util.Map;` to `RevenueCatWebhookControllerIT`'s existing imports (used by the new
tests and helpers below). Append to the class (each posts a realistically-shaped body through the real
HTTP endpoint, per design spec §11 — not the dispatcher directly):

```java
    @Test
    void cancellationFlipsAutoRenewWithoutTouchingStatusOrPlan() throws Exception {
        User user = createActiveRevenueCatUser("PLUS", "MONTHLY");
        String body = revenueCatBody("CANCELLATION", user.getId(), Map.of());

        postSigned(body);

        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        assertThat(subscription.isAutoRenew()).isFalse();
        assertThat(subscription.getPaymentProvider()).isEqualTo("REVENUECAT");
    }

    @Test
    void uncancellationTurnsAutoRenewBackOn() throws Exception {
        User user = createActiveRevenueCatUser("PLUS", "MONTHLY");
        postSigned(revenueCatBody("CANCELLATION", user.getId(), Map.of()));

        postSigned(revenueCatBody("UNCANCELLATION", user.getId(), Map.of()));

        assertThat(subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow().isAutoRenew()).isTrue();
    }

    @Test
    void expirationDowngradesDirectlyToFreeMirroringHandleHalted() throws Exception {
        User user = createActiveRevenueCatUser("PREMIUM", "MONTHLY");

        postSigned(revenueCatBody("EXPIRATION", user.getId(), Map.of()));

        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        Plan free = planRepository.findByCode("FREE").orElseThrow();
        assertThat(subscription.getPlanId()).isEqualTo(free.getId());
        assertThat(subscription.getStatus()).isEqualTo(Subscription.STATUS_ACTIVE);
        assertThat(subscription.getPaymentProvider()).isNull();
        assertThat(subscription.getStorePlatform()).isNull();
        assertThat(subscription.isAutoRenew()).isTrue();
    }

    @Test
    void billingIssueSetsPastDueNotPaymentFailed() throws Exception {
        User user = createActiveRevenueCatUser("PLUS", "MONTHLY");

        postSigned(revenueCatBody("BILLING_ISSUE", user.getId(), Map.of()));

        assertThat(subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow().getStatus())
                .isEqualTo(Subscription.STATUS_PAST_DUE);
    }

    /** Mandatory per design spec §11 -- the one event type with no Razorpay precedent to lean on. */
    @Test
    void productChangeReconcilesBothPlanAndBillingCycle() throws Exception {
        User user = createActiveRevenueCatUser("PLUS", "MONTHLY");
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        IapProduct yearlyPremium = new IapProduct();
        yearlyPremium.setProviderProductId("premium_yearly_it_" + UUID.randomUUID());
        yearlyPremium.setPlanId(premium.getId());
        yearlyPremium.setBillingCycle("YEARLY");
        yearlyPremium.setPlatform("IOS");
        yearlyPremium = iapProductRepository.save(yearlyPremium);

        postSigned(revenueCatBody("PRODUCT_CHANGE", user.getId(),
                Map.of("product_id", yearlyPremium.getProviderProductId(), "store", "APP_STORE")));

        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        assertThat(subscription.getPlanId()).isEqualTo(premium.getId());
        assertThat(subscription.getBillingCycle()).isEqualTo("YEARLY");
    }

    // --- shared fixtures for this class ---

    private User createActiveRevenueCatUser(String planCode, String billingCycle) {
        User user = new User();
        user.setEmail("revenuecat-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("RevenueCat IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        subscriptionService.provisionFreeSubscription(user.getId());

        Plan plan = planRepository.findByCode(planCode).orElseThrow();
        IapProduct product = new IapProduct();
        product.setProviderProductId(planCode.toLowerCase() + "_" + billingCycle.toLowerCase() + "_it_" + UUID.randomUUID());
        product.setPlanId(plan.getId());
        product.setBillingCycle(billingCycle);
        product.setPlatform("IOS");
        product = iapProductRepository.save(product);

        postSigned(revenueCatBody("INITIAL_PURCHASE", user.getId(),
                Map.of("product_id", product.getProviderProductId(), "store", "APP_STORE",
                        "original_transaction_id", "txn_" + UUID.randomUUID())));
        return user;
    }

    private String revenueCatBody(String type, UUID appUserId, Map<String, Object> extra) {
        long expirationEpochMs = Instant.now().plusSeconds(2_592_000).toEpochMilli();
        StringBuilder extraJson = new StringBuilder();
        extra.forEach((k, v) -> extraJson.append(",\"").append(k).append("\":\"").append(v).append("\""));
        return """
                {"event":{"type":"%s","app_user_id":"%s","expiration_at_ms":%d%s}}
                """.formatted(type, appUserId, expirationEpochMs, extraJson);
    }

```

`postSigned` is already on the class from Task 3 — do not redefine it here, only add the five test
methods above and the two helpers (`createActiveRevenueCatUser`, `revenueCatBody`) shown above them.
`aRealisticInitialPurchaseActivatesTheSubscriptionAtTheMappedPlan` from Task 3 is left as-is (it
already passes); this task only adds new methods to the same class, not remove anything.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=RevenueCatWebhookControllerIT`
Expected: FAIL — `CANCELLATION`/`UNCANCELLATION`/`EXPIRATION`/`BILLING_ISSUE`/`PRODUCT_CHANGE` all
fall through to the `default -> log.info(...)` branch, so none of the assertions about state change hold.

- [ ] **Step 3: Write the minimal implementation**

Add to `RevenueCatWebhookDispatcher`'s `dispatch()` switch:

```java
            case "CANCELLATION" -> handleCancellation(eventPayload);
            case "UNCANCELLATION" -> handleUncancellation(eventPayload);
            case "EXPIRATION" -> handleExpiration(eventPayload);
            case "BILLING_ISSUE" -> handleBillingIssue(eventPayload);
            case "PRODUCT_CHANGE" -> handleProductChange(eventPayload);
```

And the five handlers:

```java
    /** spec §5/§3. Turns off auto-renew only -- status/plan/renewal_date untouched, exactly
     *  Razorpay's own cancel() (BillingCheckoutService.cancel()). Access continues until
     *  expiration; EXPIRATION below is the actual downgrade point. */
    void handleCancellation(Map<String, Object> eventPayload) {
        subscriptionForAppUserId(eventPayload).ifPresent(subscription -> {
            subscription.setAutoRenew(false);
            subscriptionRepository.save(subscription);
        });
    }

    void handleUncancellation(Map<String, Object> eventPayload) {
        subscriptionForAppUserId(eventPayload).ifPresent(subscription -> {
            subscription.setAutoRenew(true);
            subscriptionRepository.save(subscription);
        });
    }

    /** spec §3/§5. Mirrors RazorpayWebhookDispatcher.handleHalted EXACTLY (checked against the real
     *  code, not assumed): resets the plan to FREE, clears every provider-specific field, and sets
     *  status=ACTIVE on FREE directly -- no intermediate status. */
    void handleExpiration(Map<String, Object> eventPayload) {
        subscriptionForAppUserId(eventPayload).ifPresent(subscription -> {
            Plan free = planRepository.findByCode("FREE")
                    .orElseThrow(() -> new IllegalStateException("FREE plan missing -- V99 seed data not applied"));
            subscription.setPlanId(free.getId());
            subscription.setBillingCycle(null);
            subscription.setPaymentProvider(null);
            subscription.setStorePlatform(null);
            subscription.setRevenuecatOriginalTransactionId(null);
            subscription.setStatus(Subscription.STATUS_ACTIVE);
            subscription.setAutoRenew(true);
            subscriptionRepository.save(subscription);
        });
    }

    /** spec §3/§5. Mirrors RazorpayWebhookDispatcher.handlePending: the store is retrying a failed
     *  renewal charge, access is untouched. Deliberately NOT STATUS_PAYMENT_FAILED -- that status
     *  has no live writer anywhere in the existing Razorpay flow this design otherwise mirrors. */
    void handleBillingIssue(Map<String, Object> eventPayload) {
        subscriptionForAppUserId(eventPayload).ifPresent(subscription -> {
            subscription.setStatus(Subscription.STATUS_PAST_DUE);
            subscriptionRepository.save(subscription);
        });
    }

    /** spec §3/§5/§9. The user changed plan tier and/or billing cycle through the store's own
     *  native UI -- something Razorpay has no equivalent for. Reconciles BOTH plan and cycle via
     *  iap_products, mirroring RazorpayWebhookDispatcher.handleCharged's plan-id reconciliation. */
    void handleProductChange(Map<String, Object> eventPayload) {
        Subscription subscription = subscriptionForAppUserId(eventPayload).orElse(null);
        if (subscription == null) return;

        String productId = (String) eventPayload.get("product_id");
        String store = (String) eventPayload.get("store");
        String platform = "PLAY_STORE".equals(store) ? "ANDROID" : "IOS";
        iapProductRepository.findByProviderProductIdAndPlatform(productId, platform).ifPresent(product -> {
            subscription.setPlanId(product.getPlanId());
            subscription.setBillingCycle(product.getBillingCycle());
            subscriptionRepository.save(subscription);
        });
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=RevenueCatWebhookControllerIT`
Expected: PASS (all 6 tests: the Task 3 test plus these 5)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/service/RevenueCatWebhookDispatcher.java \
        backend/src/test/java/com/finora/controller/RevenueCatWebhookControllerIT.java
git commit -m "feat(backend): handle CANCELLATION/UNCANCELLATION/EXPIRATION/BILLING_ISSUE/PRODUCT_CHANGE"
```

---

### Task 5: Close the double-billing/admin-override gaps + expose `paymentProvider`

**Files:**
- Modify: `backend/src/main/java/com/finora/service/BillingCheckoutService.java`
- Modify: `backend/src/main/java/com/finora/service/SubscriptionService.java`
- Modify: `backend/src/main/java/com/finora/dto/BillingDtos.java`
- Modify: `backend/src/main/java/com/finora/controller/BillingController.java` (only if it
  constructs `MySubscriptionDto` fields positionally anywhere other than via
  `BillingCheckoutService.mySubscription()` — check first; as read, it does not, so this file
  likely needs no change, but is listed since it's the endpoint that serves the DTO this task widens)
- Modify: `backend/src/test/java/com/finora/service/BillingCheckoutServiceTest.java`
- Modify: `backend/src/test/java/com/finora/service/SubscriptionServiceTest.java`

**Interfaces:**
- Consumes: `Subscription.getPaymentProvider()` (existing).
- Produces: `MySubscriptionDto` gains a `paymentProvider` field (new 10th positional component,
  appended last) — Task 8 (mobile) and Task 9 (web) both consume this to decide the
  read-only/active-controls split. No other signature changes; both guards keep their existing
  method signatures, only what they check changes.

- [ ] **Step 1: Write the failing tests**

Add to `BillingCheckoutServiceTest` (mirrors the existing "already has a billing subscription" test
for Razorpay, just with a RevenueCat-owned row instead), plus a new test for the widened DTO:

```java
    @Test
    void mySubscriptionSurfacesTheRevenueCatPaymentProvider() {
        // planRepository.findById(planId) is NOT already stubbed by setUp() -- only findByCode
        // is -- so this needs its own stub, same as the existing
        // mySubscriptionReturnsThePlanAndRenewalDateForAPaidSubscriber test does for "PLUS".
        Plan premium = new Plan();
        ReflectionTestUtils.setField(premium, "id", planId);
        premium.setCode("PREMIUM");
        premium.setName("Premium");
        when(planRepository.findById(planId)).thenReturn(Optional.of(premium));

        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setPlanId(planId);
        subscription.setBillingCycle("MONTHLY");
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setPaymentProvider("REVENUECAT");
        subscription.setAutoRenew(true);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(subscription));
        when(planChangeRepository.findBySubscriptionIdOrderByCreatedAtDesc(subscription.getId()))
                .thenReturn(List.of());

        var dto = service.mySubscription(userId);

        assertThat(dto.paymentProvider()).isEqualTo("REVENUECAT");
    }
```

```java
    @Test
    void checkoutRefusesWhenTheUserAlreadyHasARevenueCatOwnedSubscription() {
        Subscription existing = new Subscription();
        existing.setUserId(userId);
        existing.setPlanId(UUID.randomUUID());
        existing.setPaymentProvider("REVENUECAT");
        when(subscriptionRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.checkout(userId, "PREMIUM", "MONTHLY"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already have a billing subscription");
    }
```

Add to `SubscriptionServiceTest` (the class's own `service` field, not a new one):

```java
    @Test
    void changePlanRefusesAdminOverrideWhileARevenueCatSubscriptionIsActive() {
        Subscription active = new Subscription();
        active.setUserId(userId);
        active.setPlanId(UUID.randomUUID());
        active.setPaymentProvider("REVENUECAT");
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.changePlan(userId, "PREMIUM", "support override", adminId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("active paid subscription");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=BillingCheckoutServiceTest,SubscriptionServiceTest`
Expected: FAIL — `mySubscriptionSurfacesTheRevenueCatPaymentProvider` fails to compile
(`MySubscriptionDto` has no `paymentProvider()` component yet); the two guard tests fail because
both guards currently only match the literal string `"RAZORPAY"`/check `razorpaySubscriptionId`, so
a `REVENUECAT`-provider row sails through uncaught in both.

- [ ] **Step 3: Write the minimal implementation**

In `BillingDtos.java`, widen the record (append the new component last, so every existing
positional constructor call just needs one more argument, not reordering):

```java
    public record MySubscriptionDto(
            String planCode, String planName, String billingCycle, String status,
            LocalDate renewalDate, boolean autoRenew, boolean hasBillingSubscription,
            PendingPlanChangeDto pendingChange, PendingOrderDto pendingOrder, String paymentProvider
    ) {}
```

In `BillingCheckoutService.mySubscription()`, add the new argument to the existing construction:

```java
        return new MySubscriptionDto(
                plan.getCode(), plan.getName(), subscription.getBillingCycle(), subscription.getStatus(),
                subscription.getRenewalDate(), subscription.isAutoRenew(),
                subscription.getRazorpaySubscriptionId() != null, pendingChange, pendingOrder,
                subscription.getPaymentProvider());
```

In `BillingCheckoutService.checkout()`, replace:

```java
        subscriptionRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().findFirst()
                .filter(s -> s.getRazorpaySubscriptionId() != null)
                .ifPresent(s -> {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "You already have a billing subscription. Cancel it before starting a new one.");
                });
```

with:

```java
        // Provider PRESENCE, not status -- deliberately (design spec §2.1, invariant 7). A
        // cancelled-but-not-yet-swept Razorpay subscription (status=CANCELLED, payment_provider
        // still stamped by handleCancelled) still has real paid access per V1's own decision;
        // narrowing this to "status is ACTIVE" would let a second mandate start during that
        // legitimate window. Generalized from razorpaySubscriptionId to payment_provider so a
        // RevenueCat-owned row is caught here too, not just a Razorpay one.
        subscriptionRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().findFirst()
                .filter(s -> s.getPaymentProvider() != null && !"ADMIN_GRANT".equals(s.getPaymentProvider()))
                .ifPresent(s -> {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "You already have a billing subscription. Cancel it before starting a new one.");
                });
```

In `SubscriptionService.changePlan()`, replace:

```java
        if ("RAZORPAY".equals(subscription.getPaymentProvider())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This user has an active paid subscription. Cancel it first before granting a complimentary plan.");
        }
```

with:

```java
        if (subscription.getPaymentProvider() != null && !"ADMIN_GRANT".equals(subscription.getPaymentProvider())) {
            // Design spec §6.8: unlike Razorpay, there is no cancelPaidSubscription equivalent an
            // admin can call first to release a REVENUECAT-owned mandate -- Apple/Google don't
            // expose that to third parties. The error says so, not just that it's blocked.
            String guidance = "REVENUECAT".equals(subscription.getPaymentProvider())
                    ? "This account has an active Apple/Google subscription. Ask the user to cancel it "
                      + "through the App Store/Play Store first, then retry."
                    : "This user has an active paid subscription. Cancel it first before granting a complimentary plan.";
            throw new ApiException(HttpStatus.CONFLICT, guidance);
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=BillingCheckoutServiceTest,SubscriptionServiceTest`
Expected: PASS, including every pre-existing test in both files (the Razorpay-specific tests still
pass since `"RAZORPAY"` is non-null and non-`"ADMIN_GRANT"`, matching the old literal check exactly).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/service/BillingCheckoutService.java \
        backend/src/main/java/com/finora/service/SubscriptionService.java \
        backend/src/main/java/com/finora/dto/BillingDtos.java \
        backend/src/test/java/com/finora/service/BillingCheckoutServiceTest.java \
        backend/src/test/java/com/finora/service/SubscriptionServiceTest.java
git commit -m "fix(backend): generalize duplicate-checkout/admin-override guards, expose paymentProvider"
```

---

### Task 6: Mobile — `useEntitlements` hook + `PremiumFeatureGate` port

**Files:**
- Modify: `mobile/src/api/endpoints.ts` (add `EntitlementsDto`/`entitlementsApi` — mobile keeps every
  API namespace in this one file, same as `feedbackApi`/`referralsApi` already do there and
  `frontend/src/api/endpoints.ts` does on web; not a separate per-feature file)
- Create: `mobile/src/components/PremiumFeatureGate.tsx`
- Test: `mobile/src/components/PremiumFeatureGate.test.tsx`

**Interfaces:**
- Consumes: existing `GET /api/v1/entitlements` (`EntitlementController.mine()`, unchanged), mobile's
  existing `mobile/src/api/client.ts` axios instance.
- Produces: `entitlementsApi.mine() -> Promise<EntitlementsDto>`; `<PremiumFeatureGate featureKey
  fallback?>` — Task 7/8 do not consume this directly (the Paywall/My-Subscription screens read
  `mySubscription()`, not entitlements), but any future mobile feature gate will.

- [ ] **Step 1: Write the failing test**

Mobile uses Jest (`jest-expo` preset — see `mobile/package.json`'s `"test"` script), not Vitest.
`describe`/`it`/`expect`/`beforeEach` are globals (no import needed); mocking is `jest.mock`/
`jest.fn`, matching the exact convention `mobile/src/context/AuthContext.test.tsx` already uses.

```tsx
// mobile/src/components/PremiumFeatureGate.test.tsx
import type { ReactNode } from 'react';
import { Text } from 'react-native';
import { render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { PremiumFeatureGate } from './PremiumFeatureGate';
import { entitlementsApi } from '../api/endpoints';
import type { EntitlementsDto } from '../api/endpoints';

jest.mock('../api/endpoints', () => ({ entitlementsApi: { mine: jest.fn() } }));

const mockedEntitlementsApi = entitlementsApi as jest.Mocked<typeof entitlementsApi>;

function renderGate(featureKey: string, fallback?: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <PremiumFeatureGate featureKey={featureKey} fallback={fallback}>
        <Text>Secret premium content</Text>
      </PremiumFeatureGate>
    </QueryClientProvider>
  );
}

function entitlements(overrides: Partial<EntitlementsDto> = {}): EntitlementsDto {
  return { planCode: 'PREMIUM', planName: 'Premium', features: {}, ...overrides };
}

describe('PremiumFeatureGate (mobile)', () => {
  beforeEach(() => mockedEntitlementsApi.mine.mockReset());

  it('fails closed while loading', () => {
    mockedEntitlementsApi.mine.mockReturnValue(new Promise(() => {}));
    renderGate('FINO_AI');
    expect(screen.queryByText('Secret premium content')).toBeNull();
  });

  it('fails closed on error', async () => {
    mockedEntitlementsApi.mine.mockRejectedValue(new Error('network'));
    renderGate('FINO_AI');
    await waitFor(() => expect(screen.queryByText('Secret premium content')).toBeNull());
  });

  it('renders children when the feature is granted', async () => {
    mockedEntitlementsApi.mine.mockResolvedValue(entitlements({ features: { FINO_AI: true } }));
    renderGate('FINO_AI');
    expect(await screen.findByText('Secret premium content')).toBeTruthy();
  });

  it('renders the default upgrade prompt when the feature is absent', async () => {
    mockedEntitlementsApi.mine.mockResolvedValue(entitlements({ features: {} }));
    renderGate('FINO_AI');
    expect(await screen.findByText('This is a premium feature')).toBeTruthy();
  });

  it('renders a custom fallback when provided', async () => {
    mockedEntitlementsApi.mine.mockResolvedValue(entitlements({ features: {} }));
    renderGate('FINO_AI', <Text>Custom locked message</Text>);
    expect(await screen.findByText('Custom locked message')).toBeTruthy();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx jest src/components/PremiumFeatureGate.test.tsx`
Expected: FAIL — neither `entitlementsApi` (in `../api/endpoints`) nor `./PremiumFeatureGate` exist yet.

- [ ] **Step 3: Write the minimal implementation**

Append to the end of `mobile/src/api/endpoints.ts` (alongside `feedbackApi`/`referralsApi` — every
API namespace lives in this one file, same as web's own `endpoints.ts`):

```ts
/** Subscription billing V4. Mirrors frontend's EntitlementsDto exactly -- backend endpoint
 *  (GET /api/v1/entitlements) is unchanged; this is the mobile client that never existed before. */
export interface EntitlementsDto {
  planCode: string | null;
  planName: string | null;
  features: Record<string, boolean>;
}

export const entitlementsApi = {
  mine: () => api.get<EntitlementsDto>('/entitlements').then((r) => r.data),
};
```

```tsx
// mobile/src/components/PremiumFeatureGate.tsx
import type { ReactNode } from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import Ionicons from '@expo/vector-icons/Ionicons';
import { entitlementsApi } from '../api/endpoints';
import { useTheme } from '../theme';

interface PremiumFeatureGateProps {
  /** One of FeatureEntitlement's backend constants (e.g. "FINO_AI", "ADVANCED_REPORTS"). */
  featureKey: string;
  children: ReactNode;
  fallback?: ReactNode;
}

/** Ported from frontend/src/components/PremiumFeatureGate.tsx -- same fail-closed contract (no
 *  access while loading or on error), adapted to React Native primitives. Mobile had no
 *  entitlement-gating code at all before this (design spec §8's own correction). */
export function PremiumFeatureGate({ featureKey, children, fallback }: PremiumFeatureGateProps) {
  const c = useTheme();
  const { data, isLoading, isError } = useQuery({
    queryKey: ['entitlements'],
    queryFn: () => entitlementsApi.mine(),
  });

  if (isLoading || isError) return null;

  const hasAccess = data?.features[featureKey] === true;
  if (hasAccess) return <>{children}</>;
  if (fallback !== undefined) return <>{fallback}</>;

  return (
    <View style={[styles.container, { backgroundColor: c.bg, borderColor: c.border }]}>
      <View style={[styles.iconCircle, { backgroundColor: c.primaryLight }]}>
        <Ionicons name="lock-closed-outline" size={16} color={c.primary} />
      </View>
      <Text style={[styles.title, { color: c.ink }]}>This is a premium feature</Text>
      <Text style={[styles.subtitle, { color: c.muted }]}>Upgrade your plan to unlock this.</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { alignItems: 'center', paddingVertical: 24, paddingHorizontal: 16, borderRadius: 16, borderWidth: 1 },
  iconCircle: { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center', marginBottom: 12 },
  title: { fontSize: 14, fontWeight: '600', marginBottom: 4 },
  subtitle: { fontSize: 12 },
});
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx jest src/components/PremiumFeatureGate.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add mobile/src/api/endpoints.ts mobile/src/components/PremiumFeatureGate.tsx \
        mobile/src/components/PremiumFeatureGate.test.tsx
git commit -m "feat(mobile): add entitlements client and PremiumFeatureGate (ported from web)"
```

---

### Task 7: Mobile — `react-native-purchases` wiring + Paywall screen

**Files:**
- Modify: `mobile/src/api/endpoints.ts` (add `MySubscription`/`billingApi` — mobile has no billing
  client of any kind today, confirmed by `grep`; port only `mySubscription()` from web's
  `billingApi`, not `checkout`/`cancel`/`changePlan`/`cancelPendingOrder`, since those are
  Razorpay-checkout-specific concepts with no mobile analog — mobile's purchase flow goes through
  RevenueCat's SDK, not this endpoint)
- Create: `mobile/src/lib/revenueCat.ts`
- Create: `mobile/src/screens/PaywallScreen.tsx`
- Test: `mobile/src/lib/revenueCat.test.ts`
- Test: `mobile/src/screens/PaywallScreen.test.tsx`
- Modify: `mobile/package.json` (add `react-native-purchases`)

**Interfaces:**
- Consumes: `billingApi.mySubscription()` (added in this task).
- Produces: `configureRevenueCat(fynoraUserId: string): void`; `purchasePlan(planCode: 'PLUS' |
  'PREMIUM', billingCycle: 'MONTHLY' | 'YEARLY'): Promise<void>`. Task 8 does not call these
  directly (My Subscription screen only reads state + calls `restorePurchases`), but reuses the
  same `mySubscription()` read.

- [ ] **Step 1: Write the failing test**

```ts
// mobile/src/lib/revenueCat.test.ts
import Purchases from 'react-native-purchases';
import { configureRevenueCat, purchasePlan } from './revenueCat';

jest.mock('react-native-purchases', () => ({
  default: {
    configure: jest.fn(),
    getOfferings: jest.fn(),
    purchasePackage: jest.fn(),
  },
}));

const mockedPurchases = Purchases as jest.Mocked<typeof Purchases>;

describe('configureRevenueCat', () => {
  it('configures with the real Fynora user id as appUserID, never anonymous', () => {
    configureRevenueCat('11111111-1111-1111-1111-111111111111');

    expect(mockedPurchases.configure).toHaveBeenCalledWith(
      expect.objectContaining({ appUserID: '11111111-1111-1111-1111-111111111111' })
    );
  });
});

describe('purchasePlan', () => {
  beforeEach(() => mockedPurchases.getOfferings.mockReset());

  it('purchases the package matching the requested plan and cycle', async () => {
    const targetPackage = { identifier: 'plus_monthly', product: { identifier: 'plus_monthly' } };
    mockedPurchases.getOfferings.mockResolvedValue({
      current: { availablePackages: [targetPackage] },
    } as any);
    mockedPurchases.purchasePackage.mockResolvedValue({} as any);

    await purchasePlan('PLUS', 'MONTHLY');

    expect(mockedPurchases.purchasePackage).toHaveBeenCalledWith(targetPackage);
  });

  it('throws a clear error when no offering package matches the plan/cycle', async () => {
    mockedPurchases.getOfferings.mockResolvedValue({ current: { availablePackages: [] } } as any);

    await expect(purchasePlan('PREMIUM', 'YEARLY')).rejects.toThrow(/no.*offering/i);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx jest src/lib/revenueCat.test.ts`
Expected: FAIL — `react-native-purchases` isn't a dependency yet, `./revenueCat` doesn't exist.

- [ ] **Step 3: Write the minimal implementation**

```bash
cd mobile && npx expo install react-native-purchases
```

```ts
// mobile/src/lib/revenueCat.ts
import Purchases, { type PurchasesPackage } from 'react-native-purchases';

const REVENUECAT_API_KEY = process.env.EXPO_PUBLIC_REVENUECAT_API_KEY;

/** Subscription billing V4 (design spec §2/§6.1). appUserID is ALWAYS the real, authenticated
 *  Fynora user id -- never RevenueCat's own anonymous $RCAnonymousID. Called once at sign-in,
 *  mirroring how the backend's Razorpay integration embeds the raw user id (notes.fynoraUserId)
 *  rather than a separate mapping id. */
export function configureRevenueCat(fynoraUserId: string): void {
  if (!REVENUECAT_API_KEY) {
    throw new Error('EXPO_PUBLIC_REVENUECAT_API_KEY is not set.');
  }
  Purchases.configure({ apiKey: REVENUECAT_API_KEY, appUserID: fynoraUserId });
}

function packageIdentifierFor(planCode: string, billingCycle: string): string {
  return `${planCode.toLowerCase()}_${billingCycle.toLowerCase()}`;
}

/** Opens the OS's native purchase sheet for the given plan/cycle. Resolving does NOT mean the
 *  plan is active -- activation only ever comes from the backend's verified RevenueCat webhook
 *  (design spec §6.1 step 5), same rule as web's openRazorpayCheckout(). */
export async function purchasePlan(planCode: 'PLUS' | 'PREMIUM', billingCycle: 'MONTHLY' | 'YEARLY'): Promise<void> {
  const offerings = await Purchases.getOfferings();
  const target = packageIdentifierFor(planCode, billingCycle);
  const pkg = offerings.current?.availablePackages.find(
    (p: PurchasesPackage) => p.identifier === target || p.product.identifier === target
  );
  if (!pkg) {
    throw new Error(`No RevenueCat offering package found for ${target}.`);
  }
  await Purchases.purchasePackage(pkg);
}

export async function restorePurchases(): Promise<void> {
  await Purchases.restorePurchases();
}
```

Append to `mobile/src/api/endpoints.ts` (mobile has no billing client at all today — confirmed by
`grep`; only `mySubscription()` is ported, not the Razorpay-checkout-specific methods, since mobile
purchases go through the RevenueCat SDK above, not this endpoint):

```ts
/** Subscription billing V4. Mirrors frontend's MySubscription exactly (mobile only ever reads
 *  this -- purchasing happens through RevenueCat's SDK, not a checkout()/changePlan() call). */
export interface MySubscription {
  planCode: string;
  planName: string;
  billingCycle: string | null;
  status: string;
  renewalDate: string | null;
  autoRenew: boolean;
  hasBillingSubscription: boolean;
  paymentProvider: string | null;
}

export const billingApi = {
  mySubscription: () => api.get<MySubscription>('/billing/subscription').then((r) => r.data),
};
```

```tsx
// mobile/src/screens/PaywallScreen.tsx
import { useState } from 'react';
import { View, Text, StyleSheet, Pressable, ActivityIndicator } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { billingApi } from '../api/endpoints';
import { purchasePlan } from '../lib/revenueCat';
import { useTheme } from '../theme';

const PLANS = [
  { code: 'PLUS' as const, name: 'Plus', price: '₹399/mo' },
  { code: 'PREMIUM' as const, name: 'Premium', price: '₹799/mo' },
];

/** Mobile equivalent of frontend/src/pages/landing/Pricing.tsx, but purchase happens right here
 *  (design spec §8) -- unlike web, there's no separate marketing-site/app split on mobile. Only
 *  reachable post-auth (design spec §2's "purchase requires authentication" decision) and only
 *  shown when mySubscription() has no active paid payment_provider (design spec §6.3). */
export function PaywallScreen() {
  const c = useTheme();
  const queryClient = useQueryClient();
  const [purchasing, setPurchasing] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const { data: subscription } = useQuery({
    queryKey: ['my-subscription'],
    queryFn: () => billingApi.mySubscription(),
  });

  if (subscription?.hasBillingSubscription) {
    return null; // design spec §6.3/§6.4 -- caller (navigator) routes to MySubscriptionScreen instead
  }

  async function handlePurchase(planCode: 'PLUS' | 'PREMIUM') {
    setError(null);
    setPurchasing(planCode);
    try {
      await purchasePlan(planCode, 'MONTHLY');
      await queryClient.invalidateQueries({ queryKey: ['my-subscription'] });
      await queryClient.invalidateQueries({ queryKey: ['entitlements'] });
    } catch (e: any) {
      setError(e.message ?? 'Could not complete the purchase. Try again.');
    } finally {
      setPurchasing(null);
    }
  }

  return (
    <View style={[styles.container, { backgroundColor: c.bg }]}>
      {error && <Text style={[styles.error, { color: c.danger }]}>{error}</Text>}
      {PLANS.map((plan) => (
        <View key={plan.code} style={[styles.card, { borderColor: c.border, backgroundColor: c.card }]}>
          <Text style={[styles.planName, { color: c.ink }]}>{plan.name}</Text>
          <Text style={[styles.planPrice, { color: c.muted }]}>{plan.price}</Text>
          <Pressable
            disabled={purchasing !== null}
            onPress={() => handlePurchase(plan.code)}
            style={[styles.button, { backgroundColor: c.primary, opacity: purchasing ? 0.6 : 1 }]}
          >
            {purchasing === plan.code ? <ActivityIndicator color="#fff" /> : <Text style={styles.buttonText}>Subscribe</Text>}
          </Pressable>
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 16, gap: 16 },
  error: { fontSize: 13, marginBottom: 8 },
  card: { borderWidth: 1, borderRadius: 16, padding: 20, gap: 8 },
  planName: { fontSize: 18, fontWeight: '700' },
  planPrice: { fontSize: 14 },
  button: { marginTop: 12, borderRadius: 12, paddingVertical: 12, alignItems: 'center' },
  buttonText: { color: '#fff', fontWeight: '600' },
});
```

```tsx
// mobile/src/screens/PaywallScreen.test.tsx
import { render, screen, waitFor, fireEvent } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { PaywallScreen } from './PaywallScreen';
import { billingApi } from '../api/endpoints';
import { purchasePlan } from '../lib/revenueCat';

jest.mock('../api/endpoints', () => ({ billingApi: { mySubscription: jest.fn() } }));
jest.mock('../lib/revenueCat', () => ({ purchasePlan: jest.fn() }));

const mockedBillingApi = billingApi as jest.Mocked<typeof billingApi>;
const mockedPurchasePlan = purchasePlan as jest.MockedFunction<typeof purchasePlan>;

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={queryClient}><PaywallScreen /></QueryClientProvider>);
}

describe('PaywallScreen', () => {
  beforeEach(() => {
    mockedBillingApi.mySubscription.mockResolvedValue({
      planCode: 'FREE', hasBillingSubscription: false,
    } as any);
  });

  it('shows both plans and purchases the tapped one', async () => {
    mockedPurchasePlan.mockResolvedValue(undefined);
    renderScreen();

    fireEvent.press(await screen.findByText('Subscribe'));

    await waitFor(() => expect(mockedPurchasePlan).toHaveBeenCalledWith('PLUS', 'MONTHLY'));
  });

  it('shows an error if the purchase fails', async () => {
    mockedPurchasePlan.mockRejectedValue(new Error('Purchase cancelled'));
    renderScreen();

    fireEvent.press((await screen.findAllByText('Subscribe'))[0]);

    expect(await screen.findByText('Purchase cancelled')).toBeTruthy();
  });
});
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx jest src/lib/revenueCat.test.ts src/screens/PaywallScreen.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add mobile/package.json mobile/package-lock.json mobile/src/api/endpoints.ts \
        mobile/src/lib/revenueCat.ts mobile/src/lib/revenueCat.test.ts \
        mobile/src/screens/PaywallScreen.tsx mobile/src/screens/PaywallScreen.test.tsx
git commit -m "feat(mobile): add react-native-purchases wiring and the Paywall screen"
```

---

### Task 8: Mobile — My Subscription screen

**Files:**
- Create: `mobile/src/screens/MySubscriptionScreen.tsx`
- Create: `mobile/src/screens/SubscriptionScreen.tsx`
- Test: `mobile/src/screens/MySubscriptionScreen.test.tsx`
- Test: `mobile/src/screens/SubscriptionScreen.test.tsx`
- Modify: `mobile/src/navigation/AppTabs.tsx` (register `SubscriptionScreen` under `MoreStack`)
- Modify: `mobile/src/screens/MoreScreen.tsx` (add one static `MENU_ITEMS` entry)

**Interfaces:**
- Consumes: `billingApi.mySubscription()` (Task 7), `restorePurchases()` (Task 7),
  `PaywallScreen` (Task 7).
- Produces: none new. Note on why there's a `SubscriptionScreen` wrapper at all: `MoreScreen`'s
  existing `MENU_ITEMS` array (checked against the real file) is a static `{label, route}` list —
  `navigate(route)` with no params, no conditional destination support. It cannot route to
  `PaywallScreen` or `MySubscriptionScreen` depending on subscription state by itself, so
  `SubscriptionScreen` is the one thing `MENU_ITEMS` actually points to, and it picks between the
  other two internally.

- [ ] **Step 1: Write the failing test**

```tsx
// mobile/src/screens/MySubscriptionScreen.test.tsx
import { render, screen, waitFor, fireEvent } from '@testing-library/react-native';
import { Linking } from 'react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MySubscriptionScreen } from './MySubscriptionScreen';
import { billingApi } from '../api/endpoints';
import { restorePurchases } from '../lib/revenueCat';

jest.mock('../api/endpoints', () => ({ billingApi: { mySubscription: jest.fn() } }));
jest.mock('../lib/revenueCat', () => ({ restorePurchases: jest.fn() }));

const mockedBillingApi = billingApi as jest.Mocked<typeof billingApi>;
const mockedRestorePurchases = restorePurchases as jest.MockedFunction<typeof restorePurchases>;

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={queryClient}><MySubscriptionScreen /></QueryClientProvider>);
}

describe('MySubscriptionScreen', () => {
  it('shows a read-only view with no controls for a Razorpay-owned subscription', async () => {
    mockedBillingApi.mySubscription.mockResolvedValue({
      planCode: 'PLUS', planName: 'Plus', hasBillingSubscription: true, paymentProvider: 'RAZORPAY',
    } as any);
    renderScreen();

    expect(await screen.findByText(/managed on web/i)).toBeTruthy();
    expect(screen.queryByText('Manage subscription')).toBeNull();
    expect(screen.queryByText('Restore Purchases')).toBeNull();
  });

  it('shows Manage subscription and Restore Purchases for a RevenueCat-owned subscription', async () => {
    mockedBillingApi.mySubscription.mockResolvedValue({
      planCode: 'PREMIUM', planName: 'Premium', hasBillingSubscription: true, paymentProvider: 'REVENUECAT',
    } as any);
    renderScreen();

    expect(await screen.findByText('Manage subscription')).toBeTruthy();
    expect(await screen.findByText('Restore Purchases')).toBeTruthy();
  });

  it('opens the OS subscription settings when Manage subscription is tapped', async () => {
    mockedBillingApi.mySubscription.mockResolvedValue({
      planCode: 'PREMIUM', planName: 'Premium', hasBillingSubscription: true, paymentProvider: 'REVENUECAT',
    } as any);
    const openURLSpy = jest.spyOn(Linking, 'openURL').mockResolvedValue(true);
    renderScreen();

    fireEvent.press(await screen.findByText('Manage subscription'));

    await waitFor(() => expect(openURLSpy).toHaveBeenCalled());
  });

  it('calls restorePurchases and refetches when Restore Purchases is tapped', async () => {
    mockedBillingApi.mySubscription.mockResolvedValue({
      planCode: 'FREE', hasBillingSubscription: false, paymentProvider: null,
    } as any);
    mockedRestorePurchases.mockResolvedValue(undefined);
    renderScreen();

    fireEvent.press(await screen.findByText('Restore Purchases'));

    await waitFor(() => expect(mockedRestorePurchases).toHaveBeenCalled());
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx jest src/screens/MySubscriptionScreen.test.tsx`
Expected: FAIL — `MySubscriptionScreen` doesn't exist yet.

- [ ] **Step 3: Write the minimal implementation**

```tsx
// mobile/src/screens/MySubscriptionScreen.tsx
import { View, Text, StyleSheet, Pressable, Platform, Linking } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { billingApi } from '../api/endpoints';
import { restorePurchases } from '../lib/revenueCat';
import { useTheme } from '../theme';

const IOS_MANAGE_SUBSCRIPTIONS_URL = 'itms-apps://apps.apple.com/account/subscriptions';
const ANDROID_MANAGE_SUBSCRIPTIONS_URL = 'https://play.google.com/store/account/subscriptions';

/** Mobile equivalent of frontend/src/pages/Billing.tsx, structurally different by design (spec
 *  §2/§8): neither App Store nor Play Store policy allows an in-app cancel button for an IAP
 *  subscription, so this only ever deep-links out to the OS's own subscription management. A
 *  Razorpay-owned subscription is read-only here for the same reason mobile never offers the
 *  Paywall to one -- design spec §6.3/§6.4's ownership-source rule (§2.1, invariant 2). */
export function MySubscriptionScreen() {
  const c = useTheme();
  const queryClient = useQueryClient();
  const { data: subscription, isLoading } = useQuery({
    queryKey: ['my-subscription'],
    queryFn: () => billingApi.mySubscription(),
  });

  async function handleManageSubscription() {
    const url = Platform.OS === 'ios' ? IOS_MANAGE_SUBSCRIPTIONS_URL : ANDROID_MANAGE_SUBSCRIPTIONS_URL;
    await Linking.openURL(url);
  }

  async function handleRestore() {
    await restorePurchases();
    await queryClient.invalidateQueries({ queryKey: ['my-subscription'] });
    await queryClient.invalidateQueries({ queryKey: ['entitlements'] });
  }

  if (isLoading || !subscription) return null;

  return (
    <View style={[styles.container, { backgroundColor: c.bg }]}>
      <Text style={[styles.planName, { color: c.ink }]}>{subscription.planName ?? subscription.planCode}</Text>

      {subscription.hasBillingSubscription && subscription.paymentProvider === 'RAZORPAY' && (
        <Text style={[styles.note, { color: c.muted }]}>
          This subscription is managed on web. Open the Billing page in a browser to make changes.
        </Text>
      )}

      {subscription.hasBillingSubscription && subscription.paymentProvider === 'REVENUECAT' && (
        <Pressable onPress={handleManageSubscription} style={[styles.button, { borderColor: c.border }]}>
          <Text style={{ color: c.ink }}>Manage subscription</Text>
        </Pressable>
      )}

      {/* Not shown for a Razorpay-owned subscription -- there is nothing an App Store/Play Store
          restore could do for a web-purchased plan, and showing it would just invite a confusing
          no-op tap. Shown both for FREE (no billing subscription -- a lapsed or not-yet-synced IAP
          purchase) and REVENUECAT-owned. */}
      {subscription.paymentProvider !== 'RAZORPAY' && (
        <Pressable onPress={handleRestore} style={[styles.button, { borderColor: c.border }]}>
          <Text style={{ color: c.ink }}>Restore Purchases</Text>
        </Pressable>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 16, gap: 12 },
  planName: { fontSize: 20, fontWeight: '700' },
  note: { fontSize: 13 },
  button: { borderWidth: 1, borderRadius: 12, paddingVertical: 12, alignItems: 'center', marginTop: 8 },
});
```

`SubscriptionScreen` is the one thing actually registered in the menu — it picks `PaywallScreen` or
`MySubscriptionScreen` internally, since `MENU_ITEMS` itself can't (see this task's own Interfaces
note above):

```tsx
// mobile/src/screens/SubscriptionScreen.tsx
import { useQuery } from '@tanstack/react-query';
import { billingApi } from '../api/endpoints';
import { PaywallScreen } from './PaywallScreen';
import { MySubscriptionScreen } from './MySubscriptionScreen';

export function SubscriptionScreen() {
  const { data: subscription, isLoading } = useQuery({
    queryKey: ['my-subscription'],
    queryFn: () => billingApi.mySubscription(),
  });

  if (isLoading) return null;
  return subscription?.hasBillingSubscription ? <MySubscriptionScreen /> : <PaywallScreen />;
}
```

```tsx
// mobile/src/screens/SubscriptionScreen.test.tsx
import { render, screen } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SubscriptionScreen } from './SubscriptionScreen';
import { billingApi } from '../api/endpoints';

jest.mock('../api/endpoints', () => ({ billingApi: { mySubscription: jest.fn() } }));
jest.mock('./PaywallScreen', () => ({ PaywallScreen: () => { const { Text } = require('react-native'); return <Text>PAYWALL</Text>; } }));
jest.mock('./MySubscriptionScreen', () => ({ MySubscriptionScreen: () => { const { Text } = require('react-native'); return <Text>MY_SUBSCRIPTION</Text>; } }));

const mockedBillingApi = billingApi as jest.Mocked<typeof billingApi>;

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={queryClient}><SubscriptionScreen /></QueryClientProvider>);
}

describe('SubscriptionScreen', () => {
  it('shows the Paywall when the user has no billing subscription', async () => {
    mockedBillingApi.mySubscription.mockResolvedValue({ hasBillingSubscription: false } as any);
    renderScreen();
    expect(await screen.findByText('PAYWALL')).toBeTruthy();
  });

  it('shows My Subscription when the user already has one', async () => {
    mockedBillingApi.mySubscription.mockResolvedValue({ hasBillingSubscription: true } as any);
    renderScreen();
    expect(await screen.findByText('MY_SUBSCRIPTION')).toBeTruthy();
  });
});
```

Add the route to `mobile/src/navigation/types.ts`'s `MoreStackParamList` (alongside the existing
`Budgets`/`Goals` entries):

```ts
  Subscription: undefined;
```

Register the screen in `AppTabs.tsx`'s `MoreNavigator` (alongside the existing `Budgets`/`Goals`
entries) and import it there:

```tsx
      <MoreStack.Screen name="Subscription" component={SubscriptionScreen} />
```

Add one entry to `MoreScreen.tsx`'s existing `MENU_ITEMS` array:

```ts
  { label: 'Subscription', route: 'Subscription' },
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx jest src/screens/MySubscriptionScreen.test.tsx src/screens/SubscriptionScreen.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add mobile/src/screens/MySubscriptionScreen.tsx mobile/src/screens/MySubscriptionScreen.test.tsx \
        mobile/src/screens/SubscriptionScreen.tsx mobile/src/screens/SubscriptionScreen.test.tsx \
        mobile/src/navigation/types.ts \
        mobile/src/navigation/AppTabs.tsx mobile/src/screens/MoreScreen.tsx
git commit -m "feat(mobile): add My Subscription screen + Subscription menu entry"
```

---

### Task 9: Web — disabled controls for a RevenueCat-owned subscription

**Files:**
- Modify: `frontend/src/api/endpoints.ts` (add `paymentProvider` to the `MySubscription` interface —
  Task 5 added this field to the backend's `MySubscriptionDto`, but the frontend's own TS type is a
  hand-maintained mirror, not generated, so it needs its own one-line update)
- Modify: `frontend/src/pages/Billing.tsx`
- Modify: `frontend/src/pages/Billing.test.tsx`

**Interfaces:**
- Consumes: `billingApi.mySubscription()`'s `paymentProvider` field, added to the backend DTO by
  Task 5.

- [ ] **Step 1: Write the failing test**

Add to `Billing.test.tsx`:

```tsx
  it('shows disabled plan controls with a store-managed note for a RevenueCat-owned subscription', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue({
      planCode: 'PREMIUM', planName: 'Premium', billingCycle: 'MONTHLY', status: 'ACTIVE',
      renewalDate: '2026-10-06', autoRenew: true, hasBillingSubscription: true,
      paymentProvider: 'REVENUECAT', pendingChange: null, pendingOrder: null,
    });
    renderBilling();

    expect(await screen.findByText(/managed through the App Store\/Play Store/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /subscribe/i })).toBeDisabled();
    expect(screen.queryByRole('button', { name: /cancel subscription/i })).not.toBeInTheDocument();
  });
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/Billing.test.tsx -t "RevenueCat-owned"`
Expected: FAIL — `Billing.tsx` has no `paymentProvider` handling at all today; the note doesn't
render and the Subscribe button isn't disabled.

- [ ] **Step 3: Write the minimal implementation**

In `frontend/src/api/endpoints.ts`, add the new field to the existing interface:

```ts
export interface MySubscription {
  planCode: string;
  planName: string;
  billingCycle: string | null;
  status: string;
  renewalDate: string | null;
  autoRenew: boolean;
  hasBillingSubscription: boolean;
  pendingChange: PendingPlanChange | null;
  pendingOrder: PendingOrder | null;
  paymentProvider: string | null;
}
```

In `Billing.tsx`, add near the top of the plan-selector card (the same place the pending-order card
already checks `subscription.pendingOrder`):

```tsx
      {subscription?.hasBillingSubscription && subscription.paymentProvider === 'REVENUECAT' && (
        <div className="text-sm text-ink bg-bg border border-border rounded-lg px-4 py-2.5">
          This subscription is managed through the App Store/Play Store — changes and cancellation
          happen there, not here.
        </div>
      )}
```

And extend the existing Subscribe button's `disabled` expression:

```tsx
            <Button
              onClick={subscribeToPlan}
              disabled={isSubmitting || !!activatingPlanCode || subscription.paymentProvider === 'REVENUECAT' ||
                (targetPlan === subscription.planCode && targetCycle === subscription.billingCycle)}
            >
```

And the existing "Cancel subscription" button's visibility condition:

```tsx
            {subscription.hasBillingSubscription && subscription.autoRenew && subscription.paymentProvider !== 'REVENUECAT' && (
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/Billing.test.tsx`
Expected: PASS, full file (confirms no regression on the existing Razorpay-path tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/api/endpoints.ts frontend/src/pages/Billing.tsx frontend/src/pages/Billing.test.tsx
git commit -m "fix(frontend): disable plan controls for a RevenueCat-owned subscription"
```

---

## After all 9 tasks

Run the full suite before considering this plan done: `backend` (`./mvnw test`);
`frontend`/`admin-portal` (`npx tsc --noEmit && npx vitest run`); `mobile` (`npx tsc --noEmit &&
NODE_OPTIONS=--experimental-vm-modules npx jest` — mobile uses Jest, not Vitest, per its own
`package.json`). Real sandbox verification
(design spec §10/§11 — a genuine App Store Sandbox / Play Console internal-testing purchase,
specifically including a `PRODUCT_CHANGE` upgrade/downgrade) is external to what any of these 9
tasks can prove on their own, and must happen before store submission.
