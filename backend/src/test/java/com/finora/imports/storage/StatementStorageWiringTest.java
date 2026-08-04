package com.finora.imports.storage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

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
            .withUserConfiguration(FilesystemStatementStorage.class);

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
                .withUserConfiguration(FilesystemStatementStorage.class, StatementContentService.class)
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
                .withUserConfiguration(FilesystemStatementStorage.class, StatementContentService.class)
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(StatementContentService.class));

        new ApplicationContextRunner()
                .withUserConfiguration(FilesystemStatementStorage.class, StatementContentService.class)
                .withPropertyValues(
                        "app.statement-storage.provider=filesystem",
                        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-wiring-test")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(StatementContentService.class));
    }
}
