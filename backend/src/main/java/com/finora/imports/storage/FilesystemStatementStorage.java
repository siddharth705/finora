package com.finora.imports.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Statements on the local disk, under a configured root.
 *
 * For development and tests. Not for production behind more than one backend instance -- two
 * instances would each see only their own disk, and a statement stored by one would be missing for
 * the other. That is the same single-instance precondition the in-memory rate limiter already
 * carries; see docs/engineering/deployment-guide.md's "Before running more than one backend
 * instance". R2 is what removes it.
 *
 * Its real job is making this layer testable. An object store cannot be exercised in a unit test
 * without credentials and a network, and a mock only proves the mock behaves. Writing to a temp
 * directory exercises the actual contract -- addressing, deduplication, idempotent re-store,
 * missing-object failure -- offline and deterministically.
 */
@Component
@ConditionalOnProperty(name = "app.statement-storage.provider", havingValue = "filesystem")
public class FilesystemStatementStorage implements StatementStorage {

    private static final Logger log = LoggerFactory.getLogger(FilesystemStatementStorage.class);

    private final Path root;

    public FilesystemStatementStorage(@Value("${app.statement-storage.filesystem.root}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public ContentAddress store(byte[] content) {
        ContentAddress address = ContentAddress.forContent(content);
        Path target = resolve(address);

        // Identical content is already stored under this exact address -- that is what
        // content-addressing buys, and rewriting it would be pure cost. Returning early also makes
        // a retry after a partial failure a no-op rather than a duplicate.
        if (Files.exists(target)) return address;

        try {
            Files.createDirectories(target.getParent());
            // Write to a temp file and move: a crash mid-write must not leave a half-written object
            // at an address that then reports as present. ATOMIC_MOVE keeps that guarantee on the
            // same filesystem, which the temp file is on by construction (same parent directory).
            Path temp = Files.createTempFile(target.getParent(), ".partial-", ".tmp");
            try {
                Files.write(temp, content);
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.deleteIfExists(temp);
                throw e;
            }
        } catch (IOException e) {
            throw new StatementStorageException("Could not store statement " + address.hash(), e);
        }
        return address;
    }

    @Override
    public byte[] retrieve(ContentAddress address) {
        Path source = resolve(address);
        try {
            return Files.readAllBytes(source);
        } catch (IOException e) {
            // The caller got here from a row asserting this exists, so this is a broken invariant.
            // Logged as well as thrown because the address is the only thread back to which row.
            log.error("Statement object missing or unreadable: {}", address.key());
            throw new StatementStorageException("Could not read statement " + address.hash(), e);
        }
    }

    @Override
    public boolean exists(ContentAddress address) {
        return Files.isRegularFile(resolve(address));
    }

    /**
     * BH-017. Only ever called by {@code StatementStorageSweepService} once it has established the
     * key is unreferenced across both tables and past the retention window -- see this interface's
     * class doc. {@code deleteIfExists} rather than {@code delete}: idempotent, matching R2's
     * DeleteObject semantics, and a re-run of the sweep after a partial batch failure must not
     * throw on a key it already removed.
     */
    @Override
    public void delete(String objectKey) {
        Path target = resolveKey(objectKey);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new StatementStorageException("Could not delete statement " + objectKey, e);
        }
    }

    /**
     * Maps an address to a path, refusing anything that escapes the root.
     *
     * The key comes from ContentAddress and is machine-generated, so this should be unreachable.
     * It is here because "should be unreachable" is exactly the assumption that path traversal
     * exploits: a future implementation reading keys back from the database would make the key
     * externally influenced, and the check costs nothing.
     */
    private Path resolve(ContentAddress address) {
        return resolveKey(address.key());
    }

    private Path resolveKey(String key) {
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new StatementStorageException("Statement key escapes the storage root: " + key);
        }
        return resolved;
    }
}
