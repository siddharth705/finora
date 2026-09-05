# Subscription Billing V2 — Upgrade/Downgrade + Admin Override Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build user-initiated upgrade/downgrade (`change-plan`), the admin-override guard against
overwriting a live paid subscription, and the matching admin action to release one — the three
pieces the original design spec scoped to "Plan 2" (§6.4–§6.6) — plus nullable tax/invoice schema
prep (Task 5) added after product review, with no behavior attached to it yet. This is Plan 2 of 4
(Plan 1: backend core, merged — [`#1008`](https://github.com/siddharth705/finora/pull/1008); Plan 3:
web UI; Plan 4: mobile UI), fully testable on its own on top of Plan 1's already-merged
infrastructure.

**Architecture:** Downgrade uses Razorpay's own native scheduled-plan-change feature
(`schedule_change_at: cycle_end`) and needs no new reconciliation code — Plan 1's
`RazorpayWebhookDispatcher.handleCharged` already resolves the charged Razorpay plan id back to a
local `(tier, cycle)` via `billing_prices` and corrects `subscriptions.plan_id` on drift; this plan
only has to request the schedule and record it for visibility. Upgrade creates a second, real,
external Razorpay subscription and waits for ITS activation webhook before cancelling the old one —
the existing single-mutable-`subscriptions`-row model never grows a second row, and the old-cancel
side-effect lives inside `RazorpayWebhookDispatcher.handleActivated`, extended to fire only when
activation is completing over a pre-existing different Razorpay subscription id (which naturally
distinguishes "this is an upgrade completing" from "this is a brand-new signup," with zero new
branching).

**Tech Stack:** Same as Plan 1 — Spring Boot 3 (Java, Jakarta), Spring Data JPA, PostgreSQL,
`razorpay-java` SDK (already a dependency), JUnit 5 + Mockito (unit), Testcontainers-backed `*IT`
(integration), ArchUnit (already-enforced invariants — see Global Constraints).

**Spec:** [`docs/superpowers/specs/2026-09-05-subscription-billing-v1-design.md`](../specs/2026-09-05-subscription-billing-v1-design.md)
§6.4 (downgrade), §6.5 (upgrade), §6.6 (admin override) — read alongside this plan. **§6.7
(referral trigger) is explicitly dropped from this plan's scope — see "Spec deviations" below; do
not implement it.**

## Spec deviations (found during planning, grounded in re-reading the current codebase and
## Razorpay's own docs — not guessed)

- **§6.7 (referral trigger) does not apply and is not part of this plan.** The spec assumes
  `ReferralService.onPlanChanged` exists and currently fires from the admin's manual `changePlan`
  call. Checked the actual current `ReferralService.java`: no such method exists, and
  `SubscriptionService.changePlan` calls nothing referral-related. The referral system was
  descoped to relationship-tracking only (no reward mechanism) in an earlier, separate change —
  there is nothing to "move." If a referral-reward mechanism is built later, wiring its trigger is
  that work's own concern, not this plan's.
- **The referral-trigger risk the spec itself flagged in §10 is confirmed real, for the record.**
  §10 said to confirm `subscription.activated` fires only after a real charge "before relying on it
  as the referral-payout trigger." Checked against Razorpay's own webhook docs: `activated` can fire
  from `pending`/`halted` → `active` with **no** charge on that transition (only future invoices get
  charged), and Razorpay's own sample payloads include a "no upfront amount" activation. So
  `subscription.activated` alone is not proof of payment — `subscription.charged` is Razorpay's own
  unambiguous "a successful charge is made" event. This doesn't change anything in *this* plan (no
  referral trigger is being built), but if a referral-reward mechanism is added later, it must key
  off `subscription.charged`, not `subscription.activated` — recorded here so that mistake doesn't
  get made from a stale reading of §6.7/§10.
- **Same-tier billing-cycle changes (e.g. Plus/Monthly → Plus/Yearly) are out of scope.** The spec's
  §6.4/§6.5 only describe tier changes ("Plus↔Premium"). Cycle-only changes aren't specified by
  either flow (immediate vs. scheduled is ambiguous for that case), so `change-plan` explicitly
  refuses this combination with a clear message rather than guessing a behavior. Cancel-and-resubscribe
  remains the only supported path for a pure cycle change in V1.
- **Moving to FREE stays on the existing `/api/v1/billing/cancel` endpoint (Plan 1), not
  `change-plan`.** `change-plan` refuses `planCode: "FREE"` outright and points at `/cancel`.

## Global Constraints

Everything Plan 1's own Global Constraints section states still applies (ArchUnit rules, INR-only,
`AbstractIntegrationTest` profile requirement). Additionally, specific to this plan:

