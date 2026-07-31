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

        assertThat(names(service.effectiveAuthorities(user))).containsExactly("ROLE_USER");
    }

    @Test
    void legacyRoleResolvesAgainstMatchingRole_andPicksUpItsPermissions() {
        RoleRepository roleRepository = mock(RoleRepository.class);
        Role adminRole = role("ADMIN", permission("AUDIT_VIEW"), permission("USER_VIEW"));
        when(roleRepository.findByName(eq("ADMIN"))).thenReturn(Optional.of(adminRole));
        AuthorizationService service = new AuthorizationService(roleRepository);

        User user = new User();
        user.setRole("ADMIN"); // legacy column only -- no explicit user_roles row

        assertThat(names(service.effectiveAuthorities(user)))
                .containsExactlyInAnyOrder("ROLE_ADMIN", "AUDIT_VIEW", "USER_VIEW");
    }

    @Test
    void explicitUserRoles_unionWithLegacyRole_neverReplacesIt() {
        RoleRepository roleRepository = mock(RoleRepository.class);
        when(roleRepository.findByName(eq("USER"))).thenReturn(Optional.empty());
        AuthorizationService service = new AuthorizationService(roleRepository);

        User user = new User();
        user.setRole("USER"); // legacy floor
        user.setRoles(Set.of(role("REPORT_ANALYST", permission("REPORT_VIEW"), permission("REPORT_EXPORT"))));

        assertThat(names(service.effectiveAuthorities(user)))
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_REPORT_ANALYST", "REPORT_VIEW", "REPORT_EXPORT");
    }
}
