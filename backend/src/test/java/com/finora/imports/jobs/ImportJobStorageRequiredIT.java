package com.finora.imports.jobs;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The asynchronous path refuses to accept work it could never do.
 *
 * <p>Storage is deliberately NOT configured here, which is the default on this branch and in
 * production today. The synchronous path still works in that state because it can keep bytes in the
 * database; the asynchronous path cannot, because the worker runs later in another thread with
 * nothing to read but a content address.
 *
 * <p>The failure mode this prevents is the quiet one: accept the upload, return 202 with a job id,
 * and have the worker fail on every attempt until the job dead-letters. The user would see an
 * upload that succeeded and an import that never happened, and the cause would be a configuration
 * value nobody looked at. Refusing at the door names the missing setting instead.
 *
 * <p>A separate class from {@code ImportJobEndpointIT} because the two need different Spring
 * contexts -- one with a storage provider and one without -- and that is exactly the distinction
 * being asserted.
 */
@TestPropertySource(properties = "app.import.queue.enabled=false")
class ImportJobStorageRequiredIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private ImportJobRepository jobRepository;
    @Autowired private JwtService jwtService;

    private User user() {
        User user = new User();
        user.setEmail("import-job-nostorage-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Import Job No Storage IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private ResponseEntity<String> upload(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generateToken(user.getId(), user.getEmail(), UUID.randomUUID()));
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(
                "Date,Description,Amount,Type\n2026-07-10,SWIGGY,486.00,DEBIT\n"
                        .getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "statement.csv"; }
        });
        return restTemplate.exchange("/api/v1/import/jobs", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void anUploadIsRefusedWhenStorageIsNotConfigured() {
        ResponseEntity<String> response = upload(user());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody())
                .as("the message must name the missing setting, or the operator has to guess")
                .contains("app.statement-storage.provider");
    }

    @Test
    void noJobIsLeftBehindByARefusedUpload() {
        // A queued job with no content address can never run and would sit in the queue being
        // retried until it dead-lettered -- noise in the admin queue for a configuration problem.
        User user = user();

        upload(user);

        assertThat(jobRepository.findByUserIdOrderByCreatedAtDesc(user.getId(), PageRequest.of(0, 10)))
                .isEmpty();
    }

    @Test
    void theSynchronousPathStillWorksWithoutStorage() {
        // The reason this is a new endpoint rather than a change to the existing one: the old path
        // keeps working exactly as it did, on a deployment where the new one cannot.
        HttpHeaders headers = new HttpHeaders();
        User user = user();
        headers.setBearerAuth(jwtService.generateToken(user.getId(), user.getEmail(), UUID.randomUUID()));
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(
                "Date,Description,Amount,Type\n2026-07-10,SWIGGY,486.00,DEBIT\n"
                        .getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "statement.csv"; }
        });

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/import/csv/stage", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
