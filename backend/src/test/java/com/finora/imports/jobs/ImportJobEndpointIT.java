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

import org.springframework.data.domain.PageRequest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
        "app.import.queue.enabled=false",
        // BH-011 put /api/v1/import/jobs behind importStageLimiter, which is 10 per 10 minutes per
        // IP -- and this class uploads well past that from one loopback address. The limiter is
        // working; the ceiling is simply not what this class is here to test.
        //
        // Raised rather than switched off, and raised HERE rather than in application-test.yml, for
        // the reason CI already raises it for the e2e stack: a limit that cannot be lifted for a
        // test makes the system unverifiable, but lifting it globally would mean no integration
        // context ever runs with the shipped value. That the endpoint is in the limiter's table at
        // all is asserted by RateLimitFilterTest.everyEndpointWithARealPerCallCostIsLimited, which
        // is where that guard belongs -- it is a property of the filter, not of this flow.
        "app.rate-limit.import-stage.max=10000"
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

    /**
     * The handoff the whole progress endpoint exists to enable.
     *
     * <p>The worker used to call {@code parseAndStageAnyFormat}, which persists nothing, and then
     * complete with a null session id. So a job reached COMPLETED carrying a row count, every staged
     * row was discarded with the response object, and a client that polled to COMPLETED had nowhere
     * to send the user. The rows are asserted through the public session endpoint rather than the
     * repository, because "the client can now reach them" is the actual claim.
     */
    @Test
    void aCompletedJobPointsAtStagedRowsTheUserCanReview() {
        User user = user();
        ResponseEntity<String> accepted = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.POST, upload(user, "statement.csv", CSV), String.class);
        UUID jobId = UUID.fromString(read(accepted).get("data").get("jobId").asText());

        worker.drainOnce();

        ResponseEntity<String> progress = restTemplate.exchange(
                "/api/v1/import/jobs/" + jobId, HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class);
        JsonNode data = read(progress).get("data");
        assertThat(data.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(data.get("importSessionId").isNull())
                .as("a completed import with no session is a progress bar that ends nowhere")
                .isFalse();

        String sessionId = data.get("importSessionId").asText();
        ResponseEntity<String> session = restTemplate.exchange(
                "/api/v1/import/sessions/" + sessionId, HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(session.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read(session).get("data").get("staging").get("rows"))
                .as("the rows the worker staged, reachable by the client that polled the job")
                .hasSize(2);
    }

    /**
     * Cancelling is only worth having if it actually stops the work. Asserted by cancelling and then
     * running the worker: claims only look at QUEUED, so a cancelled job is invisible to them, and
     * the drain must leave the row exactly as the user left it.
     */
    @Test
    void aCancelledJobNeverRuns() {
        User user = user();
        ResponseEntity<String> accepted = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.POST, upload(user, "statement.csv", CSV), String.class);
        UUID jobId = UUID.fromString(read(accepted).get("data").get("jobId").asText());

        ResponseEntity<String> cancelled = restTemplate.exchange(
                "/api/v1/import/jobs/" + jobId + "/cancel", HttpMethod.POST,
                new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read(cancelled).get("data").get("status").asText())
                .as("returned rather than 204, so the client renders from this instead of racing its own next poll")
                .isEqualTo("CANCELLED");

        worker.drainOnce();

        ImportJob job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.CANCELLED);
        assertThat(job.getImportSessionId())
                .as("a cancelled import must not leave staged rows waiting to be reviewed")
                .isNull();
    }

    /** A double-click, or a retry of a request whose response was lost, reports the state the user
     *  asked for rather than an error about having asked twice. */
    @Test
    void cancellingTwiceIsNotAnError() {
        User user = user();
        ResponseEntity<String> accepted = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.POST, upload(user, "statement.csv", CSV), String.class);
        String jobId = read(accepted).get("data").get("jobId").asText();
        String url = "/api/v1/import/jobs/" + jobId + "/cancel";

        restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(bearerFor(user)), String.class);
        ResponseEntity<String> again = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read(again).get("data").get("status").asText()).isEqualTo("CANCELLED");
    }

    /**
     * "Already finished" and "cancelled" are different outcomes, and a UI that cannot tell them
     * apart will claim the wrong one to the user. 409 with a message naming the state, rather than a
     * silent no-op that reports success for something that did not happen.
     */
    @Test
    void aFinishedImportCannotBeCancelled() {
        User user = user();
        ResponseEntity<String> accepted = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.POST, upload(user, "statement.csv", CSV), String.class);
        UUID jobId = UUID.fromString(read(accepted).get("data").get("jobId").asText());
        worker.drainOnce();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/import/jobs/" + jobId + "/cancel", HttpMethod.POST,
                new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .as("a refused cancel must leave the job alone, not half-cancel it")
                .isEqualTo(ImportJob.Status.COMPLETED);
    }

    /** Same ownership rule as reading: a job id alone must never be enough to act on someone else's
     *  import, and 404 rather than 403 so the response does not confirm the id exists. */
    @Test
    void anotherUsersJobCannotBeCancelled() {
        User owner = user();
        User stranger = user();
        ResponseEntity<String> accepted = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.POST, upload(owner, "statement.csv", CSV), String.class);
        UUID jobId = UUID.fromString(read(accepted).get("data").get("jobId").asText());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/import/jobs/" + jobId + "/cancel", HttpMethod.POST,
                new HttpEntity<>(bearerFor(stranger)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(ImportJob.Status.QUEUED);
    }

    private JsonNode read(ResponseEntity<String> response) {
        try {
            return mapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("unreadable response: " + response.getBody(), e);
        }
    }

    /**
     * BH-019. Two POSTs of the same document must not become two jobs.
     *
     * <p>V67 made REPLAY of one job safe and said nothing about the same bytes being SUBMITTED
     * twice -- a double-clicked upload, or a client retrying a request whose 202 was lost. That
     * produced two jobs, two staged sessions, and a statement imported twice if the user confirmed
     * both. The second call now gets the SAME jobId back, so its poll follows work that is already
     * happening rather than racing a duplicate of it.
     */
    @Test
    void submittingTheSameDocumentTwiceReturnsTheJobAlreadyQueuedForIt() {
        User user = user();

        ResponseEntity<String> first = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.POST, upload(user, "statement.csv", CSV), String.class);
        ResponseEntity<String> second = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.POST, upload(user, "statement.csv", CSV), String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(read(second).get("data").get("jobId").asText())
                .as("the same document, so the same job -- not a second one racing it")
                .isEqualTo(read(first).get("data").get("jobId").asText());
        assertThat(jobRepository.findByUserIdOrderByCreatedAtDesc(user.getId(), PageRequest.of(0, 10)))
                .as("and exactly one row exists to prove it")
                .hasSize(1);
    }

    /**
     * The other half: deduplication is scoped to LIVE jobs, so a document whose earlier import
     * reached a terminal state can be uploaded again. Re-importing after fixing an account mapping,
     * or retrying something that failed, are both ordinary -- an index over all history would
     * refuse them for ever.
     */
    @Test
    void theSameDocumentCanBeSubmittedAgainOnceTheEarlierJobIsFinished() {
        User user = user();

        ResponseEntity<String> first = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.POST, upload(user, "statement.csv", CSV), String.class);
        UUID firstJobId = UUID.fromString(read(first).get("data").get("jobId").asText());

        ImportJob finished = jobRepository.findById(firstJobId).orElseThrow();
        finished.cancel(Instant.now());
        jobRepository.saveAndFlush(finished);

        ResponseEntity<String> second = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.POST, upload(user, "statement.csv", CSV), String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(read(second).get("data").get("jobId").asText())
                .as("the earlier job is terminal, so this is genuinely new work")
                .isNotEqualTo(firstJobId.toString());
    }

    /** And it is per user: two people uploading the same statement are two independent imports. */
    @Test
    void twoUsersUploadingTheSameDocumentEachGetTheirOwnJob() {
        User one = user();
        User two = user();

        ResponseEntity<String> a = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.POST, upload(one, "statement.csv", CSV), String.class);
        ResponseEntity<String> b = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.POST, upload(two, "statement.csv", CSV), String.class);

        assertThat(read(a).get("data").get("jobId").asText())
                .isNotEqualTo(read(b).get("data").get("jobId").asText());
    }

    // ---------------------------------------------------------------- timeline (§3.1)

    @Test
    void aCompletedJobsTimelineListsEveryStageTheWorkerRan() {
        User user = user();
        ResponseEntity<String> accepted = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.POST, upload(user, "statement.csv", CSV), String.class);
        String jobId = read(accepted).get("data").get("jobId").asText();

        worker.drainOnce();

        ResponseEntity<String> timeline = restTemplate.exchange(
                "/api/v1/import/jobs/" + jobId + "/timeline", HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(timeline.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = read(timeline).get("data");
        assertThat(data.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(data.get("failureCode").isNull())
                .as("a completed job has nothing to explain")
                .isTrue();
        JsonNode stages = data.get("stages");
        assertThat(stages.isArray()).isTrue();
        assertThat(stages.size()).as("PARSING and ANALYZING both ran").isGreaterThanOrEqualTo(2);
        assertThat(stages.get(0).get("outcome").asText())
                .as("the worker's first stage must have actually finished, not still say RUNNING")
                .isEqualTo("COMPLETED");
    }

    @Test
    void anotherUsersTimelineIsNotReadable() {
        User owner = user();
        User stranger = user();
        ResponseEntity<String> accepted = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.POST, upload(owner, "statement.csv", CSV), String.class);
        String jobId = read(accepted).get("data").get("jobId").asText();

        ResponseEntity<String> asStranger = restTemplate.exchange(
                "/api/v1/import/jobs/" + jobId + "/timeline", HttpMethod.GET,
                new HttpEntity<>(bearerFor(stranger)), String.class);

        assertThat(asStranger.getStatusCode())
                .as("a job id alone must never be enough to read someone else's timeline")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * The curated-reason path, exercised without depending on a specific parser error being
     * reproducible end to end: {@code ImportJob.recordFailure}'s exact write-path logic (proven
     * unit-level in {@code ImportJobTest}/{@code ImportJobWorkerTest}) is trusted here, and this
     * test instead proves the piece those don't cover -- that the controller/service/DTO wiring
     * correctly reads a FAILED job's stored code back out translated to the customer-facing wire
     * code the frontend's failure-UX contract is keyed by.
     */
    @Test
    void aFailedJobsTimelineCarriesTheTranslatedFailureCode() {
        User user = user();
        ResponseEntity<String> accepted = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.POST, upload(user, "statement.csv", CSV), String.class);
        UUID jobId = UUID.fromString(read(accepted).get("data").get("jobId").asText());

        ImportJob job = jobRepository.findById(jobId).orElseThrow();
        job.markClaimed("worker", Instant.now());
        job.recordFailure("ApiException: No transaction table found", "IMPORT_NO_HEADER_DETECTED",
                com.finora.exception.ErrorCode.RetryPolicy.FAIL_FAST, Instant.now());
        jobRepository.save(job);

        ResponseEntity<String> timeline = restTemplate.exchange(
                "/api/v1/import/jobs/" + jobId + "/timeline", HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class);

        JsonNode data = read(timeline).get("data");
        assertThat(data.get("status").asText()).isEqualTo("FAILED");
        assertThat(data.get("failureCode").asText())
                .as("translated to the wire code, not the raw stored ErrorCode enum name")
                .isEqualTo("IMPORT_001");
    }
}
