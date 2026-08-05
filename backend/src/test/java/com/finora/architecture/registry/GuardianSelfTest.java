package com.finora.architecture.registry;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test that verifies a {@link GuardianRule} rather than enforcing something itself.
 *
 * <p>Two shapes exist in this package, and the older rules ship both: one test proving the rule
 * goes red against a deliberately broken fixture, and one proving the rule's subject set is
 * non-empty. The second matters more than it looks. A rule whose predicate matches nothing passes
 * forever and protects nothing, and the failure is invisible -- green is exactly what a working
 * rule looks like too.
 *
 * <p>{@link GuardianRegistryTest} requires every {@code @Test} in the architecture package to be
 * one or the other, so a new rule cannot be added without being registered, and cross-checks
 * {@link GuardianRule#verification()} against the self-tests that actually exist: a rule claiming
 * {@code SELF_TEST} with nothing pointing at it fails the build.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GuardianSelfTest {

    /** The {@code FG-NNN} id of the rule this test verifies. */
    String rule();
}
