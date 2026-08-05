package com.finora.imports.storage;

import com.finora.entity.StatementImport;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.StatementImportRepository;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The backfill reads each object back after writing it, and refuses the row if what comes back is
 * not what it stored.
 *
 * <p>This is the only moment the check is possible. Phase 4 drops {@code file_content} on the
 * strength of these rows being addressed; once it has, the database copy that makes a comparison
 * meaningful is gone, and a mis-stored object becomes both undetectable and unrecoverable. Phase 3
 * is therefore the last point where "this row is addressed" can be an observation rather than an
 * assumption.
 *
 * <p>The case that matters most is the deduplicated one. For the majority of rows {@code store()}
 * writes nothing, because an identical object is already present from an earlier row -- so the
 * address being recorded points at bytes THIS row never verified. Without the read-back the
 * backfill would attest to content it had not looked at.
 */
class StatementBackfillWorkerIntegrityTest {

    private static final byte[] CONTENT = "%PDF-1.6 the real statement".getBytes(StandardCharsets.UTF_8);

    private StatementBackfillWorker workerWith(StatementStorage storage, StatementImport row) {
        StatementImportRepository imports = mock(StatementImportRepository.class);
        when(imports.findByIdIncludingDeleted(any())).thenReturn(Optional.of(row));
        return new StatementBackfillWorker(imports, mock(ImportSessionRepository.class), Optional.of(storage));
    }

    private StatementImport rowWithContent(byte[] content) {
        StatementImport row = new StatementImport();
        row.setFileContent(content);
        return row;
    }

    @Test
    void storedObjectIsReadBackAndAccepted_whenItMatches() {
        ContentAddress address = ContentAddress.forContent(CONTENT);
        StatementStorage storage = mock(StatementStorage.class);
        when(storage.exists(any())).thenReturn(false);
        when(storage.store(any())).thenReturn(address);
        when(storage.retrieve(address)).thenReturn(CONTENT);
        StatementImport row = rowWithContent(CONTENT);

        assertThat(workerWith(storage, row).addressStatementImport(UUID.randomUUID())).isTrue();

        verify(storage).retrieve(address);
        assertThat(row.getContentHash()).isEqualTo(address.hash());
    }

    @Test
    void aMisStoredObjectFailsTheRow_andNoAddressIsRecorded() {
        // The row must be left un-addressed so the next batch retries it. Recording the address
        // anyway would mark this row migrated, and Phase 4 would then delete the only good copy.
        ContentAddress address = ContentAddress.forContent(CONTENT);
        StatementStorage storage = mock(StatementStorage.class);
        when(storage.exists(any())).thenReturn(false);
        when(storage.store(any())).thenReturn(address);
        when(storage.retrieve(address)).thenReturn("truncated".getBytes(StandardCharsets.UTF_8));
        StatementImport row = rowWithContent(CONTENT);

        assertThatThrownBy(() -> workerWith(storage, row).addressStatementImport(UUID.randomUUID()))
                .isInstanceOf(StatementIntegrityException.class);

        assertThat(row.getContentHash()).isNull();
        assertThat(row.getObjectKey()).isNull();
    }

    @Test
    void anAlreadyPresentObjectIsVerifiedToo_notTrustedBecauseAnEarlierRowStoredIt() {
        // Deduplication means store() is a no-op here and the object came from some earlier row.
        // That earlier row's write was verified when IT ran, but nothing guarantees the object is
        // still intact now -- and this row is about to be marked migrated on the strength of it.
        ContentAddress address = ContentAddress.forContent(CONTENT);
        StatementStorage storage = mock(StatementStorage.class);
        when(storage.exists(any())).thenReturn(true);
        when(storage.store(any())).thenReturn(address);
        when(storage.retrieve(address)).thenReturn("rotted since it was written".getBytes(StandardCharsets.UTF_8));
        StatementImport row = rowWithContent(CONTENT);

        assertThatThrownBy(() -> workerWith(storage, row).addressStatementImport(UUID.randomUUID()))
                .isInstanceOf(StatementIntegrityException.class);

        assertThat(row.getContentHash()).isNull();
    }

    @Test
    void anAlreadyAddressedRowIsSkippedWithoutTouchingStorage() {
        // Addressed by a concurrent batch. Pre-existing behaviour, asserted here so the read-back
        // is not accidentally made to run on rows the worker is supposed to leave alone.
        StatementStorage storage = mock(StatementStorage.class);
        StatementImport row = rowWithContent(CONTENT);
        row.setContentHash(ContentAddress.forContent(CONTENT).hash());

        assertThat(workerWith(storage, row).addressStatementImport(UUID.randomUUID())).isFalse();

        verify(storage, never()).retrieve(any());
        verify(storage, never()).store(any());
    }
}
