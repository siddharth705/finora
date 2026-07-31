package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A single named capability (e.g. "TRANSACTION_DELETE") that a Role can grant. Permissions are
 * checked directly in code (@PreAuthorize("hasAuthority('X')")) rather than role names, per the
 * engineering directive -- "is this user allowed to do this specific thing," not "is this user
 * an admin." See V16__rbac_roles_permissions.sql for the seeded set.
 */
@Entity
@Table(name = "permissions")
public class Permission {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    @Column(nullable = false)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Instant getCreatedAt() { return createdAt; }
}
