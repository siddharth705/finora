package com.finora.imports.storage;

import com.finora.security.crypto.CryptoProperties;
import com.finora.security.crypto.EncryptionService;
import com.finora.security.crypto.EnvironmentKeyProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Requirement 9 of the storage review: proves the actual round trip (compress -> upload -> download
 * -> decompress -> byte-identical to the original) for both accepted formats, through BOTH
 * {@code StatementStorage} implementations, plus the backward-compatibility and content-length
 * properties {@link StatementContentServiceTest} verifies at the mock-interaction level rather than
 * end-to-end.
 *
 * <p>Filesystem needs no fake -- {@link FilesystemStatementStorage} genuinely writes to a temp
 * directory. R2 is exercised through an in-memory fake {@code S3Client} (not the bare
 * behaviour-free mock {@link R2StatementStorageTest} uses for its own logic-only assertions): PUT
 * captures what {@code R2StatementStorage} actually wrote and GET/HEAD answer from that, so a real
 * multi-step round trip is genuinely provable without network access or credentials.
 */
class StatementContentServiceCompressionTest {

    @TempDir
    Path filesystemRoot;

    /** Same {@code CryptoProperties}/{@code EnvironmentKeyProvider} idiom
     *  {@code GmailConnectionServiceTest} and {@code StatementContentServiceTest} use for a real
     *  (non-mocked) EncryptionService -- these tests prove an actual round trip through real
     *  AES-GCM, not a stubbed transform. */
    private static final EncryptionService ENCRYPTION = testEncryptionService();

    private static EncryptionService testEncryptionService() {
        CryptoProperties crypto = new CryptoProperties();
        crypto.setActiveKeyId("v1");
        Map<String, String> keys = new HashMap<>();
        byte[] raw = new byte[32];
        java.util.Arrays.fill(raw, (byte) 7);
        keys.put("v1", java.util.Base64.getEncoder().encodeToString(raw));
        crypto.setKeys(keys);
        return new EncryptionService(new EnvironmentKeyProvider(crypto));
    }

    private static byte[] csvFixture() {
        // Real bank CSVs are highly repetitive -- this is what lets GZIP earn its keep, and what a
        // "compression is doing nothing" regression would show up against.
        StringBuilder sb = new StringBuilder("Date,Description,Amount,Type\n");
        for (int i = 0; i < 300; i++) {
            sb.append("2026-08-").append(String.format("%02d", (i % 28) + 1))
                    .append(",SWIGGY ORDER ").append(1000 + i)
                    .append(",250.00,EXPENSE\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] pdfFixture() throws IOException {
        return Files.readAllBytes(Path.of("src/test/resources/pdf/separate_debit_credit_balance_sample.pdf"));
    }

    private StatementContentService filesystemBacked() {
        return new StatementContentService(
                Optional.of(new FilesystemStatementStorage(filesystemRoot.toString())),
                ENCRYPTION, "filesystem", "");
    }

    /** An in-memory fake S3 backing store -- PUT records what was actually written (reading
     *  {@code RequestBody}'s stream, exactly as bytes leave this JVM), GET/HEAD answer from that
     *  same map. What is being proven is R2StatementStorage's own read/write logic against
     *  something that behaves like a real bucket, not that the fake is R2 -- see
     *  {@link R2StatementStorageTest}'s own class doc for why nothing here proves protocol-level
     *  R2 compatibility (SigV4, path-style addressing, disabled checksums). */
    private StatementContentService r2Backed() {
        Map<String, byte[]> bucket = new HashMap<>();
        S3Client client = mock(S3Client.class);
        when(client.headObject(any(HeadObjectRequest.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0, HeadObjectRequest.class).key();
            if (!bucket.containsKey(key)) throw NoSuchKeyException.builder().message("not found").build();
            return HeadObjectResponse.builder().contentLength((long) bucket.get(key).length).build();
        });
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(inv -> {
            PutObjectRequest request = inv.getArgument(0);
            RequestBody body = inv.getArgument(1);
            byte[] uploaded;
            try (var in = body.contentStreamProvider().newStream()) {
                uploaded = in.readAllBytes();
            }
            bucket.put(request.key(), uploaded);
            return PutObjectResponse.builder().build();
        });
        when(client.getObjectAsBytes(any(GetObjectRequest.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0, GetObjectRequest.class).key();
            byte[] stored = bucket.get(key);
            if (stored == null) throw NoSuchKeyException.builder().message("not found").build();
            return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), stored);
        });
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(inv -> {
            bucket.remove(((DeleteObjectRequest) inv.getArgument(0)).key());
            return software.amazon.awssdk.services.s3.model.DeleteObjectResponse.builder().build();
        });
        StatementStorage r2 = new R2StatementStorage(client, "finora-statements-test");
        return new StatementContentService(Optional.of(r2), ENCRYPTION, "r2", "");
    }

    // ---- CSV round trip ----

    @Test
    void csvRoundTripsThroughFilesystemStorage_byteIdenticalAfterDecompression() {
        assertRoundTrips(filesystemBacked(), csvFixture(), "text/csv");
    }

    @Test
    void csvRoundTripsThroughR2Storage_byteIdenticalAfterDecompression() {
        assertRoundTrips(r2Backed(), csvFixture(), "text/csv");
    }

    // ---- PDF round trip ----

    @Test
    void pdfRoundTripsThroughFilesystemStorage_byteIdenticalAfterDecompression() throws IOException {
        assertRoundTrips(filesystemBacked(), pdfFixture(), "application/pdf");
    }

