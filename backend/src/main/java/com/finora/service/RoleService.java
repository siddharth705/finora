package com.finora.service;

import com.finora.dto.AdminDtos.CreatePermissionRequest;
import com.finora.dto.AdminDtos.CreateRoleRequest;
import com.finora.dto.AdminDtos.UpdatePermissionRequest;
import com.finora.dto.AdminDtos.UpdateRoleRequest;
import com.finora.dto.PermissionDto;
import com.finora.dto.RoleDto;
import com.finora.entity.Permission;
import com.finora.entity.Role;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.PermissionRepository;
import com.finora.repository.RoleRepository;
import com.finora.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Business logic for database-driven RBAC management (docs/engineering-directive-phase1.md,
 * Priority 2). Pulled out of RoleAdminController rather than left inline -- this codebase's own
 * convention (see GoalService/GoalController, BudgetService/BudgetController, etc.) is thin
 * controllers with repository access, transactions, and DTO mapping living in the service layer,
 * and the directive itself calls for exactly that (Priority 4: "Controllers must remain thin.
 * Business logic belongs exclusively in the Service layer.").
 */
@Service
public class RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public RoleService(RoleRepository roleRepository, PermissionRepository permissionRepository,
                        UserRepository userRepository, AuditService auditService) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<RoleDto> listRoles() {
        return roleRepository.findAll().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<PermissionDto> listPermissions() {
        return permissionRepository.findAll().stream()
                .map(p -> new PermissionDto(p.getId(), p.getName(), p.getDescription()))
                .toList();
    }

    /**
     * Grants a user an additional role via the new user_roles table -- additive only, never
     * touches the legacy User.role column, so it can't reduce anyone's access.
     *
     * Bug fix: this used to record the audit entry with no actingAdminId at all, attributing
     * ROLE_ASSIGNED to the target user itself -- indistinguishable from the user granting the role
     * to themselves. Every other mutation in this class (createRole/updateRole/deleteRole/
     * createPermission/...) already threads actingAdminId through to the audit trail; this is the
     * single most consequential action in the whole admin surface (it can grant ADMIN/SUPER_ADMIN)
     * and was the one place that didn't. Same bug class, same fix, as the actorId threading already
     * done for RelationshipService/MerchantService.
     */
    @Transactional
    public RoleDto assignRole(UUID actingAdminId, UUID userId, String roleName) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        Role role = roleRepository.findByName(roleName)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No such role: " + roleName));

        // actingAdminId used to reach this method for the audit entry alone and was never compared
        // to the target, so any holder of ROLE_MANAGE could grant themselves SUPER_ADMIN in one
        // call. AdminUserService.suspend already blocks self-targeting on the far less
        // consequential action ("worth blocking outright rather than trusting every caller of this
        // UI to never misclick on their own row"); escalation deserves at least the same.
        //
        // Grant only. Self-REVOKE stays allowed: it de-escalates, and SetupService.completeSetup
        // depends on it to strip BOOTSTRAP_ADMIN from the bootstrap account itself.
        if (userId.equals(actingAdminId)) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "You cannot grant a role to your own account. Ask another administrator to do it.");
        }
        requireScopeCanHold(user, role);

        user.getRoles().add(role);
        userRepository.save(user);
        auditService.record(userId, "ROLE_ASSIGNED", "User", userId,
                Map.of("role", roleName, "actorId", actingAdminId.toString()));
        return toDto(role);
    }

    /** Revokes a role completely: removes the explicit user_roles grant AND, when the legacy
     *  {@code User.role} string names the same role, resets that column to the default so the
     *  revocation actually takes effect.
     *
     *  <p><b>Bug fix.</b> This used to remove only the join row, and said so -- "callers that mean
     *  to fully demote a user need to change User.role too." The problem is that
     *  {@link com.finora.service.AuthorizationService#effectiveAuthorities} resolves a Role BY the
     *  legacy string and grants its entire permission set, so for any role that is also somebody's
     *  legacy value, revoking returned 200, wrote a ROLE_REVOKED audit entry, deleted the join row
     *  -- and changed nothing about what that user could do. The legacy column is written in
     *  exactly two places, and both are the highest-privilege accounts in the system:
     *  {@code BootstrapService} (BOOTSTRAP_ADMIN) and {@code SetupService} (SUPER_ADMIN). So the
     *  two roles this silently failed for were the only two that really matter.
     *
     *  <p>Concretely, it broke {@code SetupService}'s own security-critical revocation, whose
     *  comment states that revoking BOOTSTRAP_ADMIN means "SYSTEM_INITIALIZE is gone immediately,
     *  not just until this token's 15-minute expiry." It was not gone at all. That was
     *  unexploitable only by luck -- a different control ({@code tryMarkSetupCompleted()}) blocks
     *  the single endpoint SYSTEM_INITIALIZE gates -- which is defense in depth working as
     *  designed, not a reason to leave the first layer broken.
     *
     *  <p>Resetting to {@code DEFAULT_ROLE} rather than null because {@code User.role} is
     *  non-nullable with exactly that default, and because AuthorizationService always grants
     *  {@code "ROLE_" + user.getRole()} -- leaving it blank would produce a meaningless
     *  {@code "ROLE_"} authority. "USER" is the floor every account starts at.
     *
     *  <p>Bug fix: same missing-actingAdminId gap as {@link #assignRole} -- see its own doc comment. */
    @Transactional
    public void revokeRole(UUID actingAdminId, UUID userId, String roleName) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        Role role = roleRepository.findByName(roleName)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No such role: " + roleName));

        // The platform must never be left with no account able to administer it. Revoking the
        // last SUPER_ADMIN was an irreversible lockout: BootstrapService only ever mints a
        // bootstrap account when setup_completed is false, and by this point it is true, so there
        // is no automated way back -- recovery means direct database access. deleteRole() and
        // deletePermission() already refuse to remove something still in use; this is the same
        // class of guard on the one operation that did not have it.
        if (User.SUPER_ADMIN_ROLE.equals(roleName)
                && userRepository.countActiveUsersWithRole(User.SUPER_ADMIN_ROLE, User.STATUS_ACTIVE) <= 1) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This is the last active Super Admin account. Grant SUPER_ADMIN to another "
                            + "account before revoking it from this one.");
        }

        user.getRoles().remove(role);
        boolean clearedLegacyRole = roleName.equals(user.getRole());
        if (clearedLegacyRole) user.setRole(User.DEFAULT_ROLE);
        userRepository.save(user);
        // The audit entry records whether the legacy column was also reset, so a reader of the
        // trail can tell a full demotion from the join-row-only revocation this used to be.
        auditService.record(userId, "ROLE_REVOKED", "User", userId,
                Map.of("role", roleName, "actorId", actingAdminId.toString(),
                        "legacyRoleCleared", Boolean.toString(clearedLegacyRole)));
    }

    /**
     * Refuses to attach an admin-surface role to a consumer-app account.
     *
     * <p>V52 introduced {@code account_scope} so one person can hold a USER-portal and an
     * ADMIN-portal account under the same email, and states that scope "is what login
     * disambiguates on". When this guard was written that was all it did: {@code JwtService} minted
     * no scope claim and {@code AuthorizationService} computed authorities from roles alone, so an
     * access token issued to a USER-scope account was indistinguishable from an ADMIN-scope one at
     * every {@code @PreAuthorize} check, and the separation between the two portals rested entirely
     * on nobody ever granting an admin role to a USER-scope account -- a convention of the V16 seed,
     * not an invariant anything enforced.
     *
     * <p><b>That is no longer the case, and this method is no longer the only thing holding the
     * line.</b> {@code AuthorizationService.effectiveAuthorities}/{@code meAccess} now withhold
     * permission authorities from any account that is not an admin-portal account, and the token
     * carries a {@code scope} claim that {@code JwtAuthFilter} cross-checks. So a USER-scope account
     * that acquires an admin role by some route this method does not sit on -- a row predating it, a
     * future code path that forgets it, a direct database edit -- can no longer exercise the
     * permissions. This guard is kept because failing the grant with a clear message is a much
     * better outcome than silently creating a row whose permissions do nothing, and because a
     * defence at the point of writing and a defence at the point of reading fail in different ways.
     *
     * <p>"Admin-surface" is decided by evidence rather than by a hardcoded list: a role qualifies
     * if it carries any permission at all. That is exact here -- every permission the schema
     * seeds gates an {@code /api/v1/admin/**} or setup endpoint, and no user-facing endpoint in
     * the application carries a {@code @PreAuthorize} of any kind. A role with no permissions
     * (USER, and any custom role not yet granted one) stays freely assignable to either scope.
     */
    private static void requireScopeCanHold(User user, Role role) {
        if (role.getPermissions().isEmpty()) return;
        if (User.SCOPE_ADMIN.equalsIgnoreCase(user.getAccountScope())) return;
        throw new ApiException(HttpStatus.FORBIDDEN,
                "\"" + role.getName() + "\" grants admin permissions and can only be assigned to an "
                        + "admin-portal account. This account is a consumer (USER-scope) account.");
    }

    /** Whether the acting admin holds {@code role}, by either the explicit user_roles grant or
     *  the legacy User.role column -- both are live grants, as revokeRole's own doc comment
     *  records. A missing actor is treated as holding nothing rather than as an error: this backs
     *  a self-escalation guard, and the caller is already authenticated by the time it runs. */
    private boolean actorHoldsRole(UUID actingAdminId, Role role) {
        return userRepository.findById(actingAdminId)
                .map(actor -> role.getName().equals(actor.getRole())
                        || actor.getRoles().stream().anyMatch(r -> r.getName().equals(role.getName())))
                .orElse(false);
    }

    // --- Role & Permission CRUD (ROLE_MANAGE / PERMISSION_MANAGE) -- see RoleAdminController ---

    @Transactional
    public RoleDto createRole(UUID actingAdminId, CreateRoleRequest req) {
        if (roleRepository.existsByName(req.name())) {
            throw new ApiException(HttpStatus.CONFLICT, "A role named \"" + req.name() + "\" already exists.");
        }
        Role role = new Role();
        role.setName(req.name());
        role.setDescription(req.description());
        Role saved = roleRepository.save(role);
        auditService.record(actingAdminId, "ROLE_CREATED", "Role", saved.getId(), Map.of("name", saved.getName()));
        return toDto(saved);
    }

    @Transactional
    public RoleDto updateRole(UUID actingAdminId, UUID roleId, UpdateRoleRequest req) {
        Role role = requireRole(roleId);
        role.setDescription(req.description());
        Role saved = roleRepository.save(role);
        auditService.record(actingAdminId, "ROLE_UPDATED", "Role", saved.getId());
        return toDto(saved);
    }

    /** Blocks deleting a role still held by at least one user -- either through the new
     *  user_roles table or the legacy User.role string (see RoleRepository's two count queries).
     *  Deleting an unheld role is safe: role_permissions rows cascade (ON DELETE CASCADE, V16),
     *  no user loses anything. */
    @Transactional
    public void deleteRole(UUID actingAdminId, UUID roleId) {
        Role role = requireRole(roleId);
        long explicitHolders = roleRepository.countUsersWithExplicitRole(roleId);
        long legacyHolders = roleRepository.countUsersWithLegacyRole(role.getName());
        if (explicitHolders + legacyHolders > 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    (explicitHolders + legacyHolders) + " user(s) currently hold this role -- revoke it from them first.");
        }
        roleRepository.delete(role);
        auditService.record(actingAdminId, "ROLE_DELETED", "Role", roleId, Map.of("name", role.getName()));
    }

    @Transactional
    public PermissionDto createPermission(UUID actingAdminId, CreatePermissionRequest req) {
        if (permissionRepository.existsByName(req.name())) {
            throw new ApiException(HttpStatus.CONFLICT, "A permission named \"" + req.name() + "\" already exists.");
        }
        Permission permission = new Permission();
        permission.setName(req.name());
        permission.setDescription(req.description());
        Permission saved = permissionRepository.save(permission);
        auditService.record(actingAdminId, "PERMISSION_CREATED", "Permission", saved.getId(), Map.of("name", saved.getName()));
        return new PermissionDto(saved.getId(), saved.getName(), saved.getDescription());
    }

    @Transactional
    public PermissionDto updatePermission(UUID actingAdminId, UUID permissionId, UpdatePermissionRequest req) {
        Permission permission = requirePermission(permissionId);
        permission.setDescription(req.description());
        Permission saved = permissionRepository.save(permission);
        auditService.record(actingAdminId, "PERMISSION_UPDATED", "Permission", saved.getId());
        return new PermissionDto(saved.getId(), saved.getName(), saved.getDescription());
    }

    /** Blocks deleting a permission still granted to at least one role -- @PreAuthorize checks
     *  scattered across every controller reference permission names as string literals
     *  (hasAuthority('X')), so deleting one still in use wouldn't just orphan a role/permission
     *  row, it would silently disable a real endpoint's access control for everyone who held it. */
    @Transactional
    public void deletePermission(UUID actingAdminId, UUID permissionId) {
        Permission permission = requirePermission(permissionId);
        long rolesUsingIt = permissionRepository.countRolesWithPermission(permissionId);
        if (rolesUsingIt > 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    rolesUsingIt + " role(s) currently grant this permission -- remove it from them first.");
        }
        permissionRepository.delete(permission);
        auditService.record(actingAdminId, "PERMISSION_DELETED", "Permission", permissionId, Map.of("name", permission.getName()));
    }

    /** Attaches a permission to a role -- what actually makes a newly created role (which starts
     *  with zero permissions) grant anything. Idempotent: adding a permission a role already has
     *  is a no-op, not an error. */
    @Transactional
    public RoleDto addPermissionToRole(UUID actingAdminId, UUID roleId, UUID permissionId) {
        Role role = requireRole(roleId);
        Permission permission = requirePermission(permissionId);
        // Same escalation as assignRole by a different route: rather than granting yourself a
        // stronger role, attach a stronger permission to a role you already hold. actingAdminId
        // was audit-only here too.
        if (actorHoldsRole(actingAdminId, role)) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "You cannot add a permission to a role your own account holds. Ask another administrator to do it.");
        }
        if (role.getPermissions().add(permission)) {
            roleRepository.save(role);
            auditService.record(actingAdminId, "ROLE_PERMISSION_GRANTED", "Role", roleId,
                    Map.of("permission", permission.getName()));
        }
        return toDto(role);
    }

    @Transactional
    public RoleDto removePermissionFromRole(UUID actingAdminId, UUID roleId, UUID permissionId) {
        Role role = requireRole(roleId);
        Permission permission = requirePermission(permissionId);
        if (role.getPermissions().remove(permission)) {
            roleRepository.save(role);
            auditService.record(actingAdminId, "ROLE_PERMISSION_REVOKED", "Role", roleId,
                    Map.of("permission", permission.getName()));
        }
        return toDto(role);
    }

    private Role requireRole(UUID roleId) {
        return roleRepository.findById(roleId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Role not found"));
    }

    private Permission requirePermission(UUID permissionId) {
        return permissionRepository.findById(permissionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Permission not found"));
    }

    private RoleDto toDto(Role role) {
        List<PermissionDto> permissions = role.getPermissions().stream()
                .map(p -> new PermissionDto(p.getId(), p.getName(), p.getDescription()))
                .toList();
        return new RoleDto(role.getId(), role.getName(), role.getDescription(), permissions);
    }
}
