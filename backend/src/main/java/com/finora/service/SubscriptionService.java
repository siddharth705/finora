package com.finora.service;

import com.finora.dto.BillingDtos.SubscriptionHealthDto;
import com.finora.dto.BillingDtos.SubscriptionSummaryDto;
import com.finora.dto.PagedResponse;
import com.finora.entity.Plan;
import com.finora.entity.PlanChange;
import com.finora.entity.Subscription;
import com.finora.entity.SubscriptionEvent;
import com.finora.entity.SubscriptionOrder;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import com.finora.repository.PlanChangeRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionEventRepository;
import com.finora.repository.SubscriptionOrderRepository;
import com.finora.repository.SubscriptionRepository;
import com.finora.repository.UserRepository;
import com.finora.util.PageBounds;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * D-28 PR4-A. Subscription lifecycle -- provisioning the Free plan every new account gets, and
 * the admin-only manual plan change that is, for now, the only way anyone reaches Plus/Premium
 * (no payment gateway exists yet -- proposal §10).
 */
@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionEventRepository subscriptionEventRepository;
    private final PlanChangeRepository planChangeRepository;
    private final PlanRepository planRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final RazorpaySubscriptionGateway gateway;
    private final SubscriptionOrderRepository subscriptionOrderRepository;

    public SubscriptionService(SubscriptionRepository subscriptionRepository,
                                SubscriptionEventRepository subscriptionEventRepository,
                                PlanChangeRepository planChangeRepository,
                                PlanRepository planRepository, UserRepository userRepository,
                                AuditService auditService, RazorpaySubscriptionGateway gateway,
                                SubscriptionOrderRepository subscriptionOrderRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionEventRepository = subscriptionEventRepository;
        this.planChangeRepository = planChangeRepository;
        this.planRepository = planRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.gateway = gateway;
        this.subscriptionOrderRepository = subscriptionOrderRepository;
    }

    /** Called from every account-creation path (AuthService.createUserRecord and
     *  createOAuthUserRecord) -- shares the same "every new user gets this" discipline as
     *  seedDefaultCategories, which both paths already call right before this. */
    @Transactional
    public void provisionFreeSubscription(UUID userId) {
        Plan freePlan = planRepository.findByCode("FREE")
                .orElseThrow(() -> new IllegalStateException("FREE plan missing -- V99 seed data not applied"));
        Subscription subscription = new Subscription();
        subscription.setUserId(userId);
        subscription.setPlanId(freePlan.getId());
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setStartDate(LocalDate.now());
        Subscription saved = subscriptionRepository.save(subscription);

        SubscriptionEvent event = new SubscriptionEvent();
        event.setSubscriptionId(saved.getId());
        event.setEventType(SubscriptionEvent.SUBSCRIPTION_CREATED);
        event.setMetadata(Map.of("planCode", "FREE"));
        subscriptionEventRepository.save(event);
    }

    /** Admin Portal, Subscription Management. Was an unconditional {@code findAll()} across the
     *  whole table -- this grows roughly 1:1 with the user base (every signup provisions one, see
     *  {@link #provisionFreeSubscription}), so that scaled directly with total users rather than
     *  with anything an admin actually needed to see at once. The plan/user joins below still run
     *  per page, not per row: {@code planRepository.findAll()} stays unconditional because plans
     *  are a small fixed catalog (FREE/PLUS/PREMIUM), and the user batch-fetch is already scoped
     *  to only the userIds on this one page. */
    @Transactional(readOnly = true)
    public PagedResponse<SubscriptionSummaryDto> listAll(int page, int size) {
        Page<Subscription> subscriptions = subscriptionRepository.findAllByOrderByCreatedAtDesc(
                PageRequest.of(PageBounds.safePage(page), PageBounds.safeSize(size)));
        Map<UUID, Plan> plansById = planRepository.findAll().stream()
                .collect(Collectors.toMap(Plan::getId, p -> p));
        Map<UUID, User> usersById = userRepository.findAllById(
                subscriptions.getContent().stream().map(Subscription::getUserId).distinct().toList()
        ).stream().collect(Collectors.toMap(User::getId, u -> u));

        return PagedResponse.of(subscriptions.map(s -> {
            Plan plan = plansById.get(s.getPlanId());
            User user = usersById.get(s.getUserId());
            return new SubscriptionSummaryDto(
                    s.getId(), s.getUserId(),
                    user != null ? user.getEmail() : null, user != null ? user.getFullName() : null,
                    plan != null ? plan.getCode() : null, plan != null ? plan.getName() : null,
                    s.getStatus(), s.getPaymentProvider(), s.getStartDate(), s.getEndDate(), s.getRenewalDate());
        }));
    }

    /** Admin-only, manual (proposal §10: upgrade/downgrade timing and refund policy are still
     *  open product decisions -- this always takes effect immediately, the simplest behavior the
     *  schema supports, not a presumption about what the eventual self-service flow should do).
     *
     *  <p>actingAdminId: same "actorId" convention as AccountService.create() -- the audit write's
     *  subject stays userId (whose subscription changed), and actingAdminId is recorded separately
     *  in the metadata as "actorId", so the trail can tell an admin's action apart from the user's
     *  own (FG-025, AuditActorAttributionTest). */
    @Transactional
    public void changePlan(UUID userId, String newPlanCode, String reason, UUID actingAdminId) {
        Subscription subscription = subscriptionRepository.findActiveOrTrial(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "This user has no active subscription."));
        if ("RAZORPAY".equals(subscription.getPaymentProvider())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This user has an active paid subscription. Cancel it first before granting a complimentary plan.");
        }
        Plan newPlan = planRepository.findByCode(newPlanCode)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Unknown plan code: " + newPlanCode));

        UUID fromPlanId = subscription.getPlanId();
        if (fromPlanId.equals(newPlan.getId())) {
            return;
        }
        subscription.setPlanId(newPlan.getId());
        subscriptionRepository.save(subscription);

        PlanChange change = new PlanChange();
        change.setSubscriptionId(subscription.getId());
        change.setFromPlanId(fromPlanId);
        change.setToPlanId(newPlan.getId());
        change.setEffectiveAt(Instant.now());
        change.setReason(PlanChange.REASON_ADMIN_OVERRIDE);
        planChangeRepository.save(change);

        SubscriptionEvent event = new SubscriptionEvent();
        event.setSubscriptionId(subscription.getId());
        event.setEventType(SubscriptionEvent.PLAN_CHANGED);
        event.setMetadata(Map.of("fromPlanId", fromPlanId.toString(), "toPlanId", newPlan.getId().toString(), "reason", reason));
        subscriptionEventRepository.save(event);

        auditService.record(userId, "SUBSCRIPTION_PLAN_CHANGED", "Subscription", subscription.getId(),
                Map.of("toPlanCode", newPlanCode, "reason", reason, "actorId", actingAdminId.toString()));
    }

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

    /** Admin Portal, Subscription Health (Plan 3 review) -- five platform-wide counts, one plain
     *  COUNT query each. See {@link SubscriptionHealthDto}'s own doc comment for why these five
     *  and not more. */
    @Transactional(readOnly = true)
    public SubscriptionHealthDto health() {
        return new SubscriptionHealthDto(
                subscriptionRepository.countByStatus(Subscription.STATUS_ACTIVE),
                subscriptionRepository.countByStatus(Subscription.STATUS_PAST_DUE),
                subscriptionRepository.countByStatus(Subscription.STATUS_PAYMENT_FAILED),
                subscriptionRepository.countByStatus(Subscription.STATUS_CANCELLED),
                subscriptionOrderRepository.countByStatus(SubscriptionOrder.STATUS_PENDING));
    }
}
