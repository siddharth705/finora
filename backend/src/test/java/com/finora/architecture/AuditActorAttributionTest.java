package com.finora.architecture;

import com.finora.architecture.registry.GuardianSelfTest;
import com.finora.architecture.registry.GuardianRule;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Parameter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reusable security rule, not a point patch.
 *
 * <p>The bug this exists to prevent has already happened eight times across two audit passes:
 * {@code RoleService} assign/revoke, {@code RuleService} create/update/delete, {@code
 * AccountService} create/update/delete, {@code TransactionService.delete}, {@code
 * confirmMerchantCategory}, and two more fixed in an earlier pass. Every one is the same shape --
 * a service written for self-service, where {@code userId} is both the subject and the actor, is
 * later reached by an admin proxy controller so support staff can act on a user's behalf. The
 * audit write still records only {@code userId}, so the trail says a user changed their own data
 * when in fact an admin did. That is exactly the question an audit trail exists to answer, and on
 * a financial platform "who moved this money" is not a question that tolerates a wrong answer.
 *
 * <p>Fixing those eight does not stop the ninth. This test does: it fails the build the moment a
 * method that writes an audit entry becomes reachable from an admin controller without carrying a
 * parameter naming who acted.
 *
 * <h2>Why reachability, not just "has an actor parameter"</h2>
 *
 * <p>Most audited methods legitimately have one actor. {@code AuthService.login} records
 * USER_LOGIN; there is no second party, and demanding an actor parameter there would be wrong
 * rather than merely noisy -- 27 of the 64 audit writers in this codebase are in that category.
 * What makes an omission a bug is specifically the existence of an admin path. So the rule walks
 * calls transitively from admin controller handlers and only judges what it can actually reach.
 * That is what keeps the allow-list at two entries instead of twenty-seven.
 *
 * <p>"Admin controller" is not re-invented here: it is the same {@code @RestController} +
 * {@code @RequestMapping} under {@code /api/v1/admin} definition {@link
 * AdminEndpointAuthorizationTest} already uses. Two rules disagreeing about what "admin" means is
 * its own bug.
 *
 * <h2>Known limitation</h2>
 *
 * <p>This checks that an actor is <em>available</em> to the method, not that it is written into
 * the audit metadata. A method could take {@code actingAdminId} and ignore it. Verifying the
 * metadata key would mean inspecting the arguments of a {@code Map.of(...)} at the call site,
 * which bytecode analysis does not give cheaply. The weaker check still catches the real bug
 * shape, because the historical failure was always the parameter being absent entirely -- nobody
 * threaded an actor through and then dropped it.
 */
class AuditActorAttributionTest {

    private static final String ADMIN_PATH_PREFIX = "/api/v1/admin";

    /**
     * Parameter names that count as naming who acted. Two spellings exist in the codebase:
     * {@code actingAdminId} is the convention, {@code adminUserId} is {@code
     * FeatureFlagService.setEnabled}'s older name for the same thing. Both are accepted rather
     * than renaming the outlier, because a guard commit should not carry a refactor -- but a
     * third spelling should be normalised, not added here.
     */
    private static final Set<String> ACTOR_PARAMETER_NAMES = Set.of("actingAdminId", "adminUserId");

    /**
     * Audited actions with genuinely no second actor, even though an admin request can reach them.
     *
     * <p>Both are system passes, not operations: they run automatically after every import and
     * every transaction create/edit/delete, so an admin reaches them only as a downstream side
     * effect of an action that is itself separately audited WITH its actor. The entry describes a
     * machine pass over a user's data ("processed 412 transactions, matched 3 transfers"), and
     * attributing that to whichever admin happened to trigger the upstream write would make the
     * trail less accurate, not more.
     *
     * <p>Keep this list short. Every addition is a claim that an audited action has exactly one
     * possible actor forever; if that stops being true the entry becomes a silent hole.
     */
    private static final Set<String> SINGLE_ACTOR_SYSTEM_PASSES = Set.of(
            // RECONCILIATION_RUN. The audit write moved here in BH-041 when the passes were
            // extracted behind two entry points -- reconcileForUser (unbounded) and
            // reconcileForImport (windowed to an import's own date range). Both are still the same
            // single-actor system pass: they run as a consequence of the owning user's own write,
            // never on an admin's behalf, so there is no second actor to attribute.
            "ReconciliationService.reconcile",          // RECONCILIATION_RUN
            "RecurringService.detectForUser"            // RECURRING_DETECTION_RUN
    );

