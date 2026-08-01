package com.finora.config;

import com.finora.service.SilentProductionFallback;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reusable diagnostic, not a point patch -- see {@link SilentProductionFallback}'s class doc for
 * the full story: {@code ProductionConfigValidator} used to only check {@code JWT_SECRET}/
 * {@code DB_PASSWORD}, so a production deployment missing {@code RESEND_API_KEY} started up
 * completely normally and silently began returning password-reset links directly in API
 * responses. That specific gap is fixed, but nothing stopped a *third* {@code NoOp*}-style silent
 * fallback from being added later with the identical gap -- until this test.
 *
 * <p>Two checks, closing the loop from both ends:
 * <ol>
 *   <li>Every class named {@code NoOp*} under {@code com.finora} must implement
 *       {@link SilentProductionFallback} -- so a new fallback can't be added without declaring
 *       which config it silently substitutes for.</li>
 *   <li>Every declared hint must actually appear in {@code ProductionConfigValidator}'s source --
 *       so declaring a hint isn't enough on its own; the startup check has to actually exist.</li>
 * </ol>
 */
class SilentFallbackConfigValidationTest {

    private static final Path VALIDATOR_SOURCE =
            Path.of("src/main/java/com/finora/config/ProductionConfigValidator.java");

    private JavaClasses classesIn(String rootPackage, boolean excludeTestClasses) {
        ClassFileImporter importer = new ClassFileImporter();
        if (excludeTestClasses) {
            importer = importer.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS);
        }
        return importer.importPackages(rootPackage);
    }

    private List<String> classesNamedNoOpWithoutTheMarkerInterface(JavaClasses classes) {
        List<String> undeclared = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            if (!javaClass.getSimpleName().startsWith("NoOp")) continue;
            if (!javaClass.isAssignableTo(SilentProductionFallback.class)) {
                undeclared.add(javaClass.getFullName());
            }
        }
        return undeclared;
    }

    private List<String> declaredHintsNotCoveredByValidator(JavaClasses classes, String validatorSource) {
        List<String> uncovered = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            if (!javaClass.isAssignableTo(SilentProductionFallback.class)) continue;
            if (javaClass.isInterface()) continue;

            String hint = instantiateAndGetHint(javaClass);
            if (!validatorSource.contains(hint)) {
                uncovered.add(javaClass.getFullName() + " declares \"" + hint
                        + "\" but ProductionConfigValidator's source never mentions it");
            }
        }
        return uncovered;
    }

    @Test
    void everyNoOpClassDeclaresTheConfigItSilentlySubstitutesFor() {
        assertThat(classesNamedNoOpWithoutTheMarkerInterface(classesIn("com.finora", true)))
                .as("every NoOp* class must implement SilentProductionFallback, declaring the "
                        + "environment variable whose absence causes it to be selected -- see "
                        + "SilentProductionFallback's class doc")
                .isEmpty();
    }

    @Test
    void everyDeclaredConfigHintIsActuallyCheckedByProductionConfigValidator() throws IOException {
        assertThat(Files.exists(VALIDATOR_SOURCE))
                .as("expected to find ProductionConfigValidator's source at %s -- if this path is "
                        + "wrong the check below is silently checking nothing", VALIDATOR_SOURCE)
                .isTrue();
        String validatorSource = Files.readString(VALIDATOR_SOURCE);

        assertThat(declaredHintsNotCoveredByValidator(classesIn("com.finora", true), validatorSource))
                .as("a NoOp* fallback declares a config hint that ProductionConfigValidator "
                        + "doesn't actually check for -- add a startup validation block for it, "
                        + "the same way RESEND_API_KEY/TWILIO_ACCOUNT_SID are checked today")
                .isEmpty();
    }

    /**
     * Guards the guard, part one: proves the "must implement the marker" check actually fires,
     * against a fixture reproducing that exact bug shape. Not excluding test classes here, since
     * the fixture lives under target/test-classes, which DO_NOT_INCLUDE_TESTS filters out.
     */
    @Test
    void theRuleDetectsANoOpClassMissingTheMarkerInterface() {
        assertThat(classesNamedNoOpWithoutTheMarkerInterface(classesIn("com.finora.config.fixtures", false)))
                .contains("com.finora.config.fixtures.NoOpFixtureMissingInterface");
    }

    /** Guards the guard, part two: proves the "hint must be covered" check actually fires. */
    @Test
    void theRuleDetectsAnUncoveredConfigHint() throws IOException {
        String validatorSource = Files.readString(VALIDATOR_SOURCE);

        List<String> uncovered = declaredHintsNotCoveredByValidator(
                classesIn("com.finora.config.fixtures", false), validatorSource);

        assertThat(uncovered).anyMatch(message ->
                message.contains("NoOpFixtureWithUncoveredHint")
                        && message.contains("SOME_CONFIG_KEY_THE_VALIDATOR_DOES_NOT_CHECK"));
    }

    private String instantiateAndGetHint(JavaClass javaClass) {
        try {
            Class<?> clazz = javaClass.reflect();
            SilentProductionFallback instance =
                    (SilentProductionFallback) clazz.getDeclaredConstructor().newInstance();
            return instance.requiredConfigHint();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(
                    "Could not instantiate " + javaClass.getFullName() + " via its no-arg "
                    + "constructor to read requiredConfigHint() -- SilentProductionFallback "
                    + "implementations are expected to be trivially constructible", e);
        }
    }
}
