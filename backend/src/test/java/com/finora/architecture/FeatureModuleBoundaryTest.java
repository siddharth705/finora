package com.finora.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Keeps the feature-based half of the backend from being re-tangled with the layer-based half.
 *
 * <p>{@code CODING_STANDARDS.md} sets feature-based packages as the target shape and names
 * {@code com.finora.imports} as the first migrated example, with the explicit expectation that
 * "existing code moves toward it incrementally rather than in one pass". That leaves the tree in
 * two halves for a long time, and the risk during a long migration is not that it stalls -- it is
 * that the migrated half quietly acquires dependencies back on the legacy half, at which point the
 * remaining moves stop being mechanical and the migration becomes too expensive to finish.
 *
 * <p>These rules make the migration one-way. They say nothing about how fast it goes.
 */
class FeatureModuleBoundaryTest {

    /** The feature modules migrated so far, per CODING_STANDARDS.md's target shape. */
    private static final String[] FEATURE_MODULES = {
            "com.finora.imports..",
            "com.finora.accounts..",
            "com.finora.budgets..",
            "com.finora.goals..",
            "com.finora.transactions..",
            "com.finora.rules..",
    };

    private JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finora");
    }

    @Test
    void migratedFeaturesDoNotDependOnTheLegacyControllerPackage() {
        noClasses().that().resideInAnyPackage(FEATURE_MODULES)
                .should().dependOnClassesThat().resideInAPackage("com.finora.controller")
                .because("""
                        the migration only converges if it runs one way. com.finora.controller is \
                        the layer-based package being emptied; a feature module that depends on \
                        something still sitting in it pins that class in place, because moving it \
                        now means editing the migrated module too. Whatever is needed either \
                        belongs in the feature or belongs in a shared service both can call""")
                .check(productionClasses());
    }

    @Test
    void theReferenceFeatureModuleStaysAcyclic() {
        slices().matching("com.finora.imports.(*)..")
                .should().beFreeOfCycles()
                .because("""
                        com.finora.imports is the decomposition CODING_STANDARDS.md points every \
                        other module at, so its internal structure is copied by anyone migrating \
                        next. Its sub-packages (pdf, product, storage, ...) are acyclic today and \
                        that is worth keeping: a cycle between them would be inherited as the \
                        house pattern. Deliberately scoped to this module -- the top-level slices \
                        of com.finora still cycle through the layer-based packages (dto <-> entity \
                        among others), which is a fact about the in-progress migration and not \
                        something a test can usefully fail on today""")
                .check(productionClasses());
    }

    @Test
    void onlyTheStorageModuleKnowsWhichStorageProviderIsInUse() {
        noClasses().that().resideOutsideOfPackage("com.finora.imports.storage..")
                .should().dependOnClassesThat().haveSimpleName("FilesystemStatementStorage")
                .because("""
                        StatementStorage exists so the provider can be swapped -- local filesystem \
                        now, Cloudflare R2 next -- without touching a caller. That property only \
                        holds while callers name the interface. A single reference to the concrete \
                        class outside this package is what turns a configuration change into a \
                        code change, and it is the kind of thing that gets added by an IDE \
                        auto-import without anyone deciding it""")
                .check(productionClasses());
    }

    @Test
    void noProductionClassDependsOnATestFixture() {
        noClasses().should().dependOnClassesThat().resideInAPackage("..fixtures..")
                .because("""
                        the architecture tests here keep deliberately-broken classes in fixtures/ \
                        as negative cases -- code that exists precisely because it violates a rule. \
                        Production reaching one would both ship that violation and silently \
                        neutralise the test guarding against it""")
                .check(productionClasses());
    }
}
