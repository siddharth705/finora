package com.finora.imports.storage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
 */
class StatementContentServiceTest {

    private static final byte[] CONTENT = "%PDF-1.6 statement".getBytes(StandardCharsets.UTF_8);

    /** A row in whichever of the two states a test needs. */
    private record Row(String getContentHash, String getObjectKey, byte[] getFileContent) implements StoredStatement {
        static Row addressed(ContentAddress a, byte[] legacy) { return new Row(a.hash(), a.key(), legacy); }
        static Row legacy(byte[] bytes) { return new Row(null, null, bytes); }
    }

    @Test
    void withNoProviderConfigured_storeIsANoOpAndReadsComeFromTheColumn() {
        // The default. Adding this layer must not change behaviour for anyone who has not opted in.
        StatementContentService service = new StatementContentService(Optional.empty(), "");

        assertThat(service.store(CONTENT)).isEmpty();
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
        assertThatThrownBy(() -> new StatementContentService(Optional.empty(), "r2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("matches no StatementStorage implementation");
    }

    @Test
    void anUnsetProviderIsStillAFirstClassChoice_andDoesNotThrow() {
        // Unset is the supported default and must keep behaving exactly as before -- the check
        // above must fire only on a value someone actually typed.
        assertThat(new StatementContentService(Optional.empty(), "").store(CONTENT)).isEmpty();
        assertThat(new StatementContentService(Optional.empty(), null).store(CONTENT)).isEmpty();
        assertThat(new StatementContentService(Optional.empty(), "   ").store(CONTENT)).isEmpty();
    }

    @Test
    void withAProviderConfigured_storeReturnsTheAddressToRecord() {
        StatementStorage storage = mock(StatementStorage.class);
        ContentAddress address = ContentAddress.forContent(CONTENT);
        when(storage.store(CONTENT)).thenReturn(address);

        assertThat(new StatementContentService(Optional.of(storage), "filesystem").store(CONTENT)).contains(address);
    }

    @Test
    void anAddressedRowIsReadFromStorage_notFromTheColumn() {
        StatementStorage storage = mock(StatementStorage.class);
        ContentAddress address = ContentAddress.forContent(CONTENT);
        when(storage.retrieve(address)).thenReturn(CONTENT);
        // Deliberately different bytes in the column: if resolution silently preferred the legacy
        // copy, this assertion would still pass on content equality alone.
        Row row = Row.addressed(address, "stale database copy".getBytes(StandardCharsets.UTF_8));

        assertThat(new StatementContentService(Optional.of(storage), "filesystem").read(row)).isEqualTo(CONTENT);
        verify(storage).retrieve(address);
    }

    @Test
    void bytesThatDoNotMatchTheAddressAreRejected_notReturned() {
        // The failure content addressing exists to make impossible, and previously could not
        // detect: storage is up, the object is present and readable, and it is the WRONG document.
        // Bit-rot, a provider serving a stale or mismatched key, a collision after a layout change.
        // Returning these bytes would parse someone else's bank statement into this user's ledger,
        // which is strictly worse than failing the request.
        StatementStorage storage = mock(StatementStorage.class);
        ContentAddress address = ContentAddress.forContent(CONTENT);
        when(storage.retrieve(address)).thenReturn("a different statement entirely".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new StatementContentService(Optional.of(storage), "filesystem")
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
        when(storage.retrieve(address)).thenReturn("wrong".getBytes(StandardCharsets.UTF_8));
        StatementContentService service = new StatementContentService(Optional.of(storage), "filesystem");

        assertThatThrownBy(() -> service.read(Row.addressed(address, null)))
                .isInstanceOf(StatementIntegrityException.class)
                .isInstanceOf(StatementStorageException.class);
    }

    @Test
    void aLegacyRowIsReadFromTheColumnEvenWhenStorageIsConfigured() {
        // Every row predating Phase 2 is in this state until Phase 3 backfills it. Reaching for
        // storage with a null address would fail every one of them.
        StatementStorage storage = mock(StatementStorage.class);

        assertThat(new StatementContentService(Optional.of(storage), "filesystem").read(Row.legacy(CONTENT))).isEqualTo(CONTENT);
        verify(storage, never()).retrieve(any());
    }

    @Test
    void anAddressedRowWithStorageTurnedOffFailsLoudly() {
        // Rolling the provider back off after rows have been addressed. Their bytes are still in
        // the column during Phase 2, so this succeeds -- which is exactly what makes the rollback
        // safe, and why Phase 2 keeps writing the column.
        ContentAddress address = ContentAddress.forContent(CONTENT);
        StatementContentService service = new StatementContentService(Optional.empty(), "");

        assertThat(service.read(Row.addressed(address, CONTENT))).isEqualTo(CONTENT);
    }

    @Test
    void anAddressedRowWithNoColumnAndNoStorageIsAnError_notAnEmptyResult() {
        // The post-Phase-4 shape with the provider misconfigured. Returning empty here would
        // surface downstream as a statement that parsed to zero rows, which reads as a bad file
        // rather than a broken deployment.
        ContentAddress address = ContentAddress.forContent(CONTENT);
        StatementContentService service = new StatementContentService(Optional.empty(), "");

        assertThatThrownBy(() -> service.read(Row.addressed(address, null)))
                .isInstanceOf(StatementStorageException.class)
                .hasMessageContaining("no storage provider is configured");
    }

    @Test
    void aRowWithNeitherAddressNorBytesIsAnError() {
        assertThatThrownBy(() -> new StatementContentService(Optional.empty(), "").read(Row.legacy(null)))
                .isInstanceOf(StatementStorageException.class)
                .hasMessageContaining("neither stored content nor a content address");
    }

    @Test
    void aStorageFailureOnWritePropagates_soNoRowIsPersistedAgainstAMissingObject() {
        // The rule the whole ordering exists to enforce: if the object cannot be written, the
        // caller must not go on to persist a row referencing it. Degrading to empty here would
        // produce exactly that row.
        StatementStorage storage = mock(StatementStorage.class);
        when(storage.store(CONTENT)).thenThrow(new StatementStorageException("bucket unreachable"));

        assertThatThrownBy(() -> new StatementContentService(Optional.of(storage), "filesystem").store(CONTENT))
                .isInstanceOf(StatementStorageException.class);
    }
}
