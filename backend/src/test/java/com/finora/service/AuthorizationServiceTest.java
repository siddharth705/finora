package com.finora.service;

import com.finora.entity.Permission;
import com.finora.entity.Role;
import com.finora.entity.User;
import com.finora.repository.RoleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks in AuthorizationService's core promise (docs/engineering-directive-phase1.md,
 * Priority 2): wiring database-driven RBAC in is additive-only relative to the pre-existing
 * "ROLE_" + user.getRole() behavior. A user who only ever had the legacy string set keeps
 * exactly the access they had before, whether or not a matching Role row exists yet, and any
 * explicit user_roles assignment can only add authorities on top of that floor.
 *
 * <p><b>Account scope is now stated explicitly in every fixture.</b> It used to be left to
 * User's field default, which is USER — so the two tests that expected an account to receive
 * admin permissions were, without saying so, describing a CONSUMER-app account holding them.
 * That is the state Bug 18 is about, and it is the state RoleService.requireScopeCanHold exists
 * to prevent. The expectations below were not relaxed to accommodate the change; the fixtures
 * were made to say which portal each account belongs to, and the permission-bearing ones now say
 * ADMIN because that is the only way such an account can legitimately exist.
 */
class AuthorizationServiceTest {

    private Permission permission(String name) {
        Permission p = new Permission();
        p.setName(name);
        p.setDescription(name);
        return p;
    }

    private Role role(String name, Permission... permissions) {
        Role r = new Role();
        r.setName(name);
        r.setDescription(name);
        r.setPermissions(Set.of(permissions));
        return r;
    }

    private Set<String> names(Set<GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    @Test
    void legacyRoleWithNoMatchingRoleRow_stillGetsTheClassicAuthority() {
        RoleRepository roleRepository = mock(RoleRepository.class);
        when(roleRepository.findByName(eq("USER"))).thenReturn(Optional.empty());
        AuthorizationService service = new AuthorizationService(roleRepository);

        User user = new User();
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_USER);

        assertThat(names(service.effectiveAuthorities(user)))
                .containsExactlyInAnyOrder("ROLE_USER", "PORTAL_USER");
    }

    @Test
    void legacyRoleResolvesAgainstMatchingRole_andPicksUpItsPermissions() {
        RoleRepository roleRepository = mock(RoleRepository.class);
        Role adminRole = role("ADMIN", permission("AUDIT_VIEW"), permission("USER_VIEW"));
        when(roleRepository.findByName(eq("ADMIN"))).thenReturn(Optional.of(adminRole));
        AuthorizationService service = new AuthorizationService(roleRepository);

        User user = new User();
        user.setRole("ADMIN"); // legacy column only -- no explicit user_roles row
        user.setAccountScope(User.SCOPE_ADMIN); // an admin IS an admin-portal account (V52 backfill)

        assertThat(names(service.effectiveAuthorities(user)))
                .containsExactlyInAnyOrder("ROLE_ADMIN", "PORTAL_ADMIN", "AUDIT_VIEW", "USER_VIEW");
    }

    @Test
    void explicitUserRoles_unionWithLegacyRole_neverReplacesIt() {
        RoleRepository roleRepository = mock(RoleRepository.class);
        when(roleRepository.findByName(eq("ADMIN"))).thenReturn(Optional.empty());
        AuthorizationService service = new AuthorizationService(roleRepository);

        User user = new User();
        user.setRole("ADMIN"); // legacy floor
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setRoles(Set.of(role("REPORT_ANALYST", permission("REPORT_VIEW"), permission("REPORT_EXPORT"))));

        assertThat(names(service.effectiveAuthorities(user)))
                .containsExactlyInAnyOrder("ROLE_ADMIN", "PORTAL_ADMIN", "ROLE_REPORT_ANALYST",
                        "REPORT_VIEW", "REPORT_EXPORT");
    }

    // ---- Bug 18: scope is read at authorization time, not only at grant time ----

    /**
     * The finding itself. RoleService.requireScopeCanHold refuses to ATTACH a permission-bearing
     * role to a USER-scope account, and its own javadoc says that is "not a substitute for a scope
     * claim carried in the token and checked at authorization time". A guard on the granting path
     * is only as good as the completeness of the set of granting paths — a row written before the
     * guard existed, a future code path that forgets it, or a direct database edit all produce this
     * state, and until now every one of them produced a working admin.
     */
    @Test
    void aConsumerScopeAccountHoldingAnAdminRole_getsNoneOfItsPermissions() {
        RoleRepository roleRepository = mock(RoleRepository.class);
        Role adminRole = role("ADMIN", permission("AUDIT_VIEW"), permission("USER_VIEW"));
        when(roleRepository.findByName(eq("ADMIN"))).thenReturn(Optional.of(adminRole));
        AuthorizationService service = new AuthorizationService(roleRepository);

        User user = new User();
        user.setRole("ADMIN");
        user.setAccountScope(User.SCOPE_USER); // the state requireScopeCanHold exists to prevent

        Set<String> authorities = names(service.effectiveAuthorities(user));

        assertThat(authorities)
                .as("every @PreAuthorize in the application is hasAuthority('<PERMISSION>'), so "
                        + "withholding permissions withholds exactly the admin surface")
                .doesNotContain("AUDIT_VIEW", "USER_VIEW");
        // The role grant itself is still reported. Nothing authorizes on ROLE_*, and keeping it
        // makes the anomaly legible rather than making the role look like it vanished.
        assertThat(authorities).containsExactlyInAnyOrder("ROLE_ADMIN", "PORTAL_USER");
    }

    @Test
    void aConsumerScopeAccountHoldingAPermissionBearingUserRole_getsNoneOfItsPermissions() {
        RoleRepository roleRepository = mock(RoleRepository.class);
        when(roleRepository.findByName(eq("USER"))).thenReturn(Optional.empty());
        AuthorizationService service = new AuthorizationService(roleRepository);

        User user = new User();
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_USER);
        // Reached through user_roles rather than the legacy column -- the other granting path, and
        // the one V52's backfill had to account for separately.
        user.setRoles(Set.of(role("REPORT_ANALYST", permission("REPORT_VIEW"), permission("REPORT_EXPORT"))));

        assertThat(names(service.effectiveAuthorities(user)))
                .containsExactlyInAnyOrder("ROLE_USER", "PORTAL_USER", "ROLE_REPORT_ANALYST");
    }

    /**
     * /users/me/access is the admin portal's own gate. It has to withhold on the same rule, or the
     * portal admits an account and then 403s every section inside it — the same shape of confusing
     * dead end that 0205e8b fixed for unverified phone numbers.
     */
    @Test
    void meAccessAgreesWithWhatTheServerWillActuallyAllow() {
        RoleRepository roleRepository = mock(RoleRepository.class);
        Role adminRole = role("ADMIN", permission("AUDIT_VIEW"), permission("USER_VIEW"));
        when(roleRepository.findByName(eq("ADMIN"))).thenReturn(Optional.of(adminRole));
        AuthorizationService service = new AuthorizationService(roleRepository);

        User consumer = new User();
        consumer.setRole("ADMIN");
        consumer.setAccountScope(User.SCOPE_USER);
        assertThat(service.meAccess(consumer).permissions()).isEmpty();

        User admin = new User();
        admin.setRole("ADMIN");
        admin.setAccountScope(User.SCOPE_ADMIN);
        assertThat(service.meAccess(admin).permissions())
                .containsExactlyInAnyOrder("AUDIT_VIEW", "USER_VIEW");
    }
}
