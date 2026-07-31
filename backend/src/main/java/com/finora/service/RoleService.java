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

    /** Grants a user an additional role via the new user_roles table -- additive only, never
     *  touches the legacy User.role column, so it can't reduce anyone's access. */
    @Transactional
    public RoleDto assignRole(UUID userId, String roleName) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        Role role = roleRepository.findByName(roleName)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No such role: " + roleName));

        user.getRoles().add(role);
        userRepository.save(user);
        auditService.record(userId, "ROLE_ASSIGNED", "User", userId, Map.of("role", roleName));
        return toDto(role);
    }

    /** Only removes the explicit user_roles grant -- if this user's legacy `role` string still
     *  names the same role, AuthorizationService will keep granting it through that path.
     *  Callers that mean to fully demote a user need to change User.role too (a separate, more
     *  consequential action than "revoke one of possibly several roles", deliberately not folded
     *  into this method). */
    @Transactional
    public void revokeRole(UUID userId, String roleName) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        Role role = roleRepository.findByName(roleName)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No such role: " + roleName));

        user.getRoles().remove(role);
        userRepository.save(user);
        auditService.record(userId, "ROLE_REVOKED", "User", userId, Map.of("role", roleName));
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
