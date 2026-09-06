package com.finora.architecture;

import com.finora.architecture.registry.GuardianRule;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A validator that can refuse to start the application must run BEFORE the web server binds.
 *
 * <p>{@code ProductionConfigValidator} is the gate that stops a production deployment booting with
 * {@code JWT_SECRET} still set to the placeholder committed to this repository, or with
 * {@code RESEND_API_KEY} unset (which turns password reset into an account-takeover primitive --
 * see that class for the full list). It was written as an {@code ApplicationRunner}, and that is
 * one lifecycle phase too late to do the job it claims to do.
 *
 * <p>Spring Boot starts the web server inside {@code AbstractApplicationContext.finishRefresh()}.
 * {@code ApplicationRunner} and {@code CommandLineRunner} beans are invoked afterwards, by
 * {@code SpringApplication.callRunners()}, against an already-refreshed context. So the real
 * ordering was: bind the port, start accepting connections, then check the signing key, then throw.
 * The throw does end the process -- but only after a window in which real requests were served and
 * real tokens could be minted against a key anyone with repository access already knows. A platform
 * that restarts a crashed container reopens that window on every attempt, so a misconfigured
 * deployment is a repeating exposure rather than a single brief one.
 *
 * <p>{@link SmartInitializingSingleton#afterSingletonsInstantiated()} runs at the end of
 * {@code preInstantiateSingletons()}, inside {@code finishBeanFactoryInitialization()} -- the phase
 * before {@code finishRefresh()}. Throwing there fails the refresh outright, so the connector never
 * binds and there is no window at all.
 *
 * <p>The rule is worth a build break rather than a comment for the same reason FG-026 is: the wrong
 * version is invisible from everywhere you would look. It compiles, every unit test of the
 * validator passes (they call the checks directly), the application really does refuse to run, and
 * the log even shows the refusal. Nothing distinguishes "refused before serving" from "refused
 * after serving" except knowing which Spring lifecycle callback you picked, and
 * {@code ApplicationRunner} is the one that autocompletes.
 *
 * <p>Scoped by the {@code *ConfigValidator} naming convention rather than to one class by name, so
 * it holds for the next startup validator somebody writes -- which is the realistic path back to
 * this bug, not an edit to the existing file.
 *
 * <p>If you are here because this test failed: your validator implements {@code ApplicationRunner}
 * or {@code CommandLineRunner}. Implement {@code SmartInitializingSingleton} instead and move the
 * body into {@code afterSingletonsInstantiated()} -- see {@code ProductionConfigValidator}.
 */
class StartupConfigValidationLifecycleTest {

    @GuardianRule(
            id = "FG-031",
            category = GuardianRule.Category.SECURITY,
            intent = "A *ConfigValidator runs as a SmartInitializingSingleton, never as an ApplicationRunner, "
                    + "so it refuses startup before the web server binds.",
            source = "Audit: ProductionConfigValidator served traffic before validating JWT_SECRET",
            introduced = "2026-08-08",
            owner = "architecture",
            verification = GuardianRule.Verification.MANUAL_FALSIFICATION)
    @Test
    void aStartupConfigValidatorMustRunBeforeTheWebServerBinds() {
        JavaClasses classes = ProductionClasses.INSTANCE;

        List<String> offenders = new ArrayList<>();
        List<String> validators = new ArrayList<>();

        for (JavaClass javaClass : classes) {
            if (!javaClass.getSimpleName().endsWith("ConfigValidator")) continue;
            if (javaClass.isInterface()) continue;
            validators.add(javaClass.getFullName());

            boolean runsAfterRefresh = javaClass.isAssignableTo(ApplicationRunner.class)
                    || javaClass.isAssignableTo(CommandLineRunner.class);
            if (runsAfterRefresh) {
                offenders.add(javaClass.getFullName() + " (implements a *Runner)");
                continue;
            }
            if (!javaClass.isAssignableTo(SmartInitializingSingleton.class)) {
                offenders.add(javaClass.getFullName() + " (implements no pre-refresh callback)");
            }
        }

        // A rule that silently matches nothing passes forever and reads as coverage. The one
        // validator that exists is the reason this rule exists, so its absence is a broken rule,
        // not a clean tree -- the same self-vacuity trap scripts/check-imports.py guards with its
        // --self-test flag.
        assertThat(validators)
                .as("No *ConfigValidator was found at all. This rule has stopped checking anything "
                        + "-- either the class was renamed (update this rule) or it was deleted "
                        + "(delete this rule and its row in repository-guardian-rules.md).")
                .isNotEmpty();

        assertThat(offenders)
                .as("""
                        These startup validators do not run before the web server binds. An \
                        ApplicationRunner/CommandLineRunner is invoked by callRunners() AFTER \
                        finishRefresh() has already started the connector, so the application \
                        accepts real requests under the exact configuration the validator exists to \
                        reject, and only then throws. Implement SmartInitializingSingleton and put \
                        the checks in afterSingletonsInstantiated(), which runs one phase earlier -- \
                        see ProductionConfigValidator.""")
                .isEmpty();
    }
}