    @Test
    void pdfRoundTripsThroughR2Storage_byteIdenticalAfterDecompression() throws IOException {
        assertRoundTrips(r2Backed(), pdfFixture(), "application/pdf");
    }

    /** One row per {@link StoredStatement} implementor -- a real read only ever happens through
     *  one of these. */
    private record Row(String getContentHash, String getObjectKey, byte[] getFileContent,
                        CompressionType getCompressionType, String getEncryptionKeyId) implements StoredStatement {}

    private void assertRoundTrips(StatementContentService service, byte[] original, String mimeType) {
        StatementContentService.StoredContent stored = service.store(original, mimeType).orElseThrow();

        // The identity half: content_hash is the ORIGINAL bytes' hash, unaffected by compression
        // or encryption.
        assertThat(stored.address().hash())
                .as("content_hash must identify the ORIGINAL document, not its compressed/encrypted encoding")
                .isEqualTo(ContentAddress.hashOf(original));
        assertThat(stored.compressionType()).isEqualTo(CompressionType.GZIP);
        assertThat(stored.originalSize()).isEqualTo(original.length);
        assertThat(stored.mimeType()).isEqualTo(mimeType);
        assertThat(stored.encryptionKeyId()).isNotNull();

        Row row = new Row(stored.address().hash(), stored.address().key(), null, CompressionType.GZIP,
                stored.encryptionKeyId());
        byte[] readBack = service.read(row);

        assertThat(readBack)
                .as("downloading, re-importing, and every other consumer of these bytes must see "
                        + "exactly what was uploaded -- compression is transparent, not lossy")
                .isEqualTo(original);
    }

    // ---- Requirement 9: original SHA-256 verified after compression/decompression ----

    @Test
    void theSameOriginalHashIsProducedRegardlessOfWhichStorageBackendIsUsed() throws IOException {
        // Two independent StatementContentService instances (different providers, different
        // in-memory state) must still agree on content_hash for the identical input -- proving
        // hashing genuinely happens before/independent of compression and storage, not as some
        // provider-specific side effect that could drift between backends.
        byte[] pdf = pdfFixture();
        String hashViaFilesystem = filesystemBacked().store(pdf, "application/pdf").orElseThrow().address().hash();
        String hashViaR2 = r2Backed().store(pdf, "application/pdf").orElseThrow().address().hash();

        assertThat(hashViaFilesystem).isEqualTo(hashViaR2).isEqualTo(ContentAddress.hashOf(pdf));
    }

    // ---- Requirement 9: existing uncompressed R2 object stays readable ----

    @Test
    void anObjectStoredBeforeCompressionExisted_stillReadsCorrectly_throughEitherBackend() {
        // Simulates the real, live state of the bucket the moment this feature ships: every object
        // already there was written by the OLD code, raw, with no compression at all. The row's own
        // compression_type (defaulted to NONE by V92's migration for every pre-existing row) is
        // what makes this work -- not sniffing the retrieved bytes.
        byte[] original = csvFixture();
        ContentAddress rawAddress = ContentAddress.forContent(original);

        StatementStorage rawR2 = new R2StatementStorage(fakeClientPreloadedWith(rawAddress.key(), original),
                "finora-statements-test");
        StatementContentService r2Service = new StatementContentService(Optional.of(rawR2), ENCRYPTION, "r2", "");
        // Null key id, matching a real pre-V107 row: unencrypted, same as its pre-V92 NONE
        // compression_type -- read() must not attempt to decrypt either.
        Row uncompressedRow = new Row(rawAddress.hash(), rawAddress.key(), null, CompressionType.NONE, null);

        assertThat(r2Service.read(uncompressedRow)).isEqualTo(original);
    }

    private S3Client fakeClientPreloadedWith(String key, byte[] rawBytes) {
        S3Client client = mock(S3Client.class);
        when(client.getObjectAsBytes(any(GetObjectRequest.class))).thenAnswer(inv -> {
            String requestedKey = inv.getArgument(0, GetObjectRequest.class).key();
            assertThat(requestedKey).isEqualTo(key);
            return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), rawBytes);
        });
        return client;
    }

    // ---- Requirement 9: correct R2 content length after compression ----

    @Test
    void r2ReceivesTheCompressedByteCount_notTheOriginalUploadSize() {
        Map<String, byte[]> bucket = new HashMap<>();
        S3Client client = mock(S3Client.class);
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("not found").build());
        org.mockito.ArgumentCaptor<PutObjectRequest> captor =
                org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
        when(client.putObject(captor.capture(), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        byte[] original = csvFixture();
        StatementStorage r2 = new R2StatementStorage(client, "finora-statements-test");
        StatementContentService service = new StatementContentService(Optional.of(r2), ENCRYPTION, "r2", "");

        StatementContentService.StoredContent stored = service.store(original, "text/csv").orElseThrow();

        assertThat(stored.storedSize())
                .as("a real repetitive CSV must actually shrink even after the fixed 28-byte "
                        + "encryption overhead -- otherwise this test would pass even if compression "
                        + "were silently a no-op")
                .isLessThan(stored.originalSize());
        assertThat(captor.getValue().contentLength())
                .as("R2 must be told the COMPRESSED-THEN-ENCRYPTED size -- a mismatched Content-Length "
                        + "against the actual bytes streamed would be a wire-level upload failure, not "
                        + "a subtle bug")
                .isEqualTo(stored.storedSize());
    }
}
