package com.finora.architecture.fixtures;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Deliberately flawed fixture -- NOT production code, and never registered with Spring.
 *
 * <p>The admin proxy half of the bug shape: this is what makes
 * {@link UnattributedAuditServiceFixture#mutateWithoutActor} reachable by an admin acting on
 * someone else's data, which is the only reason its missing actor matters. A service method with
 * no admin caller is legitimately single-actor; it is the admin path that turns the omission into
 * an unanswerable "who did this?".
 *
 * <p>Both handlers carry {@code @PreAuthorize} on purpose, so this fixture exercises only the
 * attribution rule and does not also trip
 * {@link com.finora.architecture.AdminEndpointAuthorizationTest}.
 */
@RestController
@RequestMapping("/api/v1/admin/audit-fixture")
@PreAuthorize("hasAuthority('USER_MANAGE')")
public class UnattributedAdminAuditControllerFixture {

    /**
     * Constructed directly rather than injected, and this is not a style preference.
     *
     * <p>{@code target/test-classes} sits under {@code com.finora}, so {@code @SpringBootTest}
     * component scanning DOES reach a {@code @RestController} in test sources -- Spring registers
     * this class as a real bean. A constructor dependency here therefore makes Spring try to
     * autowire a {@link UnattributedAuditServiceFixture} bean that nothing declares, and the
     * resulting {@code NoSuchBeanDefinitionException} fails context loading for every integration
     * test in the suite, not just this package. The sibling fixtures survive only because they
     * happen to take no constructor arguments.
     *
     * <p>The field is never dereferenced at runtime: nothing routes to these handlers, and the
     * rule that reads this class only ever analyses its bytecode. The {@code null} AuditService
     * inside is likewise only ever a static call target.
     */
    private final UnattributedAuditServiceFixture service = new UnattributedAuditServiceFixture(null);

    /** Reaches the unattributed mutation -- the offending path the rule must flag. */
    @DeleteMapping
    public void adminMutatesWithoutRecordingWho(UUID userId) {
        service.mutateWithoutActor(userId);
    }

    /** Reaches the correctly-attributed mutation -- must NOT be flagged. */
    @PostMapping
    public void adminMutatesAndRecordsWho(UUID userId, UUID actingAdminId) {
        service.mutateWithActor(userId, actingAdminId);
    }
}
