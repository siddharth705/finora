package com.finora.service;

import com.finora.dto.BillingDtos.SubscriptionSummaryDto;
import com.finora.entity.Plan;
import com.finora.entity.PlanChange;
import com.finora.entity.Subscription;
import com.finora.entity.SubscriptionEvent;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.PlanChangeRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionEventRepository;
import com.finora.repository.SubscriptionRepository;
import com.finora.repository.UserRepository;
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

    public SubscriptionService(SubscriptionRepository subscriptionRepository,
                                SubscriptionEventRepository subscriptionEventRepository,
                                PlanChangeRepository planChangeRepository,
                                PlanRepository planRepository, UserRepository userRepository,
                                AuditService auditService) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionEventRepository = subscriptionEventRepository;
        this.planChangeRepository = planChangeRepository;
        this.planRepository = planRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
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

    @Transactional(readOnly = true)
    public List<SubscriptionSummaryDto> listAll() {
        List<Subscription> subscriptions = subscriptionRepository.findAll();
        Map<UUID, Plan> plansById = planRepository.findAll().stream()
                .collect(Collectors.toMap(Plan::getId, p -> p));
        Map<UUID, User> usersById = userRepository.findAllById(
                subscriptions.stream().map(Subscription::getUserId).distinct().toList()
        ).stream().collect(Collectors.toMap(User::getId, u -> u));

        return subscriptions.stream().map(s -> {
            Plan plan = plansById.get(s.getPlanId());
            User user = usersById.get(s.getUserId());
            return new SubscriptionSummaryDto(
                    s.getId(), s.getUserId(),
                    user != null ? user.getEmail() : null, user != null ? user.getFullName() : null,
                    plan != null ? plan.getCode() : null, plan != null ? plan.getName() : null,
                    s.getStatus(), s.getStartDate(), s.getEndDate(), s.getRenewalDate());
        }).toList();
    }

    /** Admin-only, manual (proposal §10: upgrade/downgrade timing and refund policy are still
     *  open product decisions -- this always takes effect immediately, the simplest behavior the
     *  schema supports, not a presumption about what the eventual self-service flow should do). */
    @Transactional
    public void changePlan(UUID adminId, UUID userId, String newPlanCode, String reason) {
        Subscription subscription = subscriptionRepository.findActiveOrTrial(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "This user has no active subscription."));
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

        auditService.record(adminId, "SUBSCRIPTION_PLAN_CHANGED", "Subscription", subscription.getId(),
                Map.of("userId", userId.toString(), "toPlanCode", newPlanCode, "reason", reason));
    }
}
