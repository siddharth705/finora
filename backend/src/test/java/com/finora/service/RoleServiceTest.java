package com.finora.service;

import com.finora.entity.Role;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.PermissionRepository;
import com.finora.repository.RoleRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers RoleService.assignRole/revokeRole -- the single most consequential mutation in the admin
 * surface (it can grant/revoke ADMIN/SUPER_ADMIN). Bug fix: both used to record their audit entry
 * with no actingAdminId at all, attributing ROLE_ASSIGNED/ROLE_REVOKED to the target user
 * themselves -- indistinguishable from a user granting a role to themselves. These tests lock in
 * that the acting admin's id now travels all the way to the audit metadata, same "actorId"
 * convention RelationshipServiceTest/MerchantServiceTest already assert for their own services.
 */
class RoleServiceTest {

    private RoleRepository roleRepository;
    private PermissionRepository permissionRepository;
    private UserRepository userRepository;
    private AuditService auditService;
    private RoleService roleService;

    private final UUID actingAdminId = UUID.randomUUID();
    private final UUID targetUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        roleRepository = mock(RoleRepository.class);
        permissionRepository = mock(PermissionRepository.class);
        userRepository = mock(UserRepository.class);
        auditService = mock(AuditService.class);
        roleService = new RoleService(roleRepository, permissionRepository, userRepository, auditService);
    }

    private User userWith(UUID id) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setRole("USER");
        return user;
    }

    private Role roleWith(String name) {
        Role role = new Role();
        ReflectionTestUtils.setField(role, "id", UUID.randomUUID());
        role.setName(name);
        role.setDescription("desc");
        return role;
    }

    @Test
    void assignRole_recordsActingAdminIdInAuditMetadata_notTheTargetUser() {
        User user = userWith(targetUserId);
        Role role = roleWith("ADMIN");
        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(user));
        when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(role));

        roleService.assignRole(actingAdminId, targetUserId, "ADMIN");

        assertThat(user.getRoles()).contains(role);
        verify(auditService).record(eq(targetUserId), eq("ROLE_ASSIGNED"), eq("User"), eq(targetUserId),
                argThat(metadata -> actingAdminId.toString().equals(metadata.get("actorId"))
                        && "ADMIN".equals(metadata.get("role"))));
    }

    @Test
    void revokeRole_recordsActingAdminIdInAuditMetadata_notTheTargetUser() {
        User user = userWith(targetUserId);
        Role role = roleWith("ADMIN");
        user.getRoles().add(role);
        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(user));
        when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(role));

        roleService.revokeRole(actingAdminId, targetUserId, "ADMIN");

        assertThat(user.getRoles()).doesNotContain(role);
        verify(auditService).record(eq(targetUserId), eq("ROLE_REVOKED"), eq("User"), eq(targetUserId),
                argThat(metadata -> actingAdminId.toString().equals(metadata.get("actorId"))
                        && "ADMIN".equals(metadata.get("role"))));
    }

    @Test
    void assignRole_throwsNotFound_whenUserDoesNotExist() {
        when(userRepository.findById(targetUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roleService.assignRole(actingAdminId, targetUserId, "ADMIN"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void assignRole_throwsNotFound_whenRoleDoesNotExist() {
        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(userWith(targetUserId)));
        when(roleRepository.findByName("NOSUCH")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roleService.assignRole(actingAdminId, targetUserId, "NOSUCH"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No such role");
    }
}
