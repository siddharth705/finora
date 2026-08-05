package com.finora.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * House rules that are cheap to state, easy to breach by habit, and invisible in review.
 *
 * <p>Each one here is a documented standard or an established convention of this codebase, and
 * each is the kind of thing that arrives by autocomplete rather than by decision -- which is
 * exactly the category worth spending a mechanical check on, and exactly the category a human
 * reviewer skims past.
 */
class ProductionCodeHygieneTest {

    /**
     * Classes allowed to touch {@code java.util.Date}, because a third-party API signature leaves
     * no choice.
     *
     * <p>Same bidirectional contract as
     * {@code LayerDependencyDirectionTest.LEGACY_CONTROLLER_REPOSITORY_ACCESS}: an unlisted
     * violation fails, and so does a listed class that no longer violates. If JJWT ever grows a
     * {@code java.time} overload, this test goes red and the entry comes out.
     */
    private static final Set<String> DATE_API_BOUNDARY = Set.of("com.finora.security.JwtService");

    private JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finora");
    }

    private Set<String> classesUsingALegacyDateType() {
        Set<String> legacyDateTypes = Set.of(
                "java.util.Date", "java.util.Calendar", "java.util.GregorianCalendar",
                "java.text.SimpleDateFormat", "java.sql.Timestamp");

        Set<String> actual = new TreeSet<>();
        productionClasses().forEach(c -> c.getDirectDependenciesFromSelf().stream()
                .map(d -> d.getTargetClass().getName())
                .filter(legacyDateTypes::contains)
                .findAny()
                .ifPresent(t -> actual.add(c.getName())));
        return actual;
    }

    @Test
    void timeIsRepresentedWithJavaTime() {
        List<String> unexpected = classesUsingALegacyDateType().stream()
                .filter(c -> !DATE_API_BOUNDARY.contains(c))
                .toList();

        assertThat(unexpected)
                .as("""
                        java.util.Date and friends are mutable, not thread-safe, and carry an \
                        offset-free instant that silently means "server default timezone" -- which \
                        for a product doing per-user statement periods and month boundaries is a \
                        correctness bug waiting for a deploy to a differently-configured host. Use \
                        java.time (Instant, LocalDate, ZonedDateTime). Add to DATE_API_BOUNDARY \
                        only when a third-party signature genuinely leaves no alternative, and \
                        convert at that boundary rather than letting the type spread inward.""")
                .isEmpty();
    }

    /**
     * Separate test method on purpose: as two assertions in one method, a new violation would fail
     * first and the stale-entry check would never run.
     */
    @Test
    void theLegacyDateAllowanceHasNoStaleEntries() {
        Set<String> actual = classesUsingALegacyDateType();
        List<String> stale = DATE_API_BOUNDARY.stream()
                .filter(c -> !actual.contains(c))
                .toList();

        assertThat(stale)
                .as("""
                        These classes are listed in DATE_API_BOUNDARY but no longer use a legacy \
                        date type. Remove them -- an accept-list that outlives what it excuses \
                        stops being reviewable.""")
                .isEmpty();
    }

    @Test
    void dependenciesArrriveThroughTheConstructor() {
        noFields().should().beAnnotatedWith(Autowired.class)
                .because("""
                        a field-injected dependency cannot be final, cannot be supplied by a plain \
                        constructor call in a unit test, and lets a class accumulate collaborators \
                        without the constructor ever getting long enough to look wrong -- removing \
                        the main signal that a class is doing too much. Every service in this \
                        codebase already uses constructor injection""")
                .check(productionClasses());
    }

    @Test
    void thereIsNoMutableGlobalState() {
        fields().that().arePublic().and().areStatic()
                .should().beFinal()
                .because("""
                        a public static mutable field is shared across every request thread in the \
                        application with no synchronisation and no owner. In a Spring app it is \
                        also invisible to the container, so nothing about the bean lifecycle \
                        resets it between tests -- which turns it into order-dependent test \
                        failures long before it turns into a production bug""")
                .check(productionClasses());
    }

    @Test
    void diagnosticsGoThroughTheLogger() {
        noClasses().should().accessField(System.class, "out")
                .orShould().accessField(System.class, "err")
                .because("""
                        CODING_STANDARDS.md, "Logging": LoggerFactory.getLogger(ThisClass.class), \
                        never System.out. Writing to stdout directly bypasses levels, structured \
                        fields and the class name, so the line cannot be filtered, correlated or \
                        switched off in production -- and on Railway it lands in the same stream \
                        as real log output while looking nothing like it""")
                .check(productionClasses());
    }

    /**
     * Matched by method name rather than by declaring type, which is not a shortcut.
     * {@code callMethod(Throwable.class, "printStackTrace")} is the obvious spelling and is
     * silently vacuous: javac emits the call against the receiver's <em>static</em> type, so
     * {@code catch (RuntimeException e) { e.printStackTrace(); }} compiles to a call owned by
     * {@code java.lang.RuntimeException} and never matches an exact-owner predicate. This rule was
     * written that way first, verified against a deliberate violation, and found to pass.
     */
    @Test
    void exceptionsAreLoggedRatherThanPrinted() {
        List<String> violations = new ArrayList<>();
        productionClasses().forEach(c -> c.getMethodCallsFromSelf().stream()
                .filter(call -> call.getTarget().getName().equals("printStackTrace"))
                .forEach(call -> violations.add(call.getOriginOwner().getSimpleName()
                        + " -> " + call.getTarget().getFullName())));

        assertThat(violations)
                .as("""
                        printStackTrace() writes to stderr with no level, no logger name and no \
                        correlation with the request that failed. It is also the usual companion \
                        to a swallowed exception, which CODING_STANDARDS.md rules out separately: \
                        domain failures throw ApiException and GlobalExceptionHandler is the only \
                        place that turns an exception into a response.""")
                .isEmpty();
    }
}
