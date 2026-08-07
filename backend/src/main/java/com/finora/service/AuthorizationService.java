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
 *
 * <h2>Account scope, and where it enters authorization</h2>
 * V52 introduced {@code account_scope} and its own comment stated the model as "login
 * disambiguates on it; authorization does not". That left the separation between the consumer app
 * and the admin portal resting on one thing: {@code RoleService.requireScopeCanHold} refusing to
 * ATTACH a permission-bearing role to a USER-scope account. That method's javadoc says plainly what
 * it is not -- "it closes the door that leads to the room", and "is not a substitute for a scope
 * claim carried in the token and checked at authorization time, which is the real fix".
 *
 * <p>This is that fix. Scope is now read where authorization happens rather than only where a grant
 * is made, so a USER-scope account that holds admin permissions <em>by any means</em> -- a row
 * predating the guard, a future code path that does not call it, a direct database edit -- exercises
 * none of them. A guard at the granting path can only ever be as good as the completeness of the
 * set of granting paths, which is not a property anything checks.
 *
 * <p><b>Permissions are the whole admin surface</b>, which is what makes this exact rather than a
 * heuristic: every {@code @PreAuthorize} in the application is {@code hasAuthority('<PERMISSION>')},
 * every seeded permission gates an {@code /api/v1/admin/**} or setup endpoint, and no user-facing
 * endpoint carries a {@code @PreAuthorize} of any kind. So withholding permission authorities from
 * a non-admin-portal account removes exactly the admin surface and nothing else.
 *
 * <p>{@code ROLE_*} authorities are deliberately still granted in full. Nothing in the application
 * authorizes on them, so they cost nothing, and keeping them preserves this class's additive-only
 * promise on the axis where it matters -- as well as making the state legible: {@code /users/me/access}
 * reports the role the account genuinely holds alongside the empty permission set its scope allows,
 * which reads as the anomaly it is instead of as the role having silently vanished.
 */
@Service
public class AuthorizationService {

    /**
     * Prefix for the authority naming which portal an account belongs to: {@code PORTAL_ADMIN} or
     * {@code PORTAL_USER}.
     *
     * <p>Deliberately not {@code SCOPE_}, which Spring Security reserves by convention for OAuth2
     * scopes ({@code JwtGrantedAuthoritiesConverter} emits exactly that prefix). Colliding with it
     * would make any future resource-server configuration silently ambiguous about whether
     * {@code SCOPE_ADMIN} came from a token's OAuth scope or from this method.
     */
    public static final String PORTAL_AUTHORITY_PREFIX = "PORTAL_";

    private final RoleRepository roleRepository;

    public AuthorizationService(RoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    /** The authority naming the portal an account of this scope belongs to. */
    public static String portalAuthority(String accountScope) {
        return PORTAL_AUTHORITY_PREFIX
                + (User.SCOPE_ADMIN.equalsIgnoreCase(accountScope) ? User.SCOPE_ADMIN : User.SCOPE_USER);
    }

    private static boolean isAdminPortalAccount(User user) {
        return User.SCOPE_ADMIN.equalsIgnoreCase(user.getAccountScope());
    }

    public Set<GrantedAuthority> effectiveAuthorities(User user) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        // Always present, regardless of whether a matching Role row exists yet -- this is exactly
        // today's pre-RBAC behavior (CurrentUserDetailsService used to grant only this), so it
        // stays the floor no user can end up below.
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole()));

        // The scope, now present in the authority set every authorization decision already reads.
        // This is what "read at authorization time" means concretely: it is available to any
        // @PreAuthorize without a second query, and JwtAuthFilter compares the access token's own
        // scope claim against it so the two can never silently diverge.
        authorities.add(new SimpleGrantedAuthority(portalAuthority(user.getAccountScope())));

        boolean adminPortal = isAdminPortalAccount(user);
        roleRepository.findByName(user.getRole()).ifPresent(role -> addRole(authorities, role, adminPortal));
        user.getRoles().forEach(role -> addRole(authorities, role, adminPortal));

        return authorities;
    }

    private void addRole(Set<GrantedAuthority> authorities, Role role, boolean adminPortal) {
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.getName()));
        if (!adminPortal) {
            // A consumer-app account holds no admin surface, whatever its role rows say.
            return;
        }
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
        boolean adminPortal = isAdminPortalAccount(user);
        roleRepository.findByName(user.getRole())
                .ifPresent(role -> collect(role, roleNames, permissionNames, adminPortal));
        user.getRoles().forEach(role -> collect(role, roleNames, permissionNames, adminPortal));

        return new MeAccessDto(List.copyOf(roleNames), List.copyOf(permissionNames));
    }

    /**
     * Withholds permissions on the same rule {@link #effectiveAuthorities} applies, and has to.
     * This response is what the admin portal's own gate reads to decide whether to render the admin
     * shell; if it advertised permissions the server would then refuse, the portal would let an
     * account in and 403 every section inside it -- which is precisely the confusing state
     * {@code 0205e8b} fixed for unverified phone numbers, arrived at from a different direction.
     */
    private void collect(Role role, Set<String> roleNames, Set<String> permissionNames, boolean adminPortal) {
        roleNames.add(role.getName());
        if (!adminPortal) {
            return;
        }
        for (Permission permission : role.getPermissions()) {
            permissionNames.add(permission.getName());
        }
    }
}
