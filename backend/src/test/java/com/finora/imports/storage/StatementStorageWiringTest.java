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
        // A typo must not silently fall back to a store. Nothing matches, so nothing is created,
        // and whatever tries to use storage fails at startup rather than writing somewhere
        // unintended.
        context.withPropertyValues("app.statement-storage.provider=r3")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(StatementStorage.class));
    }
}
