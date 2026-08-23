package com.finora.imports.storage;

import com.finora.security.crypto.EncryptionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Phase 1 must be inert: introducing this layer cannot change how the application behaves today.
 *
 * The guarantee is that no storage bean exists unless a provider is explicitly named. Without that,
 * merely adding these classes would activate a store nobody configured -- and on the filesystem
 * implementation that would silently start writing statements to a local disk that a second backend
 * instance cannot see.
 */
class StatementStorageWiringTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(FilesystemStatementStorage.class, R2StatementStorage.class);

    /** {@code StatementContentService} now requires an {@code EncryptionService} bean -- these
     *  tests are only about which {@code StatementStorage} bean gets activated, so a mock (never
     *  invoked, since none of them actually call store()/read()) is enough. */
    @Configuration
    static class EncryptionTestConfig {
        @Bean
        EncryptionService encryptionService() {
            return mock(EncryptionService.class);
        }
    }

    private static final String[] R2_CREDENTIALS = {
            "app.statement-storage.r2.account-id=test-account",
            "app.statement-storage.r2.bucket=finora-statements-test",
            "app.statement-storage.r2.access-key-id=test-key-id",
            "app.statement-storage.r2.secret-access-key=test-secret",
    };

    @Test
    void noProviderConfigured_meansNoStorageBeanAtAll() {
        // The default in application.yml is empty on purpose. This is what makes Phase 1 a pure
        // addition: statements keep going to BYTEA exactly as before because nothing else has a
        // StatementStorage to call.
        context.run(ctx -> assertThat(ctx).doesNotHaveBean(StatementStorage.class));
    }

    @Test
    void filesystemProvider_activatesTheFilesystemImplementation() {
        context.withPropertyValues(
                        "app.statement-storage.provider=filesystem",
                        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-wiring-test")
                .run(ctx -> assertThat(ctx).hasSingleBean(FilesystemStatementStorage.class));
    }

    @Test
    void r2Provider_activatesTheR2Implementation() {
        // Constructing the S3 client makes no network call, so this is safe offline with fake
        // credentials. It proves the conditional and the required-property validation, not that
        // R2 is reachable.
        context.withPropertyValues("app.statement-storage.provider=r2")
                .withPropertyValues(R2_CREDENTIALS)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(R2StatementStorage.class);
                    assertThat(ctx).doesNotHaveBean(FilesystemStatementStorage.class);
                });
    }

    @Test
    void r2ProviderWithoutCredentials_failsAtStartupRatherThanAtTheFirstUpload() {
        // The failure mode this prevents is a deploy that looks completely healthy -- context up,
        // health endpoint green -- and then fails the first time a real user imports a statement,
        // by which point the only copy of their file is in a request that already returned 500.
        //
        // Asserts the REASON, not merely that startup failed. Written as a bare hasFailed() first,
        // it passed against a context that was dying of "No default constructor found" -- a real
        // bug in the bean, entirely unrelated to credentials, which the test happily reported as
        // success. A test that accepts any failure cannot tell the failure it wants from the one
        // it is hiding.
        context.withPropertyValues("app.statement-storage.provider=r2")
                .run(ctx -> assertThat(ctx).getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("R2_ACCOUNT_ID"));
    }

    @Test
    void r2ProviderNamesTheMissingEnvironmentVariable() {
        context.withPropertyValues("app.statement-storage.provider=r2")
                .withPropertyValues(
                        "app.statement-storage.r2.account-id=test-account",
                        "app.statement-storage.r2.bucket=finora-statements-test",
                        "app.statement-storage.r2.access-key-id=test-key-id")
                .run(ctx -> assertThat(ctx).getFailure()
                        // Naming the env var, not just the Spring property, is the difference
                        // between a fixable error and a hunt through application.yml for whatever
                        // ${...} feeds it.
                        .hasRootCauseMessage(
                                "app.statement-storage.provider is 'r2' but "
                                + "app.statement-storage.r2.secret-access-key is not set "
                                + "(environment variable R2_SECRET_ACCESS_KEY). Statements would "
                                + "have nowhere durable to go, so the application refuses to start "
                                + "rather than accepting uploads it cannot store."));
    }

    @Test
    void anUnknownProviderActivatesNothingRatherThanGuessing() {
        // A typo must not silently fall back to a store. Nothing matches, so nothing is created --
        // and StatementContentService then refuses to start, which is what stops that being a
        // silent database-only deployment. See the test below.
        context.withPropertyValues("app.statement-storage.provider=r3")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(StatementStorage.class));
    }

    /**
     * Bug fix. This test's sibling above asserts only that no bean is created, and its comment used
     * to claim that "whatever tries to use storage fails at startup rather than writing somewhere
     * unintended." That was not true: {@code StatementContentService} injects
     * {@code Optional<StatementStorage>}, so an unmatched provider name produced an empty Optional,
     * an INFO line reading "no statement storage provider configured", and a deployment that kept
     * writing every statement to the database while the operator believed the migration was
     * running. Nothing failed and nothing warned.
     *
     * <p>That is the silent-degradation-on-missing-config class this codebase already decided must
     * fail loudly -- see {@code SilentProductionFallback}. Asserted here rather than left to the
     * comment, so the guarantee is checked rather than described.
     */
    @Test
    void aProviderNameThatMatchesNothingFailsStartup_ratherThanSilentlyMeaningDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(FilesystemStatementStorage.class, StatementContentService.class, EncryptionTestConfig.class)
                .withPropertyValues("app.statement-storage.provider=r2")
                .run(ctx -> assertThat(ctx)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("matches no StatementStorage implementation"));
    }

    /** The supported states both still start cleanly: unset (storage disabled, bytes stay in the
     *  database) and a name that actually resolves. Without these the check above could pass by
     *  rejecting everything. */
    @Test
    void unsetAndRecognisedProvidersBothStartCleanly() {
        new ApplicationContextRunner()
                .withUserConfiguration(FilesystemStatementStorage.class, StatementContentService.class, EncryptionTestConfig.class)
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(StatementContentService.class));

        new ApplicationContextRunner()
                .withUserConfiguration(FilesystemStatementStorage.class, StatementContentService.class, EncryptionTestConfig.class)
                .withPropertyValues(
                        "app.statement-storage.provider=filesystem",
                        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-wiring-test")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(StatementContentService.class));
    }
}
