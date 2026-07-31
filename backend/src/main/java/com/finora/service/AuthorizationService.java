package com.finora.service;

import com.finora.dto.MeAccessDto;
import com.finora.entity.Permission;
import com.finora.entity.Role;
import com.finora.entity.User;
import com.finora.repository.RoleRepository;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Computes the full set of granted authorities for a user: the union of classic "ROLE_x"
 * authorities (still used by a couple of existing @PreAuthorize("hasRole(...)") checks, and by
 * Spring Security conventions generally) and the fine-grained permission names the engineering
 * directive asks new code to check instead (@PreAuthorize("hasAuthority('TRANSACTION_DELETE')")).
 *
 * Two sources feed this, deliberately overlapping during the migration off the legacy single
 * `role` string column (docs/engineering-directive-phase1.md, Priority 2):
 *
 *  1. User.role (legacy) -- resolved against the Role of the same name, if one exists, so every
 *     user created before this migration (or by any code path that still only sets `role`, e.g.
 *     AuthService.register) keeps exactly the access it always had, with zero re-seeding
 *     required.
 *  2. User.roles (new, via the user_roles table) -- explicit, possibly-multiple role
 *     assignments, for users who've been granted access through the real RBAC model (see
 *     RoleAdminController).
 *
 * A user with both ends up with the union of both, which is always a superset of what either one
 * alone would grant on its own -- this can only add authorities relative to the legacy behavior,
 * never silently remove one, so there is no scenario where wiring this in locks an existing user
 * out of something they could do before.
 */
@Service
public class AuthorizationService {

    private final RoleRepository roleRepository;

    public AuthorizationService(RoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    public Set<GrantedAuthority> effectiveAuthorities(User user) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        // Always present, regardless of whether a matching Role row exists yet -- this is exactly
        // today's pre-RBAC behavior (CurrentUserDetailsService used to grant only this), so it
        // stays the floor no user can end up below.
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole()));

        roleRepository.findByName(user.getRole()).ifPresent(role -> addRole(authorities, role));
        user.getRoles().forEach(role -> addRole(authorities, role));

        return authorities;
    }

    private void addRole(Set<GrantedAuthority> authorities, Role role) {
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.getName()));
        for (Permission permission : role.getPermissions()) {
            authorities.add(new SimpleGrantedAuthority(permission.getName()));
        }
    }

    /**
     * Same union this class already computes for effectiveAuthorities(), just returned as plain
     * role/permission names instead of Spring Security's "ROLE_x" GrantedAuthority strings --
     * backs GET /api/v1/users/me/access, which the admin portal (frontend-admin/) calls to decide
     * whether an account has any admin-relevant access before letting it into the admin shell.
     */
    public MeAccessDto meAccess(User user) {
        Set<String> roleNames = new LinkedHashSet<>();
        Set<String> permissionNames = new LinkedHashSet<>();

        // Legacy role string -- always present, mirrors the floor effectiveAuthorities() grants.
        roleNames.add(user.getRole());
        roleRepository.findByName(user.getRole()).ifPresent(role -> collect(role, roleNames, permissionNames));
        user.getRoles().forEach(role -> collect(role, roleNames, permissionNames));

        return new MeAccessDto(List.copyOf(roleNames), List.copyOf(permissionNames));
    }

    private void collect(Role role, Set<String> roleNames, Set<String> permissionNames) {
        roleNames.add(role.getName());
        for (Permission permission : role.getPermissions()) {
            permissionNames.add(permission.getName());
        }
    }
}
