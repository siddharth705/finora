package com.finora.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reusable architecture rule, not a point patch.
 *
 * <p>The bug this exists to prevent has already happened once: {@code FirebaseConfig.firebaseApp()}
 * shipped as a {@code @Bean} method returning {@code Optional<FirebaseApp>}. Spring registers a
 * bean of type {@code Optional<FirebaseApp>} for that, but Spring's own dependency-injection
 * machinery special-cases every injection point declared as {@code Optional<X>} -- constructor
 * params, fields -- to mean "optionally autowire a plain bean of type X", never "find the
 * registered bean whose own type happens to be Optional<X>". The result: every consumer that
 * requested {@code Optional<FirebaseApp>} (i.e. {@code FirebasePhoneVerificationProvider}) always
 * received {@link Optional#empty()}, regardless of whether Firebase actually initialized
 * successfully -- silently disabling phone verification in every environment, including
 * production, even with perfectly valid credentials.
 *
 * <p>Fixing that one bean does not stop the next {@code @Configuration} class from repeating it.
 * This test does: it fails the build the moment any {@code @Bean} method anywhere in the codebase
 * is declared to return {@code Optional<...>}. The fix is always the same shape -- return the
 * plain type, nullable, and let Spring's "a @Bean method returning null registers no bean" behavior
 * do the optional-dependency job correctly.
 */
class NoOptionalBeanReturnTypeTest {

    private JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finora");
    }

    /**
     * The rule itself, factored out so the exact same logic can be pointed at the deliberately
     * broken fixture below -- see {@link #theRuleDetectsAnOptionalReturningBeanMethod()}.
     */
    private List<String> findOptionalReturningBeanMethods(JavaClasses classes) {
        List<String> offenders = new ArrayList<>();

        for (JavaClass configClass : classes) {
            if (!configClass.isAnnotatedWith(Configuration.class)) continue;

            for (JavaMethod method : configClass.getMethods()) {
                if (!method.isAnnotatedWith(Bean.class)) continue;
                if (method.getRawReturnType().isEquivalentTo(Optional.class)) {
                    offenders.add(configClass.getSimpleName() + "." + method.getName() + "()");
                }
            }
        }
        return offenders;
    }

    @Test
    void noBeanMethodReturnsOptional() {
        assertThat(findOptionalReturningBeanMethods(productionClasses()))
                .as("""
                        A @Bean method returning Optional<T> registers a bean of type Optional<T>,
                        which no Optional<T>-typed constructor/field injection point anywhere else
                        can ever receive -- Spring resolves Optional<T> injection points as
                        "optionally autowire T", not as "find the bean whose type is Optional<T>".
                        Return the plain, nullable type T instead; a @Bean method returning null
                        registers no bean at all, which is what makes downstream Optional<T>
                        injection resolve correctly.""")
                .isEmpty();
    }

    /**
     * Guards the guard, part one: proves the rule genuinely detects the bug shape it exists to
     * catch, by running the identical logic against a fixture that deliberately reproduces the
     * original FirebaseConfig vulnerability. A rule that silently stopped detecting anything would
     * otherwise look exactly like a clean codebase.
     */
    @Test
    void theRuleDetectsAnOptionalReturningBeanMethod() {
        JavaClasses fixtures = new ClassFileImporter().importPackages("com.finora.architecture.fixtures");

        assertThat(findOptionalReturningBeanMethods(fixtures))
                .as("the rule must flag the broken fixture bean, and must NOT flag its "
                        + "correctly-typed sibling")
                .containsExactly("OptionalReturningBeanConfigFixture.brokenBean()");
    }

    /**
     * Guards the guard, part two: if the detection logic silently stopped matching anything (a
     * package move, an ArchUnit upgrade that changes annotation reflection), the production
     * assertion would keep passing while checking nothing at all. Asserting a realistic floor
     * means that failure mode surfaces as a red test rather than false confidence.
     */
    @Test
    void theRuleActuallyFindsConfigurationClassesItClaimsToCheck() {
        long configClasses = productionClasses().stream()
                .filter(c -> c.isAnnotatedWith(Configuration.class))
                .count();

        assertThat(configClasses)
                .as("expected to find the @Configuration class surface; if this dropped to ~0 the "
                        + "rule above is silently checking nothing")
                .isGreaterThanOrEqualTo(3);
    }
}
