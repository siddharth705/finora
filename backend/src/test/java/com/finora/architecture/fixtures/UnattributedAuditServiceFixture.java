package com.finora.architecture.fixtures;

import com.finora.service.AuditService;

import java.util.Map;
import java.util.UUID;

/**
 * Deliberately incomplete fixture -- NOT production code, and it lives in test sources.
 * It IS nonetheless component-scanned: target/test-classes sits under
 * com.finora, so @SpringBootTest registers a stereotype-annotated fixture as a real bean --
 * which is why these fixtures take no constructor dependencies. Giving one an un-declared
 * dependency fails context loading for every integration test in the suite.
 *
 * <p>Reproduces the exact shape that has now recurred eight times across two audit passes: a
 * service method written for self-service, later reached by an admin proxy controller, which
 * writes an audit entry recording only WHOSE data changed and not WHO changed it. In the audit
 * trail an admin acting on a user's behalf becomes indistinguishable from the user acting
 * themselves -- which is precisely the question an audit trail exists to answer.
 *
 * <p>Its only purpose is to prove {@link com.finora.architecture.AuditActorAttributionTest}
 * actually detects that shape. Without it, the "every admin-reachable audit write records who
 * acted" assertion passes just as happily when the detection logic is broken as when the codebase
 * is genuinely clean.
 *
 * <p>Do not "fix" this class by threading an actor through {@link #mutateWithoutActor}. Its
 * unattributed method is the test input.
 */
public class UnattributedAuditServiceFixture {

    private final AuditService auditService;

    public UnattributedAuditServiceFixture(AuditService auditService) {
        this.auditService = auditService;
    }

    /** The bug shape: an audited mutation with no actor parameter to record. */
    public void mutateWithoutActor(UUID userId) {
        auditService.record(userId, "FIXTURE_MUTATED", "Fixture", null, Map.of());
    }

    /** A correctly-attributed sibling, so the rule is shown to flag only the offender. */
    public void mutateWithActor(UUID userId, UUID actingAdminId) {
        auditService.record(userId, "FIXTURE_MUTATED", "Fixture", null,
                Map.of("actorId", actingAdminId.toString()));
    }
}
