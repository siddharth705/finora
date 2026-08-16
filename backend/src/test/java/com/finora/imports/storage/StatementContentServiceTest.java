package com.finora.imports.storage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Resolution during the migration: a row is either addressed (bytes in object storage) or legacy
 * (bytes in the column), and both are normal until Phase 4. This is the class that hides that from
 * the five call sites which read statement bytes.
 *
 * <p>Storage review (V92): {@link StatementContentService#store} now compresses before handing
 * bytes to {@code StatementStorage}. Tests below use {@link GzipCompression} directly (same
 * package, package-private) to construct what a real provider would have received/returned, rather
 * than asserting on the service's internal compression call with an opaque byte[] matcher.
 */
class StatementContentServiceTest {

    private static final byte[] CONTENT = "%PDF-1.6 statement".getBytes(StandardCharsets.UTF_8);
    private static final String MIME_TYPE = "application/pdf";

    /** A row in whichever of the two states a test needs. */
    private record Row(String getContentHash, String getObjectKey, byte[] getFileContent,
                        CompressionType getCompressionType) implements StoredStatement {
        static Row addressed(ContentAddress a, byte[] legacy) {
            return new Row(a.hash(), a.key(), legacy, CompressionType.GZIP);
        }
        static Row addressedUncompressed(ContentAddress a, byte[] legacy) {
            return new Row(a.hash(), a.key(), legacy, CompressionType.NONE);
        }
        static Row legacy(byte[] bytes) { return new Row(null, null, bytes, CompressionType.NONE); }
    }

    @Test
    void withNoProviderConfigured_storeIsANoOpAndReadsComeFromTheColumn() {
        // The default. Adding this layer must not change behaviour for anyone who has not opted in.
        StatementContentService service = new StatementContentService(Optional.empty(), "", "");

        assertThat(service.store(CONTENT, MIME_TYPE)).isEmpty();
        assertThat(service.read(Row.legacy(CONTENT))).isEqualTo(CONTENT);
    }

    /**
     * Bug fix: a provider name matching no implementation used to be indistinguishable from no
     * provider at all -- {@code STATEMENT_STORAGE_PROVIDER=r2} produced no bean, an empty Optional,
     * and an INFO line saying storage simply was not configured, while the deployment kept writing
     * every statement to the database and the operator believed the migration was running.
     */
    @Test
    void aProviderNameThatResolvedToNoBeanIsRejected_notTreatedAsDisabled() {
        assertThatThrownBy(() -> new StatementContentService(Optional.empty(), "r2", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("matches no StatementStorage implementation");
    }

    @Test
    void anUnsetProviderIsStillAFirstClassChoice_andDoesNotThrow() {
        // Unset is the supported default and must keep behaving exactly as before -- the check
        // above must fire only on a value someone actually typed.
        assertThat(new StatementContentService(Optional.empty(), "", "").store(CONTENT, MIME_TYPE)).isEmpty();
        assertThat(new StatementContentService(Optional.empty(), null, "").store(CONTENT, MIME_TYPE)).isEmpty();
        assertThat(new StatementContentService(Optional.empty(), "   ", "").store(CONTENT, MIME_TYPE)).isEmpty();
    }

    /**
     * The compression contract: content_hash identifies the ORIGINAL bytes (what a caller re-hashes
     * to verify this is the document the bank issued), independent of what the storage layer's own
     * address happens to be for the compressed representation actually written.
     */
    @Test
    void withAProviderConfigured_storeCompressesFirst_andReturnsTheOriginalHashWithSizesAndMimeType() {
        StatementStorage storage = mock(StatementStorage.class);
        byte[] compressed = GzipCompression.compress(CONTENT);
        ContentAddress backendAddress = ContentAddress.forContent(compressed);
        when(storage.store(any(byte[].class))).thenReturn(backendAddress);

        Optional<StatementContentService.StoredContent> result =
                new StatementContentService(Optional.of(storage), "filesystem", "").store(CONTENT, MIME_TYPE);

        assertThat(result).isPresent();
        StatementContentService.StoredContent stored = result.get();
        assertThat(stored.address().hash())
                .as("identity is the ORIGINAL bytes' hash, not the compressed bytes' hash")
                .isEqualTo(ContentAddress.hashOf(CONTENT));
        assertThat(stored.address().key()).isEqualTo(backendAddress.key());
        assertThat(stored.originalSize()).isEqualTo(CONTENT.length);
        assertThat(stored.storedSize()).isEqualTo(compressed.length);
        assertThat(stored.compressionType()).isEqualTo(CompressionType.GZIP);
        assertThat(stored.mimeType()).isEqualTo(MIME_TYPE);

        // What was actually handed to the storage layer was the COMPRESSED bytes, not the original.
        verify(storage).store(compressed);
    }

    @Test
    void anAddressedRowIsReadFromStorage_notFromTheColumn_andDecompressed() {
        StatementStorage storage = mock(StatementStorage.class);
        ContentAddress address = ContentAddress.forContent(CONTENT);
        byte[] compressed = GzipCompression.compress(CONTENT);
        when(storage.retrieve(address)).thenReturn(compressed);
        // Deliberately different bytes in the column: if resolution silently preferred the legacy
        // copy, this assertion would still pass on content equality alone.
        Row row = Row.addressed(address, "stale database copy".getBytes(StandardCharsets.UTF_8));

        assertThat(new StatementContentService(Optional.of(storage), "filesystem", "").read(row)).isEqualTo(CONTENT);
        verify(storage).retrieve(address);
    }

    /** Backward compatibility: an object written before compression existed
     *  (compression_type = NONE) must keep reading correctly -- no attempt to gunzip raw bytes. */
    @Test
    void anExistingUncompressedObjectStillReadsCorrectly() {
        StatementStorage storage = mock(StatementStorage.class);
        ContentAddress address = ContentAddress.forContent(CONTENT);
        when(storage.retrieve(address)).thenReturn(CONTENT);
        Row row = Row.addressedUncompressed(address, null);

        assertThat(new StatementContentService(Optional.of(storage), "filesystem", "").read(row)).isEqualTo(CONTENT);
    }

    @Test
    void bytesThatDoNotMatchTheAddressAreRejected_notReturned() {
        // The failure content addressing exists to make impossible, and previously could not
        // detect: storage is up, the object is present and readable, and it is the WRONG document.
        // Bit-rot, a provider serving a stale or mismatched key, a collision after a layout change.
        // Returning these bytes would parse someone else's bank statement into this user's ledger,
        // which is strictly worse than failing the request. The wrong bytes are still validly
        // gzipped (a real corrupted/mismatched object would be too) so this exercises the hash
        // check specifically, not a decompression failure.
        StatementStorage storage = mock(StatementStorage.class);
        ContentAddress address = ContentAddress.forContent(CONTENT);
        byte[] wrongButValidGzip = GzipCompression.compress("a different statement entirely".getBytes(StandardCharsets.UTF_8));
        when(storage.retrieve(address)).thenReturn(wrongButValidGzip);

        assertThatThrownBy(() -> new StatementContentService(Optional.of(storage), "filesystem", "")
                .read(Row.addressed(address, null)))
                .isInstanceOf(StatementIntegrityException.class)
                .hasMessageContaining("Integrity check failed")
                .hasMessageContaining(address.hash());
    }

    @Test
    void integrityFailureIsDistinguishableFromTheObjectSimplyBeingMissing() {
        // Both are StatementStorageException so no caller has to change, but they demand opposite
        // responses -- missing may resolve when a provider recovers, corrupt never will -- so the
        // types must be separable by anything that routes or alerts on them.
        StatementStorage storage = mock(StatementStorage.class);
        ContentAddress address = ContentAddress.forContent(CONTENT);
        when(storage.retrieve(address)).thenReturn(GzipCompression.compress("wrong".getBytes(StandardCharsets.UTF_8)));
        StatementContentService service = new StatementContentService(Optional.of(storage), "filesystem", "");

        assertThatThrownBy(() -> service.read(Row.addressed(address, null)))
                .isInstanceOf(StatementIntegrityException.class)
                .isInstanceOf(StatementStorageException.class);
    }

    @Test
    void aLegacyRowIsReadFromTheColumnEvenWhenStorageIsConfigured() {
        // Every row predating Phase 2 is in this state until Phase 3 backfills it. Reaching for
        // storage with a null address would fail every one of them.
        StatementStorage storage = mock(StatementStorage.class);

        assertThat(new StatementContentService(Optional.of(storage), "filesystem", "").read(Row.legacy(CONTENT))).isEqualTo(CONTENT);
        verify(storage, never()).retrieve(any());
    }

    @Test
    void anAddressedRowWithStorageTurnedOffFailsLoudly() {
        // Rolling the provider back off after rows have been addressed. Their bytes are still in
        // the column during Phase 2, so this succeeds -- which is exactly what makes the rollback
        // safe, and why Phase 2 keeps writing the column.
        ContentAddress address = ContentAddress.forContent(CONTENT);
        StatementContentService service = new StatementContentService(Optional.empty(), "", "");

        assertThat(service.read(Row.addressed(address, CONTENT))).isEqualTo(CONTENT);
    }

    @Test
    void anAddressedRowWithNoColumnAndNoStorageIsAnError_notAnEmptyResult() {
        // The post-Phase-4 shape with the provider misconfigured. Returning empty here would
        // surface downstream as a statement that parsed to zero rows, which reads as a bad file
        // rather than a broken deployment.
        ContentAddress address = ContentAddress.forContent(CONTENT);
        StatementContentService service = new StatementContentService(Optional.empty(), "", "");

        assertThatThrownBy(() -> service.read(Row.addressed(address, null)))
                .isInstanceOf(StatementStorageException.class)
                .hasMessageContaining("no storage provider is configured");
    }

    @Test
    void aRowWithNeitherAddressNorBytesIsAnError() {
        assertThatThrownBy(() -> new StatementContentService(Optional.empty(), "", "").read(Row.legacy(null)))
                .isInstanceOf(StatementStorageException.class)
                .hasMessageContaining("neither stored content nor a content address");
    }

    @Test
    void aStorageFailureOnWritePropagates_soNoRowIsPersistedAgainstAMissingObject() {
        // The rule the whole ordering exists to enforce: if the object cannot be written, the
        // caller must not go on to persist a row referencing it. Degrading to empty here would
        // produce exactly that row.
        StatementStorage storage = mock(StatementStorage.class);
        when(storage.store(any(byte[].class))).thenThrow(new StatementStorageException("bucket unreachable"));

        assertThatThrownBy(() -> new StatementContentService(Optional.of(storage), "filesystem", "").store(CONTENT, MIME_TYPE))
                .isInstanceOf(StatementStorageException.class);
    }

    @Test
    void theNearMissEnvironmentVariableNameIsRefusedRatherThanIgnored() {
        // STORAGE_PROVIDER is the name people reach for and nothing reads it. Set on its own it
        // produces the one failure no error surfaces: object storage stays off, every import
        // succeeds, health is green, and the bucket stays empty until somebody looks.
        assertThatThrownBy(() -> new StatementContentService(Optional.empty(), "", "r2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STATEMENT_STORAGE_PROVIDER");
    }

    @Test
    void theNearMissIsIgnoredOnceTheRealVariableIsSet() {
        // Both set is not a misconfiguration -- the real one wins and there is nothing to warn
        // about. Failing here would punish anyone who set the wrong name, read the error, added
        // the right one, and did not think to remove the first.
        StatementStorage storage = mock(StatementStorage.class);
        assertThatCode(() -> new StatementContentService(Optional.of(storage), "filesystem", "r2"))
                .doesNotThrowAnyException();
    }

    @Test
    void nothingIsRefusedWhenNeitherVariableIsSet() {
        assertThatCode(() -> new StatementContentService(Optional.empty(), "", ""))
                .doesNotThrowAnyException();
    }
}
