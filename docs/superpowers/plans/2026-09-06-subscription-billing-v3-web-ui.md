# Subscription billing V3 — web UI implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the fully-built Plan 1 (checkout/webhooks/renewal, PR #1008) and Plan 2
(upgrade/downgrade/admin override, PR #1016) backend into something a user can actually reach: a
live pricing page, a self-service billing portal (current plan, upgrade/downgrade/cancel,
history), and an admin portal that handles the "user has a live Razorpay subscription" guard
Plan 2 already enforces server-side.

**Architecture:** Two small backend tasks (Tasks 1-2) close gaps this UI cannot work without (§0
below), then four frontend/admin-portal tasks (Tasks 3-6) build the actual screens against the
now-complete API surface. No new backend domain concepts — every screen is a thin client over
`BillingCheckoutService`/`SubscriptionService`, matching the existing app's "components call
`api/endpoints.ts`, endpoints call the backend 1:1" convention.

**Tech Stack:** React + TypeScript + Vite (`frontend`, `admin-portal`), TanStack Query, Razorpay
Checkout JS (`https://checkout.razorpay.com/v1/checkout.js`, loaded dynamically — no new npm
dependency), Vitest + Testing Library. Backend: Spring Boot, unchanged stack from Plans 1/2.

**Spec:** [`docs/superpowers/specs/2026-09-05-subscription-billing-v1-design.md`](../specs/2026-09-05-subscription-billing-v1-design.md)
§6.1 (checkout), §6.4/§6.5 (downgrade/upgrade), §6.6 (admin override), §8 (frontend/mobile — this
plan implements the "Web" and "Admin portal" bullets; mobile is a separate later plan).

## §0. Finding from planning this plan — a real bug in already-merged Plan 2 code

**`POST /api/v1/billing/change-plan` cannot actually complete an upgrade today.** Read directly
from `backend/src/main/java/com/finora/service/BillingCheckoutService.java` and
`backend/src/main/java/com/finora/controller/BillingController.java` (both merged in PR #1016):

- `changePlan()`'s upgrade branch (`upgradeToNewSubscription`) calls
  `gateway.createSubscription(...)` and gets back a real `RazorpaySubscriptionDto(id, status)` —
  the same object `checkout()` returns to the caller as `CheckoutResponseDto(razorpaySubscriptionId,
  keyId)` so the frontend can open Razorpay's Checkout widget and the user can authorize the new
  mandate.
- `upgradeToNewSubscription` is `void`. `changePlan()` is `void`. `BillingController.changePlan()`
  returns `ApiResponse<Void>` — literally `ApiResponse.ok(null, "Plan change requested")`.
- **The new subscription's id and Razorpay key never reach the caller.** There is no way for any
  client — this plan's web UI, a future mobile screen, anything — to open the checkout widget for
  the new mandate. The new Razorpay subscription sits created but never authorized, and
  `subscription.activated` never fires for it, because nobody ever paid.

This was never caught because `SubscriptionUpgradeDowngradeEndToEndIT.upgradeActivatesTheNewSubscriptionAndCancelsTheOldOne`
(Plan 2) calls `dispatcher.dispatch("subscription.activated", ...)` directly — a deliberate,
correct way to test the backend's webhook-reconciliation logic in isolation, but it never asserts
what the `change-plan` HTTP response body actually contains, so it could not have caught "the
frontend has nothing to open a checkout widget with."

**Task 1 below fixes this first**, before anything else in this plan, because Task 5 (the Billing
Portal's upgrade flow) cannot be written against a contract that doesn't exist yet.

## §0.5. Second finding — an external review of this draft caught a live production bug

A product review of this plan's first draft flagged (among other things) that an abandoned
checkout has no recovery path. Checking that against the actual code turned up something more
serious than the review itself realized: **combined with the duplicate-order guard added earlier
in this same session (already merged to `main` in PR #1016), any user who abandons checkout today
is permanently locked out of ever checking out again.**

- `SubscriptionOrderRepository.existsByUserIdAndStatus` (added in PR #1016, this session) makes
  `checkout()`/`changePlan()`'s upgrade path refuse to run at all once a `PENDING`
  `subscription_orders` row exists for a user.
- Nothing anywhere in the codebase ever transitions a `subscription_orders` row out of `PENDING`
  except the activation webhook (→ `COMPLETED`). Confirmed by grepping every call site that sets a
  `SubscriptionOrder`'s status: `STATUS_FAILED` and `STATUS_ABANDONED` are declared on the entity
  and never written by anything.
- So: user starts checkout, closes the tab before paying → their `subscription_orders` row stays
  `PENDING` forever → the guard refuses every future checkout attempt forever → there is not even
  an admin action to clear it.

The review's own suggestions (resumable checkout + idempotent same-plan retry) are the fix, folded
into Task 2 below rather than treated as separate nice-to-haves — this is now a correctness fix to
already-merged code, not a new feature. Two of the review's other points (a consolidated billing
status endpoint, and surfacing a pending downgrade in that same response) turned out to already be
exactly what Task 2 builds; one point (distinguishing a retryable payment failure from a terminal
one) was checked against `RazorpayWebhookDispatcher.handlePending`/`handleHalted` and found to
already be correct — `subscription.pending` writes `Payment.STATUS_PENDING`, only
`subscription.halted` ever writes `STATUS_FAILED`, for the reasons design spec §4.6 already gives.
No change needed there. The review's admin "Subscription Health" dashboard suggestion is a good
idea deferred to its own later plan (new aggregate-count queries and a new admin view — real scope,
not a Plan 3 blocker, and more useful once real subscribers exist to measure).

## Global Constraints

- No AI attribution in commit messages (repo-wide `CLAUDE.md` rule).
- Work happens in this worktree/branch only; verify with `pwd`/`git branch --show-current` before
  any write, per this repo's shared-primary-checkout rule.
- Full TDD: failing test first, then minimal implementation, per task.
- No new Flyway migration in this plan — nothing here changes the schema.
- Frontend/admin-portal: no new npm dependency for Razorpay — the Checkout widget is a hosted
  script loaded once at runtime (mirrors `frontend/src/lib/googleIdentity.ts`'s exact pattern),
  not an SDK package.
- Every currency amount shown to a user is formatted `₹` + `Math.round(n).toLocaleString('en-IN')`
  — the existing convention in `BillingHistory.tsx`/`Budgets.tsx`, not a new one.
- `frontend`'s error-handling convention for a failed mutation is
  `e.response?.data?.message ?? '<fallback>'` stored in local component state (see `Goals.tsx`) —
  this app has no global toast/notification system (unlike `admin-portal`, which uses
  `useNotify()`). Follow whichever convention the file being edited already uses.

---

## Task 1: Fix `change-plan` to return checkout details for an upgrade

**Files:**
- Modify: `backend/src/main/java/com/finora/service/BillingCheckoutService.java`
- Modify: `backend/src/main/java/com/finora/controller/BillingController.java`
- Test: `backend/src/test/java/com/finora/service/BillingCheckoutServiceTest.java` (extend)
- Test: `backend/src/test/java/com/finora/controller/BillingControllerIT.java` (extend)
- Test: `backend/src/test/java/com/finora/controller/SubscriptionUpgradeDowngradeEndToEndIT.java` (extend)

**Interfaces:**
- Produces: `BillingCheckoutService.changePlan(UUID, String, String) : CheckoutResponseDto` (was
  `void`) — `null` for a downgrade or a same-plan no-op, populated for an upgrade. Reuses the
  existing `CheckoutResponseDto(String razorpaySubscriptionId, String keyId)` record from
  `BillingDtos` (Plan 1) — no new DTO.
- Consumes: `RazorpaySubscriptionDto` (Plan 1, unchanged), `RazorpayProperties.getKeyId()` (Plan 1,
  already a field on this service).

- [ ] **Step 1: Write the failing unit test**

Find the existing `changePlanInitiatesAnUpgradeByCreatingANewRazorpaySubscription` test in
`BillingCheckoutServiceTest.java`. It currently calls `service.changePlan(...)` without capturing
the return value at all — change that one line:

```java
        service.changePlan(userId, "PREMIUM", "MONTHLY");
```

to:

```java
        CheckoutResponseDto response = service.changePlan(userId, "PREMIUM", "MONTHLY");
```

and add these three assertions immediately after it, keeping every existing assertion in that test
(the `verify(gateway, never())...`, `verify(planChangeRepository, never())...`, and the
`SubscriptionOrder` capture block) exactly as they already are — this only adds coverage for the
return value, it doesn't replace anything the test already checks:

```java
        assertThat(response).isNotNull();
        assertThat(response.razorpaySubscriptionId()).isEqualTo("sub_new");
        assertThat(response.keyId()).isEqualTo("rzp_test_123");
```

Then add this new test alongside it:

```java
    @Test
    void changePlanReturnsNullForADowngrade() {
        UUID premiumPlanId = planId;
        UUID plusPlanId = UUID.randomUUID();
        Plan plus = new Plan();
        ReflectionTestUtils.setField(plus, "id", plusPlanId);
        plus.setCode("PLUS");
        when(planRepository.findByCode("PLUS")).thenReturn(Optional.of(plus));
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        when(planRepository.findById(premiumPlanId)).thenReturn(Optional.of(premium));

        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setPlanId(premiumPlanId);
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId("sub_existing");
        subscription.setRenewalDate(LocalDate.of(2026, 10, 5));
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(subscription));

        BillingPrice plusMonthly = new BillingPrice();
        plusMonthly.setPlanId(plusPlanId);
        plusMonthly.setBillingCycle("MONTHLY");
        plusMonthly.setPrice(new BigDecimal("399.00"));
        plusMonthly.setRazorpayPlanId("plan_plus_monthly");
        when(billingPriceRepository.findByPlanIdAndBillingCycleAndActiveTrue(plusPlanId, "MONTHLY"))
                .thenReturn(Optional.of(plusMonthly));

        CheckoutResponseDto response = service.changePlan(userId, "PLUS", "MONTHLY");

        assertThat(response).isNull();
    }
```

Also update the existing `changePlanIsANoOpWhenAlreadyOnTheRequestedPlanAndCycle` test to assert
the return value is `null`:

```java
        CheckoutResponseDto response = service.changePlan(userId, "PLUS", "MONTHLY");

        assertThat(response).isNull();
        verify(gateway, never()).updateSubscription(any(), any(), anyBoolean());
        verify(gateway, never()).createSubscription(any(), any(), anyMap());
```

(replacing that test's current `service.changePlan(userId, "PLUS", "MONTHLY");` bare call.)

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd backend && ./mvnw test -Dtest=BillingCheckoutServiceTest
```
Expected: FAIL — `changePlan` still returns `void`, so `CheckoutResponseDto response = service.changePlan(...)` does not compile.

- [ ] **Step 3: Change `BillingCheckoutService.changePlan`'s return type**

In `backend/src/main/java/com/finora/service/BillingCheckoutService.java`, change the method
signature and its two branches:

```java
    @Transactional
    public CheckoutResponseDto changePlan(UUID userId, String planCode, String billingCycle) {
        if ("FREE".equals(planCode)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Use POST /api/v1/billing/cancel to move to the Free plan.");
        }
        int newRank = TIER_ORDER.indexOf(planCode);
        if (newRank < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown plan code: " + planCode);
        }

        Subscription subscription = subscriptionRepository.findActiveOrTrial(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No active subscription."));
        if (subscription.getRazorpaySubscriptionId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "No billing subscription to change. Use POST /api/v1/billing/checkout first.");
        }
        Plan currentPlan = planRepository.findById(subscription.getPlanId())
                .orElseThrow(() -> new IllegalStateException("Subscription references a missing plan."));
        int currentRank = TIER_ORDER.indexOf(currentPlan.getCode());

        if (newRank == currentRank) {
            if (billingCycle.equals(subscription.getBillingCycle())) {
                return null;
            }
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Changing billing cycle without changing tier is not supported yet -- cancel and re-subscribe.");
        }

        Plan newPlan = planRepository.findByCode(planCode)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Unknown plan code: " + planCode));
        BillingPrice newPrice = billingPriceRepository.findByPlanIdAndBillingCycleAndActiveTrue(newPlan.getId(), billingCycle)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "No active price for " + planCode + "/" + billingCycle));
        if (newPrice.getRazorpayPlanId() == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "This plan is not yet set up for checkout (missing Razorpay plan id).");
        }

        if (newRank > currentRank) {
            return upgradeToNewSubscription(userId, newPlan, newPrice, billingCycle);
        }
        scheduleDowngrade(subscription, currentPlan, newPlan, newPrice.getRazorpayPlanId());
        return null;
    }
```

And `upgradeToNewSubscription`:

```java
    private CheckoutResponseDto upgradeToNewSubscription(UUID userId, Plan newPlan, BillingPrice newPrice, String billingCycle) {
        ensureNoOrderInFlight(userId);
        RazorpaySubscriptionDto razorpaySubscription = gateway.createSubscription(
                newPrice.getRazorpayPlanId(), billingCycle,
                Map.of("fynoraUserId", userId.toString(), "planCode", newPlan.getCode(), "billingCycle", billingCycle));

        SubscriptionOrder order = new SubscriptionOrder();
        order.setUserId(userId);
        order.setPlanId(newPlan.getId());
        order.setBillingCycle(billingCycle);
        order.setRazorpaySubscriptionId(razorpaySubscription.id());
        order.setStatus(SubscriptionOrder.STATUS_PENDING);
        order.setAmount(newPrice.getPrice());
        subscriptionOrderRepository.save(order);

        return new CheckoutResponseDto(razorpaySubscription.id(), properties.getKeyId());
    }
```

`scheduleDowngrade` is unchanged (still returns nothing — it's `void`, called for its side effect).

- [ ] **Step 4: Update `BillingController.changePlan`**

`CheckoutResponseDto` is already imported unqualified in this file (used by the existing
`checkout()` method) — reuse that import rather than qualifying it again:

```java
    @PostMapping("/change-plan")
    public ApiResponse<CheckoutResponseDto> changePlan(
            @Valid @RequestBody com.finora.dto.BillingDtos.UserChangePlanRequest request) {
        CheckoutResponseDto checkout = billingCheckoutService.changePlan(currentUser.id(), request.planCode(), request.billingCycle());
        return ApiResponse.ok(checkout, checkout != null
                ? "Upgrade started -- authorize the new subscription to activate it."
                : "Plan change requested");
    }
```

(`UserChangePlanRequest` stays fully qualified inline, matching this method's existing style —
only `CheckoutResponseDto` was already imported unqualified before this change.)

- [ ] **Step 5: Run the unit tests to verify they pass**

```bash
cd backend && ./mvnw test -Dtest=BillingCheckoutServiceTest
```
Expected: PASS.

- [ ] **Step 6: Extend `BillingControllerIT`**

Add, alongside the existing `changePlanSchedulesADowngradeForAnExistingPaidSubscriber` test:

```java
    @Test
    void changePlanReturnsCheckoutDetailsForAnUpgrade() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        subscription.setPlanId(plus.getId());
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId("sub_test_" + UUID.randomUUID());
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        BillingPrice premiumMonthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(planRepository.findByCode("PREMIUM").orElseThrow().getId(), "MONTHLY")
                .orElseThrow();
        premiumMonthly.setRazorpayPlanId("plan_test_" + UUID.randomUUID());
        billingPriceRepository.save(premiumMonthly);
        when(gateway.createSubscription(eq(premiumMonthly.getRazorpayPlanId()), eq("MONTHLY"), anyMap()))
                .thenReturn(new RazorpaySubscriptionDto("sub_new_" + UUID.randomUUID(), "created"));

        HttpEntity<String> request = new HttpEntity<>(
                "{\"planCode\":\"PREMIUM\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/billing/change-plan", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("razorpaySubscriptionId").contains("keyId");
    }
```

Add `import com.finora.integrations.razorpay.RazorpaySubscriptionDto;` and
`import static org.mockito.ArgumentMatchers.anyMap;` and
`import static org.mockito.ArgumentMatchers.eq;` to that file's imports if not already present.

- [ ] **Step 7: Extend the end-to-end test**

In `SubscriptionUpgradeDowngradeEndToEndIT.upgradeActivatesTheNewSubscriptionAndCancelsTheOldOne`,
after the existing "1. Request the upgrade" block, assert the response body now actually carries
what a real client needs:

```java
        assertThat(changePlanResponse.getBody()).contains(newRazorpaySubscriptionId).contains("keyId");
```

(Insert this line immediately after the existing
`assertThat(changePlanResponse.getStatusCode()).isEqualTo(HttpStatus.OK);` line in that test.)

- [ ] **Step 8: Run the full backend suite**

```bash
cd backend && ./mvnw test
```
Expected: PASS, no regressions.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/finora/service/BillingCheckoutService.java \
        backend/src/main/java/com/finora/controller/BillingController.java \
        backend/src/test/java/com/finora/service/BillingCheckoutServiceTest.java \
        backend/src/test/java/com/finora/controller/BillingControllerIT.java \
        backend/src/test/java/com/finora/controller/SubscriptionUpgradeDowngradeEndToEndIT.java
git commit -m "fix(backend): return checkout details from change-plan for an upgrade"
```

---

## Task 2: Self-service "my subscription" endpoint + resumable checkout + admin payment-provider field

**Files:**
- Modify: `backend/src/main/java/com/finora/dto/BillingDtos.java`
- Modify: `backend/src/main/java/com/finora/repository/SubscriptionOrderRepository.java`
- Modify: `backend/src/main/java/com/finora/service/BillingCheckoutService.java`
- Modify: `backend/src/main/java/com/finora/service/SubscriptionService.java` (admin `listAll`)
- Modify: `backend/src/main/java/com/finora/controller/BillingController.java`
- Test: `backend/src/test/java/com/finora/service/BillingCheckoutServiceTest.java` (extend)
- Test: `backend/src/test/java/com/finora/controller/BillingControllerIT.java` (extend)
- Test: `backend/src/test/java/com/finora/service/SubscriptionServiceTest.java` (extend)

**Interfaces:**
- Consumes: `PlanChangeRepository.findBySubscriptionIdOrderByCreatedAtDesc` (Plan 1, unchanged),
  `PlanChange.REASON_DOWNGRADE_SCHEDULED` (Plan 2, unchanged).
- Produces: `GET /api/v1/billing/subscription` → `BillingDtos.MySubscriptionDto` (now carrying a
  `pendingOrder` field, not just plan/renewal/pendingChange — see §0.5); `BillingCheckoutService
  .cancelPendingOrder(UUID) : void` and `POST /api/v1/billing/pending-order/cancel`; a changed
  internal contract for `checkout()`/`changePlan()`'s upgrade path, which now RESUME a same-plan
  pending order instead of refusing outright (§0.5's fix).

- [ ] **Step 1: Write the failing unit tests for `mySubscription`**

Add to `BillingCheckoutServiceTest.java`:

```java
    @Test
    void mySubscriptionReturnsThePlanAndRenewalDateForAPaidSubscriber() {
        UUID plusPlanId = planId; // reuse the PREMIUM-labelled fixture id from setUp() -- code doesn't matter here, only that findById resolves it
        Plan plus = new Plan();
        ReflectionTestUtils.setField(plus, "id", plusPlanId);
        plus.setCode("PLUS");
        plus.setName("Plus");
        when(planRepository.findById(plusPlanId)).thenReturn(Optional.of(plus));

        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setPlanId(plusPlanId);
        subscription.setBillingCycle("MONTHLY");
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setRazorpaySubscriptionId("sub_existing");
        subscription.setAutoRenew(true);
        subscription.setRenewalDate(LocalDate.of(2026, 10, 5));
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(subscription));
        when(planChangeRepository.findBySubscriptionIdOrderByCreatedAtDesc(subscription.getId()))
                .thenReturn(List.of());
        // Mockito's default answer for an Optional-returning method is Optional.empty() -- no
        // pending order in this scenario, so subscriptionOrderRepository needs no stub here.

        var dto = service.mySubscription(userId);

        assertThat(dto.planCode()).isEqualTo("PLUS");
        assertThat(dto.planName()).isEqualTo("Plus");
        assertThat(dto.billingCycle()).isEqualTo("MONTHLY");
        assertThat(dto.status()).isEqualTo("ACTIVE");
        assertThat(dto.renewalDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(dto.autoRenew()).isTrue();
        assertThat(dto.hasBillingSubscription()).isTrue();
        assertThat(dto.pendingChange()).isNull();
        assertThat(dto.pendingOrder()).isNull();
    }

    @Test
    void mySubscriptionSurfacesAPendingScheduledDowngrade() {
        UUID premiumPlanId = planId;
        UUID plusPlanId = UUID.randomUUID();
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        when(planRepository.findById(premiumPlanId)).thenReturn(Optional.of(premium));
        Plan plus = new Plan();
        ReflectionTestUtils.setField(plus, "id", plusPlanId);
        plus.setCode("PLUS");
        plus.setName("Plus");
        when(planRepository.findById(plusPlanId)).thenReturn(Optional.of(plus));

        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setPlanId(premiumPlanId); // still Premium -- the downgrade hasn't applied yet
        subscription.setBillingCycle("MONTHLY");
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setRazorpaySubscriptionId("sub_existing");
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(subscription));

        PlanChange scheduled = new PlanChange();
        scheduled.setSubscriptionId(subscription.getId());
        scheduled.setFromPlanId(premiumPlanId);
        scheduled.setToPlanId(plusPlanId);
        scheduled.setEffectiveAt(java.time.Instant.parse("2026-10-05T00:00:00Z"));
        scheduled.setReason(PlanChange.REASON_DOWNGRADE_SCHEDULED);
        when(planChangeRepository.findBySubscriptionIdOrderByCreatedAtDesc(subscription.getId()))
                .thenReturn(List.of(scheduled));

        var dto = service.mySubscription(userId);

        assertThat(dto.pendingChange()).isNotNull();
        assertThat(dto.pendingChange().toPlanCode()).isEqualTo("PLUS");
        assertThat(dto.pendingChange().toPlanName()).isEqualTo("Plus");
        assertThat(dto.pendingChange().effectiveAt()).isEqualTo(java.time.Instant.parse("2026-10-05T00:00:00Z"));
    }

    @Test
    void mySubscriptionOmitsAPendingChangeOnceItHasAlreadyApplied() {
        UUID plusPlanId = planId;
        Plan plus = new Plan();
        ReflectionTestUtils.setField(plus, "id", plusPlanId);
        plus.setCode("PLUS");
        plus.setName("Plus");
        when(planRepository.findById(plusPlanId)).thenReturn(Optional.of(plus));

        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setPlanId(plusPlanId); // already Plus -- the downgrade already reconciled
        subscription.setBillingCycle("MONTHLY");
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setRazorpaySubscriptionId("sub_existing");
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(subscription));

        PlanChange applied = new PlanChange();
        applied.setSubscriptionId(subscription.getId());
        applied.setFromPlanId(UUID.randomUUID());
        applied.setToPlanId(plusPlanId); // matches the subscription's CURRENT plan
        applied.setEffectiveAt(java.time.Instant.now());
        applied.setReason(PlanChange.REASON_DOWNGRADE_SCHEDULED);
        when(planChangeRepository.findBySubscriptionIdOrderByCreatedAtDesc(subscription.getId()))
                .thenReturn(List.of(applied));

        var dto = service.mySubscription(userId);

        assertThat(dto.pendingChange()).isNull();
    }

    @Test
    void mySubscriptionReflectsFreeWithNoBillingSubscription() {
        Subscription free = new Subscription();
        ReflectionTestUtils.setField(free, "id", UUID.randomUUID());
        free.setPlanId(planId);
        free.setStatus(Subscription.STATUS_ACTIVE);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(free));
        Plan freePlan = new Plan();
        ReflectionTestUtils.setField(freePlan, "id", planId);
        freePlan.setCode("FREE");
        freePlan.setName("Free");
        when(planRepository.findById(planId)).thenReturn(Optional.of(freePlan));
        when(planChangeRepository.findBySubscriptionIdOrderByCreatedAtDesc(free.getId())).thenReturn(List.of());

        var dto = service.mySubscription(userId);

        assertThat(dto.planCode()).isEqualTo("FREE");
        assertThat(dto.hasBillingSubscription()).isFalse();
        assertThat(dto.billingCycle()).isNull();
        assertThat(dto.renewalDate()).isNull();
    }

    @Test
    void mySubscriptionSurfacesAPendingOrderTheUserCanResumeOrCancel() {
        Subscription free = new Subscription();
        ReflectionTestUtils.setField(free, "id", UUID.randomUUID());
        free.setPlanId(planId);
        free.setStatus(Subscription.STATUS_ACTIVE);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(free));
        Plan freePlan = new Plan();
        ReflectionTestUtils.setField(freePlan, "id", planId);
        freePlan.setCode("FREE");
        freePlan.setName("Free");
        when(planRepository.findById(planId)).thenReturn(Optional.of(freePlan));
        when(planChangeRepository.findBySubscriptionIdOrderByCreatedAtDesc(free.getId())).thenReturn(List.of());

        UUID premiumPlanId = UUID.randomUUID();
        Plan premium = new Plan();
        ReflectionTestUtils.setField(premium, "id", premiumPlanId);
        premium.setCode("PREMIUM");
        premium.setName("Premium");
        when(planRepository.findById(premiumPlanId)).thenReturn(Optional.of(premium));

        com.finora.entity.SubscriptionOrder order = new com.finora.entity.SubscriptionOrder();
        order.setPlanId(premiumPlanId);
        order.setBillingCycle("YEARLY");
        order.setRazorpaySubscriptionId("sub_abandoned");
        order.setStatus(com.finora.entity.SubscriptionOrder.STATUS_PENDING);
        when(subscriptionOrderRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(
                userId, com.finora.entity.SubscriptionOrder.STATUS_PENDING))
                .thenReturn(Optional.of(order));

        var dto = service.mySubscription(userId);

        assertThat(dto.pendingOrder()).isNotNull();
        assertThat(dto.pendingOrder().planCode()).isEqualTo("PREMIUM");
        assertThat(dto.pendingOrder().planName()).isEqualTo("Premium");
        assertThat(dto.pendingOrder().billingCycle()).isEqualTo("YEARLY");
        assertThat(dto.pendingOrder().razorpaySubscriptionId()).isEqualTo("sub_abandoned");
        assertThat(dto.pendingOrder().keyId()).isEqualTo("rzp_test_123");
    }
```

Add `import com.finora.entity.PlanChange;` and `import java.util.List;` to that test file's
imports if not already present (`PlanChange` and `List` are both already imported by the existing
downgrade test in this file, so this is likely a no-op check, not a real addition).

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd backend && ./mvnw test -Dtest=BillingCheckoutServiceTest
```
Expected: FAIL — `service.mySubscription` does not exist yet.

- [ ] **Step 3: Add `MySubscriptionDto`/`PendingOrderDto` to `BillingDtos.java`**

```java
    /** GET /api/v1/billing/subscription -- what the web/mobile Billing Portal reads. Distinct from
     *  {@link EntitlementsDto} (which only carries plan/features, for gating) and from
     *  {@link SubscriptionSummaryDto} (the admin list row, keyed by userId/email for a table, not
     *  by "the caller's own subscription"). */
    public record MySubscriptionDto(
            String planCode, String planName, String billingCycle, String status,
            LocalDate renewalDate, boolean autoRenew, boolean hasBillingSubscription,
            PendingPlanChangeDto pendingChange, PendingOrderDto pendingOrder
    ) {}

    /** Null on {@link MySubscriptionDto} unless a downgrade has been scheduled (design spec §6.4)
     *  and not yet reconciled -- see {@code BillingCheckoutService.mySubscription}'s own doc
     *  comment for how "not yet reconciled" is detected. */
    public record PendingPlanChangeDto(String toPlanCode, String toPlanName, Instant effectiveAt) {}

    /** Non-null on {@link MySubscriptionDto} exactly when a {@code subscription_orders} row is
     *  still {@code PENDING} for this user -- an abandoned or in-flight checkout the Billing
     *  Portal can offer to resume (the same {@code razorpaySubscriptionId}/{@code keyId} Checkout
     *  needs, with no new Razorpay call) or cancel via
     *  {@code POST /api/v1/billing/pending-order/cancel}. Added during Plan 3 review: before this,
     *  nothing gave a user visibility into, or a way to clear, a stuck pending order -- see this
     *  plan's §0.5 for the bug that made that a real dead end, not just a UX gap. */
    public record PendingOrderDto(String planCode, String planName, String billingCycle,
                                   String razorpaySubscriptionId, String keyId) {}
```

- [ ] **Step 4: Add the repository finder**

In `SubscriptionOrderRepository.java`, replace `existsByUserIdAndStatus` (added in PR #1016,
superseded by this task's resumable-checkout logic, which needs the actual order, not just its
existence) with a finder that returns it:

```java
public interface SubscriptionOrderRepository extends JpaRepository<SubscriptionOrder, UUID> {
    Optional<SubscriptionOrder> findByRazorpaySubscriptionId(String razorpaySubscriptionId);

    /** Subscription billing V3 (design spec review, §0.5 of the V3 plan). What both
     *  {@code mySubscription} (read) and {@code resumableOrderOrGuard} (checkout/upgrade) use to
     *  find a user's still-in-flight checkout -- "first" only matters if more than one PENDING
     *  order ever exists for one user, which itself would be its own bug; ordering by
     *  createdAt desc is defensive, not load-bearing. */
    Optional<SubscriptionOrder> findFirstByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, String status);
}
```

(This deletes the `existsByUserIdAndStatus` method entirely -- it has no remaining caller after
this task's Step 6 rewrites the one method that used it.)

- [ ] **Step 5: Add `mySubscription` and `cancelPendingOrder` to `BillingCheckoutService.java`**

```java
    @Transactional(readOnly = true)
    public MySubscriptionDto mySubscription(UUID userId) {
        Subscription subscription = subscriptionRepository.findActiveOrTrial(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No active subscription."));
        Plan plan = planRepository.findById(subscription.getPlanId())
                .orElseThrow(() -> new IllegalStateException("Subscription references a missing plan."));

        // A scheduled downgrade (Plan 2, §6.4) writes a plan_changes row immediately, at request
        // time -- long before it actually takes effect at the next subscription.charged webhook.
        // The most recent row is "still pending" exactly when its target plan doesn't match what
        // the subscription is on RIGHT NOW: once handleCharged reconciles plan_id to match, this
        // same query naturally stops returning it as pending, with no separate "applied" flag to
        // maintain.
        PendingPlanChangeDto pendingChange = planChangeRepository
                .findBySubscriptionIdOrderByCreatedAtDesc(subscription.getId()).stream()
                .findFirst()
                .filter(c -> PlanChange.REASON_DOWNGRADE_SCHEDULED.equals(c.getReason()))
                .filter(c -> !c.getToPlanId().equals(subscription.getPlanId()))
                .map(c -> {
                    Plan toPlan = planRepository.findById(c.getToPlanId()).orElse(null);
                    return new PendingPlanChangeDto(
                            toPlan != null ? toPlan.getCode() : null,
                            toPlan != null ? toPlan.getName() : null,
                            c.getEffectiveAt());
                })
                .orElse(null);

        PendingOrderDto pendingOrder = subscriptionOrderRepository
                .findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, SubscriptionOrder.STATUS_PENDING)
                .map(order -> {
                    Plan orderPlan = planRepository.findById(order.getPlanId()).orElse(null);
                    return new PendingOrderDto(
                            orderPlan != null ? orderPlan.getCode() : null,
                            orderPlan != null ? orderPlan.getName() : null,
                            order.getBillingCycle(), order.getRazorpaySubscriptionId(), properties.getKeyId());
                })
                .orElse(null);

        return new MySubscriptionDto(
                plan.getCode(), plan.getName(), subscription.getBillingCycle(), subscription.getStatus(),
                subscription.getRenewalDate(), subscription.isAutoRenew(),
                subscription.getRazorpaySubscriptionId() != null, pendingChange, pendingOrder);
    }

    /** §0.5 of this plan. Gives {@code SubscriptionOrder.STATUS_ABANDONED} its first real writer
     *  -- without this, a user who abandons checkout (or wants a DIFFERENT plan than the one
     *  they have a stale pending order for) has no way to ever check out again, since
     *  {@code resumableOrderOrGuard} below refuses every subsequent attempt for a different plan
     *  forever otherwise. Deliberately does not call the Razorpay API: a "created" Razorpay
     *  subscription that was never authorized never charges anything (Razorpay's own model), so
     *  nothing on Razorpay's side needs stopping -- only Fynora's own "this user has a checkout
     *  in flight" bookkeeping needs clearing. */
    @Transactional
    public void cancelPendingOrder(UUID userId) {
        SubscriptionOrder order = subscriptionOrderRepository
                .findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, SubscriptionOrder.STATUS_PENDING)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No pending checkout to cancel."));
        order.setStatus(SubscriptionOrder.STATUS_ABANDONED);
        subscriptionOrderRepository.save(order);
    }
```

Add these imports to `BillingCheckoutService.java`, alongside its existing
`import com.finora.dto.BillingDtos.CheckoutResponseDto;`:

```java
import com.finora.dto.BillingDtos.MySubscriptionDto;
import com.finora.dto.BillingDtos.PendingOrderDto;
import com.finora.dto.BillingDtos.PendingPlanChangeDto;
import com.finora.entity.PlanChange;
```

- [ ] **Step 6: Replace `ensureNoOrderInFlight` with a resumable version, and update its two callers**

Replace the existing `ensureNoOrderInFlight` method (added in PR #1016) entirely:

```java
    /** Closes the double-submit window between "create the Razorpay subscription" and "the
     *  activation webhook lands" -- WITHOUT creating a dead end for a user who simply abandoned
     *  checkout (§0.5 of this plan): a double-tap, retry, or a genuinely abandoned first attempt
     *  at the SAME plan+cycle resumes the existing pending order's already-created Razorpay
     *  subscription instead of either creating a second one (the original PR #1016 bug this
     *  guard fixed) or refusing forever (the bug THIS guard introduced). A pending order for a
     *  DIFFERENT plan+cycle still blocks -- {@code cancelPendingOrder} above is how the caller
     *  clears that to start over.
     *
     *  @return an existing, resumable {@link CheckoutResponseDto} if one applies, or {@code null}
     *  if the caller should proceed to create a new Razorpay subscription. */
    private CheckoutResponseDto resumableOrderOrGuard(UUID userId, UUID planId, String billingCycle) {
        return subscriptionOrderRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, SubscriptionOrder.STATUS_PENDING)
                .map(order -> {
                    if (order.getPlanId().equals(planId) && order.getBillingCycle().equals(billingCycle)) {
                        return new CheckoutResponseDto(order.getRazorpaySubscriptionId(), properties.getKeyId());
                    }
                    throw new ApiException(HttpStatus.CONFLICT,
                            "You have a checkout already in progress for a different plan. " +
                            "Cancel it (POST /api/v1/billing/pending-order/cancel) before starting a new one.");
                })
                .orElse(null);
    }
```

Update `checkout()` to call it AFTER resolving `plan`/`price` (it needs `plan.getId()`), not
before -- this reorders the method's existing checks, it does not change what any of them do:

```java
    @Transactional
    public CheckoutResponseDto checkout(UUID userId, String planCode, String billingCycle) {
        if (!gateway.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Billing is not available yet.");
        }

        subscriptionRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().findFirst()
                .filter(s -> s.getRazorpaySubscriptionId() != null)
                .ifPresent(s -> {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "You already have a billing subscription. Cancel it before starting a new one.");
                });

        Plan plan = planRepository.findByCode(planCode)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Unknown plan code: " + planCode));
        BillingPrice price = billingPriceRepository.findByPlanIdAndBillingCycleAndActiveTrue(plan.getId(), billingCycle)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "No active price for " + planCode + "/" + billingCycle));
        if (price.getRazorpayPlanId() == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "This plan is not yet set up for checkout (missing Razorpay plan id).");
        }

        CheckoutResponseDto resumable = resumableOrderOrGuard(userId, plan.getId(), billingCycle);
        if (resumable != null) {
            return resumable;
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
```

Update `upgradeToNewSubscription()` (as it stands after Task 1's fix) the same way:

```java
    private CheckoutResponseDto upgradeToNewSubscription(UUID userId, Plan newPlan, BillingPrice newPrice, String billingCycle) {
        CheckoutResponseDto resumable = resumableOrderOrGuard(userId, newPlan.getId(), billingCycle);
        if (resumable != null) {
            return resumable;
        }

        RazorpaySubscriptionDto razorpaySubscription = gateway.createSubscription(
                newPrice.getRazorpayPlanId(), billingCycle,
                Map.of("fynoraUserId", userId.toString(), "planCode", newPlan.getCode(), "billingCycle", billingCycle));

        SubscriptionOrder order = new SubscriptionOrder();
        order.setUserId(userId);
        order.setPlanId(newPlan.getId());
        order.setBillingCycle(billingCycle);
        order.setRazorpaySubscriptionId(razorpaySubscription.id());
        order.setStatus(SubscriptionOrder.STATUS_PENDING);
        order.setAmount(newPrice.getPrice());
        subscriptionOrderRepository.save(order);

        return new CheckoutResponseDto(razorpaySubscription.id(), properties.getKeyId());
    }
```

- [ ] **Step 7: Update the two existing PR #1016 tests that stubbed the now-deleted `existsByUserIdAndStatus`**

`BillingCheckoutServiceTest.java` has two tests from the original (buggy) guard that must change
to match the new resumable behavior, not just keep compiling. Find
`refusesCheckoutWhenAPendingOrderAlreadyExistsForThisUser`:

```java
    @Test
    void refusesCheckoutWhenAPendingOrderAlreadyExistsForThisUser() {
        when(subscriptionOrderRepository.existsByUserIdAndStatus(userId, com.finora.entity.SubscriptionOrder.STATUS_PENDING))
                .thenReturn(true);

        assertThatThrownBy(() -> service.checkout(userId, "PREMIUM", "MONTHLY"))
                .isInstanceOf(ApiException.class);

        verify(gateway, never()).createSubscription(any(), any(), anyMap());
    }
```

Replace it with two tests covering both branches of the new behavior:

```java
    @Test
    void checkoutResumesAnExistingPendingOrderForTheSamePlanAndCycle() {
        BillingPrice price = new BillingPrice();
        price.setPlanId(planId);
        price.setBillingCycle("MONTHLY");
        price.setPrice(new BigDecimal("799.00"));
        price.setRazorpayPlanId("plan_razorpay_123");
        when(billingPriceRepository.findByPlanIdAndBillingCycleAndActiveTrue(eq(planId), eq("MONTHLY")))
                .thenReturn(Optional.of(price));

        com.finora.entity.SubscriptionOrder existingOrder = new com.finora.entity.SubscriptionOrder();
        existingOrder.setPlanId(planId);
        existingOrder.setBillingCycle("MONTHLY");
        existingOrder.setRazorpaySubscriptionId("sub_already_created");
        existingOrder.setStatus(com.finora.entity.SubscriptionOrder.STATUS_PENDING);
        when(subscriptionOrderRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(
                userId, com.finora.entity.SubscriptionOrder.STATUS_PENDING))
                .thenReturn(Optional.of(existingOrder));

        CheckoutResponseDto response = service.checkout(userId, "PREMIUM", "MONTHLY");

        assertThat(response.razorpaySubscriptionId()).isEqualTo("sub_already_created");
        verify(gateway, never()).createSubscription(any(), any(), anyMap());
        verify(subscriptionOrderRepository, never()).save(any());
    }

    @Test
    void checkoutRefusesADifferentPlanWhileAnotherIsPending() {
        BillingPrice price = new BillingPrice();
        price.setPlanId(planId);
        price.setBillingCycle("MONTHLY");
        price.setPrice(new BigDecimal("799.00"));
        price.setRazorpayPlanId("plan_razorpay_123");
        when(billingPriceRepository.findByPlanIdAndBillingCycleAndActiveTrue(eq(planId), eq("MONTHLY")))
                .thenReturn(Optional.of(price));

        com.finora.entity.SubscriptionOrder existingOrder = new com.finora.entity.SubscriptionOrder();
        existingOrder.setPlanId(UUID.randomUUID()); // a DIFFERENT plan than the one being requested
        existingOrder.setBillingCycle("MONTHLY");
        existingOrder.setRazorpaySubscriptionId("sub_other_plan");
        existingOrder.setStatus(com.finora.entity.SubscriptionOrder.STATUS_PENDING);
        when(subscriptionOrderRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(
                userId, com.finora.entity.SubscriptionOrder.STATUS_PENDING))
                .thenReturn(Optional.of(existingOrder));

        assertThatThrownBy(() -> service.checkout(userId, "PREMIUM", "MONTHLY"))
                .isInstanceOf(ApiException.class);

        verify(gateway, never()).createSubscription(any(), any(), anyMap());
    }

    @Test
    void cancelPendingOrderMarksItAbandoned() {
        com.finora.entity.SubscriptionOrder order = new com.finora.entity.SubscriptionOrder();
        order.setStatus(com.finora.entity.SubscriptionOrder.STATUS_PENDING);
        when(subscriptionOrderRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(
                userId, com.finora.entity.SubscriptionOrder.STATUS_PENDING))
                .thenReturn(Optional.of(order));

        service.cancelPendingOrder(userId);

        assertThat(order.getStatus()).isEqualTo(com.finora.entity.SubscriptionOrder.STATUS_ABANDONED);
        verify(subscriptionOrderRepository).save(order);
        verify(gateway, never()).cancelSubscription(any(), anyBoolean());
    }

    @Test
    void cancelPendingOrderThrowsWhenNoneExists() {
        assertThatThrownBy(() -> service.cancelPendingOrder(userId))
                .isInstanceOf(ApiException.class);
    }
```

Also find `changePlanRefusesAnUpgradeWhenAPendingOrderAlreadyExistsForThisUser` (also from PR
#1016, stubbing the same now-deleted method) and update its stub the same way -- replace:

```java
        when(subscriptionOrderRepository.existsByUserIdAndStatus(userId, com.finora.entity.SubscriptionOrder.STATUS_PENDING))
                .thenReturn(true);
```

with:

```java
        com.finora.entity.SubscriptionOrder existingOrder = new com.finora.entity.SubscriptionOrder();
        existingOrder.setPlanId(UUID.randomUUID()); // different plan than PREMIUM, still blocks
        existingOrder.setBillingCycle("MONTHLY");
        existingOrder.setStatus(com.finora.entity.SubscriptionOrder.STATUS_PENDING);
        when(subscriptionOrderRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(
                userId, com.finora.entity.SubscriptionOrder.STATUS_PENDING))
                .thenReturn(Optional.of(existingOrder));
```

(leaving the rest of that test -- its `assertThatThrownBy`/`verify` lines -- unchanged).

- [ ] **Step 8: Run the unit tests to verify they pass**

```bash
cd backend && ./mvnw test -Dtest=BillingCheckoutServiceTest
```
Expected: PASS.

- [ ] **Step 9: Add the endpoints to `BillingController.java`**

```java
    @GetMapping("/subscription")
    public ApiResponse<MySubscriptionDto> mySubscription() {
        return ApiResponse.ok(billingCheckoutService.mySubscription(currentUser.id()));
    }

    @PostMapping("/pending-order/cancel")
    public ApiResponse<Void> cancelPendingOrder() {
        billingCheckoutService.cancelPendingOrder(currentUser.id());
        return ApiResponse.ok(null, "Pending checkout cancelled");
    }
```

Add `import com.finora.dto.BillingDtos.MySubscriptionDto;` and
`import org.springframework.web.bind.annotation.GetMapping;` to that file's imports.

- [ ] **Step 10: Write the failing integration tests**

Add to `BillingControllerIT.java`:

```java
    @Test
    void mySubscriptionReportsThePlanAndRenewalDate() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        subscription.setPlanId(plus.getId());
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId("sub_test_" + UUID.randomUUID());
        subscription.setPaymentProvider("RAZORPAY");
        subscription.setRenewalDate(java.time.LocalDate.of(2026, 11, 1));
        subscriptionRepository.save(subscription);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/billing/subscription", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"planCode\":\"PLUS\"").contains("2026-11-01").contains("\"hasBillingSubscription\":true");
    }

    @Test
    void aSecondCheckoutForTheSamePlanResumesTheFirstInsteadOfCreatingAnother() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        when(gateway.createSubscription(any(), any(), anyMap()))
                .thenReturn(new RazorpaySubscriptionDto("sub_first_attempt", "created"));

        HttpEntity<String> request = new HttpEntity<>(
                "{\"planCode\":\"PREMIUM\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user));
        ResponseEntity<String> first = restTemplate.postForEntity("/api/v1/billing/checkout", request, String.class);
        assertThat(first.getBody()).contains("sub_first_attempt");

        ResponseEntity<String> second = restTemplate.postForEntity("/api/v1/billing/checkout", request, String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).contains("sub_first_attempt");
        verify(gateway, times(1)).createSubscription(any(), any(), anyMap());
    }

    @Test
    void cancellingAPendingOrderAllowsCheckingOutADifferentPlanAfterwards() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        when(gateway.createSubscription(any(), any(), anyMap()))
                .thenReturn(new RazorpaySubscriptionDto("sub_premium_attempt", "created"))
                .thenReturn(new RazorpaySubscriptionDto("sub_plus_attempt", "created"));

        restTemplate.postForEntity("/api/v1/billing/checkout",
                new HttpEntity<>("{\"planCode\":\"PREMIUM\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user)), String.class);

        ResponseEntity<String> blocked = restTemplate.postForEntity("/api/v1/billing/checkout",
                new HttpEntity<>("{\"planCode\":\"PLUS\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user)), String.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<String> cancel = restTemplate.postForEntity(
                "/api/v1/billing/pending-order/cancel", new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> retried = restTemplate.postForEntity("/api/v1/billing/checkout",
                new HttpEntity<>("{\"planCode\":\"PLUS\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user)), String.class);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retried.getBody()).contains("sub_plus_attempt");
    }
```

Add `import static org.mockito.Mockito.times;` to that file's imports if not already present.

- [ ] **Step 11: Run tests to verify they pass**

```bash
cd backend && ./mvnw test -Dtest=BillingControllerIT
```
Expected: PASS.

- [ ] **Step 12: Add `paymentProvider` to the admin `SubscriptionSummaryDto`**

In `BillingDtos.java`:

```java
    /** Admin Portal, Subscription Management -- one row per user's current subscription.
     *  paymentProvider (Plan 3) is what the admin UI reads to know whether the plain FREE/PLUS/
     *  PREMIUM dropdown is safe to fire directly, or whether it must go through the
     *  cancel-paid-subscription confirm flow first (design spec §6.6) -- "RAZORPAY" means a live
     *  Razorpay mandate exists, "ADMIN_GRANT" or null means it doesn't. */
    public record SubscriptionSummaryDto(
            UUID subscriptionId, UUID userId, String userEmail, String userFullName,
            String planCode, String planName, String status, String paymentProvider,
            LocalDate startDate, LocalDate endDate, LocalDate renewalDate
    ) {}
```

- [ ] **Step 13: Write the failing unit test for the admin list**

Add to `SubscriptionServiceTest.java` (find the existing `listAll` test and add this alongside
it):

```java
    @Test
    void listAllIncludesThePaymentProviderForEachRow() {
        Subscription sub = new Subscription();
        ReflectionTestUtils.setField(sub, "id", UUID.randomUUID());
        sub.setUserId(UUID.randomUUID());
        sub.setPlanId(UUID.randomUUID());
        sub.setStatus(Subscription.STATUS_ACTIVE);
        sub.setStartDate(LocalDate.now());
        sub.setPaymentProvider("RAZORPAY");
        Page<Subscription> page = new PageImpl<>(List.of(sub));
        when(subscriptionRepository.findAllByOrderByCreatedAtDesc(any())).thenReturn(page);
        when(planRepository.findAll()).thenReturn(List.of());
        when(userRepository.findAllById(any())).thenReturn(List.of());

        var result = service.listAll(0, 20);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).paymentProvider()).isEqualTo("RAZORPAY");
    }
```

Add `import org.springframework.data.domain.PageImpl;` to that test file's imports if not already
present.

- [ ] **Step 14: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=SubscriptionServiceTest
```
Expected: FAIL — `SubscriptionSummaryDto` doesn't have a `paymentProvider()` accessor's worth of
data being passed in `listAll`'s mapping yet (compiles once Step 12 lands, but the field will be
`null` until Step 15).

- [ ] **Step 15: Populate the field in `SubscriptionService.listAll`**

```java
        return PagedResponse.of(subscriptions.map(s -> {
            Plan plan = plansById.get(s.getPlanId());
            User user = usersById.get(s.getUserId());
            return new SubscriptionSummaryDto(
                    s.getId(), s.getUserId(),
                    user != null ? user.getEmail() : null, user != null ? user.getFullName() : null,
                    plan != null ? plan.getCode() : null, plan != null ? plan.getName() : null,
                    s.getStatus(), s.getPaymentProvider(), s.getStartDate(), s.getEndDate(), s.getRenewalDate());
        }));
```

(This replaces the existing `return PagedResponse.of(...)` block in `listAll` — same structure,
one more constructor argument.)

- [ ] **Step 16: Run the full backend suite**

```bash
cd backend && ./mvnw test
```
Expected: PASS, no regressions (in particular, `AdminSubscriptionControllerIT`'s existing
assertions on `SubscriptionSummaryDto`'s JSON shape should still pass since this only adds a
field).

- [ ] **Step 17: Commit**

```bash
git add backend/src/main/java/com/finora/dto/BillingDtos.java \
        backend/src/main/java/com/finora/repository/SubscriptionOrderRepository.java \
        backend/src/main/java/com/finora/service/BillingCheckoutService.java \
        backend/src/main/java/com/finora/service/SubscriptionService.java \
        backend/src/main/java/com/finora/controller/BillingController.java \
        backend/src/test/java/com/finora/service/BillingCheckoutServiceTest.java \
        backend/src/test/java/com/finora/controller/BillingControllerIT.java \
        backend/src/test/java/com/finora/service/SubscriptionServiceTest.java
git commit -m "fix(backend): make abandoned checkouts recoverable instead of a permanent lockout"
```

---

- [ ] **Step 1: Write the failing unit tests**

Add to `BillingCheckoutServiceTest.java`:

```java
    @Test
    void mySubscriptionReturnsThePlanAndRenewalDateForAPaidSubscriber() {
        UUID plusPlanId = planId; // reuse the PREMIUM-labelled fixture id from setUp() -- code doesn't matter here, only that findById resolves it
        Plan plus = new Plan();
        ReflectionTestUtils.setField(plus, "id", plusPlanId);
        plus.setCode("PLUS");
        plus.setName("Plus");
        when(planRepository.findById(plusPlanId)).thenReturn(Optional.of(plus));

        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setPlanId(plusPlanId);
        subscription.setBillingCycle("MONTHLY");
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setRazorpaySubscriptionId("sub_existing");
        subscription.setAutoRenew(true);
        subscription.setRenewalDate(LocalDate.of(2026, 10, 5));
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(subscription));
        when(planChangeRepository.findBySubscriptionIdOrderByCreatedAtDesc(subscription.getId()))
                .thenReturn(List.of());

        var dto = service.mySubscription(userId);

        assertThat(dto.planCode()).isEqualTo("PLUS");
        assertThat(dto.planName()).isEqualTo("Plus");
        assertThat(dto.billingCycle()).isEqualTo("MONTHLY");
        assertThat(dto.status()).isEqualTo("ACTIVE");
        assertThat(dto.renewalDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(dto.autoRenew()).isTrue();
        assertThat(dto.hasBillingSubscription()).isTrue();
        assertThat(dto.pendingChange()).isNull();
    }

    @Test
    void mySubscriptionSurfacesAPendingScheduledDowngrade() {
        UUID premiumPlanId = planId;
        UUID plusPlanId = UUID.randomUUID();
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        when(planRepository.findById(premiumPlanId)).thenReturn(Optional.of(premium));
        Plan plus = new Plan();
        ReflectionTestUtils.setField(plus, "id", plusPlanId);
        plus.setCode("PLUS");
        plus.setName("Plus");
        when(planRepository.findById(plusPlanId)).thenReturn(Optional.of(plus));

        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setPlanId(premiumPlanId); // still Premium -- the downgrade hasn't applied yet
        subscription.setBillingCycle("MONTHLY");
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setRazorpaySubscriptionId("sub_existing");
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(subscription));

        PlanChange scheduled = new PlanChange();
        scheduled.setSubscriptionId(subscription.getId());
        scheduled.setFromPlanId(premiumPlanId);
        scheduled.setToPlanId(plusPlanId);
        scheduled.setEffectiveAt(java.time.Instant.parse("2026-10-05T00:00:00Z"));
        scheduled.setReason(PlanChange.REASON_DOWNGRADE_SCHEDULED);
        when(planChangeRepository.findBySubscriptionIdOrderByCreatedAtDesc(subscription.getId()))
                .thenReturn(List.of(scheduled));

        var dto = service.mySubscription(userId);

        assertThat(dto.pendingChange()).isNotNull();
        assertThat(dto.pendingChange().toPlanCode()).isEqualTo("PLUS");
        assertThat(dto.pendingChange().toPlanName()).isEqualTo("Plus");
        assertThat(dto.pendingChange().effectiveAt()).isEqualTo(java.time.Instant.parse("2026-10-05T00:00:00Z"));
    }

    @Test
    void mySubscriptionOmitsAPendingChangeOnceItHasAlreadyApplied() {
        UUID plusPlanId = planId;
        Plan plus = new Plan();
        ReflectionTestUtils.setField(plus, "id", plusPlanId);
        plus.setCode("PLUS");
        plus.setName("Plus");
        when(planRepository.findById(plusPlanId)).thenReturn(Optional.of(plus));

        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setPlanId(plusPlanId); // already Plus -- the downgrade already reconciled
        subscription.setBillingCycle("MONTHLY");
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setRazorpaySubscriptionId("sub_existing");
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(subscription));

        PlanChange applied = new PlanChange();
        applied.setSubscriptionId(subscription.getId());
        applied.setFromPlanId(UUID.randomUUID());
        applied.setToPlanId(plusPlanId); // matches the subscription's CURRENT plan
        applied.setEffectiveAt(java.time.Instant.now());
        applied.setReason(PlanChange.REASON_DOWNGRADE_SCHEDULED);
        when(planChangeRepository.findBySubscriptionIdOrderByCreatedAtDesc(subscription.getId()))
                .thenReturn(List.of(applied));

        var dto = service.mySubscription(userId);

        assertThat(dto.pendingChange()).isNull();
    }

    @Test
    void mySubscriptionReflectsFreeWithNoBillingSubscription() {
        Subscription free = new Subscription();
        ReflectionTestUtils.setField(free, "id", UUID.randomUUID());
        free.setPlanId(planId);
        free.setStatus(Subscription.STATUS_ACTIVE);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(free));
        Plan freePlan = new Plan();
        ReflectionTestUtils.setField(freePlan, "id", planId);
        freePlan.setCode("FREE");
        freePlan.setName("Free");
        when(planRepository.findById(planId)).thenReturn(Optional.of(freePlan));
        when(planChangeRepository.findBySubscriptionIdOrderByCreatedAtDesc(free.getId())).thenReturn(List.of());

        var dto = service.mySubscription(userId);

        assertThat(dto.planCode()).isEqualTo("FREE");
        assertThat(dto.hasBillingSubscription()).isFalse();
        assertThat(dto.billingCycle()).isNull();
        assertThat(dto.renewalDate()).isNull();
    }
```

Add `import com.finora.entity.PlanChange;` and `import java.util.List;` to that test file's
imports if not already present (`PlanChange` and `List` are both already imported by the existing
downgrade test in this file, so this is likely a no-op check, not a real addition).

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd backend && ./mvnw test -Dtest=BillingCheckoutServiceTest
```
Expected: FAIL — `service.mySubscription` does not exist yet.

- [ ] **Step 3: Add `MySubscriptionDto` to `BillingDtos.java`**

```java
    /** GET /api/v1/billing/subscription -- what the web/mobile Billing Portal reads. Distinct from
     *  {@link EntitlementsDto} (which only carries plan/features, for gating) and from
     *  {@link SubscriptionSummaryDto} (the admin list row, keyed by userId/email for a table, not
     *  by "the caller's own subscription"). */
    public record MySubscriptionDto(
            String planCode, String planName, String billingCycle, String status,
            LocalDate renewalDate, boolean autoRenew, boolean hasBillingSubscription,
            PendingPlanChangeDto pendingChange
    ) {}

    /** Null on {@link MySubscriptionDto} unless a downgrade has been scheduled (design spec §6.4)
     *  and not yet reconciled -- see {@code BillingCheckoutService.mySubscription}'s own doc
     *  comment for how "not yet reconciled" is detected. */
    public record PendingPlanChangeDto(String toPlanCode, String toPlanName, Instant effectiveAt) {}
```

- [ ] **Step 4: Add `mySubscription` to `BillingCheckoutService.java`**

```java
    @Transactional(readOnly = true)
    public MySubscriptionDto mySubscription(UUID userId) {
        Subscription subscription = subscriptionRepository.findActiveOrTrial(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No active subscription."));
        Plan plan = planRepository.findById(subscription.getPlanId())
                .orElseThrow(() -> new IllegalStateException("Subscription references a missing plan."));

        // A scheduled downgrade (Plan 2, §6.4) writes a plan_changes row immediately, at request
        // time -- long before it actually takes effect at the next subscription.charged webhook.
        // The most recent row is "still pending" exactly when its target plan doesn't match what
        // the subscription is on RIGHT NOW: once handleCharged reconciles plan_id to match, this
        // same query naturally stops returning it as pending, with no separate "applied" flag to
        // maintain.
        PendingPlanChangeDto pendingChange = planChangeRepository
                .findBySubscriptionIdOrderByCreatedAtDesc(subscription.getId()).stream()
                .findFirst()
                .filter(c -> PlanChange.REASON_DOWNGRADE_SCHEDULED.equals(c.getReason()))
                .filter(c -> !c.getToPlanId().equals(subscription.getPlanId()))
                .map(c -> {
                    Plan toPlan = planRepository.findById(c.getToPlanId()).orElse(null);
                    return new PendingPlanChangeDto(
                            toPlan != null ? toPlan.getCode() : null,
                            toPlan != null ? toPlan.getName() : null,
                            c.getEffectiveAt());
                })
                .orElse(null);

        return new MySubscriptionDto(
                plan.getCode(), plan.getName(), subscription.getBillingCycle(), subscription.getStatus(),
                subscription.getRenewalDate(), subscription.isAutoRenew(),
                subscription.getRazorpaySubscriptionId() != null, pendingChange);
    }
```

Add these imports to `BillingCheckoutService.java`, alongside its existing
`import com.finora.dto.BillingDtos.CheckoutResponseDto;`:

```java
import com.finora.dto.BillingDtos.MySubscriptionDto;
import com.finora.dto.BillingDtos.PendingPlanChangeDto;
import com.finora.entity.PlanChange;
```

- [ ] **Step 5: Run the unit tests to verify they pass**

```bash
cd backend && ./mvnw test -Dtest=BillingCheckoutServiceTest
```
Expected: PASS.

- [ ] **Step 6: Add the endpoint to `BillingController.java`**

```java
    @GetMapping("/subscription")
    public ApiResponse<MySubscriptionDto> mySubscription() {
        return ApiResponse.ok(billingCheckoutService.mySubscription(currentUser.id()));
    }
```

Add `import com.finora.dto.BillingDtos.MySubscriptionDto;` and
`import org.springframework.web.bind.annotation.GetMapping;` to that file's imports.

- [ ] **Step 7: Write the failing integration test**

Add to `BillingControllerIT.java`:

```java
    @Test
    void mySubscriptionReportsThePlanAndRenewalDate() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        subscription.setPlanId(plus.getId());
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId("sub_test_" + UUID.randomUUID());
        subscription.setPaymentProvider("RAZORPAY");
        subscription.setRenewalDate(java.time.LocalDate.of(2026, 11, 1));
        subscriptionRepository.save(subscription);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/billing/subscription", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"planCode\":\"PLUS\"").contains("2026-11-01").contains("\"hasBillingSubscription\":true");
    }
```

- [ ] **Step 8: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=BillingControllerIT
```
Expected: PASS.

- [ ] **Step 9: Add `paymentProvider` to the admin `SubscriptionSummaryDto`**

In `BillingDtos.java`:

```java
    /** Admin Portal, Subscription Management -- one row per user's current subscription.
     *  paymentProvider (Plan 3) is what the admin UI reads to know whether the plain FREE/PLUS/
     *  PREMIUM dropdown is safe to fire directly, or whether it must go through the
     *  cancel-paid-subscription confirm flow first (design spec §6.6) -- "RAZORPAY" means a live
     *  Razorpay mandate exists, "ADMIN_GRANT" or null means it doesn't. */
    public record SubscriptionSummaryDto(
            UUID subscriptionId, UUID userId, String userEmail, String userFullName,
            String planCode, String planName, String status, String paymentProvider,
            LocalDate startDate, LocalDate endDate, LocalDate renewalDate
    ) {}
```

- [ ] **Step 10: Write the failing unit test for the admin list**

Add to `SubscriptionServiceTest.java` (find the existing `listAll` test and add this alongside
it):

```java
    @Test
    void listAllIncludesThePaymentProviderForEachRow() {
        Subscription sub = new Subscription();
        ReflectionTestUtils.setField(sub, "id", UUID.randomUUID());
        sub.setUserId(UUID.randomUUID());
        sub.setPlanId(UUID.randomUUID());
        sub.setStatus(Subscription.STATUS_ACTIVE);
        sub.setStartDate(LocalDate.now());
        sub.setPaymentProvider("RAZORPAY");
        Page<Subscription> page = new PageImpl<>(List.of(sub));
        when(subscriptionRepository.findAllByOrderByCreatedAtDesc(any())).thenReturn(page);
        when(planRepository.findAll()).thenReturn(List.of());
        when(userRepository.findAllById(any())).thenReturn(List.of());

        var result = service.listAll(0, 20);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).paymentProvider()).isEqualTo("RAZORPAY");
    }
```

Add `import org.springframework.data.domain.PageImpl;` to that test file's imports if not already
present.

- [ ] **Step 11: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=SubscriptionServiceTest
```
Expected: FAIL — `SubscriptionSummaryDto` doesn't have a `paymentProvider()` accessor's worth of
data being passed in `listAll`'s mapping yet (compiles once Step 9 lands, but the field will be
`null` until Step 12).

- [ ] **Step 12: Populate the field in `SubscriptionService.listAll`**

```java
        return PagedResponse.of(subscriptions.map(s -> {
            Plan plan = plansById.get(s.getPlanId());
            User user = usersById.get(s.getUserId());
            return new SubscriptionSummaryDto(
                    s.getId(), s.getUserId(),
                    user != null ? user.getEmail() : null, user != null ? user.getFullName() : null,
                    plan != null ? plan.getCode() : null, plan != null ? plan.getName() : null,
                    s.getStatus(), s.getPaymentProvider(), s.getStartDate(), s.getEndDate(), s.getRenewalDate());
        }));
```

(This replaces the existing `return PagedResponse.of(...)` block in `listAll` — same structure,
one more constructor argument.)

- [ ] **Step 13: Run the full backend suite**

```bash
cd backend && ./mvnw test
```
Expected: PASS, no regressions (in particular, `AdminSubscriptionControllerIT`'s existing
assertions on `SubscriptionSummaryDto`'s JSON shape should still pass since this only adds a
field).

- [ ] **Step 14: Commit**

```bash
git add backend/src/main/java/com/finora/dto/BillingDtos.java \
        backend/src/main/java/com/finora/service/BillingCheckoutService.java \
        backend/src/main/java/com/finora/service/SubscriptionService.java \
        backend/src/main/java/com/finora/controller/BillingController.java \
        backend/src/test/java/com/finora/service/BillingCheckoutServiceTest.java \
        backend/src/test/java/com/finora/controller/BillingControllerIT.java \
        backend/src/test/java/com/finora/service/SubscriptionServiceTest.java
git commit -m "feat(backend): add self-service subscription read endpoint, admin paymentProvider field"
```

---

## Task 3: Frontend API layer — `billingApi` additions, Razorpay loader, admin API

**Files:**
- Modify: `frontend/src/api/endpoints.ts`
- Create: `frontend/src/lib/razorpayCheckout.ts`
- Test: `frontend/src/lib/razorpayCheckout.test.ts`
- Modify: `admin-portal/src/api/endpoints.ts`
- Modify: `admin-portal/src/types/index.ts`

**Interfaces:**
- Consumes: `GET /api/v1/billing/subscription`, `POST /api/v1/billing/checkout`,
  `POST /api/v1/billing/cancel`, `POST /api/v1/billing/change-plan`,
  `POST /api/v1/billing/pending-order/cancel` (Tasks 1-2),
  `POST /api/v1/admin/subscriptions/{userId}/cancel-paid-subscription` (Plan 2, already exists on
  the backend, never called from any admin-portal client until now).
- Produces: `billingApi.mySubscription()/.checkout()/.cancel()/.changePlan()/.cancelPendingOrder()`,
  `loadRazorpayCheckout(): Promise<RazorpayConstructor>`,
  `openRazorpayCheckout(options): Promise<{ paymentId: string } | null>` (frontend);
  `adminSubscriptionsApi.cancelPaidSubscription(userId)` (admin-portal).

- [ ] **Step 1: Write the failing test for the Razorpay loader**

Create `frontend/src/lib/razorpayCheckout.test.ts`:

```typescript
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { loadRazorpayCheckout } from './razorpayCheckout';

describe('loadRazorpayCheckout', () => {
  const SRC = 'https://checkout.razorpay.com/v1/checkout.js';

  beforeEach(() => {
    document.querySelectorAll(`script[src="${SRC}"]`).forEach((el) => el.remove());
    // @ts-expect-error -- test-only reset of the module's cached promise via a fresh dynamic
    // import is impractical in Vitest without vi.resetModules(); instead each test controls
    // window.Razorpay directly, matching how the module actually detects readiness.
    delete window.Razorpay;
  });

  afterEach(() => {
    document.querySelectorAll(`script[src="${SRC}"]`).forEach((el) => el.remove());
  });

  it('resolves immediately if window.Razorpay is already present', async () => {
    const Ctor = vi.fn();
    // @ts-expect-error -- test double, not the real Razorpay Checkout constructor's full type
    window.Razorpay = Ctor;

    const resolved = await loadRazorpayCheckout();

    expect(resolved).toBe(Ctor);
    expect(document.querySelector(`script[src="${SRC}"]`)).toBeNull();
  });

  it('injects the script tag and resolves once it loads', async () => {
    const promise = loadRazorpayCheckout();
    const script = document.querySelector<HTMLScriptElement>(`script[src="${SRC}"]`);
    expect(script).not.toBeNull();

    const Ctor = vi.fn();
    // @ts-expect-error -- test double
    window.Razorpay = Ctor;
    script!.dispatchEvent(new Event('load'));

    await expect(promise).resolves.toBe(Ctor);
  });

  it('rejects if the script fails to load', async () => {
    const promise = loadRazorpayCheckout();
    const script = document.querySelector<HTMLScriptElement>(`script[src="${SRC}"]`);
    script!.dispatchEvent(new Event('error'));

    await expect(promise).rejects.toThrow(/failed to load/i);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd frontend && npx vitest run src/lib/razorpayCheckout.test.ts
```
Expected: FAIL — the module doesn't exist yet.

- [ ] **Step 3: Write `razorpayCheckout.ts`**

```typescript
// Razorpay's own hosted Checkout widget (design spec §6.1/§6.5) -- loaded once at runtime, the
// same pattern lib/googleIdentity.ts already uses for Google Identity Services, not an npm
// dependency: Razorpay explicitly documents this script as the integration point, not an SDK
// package to bundle.

export interface RazorpayCheckoutOptions {
  key: string;
  subscription_id: string;
  name: string;
  description: string;
  // Left empty/undefined by every caller here -- Razorpay's own state machine drives entitlement
  // activation via the backend webhook (design spec §6.1 step 6), never this callback's own
  // presence. Declared for completeness against Razorpay's real options shape, not because this
  // app reads it.
  handler?: (response: { razorpay_payment_id: string }) => void;
  modal?: { ondismiss?: () => void };
  prefill?: { email?: string; name?: string };
  theme?: { color?: string };
}

interface RazorpayInstance {
  open(): void;
  on(event: 'payment.failed', handler: (response: { error: { description: string } }) => void): void;
}

type RazorpayConstructor = new (options: RazorpayCheckoutOptions) => RazorpayInstance;

declare global {
  interface Window {
    Razorpay?: RazorpayConstructor;
  }
}

const SCRIPT_SRC = 'https://checkout.razorpay.com/v1/checkout.js';

let scriptPromise: Promise<RazorpayConstructor> | null = null;

/**
 * Loads Razorpay's Checkout script exactly once (cached across every call), resolving once
 * `window.Razorpay` is actually usable. Rejects rather than hanging forever on a load failure
 * (offline, an ad blocker, Razorpay's CDN unreachable) -- mirrors
 * lib/googleIdentity.ts's loadGoogleIdentityServices exactly, including not caching a failure so a
 * later retry gets a fresh attempt.
 */
export function loadRazorpayCheckout(): Promise<RazorpayConstructor> {
  if (window.Razorpay) {
    return Promise.resolve(window.Razorpay);
  }
  if (!scriptPromise) {
    scriptPromise = new Promise<RazorpayConstructor>((resolve, reject) => {
      const existing = document.querySelector<HTMLScriptElement>(`script[src="${SCRIPT_SRC}"]`);
      const script = existing ?? document.createElement('script');
      script.addEventListener('load', () => {
        if (window.Razorpay) resolve(window.Razorpay);
        else reject(new Error('Razorpay Checkout loaded but window.Razorpay is missing.'));
      });
      script.addEventListener('error', () => reject(new Error('Failed to load Razorpay Checkout.')));
      if (!existing) {
        script.src = SCRIPT_SRC;
        script.async = true;
        document.head.appendChild(script);
      }
    }).catch((err) => {
      scriptPromise = null;
      throw err;
    });
  }
  return scriptPromise!;
}

/**
 * Opens the Checkout widget for a subscription id the backend already created (design spec
 * §6.1 step 3 / §6.5 step 1) and resolves once the user either completes or abandons it.
 *
 * Resolves `{ paymentId }` on Razorpay's own success callback and `null` on dismiss/failure --
 * the caller must NOT treat a non-null resolution as "the plan is now active." Activation only
 * ever happens from the backend's verified webhook (design spec §6.1 step 6, restated for
 * upgrades in §6.5 step 3); this promise resolving is "the checkout flow is over," nothing more.
 */
export async function openRazorpayCheckout(
  options: Omit<RazorpayCheckoutOptions, 'handler' | 'modal'>
): Promise<{ paymentId: string } | null> {
  const Razorpay = await loadRazorpayCheckout();
  return new Promise((resolve) => {
    const instance = new Razorpay({
      ...options,
      handler: (response) => resolve({ paymentId: response.razorpay_payment_id }),
      modal: { ondismiss: () => resolve(null) },
    });
    instance.on('payment.failed', () => resolve(null));
    instance.open();
  });
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd frontend && npx vitest run src/lib/razorpayCheckout.test.ts
```
Expected: PASS.

- [ ] **Step 5: Add `billingApi` methods to `frontend/src/api/endpoints.ts`**

Replace the existing `billingApi` block (currently just `history`):

```typescript
// D-28 PR4-B, extended by subscription billing V1/V2/V3. The user's own billing surface: history
// (Plan 1, always existed), and now the active subscription plus the actions that change it.
export interface BillingHistoryEntry {
  id: string;
  amount: number;
  currency: string;
  provider: string | null;
  status: string;
  createdAt: string;
}

// Mirrors backend BillingDtos.MySubscriptionDto exactly.
export interface PendingPlanChange {
  toPlanCode: string;
  toPlanName: string;
  effectiveAt: string;
}
// A stuck or in-flight checkout the Billing Portal can offer to resume or cancel (Plan 3 review,
// §0.5 of the plan) -- razorpaySubscriptionId/keyId are the SAME values a fresh checkout() call
// would return, safe to hand straight to openRazorpayCheckout with no other change.
export interface PendingOrder {
  planCode: string;
  planName: string;
  billingCycle: string;
  razorpaySubscriptionId: string;
  keyId: string;
}
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
}

// Mirrors backend BillingDtos.CheckoutResponseDto exactly. `null` from changePlan() means the
// requested change (a downgrade, or a same-plan no-op) needed no further client action -- see
// that endpoint's own doc comment on the backend.
export interface CheckoutResponse {
  razorpaySubscriptionId: string;
  keyId: string;
}

export const billingApi = {
  history: () => api.get<BillingHistoryEntry[]>('/billing/history').then((r) => r.data),
  mySubscription: () => api.get<MySubscription>('/billing/subscription').then((r) => r.data),
  checkout: (planCode: string, billingCycle: string) =>
    api.post<CheckoutResponse>('/billing/checkout', { planCode, billingCycle }).then((r) => r.data),
  cancel: () => api.post<{ message: string }>('/billing/cancel').then((r) => r.data),
  changePlan: (planCode: string, billingCycle: string) =>
    api.post<CheckoutResponse | null>('/billing/change-plan', { planCode, billingCycle }).then((r) => r.data),
  // Plan 3 review, §0.5 -- clears a stuck PENDING order so a different plan/cycle can be checked
  // out. Never calls Razorpay itself; see the backend's cancelPendingOrder for why that's correct.
  cancelPendingOrder: () => api.post<{ message: string }>('/billing/pending-order/cancel').then((r) => r.data),
};
```

Delete the old, now-superseded `BillingHistoryEntry`/`billingApi` block that previously sat just
above `referralsApi` (the one with only `history: () => ...`) — this replaces it in place, same
export names, so nothing importing `billingApi`/`BillingHistoryEntry` elsewhere needs to change.

- [ ] **Step 6: Add `cancelPaidSubscription` to the admin portal, and `paymentProvider` to its type**

In `admin-portal/src/types/index.ts`:

```typescript
export interface SubscriptionSummaryDto {
  subscriptionId: string;
  userId: string;
  userEmail: string | null;
  userFullName: string | null;
  planCode: string | null;
  planName: string | null;
  status: string;
  // Plan 3. "RAZORPAY" means a live Razorpay mandate exists -- the plain plan dropdown must not
  // fire directly; "ADMIN_GRANT" or null means it's safe to.
  paymentProvider: string | null;
  startDate: string;
  endDate: string | null;
  renewalDate: string | null;
}
```

In `admin-portal/src/api/endpoints.ts`:

```typescript
export const adminSubscriptionsApi = {
  list: (page: number, size: number) =>
    api.get<PagedResponse<SubscriptionSummaryDto>>('/admin/subscriptions', { params: { page, size } }).then((r) => r.data),
  changePlan: (userId: string, planCode: string, reason: string) =>
    api.put(`/admin/subscriptions/${userId}/plan`, { planCode, reason }),
  // Plan 3 / design spec §6.6 -- releases a live Razorpay mandate immediately (not at cycle end;
  // this is an admin support action) so changePlan above can then succeed.
  cancelPaidSubscription: (userId: string) =>
    api.post(`/admin/subscriptions/${userId}/cancel-paid-subscription`),
};
```

- [ ] **Step 7: Typecheck both frontends**

```bash
cd frontend && npx tsc --noEmit
cd ../admin-portal && npx tsc --noEmit
```
Expected: no new errors. (`BillingHistory.tsx` still compiles against the new `billingApi.history`
and `BillingHistoryEntry` — same names, same shape, unchanged by this task; Task 5 replaces that
file's content, not this task.)

- [ ] **Step 8: Commit**

```bash
git add frontend/src/api/endpoints.ts frontend/src/lib/razorpayCheckout.ts frontend/src/lib/razorpayCheckout.test.ts \
        admin-portal/src/api/endpoints.ts admin-portal/src/types/index.ts
git commit -m "feat(frontend): add billing API surface, Razorpay Checkout loader, admin cancel action"
```

---

## Task 4: Public Pricing page goes live

**Files:**
- Modify: `frontend/src/pages/landing/plans.ts`
- Modify: `frontend/src/pages/landing/Pricing.tsx`
- Modify: `frontend/src/pages/landing/landing-claims.test.tsx`

**Interfaces:**
- Consumes: nothing new — no API call happens on this public, unauthenticated page. Checkout
  itself only ever happens inside the app (Task 5), matching how the existing "Start free" CTA
  already just links to `/auth` rather than doing anything itself.
- Produces: `Plan.price`/`cadence` populated for `plus`/`premium`; a new optional
  `Plan.secondaryPriceNote` field other pages don't need to know about.

- [ ] **Step 1: Write the failing test**

Update `frontend/src/pages/landing/landing-claims.test.tsx`. Find this assertion (currently
asserting only `free` is available):

```typescript
    expect(PLANS.filter((p) => p.availability === 'available').map((p) => p.id)).toEqual(['free']);
```

Replace it with:

```typescript
    expect(PLANS.filter((p) => p.availability === 'available').map((p) => p.id)).toEqual(['free', 'plus', 'premium']);
```

Find this assertion (allowed rupee prices in the rendered section):

```typescript
    const allowed = PLANS.filter((p) => p.price).map((p) => p.price as string);
```

This stays correct as-is once `plus`/`premium` carry real `price` values — it already derives
`allowed` from `PLANS` itself rather than a hardcoded list, so no change needed there. No other
line in this file needs editing: the "never marks an available plan as coming soon" and "buyish
CTA count" assertions (lines ~195-214) already derive their expectations from `PLANS.filter(p =>
p.availability === 'available')`, so they adjust automatically once Step 3 below flips the two
plans' availability.

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frontend && npx vitest run src/pages/landing/landing-claims.test.tsx
```
Expected: FAIL — `PLANS` still has only `free` available.

- [ ] **Step 3: Update `plans.ts`**

Add one new optional field to the `Plan` interface:

```typescript
  /** A second cadence worth mentioning below the primary price (e.g. the yearly price, when the
   *  primary price shown is monthly) -- purely informational, never a second buyable price on its
   *  own; checking out at a specific cycle happens inside the app's Billing Portal, not here. */
  secondaryPriceNote?: string;
```

Update the `plus` and `premium` entries in `PLANS` (only `price`, `cadence`, `availability`, and
the new `secondaryPriceNote` change — `blurb`/`promise`/`stage`/`features`/`ladder` are unchanged,
omitted below for brevity but must be kept exactly as they are today):

```typescript
  {
    id: 'plus',
    name: 'Plus',
    price: '₹399',
    cadence: '/month',
    secondaryPriceNote: 'or ₹3,500/year',
    availability: 'available',
    // ...blurb, promise, stage, features, ladder unchanged
  },
```

```typescript
  {
    id: 'premium',
    name: 'Premium',
    price: '₹799',
    cadence: '/month',
    secondaryPriceNote: 'or ₹8,000/year',
    availability: 'available',
    // ...blurb, promise, stage, features, ladder unchanged
  },
```

These four numbers (₹399/₹3,500/₹799/₹8,000) are the design spec's own §2 pricing decision table
and match the already-seeded `billing_prices` rows exactly (Plan 1's `V154__subscription_billing_v1.sql`)
— not new numbers invented for this page.

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd frontend && npx vitest run src/pages/landing/landing-claims.test.tsx
```
Expected: PASS.

- [ ] **Step 5: Update `Pricing.tsx`'s CTA and price block**

The existing ternary already branches on `plan.availability === 'available'` for both the price
display and the CTA — once Step 3 flips `plus`/`premium` to `'available'`, both branches already
render correctly with NO changes needed to that logic. The only addition is rendering the new
`secondaryPriceNote` under the price. Find this block:

```tsx
              {plan.price ? (
                <p className="text-3xl font-extrabold mb-1" style={{ fontFamily: "'Manrope', Inter, sans-serif", color: 'var(--m-ink)' }}>
                  {plan.price}
                  <span className="text-sm font-medium" style={{ color: 'var(--m-ink-3)' }}>{plan.cadence}</span>
                </p>
              ) : (
```

Replace it with:

```tsx
              {plan.price ? (
                <>
                  <p className="text-3xl font-extrabold mb-1" style={{ fontFamily: "'Manrope', Inter, sans-serif", color: 'var(--m-ink)' }}>
                    {plan.price}
                    <span className="text-sm font-medium" style={{ color: 'var(--m-ink-3)' }}>{plan.cadence}</span>
                  </p>
                  {plan.secondaryPriceNote && (
                    <p className="text-xs mb-1" style={{ color: 'var(--m-ink-3)' }}>{plan.secondaryPriceNote}</p>
                  )}
                </>
              ) : (
```

Also update the CTA text so "Start free" doesn't render on the Plus/Premium cards (both plans now
hit the `plan.availability === 'available'` branch, which currently always says "Start free"
regardless of plan). Find:

```tsx
              {plan.availability === 'available' ? (
                <MagneticLink to="/auth" className="m-btn m-btn-primary w-full">Start free</MagneticLink>
              ) : (
```

Replace with:

```tsx
              {plan.availability === 'available' ? (
                <MagneticLink to="/auth" className="m-btn m-btn-primary w-full">
                  {plan.id === 'free' ? 'Start free' : 'Get started'}
                </MagneticLink>
              ) : (
```

Every available plan's CTA still goes to `/auth` — same destination, same reasoning as the
existing "Start free" link: this marketing page's only job is converting a visitor into an
account. The actual plan pick + checkout happens once inside the app, in the Billing Portal
(Task 5). This deliberately does NOT add a "remember which plan they wanted" redirect mechanism —
no such mechanism exists anywhere else in the app's auth flow today, and inventing one here (a
query param carried through signup/login/OTP verification, then consumed by the Billing Portal)
is real new infrastructure the spec doesn't ask for. A visitor who picked Plus lands in the app on
Free and chooses Plus again from the Billing Portal, one extra click.

Also update the comparison table's header, which currently hardcodes a "coming soon" sub-label
under Plus/Premium regardless of `PLANS`' own availability. Find:

```tsx
                <th scope="col" className="px-4 py-3 font-semibold w-28" style={{ color: 'var(--m-ink)' }}>
                  Plus
                  <span className="block text-[9px] font-medium normal-case tracking-normal" style={{ color: 'var(--m-ink-3)' }}>
                    coming soon
                  </span>
                </th>
                <th scope="col" className="px-4 py-3 font-semibold w-28" style={{ color: 'var(--m-ink)' }}>
                  Premium
                  <span className="block text-[9px] font-medium normal-case tracking-normal" style={{ color: 'var(--m-ink-3)' }}>
                    coming soon
                  </span>
                </th>
```

Replace with a version that reads the real availability instead of a hardcoded label:

```tsx
                <th scope="col" className="px-4 py-3 font-semibold w-28" style={{ color: 'var(--m-ink)' }}>
                  Plus
                </th>
                <th scope="col" className="px-4 py-3 font-semibold w-28" style={{ color: 'var(--m-ink)' }}>
                  Premium
                </th>
```

(Simplest correct fix: the sub-label only ever said "coming soon," which is no longer true for
either column and there is no other status worth stating in a table header — the cards above
already carry the `AVAILABILITY_LABEL` badge for whichever plans aren't available, so removing the
stale label here doesn't lose any information the page provides elsewhere.)

- [ ] **Step 6: Update the "There is no billing anywhere" doc comment at the top of `Pricing.tsx`**

The file's own top-of-file comment (`There is no billing anywhere in the backend...`) is now
false. Replace that paragraph:

```tsx
 * Plus and Premium are now real, purchasable plans (subscription billing V1/V2, PRs #1008/#1016)
 * -- checkout itself happens inside the app's Billing Portal (frontend/src/pages/Billing.tsx),
 * not on this public page. This page's job stays the same as before: state what a plan costs and
 * whether it can be bought, accurately, and get a visitor into the app to actually buy it.
```

(Replacing the old paragraph that begins "There is no billing anywhere in the backend -- no plan
field on User, no payment integration -- so nothing here can be purchased today by design, not by
oversight.")

- [ ] **Step 7: Run the frontend's landing test suite**

```bash
cd frontend && npx vitest run src/pages/landing/
```
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/pages/landing/plans.ts frontend/src/pages/landing/Pricing.tsx frontend/src/pages/landing/landing-claims.test.tsx
git commit -m "feat(frontend): make Plus and Premium live, purchasable plans on the pricing page"
```

---

## Task 5: Billing Portal page

**Files:**
- Create: `frontend/src/pages/Billing.tsx` (replaces `BillingHistory.tsx`)
- Delete: `frontend/src/pages/BillingHistory.tsx`
- Create: `frontend/src/pages/Billing.test.tsx` (replaces `BillingHistory.test.tsx`)
- Delete: `frontend/src/pages/BillingHistory.test.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/components/Sidebar.tsx`

**Interfaces:**
- Consumes: `billingApi.mySubscription()/.history()/.checkout()/.cancel()/.changePlan()/.cancelPendingOrder()`
  (Task 3), `openRazorpayCheckout` (Task 3), `entitlementsApi.mine()` (Plan 1, unchanged — used only
  to invalidate/refetch after a checkout completes, not read directly by this page).
- Produces: the `/app/billing` route's page component. Nothing else in the app imports from this
  file today (confirmed: `BillingHistory` is only ever referenced from `App.tsx`'s own lazy import),
  so renaming it has exactly one other call site to update (`App.tsx`, this task's own Step 6).

**Design decisions this task makes** (not spec-mandated line by line, but consistent with it):
- **Plan/cycle picker:** a plain `<select>` for the target plan (PLUS/PREMIUM, or FREE meaning
  "cancel") and a second `<select>` for MONTHLY/YEARLY, both defaulting to the user's current
  values where applicable — no new design-system component, matching how `Subscriptions.tsx`
  (admin portal) already uses a plain `<select>` for the same kind of choice.
- **Upgrade vs. downgrade vs. cancel, decided client-side only to choose which action to call**,
  never to decide what happens: picking a higher tier calls `changePlan` then opens the Razorpay
  widget if a `CheckoutResponse` comes back (an upgrade); picking a lower paid tier calls
  `changePlan` with no further client action (a downgrade, applies at renewal); picking FREE calls
  `cancel()` instead of `changePlan` (mirrors `BillingCheckoutService.changePlan`'s own refusal to
  accept `planCode: "FREE"`).
- **"Activating…" polling** after a successful checkout (design spec §6.1 step 6, restated for
  upgrades in §6.5): re-fetch `mySubscription` every 2 seconds, up to 30 seconds, stopping early
  the moment the returned `planCode` matches what was just purchased. This is the one place this
  page polls; every other action (cancel, schedule a downgrade) takes effect in the SAME response
  the backend already returns, so a plain `invalidateQueries` is enough there.
- **A pending-order banner (Plan 3 review, §0.5 of this plan)** shown above the current-plan card
  whenever `mySubscription().pendingOrder` is non-null: "You started upgrading to X but didn't
  finish payment," with **Resume checkout** (opens Razorpay directly with the pending order's
  already-issued `razorpaySubscriptionId`/`keyId` — no new `billingApi.checkout()` call, that's the
  entire point of resuming) and **Cancel** (confirm dialog → `billingApi.cancelPendingOrder()`).

- [ ] **Step 1: Write the failing test**

Create `frontend/src/pages/Billing.test.tsx`:

```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Billing from './Billing';
import { billingApi } from '../api/endpoints';
import { openRazorpayCheckout } from '../lib/razorpayCheckout';
import type { BillingHistoryEntry, MySubscription } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  billingApi: {
    history: vi.fn(), mySubscription: vi.fn(), checkout: vi.fn(), cancel: vi.fn(),
    changePlan: vi.fn(), cancelPendingOrder: vi.fn(),
  },
}));
vi.mock('../lib/razorpayCheckout', () => ({
  openRazorpayCheckout: vi.fn(),
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Billing />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function subscription(overrides: Partial<MySubscription> = {}): MySubscription {
  return {
    planCode: 'FREE', planName: 'Free', billingCycle: null, status: 'ACTIVE',
    renewalDate: null, autoRenew: true, hasBillingSubscription: false, pendingChange: null,
    pendingOrder: null,
    ...overrides,
  };
}

function entry(overrides: Partial<BillingHistoryEntry> = {}): BillingHistoryEntry {
  return {
    id: 'payment-1', amount: 499, currency: 'INR', provider: 'RAZORPAY', status: 'SUCCESS',
    createdAt: '2026-08-20T10:00:00Z', ...overrides,
  };
}

describe('Billing', () => {
  beforeEach(() => {
    vi.mocked(billingApi.history).mockReset().mockResolvedValue([]);
    vi.mocked(billingApi.mySubscription).mockReset();
    vi.mocked(billingApi.checkout).mockReset();
    vi.mocked(billingApi.cancel).mockReset();
    vi.mocked(billingApi.changePlan).mockReset();
    vi.mocked(billingApi.cancelPendingOrder).mockReset();
    vi.mocked(openRazorpayCheckout).mockReset();
  });

  it('shows the current Free plan and no cancel button', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    renderPage();

    expect(await screen.findByText('Free')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /cancel/i })).not.toBeInTheDocument();
  });

  it('shows the renewal date and a cancel button for a paid plan', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PLUS', planName: 'Plus', billingCycle: 'MONTHLY',
      renewalDate: '2026-11-01', hasBillingSubscription: true,
    }));
    renderPage();

    expect(await screen.findByText('Plus')).toBeInTheDocument();
    // formatDate renders a LocalDate like "2026-11-01" as e.g. "1 Nov 2026" (en-IN,
    // locale-dependent exact token order) -- assert on the parts that don't vary, not the literal
    // ISO string, which never appears in the rendered DOM once formatDate is applied.
    expect(screen.getByText(/nov/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /cancel/i })).toBeInTheDocument();
  });

  it('shows a pending downgrade banner', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PREMIUM', planName: 'Premium', billingCycle: 'MONTHLY',
      hasBillingSubscription: true,
      pendingChange: { toPlanCode: 'PLUS', toPlanName: 'Plus', effectiveAt: '2026-11-01T00:00:00Z' },
    }));
    renderPage();

    expect(await screen.findByText(/downgrading to plus/i)).toBeInTheDocument();
  });

  it('shows a resume/cancel banner for an abandoned checkout', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      pendingOrder: { planCode: 'PREMIUM', planName: 'Premium', billingCycle: 'YEARLY', razorpaySubscriptionId: 'sub_stuck', keyId: 'rzp_test' },
    }));
    renderPage();

    expect(await screen.findByText(/premium/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /resume checkout/i })).toBeInTheDocument();
  });

  it('resuming a pending order opens Razorpay directly without calling checkout again', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      pendingOrder: { planCode: 'PREMIUM', planName: 'Premium', billingCycle: 'YEARLY', razorpaySubscriptionId: 'sub_stuck', keyId: 'rzp_test' },
    }));
    vi.mocked(openRazorpayCheckout).mockResolvedValue({ paymentId: 'pay_1' });
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('button', { name: /resume checkout/i });

    await user.click(screen.getByRole('button', { name: /resume checkout/i }));

    await waitFor(() => expect(openRazorpayCheckout).toHaveBeenCalledWith(
      expect.objectContaining({ key: 'rzp_test', subscription_id: 'sub_stuck' })
    ));
    expect(billingApi.checkout).not.toHaveBeenCalled();
  });

  it('cancelling a pending order calls cancelPendingOrder after confirmation', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      pendingOrder: { planCode: 'PREMIUM', planName: 'Premium', billingCycle: 'YEARLY', razorpaySubscriptionId: 'sub_stuck', keyId: 'rzp_test' },
    }));
    vi.mocked(billingApi.cancelPendingOrder).mockResolvedValue({ message: 'Cancelled' });
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('button', { name: /resume checkout/i });

    await user.click(screen.getByRole('button', { name: /^cancel$/i }));
    await user.click(screen.getByRole('button', { name: /confirm/i }));

    await waitFor(() => expect(billingApi.cancelPendingOrder).toHaveBeenCalled());
  });

  it('checking out a paid plan from Free opens the Razorpay widget', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    vi.mocked(billingApi.checkout).mockResolvedValue({ razorpaySubscriptionId: 'sub_new', keyId: 'rzp_test' });
    vi.mocked(openRazorpayCheckout).mockResolvedValue({ paymentId: 'pay_1' });
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('Free');

    await user.selectOptions(screen.getByLabelText(/choose a plan/i), 'PLUS');
    await user.click(screen.getByRole('button', { name: /subscribe/i }));

    await waitFor(() => expect(billingApi.checkout).toHaveBeenCalledWith('PLUS', 'MONTHLY'));
    expect(openRazorpayCheckout).toHaveBeenCalledWith(
      expect.objectContaining({ key: 'rzp_test', subscription_id: 'sub_new' })
    );
  });

  it('cancelling calls the cancel endpoint after confirmation', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PLUS', planName: 'Plus', billingCycle: 'MONTHLY', hasBillingSubscription: true,
    }));
    vi.mocked(billingApi.cancel).mockResolvedValue({ message: 'Cancelled' });
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('Plus');

    await user.click(screen.getByRole('button', { name: /cancel/i }));
    await user.click(screen.getByRole('button', { name: /confirm/i }));

    await waitFor(() => expect(billingApi.cancel).toHaveBeenCalled());
  });

  it('renders payment history below the plan card', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    vi.mocked(billingApi.history).mockResolvedValue([entry()]);
    renderPage();

    expect(await screen.findByText('₹499')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frontend && npx vitest run src/pages/Billing.test.tsx
```
Expected: FAIL — `./Billing` does not exist yet.

- [ ] **Step 3: Write `Billing.tsx`**

```tsx
import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Receipt, CreditCard } from 'lucide-react';
import { billingApi, type MySubscription } from '../api/endpoints';
import { openRazorpayCheckout } from '../lib/razorpayCheckout';
import { formatDate } from '../utils/date';
import { FinoraCard, EmptyState, Button, ConfirmDialog } from '../design-system';

function fmt(amount: number, currency: string) {
  const symbol = currency === 'INR' ? '₹' : currency + ' ';
  return symbol + Math.round(amount).toLocaleString('en-IN');
}

function statusLabel(status: string) {
  switch (status) {
    case 'SUCCESS': return { text: 'Paid', className: 'text-success bg-success-bg' };
    case 'REFUNDED': return { text: 'Refunded', className: 'text-muted bg-bg' };
    case 'FAILED': return { text: 'Failed', className: 'text-danger bg-danger-bg' };
    default: return { text: 'Pending', className: 'text-warning bg-warning-bg' };
  }
}

// Design spec §2's pricing table -- same four numbers Pricing.tsx (public site) and
// V154__subscription_billing_v1.sql (backend seed) both already carry. Duplicated here rather
// than fetched: there is no live pricing-list endpoint (§8 never asks for one), and this fixed,
// rarely-changing catalog matches how frontend/src/pages/landing/plans.ts already hardcodes Free's
// price the same way.
const CHECKOUT_PLANS = [
  { code: 'PLUS', name: 'Plus' },
  { code: 'PREMIUM', name: 'Premium' },
] as const;
const CHECKOUT_CYCLES = [
  { code: 'MONTHLY', label: 'Monthly' },
  { code: 'YEARLY', label: 'Yearly' },
] as const;
const TIER_RANK: Record<string, number> = { FREE: 0, PLUS: 1, PREMIUM: 2 };

/** Polls `mySubscription` after a successful checkout until the plan actually flips, or 30
 *  seconds pass -- design spec §6.1 step 6 / §6.5 step 3: activation only ever comes from the
 *  backend's verified webhook, never from Checkout's own success callback, so this page cannot
 *  just trust that callback and must wait to see the real state change. */
function useActivationPoll(expectedPlanCode: string | null, onSettled: () => void) {
  useEffect(() => {
    if (!expectedPlanCode) return;
    const deadline = Date.now() + 30_000;
    const interval = setInterval(async () => {
      const current = await billingApi.mySubscription();
      if (current.planCode === expectedPlanCode || Date.now() > deadline) {
        clearInterval(interval);
        onSettled();
      }
    }, 2000);
    return () => clearInterval(interval);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- onSettled is a stable setState-based
    // closure from the caller; including it would re-run this effect (and restart the poll) every
    // render, which the caller's re-render-per-tick already causes without help from this list.
  }, [expectedPlanCode]);
}

export default function Billing() {
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [confirmingCancel, setConfirmingCancel] = useState(false);
  const [confirmingCancelPendingOrder, setConfirmingCancelPendingOrder] = useState(false);
  const [targetPlan, setTargetPlan] = useState('PLUS');
  const [targetCycle, setTargetCycle] = useState('MONTHLY');
  const [activatingPlanCode, setActivatingPlanCode] = useState<string | null>(null);

  const { data: subscription, isLoading: subLoading } = useQuery({
    queryKey: ['my-subscription'],
    queryFn: () => billingApi.mySubscription(),
  });
  const { data: entries, isLoading: historyLoading } = useQuery({
    queryKey: ['billing-history'],
    queryFn: () => billingApi.history(),
  });

  useActivationPoll(activatingPlanCode, () => {
    setActivatingPlanCode(null);
    void queryClient.invalidateQueries({ queryKey: ['my-subscription'] });
    void queryClient.invalidateQueries({ queryKey: ['entitlements'] });
  });

  const cancelMutation = useMutation({
    mutationFn: () => billingApi.cancel(),
    onSuccess: () => {
      setConfirmingCancel(false);
      void queryClient.invalidateQueries({ queryKey: ['my-subscription'] });
    },
    onError: (e: any) => {
      setConfirmingCancel(false);
      setError(e.response?.data?.message ?? 'Could not cancel this subscription. Try again.');
    },
  });

  const cancelPendingOrderMutation = useMutation({
    mutationFn: () => billingApi.cancelPendingOrder(),
    onSuccess: () => {
      setConfirmingCancelPendingOrder(false);
      void queryClient.invalidateQueries({ queryKey: ['my-subscription'] });
    },
    onError: (e: any) => {
      setConfirmingCancelPendingOrder(false);
      setError(e.response?.data?.message ?? 'Could not cancel this pending checkout. Try again.');
    },
  });

  // Plan 3 review, §0.5. Deliberately does NOT call billingApi.checkout() again -- the whole
  // point of resuming is reusing the SAME Razorpay subscription the abandoned attempt already
  // created, not creating a second one.
  async function resumePendingOrder() {
    if (!subscription?.pendingOrder) return;
    setError(null);
    const result = await openRazorpayCheckout({
      key: subscription.pendingOrder.keyId,
      subscription_id: subscription.pendingOrder.razorpaySubscriptionId,
      name: 'Fynora',
      description: `${subscription.pendingOrder.planCode} — ${subscription.pendingOrder.billingCycle}`,
    });
    if (result) setActivatingPlanCode(subscription.pendingOrder.planCode);
  }

  async function subscribeToPlan() {
    setError(null);
    try {
      if (!subscription?.hasBillingSubscription) {
        const checkout = await billingApi.checkout(targetPlan, targetCycle);
        const result = await openRazorpayCheckout({
          key: checkout.keyId,
          subscription_id: checkout.razorpaySubscriptionId,
          name: 'Fynora',
          description: `${targetPlan} — ${targetCycle}`,
        });
        if (result) setActivatingPlanCode(targetPlan);
        return;
      }
      const isUpgrade = TIER_RANK[targetPlan] > TIER_RANK[subscription.planCode];
      const checkout = await billingApi.changePlan(targetPlan, targetCycle);
      if (isUpgrade && checkout) {
        const result = await openRazorpayCheckout({
          key: checkout.keyId,
          subscription_id: checkout.razorpaySubscriptionId,
          name: 'Fynora',
          description: `${targetPlan} — ${targetCycle}`,
        });
        if (result) setActivatingPlanCode(targetPlan);
      } else {
        void queryClient.invalidateQueries({ queryKey: ['my-subscription'] });
      }
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not change your plan. Try again.');
    }
  }

  if (subLoading || historyLoading) return <p className="text-muted">Loading…</p>;

  const payments = entries ?? [];

  return (
    <div className="space-y-6">
      <div className="mb-2">
        <h1 className="text-xl font-bold text-ink">Billing</h1>
        <p className="text-sm text-muted">Your plan, payments, and subscription actions.</p>
      </div>

      {error && (
        <div className="text-sm text-danger bg-danger-bg rounded-lg px-4 py-2.5">{error}</div>
      )}

      {activatingPlanCode && (
        <div className="text-sm text-ink bg-bg border border-border rounded-lg px-4 py-2.5">
          Activating your {activatingPlanCode} plan… this can take a few seconds.
        </div>
      )}

      {subscription?.pendingOrder && (
        <FinoraCard padding="lg">
          <div className="flex items-center justify-between gap-4 flex-wrap">
            <div>
              <p className="text-sm font-semibold text-ink">
                You started upgrading to {subscription.pendingOrder.planName} but didn't finish payment.
              </p>
              <p className="text-xs text-muted mt-0.5">{subscription.pendingOrder.billingCycle} billing</p>
            </div>
            <div className="flex gap-2">
              <Button size="sm" onClick={resumePendingOrder}>Resume checkout</Button>
              <Button variant="secondary" size="sm" onClick={() => setConfirmingCancelPendingOrder(true)}>
                Cancel
              </Button>
            </div>
          </div>
        </FinoraCard>
      )}

      {subscription && (
        <FinoraCard padding="lg">
          <div className="flex items-start justify-between gap-4 flex-wrap">
            <div>
              <p className="text-xs text-muted uppercase tracking-wide mb-1">Current plan</p>
              <p className="text-lg font-bold text-ink">{subscription.planName}</p>
              {subscription.renewalDate && (
                <p className="text-sm text-muted mt-1">Renews {formatDate(subscription.renewalDate)}</p>
              )}
              {subscription.pendingChange && (
                <p className="text-sm text-warning mt-1">
                  Downgrading to {subscription.pendingChange.toPlanName} on{' '}
                  {formatDate(subscription.pendingChange.effectiveAt)}
                </p>
              )}
            </div>
            {subscription.hasBillingSubscription && (
              <Button variant="danger" size="sm" onClick={() => setConfirmingCancel(true)}>
                Cancel subscription
              </Button>
            )}
          </div>

          <div className="mt-5 pt-5 border-t border-border flex items-end gap-3 flex-wrap">
            <label className="flex flex-col gap-1 text-xs text-muted">
              Choose a plan
              <select
                value={targetPlan}
                onChange={(e) => setTargetPlan(e.target.value)}
                className="text-sm border border-border rounded-lg px-2.5 py-2 bg-card text-ink"
              >
                {CHECKOUT_PLANS.map((p) => (
                  <option key={p.code} value={p.code}>{p.name}</option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1 text-xs text-muted">
              Billing cycle
              <select
                value={targetCycle}
                onChange={(e) => setTargetCycle(e.target.value)}
                className="text-sm border border-border rounded-lg px-2.5 py-2 bg-card text-ink"
              >
                {CHECKOUT_CYCLES.map((c) => (
                  <option key={c.code} value={c.code}>{c.label}</option>
                ))}
              </select>
            </label>
            <Button
              onClick={subscribeToPlan}
              disabled={targetPlan === subscription.planCode && targetCycle === subscription.billingCycle}
            >
              <CreditCard size={14} /> Subscribe
            </Button>
          </div>
        </FinoraCard>
      )}

      {confirmingCancel && (
        <ConfirmDialog
          title="Cancel subscription?"
          message="Your plan stays active until the end of the current billing period, then moves to Free."
          confirmLabel="Confirm"
          danger
          busy={cancelMutation.isPending}
          onConfirm={() => cancelMutation.mutate()}
          onCancel={() => setConfirmingCancel(false)}
        />
      )}

      {confirmingCancelPendingOrder && (
        <ConfirmDialog
          title="Cancel this pending checkout?"
          message="You'll be able to start a fresh checkout for any plan afterward."
          confirmLabel="Confirm"
          danger
          busy={cancelPendingOrderMutation.isPending}
          onConfirm={() => cancelPendingOrderMutation.mutate()}
          onCancel={() => setConfirmingCancelPendingOrder(false)}
        />
      )}

      <div>
        <h2 className="text-sm font-semibold text-ink mb-2">Payment history</h2>
        {payments.length === 0 ? (
          <FinoraCard padding="lg">
            <EmptyState
              icon={Receipt}
              iconBg="bg-blue-100"
              iconColor="text-blue-600"
              title="No billing history yet"
              desc="Payment records will appear here once you've made your first payment."
            />
          </FinoraCard>
        ) : (
          <div className="bg-card rounded-xl2 shadow-card border border-border overflow-hidden">
            <div className="divide-y divide-border">
              {payments.map((p) => {
                const status = statusLabel(p.status);
                return (
                  <div key={p.id} className="px-5 py-3.5 flex items-center justify-between gap-4 flex-wrap">
                    <div className="min-w-0">
                      <p className="text-sm font-medium text-ink">{fmt(p.amount, p.currency)}</p>
                      <p className="text-xs text-muted">
                        {formatDate(p.createdAt)}{p.provider ? ` · ${p.provider}` : ''}
                      </p>
                    </div>
                    <span className={`text-[10px] uppercase font-semibold rounded px-2 py-1 ${status.className}`}>
                      {status.text}
                    </span>
                  </div>
                );
              })}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd frontend && npx vitest run src/pages/Billing.test.tsx
```
Expected: PASS.

- [ ] **Step 5: Delete the old file and its test**

```bash
git rm frontend/src/pages/BillingHistory.tsx frontend/src/pages/BillingHistory.test.tsx
```

- [ ] **Step 6: Update `App.tsx`'s import and route**

Find:

```typescript
const BillingHistory = lazy(() => import('./pages/BillingHistory'));
```

Replace with:

```typescript
const Billing = lazy(() => import('./pages/Billing'));
```

Find:

```tsx
          <Route path="/app/billing" element={<Protected><BillingHistory /></Protected>} />
```

Replace with:

```tsx
          <Route path="/app/billing" element={<Protected><Billing /></Protected>} />
```

- [ ] **Step 7: Update the Sidebar label**

In `frontend/src/components/Sidebar.tsx`, find:

```tsx
              <NavLink
                to="/app/billing"
                onClick={() => setMenuOpen(false)}
                className="flex items-center gap-2.5 px-3.5 py-2.5 text-sm text-gray-300 hover:text-white hover:bg-white/5"
              >
                <Receipt size={15} /> Billing History
              </NavLink>
```

Replace `Billing History` with `Billing` (the link's `to`/icon/styling are all unchanged — this
page is no longer only history, so the label should say so):

```tsx
              <NavLink
                to="/app/billing"
                onClick={() => setMenuOpen(false)}
                className="flex items-center gap-2.5 px-3.5 py-2.5 text-sm text-gray-300 hover:text-white hover:bg-white/5"
              >
                <Receipt size={15} /> Billing
              </NavLink>
```

- [ ] **Step 8: Run the frontend suite**

```bash
cd frontend && npx vitest run
```
Expected: PASS, no regressions (in particular, nothing else references `BillingHistory` — verify
with `grep -rn "BillingHistory" frontend/src` returning nothing before moving on).

- [ ] **Step 9: Typecheck**

```bash
cd frontend && npx tsc --noEmit
```
Expected: no errors.

- [ ] **Step 10: Commit**

```bash
git add frontend/src/pages/Billing.tsx frontend/src/pages/Billing.test.tsx frontend/src/App.tsx frontend/src/components/Sidebar.tsx
git commit -m "feat(frontend): replace billing history page with a full self-service Billing Portal"
```

---

## Task 6: Admin portal — confirm-flow for the Plan 2 guard

**Files:**
- Modify: `admin-portal/src/pages/Subscriptions.tsx`
- Modify: `admin-portal/src/pages/Subscriptions.test.tsx`

**Interfaces:**
- Consumes: `adminSubscriptionsApi.changePlan()` (unchanged), `adminSubscriptionsApi.cancelPaidSubscription()`
  (Task 3), `SubscriptionSummaryDto.paymentProvider` (Task 2).
- Produces: nothing new for other files — this is the last task, purely UI.

- [ ] **Step 1: Write the failing test**

Update `admin-portal/src/pages/Subscriptions.test.tsx`. This file already has a `subscription()`
fixture helper (defaulting `paymentProvider` is the only change it needs) and a `pageOf(...rows)`
helper — reuse both exactly as they exist today, do not introduce parallel ones.

Find the existing mock:

```typescript
vi.mock('../api/endpoints', () => ({
  adminSubscriptionsApi: { list: vi.fn(), changePlan: vi.fn() },
}));
```

Replace with:

```typescript
vi.mock('../api/endpoints', () => ({
  adminSubscriptionsApi: { list: vi.fn(), changePlan: vi.fn(), cancelPaidSubscription: vi.fn() },
}));
```

Find the `subscription()` fixture helper:

```typescript
function subscription(overrides: Partial<SubscriptionSummaryDto> = {}): SubscriptionSummaryDto {
  return {
    subscriptionId: 'sub-1', userId: 'user-1', userEmail: 'jane@example.com', userFullName: 'Jane Doe',
    planCode: 'FREE', planName: 'Free', status: 'ACTIVE', startDate: '2026-08-01',
    endDate: null, renewalDate: null, ...overrides,
  };
}
```

Add `paymentProvider: null,` to its defaults (after `planName: 'Free',` is a natural spot) so it
matches the now-required field on `SubscriptionSummaryDto` (Task 2):

```typescript
function subscription(overrides: Partial<SubscriptionSummaryDto> = {}): SubscriptionSummaryDto {
  return {
    subscriptionId: 'sub-1', userId: 'user-1', userEmail: 'jane@example.com', userFullName: 'Jane Doe',
    planCode: 'FREE', planName: 'Free', paymentProvider: null, status: 'ACTIVE', startDate: '2026-08-01',
    endDate: null, renewalDate: null, ...overrides,
  };
}
```

Add `vi.mocked(adminSubscriptionsApi.cancelPaidSubscription).mockReset();` to the existing
`beforeEach` block, alongside its two existing `mockReset()` calls.

Add these two tests, following the same `mockAuth(['SUBSCRIPTION_MANAGEMENT_VIEW'])` +
`renderPage()` + `pageOf(...)` shape every other test in this file already uses:

```typescript
  it('shows a confirm dialog instead of changing the plan directly for a Razorpay-backed subscription', async () => {
    mockAuth(['SUBSCRIPTION_MANAGEMENT_VIEW']);
    vi.mocked(adminSubscriptionsApi.list).mockResolvedValue(
      pageOf(subscription({ paymentProvider: 'RAZORPAY', planCode: 'PLUS', planName: 'Plus' }))
    );
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(screen.getByText('Jane Doe')).toBeInTheDocument());

    // A Razorpay-backed row renders a "Cancel paid subscription" action instead of the plain
    // plan dropdown a non-Razorpay row still gets.
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /cancel paid subscription/i }));
    await user.click(screen.getByRole('button', { name: /confirm/i }));

    await waitFor(() => expect(adminSubscriptionsApi.cancelPaidSubscription).toHaveBeenCalledWith('user-1'));
  });

  it('still shows the plain dropdown for a non-Razorpay subscription', async () => {
    mockAuth(['SUBSCRIPTION_MANAGEMENT_VIEW']);
    vi.mocked(adminSubscriptionsApi.list).mockResolvedValue(pageOf(subscription()));
    renderPage();
    await waitFor(() => expect(screen.getByText('Jane Doe')).toBeInTheDocument());

    expect(screen.getByRole('combobox')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /cancel paid subscription/i })).not.toBeInTheDocument();
  });
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd admin-portal && npx vitest run src/pages/Subscriptions.test.tsx
```
Expected: FAIL — no "Cancel paid subscription" button exists yet, `cancelPaidSubscription` is
never called.

- [ ] **Step 3: Update `Subscriptions.tsx`**

Add the cancel-paid-subscription mutation and a small per-row confirm state, and branch the
"Plan" column's render on `paymentProvider`:

```tsx
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Users as UsersIcon } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { Pagination } from '../components/Pagination';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { useNotify } from '../context/NotificationContext';
import { adminSubscriptionsApi } from '../api/endpoints';
import type { SubscriptionSummaryDto } from '../types';

function errorMessage(err: any, fallback: string) {
  return err?.response?.data?.message ?? fallback;
}

const PLAN_CODES = ['FREE', 'PLUS', 'PREMIUM'];

const PAGE_SIZE = 20;

function SubscriptionsContent() {
  const [page, setPage] = useState(0);
  const [confirmingCancelFor, setConfirmingCancelFor] = useState<SubscriptionSummaryDto | null>(null);
  const queryClient = useQueryClient();
  const notify = useNotify();
  const { data, isLoading } = useQuery({
    queryKey: ['admin-subscriptions', page],
    queryFn: () => adminSubscriptionsApi.list(page, PAGE_SIZE),
  });

  const changePlanMutation = useMutation({
    mutationFn: ({ userId, planCode }: { userId: string; planCode: string }) =>
      adminSubscriptionsApi.changePlan(userId, planCode, 'Admin manual override'),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['admin-subscriptions'] });
      notify.success('Plan updated.');
    },
    onError: (err: any) => notify.error(errorMessage(err, 'Failed to change this plan.')),
  });

  // design spec §6.6 -- a Razorpay-backed subscription cannot be moved by the plain dropdown
  // (the backend already refuses it with 409); this is the confirm-then-retry flow that
  // dropdown's 409 error message alone left no way to actually act on.
  const cancelPaidMutation = useMutation({
    mutationFn: (userId: string) => adminSubscriptionsApi.cancelPaidSubscription(userId),
    onSuccess: () => {
      setConfirmingCancelFor(null);
      void queryClient.invalidateQueries({ queryKey: ['admin-subscriptions'] });
      notify.success('Paid subscription cancelled. You can now change this user\'s plan.');
    },
    onError: (err: any) => {
      setConfirmingCancelFor(null);
      notify.error(errorMessage(err, 'Failed to cancel this subscription.'));
    },
  });

  const columns: DataTableColumn<SubscriptionSummaryDto>[] = [
    {
      header: 'User',
      render: (s) => (
        <div className="flex items-center gap-2.5">
          <span className="w-7 h-7 rounded-lg bg-bg border border-border flex items-center justify-center flex-shrink-0">
            <UsersIcon size={13} className="text-muted" />
          </span>
          <div className="min-w-0">
            <p className="font-medium text-ink truncate">{s.userFullName ?? '(no name)'}</p>
            <p className="text-xs text-muted truncate">{s.userEmail}</p>
          </div>
        </div>
      ),
    },
    {
      header: 'Plan',
      render: (s) =>
        s.paymentProvider === 'RAZORPAY' ? (
          <div className="flex items-center gap-2">
            <span className="text-xs text-ink">{s.planCode}</span>
            <button
              type="button"
              onClick={() => setConfirmingCancelFor(s)}
              className="text-[11px] font-semibold text-danger hover:underline"
            >
              Cancel paid subscription
            </button>
          </div>
        ) : (
          <select
            value={s.planCode ?? ''}
            disabled={changePlanMutation.isPending}
            onChange={(e) => changePlanMutation.mutate({ userId: s.userId, planCode: e.target.value })}
            className="text-xs border border-border rounded-lg px-2 py-1.5 bg-card text-ink"
          >
            {PLAN_CODES.map((code) => (
              <option key={code} value={code}>{code}</option>
            ))}
          </select>
        ),
    },
    { header: 'Status', render: (s) => s.status, cellClassName: 'text-muted' },
    { header: 'Start date', render: (s) => s.startDate, cellClassName: 'text-muted' },
    { header: 'Renewal date', render: (s) => s.renewalDate ?? '—', cellClassName: 'text-muted' },
  ];

  return (
    <div className="space-y-4">
      <p className="text-sm text-muted max-w-xl">
        Every user's current plan. A Razorpay-backed subscription must be cancelled here before its
        plan can be changed manually -- see design spec §6.6.
      </p>
      <DataTable
        columns={columns}
        rows={data?.content ?? []}
        keyFor={(s) => s.subscriptionId}
        loading={isLoading}
        emptyMessage="No subscriptions yet."
      />
      {data && (
        <Pagination
          page={page}
          totalPages={data.totalPages}
          totalElements={data.totalElements}
          pageSize={PAGE_SIZE}
          onPageChange={setPage}
        />
      )}
      {confirmingCancelFor && (
        <ConfirmDialog
          title="Cancel this user's paid subscription?"
          message={`This immediately stops ${confirmingCancelFor.userEmail ?? 'this user'}'s Razorpay subscription. You can then change their plan manually.`}
          confirmLabel="Confirm"
          danger
          busy={cancelPaidMutation.isPending}
          onConfirm={() => cancelPaidMutation.mutate(confirmingCancelFor.userId)}
          onCancel={() => setConfirmingCancelFor(null)}
        />
      )}
    </div>
  );
}

export default function Subscriptions() {
  return (
    <AdminLayout title="Subscriptions" subtitle="Manage user plans -- Free, Plus, Premium">
      <RequirePermission permission="SUBSCRIPTION_MANAGEMENT_VIEW">
        <SubscriptionsContent />
      </RequirePermission>
    </AdminLayout>
  );
}
```

Confirmed: `admin-portal/src/components/ConfirmDialog.tsx` already exists with exactly this prop
shape (`title`/`message`/`confirmLabel`/`cancelLabel`/`danger`/`busy`/`onConfirm`/`onCancel`) — the
same component shape as the frontend app's own `design-system/ConfirmDialog` (Task 5), per that
file's own doc comment. The import above is correct as written, no adjustment needed.

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd admin-portal && npx vitest run src/pages/Subscriptions.test.tsx
```
Expected: PASS.

- [ ] **Step 5: Run the full admin-portal suite and typecheck**

```bash
cd admin-portal && npx vitest run
npx tsc --noEmit
```
Expected: PASS, no errors.

- [ ] **Step 6: Commit**

```bash
git add admin-portal/src/pages/Subscriptions.tsx admin-portal/src/pages/Subscriptions.test.tsx
git commit -m "feat(admin-portal): add confirm flow for cancelling a Razorpay-backed subscription"
```

---

## Final verification

- [ ] **Run the full backend suite**

```bash
cd backend && ./mvnw test
```

- [ ] **Run both frontend suites and typechecks**

```bash
cd frontend && npx vitest run && npx tsc --noEmit
cd ../admin-portal && npx vitest run && npx tsc --noEmit
```

- [ ] **Manual smoke check** (this plan has no Razorpay sandbox credentials to test against —
  §10 of the design spec already flags live-sandbox verification as an external dependency, not
  engineering work): confirm the app builds and the Billing page renders for a Free user, a
  Plus/Premium user, and a user with a pending downgrade, using the same kind of manual
  `subscriptionRepository.save(...)` fixture setup the backend ITs already use, driven through the
  actual running frontend rather than a component test, if a real Razorpay test-mode key becomes
  available before this ships.

## Plan self-review notes

- **Task 1 exists because of a real, evidence-grounded finding**, not a hypothetical: read
  directly from the merged `BillingCheckoutService.java`/`BillingController.java`, not inferred.
- **Task 2's pending-order recovery also exists because of a real, evidence-grounded finding**
  (§0.5): an external review of this plan's first draft raised abandoned-checkout recovery as a UX
  nice-to-have; checking it against the actual code turned up that it's a correctness fix to
  already-merged Plan 2 code, not new scope — the duplicate-order guard added earlier this session
  (PR #1016) has no way to ever release a stale `PENDING` order, so it silently became a permanent
  lockout the moment it merged. Confirmed by grepping every writer of `SubscriptionOrder.status` in
  the codebase, not assumed.
- **No new backend domain concepts.** Tasks 1-2 extend existing DTOs/services; no new entity, no
  new table, no new Flyway migration. `PendingOrderDto` and `cancelPendingOrder` reuse the
  `subscription_orders` table's existing `STATUS_ABANDONED` constant, which existed but had never
  been written by anything until now.
- **Mobile is explicitly out of scope for this plan** (design spec §8's "Mobile" bullet is a
  separate future plan, matching the established one-plan-per-platform-slice pattern from Plans
  1-2's own "Web"/backend split).
- **Coupons/proration/refunds/free trial** remain out of scope, unchanged from the design spec's
  own §9 — this plan adds no UI surface for any of them.
- **A consolidated "admin Subscription Health" view was considered and deliberately deferred**
  (also raised by the same external review) — real new scope (aggregate-count queries, a new admin
  page) that's more useful as its own plan once real subscribers exist to measure, not a Plan 3
  blocker.
- **One reviewed claim was checked and found already correct, not fixed**: the review suggested
  distinguishing a retryable payment failure from a terminal one. `RazorpayWebhookDispatcher`
  already does exactly this — `handlePending` writes `Payment.STATUS_PENDING`, only `handleHalted`
  (retries exhausted) ever writes `STATUS_FAILED`. No change made.
- **The "activating…" poll (Task 5) is the only new client-side polling in this plan** — every
  other mutation (cancel, schedule-a-downgrade, admin cancel-paid-subscription, cancel-a-pending-
  order) takes effect synchronously in the same HTTP response, so a plain query invalidation is
  enough there; adding a poll to those too would be unearned complexity.
