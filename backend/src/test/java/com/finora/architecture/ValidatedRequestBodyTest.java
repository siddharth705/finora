package com.finora.architecture;

import com.finora.architecture.registry.GuardianSelfTest;
import com.finora.architecture.registry.GuardianRule;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import jakarta.validation.Constraint;
import jakarta.validation.Valid;
import org.junit.jupiter.api.Test;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reusable validation rule, not a point patch.
 *
 * <p>The bug this exists to prevent has already happened once: {@code AccountDto.CreateRequest}
 * carries real Bean Validation constraints ({@code @NotBlank}, {@code @Size}) added specifically
 * so a blank/oversized field fails with a clean 400 instead of an unhandled
 * {@code DataIntegrityViolationException} 500 -- {@code AccountController} (the user-facing path)
 * applies {@code @Valid} on both {@code create()} and {@code update()}, exactly as intended. But
 * {@code AdminAccountController}, added later to let support staff manage a user's accounts on
 * their behalf, reuses the exact same {@code AccountDto.CreateRequest} body on its own
 * {@code create()}/{@code update()} handlers with no {@code @Valid} at all -- Spring only runs
 * Bean Validation on a {@code @RequestBody} when the parameter itself is annotated
 * {@code @Valid}/{@code @Validated}, so every constraint on the DTO was silently dead on the admin
 * path: an admin submitting a blank account name got the exact same raw 500 the original fix was
 * written to eliminate.
 *
 * <p>Fixing that one controller does not stop the next handler that reuses a constrained DTO from
 * repeating it. This test does: it fails the build the moment any {@code @RequestBody} parameter
 * is typed as a class/record that declares a {@code jakarta.validation.constraints} annotation on
 * one of its own fields, unless the parameter itself carries {@code @Valid} or {@code @Validated}.
 *
 * <p>Deliberately a direct-field check only (not recursive into nested types): every constrained
 * request body in this codebase today declares its constraints directly on its own record
 * components, and recursing into arbitrarily nested types risks false positives against DTOs that
 * merely embed an unrelated, independently-validated type.
 */
class ValidatedRequestBodyTest {

    private static final String VALIDATION_CONSTRAINT_PACKAGE = "jakarta.validation.constraints";

    private JavaClasses productionClasses() {
        return ProductionClasses.INSTANCE;
    }

    /**
     * The rule itself, factored out so the exact same logic can be pointed at the deliberately
     * broken fixture below -- see {@link #theRuleDetectsAnUnvalidatedConstrainedRequestBody()}.
     */
    private List<String> findUnvalidatedConstrainedRequestBodies(JavaClasses classes) {
        List<String> offenders = new ArrayList<>();

        for (JavaClass clazz : classes) {
            for (JavaMethod method : clazz.getMethods()) {
                for (JavaParameter parameter : method.getParameters()) {
                    if (!parameter.isAnnotatedWith(RequestBody.class)) continue;
                    if (parameter.isAnnotatedWith(Valid.class) || parameter.isAnnotatedWith(Validated.class)) {
                        continue;
                    }
                    if (declaresValidationConstraint(parameter.getRawType())) {
                        offenders.add(clazz.getSimpleName() + "." + method.getName() + "()");
                    }
                }
            }
        }
        return offenders;
    }

    /** True if any field on {@code type} carries a Bean Validation constraint -- covers record
     *  components too, since a validation constraint's {@code @Target} includes {@code FIELD} and
     *  javac copies a record component's annotations onto the backing field.
     *
     *  <p>Two ways to be a constraint, and both must count. The built-in ones live in
     *  {@code jakarta.validation.constraints}; a project-defined composed constraint (e.g.
     *  {@code com.finora.util.SafeHttpUrl}) lives in its own package and is identified instead by
     *  being meta-annotated {@code @Constraint}. Checking only the package would have left this
     *  rule blind to exactly the custom constraints written to enforce a security invariant --
     *  a DTO carrying nothing but {@code @SafeHttpUrl} would have looked unconstrained, and its
     *  missing {@code @Valid} would not have been reported. */
    private boolean declaresValidationConstraint(JavaClass type) {
        return type.getFields().stream()
                .flatMap(field -> field.getAnnotations().stream())
                .map(JavaAnnotation::getRawType)
                .anyMatch(annotationType -> VALIDATION_CONSTRAINT_PACKAGE.equals(annotationType.getPackageName())
                        || annotationType.isAnnotatedWith(Constraint.class));
    }

    @GuardianRule(
            id = "FG-028",
            category = GuardianRule.Category.SECURITY,
            intent = "A @RequestBody whose type carries constraints is annotated @Valid.",
            source = "CODING_STANDARDS.md > Backend > Validation",
            introduced = "2026-08-05",
            owner = "architecture",
            verification = GuardianRule.Verification.SELF_TEST)
    @Test
    void everyConstrainedRequestBodyIsAnnotatedValid() {
        assertThat(findUnvalidatedConstrainedRequestBodies(productionClasses()))
                .as("""
                        A @RequestBody parameter whose type declares real Bean Validation
                        constraints (@NotBlank, @Size, ...) but is missing @Valid/@Validated on
                        the parameter itself. Spring only runs Bean Validation on a @RequestBody
                        when the PARAMETER carries @Valid/@Validated -- the constraint annotations
                        alone do nothing. Without it, a value that should be rejected with a clean
                        400 instead reaches the service/repository layer unchecked and typically
                        fails later as an unhandled 500. Add @Valid to the parameter.""")
                .isEmpty();
    }

    /**
     * Guards the guard, part one: proves the rule genuinely detects the bug shape it exists to
     * catch, by running the identical logic against a fixture that deliberately reproduces the
     * original AdminAccountController vulnerability. A rule that silently stopped detecting
     * anything would otherwise look exactly like a clean codebase.
     */
    @GuardianSelfTest(rule = "FG-028")
    @Test
    void theRuleDetectsAnUnvalidatedConstrainedRequestBody() {
        JavaClasses fixtures = new ClassFileImporter().importPackages("com.finora.architecture.fixtures");

        assertThat(findUnvalidatedConstrainedRequestBodies(fixtures))
                .as("the rule must flag the unvalidated fixture handler, and must NOT flag its "
                        + "correctly-@Valid'd sibling")
                .containsExactly("UnvalidatedConstrainedRequestBodyControllerFixture.unvalidatedHandler()");
    }

    /**
     * Guards the guard, part two: if the detection logic silently stopped matching anything (a
     * package move, an ArchUnit upgrade that changes annotation reflection), the production
     * assertion would keep passing while checking nothing at all. Asserting a realistic floor
     * means that failure mode surfaces as a red test rather than false confidence.
     */
    @GuardianSelfTest(rule = "FG-028")
    @Test
    void theRuleActuallyFindsRequestBodyParametersItClaimsToCheck() {
        long requestBodyParameters = productionClasses().stream()
                .flatMap(c -> c.getMethods().stream())
                .flatMap(m -> m.getParameters().stream())
                .filter(p -> p.isAnnotatedWith(RequestBody.class))
                .count();

        assertThat(requestBodyParameters)
                .as("expected to find the @RequestBody parameter surface; if this dropped to ~0 "
                        + "the rule above is silently checking nothing")
                .isGreaterThanOrEqualTo(20);
    }
}
