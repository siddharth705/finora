package com.finora.config;

import com.finora.FinoraApplication;
import com.finora.testsupport.TestPhoneVerificationConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Closes the gap {@code ProductionConfigValidatorTest} deliberately leaves open: that test calls
 * {@code validator.validate()} directly, which proves the method throws but proves nothing about
 * whether the real Spring Boot application lifecycle actually stops before serving traffic. See
 * {@code StartupConfigValidationLifecycleTest} and {@code ProductionConfigValidator}'s own class
 * doc for why the lifecycle phase matters: an {@code ApplicationRunner}-based validator would fail
 * this exact scenario while every unit test of it kept passing, because the web server would
 * already be bound and accepting connections by the time the exception was thrown.
 *
 * <p>This test boots a real, close-to-full application context -- real Testcontainers Postgres,
 * real Flyway migrations, every {@code SmartInitializingSingleton} in the app -- with the
 * {@code prod} profile active, every OTHER required production setting valid, and only
 * {@code RESEND_API_KEY} left unset. It proves three things unit tests of the validator cannot:
 *
 * <ol>
 *   <li>{@link SpringApplicationBuilder#run(String...)} itself throws -- not just the validator's
 *       own method.</li>
 *   <li>The exception surfacing from a full context refresh still carries the RESEND_API_KEY
 *       explanation, not just "context failed to start".</li>
 *   <li>No embedded server port is ever bound -- {@link WebServerInitializedEvent} never fires --
 *       which is the literal defect FG-031 exists to prevent (see this class doc and
 *       {@code StartupConfigValidationLifecycleTest}: the historical bug served real requests for
 *       the width of a window before throwing).</li>
 * </ol>
 */
class ProductionConfigValidatorApplicationStartupIT {

    // Deliberately a container private to this test, not AbstractIntegrationTest.POSTGRES: that
    // shared container is seeded with the weak "finora" password AbstractIntegrationTest hard-codes,
    // which is exactly one of the values ProductionConfigValidator's DB_PASSWORD check rejects. This
    // test needs a real, non-placeholder password so the *only* validator failure in play is
    // RESEND_API_KEY -- otherwise a passing assertion on the RESEND_API_KEY message would not prove
    // isolation from the other checks.
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("finora_prod_startup_it")
            .withUsername("finora")
            .withPassword("a-genuinely-strong-non-placeholder-test-password");

    static {
        POSTGRES.start();
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @Test
    void applicationFailsToStart_inProdProfile_withNoResendApiKey_andNeverBindsAPort() throws IOException {
        Path storageRoot = Files.createTempDirectory("finora-prod-startup-it-storage");

        AtomicBoolean webServerBound = new AtomicBoolean(false);

        // Passed as command-line args (highest-precedence Spring property source), not via
        // SpringApplicationBuilder.properties(...) -- that method registers a "defaultProperties"
        // source, which is LOWER precedence than the values application.yml's own placeholders
        // (${DB_PASSWORD:finora}, etc.) resolve to once bound. A first attempt using .properties()
        // silently lost every one of these to those yml defaults, and the test failed for the wrong
        // reasons (JWT_SECRET/DB_PASSWORD/storage all "unset" too, not just RESEND_API_KEY) --
        // proving nothing about isolating this one check. Command-line args always win.
        SpringApplicationBuilder builder = new SpringApplicationBuilder(
                FinoraApplication.class, TestPhoneVerificationConfig.class)
                .web(WebApplicationType.SERVLET)
                .listeners((ApplicationListener<WebServerInitializedEvent>) event -> webServerBound.set(true));

        String[] args = {
                "--spring.profiles.active=prod",
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                // Real, non-placeholder, 32+ character secret -- every OTHER hard-fail check in
                // ProductionConfigValidator must pass so this test isolates RESEND_API_KEY.
                "--app.jwt.secret=a-genuinely-long-random-secret-value-used-only-by-this-test",
                "--app.statement-storage.provider=filesystem",
                "--app.statement-storage.filesystem.root=" + storageRoot,
                // The one thing under test: left unset. application.yml's own default
                // (${RESEND_API_KEY:}) resolves to blank, which is exactly the production gap this
                // validator exists to catch.
                "--app.email.api-key=",
                // Not a hard-fail check (see ProductionConfigValidator/SmsProperties), but pinned
                // explicitly so a missing value can't be mistaken for the failure under test.
                "--app.security.trust-proxy-headers=true"
        };

        assertThatThrownBy(() -> builder.run(args))
                .as("SpringApplication.run() must itself throw for a full boot attempt, not just "
                        + "ProductionConfigValidator.validate() called directly -- see "
                        + "ProductionConfigValidatorTest for the direct-call version of this "
                        + "assertion and this class's own doc for why that alone isn't proof")
                .satisfies(thrown -> {
                    Throwable cause = rootCause(thrown);
                    assertThat(cause)
                            .as("the underlying failure must be ProductionConfigValidator's own "
                                    + "exception, not some unrelated context-refresh error")
                            .isInstanceOf(IllegalStateException.class);
                    assertThat(cause.getMessage())
                            .as("the failure surfacing from a real context refresh must still carry "
                                    + "the RESEND_API_KEY explanation, not just \"context failed to "
                                    + "start\", and must NOT also list JWT_SECRET/DB_PASSWORD/storage "
                                    + "as problems -- this test isolates RESEND_API_KEY specifically")
                            .contains("RESEND_API_KEY")
                            .doesNotContain("JWT_SECRET is unset")
                            .doesNotContain("DB_PASSWORD is")
                            .doesNotContain("app.statement-storage.provider is unset");
                });

        assertThat(webServerBound)
                .as("FG-031: a *ConfigValidator must refuse startup BEFORE the embedded server binds "
                        + "a port. WebServerInitializedEvent firing here would mean the application "
                        + "was already accepting connections when ProductionConfigValidator threw -- "
                        + "the exact historical bug (ApplicationRunner ran after finishRefresh() had "
                        + "already started the connector) this test exists to catch a regression of.")
                .isFalse();
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
