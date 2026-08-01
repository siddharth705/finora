package com.finora.security;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The other half of what makes {@link OwnershipGuard} a reusable security rule rather than a
 * one-time cleanup: this fails the build if a service reintroduces a hand-rolled ownership
 * comparison instead of calling it -- exactly how the codebase ended up with nine independent
 * copies of the same six lines in the first place (see {@code OwnershipGuard}'s class comment).
 * Consolidating the logic once does nothing to stop a tenth copy from being pasted into new code
 * next month; this test is what actually stops that.
 *
 * <p>ArchUnit does not expose string literals inside method bodies through its class model, so
 * detection instead scans each compiled class file's raw bytes for the message text every
 * hand-rolled check produced ({@code "does not belong to you"}). Crude, but it survives
 * reformatting, cannot be defeated by whitespace, and -- per
 * {@link #theRuleDetectsAHandRolledOwnershipCheck()} -- has been proven to actually fire on the
 * exact bug shape it exists to catch.
 */
class OwnershipGuardUsageTest {

    private static final Pattern HAND_ROLLED_MESSAGE = Pattern.compile("does not belong to you");

    // Classes legitimately allowed to contain the message text without calling OwnershipGuard:
    // OwnershipGuard itself builds it, and ErrorCode is a declared-but-not-yet-wired-to-any-throw-
    // site message catalog (see its class doc) -- a string sitting in an enum constant, not
    // comparison logic, so it isn't the duplication this rule exists to catch.
    private static final List<String> ALLOWED_TO_CONTAIN_THE_MESSAGE =
            List.of(OwnershipGuard.class.getSimpleName(), "ErrorCode");

    private List<String> classesContainingTheHandRolledMessage(String rootPackage, boolean excludeTestClasses) {
        ClassFileImporter importer = new ClassFileImporter();
        if (excludeTestClasses) {
            importer = importer.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS);
        }
        JavaClasses classes = importer.importPackages(rootPackage);

        List<String> offenders = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            if (ALLOWED_TO_CONTAIN_THE_MESSAGE.contains(javaClass.getSimpleName())) continue;
            if (classFileContainsMessage(javaClass)) {
                offenders.add(javaClass.getFullName());
            }
        }
        return offenders;
    }

    private boolean classFileContainsMessage(JavaClass javaClass) {
        String resourceName = javaClass.getName().replace('.', '/') + ".class";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) return false;
            String raw = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            return HAND_ROLLED_MESSAGE.matcher(raw).find();
        } catch (Exception e) {
            throw new RuntimeException("Could not inspect " + resourceName, e);
        }
    }

    @Test
    void noServiceBuildsItsOwnBelongsToYouMessage_exceptOwnershipGuardItself() {
        assertThat(classesContainingTheHandRolledMessage("com.finora", true))
                .as("""
                        These classes build their own "does not belong to you" exception instead \
                        of calling OwnershipGuard.requireOwned()/requireOwnedBy() -- exactly the \
                        duplication that let TransactionService.create() ship without an \
                        ownership check at all, because ownership checking was a per-class \
                        convention rather than a single mechanism.""")
                .isEmpty();
    }

    /**
     * Guards the guard: proves the byte-scan genuinely detects the bug shape it exists to catch,
     * the same way {@code AdminEndpointAuthorizationTest} proves its own detection against a
     * fixture. A rule that silently stopped matching anything would look identical to a clean
     * codebase.
     */
    @Test
    void theRuleDetectsAHandRolledOwnershipCheck() {
        // Not excluding test classes here: the fixture itself lives under target/test-classes,
        // which DO_NOT_INCLUDE_TESTS filters out by design -- so this scan intentionally includes
        // them to reach it.
        assertThat(classesContainingTheHandRolledMessage("com.finora.security.fixtures", false))
                .contains("com.finora.security.fixtures.HandRolledOwnershipCheckFixture");
    }
}
