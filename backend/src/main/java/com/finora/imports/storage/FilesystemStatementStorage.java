package com.finora.imports.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new StatementStorageException("Could not create statement storage root " + this.root, e);
        }
        // createDirectories() above is a no-op when root already exists -- it does not check
        // permissions on a directory it didn't have to create. So a pre-existing but read-only
        // mount (e.g. misconfigured volume permissions) would otherwise sail through construction
        // and only fail later, at the first store() call, with the same "Could not create a
        // scratch file" error this constructor exists to make diagnosable. Checked explicitly here
        // so that failure mode also surfaces at startup, naming the property, rather than at a
        // customer's first upload.
        if (!Files.isWritable(this.root)) {
            throw new StatementStorageException(
                    "Statement storage root " + this.root + " is not writable. Set "
                    + "app.statement-storage.filesystem.root (STATEMENT_STORAGE_FS_ROOT) to a "
                    + "writable path.");
        }
    }

    /**
     * BH-018. The address can only be known once every byte has been read (see
     * {@link ContentAddress#copyAndAddress}), so this is necessarily two phases: spool first,
     * learn the address, then move into place. The scratch file is created directly under
     * {@link #root} -- not the system temp directory -- specifically so the {@code ATOMIC_MOVE}
     * below is guaranteed to land on the same filesystem as the final target. {@code root} is
     * created in the constructor, so it always exists by the time {@code store} runs; the
     * hash-prefixed subdirectory the target itself lives under may not yet, which is why the
     * scratch file can't simply be created there instead.
     */
    @Override
    public ContentAddress store(InputStream content, long contentLength) {
        Path scratch;
        try {
            scratch = Files.createTempFile(root, ".spool-", ".tmp");
        } catch (IOException e) {
            throw new StatementStorageException("Could not create a scratch file to spool an upload into", e);
        }

        ContentAddress address;
        try {
            try (OutputStream out = Files.newOutputStream(scratch)) {
                address = ContentAddress.copyAndAddress(content, out);
            }
        } catch (IOException e) {
            deleteQuietly(scratch);
            throw new StatementStorageException("Could not spool statement content before storing it", e);
        }

        Path target = resolve(address);

        // Identical content is already stored under this exact address -- that is what
        // content-addressing buys, and rewriting it would be pure cost. Returning early also makes
        // a retry after a partial failure a no-op rather than a duplicate. The scratch file was
        // needed only to learn that; it carries nothing this address doesn't already have stored.
        if (Files.exists(target)) {
            deleteQuietly(scratch);
            return address;
        }

        try {
            Files.createDirectories(target.getParent());
            // A crash mid-write must not leave a half-written object at an address that then
            // reports as present. ATOMIC_MOVE keeps that guarantee, same as before this fix --
            // only where the temp file was written and when its content became known have changed.
            Files.move(scratch, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteQuietly(scratch);
            throw new StatementStorageException("Could not store statement " + address.hash(), e);
        }
        return address;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not clean up scratch file {} after a failed or superseded store -- "
                    + "harmless, but it will sit in the storage root until removed by hand.", path, e);
        }
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
