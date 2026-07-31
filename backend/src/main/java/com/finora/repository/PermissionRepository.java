package com.finora.repository;

import com.finora.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {
    boolean existsByName(String name);

    // Guards RoleService.deletePermission -- a permission still granted to at least one role
    // can't be deleted out from under it (every user holding that role would silently lose the
    // capability, with no record of why).
    @Query("SELECT COUNT(r) FROM Role r JOIN r.permissions p WHERE p.id = :permissionId")
    long countRolesWithPermission(@Param("permissionId") UUID permissionId);
}