- No new Flyway migration is needed — `plan_changes.reason` already accepts
  `REASON_DOWNGRADE_SCHEDULED` (added in Plan 1's V154), and no other schema change is required.
  If a task below turns out to need one anyway, stop and re-verify against
  `git ls-tree -r --name-only origin/main -- backend/src/main/resources/db/migration | sort -t'V' -k2 -n | tail -3`
  before creating it — do not assume V155 is free without checking.
- `RazorpaySubscriptionGateway.cancelSubscription(id, cancelAtCycleEnd)` and
  `.updateSubscription(id, newPlanId, scheduleAtCycleEnd)` already exist (Plan 1) with the exact
  semantics this plan needs — `cancelAtCycleEnd=false` for an immediate stop (used here for both the
  upgrade's old-subscription cancellation and the admin's cancel action), `scheduleAtCycleEnd=true`
  for a deferred downgrade. Neither the interface nor `RazorpaySubscriptionGatewayImpl` needs any
  change in this plan.
- Tier ordering (FREE < PLUS < PREMIUM) is a fixed, hardcoded ordinal list in code, matching how the
  three plan codes are already a fixed catalog elsewhere in this codebase (`Plan`'s own class doc) —
  no new `plans` column for this.

---

## Task 1: `change-plan` endpoint — downgrade and upgrade-initiation

**Files:**
- Modify: `backend/src/main/java/com/finora/service/BillingCheckoutService.java` (add
  `PlanChangeRepository` dependency, add `changePlan` method + two private helpers)
- Modify: `backend/src/main/java/com/finora/dto/BillingDtos.java` (add `UserChangePlanRequest`)
- Modify: `backend/src/main/java/com/finora/controller/BillingController.java` (add
  `POST /change-plan`)
- Test: `backend/src/test/java/com/finora/service/BillingCheckoutServiceTest.java` (extend)
- Test: `backend/src/test/java/com/finora/controller/BillingControllerIT.java` (extend)

**Interfaces:**
- Consumes: `PlanChangeRepository` (existing, unmodified — `findBySubscriptionIdOrderByCreatedAtDesc`,
  `findBySubscriptionIdInOrderByCreatedAtDesc`, both already present), everything
  `BillingCheckoutService` already depends on.
- Produces: `BillingCheckoutService.changePlan(UUID userId, String planCode, String billingCycle) : void`.
  `POST /api/v1/billing/change-plan`.

- [ ] **Step 1: Add `UserChangePlanRequest` to `BillingDtos.java`**

Add alongside the existing records (the admin-facing `ChangePlanRequest` already exists and is a
different shape — `reason` instead of `billingCycle` — so this needs its own name, not a reuse):

```java
    /** POST /api/v1/billing/change-plan (design spec §6.4/§6.5) -- user-initiated upgrade/downgrade,
     *  distinct from the admin-facing {@link ChangePlanRequest} above. */
    public record UserChangePlanRequest(
            @NotBlank(message = "Plan code is required") String planCode,
            @NotBlank(message = "Billing cycle is required") String billingCycle
    ) {}
```

- [ ] **Step 2: Write the failing unit tests**

Add to `BillingCheckoutServiceTest.java`. First, update the mock list and constructor call to add
the new `PlanChangeRepository` dependency (the 7th constructor parameter):

```java
    private final PlanChangeRepository planChangeRepository = mock(PlanChangeRepository.class);
```

```java
        service = new BillingCheckoutService(planRepository, billingPriceRepository,
                subscriptionOrderRepository, subscriptionRepository, planChangeRepository, gateway, properties);
```

Add `import com.finora.entity.PlanChange;` and `import com.finora.repository.PlanChangeRepository;`
and `import java.time.LocalDate;` to the test file's imports.

Then add the new tests:

```java
    @Test
    void changePlanRefusesMovingToFree() {
        assertThatThrownBy(() -> service.changePlan(userId, "FREE", "MONTHLY"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cancel");
    }

    @Test
    void changePlanRefusesWhenTheUserHasNoBillingSubscriptionYet() {
        Subscription free = new Subscription(); // razorpaySubscriptionId left null
        ReflectionTestUtils.setField(free, "id", UUID.randomUUID());
        free.setPlanId(UUID.randomUUID());
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(free));

        assertThatThrownBy(() -> service.changePlan(userId, "PREMIUM", "MONTHLY"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("checkout");
    }

    @Test
    void changePlanRefusesACycleOnlyChangeAtTheSameTier() {
        UUID plusPlanId = UUID.randomUUID();
        Plan plus = new Plan();
        ReflectionTestUtils.setField(plus, "id", plusPlanId);
        plus.setCode("PLUS");
        when(planRepository.findByCode("PLUS")).thenReturn(Optional.of(plus));
        when(planRepository.findById(plusPlanId)).thenReturn(Optional.of(plus));

        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setPlanId(plusPlanId);
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId("sub_existing");
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(subscription));

        assertThatThrownBy(() -> service.changePlan(userId, "PLUS", "YEARLY"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    void changePlanIsANoOpWhenAlreadyOnTheRequestedPlanAndCycle() {
        UUID plusPlanId = UUID.randomUUID();
        Plan plus = new Plan();
        ReflectionTestUtils.setField(plus, "id", plusPlanId);
        plus.setCode("PLUS");
        when(planRepository.findByCode("PLUS")).thenReturn(Optional.of(plus));
        when(planRepository.findById(plusPlanId)).thenReturn(Optional.of(plus));

        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setPlanId(plusPlanId);
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId("sub_existing");
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(subscription));

        service.changePlan(userId, "PLUS", "MONTHLY");

        verify(gateway, never()).updateSubscription(any(), any(), anyBoolean());
        verify(gateway, never()).createSubscription(any(), any(), anyMap());
    }

    @Test
    void changePlanSchedulesADowngradeAndRecordsAPlanChangeRow() {
        UUID premiumPlanId = planId; // reuse the PREMIUM plan already stubbed in setUp()
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

        service.changePlan(userId, "PLUS", "MONTHLY");

        verify(gateway).updateSubscription("sub_existing", "plan_plus_monthly", true);
        verify(gateway, never()).createSubscription(any(), any(), anyMap());

        ArgumentCaptor<PlanChange> changeCaptor = ArgumentCaptor.forClass(PlanChange.class);
        verify(planChangeRepository).save(changeCaptor.capture());
        PlanChange change = changeCaptor.getValue();
        assertThat(change.getSubscriptionId()).isEqualTo(subscription.getId());
        assertThat(change.getFromPlanId()).isEqualTo(premiumPlanId);
        assertThat(change.getToPlanId()).isEqualTo(plusPlanId);
        assertThat(change.getReason()).isEqualTo(PlanChange.REASON_DOWNGRADE_SCHEDULED);
        assertThat(change.getEffectiveAt()).isEqualTo(
                LocalDate.of(2026, 10, 5).atStartOfDay(java.time.ZoneOffset.UTC).toInstant());

        // Downgrade never touches the subscriptions row directly -- reconciliation happens later,
        // at the next subscription.charged webhook (Plan 1's existing handleCharged), not here.
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void changePlanInitiatesAnUpgradeByCreatingANewRazorpaySubscription() {
        UUID plusPlanId = UUID.randomUUID(); // deliberately NOT the "planId" field from setUp() --
                                              // this test is the current plan, distinct from setUp()'s premium
        UUID premiumPlanId = UUID.randomUUID();
        Plan premium = new Plan();
        ReflectionTestUtils.setField(premium, "id", premiumPlanId);
        premium.setCode("PREMIUM");
        when(planRepository.findByCode("PREMIUM")).thenReturn(Optional.of(premium));

        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setPlanId(plusPlanId);
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId("sub_old");
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(subscription));
        Plan plus = new Plan();
        ReflectionTestUtils.setField(plus, "id", plusPlanId);
        plus.setCode("PLUS");
        when(planRepository.findById(plusPlanId)).thenReturn(Optional.of(plus));

        BillingPrice premiumMonthly = new BillingPrice();
        premiumMonthly.setPlanId(premiumPlanId);
        premiumMonthly.setBillingCycle("MONTHLY");
        premiumMonthly.setPrice(new BigDecimal("799.00"));
        premiumMonthly.setRazorpayPlanId("plan_premium_monthly");
        when(billingPriceRepository.findByPlanIdAndBillingCycleAndActiveTrue(premiumPlanId, "MONTHLY"))
                .thenReturn(Optional.of(premiumMonthly));
        when(gateway.createSubscription(eq("plan_premium_monthly"), eq("MONTHLY"), anyMap()))
                .thenReturn(new RazorpaySubscriptionDto("sub_new", "created"));

        service.changePlan(userId, "PREMIUM", "MONTHLY");

        verify(gateway, never()).updateSubscription(any(), any(), anyBoolean());
        verify(gateway, never()).cancelSubscription(any(), anyBoolean());
        verify(planChangeRepository, never()).save(any());

        ArgumentCaptor<com.finora.entity.SubscriptionOrder> orderCaptor =
                ArgumentCaptor.forClass(com.finora.entity.SubscriptionOrder.class);
        verify(subscriptionOrderRepository).save(orderCaptor.capture());
        com.finora.entity.SubscriptionOrder order = orderCaptor.getValue();
        assertThat(order.getUserId()).isEqualTo(userId);
        assertThat(order.getPlanId()).isEqualTo(premiumPlanId);
        assertThat(order.getRazorpaySubscriptionId()).isEqualTo("sub_new");
        assertThat(order.getStatus()).isEqualTo(com.finora.entity.SubscriptionOrder.STATUS_PENDING);

        // The existing subscriptions row is untouched until the new subscription's own activation
        // webhook confirms real payment (design spec §6.5 step 2) -- this call must not mutate it.
        assertThat(subscription.getPlanId()).isEqualTo(plusPlanId);
        assertThat(subscription.getRazorpaySubscriptionId()).isEqualTo("sub_old");
    }
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
cd backend && ./mvnw test -Dtest=BillingCheckoutServiceTest
```
Expected: FAIL — `changePlan` doesn't exist yet, and the constructor call in `setUp()` won't compile
against the current 6-parameter constructor.

- [ ] **Step 4: Add the `PlanChangeRepository` dependency and `changePlan` to `BillingCheckoutService.java`**

Add the import and field/constructor parameter:

```java
import com.finora.entity.PlanChange;
import com.finora.repository.PlanChangeRepository;
```

Replace the field list and constructor (adds one new field/parameter, keeps every existing one):

```java
    private final PlanRepository planRepository;
    private final BillingPriceRepository billingPriceRepository;
    private final SubscriptionOrderRepository subscriptionOrderRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanChangeRepository planChangeRepository;
    private final RazorpaySubscriptionGateway gateway;
    private final RazorpayProperties properties;

    public BillingCheckoutService(PlanRepository planRepository, BillingPriceRepository billingPriceRepository,
                                   SubscriptionOrderRepository subscriptionOrderRepository,
                                   SubscriptionRepository subscriptionRepository,
                                   PlanChangeRepository planChangeRepository,
                                   RazorpaySubscriptionGateway gateway, RazorpayProperties properties) {
        this.planRepository = planRepository;
        this.billingPriceRepository = billingPriceRepository;
        this.subscriptionOrderRepository = subscriptionOrderRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.planChangeRepository = planChangeRepository;
        this.gateway = gateway;
        this.properties = properties;
    }
```

Add the tier-ordering constant and the three new methods (`changePlan` plus its two private
helpers), placed after the existing `cancel` method:

```java
    /** design spec §6.4/§6.5. Fixed, hardcoded ordering -- matches Plan's own class doc: the three
     *  plan codes are a fixed, product-approved catalog, not expected to grow without a broader
     *  product decision, so this needs no database column of its own. */
    private static final java.util.List<String> TIER_ORDER = java.util.List.of("FREE", "PLUS", "PREMIUM");

    @Transactional
    public void changePlan(UUID userId, String planCode, String billingCycle) {
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
                return;
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
            upgradeToNewSubscription(userId, newPlan, newPrice, billingCycle);
        } else {
            scheduleDowngrade(subscription, currentPlan, newPlan, newPrice.getRazorpayPlanId());
        }
    }

    /** design spec §6.5. Creates a NEW, real, external Razorpay subscription and a PENDING
     *  {@code subscription_orders} row carrying its id -- the existing {@code subscriptions} row is
     *  left completely untouched (still on the old plan, still pointing at the old
     *  razorpaySubscriptionId) until the new subscription's own activation webhook confirms real
     *  payment ({@code RazorpayWebhookDispatcher.handleActivated}, extended in Task 2 of this plan
     *  to also stop the old mandate at that point, not before). */
    private void upgradeToNewSubscription(UUID userId, Plan newPlan, BillingPrice newPrice, String billingCycle) {
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
    }

    /** design spec §6.4. Razorpay's own scheduled-plan-change feature defers the actual switch to
     *  the next billing cycle; {@code subscriptions.plan_id}/{@code billing_cycle} are corrected
     *  later by {@code RazorpayWebhookDispatcher.handleCharged}'s existing plan-id reconciliation
     *  (built in Plan 1, unmodified here) the next time this subscription is actually charged -- no
     *  separate "apply" job. This method only calls Razorpay and records the {@link PlanChange} row
     *  so the billing portal can show "Downgrading to X on <date>." */
    private void scheduleDowngrade(Subscription subscription, Plan currentPlan, Plan newPlan, String newRazorpayPlanId) {
        gateway.updateSubscription(subscription.getRazorpaySubscriptionId(), newRazorpayPlanId, true);

        PlanChange change = new PlanChange();
        change.setSubscriptionId(subscription.getId());
        change.setFromPlanId(currentPlan.getId());
        change.setToPlanId(newPlan.getId());
        change.setEffectiveAt(subscription.getRenewalDate() != null
                ? subscription.getRenewalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
                : Instant.now());
        change.setReason(PlanChange.REASON_DOWNGRADE_SCHEDULED);
        planChangeRepository.save(change);
    }
```

Add `import java.time.Instant;` if not already present in the file (it is not).

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd backend && ./mvnw test -Dtest=BillingCheckoutServiceTest
```
Expected: PASS.

- [ ] **Step 6: Add the endpoint to `BillingController.java`**

```java
    @PostMapping("/change-plan")
    public ApiResponse<Void> changePlan(@Valid @RequestBody com.finora.dto.BillingDtos.UserChangePlanRequest request) {
        billingCheckoutService.changePlan(currentUser.id(), request.planCode(), request.billingCycle());
        return ApiResponse.ok(null, "Plan change requested");
    }
```

- [ ] **Step 7: Write the failing integration test**

Add to `BillingControllerIT.java`:

```java
    @Test
    void changePlanSchedulesADowngradeForAnExistingPaidSubscriber() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        subscription.setPlanId(premium.getId());
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId("sub_test_" + UUID.randomUUID());
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        BillingPrice plusMonthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(planRepository.findByCode("PLUS").orElseThrow().getId(), "MONTHLY")
                .orElseThrow();
        plusMonthly.setRazorpayPlanId("plan_test_" + UUID.randomUUID());
        billingPriceRepository.save(plusMonthly);

        HttpEntity<String> request = new HttpEntity<>(
                "{\"planCode\":\"PLUS\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/billing/change-plan", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(gateway).updateSubscription(eq(subscription.getRazorpaySubscriptionId()),
                eq(plusMonthly.getRazorpayPlanId()), eq(true));
    }
```

Add `import com.finora.entity.BillingPrice;` and `import static org.mockito.Mockito.verify;` if not
already present (`BillingPrice` is not imported in this file yet; `Mockito.verify` is not either).

- [ ] **Step 8: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=BillingControllerIT
```
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/finora/service/BillingCheckoutService.java \
        backend/src/main/java/com/finora/dto/BillingDtos.java \
        backend/src/main/java/com/finora/controller/BillingController.java \
        backend/src/test/java/com/finora/service/BillingCheckoutServiceTest.java \
        backend/src/test/java/com/finora/controller/BillingControllerIT.java
git commit -m "feat(backend): add user-initiated change-plan endpoint for upgrade/downgrade"
```

---

## Task 2: Upgrade completion — cancel the old subscription on activation

**Files:**
- Modify: `backend/src/main/java/com/finora/service/RazorpayWebhookDispatcher.java`
- Modify: `backend/src/test/java/com/finora/service/RazorpayWebhookDispatcherIT.java`

**Interfaces:**
- Consumes: `RazorpaySubscriptionGateway` (existing, Plan 1) — new dependency for this class.
- Produces: `handleActivated` now also stops the old Razorpay subscription when activation is
  completing an upgrade (a pre-existing, different `razorpaySubscriptionId` on the row before this
  call). No new public method — this is a behavior extension of the existing one.

- [ ] **Step 1: Add the failing test to `RazorpayWebhookDispatcherIT`**

Add the mock field (this class gains a real `RazorpaySubscriptionGatewayImpl` bean by default from
Plan 1 — mock it here the same way `BillingControllerIT`/`SubscriptionBillingEndToEndIT` already do,
so no real network calls happen):

```java
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.finora.integrations.razorpay.RazorpaySubscriptionGateway gateway;
```

```java
    @Test
    void activatingAnUpgradeCancelsTheOldRazorpaySubscriptionImmediately() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        String oldRazorpaySubscriptionId = "sub_old_" + UUID.randomUUID();
        String newRazorpaySubscriptionId = "sub_new_" + UUID.randomUUID();

        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPlanId(plus.getId());
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId(oldRazorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        SubscriptionOrder order = new SubscriptionOrder();
        order.setUserId(user.getId());
        order.setPlanId(premium.getId());
        order.setBillingCycle("MONTHLY");
        order.setRazorpaySubscriptionId(newRazorpaySubscriptionId);
        order.setStatus(SubscriptionOrder.STATUS_PENDING);
        order.setAmount(new BigDecimal("799.00"));
        subscriptionOrderRepository.save(order);

        Map<String, Object> payload = Map.of(
                "subscription", Map.of("entity", Map.of(
                        "id", newRazorpaySubscriptionId, "current_end", 1893456000L))); // synthetic-ok: fixture epoch second

        dispatcher.dispatch("subscription.activated", payload);

        Subscription reloaded = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        assertThat(reloaded.getPlanId()).isEqualTo(premium.getId());
        assertThat(reloaded.getRazorpaySubscriptionId()).isEqualTo(newRazorpaySubscriptionId);
        verify(gateway).cancelSubscription(oldRazorpaySubscriptionId, false);
    }

    @Test
    void aBrandNewSignupActivationNeverCallsCancel() {
        // The existing activation path (Plan 1) has no prior razorpaySubscriptionId on the row --
        // the same code that stops an old upgrade mandate must do nothing here.
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
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
                "subscription", Map.of("entity", Map.of("id", razorpaySubscriptionId, "current_end", 1893456000L))); // synthetic-ok: fixture epoch second

        dispatcher.dispatch("subscription.activated", payload);

        verify(gateway, never()).cancelSubscription(any(), anyBoolean());
    }
```

Add `import static org.mockito.ArgumentMatchers.any;`, `import static org.mockito.ArgumentMatchers.anyBoolean;`,
and `import static org.mockito.Mockito.verify;` to the test file's imports.

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd backend && ./mvnw test -Dtest=RazorpayWebhookDispatcherIT
```
Expected: FAIL on the two new tests, and every other test in this file also fails to compile/run
until Step 3 adds the new constructor dependency this class needs to be wired.

- [ ] **Step 3: Add the `RazorpaySubscriptionGateway` dependency and the cancel-old-subscription
  logic to `RazorpayWebhookDispatcher.java`**

Add the import:

```java
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
```

Add the field and update the constructor (adds one new parameter, keeps every existing one):

```java
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionOrderRepository subscriptionOrderRepository;
    private final SubscriptionEventRepository subscriptionEventRepository;
    private final PlanRepository planRepository;
    private final BillingPriceRepository billingPriceRepository;
    private final PaymentRepository paymentRepository;
    private final RazorpaySubscriptionGateway gateway;

    public RazorpayWebhookDispatcher(SubscriptionRepository subscriptionRepository,
                                      SubscriptionOrderRepository subscriptionOrderRepository,
                                      SubscriptionEventRepository subscriptionEventRepository,
                                      PlanRepository planRepository,
                                      BillingPriceRepository billingPriceRepository,
                                      PaymentRepository paymentRepository,
                                      RazorpaySubscriptionGateway gateway) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionOrderRepository = subscriptionOrderRepository;
        this.subscriptionEventRepository = subscriptionEventRepository;
        this.planRepository = planRepository;
        this.billingPriceRepository = billingPriceRepository;
        this.paymentRepository = paymentRepository;
        this.gateway = gateway;
    }
```

In `handleActivated`, capture the subscription's OLD Razorpay id before it gets overwritten, and
call the gateway after the row is saved. Replace this block:

```java
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
```

with:

```java
        String oldRazorpaySubscriptionId = subscription.getRazorpaySubscriptionId();

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

        // design spec §6.5 step 4. A pre-existing, DIFFERENT razorpaySubscriptionId on the row
        // means this activation is completing an upgrade (Task 1 of this plan) over an old, still
        // live subscription -- stop it now that the new one is confirmed active, not before (spec
        // §6.5's whole reason for this ordering: cancelling first would risk leaving the user with
        // no active paid access if they abandoned the new checkout). Deliberately NOT allowed to
        // roll back this transaction: the new subscription is genuinely active and correctly
        // billing regardless of whether stopping the old one succeeds, so a Razorpay error here is
        // caught, logged for manual follow-up, and does not undo the activation that already
        // happened. A brand-new signup has no prior razorpaySubscriptionId (null), so this never
        // fires for that path.
        if (oldRazorpaySubscriptionId != null && !oldRazorpaySubscriptionId.equals(razorpaySubscriptionId)) {
            try {
                gateway.cancelSubscription(oldRazorpaySubscriptionId, false);
            } catch (RuntimeException e) {
                log.error("Upgrade completed for user {} but cancelling the old Razorpay subscription {} " +
                        "failed -- requires manual follow-up to stop it from charging again.",
                        subscription.getUserId(), oldRazorpaySubscriptionId, e);
            }
        }

        SubscriptionEvent event = new SubscriptionEvent();
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd backend && ./mvnw test -Dtest=RazorpayWebhookDispatcherIT
```
Expected: PASS — all tests in this file, including every one from Plan 1 (the constructor gained a
parameter; Spring wires it automatically for the `@Autowired dispatcher` field, but re-run the whole
file to confirm nothing else in it broke).

- [ ] **Step 5: Run the full webhook controller and end-to-end tests too**

```bash
cd backend && ./mvnw test -Dtest=RazorpayWebhookControllerIT,SubscriptionBillingEndToEndIT
```
Expected: PASS — neither test constructs `RazorpayWebhookDispatcher` directly, so the new
constructor parameter should not affect them, but confirm rather than assume.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/service/RazorpayWebhookDispatcher.java \
        backend/src/test/java/com/finora/service/RazorpayWebhookDispatcherIT.java
git commit -m "feat(backend): cancel the old Razorpay subscription when an upgrade's new one activates"
```

---

## Task 3: Admin override guard + cancel-paid-subscription action

**Files:**
- Modify: `backend/src/main/java/com/finora/service/SubscriptionService.java`
- Modify: `backend/src/main/java/com/finora/controller/AdminSubscriptionController.java`
- Test: `backend/src/test/java/com/finora/service/SubscriptionServiceTest.java` (extend)
- Test: `backend/src/test/java/com/finora/controller/AdminSubscriptionControllerIT.java` (extend)

**Interfaces:**
- Consumes: `RazorpaySubscriptionGateway` (Plan 1) — new dependency for `SubscriptionService`.
- Produces: `SubscriptionService.changePlan` gains a 409 guard (existing signature, no change).
  `SubscriptionService.cancelPaidSubscription(UUID userId, UUID actingAdminId) : void` (new).
  `POST /api/v1/admin/subscriptions/{userId}/cancel-paid-subscription`.

- [ ] **Step 1: Write the failing unit tests**

Add to `SubscriptionServiceTest.java`. First, update `setUp()` to add the new
`RazorpaySubscriptionGateway` mock and pass it into the constructor (6th parameter):

```java
    private RazorpaySubscriptionGateway gateway;
```

```java
        gateway = mock(RazorpaySubscriptionGateway.class);
        service = new SubscriptionService(subscriptionRepository, subscriptionEventRepository,
                planChangeRepository, planRepository, userRepository, auditService, gateway);
```

Add `import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;` to the test file's
imports. Then add the new tests:

```java
    @Test
    void changePlan_refusesWithConflict_whenTheUserHasAnActiveRazorpaySubscription() {
        Subscription existing = new Subscription();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        existing.setUserId(userId);
        existing.setPlanId(UUID.randomUUID());
        existing.setStatus(Subscription.STATUS_ACTIVE);
        existing.setPaymentProvider("RAZORPAY");
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.changePlan(userId, "PLUS", "beta tester", adminId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Cancel it first");
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void changePlan_stillWorks_forATrialRazorpaySubscription() {
        // findActiveOrTrial can return either ACTIVE or TRIAL -- the guard must fire for both, not
        // just the ACTIVE case above.
        Subscription existing = new Subscription();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        existing.setUserId(userId);
        existing.setPlanId(UUID.randomUUID());
        existing.setStatus(Subscription.STATUS_TRIAL);
        existing.setPaymentProvider("RAZORPAY");
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.changePlan(userId, "PLUS", "beta tester", adminId))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void changePlan_stillWorks_forAnAdminGrantSubscriptionWithNoRazorpayLink() {
        UUID freePlanId = UUID.randomUUID();
        UUID premiumPlanId = UUID.randomUUID();
        Subscription existing = new Subscription();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        existing.setUserId(userId);
        existing.setPlanId(freePlanId);
        existing.setStatus(Subscription.STATUS_ACTIVE);
        existing.setPaymentProvider(null); // not a Razorpay-backed subscription
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(existing));
        when(planRepository.findByCode("PREMIUM")).thenReturn(Optional.of(planWith("PREMIUM", premiumPlanId)));

        service.changePlan(userId, "PREMIUM", "beta tester", adminId);

        assertThat(existing.getPlanId()).isEqualTo(premiumPlanId);
    }

    @Test
    void cancelPaidSubscription_cancelsImmediatelyAndClearsTheRazorpayLink() {
        Subscription existing = new Subscription();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        existing.setUserId(userId);
        existing.setPlanId(UUID.randomUUID());
        existing.setStatus(Subscription.STATUS_ACTIVE);
        existing.setPaymentProvider("RAZORPAY");
        existing.setRazorpaySubscriptionId("sub_test_123");
        existing.setAutoRenew(true);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(existing));

        service.cancelPaidSubscription(userId, adminId);

        verify(gateway).cancelSubscription("sub_test_123", false);
        assertThat(existing.getRazorpaySubscriptionId()).isNull();
        assertThat(existing.isAutoRenew()).isFalse();
        assertThat(existing.getPaymentProvider()).isNull();
        verify(subscriptionRepository).save(existing);
        verify(auditService).record(eq(userId), eq("SUBSCRIPTION_PAID_CANCELLED_BY_ADMIN"),
                eq("Subscription"), eq(existing.getId()), any());
    }

    @Test
    void cancelPaidSubscription_throwsBadRequest_whenThereIsNoBillingToCancel() {
        Subscription existing = new Subscription();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        existing.setUserId(userId);
        existing.setPlanId(UUID.randomUUID());
        existing.setStatus(Subscription.STATUS_ACTIVE);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.cancelPaidSubscription(userId, adminId))
                .isInstanceOf(ApiException.class);
        verify(gateway, never()).cancelSubscription(any(), anyBoolean());
    }
