package com.finora.repository;

import com.finora.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<Role, UUID> {
    Optional<Role> findByName(String name);
    boolean existsByName(String name);

    // Guards RoleService.deleteRole -- a role currently held by at least one user (via either
    // the explicit user_roles table or the legacy User.role string) can't be deleted out from
    // under them. Traverses User.roles (the ManyToMany) in JPQL rather than a native query on
    // user_roles directly, matching how AuthorizationService already walks this same relationship.
    @Query("SELECT COUNT(u) FROM User u JOIN u.roles r WHERE r.id = :roleId")
    long countUsersWithExplicitRole(@Param("roleId") UUID roleId);

    // The legacy path: a role can also be "held" by a user purely through User.role matching
    // this role's name (AuthorizationService.effectiveAuthorities resolves that string against
    // the Role table too) -- deleting a role a batch of users are still implicitly relying on
    // that way would silently take away access from every one of them.
    @Query("SELECT COUNT(u) FROM User u WHERE u.role = :roleName")
    long countUsersWithLegacyRole(@Param("roleName") String roleName);
}
