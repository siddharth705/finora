package com.finora.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Wraps the two filesystem operations BootstrapService/SetupService need for the installation-key
 * file (V33__bootstrap_admin.sql). Its own component -- not called as a static/direct
 * java.nio.file API from those services -- purely so BootstrapServiceTest/SetupServiceTest can
 * mock it instead of performing real filesystem writes on every test run.
 */
@Component
public class SetupKeyFileWriter {

    // Relative to the JVM's working directory -- /app in the Docker image (see Dockerfile's
    // WORKDIR), which docker-compose.yml bind-mounts to ./.finora on the host. Named to match the
    // UI's own "Installation Key" terminology exactly, not an internal implementation name.
    public static final String SETUP_KEY_FILE_PATH = ".finora/installation.key";

    public void write(String rawPassword) throws IOException {
        Path path = Path.of(SETUP_KEY_FILE_PATH);
        Files.createDirectories(path.getParent());
        Files.writeString(path, """
                ======================================
                FINORA PLATFORM INITIALIZATION
                Installation Key

                %s

                This key can only be used once. It is retired automatically the moment setup
                completes, and this file is deleted at that point too. It is safe to delete
                manually before then if you'd rather not leave it on disk.
                ======================================
                """.formatted(rawPassword), StandardCharsets.UTF_8);
    }

    public void deleteIfPresent() throws IOException {
        Files.deleteIfExists(Path.of(SETUP_KEY_FILE_PATH));
    }

    public boolean exists() {
        return Files.exists(Path.of(SETUP_KEY_FILE_PATH));
    }
}
