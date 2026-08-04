package com.finora.controller;

import com.finora.dto.AdminDtos.CreatePermissionRequest;
import com.finora.dto.AdminDtos.CreateRoleRequest;
import com.finora.dto.AdminDtos.UpdatePermissionRequest;
import com.finora.dto.AdminDtos.UpdateRoleRequest;
import com.finora.dto.ApiResponse;
import com.finora.dto.PermissionDto;
import com.finora.dto.RoleDto;
import com.finora.security.CurrentUser;
import com.finora.service.RoleService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Database-driven RBAC management (docs/engineering-directive-phase1.md, Priority 2). Gated by
 * granular permissions -- ROLE_MANAGE / PERMISSION_MANAGE -- rather than hasRole('ADMIN'), which
 * is exactly the "permissions control access instead of hardcoded role checks" shift the
 * directive calls for. Seeded role/permission data lives in V16__rbac_roles_permissions.sql;
 * only SUPER_ADMIN holds these two permissions at seed time (see that migration), so in practice
 * this controller is reserved for the top of the role hierarchy, not general admins.
 *
 * Deliberately thin -- all repository access, transactions, and DTO mapping live in RoleService,
 * matching this codebase's own convention elsewhere (GoalController/GoalService,
 * BudgetController/BudgetService, ...) and the directive's own Priority 4 standard.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class RoleAdminController {

    private final RoleService roleService;
    private final CurrentUser currentUser;

    public RoleAdminController(RoleService roleService, CurrentUser currentUser) {
        this.roleService = roleService;
        this.currentUser = currentUser;
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    public ApiResponse<List<RoleDto>> listRoles() {
        return ApiResponse.ok(roleService.listRoles());
    }

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    public ApiResponse<RoleDto> createRole(@Valid @RequestBody CreateRoleRequest request) {
        return ApiResponse.ok(roleService.createRole(currentUser.id(), request), "Role created");
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    public ApiResponse<RoleDto> updateRole(@PathVariable UUID id, @Valid @RequestBody UpdateRoleRequest request) {
        return ApiResponse.ok(roleService.updateRole(currentUser.id(), id, request), "Role updated");
    }

    @DeleteMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    public ApiResponse<Void> deleteRole(@PathVariable UUID id) {
        roleService.deleteRole(currentUser.id(), id);
        return ApiResponse.ok(null, "Role deleted");
    }

    @PostMapping("/roles/{roleId}/permissions/{permissionId}")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    public ApiResponse<RoleDto> grantPermissionToRole(@PathVariable UUID roleId, @PathVariable UUID permissionId) {
        return ApiResponse.ok(roleService.addPermissionToRole(currentUser.id(), roleId, permissionId), "Permission granted to role");
    }

    @DeleteMapping("/roles/{roleId}/permissions/{permissionId}")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    public ApiResponse<RoleDto> revokePermissionFromRole(@PathVariable UUID roleId, @PathVariable UUID permissionId) {
        return ApiResponse.ok(roleService.removePermissionFromRole(currentUser.id(), roleId, permissionId), "Permission revoked from role");
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('PERMISSION_MANAGE')")
    public ApiResponse<List<PermissionDto>> listPermissions() {
        return ApiResponse.ok(roleService.listPermissions());
    }

    @PostMapping("/permissions")
    @PreAuthorize("hasAuthority('PERMISSION_MANAGE')")
    public ApiResponse<PermissionDto> createPermission(@Valid @RequestBody CreatePermissionRequest request) {
        return ApiResponse.ok(roleService.createPermission(currentUser.id(), request), "Permission created");
    }

    @PutMapping("/permissions/{id}")
    @PreAuthorize("hasAuthority('PERMISSION_MANAGE')")
    public ApiResponse<PermissionDto> updatePermission(@PathVariable UUID id, @Valid @RequestBody UpdatePermissionRequest request) {
        return ApiResponse.ok(roleService.updatePermission(currentUser.id(), id, request), "Permission updated");
    }

    @DeleteMapping("/permissions/{id}")
    @PreAuthorize("hasAuthority('PERMISSION_MANAGE')")
    public ApiResponse<Void> deletePermission(@PathVariable UUID id) {
        roleService.deletePermission(currentUser.id(), id);
        return ApiResponse.ok(null, "Permission deleted");
    }

    @PostMapping("/users/{userId}/roles/{roleName}")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    public ApiResponse<RoleDto> assignRole(@PathVariable UUID userId, @PathVariable String roleName) {
        return ApiResponse.ok(roleService.assignRole(currentUser.id(), userId, roleName), "Role assigned");
    }

    @DeleteMapping("/users/{userId}/roles/{roleName}")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    public ApiResponse<Void> revokeRole(@PathVariable UUID userId, @PathVariable String roleName) {
        roleService.revokeRole(currentUser.id(), userId, roleName);
        return ApiResponse.ok(null, "Role revoked");
    }
}
