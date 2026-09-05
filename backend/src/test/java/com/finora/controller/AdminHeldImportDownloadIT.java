package com.finora.controller;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.AuditLog;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.exception.ErrorCode;
import com.finora.imports.storage.ContentAddress;
import com.finora.imports.storage.StatementStorage;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /{jobId}/document} on the parser-gap queue -- mirrors {@code
 * AdminHeldStatementDownloadIT} exactly, one queue over. See that class's own doc for why this
 * needs a real, configured {@link StatementStorage} rather than {@code ImportJob.getFileContent()}
 * (BH-045: a job carries an address, never the bytes).
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-held-import-download-it"
})
class AdminHeldImportDownloadIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private StatementStorage storage;

    private static final byte[] PDF_BYTES = "%PDF-1.4 held-import fixture bytes".getBytes(StandardCharsets.UTF_8);

    private User createUser(String role) {
        User user = new User();
        user.setEmail("held-import-dl-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Held Import Download IT User");
        user.setRole(role);
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ImportJob heldJob() {
        User owner = createUser("USER");
        ContentAddress address = storage.store(PDF_BYTES);
        ImportJob job = new ImportJob(owner.getId(), "hdfc-june.pdf", address.hash(), address.key(), "PDF");
        job.markClaimed("worker", Instant.now());
        job.markClaimed("worker", Instant.now());
        job.recordFailure("IllegalStateException: no header row", "IllegalStateException",
                ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT, Instant.now());
        job.holdForReview("IllegalStateException", Instant.now());
        return importJobRepository.save(job);
    }

    private ResponseEntity<byte[]> download(UUID jobId, User admin) {
        return restTemplate.exchange("/api/v1/admin/held-imports/" + jobId + "/document",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), byte[].class);
    }

    @Test
    void downloadRefusesAnUnauthenticatedCaller() {
        ImportJob job = heldJob();

        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/api/v1/admin/held-imports/" + job.getId() + "/document",
                HttpMethod.GET, HttpEntity.EMPTY, byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void downloadRefusesAPlainUser() {
        ImportJob job = heldJob();
        User user = createUser("USER");

        ResponseEntity<byte[]> response = download(job.getId(), user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void everyDownloadIsAudited() {
        ImportJob job = heldJob();
        User admin = createUser("ADMIN");

        download(job.getId(), admin);

        List<AuditLog> entries = auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId());
        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry.getAction()).isEqualTo("HELD_IMPORT_DOWNLOADED");
            assertThat(entry.getUserId()).isEqualTo(admin.getId());
        });
    }

    @Test
    void anAdminGetsThePdfBytes() {
        ImportJob job = heldJob();
        User admin = createUser("ADMIN");

        ResponseEntity<byte[]> response = download(job.getId(), admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(PDF_BYTES);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
    }

    /**
     * Caught in review: an earlier version of this endpoint accepted any job id regardless of
     * status, since {@code AdminHeldImportService.download} only called {@code require}, not
     * {@code requireHeld}. Proven here against a real, still-live QUEUED job with real stored bytes
     * -- not just a unit test with a mocked repository -- so a regression that let the query still
     * find the job but skip the status check would still be caught.
     */
    @Test
    void downloadRefusesAJobThatIsNotHeld() {
        User owner = createUser("USER");
        ContentAddress address = storage.store(PDF_BYTES);
        ImportJob job = importJobRepository.save(
                new ImportJob(owner.getId(), "hdfc-june.pdf", address.hash(), address.key(), "PDF"));
        User admin = createUser("ADMIN");

        ResponseEntity<byte[]> response = download(job.getId(), admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void anUnknownJobIdIs404NotAnAttributeError() {
        User admin = createUser("ADMIN");

        ResponseEntity<byte[]> response = download(UUID.randomUUID(), admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
