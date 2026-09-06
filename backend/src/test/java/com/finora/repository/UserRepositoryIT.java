package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Role;
import com.finora.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    /** Assigns a real seeded role (ADMIN/SUPER_ADMIN, both carrying real permissions via V16/
     *  V31/V135/V144) to a fresh admin-scope user -- exercises the real permission graph rather
     *  than a hand-built fixture, so a change to which roles carry a permission is caught here. */
    private User saveAdminWithRole(RoleRepository roleRepository, String roleName) {
        User user = new User();
        user.setEmail("user-repository-it-perm-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("User Repository IT Permission Test User");
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setStatus(User.STATUS_ACTIVE);
        user.setPhoneVerified(true);
        Role role = roleRepository.findByName(roleName).orElseThrow();
        user.getRoles().add(role);
        return userRepository.save(user);
    }

    @Test
    @Transactional
    void findByPermissionNameAndAccountScope_returnsAdminsWhoseRoleGrantsIt(
            @Autowired RoleRepository roleRepository) {
        User grantedByAdmin = saveAdminWithRole(roleRepository, "ADMIN");
        User grantedBySuperAdmin = saveAdminWithRole(roleRepository, "SUPER_ADMIN");

        List<User> recipients =
                userRepository.findByPermissionNameAndAccountScope("IMPORT_TRIAGE_MANAGE", User.SCOPE_ADMIN);

        assertThat(recipients).extracting(User::getId)
                .contains(grantedByAdmin.getId(), grantedBySuperAdmin.getId());
    }

    @Test
    @Transactional
    void findByPermissionNameAndAccountScope_excludesAUserWithNoRoleGrantingIt(
            @Autowired RoleRepository roleRepository) {
        // A real admin account, but with no role assigned at all -- the RBAC-empty case, distinct
        // from "assigned a role that doesn't happen to carry this permission" (there is no such
        // role in the seeded set today, so the empty-roles case is the one worth pinning).
        User unrelatedAdmin = new User();
        unrelatedAdmin.setEmail("user-repository-it-perm-none-" + UUID.randomUUID() + "@example.com");
        unrelatedAdmin.setPasswordHash("irrelevant-for-this-test");
        unrelatedAdmin.setFullName("User Repository IT No-Permission Test User");
        unrelatedAdmin.setAccountScope(User.SCOPE_ADMIN);
        unrelatedAdmin.setStatus(User.STATUS_ACTIVE);
        unrelatedAdmin.setPhoneVerified(true);
        userRepository.save(unrelatedAdmin);

        List<User> recipients =
                userRepository.findByPermissionNameAndAccountScope("IMPORT_TRIAGE_MANAGE", User.SCOPE_ADMIN);

        assertThat(recipients).extracting(User::getId).doesNotContain(unrelatedAdmin.getId());
    }

    @Test
    @Transactional
    void findByPermissionNameAndAccountScope_isScopedToTheGivenAccountScope(
            @Autowired RoleRepository roleRepository) {
        // A USER-scope account holding the same role name is not an admin-portal account and must
        // never be resolved as an alert recipient, however its roles happen to be configured.
        User userScopeAccount = new User();
        userScopeAccount.setEmail("user-repository-it-perm-userscope-" + UUID.randomUUID() + "@example.com");
        userScopeAccount.setPasswordHash("irrelevant-for-this-test");
        userScopeAccount.setFullName("User Repository IT User-Scope Test User");
        userScopeAccount.setAccountScope(User.SCOPE_USER);
        userScopeAccount.setStatus(User.STATUS_ACTIVE);
        userScopeAccount.setPhoneVerified(true);
        Role role = roleRepository.findByName("ADMIN").orElseThrow();
        userScopeAccount.getRoles().add(role);
        userRepository.save(userScopeAccount);

        List<User> recipients =
                userRepository.findByPermissionNameAndAccountScope("IMPORT_TRIAGE_MANAGE", User.SCOPE_ADMIN);

        assertThat(recipients).extracting(User::getId).doesNotContain(userScopeAccount.getId());
    }
}
