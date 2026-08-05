package com.finora.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dependencies between layers point one way: controller -> service -> repository -> entity.
 *
 * <p>Enforces {@code docs/engineering/CODING_STANDARDS.md} -- "Controllers" ("Thin: parse request,
 * call one service method, wrap the result. No business logic, no direct repository access") and
 * "DTO mapping" ("Never expose entities directly from a controller"). Those are stated as rules
 * and were, until this test, entirely unenforced.
 *
 * <p><b>Why direction is the thing worth locking, and not placement.</b> The backend is mid-
 * migration from layer-based packages ({@code controller/}, {@code service/}, {@code repository/})
 * to feature-based ones, with {@code com.finora.imports} as the first migrated example. 43 of the
 * 48 controllers still live in {@code com.finora.controller}. A rule asserting that a controller
 * sits in its feature package would therefore fail 43 times on day one, and a permanently-red rule
 * does not protect anything -- it teaches the team that the architecture suite is noise to be
 * skipped. Dependency direction, by contrast, is already true almost everywhere and is what the
 * migration is actually trying to preserve, so it can be locked now and holds regardless of which
 * package a class ends up in.
 */
class LayerDependencyDirectionTest {

    /**
     * The four controller -> repository dependencies that predate this rule.
     *
     * <p>Deliberately an explicit accept-list rather than a tolerated count, following
     * {@code check-dependency-advisories.py} -- the template
     * {@code docs/engineering/repository-guardian.md} §3.2 names, for the property that matters
     * most: it fails on a <em>stale</em> entry too, so the list cannot quietly rot. Deleting a
     * repository dependency from one of these controllers turns this test red until the entry is
     * removed, which makes the ratchet tighten automatically instead of depending on someone
     * remembering to tighten it.
     *
     * <p>Nothing may be added here. A new entry means a new violation of a documented standard,
     * and the fix is to move the query behind a service.
     */
    private static final Map<String, Set<String>> LEGACY_CONTROLLER_REPOSITORY_ACCESS = Map.of(
            "com.finora.controller.AdminBankController", Set.of("AuditLogRepository"),
            "com.finora.controller.AdminController", Set.of("AuditLogRepository"),
            "com.finora.controller.CategoryController", Set.of("CategoryRepository"),
            "com.finora.controller.UserController", Set.of("UserRepository"));

    private JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finora");
    }

    private Map<String, Set<String>> controllerToRepositoryDependencies() {
        Map<String, Set<String>> actual = new java.util.TreeMap<>();
        productionClasses().stream()
                .filter(c -> c.getSimpleName().endsWith("Controller"))
                .forEach(c -> c.getDirectDependenciesFromSelf().stream()
                        .map(d -> d.getTargetClass())
                        .filter(t -> t.getName().startsWith("com.finora")
                                && t.getSimpleName().endsWith("Repository"))
                        .forEach(t -> actual
                                .computeIfAbsent(c.getName(), k -> new TreeSet<>())
                                .add(t.getSimpleName())));
        return actual;
    }

    @Test
    void controllersDoNotReachPastTheirServiceIntoARepository() {
        Map<String, Set<String>> actual = controllerToRepositoryDependencies();
        List<String> newViolations = new ArrayList<>();
        actual.forEach((controller, repositories) -> {
            Set<String> accepted = LEGACY_CONTROLLER_REPOSITORY_ACCESS
                    .getOrDefault(controller, Set.of());
            repositories.stream()
                    .filter(r -> !accepted.contains(r))
                    .forEach(r -> newViolations.add(controller + " -> " + r));
        });

        assertThat(newViolations)
                .as("""
                        A controller reached directly into a repository. CODING_STANDARDS.md says a \
                        controller is thin -- parse the request, call one service method, wrap the \
                        result -- with no direct repository access. Move the query behind a service \
                        so the transaction boundary, authorization check and error translation all \
                        live in one place. Do not add the class to \
                        LEGACY_CONTROLLER_REPOSITORY_ACCESS; that list is frozen and only shrinks.""")
                .isEmpty();
    }

    /**
     * Separate test method on purpose: as two assertions in one method, a new violation would fail
     * first and the stale-entry check would never run.
     */
    @Test
    void theLegacyControllerRepositoryAllowanceHasNoStaleEntries() {
        Map<String, Set<String>> actual = controllerToRepositoryDependencies();
        List<String> stale = new ArrayList<>();
        LEGACY_CONTROLLER_REPOSITORY_ACCESS.forEach((controller, repositories) -> repositories.stream()
                .filter(r -> !actual.getOrDefault(controller, Set.of()).contains(r))
                .forEach(r -> stale.add(controller + " -> " + r)));

        assertThat(stale)
                .as("""
                        These entries in LEGACY_CONTROLLER_REPOSITORY_ACCESS no longer describe \
                        anything real -- the debt was paid. Delete them so the accept-list keeps \
                        meaning what it says and the rule gets correspondingly stricter.""")
                .isEmpty();
    }

    @Test
    void controllersNeverReturnAnEntity() {
        List<String> violations = new ArrayList<>();
        productionClasses().stream()
                .filter(c -> c.getSimpleName().endsWith("Controller"))
                .flatMap(c -> c.getMethods().stream())
                .forEach(m -> m.getReturnType().getAllInvolvedRawTypes().stream()
                        .filter(this::isEntity)
                        .forEach(t -> violations.add(m.getOwner().getSimpleName() + "."
                                + m.getName() + "() exposes " + t.getSimpleName())));

        assertThat(violations)
                .as("""
                        CODING_STANDARDS.md: never expose entities directly from a controller. An \
                        entity on a response is a Hibernate-managed object with lazy associations \
                        and every column it happens to have -- serializing it leaks fields nobody \
                        chose to publish and couples the wire format to the schema, so a column \
                        rename becomes a breaking API change. Return a <Noun>Dto instead. Note this \
                        checks the whole generic return type, so ResponseEntity<ApiResponse<Entity>> \
                        is caught too. Taking an entity as a PARAMETER is fine and is the normal \
                        shape of a private toDto(...) mapping helper.""")
                .isEmpty();
    }

    private boolean isEntity(JavaClass type) {
        return type.getPackageName().equals("com.finora.entity");
    }

    @Test
    void theTransactionBoundaryIsNotDrawnInTheWebLayer() {
        noClasses().that().haveSimpleNameEndingWith("Controller")
                .should().beAnnotatedWith(Transactional.class)
                .because("""
                        a transaction opened at the controller stays open for the whole request, \
                        including response serialization, so a slow client holds a database \
                        connection from a pool of a few dozen. It also silently makes lazy loading \
                        work during serialization, which hides N+1 queries that then appear the \
                        moment the annotation moves. The boundary belongs on the service method \
                        that owns the unit of work""")
                .check(productionClasses());
    }

    @Test
    void entitiesDependOnNothingAboveThem() {
        noClasses().that().resideInAPackage("com.finora.entity..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Service")
                .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Controller")
                .orShould().dependOnClassesThat().resideInAPackage("com.finora.dto..")
                .because("""
                        an entity is the bottom of the dependency graph. Once it can reach a \
                        service or a repository, "load this row" and "run this business rule" stop \
                        being separable, every entity test needs a Spring context, and the \
                        service <-> entity cycle makes the module impossible to extract later""")
                .check(productionClasses());
    }

    @Test
    void dtosCarryDataAndDoNotFetchIt() {
        noClasses().that().resideInAPackage("com.finora.dto..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Service")
                .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .because("""
                        a DTO that can reach a repository invites lazy per-field querying during \
                        serialization, which is where N+1 problems become invisible -- the query \
                        log fills up from inside Jackson, far from any code that looks like it \
                        loads data. DTOs are populated by their caller""")
                .check(productionClasses());
    }

    @Test
    void repositoriesDoNotCallBackUpTheStack() {
        noClasses().that().haveSimpleNameEndingWith("Repository")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Service")
                .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Controller")
                .because("""
                        persistence is the leaf of the call graph. A repository that calls a \
                        service creates a cycle that survives every later refactor and defeats any \
                        attempt to test persistence without booting the business layer""")
                .check(productionClasses());
    }

    @Test
    void controllersDoNotCallOtherControllers() {
        noClasses().that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat(com.tngtech.archunit.base.DescribedPredicate.describe(
                        "another Finora controller",
                        c -> c.getName().startsWith("com.finora")
                                && c.getSimpleName().endsWith("Controller")))
                .because("""
                        shared behaviour between two endpoints belongs in a service, not in one \
                        controller calling another. Chaining controllers means the second one's \
                        @PreAuthorize, @Valid and exception mapping are all bypassed -- the \
                        annotations only run when Spring invokes the method through the web layer, \
                        never on a plain Java call""")
                .check(productionClasses());
    }
}