    private JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finora");
    }

    /**
     * The rule itself, factored out so the exact same logic can be pointed at the deliberately
     * broken fixture below -- see {@link #theRuleDetectsAnUnattributedAdminAuditWrite()}.
     */
    private List<String> findAdminReachableAuditWritesWithoutActor(JavaClasses classes) {
        Deque<JavaCodeUnit> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        List<String> offenders = new ArrayList<>();

        for (JavaClass clazz : classes) {
            if (isAdminController(clazz)) queue.addAll(clazz.getMethods());
        }

        while (!queue.isEmpty()) {
            JavaCodeUnit unit = queue.poll();
            if (!visited.add(unit.getFullName())) continue;
            if (!unit.getOwner().getPackageName().startsWith("com.finora")) continue;

            if (unit instanceof JavaMethod method && writesAuditEntry(method)
                    // AuditService.record's own no-metadata overload delegates to the other; it is
                    // the sink itself, not a caller that forgot an actor.
                    && !method.getOwner().getSimpleName().equals("AuditService")) {
                String id = method.getOwner().getSimpleName() + "." + method.getName();
                if (!hasActorParameter(method) && !SINGLE_ACTOR_SYSTEM_PASSES.contains(id)) {
                    offenders.add(id);
                }
            }

            unit.getCallsFromSelf().stream()
                    .map(call -> call.getTarget().resolveMember().orElse(null))
                    .filter(Objects::nonNull)
                    .filter(target -> target.getOwner().getPackageName().startsWith("com.finora"))
                    .forEach(queue::add);
        }
        return offenders.stream().distinct().sorted().toList();
    }

    private boolean writesAuditEntry(JavaCodeUnit unit) {
        return unit.getCallsFromSelf().stream()
                .anyMatch(call -> call.getTargetOwner().getFullName().endsWith("AuditService")
                        && call.getName().equals("record"));
    }

    /** Parameter names survive compilation because spring-boot-starter-parent enables
     *  {@code -parameters}; {@code reflect()} is how ArchUnit exposes them. */
    private boolean hasActorParameter(JavaMethod method) {
        try {
            return Arrays.stream(method.reflect().getParameters())
                    .map(Parameter::getName)
                    .anyMatch(ACTOR_PARAMETER_NAMES::contains);
        } catch (Throwable unreflectable) {
            return false;
        }
    }

    private boolean isAdminController(JavaClass candidate) {
        if (!candidate.isAnnotatedWith(RestController.class)) return false;
        if (!candidate.isAnnotatedWith(RequestMapping.class)) return false;

        RequestMapping mapping = candidate.getAnnotationOfType(RequestMapping.class);
        List<String> paths = new ArrayList<>(Arrays.asList(mapping.value()));
        paths.addAll(Arrays.asList(mapping.path()));
        return paths.stream().anyMatch(path -> path.startsWith(ADMIN_PATH_PREFIX));
    }

    @GuardianRule(
            id = "FG-025",
            category = GuardianRule.Category.SECURITY,
            intent = "Every admin-reachable audit write records the acting actor.",
            source = "Incident: unattributed admin audit write",
            introduced = "2026-08-05",
            owner = "architecture",
            verification = GuardianRule.Verification.SELF_TEST)
    @Test
    void everyAdminReachableAuditWriteRecordsWhoActed() {
        assertThat(findAdminReachableAuditWritesWithoutActor(productionClasses()))
                .as("""
                        An audited mutation reachable from an admin controller that cannot record
                        WHO performed it. The audit entry names only whose data changed, so an
                        admin acting on a user's behalf is indistinguishable from the user acting
                        themselves -- the exact question the audit trail exists to answer. Thread
                        an actor through (parameter named actingAdminId) and write it into the
                        metadata as "actorId", following AccountService.create(). If the action
                        genuinely has one possible actor forever, add it to
                        SINGLE_ACTOR_SYSTEM_PASSES with the reason.""")
                .isEmpty();
    }

    /**
     * Guards the guard, part one: proves the rule genuinely detects the bug shape it exists to
     * catch, by running the identical logic against a fixture that deliberately reproduces it. A
     * security rule that silently stopped detecting anything would otherwise look exactly like a
     * clean codebase.
     */
    @GuardianSelfTest(rule = "FG-025")
    @Test
    void theRuleDetectsAnUnattributedAdminAuditWrite() {
        JavaClasses fixtures = new ClassFileImporter().importPackages("com.finora.architecture.fixtures");

        assertThat(findAdminReachableAuditWritesWithoutActor(fixtures))
                .as("the rule must flag the unattributed fixture mutation, and must NOT flag its "
                        + "correctly-attributed sibling")
                .containsExactly("UnattributedAuditServiceFixture.mutateWithoutActor");
    }

    /**
     * Guards the guard, part two: if the reachability walk silently stopped matching anything (a
     * package move, an ArchUnit upgrade that changes call resolution, {@code -parameters} being
     * dropped so every method looks actor-less), the production assertion would keep passing while
     * checking nothing at all. Asserting a realistic floor means that failure mode surfaces as a
     * red test rather than false confidence.
     */
    @GuardianSelfTest(rule = "FG-025")
    @Test
    void theRuleActuallyReachesTheAuditWritersItClaimsToCheck() {
        JavaClasses classes = productionClasses();
        Deque<JavaCodeUnit> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        Set<String> auditWriters = new HashSet<>();

        for (JavaClass clazz : classes) {
            if (isAdminController(clazz)) queue.addAll(clazz.getMethods());
        }
        while (!queue.isEmpty()) {
            JavaCodeUnit unit = queue.poll();
            if (!visited.add(unit.getFullName())) continue;
            if (!unit.getOwner().getPackageName().startsWith("com.finora")) continue;
            if (writesAuditEntry(unit) && !unit.getOwner().getSimpleName().equals("AuditService")) {
                auditWriters.add(unit.getOwner().getSimpleName() + "." + unit.getName());
            }
            unit.getCallsFromSelf().stream()
                    .map(call -> call.getTarget().resolveMember().orElse(null))
                    .filter(Objects::nonNull)
                    .filter(target -> target.getOwner().getPackageName().startsWith("com.finora"))
                    .forEach(queue::add);
        }

        assertThat(auditWriters)
                .as("expected to reach the admin-triggered audit surface; if this dropped to ~0 "
                        + "the attribution rule above is silently checking nothing")
                .hasSizeGreaterThanOrEqualTo(25);
    }
}
