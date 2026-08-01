package com.finora.security.fixtures;

/**
 * Deliberately reproduces the pre-{@link com.finora.security.OwnershipGuard} bug shape: a
 * hand-rolled ownership check that builds its own "does not belong to you" message instead of
 * delegating. Not a Spring bean, not production code -- exists only so
 * {@code OwnershipGuardUsageTest} can prove its detection logic actually fires on the pattern it
 * claims to catch. Do not "fix" this by calling OwnershipGuard; that would defeat its purpose.
 */
public class HandRolledOwnershipCheckFixture {

    public void checkOwnership(java.util.UUID ownerId, java.util.UUID userId) {
        if (!ownerId.equals(userId)) {
            throw new RuntimeException("This widget does not belong to you");
        }
    }
}
