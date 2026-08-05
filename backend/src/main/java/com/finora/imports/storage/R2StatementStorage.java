package com.finora.imports.storage;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;

/**
 * Statements in a Cloudflare R2 bucket.
 *
 * <p>The production provider, and the one that removes the single-instance precondition
 * {@link FilesystemStatementStorage} carries: Railway's container filesystem is ephemeral and
 * per-instance, so a statement written by one container is missing after the next deploy and
 * invisible to any second instance. An object store is the only durable answer.
 *
 * <p>R2 is addressed through the AWS S3 SDK because R2 implements the S3 API. Three things about
 * that are R2-specific and are the whole reason this class is not just an S3 client with a
 * different URL:
 *
 * <ul>
 *   <li><b>Region is the literal string {@code auto}.</b> R2 is not regional in the S3 sense. The
 *       SDK still requires a region because it feeds SigV4 signing, and any other value produces a
 *       signature R2 rejects with an unhelpful 401.</li>
 *   <li><b>Path-style addressing.</b> Virtual-host style would put the bucket in the hostname,
 *       which needs DNS that only exists for buckets with a custom domain configured.</li>
 *   <li><b>Checksums are opt-in, not automatic.</b> AWS SDK 2.30 began sending
 *       {@code x-amz-checksum-crc32} on every upload by default and validating checksums on every
 *       download. R2 does not implement that flavour of the protocol consistently, and the symptom
 *       is not a clear "unsupported" error but a signature or integrity failure that reads like bad
 *       credentials. Both are set to {@code WHEN_REQUIRED}.</li>
 * </ul>
 *
 * <p>Losing the SDK's checksum does not weaken the integrity guarantee, which is the reason it is
 * safe to turn off: this system verifies SHA-256 over the bytes on every read
 * ({@link ContentAddress#requireMatches}), against a hash recorded in the database rather than one
 * supplied by the same service that stored the object. That is a strictly stronger check than a
 * transport CRC, and it is the one that catches a provider returning the wrong object.
 */
@Component
@ConditionalOnProperty(name = "app.statement-storage.provider", havingValue = "r2")
public class R2StatementStorage implements StatementStorage {

    private static final Logger log = LoggerFactory.getLogger(R2StatementStorage.class);

    /** Opaque on purpose: the key carries no filename, extension or user-derived text. */
    private static final String CONTENT_TYPE = "application/octet-stream";

    private final S3Client client;
    private final String bucket;

    // @Autowired is required, not decorative: the package-private test constructor below means
    // this class has two, and Spring will not guess between them -- it falls back to looking for a
    // no-arg constructor and fails with "No default constructor found", which names neither the
    // real constructor nor the ambiguity. That failure only appears with provider=r2, so it would
    // have reached production as a container that refuses to start.
    @Autowired
    public R2StatementStorage(
            @Value("${app.statement-storage.r2.account-id:}") String accountId,
            @Value("${app.statement-storage.r2.bucket:}") String bucket,
            @Value("${app.statement-storage.r2.access-key-id:}") String accessKeyId,
            @Value("${app.statement-storage.r2.secret-access-key:}") String secretAccessKey,
            @Value("${app.statement-storage.r2.endpoint:}") String endpoint) {

        // Fail at startup, not at the first upload. Same reasoning as StatementContentService's
        // unknown-provider check: a storage misconfiguration that only surfaces when a user
        // imports a statement is one a deploy looks healthy through.
        requirePresent(accountId, "account-id", "R2_ACCOUNT_ID");
        requirePresent(bucket, "bucket", "R2_BUCKET");
        requirePresent(accessKeyId, "access-key-id", "R2_ACCESS_KEY_ID");
        requirePresent(secretAccessKey, "secret-access-key", "R2_SECRET_ACCESS_KEY");

        this.bucket = bucket;
        this.client = buildClient(resolveEndpoint(endpoint, accountId), accessKeyId,
                secretAccessKey);

        // Bucket only. Never the keys, and never the account id -- it is the storage hostname, and
        // there is no reason for it to reach a log aggregator.
        log.info("Statement storage: Cloudflare R2, bucket '{}'", bucket);
    }