```

Add `import static org.mockito.ArgumentMatchers.anyBoolean;` to the test file's imports if not
already present.

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd backend && ./mvnw test -Dtest=SubscriptionServiceTest
```
Expected: FAIL — the constructor call in `setUp()` won't compile against the current 6-parameter
constructor, and `cancelPaidSubscription` doesn't exist.

- [ ] **Step 3: Add the guard and the new method to `SubscriptionService.java`**

Add the import:

```java
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
```

Add the field and update the constructor (adds one new parameter, keeps every existing one):

```java
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionEventRepository subscriptionEventRepository;
    private final PlanChangeRepository planChangeRepository;
    private final PlanRepository planRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final RazorpaySubscriptionGateway gateway;

    public SubscriptionService(SubscriptionRepository subscriptionRepository,
                                SubscriptionEventRepository subscriptionEventRepository,
                                PlanChangeRepository planChangeRepository,
                                PlanRepository planRepository, UserRepository userRepository,
                                AuditService auditService, RazorpaySubscriptionGateway gateway) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionEventRepository = subscriptionEventRepository;
        this.planChangeRepository = planChangeRepository;
        this.planRepository = planRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.gateway = gateway;
    }
```

In `changePlan`, add the guard immediately after the existing subscription lookup (before the plan
lookup):

