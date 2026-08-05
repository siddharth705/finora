package com.finora.architecture.registry;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test method as an enforced Repository Guardian rule and carries its lifecycle metadata.
 *
 * <p>The point of putting this on the method rather than in a document is that the document then
 * cannot drift. {@link GuardianRegistryTest} reads these annotations, checks them for uniqueness
 * and completeness, and fails if {@code docs/architecture/repository-guardian-rules.md} disagrees
 * with them in either direction. The registry is generated from the code, so a rule cannot be
 * added, retired or recategorised without the published list following it in the same commit.
 *
 * <p>Not every {@code @Test} in the architecture package is a rule. Several rules ship with
 * self-tests -- one proving the rule fires on a deliberate violation, one proving its subject set
 * is non-empty -- and those are the rule's own tests, not separate rules. Only annotate the method
 * that enforces something about production code.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GuardianRule {

    /**
     * Permanent identifier, {@code FG-NNN}. Never reused and never renumbered -- a new rule takes
     * the next free number regardless of its category, the same way a CVE id says nothing about
     * what kind of bug it is. Renumbering to keep categories contiguous would break every
     * reference in a commit message, a review comment or a CI log.
     */
    String id();

    Category category();

    /** One line, imperative, describing what the rule prevents. Mirrored into the registry. */
    String intent();

    /**
     * Where the rule's authority comes from -- a {@code CODING_STANDARDS.md} section, an ADR, or
     * the incident that motivated it. A rule with no source is an opinion, and opinions belong in
     * review rather than in a build-breaking check.
     */
    String source();

    /** ISO date the rule was first enforced. */
    String introduced();

    /**
     * Area accountable for the rule, not a person. Named owners are deliberately absent: this
     * repository has no maintainer-to-area mapping recorded anywhere, and inventing one would make
     * the registry confidently wrong about who to ask.
     */
    String owner();

    Verification verification();

    /** Accepted exceptions, naming the accept-list constant. Empty means the rule is absolute. */
    String exceptions() default "";

    enum Category {
        /** Which layer may depend on which. */
        DEPENDENCY,
        /** What a module may reach into, and what may reach into it. */
        BOUNDARY,
        /** Keeps the layer-based to feature-based migration converging. */
        MIGRATION,
        /** Names and Spring stereotypes agree. */
        NAMING,
        /** House conventions that arrive by autocomplete rather than by decision. */
        HYGIENE,
        /** Authorization, attribution, tenant scoping, input validation. */
        SECURITY,
        /** Framework misuse that is silently wrong rather than merely untidy. */
        CORRECTNESS,
    }

    enum Verification {
        /**
         * The rule ships with its own tests proving it fires on a violation and that its subject
         * set is non-empty. Re-verified on every run.
         */
        SELF_TEST,
        /**
         * Verified once, by hand, by introducing a deliberate violation and observing the failure.
         * Weaker than {@link #SELF_TEST}: it says the rule worked on the day it was written, not
         * that it still does. Upgrading these is tracked in repository-guardian.md.
         */
        MANUAL_FALSIFICATION,
    }
}
