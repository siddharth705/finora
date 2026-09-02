package com.finora.architecture;

import com.finora.architecture.registry.GuardianRule;
import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * A class's name has to predict what it is, because the name is all a reader has at the call site.
 *
 * <p>{@code CODING_STANDARDS.md} "Naming" fixes the vocabulary -- {@code <Noun>Controller},
 * {@code <Noun>Service} for "the public orchestration entry point Spring wires into controllers",
 * {@code <Noun>Dto}. It also makes the sharper point that a single-responsibility collaborator used
 * by one service should be named for what it <em>does</em> ({@code CsvParser},
 * {@code DuplicateDetector}) rather than carrying a {@code -Service} suffix it has not earned.
 *
 * <p>These rules check the mechanical half of that -- that the suffix and the Spring stereotype
 * agree. They cannot check the judgement half, which is what code review is for.
 */
class StereotypeNamingConventionTest {

    private JavaClasses productionClasses() {
        return ProductionClasses.INSTANCE;
    }

    @GuardianRule(
            id = "FG-014",
            category = GuardianRule.Category.NAMING,
            intent = "A class named *Controller is annotated @RestController.",
            source = "CODING_STANDARDS.md > Backend > Naming",
            introduced = "2026-08-05",
            owner = "architecture",
            verification = GuardianRule.Verification.MANUAL_FALSIFICATION)
    @Test
    void everyControllerSuffixIsAnActualRestController() {
        classes().that().haveSimpleNameEndingWith("Controller")
                .should().beAnnotatedWith(RestController.class)
                .because("""
                        a class named *Controller that Spring never registers is invisible in the \
                        worst way: the endpoints simply do not exist, every unit test of the class \
                        still passes because it is being called as a plain object, and the only \
                        symptom is a 404 that looks like a routing problem""")
                .check(productionClasses());
    }

    @GuardianRule(
            id = "FG-015",
            category = GuardianRule.Category.NAMING,
            intent = "A class annotated @RestController is named *Controller.",
            source = "CODING_STANDARDS.md > Backend > Naming",
            introduced = "2026-08-05",
            owner = "architecture",
            verification = GuardianRule.Verification.MANUAL_FALSIFICATION)
    @Test
    void everyRestControllerIsNamedController() {
        classes().that().areAnnotatedWith(RestController.class)
                .should().haveSimpleNameEndingWith("Controller")
                .because("""
                        the reverse direction matters for search. Auditing "every HTTP entry point" \
                        -- which the admin authorization and request-validation rules in this \
                        package both do by name -- silently skips anything that serves requests \
                        without announcing it in its name""")
                .check(productionClasses());
    }

    @GuardianRule(
            id = "FG-016",
            category = GuardianRule.Category.NAMING,
            intent = "A class named *Service is a Spring bean (@Service or @Component).",
            source = "CODING_STANDARDS.md > Backend > Naming",
            introduced = "2026-08-05",
            owner = "architecture",
            verification = GuardianRule.Verification.MANUAL_FALSIFICATION,
            exceptions = "@Component accepted: ImportSessionService, ImportRuleLearningService, BootstrapService")
    @Test
    void everyServiceSuffixIsASpringManagedBean() {
        classes().that().haveSimpleNameEndingWith("Service").and().areNotInterfaces()
                .should().beAnnotatedWith(Service.class)
                .orShould().beAnnotatedWith(Component.class)
                .because("""
                        a *Service that is not a bean cannot be injected, and the failure arrives \
                        as a context-startup error naming a dependency rather than the class that \
                        forgot its annotation. @Component is accepted as well as @Service: \
                        ImportSessionService, ImportRuleLearningService and BootstrapService are \
                        deliberately @Component, and the two stereotypes are functionally identical \
                        to Spring -- insisting on one spelling would be a rename with no behaviour \
                        behind it""")
                .check(productionClasses());
    }

    @GuardianRule(
            id = "FG-017",
            category = GuardianRule.Category.NAMING,
            intent = "A type named *Repository is an interface extending Spring Data Repository.",
            source = "CODING_STANDARDS.md > Backend > Naming",
            introduced = "2026-08-05",
            owner = "architecture",
            verification = GuardianRule.Verification.MANUAL_FALSIFICATION)
    @Test
    void everyRepositorySuffixIsASpringDataInterface() {
        classes().that().haveSimpleNameEndingWith("Repository")
                .should().beInterfaces()
                .andShould().beAssignableTo(Repository.class)
                .because("""
                        the *Repository name is what the layering rules in this package match on to \
                        decide what counts as persistence. A hand-written class wearing the suffix \
                        would be exempt from Spring Data's proxying while still being treated as a \
                        repository by every rule here -- so a query could sit in a controller and \
                        no rule would notice""")
                .check(productionClasses());
    }

    @GuardianRule(
            id = "FG-018",
            category = GuardianRule.Category.NAMING,
            intent = "A class named *Config is annotated @Configuration.",
            source = "CODING_STANDARDS.md > Backend > Naming",
            introduced = "2026-08-05",
            owner = "architecture",
            verification = GuardianRule.Verification.MANUAL_FALSIFICATION)
    @Test
    void everyConfigSuffixIsAConfigurationClass() {
        classes().that().haveSimpleNameEndingWith("Config")
                .should().beAnnotatedWith(Configuration.class)
                .because("""
                        a *Config class without @Configuration contributes no beans at all. Its \
                        @Bean methods are never called, and every dependency that was meant to come \
                        from it either fails to wire or -- worse, and this has happened here before \
                        with Optional beans -- resolves to something empty that looks deliberate""")
                .check(productionClasses());
    }
}