```java
    @Transactional
    public void changePlan(UUID userId, String newPlanCode, String reason, UUID actingAdminId) {
        Subscription subscription = subscriptionRepository.findActiveOrTrial(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "This user has no active subscription."));
        if ("RAZORPAY".equals(subscription.getPaymentProvider())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This user has an active paid subscription. Cancel it first before granting a complimentary plan.");
        }
        Plan newPlan = planRepository.findByCode(newPlanCode)
```

(The rest of the existing method body is unchanged — this only inserts the guard between the
existing subscription lookup and the existing plan lookup.)

**Why the guard is a single check, not the three-status list the spec's prose describes:** the
spec's §6.6 text says to guard when status is "ACTIVE/PAST_DUE/TRIAL", but `findActiveOrTrial`
(the lookup this method already uses, unchanged) is hardcoded to
`findByUserIdAndStatusIn(userId, List.of(STATUS_ACTIVE, STATUS_TRIAL))` — a `PAST_DUE` row can
never reach this line in the first place, `orElseThrow` above would already have fired 404 for it.
Listing `STATUS_PAST_DUE` in a `.contains(...)` check the subscription variable could never satisfy
is dead code that would only look meaningful; a mocked unit test that stubs `findActiveOrTrial`
directly to return a `PAST_DUE` subscription (bypassing the real filter) would pass without proving
anything reachable in production. Once `subscription` is non-null here, its status is already
guaranteed `ACTIVE` or `TRIAL` by construction, so the only real condition left to check is the
payment provider. If blocking a `PAST_DUE` admin override specifically is wanted later, it needs
its own lookup (e.g. `findByUserIdOrderByCreatedAtDesc(userId).stream().findFirst()`, the same
all-statuses pattern `BillingCheckoutService.checkout()`'s guard already uses) — out of scope here
since it would also change this method's existing 404 semantics for every other caller, which this
plan has no reason to touch.

Add the new method after `changePlan`:

```java
    /** design spec §6.6. Admin support action, immediate (not at cycle end) -- this is what unblocks
     *  the guard above: an admin cannot grant a complimentary plan over a live Razorpay subscription
     *  until they explicitly stop it here first. Deliberately leaves {@code subscriptions.plan_id}
     *  untouched -- the admin's very next call is expected to be {@link #changePlan}, which will set
     *  whatever plan the admin intends; this method's only job is releasing the Razorpay link. */
    @Transactional
    public void cancelPaidSubscription(UUID userId, UUID actingAdminId) {
        Subscription subscription = subscriptionRepository.findActiveOrTrial(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "This user has no active subscription."));
        if (subscription.getRazorpaySubscriptionId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This subscription has no billing to cancel.");
        }
        gateway.cancelSubscription(subscription.getRazorpaySubscriptionId(), false);
        subscription.setRazorpaySubscriptionId(null);
        subscription.setAutoRenew(false);
        subscription.setPaymentProvider(null);
        subscriptionRepository.save(subscription);

        auditService.record(userId, "SUBSCRIPTION_PAID_CANCELLED_BY_ADMIN", "Subscription", subscription.getId(),
                Map.of("actorId", actingAdminId.toString()));
    }
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd backend && ./mvnw test -Dtest=SubscriptionServiceTest
```
Expected: PASS.

- [ ] **Step 5: Add the endpoint to `AdminSubscriptionController.java`**

```java
    @PostMapping("/{userId}/cancel-paid-subscription")
    @PreAuthorize("hasAuthority('SUBSCRIPTION_MANAGEMENT_MANAGE')")
    public ApiResponse<Void> cancelPaidSubscription(@PathVariable UUID userId) {
        subscriptionService.cancelPaidSubscription(userId, currentUser.id());
        return ApiResponse.ok(null, "Paid subscription cancelled");
    }
```

- [ ] **Step 6: Write the failing integration test**

Add to `AdminSubscriptionControllerIT.java`:

```java
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.finora.integrations.razorpay.RazorpaySubscriptionGateway gateway;

    @org.springframework.beans.factory.annotation.Autowired
    private com.finora.repository.SubscriptionRepository subscriptionRepository;

    @Test
    void admin_isBlockedFromChangingPlan_whileAUserHasAnActiveRazorpaySubscription() {
        User admin = createUser("ADMIN");
        User target = createUser("USER");
        subscriptionService.provisionFreeSubscription(target.getId());
        var subscription = subscriptionRepository.findActiveOrTrial(target.getId()).orElseThrow();
        subscription.setPaymentProvider("RAZORPAY");
        subscription.setRazorpaySubscriptionId("sub_test_" + UUID.randomUUID());
        subscriptionRepository.save(subscription);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/subscriptions/" + target.getId() + "/plan", HttpMethod.PUT,
                new HttpEntity<>(Map.of("planCode", "PLUS", "reason", "test"), bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void admin_cancelsThePaidSubscription_thenChangePlanSucceeds() {
        User admin = createUser("ADMIN");
        User target = createUser("USER");
        subscriptionService.provisionFreeSubscription(target.getId());
        var subscription = subscriptionRepository.findActiveOrTrial(target.getId()).orElseThrow();
        subscription.setPaymentProvider("RAZORPAY");
        subscription.setRazorpaySubscriptionId("sub_test_" + UUID.randomUUID());
        subscriptionRepository.save(subscription);

        ResponseEntity<String> cancelResponse = restTemplate.postForEntity(
                "/api/v1/admin/subscriptions/" + target.getId() + "/cancel-paid-subscription",
                new HttpEntity<>(bearerFor(admin)), String.class);
        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> changePlanResponse = restTemplate.exchange(
                "/api/v1/admin/subscriptions/" + target.getId() + "/plan", HttpMethod.PUT,
                new HttpEntity<>(Map.of("planCode", "PLUS", "reason", "beta tester"), bearerFor(admin)), String.class);
        assertThat(changePlanResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
```

- [ ] **Step 7: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=AdminSubscriptionControllerIT
```
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/finora/service/SubscriptionService.java \
        backend/src/main/java/com/finora/controller/AdminSubscriptionController.java \
        backend/src/test/java/com/finora/service/SubscriptionServiceTest.java \
        backend/src/test/java/com/finora/controller/AdminSubscriptionControllerIT.java
git commit -m "feat(backend): guard admin plan changes against a live Razorpay subscription"
```

---

## Task 4: End-to-end integration test

**Files:**
- Create: `backend/src/test/java/com/finora/controller/SubscriptionUpgradeDowngradeEndToEndIT.java`

**Interfaces:**
- Consumes everything built in Tasks 1–3 plus Plan 1. No new production code.

Matches Plan 1's `SubscriptionBillingEndToEndIT` pattern: real HTTP calls against the real Spring
context, only the Razorpay gateway mocked. Covers the two flows Plan 1's own end-to-end test does
not: a downgrade that reconciles at the next charge, and an upgrade that activates and cancels the
old subscription.

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
import com.finora.repository.SubscriptionRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.RazorpayWebhookDispatcher;
import com.finora.service.SubscriptionService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The upgrade and downgrade paths end to end, on top of Plan 1's already-merged infrastructure.
 * Plan 1's SubscriptionBillingEndToEndIT covers first-time checkout/activation/renewal/history;
 * this test's job is the two flows Plan 2 adds.
 */
class SubscriptionUpgradeDowngradeEndToEndIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private BillingPriceRepository billingPriceRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private RazorpayWebhookDispatcher dispatcher;

    @MockitoBean private RazorpaySubscriptionGateway gateway;

    private User createUser() {
        User user = new User();
        user.setEmail("upgrade-downgrade-e2e-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Upgrade Downgrade E2E IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        User saved = userRepository.save(user);
        subscriptionService.provisionFreeSubscription(saved.getId());
        return saved;
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void downgradeSchedulesNowAndReconcilesAtTheNextCharge() {
        User user = createUser();
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        BillingPrice plusMonthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(plus.getId(), "MONTHLY").orElseThrow();
        String downgradeRazorpayPlanId = "plan_e2e_plus_" + UUID.randomUUID();
        plusMonthly.setRazorpayPlanId(downgradeRazorpayPlanId);
        billingPriceRepository.save(plusMonthly);
        String razorpaySubscriptionId = "sub_e2e_" + UUID.randomUUID();

        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPlanId(premium.getId());
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        // 1. Request the downgrade.
        ResponseEntity<String> changePlanResponse = restTemplate.postForEntity("/api/v1/billing/change-plan",
                new HttpEntity<>("{\"planCode\":\"PLUS\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user)),
                String.class);
        assertThat(changePlanResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(gateway).updateSubscription(razorpaySubscriptionId, downgradeRazorpayPlanId, true);

        // 2. Entitlements still reflect Premium -- the downgrade hasn't taken effect yet.
        ResponseEntity<String> entitlementsBeforeCharge = restTemplate.exchange(
                "/api/v1/entitlements", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(entitlementsBeforeCharge.getBody()).contains("\"planCode\":\"PREMIUM\"");

        // 3. At cycle end, Razorpay charges the NEW (lower) plan -- Plan 1's existing handleCharged
        // reconciliation is what actually applies the downgrade, unmodified by this plan.
        dispatcher.dispatch("subscription.charged", Map.of(
                "payment", Map.of("entity", Map.of("id", "pay_e2e_1", "amount", 39900)),
                "subscription", Map.of("entity", Map.of(
                        "id", razorpaySubscriptionId, "plan_id", downgradeRazorpayPlanId, "current_end", 1896134400L)))); // synthetic-ok: fixture epoch second

        // 4. Entitlements now reflect Plus.
        ResponseEntity<String> entitlementsAfterCharge = restTemplate.exchange(
                "/api/v1/entitlements", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(entitlementsAfterCharge.getBody()).contains("\"planCode\":\"PLUS\"");
    }

    @Test
    void upgradeActivatesTheNewSubscriptionAndCancelsTheOldOne() {
        User user = createUser();
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        BillingPrice premiumMonthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(premium.getId(), "MONTHLY").orElseThrow();
        String upgradeRazorpayPlanId = "plan_e2e_premium_" + UUID.randomUUID();
        premiumMonthly.setRazorpayPlanId(upgradeRazorpayPlanId);
        billingPriceRepository.save(premiumMonthly);
        String oldRazorpaySubscriptionId = "sub_e2e_old_" + UUID.randomUUID();
        String newRazorpaySubscriptionId = "sub_e2e_new_" + UUID.randomUUID();

        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPlanId(plus.getId());
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId(oldRazorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        when(gateway.createSubscription(eq(upgradeRazorpayPlanId), eq("MONTHLY"), anyMap()))
                .thenReturn(new RazorpaySubscriptionDto(newRazorpaySubscriptionId, "created"));

        // 1. Request the upgrade -- a real, external, second Razorpay subscription is created.
        ResponseEntity<String> changePlanResponse = restTemplate.postForEntity("/api/v1/billing/change-plan",
                new HttpEntity<>("{\"planCode\":\"PREMIUM\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user)),
                String.class);
        assertThat(changePlanResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 2. Entitlements still reflect Plus -- never granted from this call's own return value.
        ResponseEntity<String> entitlementsBeforeActivation = restTemplate.exchange(
                "/api/v1/entitlements", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(entitlementsBeforeActivation.getBody()).contains("\"planCode\":\"PLUS\"");

        // 3. The new subscription's activation webhook arrives.
        dispatcher.dispatch("subscription.activated", Map.of(
                "subscription", Map.of("entity", Map.of("id", newRazorpaySubscriptionId, "current_end", 1893456000L)))); // synthetic-ok: fixture epoch second

        // 4. Entitlements now reflect Premium, and the old mandate was stopped.
        ResponseEntity<String> entitlementsAfterActivation = restTemplate.exchange(
                "/api/v1/entitlements", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(entitlementsAfterActivation.getBody()).contains("\"planCode\":\"PREMIUM\"");
        verify(gateway).cancelSubscription(oldRazorpaySubscriptionId, false);

        var reloaded = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        assertThat(reloaded.getRazorpaySubscriptionId()).isEqualTo(newRazorpaySubscriptionId);
    }
}
```

- [ ] **Step 2: Run the tests**

```bash
cd backend && ./mvnw test -Dtest=SubscriptionUpgradeDowngradeEndToEndIT
```
Expected: PASS. If entitlements JSON formatting differs from the literal substrings asserted above,
adjust the assertions to match the real serialized shape (same note as Plan 1's own end-to-end test)
rather than the response format itself.

- [ ] **Step 3: Run the entire backend test suite once, to confirm nothing in Tasks 1–4 regressed
  anything else**

```bash
cd backend && ./mvnw test
```
Expected: PASS. Pay particular attention to `ArchUnit` tests and to any other call site of
`BillingCheckoutService`'s or `SubscriptionService`'s or `RazorpayWebhookDispatcher`'s constructors —
all three gained a parameter in this plan; grep for `new BillingCheckoutService(`, `new
SubscriptionService(`, and `new RazorpayWebhookDispatcher(` across `backend/src` and check every
call site compiles against the new signature before declaring this task done.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/finora/controller/SubscriptionUpgradeDowngradeEndToEndIT.java
git commit -m "test(backend): add end-to-end upgrade and downgrade integration tests"
```

---

## Task 5: Tax and invoice schema prep (no behavior change)

Added per explicit product review after this plan was first drafted: nullable columns only, so a
future GST rollout or invoice-download feature doesn't need a breaking schema change to land. **No
task in this plan populates or reads these columns.** Coupons are deliberately NOT included here —
unlike tax/invoice, a coupon needs no schema prepared in advance: `plan_changes`/`payments` impose
no constraint that would block adding `discount_amount`/`coupon_code` columns whenever a coupon
feature is actually built, so preparing for it now would be speculative schema with no near-term
consumer.

**Files:**
- Create: `backend/src/main/resources/db/migration/V155__billing_tax_and_invoice_fields.sql`
- Modify: `backend/src/main/java/com/finora/entity/Payment.java` (add `baseAmount`, `taxAmount`,
  `invoiceId`, `invoiceUrl`)
- Modify: `backend/src/main/java/com/finora/entity/BillingPrice.java` (add `gstRate`)
- Test: `backend/src/test/java/com/finora/repository/PaymentRepositoryIT.java` (new file — no such
  test exists yet for this entity)

**Interfaces:**
- Produces: `Payment.getBaseAmount()`/`setBaseAmount(BigDecimal)`,
  `.getTaxAmount()`/`setTaxAmount(BigDecimal)`, `.getInvoiceId()`/`setInvoiceId(String)`,
  `.getInvoiceUrl()`/`setInvoiceUrl(String)` — all nullable, all optional. `BillingPrice
  .getGstRate()`/`setGstRate(BigDecimal)` — nullable, a percentage (e.g. `18.00` for 18%), purely
  informational until a real GST calculation is built.
- Consumes: nothing new — this task has no dependency on Tasks 1–4 and none of them depend on it;
  it can be done in any order relative to the rest of this plan.

- [ ] **Step 1: Confirm the migration version is still free**

```bash
git fetch origin
git ls-tree -r --name-only origin/main -- backend/src/main/resources/db/migration | sort -t'V' -k2 -n | tail -3
```
Expected: `V154__subscription_billing_v1.sql` is still the latest (confirmed free at `V155` when
this task was written, 2026-09-05). If a `V155` (or later) already exists, rename this migration to
the next free number and update every reference to `V155` in this task accordingly.

- [ ] **Step 2: Write the migration**

```sql
-- Subscription billing V2 (docs/superpowers/plans/2026-09-05-subscription-billing-v2-upgrade-downgrade-admin.md).
-- Nullable schema prep for tax (GST) and invoice references, requested during product review before
-- any GST or invoice-download feature is actually built. No writer populates these yet -- adding the
-- columns now avoids a breaking migration once one is needed. "amount" on payments keeps its
-- existing meaning (the total actually charged); base_amount/tax_amount are an optional breakdown
-- of that same total, not a replacement for it.

ALTER TABLE payments ADD COLUMN base_amount NUMERIC(10, 2);
ALTER TABLE payments ADD COLUMN tax_amount NUMERIC(10, 2);
ALTER TABLE payments ADD COLUMN invoice_id VARCHAR(50);
ALTER TABLE payments ADD COLUMN invoice_url VARCHAR(500);

-- Percentage (e.g. 18.00 for 18%), not an amount -- billing_prices.price stays GST-exclusive until
-- a real tax calculation exists; this column only records the rate that would apply, for whenever
-- that calculation is built.
ALTER TABLE billing_prices ADD COLUMN gst_rate NUMERIC(5, 2);
```

- [ ] **Step 3: Update `Payment.java`**

Add the four new nullable columns and their accessors, alongside the existing ones:

```java
    @Column(name = "base_amount", precision = 10, scale = 2)
    private BigDecimal baseAmount;

    @Column(name = "tax_amount", precision = 10, scale = 2)
    private BigDecimal taxAmount;

    @Column(name = "invoice_id", length = 50)
    private String invoiceId;

    @Column(name = "invoice_url", length = 500)
    private String invoiceUrl;
```

```java
    public BigDecimal getBaseAmount() { return baseAmount; }
    public void setBaseAmount(BigDecimal baseAmount) { this.baseAmount = baseAmount; }
    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }
    public String getInvoiceId() { return invoiceId; }
    public void setInvoiceId(String invoiceId) { this.invoiceId = invoiceId; }
    public String getInvoiceUrl() { return invoiceUrl; }
    public void setInvoiceUrl(String invoiceUrl) { this.invoiceUrl = invoiceUrl; }
```

- [ ] **Step 4: Update `BillingPrice.java`**

Add the new nullable column and its accessor, alongside the existing ones:

```java
    @Column(name = "gst_rate", precision = 5, scale = 2)
    private BigDecimal gstRate;
```

```java
    public BigDecimal getGstRate() { return gstRate; }
    public void setGstRate(BigDecimal gstRate) { this.gstRate = gstRate; }
```

- [ ] **Step 5: Write the failing repository test**

```java
package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Payment;
import com.finora.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentRepositoryIT extends AbstractIntegrationTest {

    @Autowired private PaymentRepository paymentRepository;
    @Autowired private UserRepository userRepository;

    private User createUser() {
        User user = new User();
        user.setEmail("payment-tax-invoice-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Payment Tax Invoice IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    @Test
    void savesAndReloadsTheOptionalTaxAndInvoiceFields() {
        User user = createUser();
        Payment payment = new Payment();
        payment.setUserId(user.getId());
        payment.setAmount(new BigDecimal("943.82"));
        payment.setCurrency("INR");
        payment.setProvider("RAZORPAY");
        payment.setStatus(Payment.STATUS_SUCCESS);
        payment.setBaseAmount(new BigDecimal("799.00"));
        payment.setTaxAmount(new BigDecimal("144.82"));
        payment.setInvoiceId("inv_test_123");
        payment.setInvoiceUrl("https://razorpay.com/invoices/inv_test_123");
        Payment saved = paymentRepository.save(payment);

        Payment reloaded = paymentRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getBaseAmount()).isEqualByComparingTo(new BigDecimal("799.00"));
        assertThat(reloaded.getTaxAmount()).isEqualByComparingTo(new BigDecimal("144.82"));
        assertThat(reloaded.getInvoiceId()).isEqualTo("inv_test_123");
        assertThat(reloaded.getInvoiceUrl()).isEqualTo("https://razorpay.com/invoices/inv_test_123");
    }

    @Test
    void leavesTheNewFieldsNullWhenNeverSet() {
        // Every existing writer (RazorpayWebhookDispatcher.handleCharged/handlePending, Plan 1) does
        // not set these -- confirms that keeps working exactly as it does today, with no NOT NULL
        // constraint newly required of them.
        User user = createUser();
        Payment payment = new Payment();
        payment.setUserId(user.getId());
        payment.setAmount(new BigDecimal("399.00"));
        payment.setCurrency("INR");
        payment.setProvider("RAZORPAY");
        payment.setStatus(Payment.STATUS_SUCCESS);
        Payment saved = paymentRepository.save(payment);

        Payment reloaded = paymentRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getBaseAmount()).isNull();
        assertThat(reloaded.getTaxAmount()).isNull();
        assertThat(reloaded.getInvoiceId()).isNull();
        assertThat(reloaded.getInvoiceUrl()).isNull();
    }
}
```

- [ ] **Step 6: Run the migration and the test**

```bash
cd backend && ./mvnw test -Dtest=PaymentRepositoryIT
```
Expected: PASS. Flyway applies `V155` as part of Testcontainers startup; if it fails with a
migration checksum/version error, re-check Step 1's version-freshness assumption first.

- [ ] **Step 7: Run the full backend suite once to confirm the new nullable columns don't break any
  existing writer**

```bash
cd backend && ./mvnw test
```
Expected: PASS — in particular, `RazorpayWebhookDispatcherIT`'s `handleCharged`/`handlePending`
tests (Plan 1) construct `Payment` without setting the four new fields; confirm they still pass
exactly as before (nullable columns, no new constraint).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/resources/db/migration/V155__billing_tax_and_invoice_fields.sql \
        backend/src/main/java/com/finora/entity/Payment.java \
        backend/src/main/java/com/finora/entity/BillingPrice.java \
        backend/src/test/java/com/finora/repository/PaymentRepositoryIT.java
git commit -m "feat(db): add nullable tax and invoice reference columns for future GST/invoicing"
```

---

## What this plan does not cover

Per the spec's own decomposition and the "Spec deviations" section above:

- Referral trigger (spec §6.7) — does not apply; the mechanism it describes moving doesn't exist in
  the current codebase (see "Spec deviations").
- Web pricing/billing-portal UI changes to show "Downgrading to Plus on Oct 5" or an upgrade-in-progress
  state, and the mobile equivalents — **Plan 3** (web) and **Plan 4** (mobile). This plan's backend
  work makes the data available (`plan_changes` rows, `subscription_orders` rows) but adds no new
  GET endpoint to read "pending change" state for a user's own subscription — the spec's own §7 API
  surface list for all of V1 does not list one either; Plan 3 should define whatever read shape its
  UI actually needs when it starts, rather than this plan guessing at it now.
- Proration, refunds, coupons, multi-currency, a Fynora-side grace-period clock — all explicitly out
  of scope for all of V1 per spec §9, not just this plan.
- The two items spec §3 flagged as needing live-sandbox verification before this plan's downgrade/
  upgrade code is fully trusted in production (which exact webhook fires the moment a scheduled
  change applies; whether a saved mandate can be reused across a second Subscription for the same
  customer) — this plan's code is written defensively enough to be correct either way (downgrade
  reconciles via the next `subscription.charged`, regardless of which other event might also fire;
  upgrade creates a genuinely new subscription and does not assume mandate reuse), but the live
  sandbox exercise itself is still blocked on the Razorpay account existing (spec §10).
