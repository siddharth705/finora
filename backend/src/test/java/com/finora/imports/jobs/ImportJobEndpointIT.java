package com.finora.imports.jobs;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The asynchronous upload path, end to end.
 *
 * <p>Storage is switched on for this class explicitly. It is NOT on by default -- with no provider
 * configured this endpoint returns 503 by design, because the worker runs later and has nothing to
 * read but a content address. That is asserted in {@code ImportJobStorageRequiredIT}, which is a
 * separate class precisely because it needs a context without storage.
 *
 * <p>The queue poller is off so the test drives the worker itself. With it running, a job could be
 * claimed and completed between the 202 and the first poll, and an assertion about QUEUED would
 * fail intermittently -- the flake shape that cost this milestone a debugging round already.
 */
@TestPropertySource(properties = {
        // Storage must be ON for this path to be reachable at all: the worker runs later and has
        // nothing to read but a content address. Configured here rather than assumed -- main's
        // AbstractIntegrationTest does NOT enable storage (that change lives on the parked V55
        // branch), so this IT's first run hit the 503 guard, which was the guard working.
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-import-job-it",
        "app.import.queue.enabled=false"
})
class ImportJobEndpointIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private ImportJobRepository jobRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User user() {
        User user = new User();
        user.setEmail("import-job-endpoint-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Import Job Endpoint IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    private HttpEntity<MultiValueMap<String, Object>> upload(User user, String fileName, String csv) {
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return fileName; }
        });
        return new HttpEntity<>(body, headers);
    }

    private static final String CSV = """
            Date,Description,Amount,Type
            2026-07-10,SWIGGY ORDER,486.00,DEBIT
            2026-07-11,BLINKIT GROCERIES,1240.50,DEBIT
            """;

    @Test
    void anUploadIsAcceptedWithSomewhereToPoll() {
        User user = user();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.POST, upload(user, "statement.csv", CSV), String.class);

        assertThat(response.getStatusCode())
                .as("202, not 200 -- the work has not happened yet and the response must not imply it has")
                .isEqualTo(HttpStatus.ACCEPTED);

        JsonNode data = read(response).get("data");
        assertThat(data.get("jobId").asText()).isNotBlank();
        assertThat(data.get("statusUrl").asText())
                .as("returned rather than left for the client to construct, so the route can move")
                .isEqualTo("/api/v1/import/jobs/" + data.get("jobId").asText());
    }

    @Test
    void theJobIsDurableBeforeTheResponseIsSent() {
        // The point of the whole design: if the process died right now, the work would still happen.
        User user = user();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.POST, upload(user, "statement.csv", CSV), String.class);
        UUID jobId = UUID.fromString(read(response).get("data").get("jobId").asText());

        ImportJob job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.QUEUED);
        assertThat(job.getContentHash())
                .as("without an address the worker has nothing to read and the job can never run")
                .isNotBlank();
        assertThat(job.getObjectKey()).isNotBlank();
    }

    @Test
    void progressIsReadableByTheOwner() {
        User user = user();
        ResponseEntity<String> accepted = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.POST, upload(user, "statement.csv", CSV), String.class);
        String jobId = read(accepted).get("data").get("jobId").asText();

        ResponseEntity<String> progress = restTemplate.exchange(
                "/api/v1/import/jobs/" + jobId, HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(progress.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = read(progress).get("data");
        assertThat(data.get("status").asText()).isEqualTo("QUEUED");
        assertThat(data.get("rowsTotal").isNull())
                .as("null until PARSING has counted -- 0 would look like an empty file")
                .isTrue();
    }

    @Test
    void anotherUsersJobIsNotReadable() {
        // A job id alone must never be enough to read someone else's import. 404 rather than 403
        // so the response does not confirm the id exists.
        User owner = user();
        User stranger = user();
        ResponseEntity<String> accepted = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.POST, upload(owner, "statement.csv", CSV), String.class);
        String jobId = read(accepted).get("data").get("jobId").asText();

        ResponseEntity<String> asStranger = restTemplate.exchange(
                "/api/v1/import/jobs/" + jobId, HttpMethod.GET,
                new HttpEntity<>(bearerFor(stranger)), String.class);

        assertThat(asStranger.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anonymousUploadIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(CSV.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "statement.csv"; }
        });

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
    }

    @Test
    void aPdfPostedWithACsvNameIsRejectedAtUploadTime() {
        // The endpoint validates against the same rule the worker parses with, so a mismatch fails
        // while the user is still at the upload dialog rather than minutes later in a job status.
        User user = user();
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource("%PDF-1.6 definitely a pdf".getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "statement.csv"; }
        });

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(jobRepository.findByUserIdOrderByCreatedAtDesc(
                user.getId(), org.springframework.data.domain.PageRequest.of(0, 10)))
                .as("a rejected upload must not leave a job behind")
                .isEmpty();
    }

    @Test
    void theWorkerRunsAQueuedJobToCompletion() {
        // The end-to-end proof: 202, then the worker picks it up and the job reaches a terminal
        // state with the rows it parsed recorded.
        User user = user();
        ResponseEntity<String> accepted = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.POST, upload(user, "statement.csv", CSV), String.class);
        UUID jobId = UUID.fromString(read(accepted).get("data").get("jobId").asText());

        worker.drainOnce();

        ImportJob job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus())
                .as("last error: %s", job.getLastError())
                .isEqualTo(ImportJob.Status.COMPLETED);
        assertThat(job.getRowsTotal()).isEqualTo(2);
        assertThat(job.getCorrelationId())
                .as("ties the job to the worker pass's logs and audit rows")
                .startsWith("worker-");
    }

    @Autowired private ImportJobWorker worker;

    @Test
    void recentListsTheCallersOwnJobsOnly() {
        User mine = user();
        User theirs = user();
        restTemplate.exchange("/api/v1/import/jobs", HttpMethod.POST,
                upload(mine, "mine.csv", CSV), String.class);
        restTemplate.exchange("/api/v1/import/jobs", HttpMethod.POST,
                upload(theirs, "theirs.csv", CSV), String.class);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.GET, new HttpEntity<>(bearerFor(mine)), String.class);

        JsonNode data = read(response).get("data");
        assertThat(data).hasSize(1);
    }

    private JsonNode read(ResponseEntity<String> response) {
        try {
            return mapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("unreadable response: " + response.getBody(), e);
        }
    }
}
