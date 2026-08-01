package com.finora.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reusable security rule, not a point patch.
 *
 * <p>The bug this exists to prevent has already happened once: {@code AdminSearchController}
 * shipped with no {@code @PreAuthorize} at all, on the mistaken theory that SecurityConfig's
 * {@code anyRequest().authenticated()} already restricted {@code /api/v1/admin/**} to admins. It
 * does not -- SecurityConfig has NO admin-path authorization whatsoever, so that rule is satisfied
 * by any valid JWT including an ordinary consumer-app user's own login. Authorization for the
 * entire admin surface is delegated, endpoint by endpoint, to {@code @PreAuthorize}. That makes an
 * omitted annotation silently equivalent to "any logged-in user may call this" -- which is exactly
 * how a non-admin came to be able to read other users' names and email addresses.
 *
 * <p>Fixing that one controller does not stop the 47th admin controller from repeating it. This
 * test does: it fails the build the moment any admin-mapped handler method is added without an
 * authorization annotation covering it, at class level or method level. It is deliberately scoped
 * to {@code /api/v1/admin} paths -- consumer endpoints are user-scoped and enforce access through
 * {@code CurrentUser} plus per-row ownership checks (see {@code OwnershipGuard}), not authorities,
 * so requiring {@code @PreAuthorize} on those would be wrong rather than merely noisy.
 */
class AdminEndpointAuthorizationTest {

    private static final String ADMIN_PATH_PREFIX = "/api/v1/admin";

    /** Every annotation Spring treats as declaring a request-handling method. */
    private static final List<Class<? extends Annotation>> MAPPING_ANNOTATIONS = List.of(
            RequestMapping.class, GetMapping.class, PostMapping.class,
            PutMapping.class, DeleteMapping.class, PatchMapping.class);

    private JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finora");
    }

    /**
     * The rule itself, factored out so the exact same logic can be pointed at the deliberately
     * vulnerable fixture below -- see {@link #theRuleDetectsAnUnguardedAdminEndpoint()}.
     */
    private List<String> findUnguardedAdminHandlers(JavaClasses classes) {
        List<String> unguarded = new ArrayList<>();

        for (JavaClass controller : classes) {
            if (!isAdminController(controller)) continue;
            // A class-level @PreAuthorize covers every handler in the class -- the majority
            // style in this codebase (see AdminBankController). Only classes without one need
            // each individual method checked.
            if (controller.isAnnotatedWith(PreAuthorize.class)) continue;

            for (JavaMethod method : controller.getMethods()) {
                if (!isRequestHandler(method)) continue;
                if (method.isAnnotatedWith(PreAuthorize.class)) continue;
                unguarded.add(controller.getSimpleName() + "." + method.getName() + "()");
            }
        }
        return unguarded;
    }

    @Test
    void everyAdminEndpointIsGuardedByAnAuthorizationAnnotation() {
        assertThat(findUnguardedAdminHandlers(productionClasses()))
                .as("""
                        Admin endpoints reachable by ANY authenticated user, including non-admins.
                        SecurityConfig only enforces anyRequest().authenticated() -- it has no
                        admin-path rule -- so an admin handler without @PreAuthorize is open to
                        every logged-in consumer account. Add @PreAuthorize("hasAuthority('...')")
                        to the method, or to the controller class to cover all of its handlers.""")
                .isEmpty();
    }

    /**
     * Guards the guard, part one: proves the rule genuinely detects the bug shape it exists to
     * catch, by running the identical logic against a fixture that deliberately reproduces the
     * original AdminSearchController vulnerability. A security rule that silently stopped
     * detecting anything would otherwise look exactly like a clean codebase.
     */
    @Test
    void theRuleDetectsAnUnguardedAdminEndpoint() {
        JavaClasses fixtures = new ClassFileImporter().importPackages("com.finora.architecture.fixtures");

        assertThat(findUnguardedAdminHandlers(fixtures))
                .as("the rule must flag the unguarded fixture handler, and must NOT flag its "
                        + "correctly-@PreAuthorize'd sibling")
                .containsExactly("UnguardedAdminControllerFixture.unguardedHandler()");
    }

    /**
     * Guards the guard, part two: if the detection logic silently stopped matching anything (a
     * package move, a renamed annotation, an ArchUnit upgrade that changes annotation reflection),
     * the production assertion would keep passing while checking nothing at all. Asserting a
     * realistic floor means that failure mode surfaces as a red test rather than false confidence.
     */
    @Test
    void theRuleActuallyFindsTheAdminControllersItClaimsToCheck() {
        long adminControllers = productionClasses().stream().filter(this::isAdminController).count();

        assertThat(adminControllers)
                .as("expected to find the admin controller surface; if this dropped to ~0 the "
                        + "authorization rule above is silently checking nothing")
                .isGreaterThanOrEqualTo(20);
    }

    private boolean isAdminController(JavaClass candidate) {
        if (!candidate.isAnnotatedWith(RestController.class)) return false;
        if (!candidate.isAnnotatedWith(RequestMapping.class)) return false;

        RequestMapping mapping = candidate.getAnnotationOfType(RequestMapping.class);
        return declaredPaths(mapping).stream().anyMatch(path -> path.startsWith(ADMIN_PATH_PREFIX));
    }

    /** Spring accepts the path under either value() or path() -- both are checked. */
    private List<String> declaredPaths(RequestMapping mapping) {
        List<String> paths = new ArrayList<>();
        paths.addAll(List.of(mapping.value()));
        paths.addAll(List.of(mapping.path()));
        return paths;
    }

    private boolean isRequestHandler(JavaMethod method) {
        return MAPPING_ANNOTATIONS.stream().anyMatch(method::isAnnotatedWith);
    }
}