    /**
     * Test seam. The store/dedupe/missing-object semantics below are ordinary logic with real
     * failure modes and deserve tests, but building the real client needs credentials and a
     * network. This constructor lets those semantics be exercised against a stub client; it does
     * not stub the S3 protocol itself, which only a real bucket can prove.
     */
    R2StatementStorage(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    /**
     * The configured endpoint, or the standard one derived from the account id.
     *
     * <p>Derivation covers the normal case, so the usual deployment sets four variables and not
     * five. The override exists for the case derivation gets wrong: a bucket created with a
     * jurisdiction restriction lives at {@code https://<account>.eu.r2.cloudflarestorage.com} (or
     * {@code .fedramp.}), and the derived URL would then point at a bucket that does not exist,
     * failing as an auth error rather than as a missing bucket.
     *
     * <p>Validated rather than passed through, because the failure it prevents is silent. A
     * malformed or {@code http://} endpoint would otherwise surface as a connection or signature
     * error at the first upload, long after the deploy that introduced it, naming nothing that
     * points back at the variable.
     */
    static URI resolveEndpoint(String configured, String accountId) {
        if (configured == null || configured.isBlank()) {
            return URI.create("https://" + accountId + ".r2.cloudflarestorage.com");
        }
        URI uri;
        try {
            uri = new URI(configured.trim());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(
                    "app.statement-storage.r2.endpoint (environment variable R2_ENDPOINT) is not a "
                    + "valid URL: \"" + configured + "\". Copy the S3 API URL from the Cloudflare "
                    + "R2 dashboard, or leave it unset to derive it from the account id.", e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalStateException(
                    "app.statement-storage.r2.endpoint (environment variable R2_ENDPOINT) must be "
                    + "an absolute https URL with a host, e.g. "
                    + "https://<account-id>.r2.cloudflarestorage.com -- got \"" + configured
                    + "\". Statements are financial documents and must not travel over plaintext "
                    + "http.");
        }
        return uri;
    }

    private static S3Client buildClient(URI endpoint, String accessKeyId,
                                        String secretAccessKey) {
        return S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(ApacheHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(10))
                        .socketTimeout(Duration.ofSeconds(60)))
                .build();
    }

    private static void requirePresent(String value, String property, String envVar) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "app.statement-storage.provider is 'r2' but app.statement-storage.r2." + property
                    + " is not set (environment variable " + envVar + "). Statements would have "
                    + "nowhere durable to go, so the application refuses to start rather than "
                    + "accepting uploads it cannot store.");
        }
    }

    @Override
    public ContentAddress store(byte[] content) {
        ContentAddress address = ContentAddress.forContent(content);

        // Content-addressed, so identical bytes are already at this exact key and re-uploading
        // them is pure cost. This is also what makes a retry after a partial failure a no-op
        // rather than a duplicate, and what deduplicates a session against the import it becomes.
        if (exists(address)) return address;

        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(address.key())
                            .contentType(CONTENT_TYPE)
                            .contentLength((long) content.length)
                            .build(),
                    RequestBody.fromBytes(content));
        } catch (SdkException e) {
            // The message deliberately carries the key and never the content. A caller that sees
            // this must not persist a row: a row pointing at an object that was never written is
            // the one failure this design cannot recover from.
            throw new StatementStorageException(
                    "Could not store statement content at " + address.key() + " in R2 bucket '"
                    + bucket + "'. The row must not be saved.", e);
        }
        return address;
    }

    @Override
    public byte[] retrieve(ContentAddress address) {
        try {
            return client.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(address.key())
                            .build())
                    .asByteArray();
        } catch (NoSuchKeyException e) {
            throw new StatementStorageException(
                    "Statement object " + address.key() + " is missing from R2 bucket '" + bucket
                    + "'. A row references content that is not there.", e);
        } catch (SdkException e) {
            throw new StatementStorageException(
                    "Could not read statement object " + address.key() + " from R2 bucket '"
                    + bucket + "'.", e);
        }
    }

    @Override
    public boolean exists(ContentAddress address) {
        try {
            client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(address.key())
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            // HeadObject cannot return a body, so R2 reports a missing key as a bare 404 that the
            // SDK sometimes surfaces as a generic S3Exception rather than NoSuchKeyException.
            // Treating any other status as "absent" would be wrong in the dangerous direction:
            // store() would then re-upload on a transient 500, and worse, a permissions failure
            // would read as "not stored yet" forever.
            if (e.statusCode() == 404) return false;
            throw new StatementStorageException(
                    "Could not determine whether statement object " + address.key()
                    + " exists in R2 bucket '" + bucket + "'.", e);
        } catch (SdkException e) {
            throw new StatementStorageException(
                    "Could not determine whether statement object " + address.key()
                    + " exists in R2 bucket '" + bucket + "'.", e);
        }
    }

    @PreDestroy
    void close() {
        client.close();
    }
}
