package com.finora.imports.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The storage contract, exercised against a real temp directory rather than a mock -- a mock would
 * only prove the mock behaves. Everything asserted here is a property the R2 implementation will
 * have to satisfy identically, which is the point of testing the interface's behaviour rather than
 * one class's internals.
 */
class FilesystemStatementStorageTest {

    @TempDir
    Path root;

    private FilesystemStatementStorage storage() {
        return new FilesystemStatementStorage(root.toString());
    }

    private static byte[] pdf(String marker) {
        return ("%PDF-1.6\n" + marker).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void roundTripsContentUnchanged() {
        byte[] content = pdf("statement");
        FilesystemStatementStorage storage = storage();

        ContentAddress address = storage.store(content);

        assertThat(storage.retrieve(address)).isEqualTo(content);
        assertThat(storage.exists(address)).isTrue();
    }

    @Test
    void survivesBytesThatAreNotText() {
        // Statements are PDFs read as raw bytes. 0x00 and 0xff have to come back untouched, and a
        // signed-byte or encoding mistake would corrupt exactly these.
        byte[] content = new byte[256];
        for (int i = 0; i < 256; i++) content[i] = (byte) i;
        FilesystemStatementStorage storage = storage();

        assertThat(storage.retrieve(storage.store(content))).isEqualTo(content);
    }

    @Test
    void identicalContentGetsOneObject_whichIsTheWholePoint() {
        // The duplication this migration exists to fix: confirmMultiSection writes one copy per
        // account section, and every re-import writes another. Addressing by content collapses
        // them.
        byte[] content = pdf("composite statement");
        FilesystemStatementStorage storage = storage();

        ContentAddress first = storage.store(content);
        ContentAddress secondSection = storage.store(content);
        ContentAddress afterReimport = storage.store(content);

        assertThat(secondSection).isEqualTo(first);
        assertThat(afterReimport).isEqualTo(first);
        assertThat(filesUnder(root)).hasSize(1);
    }

    @Test
    void differentContentGetsDifferentObjects() {
        FilesystemStatementStorage storage = storage();

        ContentAddress january = storage.store(pdf("january"));
        ContentAddress february = storage.store(pdf("february"));

        assertThat(january.hash()).isNotEqualTo(february.hash());
        assertThat(january.key()).isNotEqualTo(february.key());
        assertThat(filesUnder(root)).hasSize(2);
    }

    @Test
    void storeIsIdempotentAndDoesNotRewriteAnExistingObject() throws IOException {
        byte[] content = pdf("statement");
        FilesystemStatementStorage storage = storage();
        ContentAddress address = storage.store(content);
        Path stored = root.resolve(address.key());
        var writtenAt = Files.getLastModifiedTime(stored);

        storage.store(content);

        // Re-storing after a partial failure has to be a no-op, not a second write -- that is what
        // makes the R2-first retry in the dual-write ordering safe.
        assertThat(Files.getLastModifiedTime(stored)).isEqualTo(writtenAt);
    }

    @Test
    void aMissingObjectFailsLoudlyRatherThanReturningNothing() {
        // Every caller arrives from a row asserting the object exists, so absence is a broken
        // invariant. Returning empty would let it propagate as a zero-row import rather than a
        // fault.
        ContentAddress neverStored = ContentAddress.forContent(pdf("never stored"));
        FilesystemStatementStorage storage = storage();

        assertThat(storage.exists(neverStored)).isFalse();
        assertThatThrownBy(() -> storage.retrieve(neverStored))
                .isInstanceOf(StatementStorageException.class)
                .hasMessageContaining(neverStored.hash());
    }

    @Test
    void leavesNoPartialFilesBehindOnSuccess() {
        FilesystemStatementStorage storage = storage();
        storage.store(pdf("statement"));

        // The write goes via a temp file and an atomic move, so a crash cannot leave a truncated
        // object sitting at an address that then reports present.
        assertThat(filesUnder(root)).noneMatch(p -> p.getFileName().toString().startsWith(".partial-"));
    }

    @Test
    void deleteRemovesTheObject() {
        FilesystemStatementStorage storage = storage();
        ContentAddress address = storage.store(pdf("statement"));
        assertThat(storage.exists(address)).isTrue();

        storage.delete(address.key());

        assertThat(storage.exists(address)).isFalse();
    }

    @Test
    void deleteIsIdempotentForAKeyThatIsAlreadyGone() {
        // Matches S3/R2's DeleteObject semantics -- a re-run of the sweep after a partial batch
        // failure must not throw on a key it already removed.
        FilesystemStatementStorage storage = storage();
        ContentAddress neverStored = ContentAddress.forContent(pdf("never stored"));

        assertThatCode(() -> storage.delete(neverStored.key())).doesNotThrowAnyException();
    }

    @Test
    void deleteRefusesAKeyThatEscapesTheStorageRoot() {
        assertThatThrownBy(() -> storage().delete("../../etc/passwd"))
                .isInstanceOf(StatementStorageException.class)
                .hasMessageContaining("escapes the storage root");
    }

    @Test
    void refusesAKeyThatEscapesTheStorageRoot() {
        // Unreachable via ContentAddress today, since keys are machine-generated. Asserted because
        // a later implementation reading keys back from the database would make them externally
        // influenced, and this is the check that stops that being a path traversal.
        ContentAddress escaping = new ContentAddress("deadbeef", "../../etc/passwd");

        assertThatThrownBy(() -> storage().retrieve(escaping))
                .isInstanceOf(StatementStorageException.class)
                .hasMessageContaining("escapes the storage root");
    }

    private static List<Path> filesUnder(Path dir) {
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
