package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * countByEmailNot/countByStatusAndEmailNot back the admin dashboards' bootstrap-account exclusion
 * (AdminOperationalDashboardService, AdminStatsService) -- see
 * {@code BootstrapService.BOOTSTRAP_IDENTIFIER}'s own doc comment for why email, not role, is the
 * stable identifier for that account.
 *
 * <p><b>Bug fix.</b> The queries these replace, {@code countByRoleNot}/{@code
 * countByStatusAndRoleNot}, only excluded the bootstrap account while {@code role} still held
 * "BOOTSTRAP_ADMIN" -- true only during the setup wizard. {@code SetupService.completeSetup()}
 * calls {@code RoleService.revokeRole(...)}, which resets the legacy {@code User.role} column to
 * {@code DEFAULT_ROLE} ("USER") the moment the revoked role matches it, and also sets {@code
 * status="SUSPENDED"}. So the role-based filter silently stopped excluding the account the instant
 * setup finished -- which is every real deployment almost all of the time -- and the status-based
 * pairing let it leak straight into "suspended users" instead. These tests build that exact
 * post-setup shape and prove the email-based filter still excludes it, which is precisely the case
 * a role-based filter gets wrong.
 */
class UserRepositoryIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;

    private User save(String email, String role, String status) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("User Repository IT Test User");
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setRole(role);
        user.setStatus(status);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    @Test
    @Transactional
    void countByEmailNot_stillExcludesTheAccount_afterItsRoleHasBeenResetBySetupCompletion() {
        String excludedEmail = "user-repository-it-bootstrap-" + UUID.randomUUID() + "@example.com";
        long baseline = userRepository.countByEmailNot(excludedEmail);

        // The exact post-setup shape SetupService.completeSetup() leaves the real bootstrap
        // account in: role reset to the legacy default, not "BOOTSTRAP_ADMIN" anymore.
        save(excludedEmail, User.DEFAULT_ROLE, User.STATUS_ACTIVE);
        assertThat(userRepository.countByEmailNot(excludedEmail)).isEqualTo(baseline);

        // A genuinely different account, same role, must still be counted -- proves the exclusion
        // is keyed on email, not accidentally always excluding everyone with DEFAULT_ROLE.
        save("user-repository-it-real-" + UUID.randomUUID() + "@example.com", User.DEFAULT_ROLE, User.STATUS_ACTIVE);
        assertThat(userRepository.countByEmailNot(excludedEmail)).isEqualTo(baseline + 1);
    }

    @Test
    @Transactional
    void countByStatusAndEmailNot_stillExcludesTheAccount_whenItsStatusMatchesAfterSetupCompletion() {
        String excludedEmail = "user-repository-it-bootstrap-" + UUID.randomUUID() + "@example.com";
        long baseline = userRepository.countByStatusAndEmailNot(User.STATUS_SUSPENDED, excludedEmail);

        // SetupService.completeSetup() also sets status=SUSPENDED on the real bootstrap account --
        // matching this status filter is exactly what let it leak into suspendedUsers before.
        save(excludedEmail, User.DEFAULT_ROLE, User.STATUS_SUSPENDED);
        assertThat(userRepository.countByStatusAndEmailNot(User.STATUS_SUSPENDED, excludedEmail)).isEqualTo(baseline);

        save("user-repository-it-real-" + UUID.randomUUID() + "@example.com", User.DEFAULT_ROLE, User.STATUS_SUSPENDED);
        assertThat(userRepository.countByStatusAndEmailNot(User.STATUS_SUSPENDED, excludedEmail)).isEqualTo(baseline + 1);
    }
}
