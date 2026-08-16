package com.finora.imports.jobs;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BH-010. An upload past the servlet container's own multipart ceiling must come back as a clean
 * 413, not an unhandled 500 -- {@code GlobalExceptionHandler.handleUploadTooLarge}.
 *
 * <p>This is a separate class, not a test added to {@link ImportJobEndpointIT}, because it needs
 * its own {@code max-file-size}. An earlier version of this test ran against the real 10 MB
 * production limit and pushed an 11 MB payload at it over loopback -- Tomcat aborted the
 * connection outright ({@code SocketException: Broken pipe}) before the client ever read a
 * response, which is a transport-level failure, not evidence about whether the handler itself
 * works. Lowering the limit here to a few KB lets a modest, fast payload cross it while comfortably
 * inside whatever buffer/timeout threshold caused the abort at 11 MB.
 */
@TestPropertySource(properties = {
        "spring.servlet.multipart.max-file-size=8KB",
        "spring.servlet.multipart.max-request-size=8KB",
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-oversized-upload-it",
        "app.import.queue.enabled=false",
        // Same reasoning as ImportJobEndpointIT: importStageLimiter is 10 per 10 minutes per IP,
        // and raised here rather than disabled so the limiter itself stays exercised elsewhere.
        "app.rate-limit.import-stage.max=10000"
})
class OversizedUploadIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private ImportJobRepository jobRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;

    private User user() {
        User user = new User();
        user.setEmail("oversized-upload-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Oversized Upload IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    @Test
    void anUploadPastTheConfiguredLimitReturns413NotAServerFault() {
        User user = user();
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        // 20 KB against an 8 KB ceiling: comfortably over the limit, comfortably under whatever
        // size made the container itself abort the connection at 11 MB.
        String oversized = "A".repeat(20 * 1024);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(oversized.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "statement.csv"; }
        });

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode())
                .as("an oversized upload must be rejected cleanly, not surfaced as a server fault")
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(jobRepository.findByUserIdOrderByCreatedAtDesc(user.getId(), PageRequest.of(0, 10)))
                .as("a rejected upload must not leave a job behind")
                .isEmpty();
    }
}
