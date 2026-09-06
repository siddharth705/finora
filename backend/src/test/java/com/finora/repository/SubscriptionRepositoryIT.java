package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Plan;
import com.finora.entity.Subscription;
import com.finora.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Admin Portal, Subscription Management list -- a live bug found from a real screenshot: the
 * page was showing ADMIN-scope accounts (every account gets a free subscription on signup
 * regardless of account_scope) and DELETED users' stale subscription rows (a purged account's own
 * subscription should already be gone via AccountPurgeSweepService.purgeOne's hard-delete, but a
 * pre-existing data gap left some behind) as if they were live customer plans.
 */
class SubscriptionRepositoryIT extends AbstractIntegrationTest {

    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;

    private UUID saveUser(String accountScope, String status) {
        User user = new User();
        user.setEmail("scope-test-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Scope Test User");
        user.setAccountScope(accountScope);
        user.setStatus(status);
        return userRepository.save(user).getId();
    }

    private void saveSubscription(UUID userId, UUID planId) {
        Subscription sub = new Subscription();
        sub.setUserId(userId);
        sub.setPlanId(planId);
        sub.setStatus(Subscription.STATUS_ACTIVE);
        sub.setStartDate(LocalDate.now());
        subscriptionRepository.save(sub);
    }

    @Test
    void includesAnActiveUserScopeSubscription_excludesAdminScopeAndDeletedUsers() {
        UUID freePlanId = planRepository.findByCode("FREE").orElseThrow().getId();
        UUID customerId = saveUser(User.SCOPE_USER, User.STATUS_ACTIVE);
        UUID adminId = saveUser(User.SCOPE_ADMIN, User.STATUS_ACTIVE);
        UUID deletedCustomerId = saveUser(User.SCOPE_USER, User.STATUS_DELETED);
        saveSubscription(customerId, freePlanId);
        saveSubscription(adminId, freePlanId);
        saveSubscription(deletedCustomerId, freePlanId);

        Page<Subscription> page = subscriptionRepository.findForCustomerAccountsOrderByCreatedAtDesc(
                PageRequest.of(0, 100));

        List<UUID> userIds = page.getContent().stream().map(Subscription::getUserId).toList();
        assertThat(userIds).contains(customerId);
        assertThat(userIds).doesNotContain(adminId, deletedCustomerId);
    }
}
