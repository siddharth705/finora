package com.finora.service;

import com.finora.dto.BillingDtos.SubscriptionSummaryDto;
import com.finora.dto.PagedResponse;
import com.finora.entity.Plan;
import com.finora.entity.PlanChange;
import com.finora.entity.Subscription;
import com.finora.entity.SubscriptionEvent;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import com.finora.repository.PlanChangeRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionEventRepository;
import com.finora.repository.SubscriptionOrderRepository;
import com.finora.repository.SubscriptionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** D-28 PR4-A. Covers Free-plan auto-provisioning (every account-creation path) and the
 *  admin-only manual plan change -- the only way to reach Plus/Premium until a payment gateway
 *  exists. */
class SubscriptionServiceTest {

    private SubscriptionRepository subscriptionRepository;
    private SubscriptionEventRepository subscriptionEventRepository;
    private PlanChangeRepository planChangeRepository;
    private PlanRepository planRepository;
    private UserRepository userRepository;
    private AuditService auditService;
    private RazorpaySubscriptionGateway gateway;
    private SubscriptionOrderRepository subscriptionOrderRepository;
    private SubscriptionService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        subscriptionEventRepository = mock(SubscriptionEventRepository.class);
        planChangeRepository = mock(PlanChangeRepository.class);
        planRepository = mock(PlanRepository.class);
        userRepository = mock(UserRepository.class);
        auditService = mock(AuditService.class);
        gateway = mock(RazorpaySubscriptionGateway.class);
        subscriptionOrderRepository = mock(SubscriptionOrderRepository.class);
        service = new SubscriptionService(subscriptionRepository, subscriptionEventRepository,
                planChangeRepository, planRepository, userRepository, auditService, gateway,
                subscriptionOrderRepository);
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(inv -> {
            Subscription s = inv.getArgument(0);
            if (s.getId() == null) ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
            return s;
        });
    }

    private Plan planWith(String code, UUID id) {
        Plan p = new Plan();
        ReflectionTestUtils.setField(p, "id", id);
        p.setCode(code);
        p.setName(code);
        return p;
    }

    @Test
    void provisionFreeSubscription_createsAnActiveFreeSubscription_andALifecycleEvent() {
        UUID freePlanId = UUID.randomUUID();
        when(planRepository.findByCode("FREE")).thenReturn(Optional.of(planWith("FREE", freePlanId)));

        service.provisionFreeSubscription(userId);

        ArgumentCaptor<Subscription> subCaptor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(subCaptor.capture());
        assertThat(subCaptor.getValue().getUserId()).isEqualTo(userId);
        assertThat(subCaptor.getValue().getPlanId()).isEqualTo(freePlanId);
        assertThat(subCaptor.getValue().getStatus()).isEqualTo(Subscription.STATUS_ACTIVE);

        ArgumentCaptor<SubscriptionEvent> eventCaptor = ArgumentCaptor.forClass(SubscriptionEvent.class);
        verify(subscriptionEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo(SubscriptionEvent.SUBSCRIPTION_CREATED);
    }

    @Test
    void provisionFreeSubscription_throws_whenTheFreePlanSeedIsMissing() {
        when(planRepository.findByCode("FREE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.provisionFreeSubscription(userId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void changePlan_movesTheSubscription_andRecordsAPlanChangeAndEvent() {
        UUID freePlanId = UUID.randomUUID();
        UUID premiumPlanId = UUID.randomUUID();
        Subscription existing = new Subscription();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        existing.setUserId(userId);
        existing.setPlanId(freePlanId);
        existing.setStatus(Subscription.STATUS_ACTIVE);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(existing));
        when(planRepository.findByCode("PREMIUM")).thenReturn(Optional.of(planWith("PREMIUM", premiumPlanId)));

        service.changePlan(userId, "PREMIUM", "beta tester", adminId);

        assertThat(existing.getPlanId()).isEqualTo(premiumPlanId);
        verify(subscriptionRepository).save(existing);

        ArgumentCaptor<PlanChange> changeCaptor = ArgumentCaptor.forClass(PlanChange.class);
        verify(planChangeRepository).save(changeCaptor.capture());
        assertThat(changeCaptor.getValue().getFromPlanId()).isEqualTo(freePlanId);
        assertThat(changeCaptor.getValue().getToPlanId()).isEqualTo(premiumPlanId);
        assertThat(changeCaptor.getValue().getReason()).isEqualTo(PlanChange.REASON_ADMIN_OVERRIDE);

        verify(subscriptionEventRepository).save(any(SubscriptionEvent.class));
        // The audit write's subject stays userId (whose subscription changed), with the acting
        // admin recorded separately as "actorId" in metadata -- same convention as
        // AccountService.create(), enforced by AuditActorAttributionTest (FG-025).
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(userId), eq("SUBSCRIPTION_PLAN_CHANGED"), eq("Subscription"),
                eq(existing.getId()), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue()).containsEntry("actorId", adminId.toString());
    }

    @Test
    void changePlan_isANoOp_whenTheRequestedPlanIsAlreadyCurrent() {
        UUID planId = UUID.randomUUID();
        Subscription existing = new Subscription();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        existing.setUserId(userId);
        existing.setPlanId(planId);
        existing.setStatus(Subscription.STATUS_ACTIVE);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(existing));
        when(planRepository.findByCode("PLUS")).thenReturn(Optional.of(planWith("PLUS", planId)));

        service.changePlan(userId, "PLUS", "no-op", adminId);

        verify(subscriptionRepository, never()).save(any());
        verify(planChangeRepository, never()).save(any());
    }

    @Test
    void changePlan_throwsNotFound_whenTheUserHasNoActiveSubscription() {
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changePlan(userId, "PLUS", "x", adminId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no active subscription");
    }

    @Test
    void changePlan_throwsBadRequest_forAnUnknownPlanCode() {
        Subscription existing = new Subscription();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        existing.setUserId(userId);
        existing.setPlanId(UUID.randomUUID());
        existing.setStatus(Subscription.STATUS_ACTIVE);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(existing));
        when(planRepository.findByCode("GOLD")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changePlan(userId, "GOLD", "x", adminId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Unknown plan code");
    }

    @Test
    void listAll_joinsSubscriptionsWithTheirPlanAndUser() {
        UUID planId = UUID.randomUUID();
        Subscription sub = new Subscription();
        ReflectionTestUtils.setField(sub, "id", UUID.randomUUID());
        sub.setUserId(userId);
        sub.setPlanId(planId);
        sub.setStatus(Subscription.STATUS_ACTIVE);
        when(subscriptionRepository.findForCustomerAccountsOrderByCreatedAtDesc(PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(sub)));
        when(planRepository.findAll()).thenReturn(List.of(planWith("PLUS", planId)));
        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setEmail("jane@example.com");
        user.setFullName("Jane Doe");
        when(userRepository.findAllById(List.of(userId))).thenReturn(List.of(user));

        PagedResponse<SubscriptionSummaryDto> result = service.listAll(0, 20);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).userEmail()).isEqualTo("jane@example.com");
        assertThat(result.content().get(0).planCode()).isEqualTo("PLUS");
    }

    @Test
    void listAllIncludesThePaymentProviderForEachRow() {
        UUID planId = UUID.randomUUID();
        Subscription sub = new Subscription();
        ReflectionTestUtils.setField(sub, "id", UUID.randomUUID());
        sub.setUserId(userId);
        sub.setPlanId(planId);
        sub.setStatus(Subscription.STATUS_ACTIVE);
        sub.setPaymentProvider("RAZORPAY");
        when(subscriptionRepository.findForCustomerAccountsOrderByCreatedAtDesc(PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(sub)));
        when(planRepository.findAll()).thenReturn(List.of());
        when(userRepository.findAllById(any())).thenReturn(List.of());

        PagedResponse<SubscriptionSummaryDto> result = service.listAll(0, 20);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).paymentProvider()).isEqualTo("RAZORPAY");
    }

    /** PageBounds.safePage/safeSize clamp before the query -- a negative page or an oversized
     *  size must never reach PageRequest.of directly (it throws IllegalArgumentException with no
     *  handler, surfacing as an opaque 500), same reasoning as AdminUserService.list. */
    @Test
    void listAll_clampsAnOutOfRangePageAndSize() {
        when(subscriptionRepository.findForCustomerAccountsOrderByCreatedAtDesc(PageRequest.of(0, 100)))
                .thenReturn(new PageImpl<>(List.of()));
        when(planRepository.findAll()).thenReturn(List.of());
        when(userRepository.findAllById(List.of())).thenReturn(List.of());

        PagedResponse<SubscriptionSummaryDto> result = service.listAll(-5, 500);

        assertThat(result.content()).isEmpty();
        verify(subscriptionRepository).findForCustomerAccountsOrderByCreatedAtDesc(PageRequest.of(0, 100));
    }

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

    @Test
    void healthReportsCountsForEachSubscriptionStatusAndPendingOrders() {
        when(subscriptionRepository.countForCustomerAccountsByStatus(Subscription.STATUS_ACTIVE)).thenReturn(120L);
        when(subscriptionRepository.countForCustomerAccountsByStatus(Subscription.STATUS_PAST_DUE)).thenReturn(5L);
        when(subscriptionRepository.countForCustomerAccountsByStatus(Subscription.STATUS_PAYMENT_FAILED)).thenReturn(3L);
        when(subscriptionRepository.countForCustomerAccountsByStatus(Subscription.STATUS_CANCELLED)).thenReturn(8L);
        when(subscriptionOrderRepository.countByStatus(com.finora.entity.SubscriptionOrder.STATUS_PENDING))
                .thenReturn(2L);

        var health = service.health();

        assertThat(health.activeCount()).isEqualTo(120L);
        assertThat(health.pastDueCount()).isEqualTo(5L);
        assertThat(health.paymentFailedCount()).isEqualTo(3L);
        assertThat(health.cancelledCount()).isEqualTo(8L);
        assertThat(health.pendingOrderCount()).isEqualTo(2L);
    }

    @Test
    void changePlanRefusesAdminOverrideWhileARevenueCatSubscriptionIsActive() {
        Subscription active = new Subscription();
        active.setUserId(userId);
        active.setPlanId(UUID.randomUUID());
        active.setPaymentProvider("REVENUECAT");
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.changePlan(userId, "PREMIUM", "support override", adminId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("active Apple/Google subscription");
    }
}
