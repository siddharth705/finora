package com.finora.imports.jobs;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.imports.trace.ImportTraceDto;
import com.finora.imports.trace.ImportTraceService;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The worker's own stage reporting, driven through a real upload.
 *
 * <p>{@code ImportStageRecorderIT} proves the recorder writes what it is told. This proves the
 * <b>wiring</b> — that a job actually running through {@code ImportJobWorker} leaves the stages it
 * ran, in order, with durations, and records the ones it passed over. Nothing else asserts that: a
 * worker that silently stopped calling the recorder would leave every other test green and the trace
 * empty, which is indistinguishable from a queue nobody used.
 *
 * <p>Storage is on and the poller is off, matching {@code ImportJobEndpointIT}: the test drives
 * {@code drainOnce()} itself so the assertions are about a completed pass rather than a race with a
 * scheduler.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-import-stage-it",
        "app.import.queue.enabled=false"
})
class ImportJobWorkerStageIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private ImportJobRepository jobRepository;
    @Autowired private ImportJobWorker worker;
    @Autowired private ImportJobStageRepository stageRepository;
    @Autowired private ImportTraceService traceService;
    @Autowired private JwtService jwtService;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String CSV = """
            Date,Description,Amount,Type
            2026-07-10,SWIGGY ORDER,486.00,DEBIT
            2026-07-11,BLINKIT GROCERIES,1240.50,DEBIT
            """;

    private User user() {
        User user = new User();
        user.setEmail("import-stage-worker-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Import Stage Worker IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private UUID uploadedJobId(User user) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generateToken(user.getId(), user.getEmail(), UUID.randomUUID()));
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(CSV.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "statement.csv"; }
        });
        ResponseEntity<String> accepted = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        JsonNode data = mapper.readTree(accepted.getBody()).get("data");
        return UUID.fromString(data.get("jobId").asText());
    }

    @Test
    void aCompletedPassLeavesEveryStageItRanAndEveryStageItDidNot() throws Exception {
        User user = user();
        UUID jobId = uploadedJobId(user);

        worker.drainOnce();

        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(ImportJob.Status.COMPLETED);

        var stages = stageRepository.findByJobIdOrderByRecordedAtAsc(jobId);
        assertThat(stages).extracting(s -> s.getStage() + ":" + s.getOutcome())
                .as("the two stages this worker runs, then the three it stops short of -- SKIPPED "
                    + "rather than absent, so 'does not run here' is distinguishable from "
                    + "'not instrumented'")
                .containsExactly(
                        "PARSING:COMPLETED", "ANALYZING:COMPLETED",
                        "DEDUPING:SKIPPED", "IMPORTING:SKIPPED", "LEARNING:SKIPPED");

        assertThat(stages).filteredOn(s -> s.getOutcome() == ImportJobStage.Outcome.COMPLETED)
                .as("the question statement_analysis_sessions.duration_ms could never answer")
                .allSatisfy(s -> assertThat(s.getDurationMs()).isNotNull());
        assertThat(stages).allSatisfy(s -> assertThat(s.getAttempt()).isEqualTo(1));
    }

    @Test
    void theTraceOfThatJobCarriesItsStagesAndItsVerification() throws Exception {
        // The criterion, on the asynchronous path: one lookup, no log, no engineer.
        User user = user();
        UUID jobId = uploadedJobId(user);

        worker.drainOnce();

        ImportTraceDto.Trace trace = traceService.byJobId(jobId).orElseThrow();
        assertThat(trace.job().status()).isEqualTo("COMPLETED");
        assertThat(trace.job().totalDurationMs()).isNotNull();
        assertThat(trace.stages()).hasSize(5);
        assertThat(trace.verification())
                .as("the rules ran during staging and their findings used to end with the response "
                    + "-- on this path there is not even a user holding one")
                .isNotEmpty()
                .extracting(ImportTraceDto.Finding::rule)
                .contains("BALANCE_CHAIN");
    }
}
