package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A named group of Permissions (SUPER_ADMIN / ADMIN / USER at seed time -- see
 * V16__rbac_roles_permissions.sql). Assigned to users via the user_roles join table
 * (User.roles), in addition to the legacy User.role string -- see AuthorizationService for how
 * the two are reconciled into one effective authority set.
 *
 * Permissions are fetched eagerly: a Role's permission set is small (low double digits at most)
 * and gets read on every authenticated request via AuthorizationService, so the simplicity of
 * always having it loaded outweighs the cost of a lazy round trip almost every caller would
 * immediately trigger anyway.
 */
@Entity
@Table(name = "roles")
public class Role {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    @Column(nullable = false)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "role_permissions",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id")
    )
    private Set<Permission> permissions = new HashSet<>();

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Instant getCreatedAt() { return createdAt; }
    public Set<Permission> getPermissions() { return permissions; }
    public void setPermissions(Set<Permission> permissions) { this.permissions = permissions; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Role other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        // Constant, not id.hashCode(): id is null until persisted, and a JPA entity's hashCode
        // must stay stable across that transition -- otherwise adding a transient Role to a
        // HashSet (e.g. User.roles) before save() puts it in the wrong bucket, and it becomes
        // unfindable by equals() after the id is assigned. See User.roles / Role usage in
        // RoleAdminController.assignRole for exactly that transient-then-persisted path.
        return 31;
    }
}
