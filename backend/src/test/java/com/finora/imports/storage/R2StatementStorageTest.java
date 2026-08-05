package com.finora.imports.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The parts of the R2 provider that are logic rather than protocol.
 *
 * <p>Explicitly NOT a test that R2 works -- a stubbed {@link S3Client} proves only that the stub
 * behaves, and every genuinely R2-specific thing this class does (the {@code auto} region, path
 * style addressing, disabled SDK checksums) is invisible from here and provable only against a real
 * bucket. What is worth testing offline is what this class decides: when it skips an upload, and
 * how it maps an object store's answers onto the contract callers depend on.
 */
class R2StatementStorageTest {

    private static final byte[] CONTENT = "statement bytes".getBytes(StandardCharsets.UTF_8);

    private S3Client client;
    private R2StatementStorage storage;

    @BeforeEach
    void setUp() {
        client = mock(S3Client.class);
        storage = new R2StatementStorage(client, "finora-statements");
    }

    private void objectIsAbsent() {
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("not found").build());
    }

    private static S3Exception s3ExceptionWithStatus(int status) {
        return (S3Exception) S3Exception.builder()
                .statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("Test").build())
                .message("status " + status)
                .build();
    }

    @Test
    void storeUploadsWhenTheObjectIsNotThereYet() {
        objectIsAbsent();

        ContentAddress address = storage.store(CONTENT);

        assertThat(address.hash()).isEqualTo(ContentAddress.hashOf(CONTENT));
        verify(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void storeSkipsTheUploadWhenIdenticalContentIsAlreadyStored() {
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength((long) CONTENT.length).build());

        ContentAddress address = storage.store(CONTENT);

        // The dedupe that content-addressing exists for: a staged session and the import it
        // confirms into hold identical bytes, so they resolve to one address and one object. It is
        // also what makes a retry after a partial failure a no-op instead of a second upload.
        assertThat(address.hash()).isEqualTo(ContentAddress.hashOf(CONTENT));
        verify(client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void aFailedUploadThrowsRatherThanReturningAnAddressNothingIsStoredAt() {
        objectIsAbsent();
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(s3ExceptionWithStatus(500));

        // The single most damaging thing this class could do is return an address for content it
        // failed to write: the caller would persist a row pointing at nothing, which is the one
        // failure the design calls unrecoverable.
        assertThatThrownBy(() -> storage.store(CONTENT))
                .isInstanceOf(StatementStorageException.class)
                .hasMessageContaining("must not be saved");
    }

    @Test
    void aMissingObjectOnReadIsReportedAsAStorageFailure() {
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("not found").build());

        assertThatThrownBy(() -> storage.retrieve(ContentAddress.forContent(CONTENT)))
                .isInstanceOf(StatementStorageException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void retrieveReturnsTheStoredBytes() {
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), CONTENT));

        assertThat(storage.retrieve(ContentAddress.forContent(CONTENT))).isEqualTo(CONTENT);
    }

    @Test
    void a404FromHeadObjectMeansAbsent() {
        // R2 answers a missing key on HeadObject with a bare 404 and no body, which the SDK cannot
        // always resolve into NoSuchKeyException because there is no error document to parse.
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(s3ExceptionWithStatus(404));

        assertThat(storage.exists(ContentAddress.forContent(CONTENT))).isFalse();
    }

    @Test
    void anAuthFailureIsNotSilentlyTreatedAsAbsent() {
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(s3ExceptionWithStatus(403));

        // Mapping every error to "absent" would fail in the dangerous direction. A wrong or expired
        // API token would read as "nothing is stored yet" forever: every store() would re-upload
        // and every read would report a missing object, with no error naming the credentials.
        assertThatThrownBy(() -> storage.exists(ContentAddress.forContent(CONTENT)))
                .isInstanceOf(StatementStorageException.class);
    }

    @Test
    void aTransientServerErrorOnExistsIsNotTreatedAsAbsentEither() {
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(s3ExceptionWithStatus(503));

        assertThatThrownBy(() -> storage.exists(ContentAddress.forContent(CONTENT)))
                .isInstanceOf(StatementStorageException.class);
    }
}
