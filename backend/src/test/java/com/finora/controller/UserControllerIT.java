package com.finora.controller;

import com.finora.AbstractIntegrationTest;
import com.finora.config.CorrelationIdFilter;
import com.finora.dto.AccountLifecycleDtos.ExportDataRequest;
import com.finora.entity.AuditLog;
import com.finora.entity.User;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link com.finora.service.DataExportServiceTest}/{@link com.finora.service.DataExportServiceIT}
 * prove {@code buildBundle}/{@code writeZip}'s own logic; this proves the controller wiring around
 * them that only a real HTTP round-trip can -- the mid-stream audit ordering, the {@code
 * Content-Disposition} header, and (the actual regression this class exists to catch, per the
 * review that flagged {@code exportData()} as having zero test coverage of any kind) that the
 * request's correlation ID, captured on the sync thread per {@code UserController.exportData}'s own
 * doc comment, genuinely survives into the DATA_EXPORTED audit row written from
 * StreamingResponseBody's separate callback thread -- not just that the code compiles.
 *
 * <p>Scoped to {@code POST /data-export} only, the endpoint the review flagged -- not a general
 * {@code UserController} test sweep, which is a separate, larger undertaking this codebase has
 * never done for {@code deactivate()}/{@code deleteAccount()} either.
 */
class UserControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String PASSWORD = "correct horse battery staple";
    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setEmail("export-controller-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setFullName("Export Controller IT User");
        // PhoneVerificationFilter gates the whole authenticated API on this -- without it every
        // request here 403s before reaching the controller at all, per MeAccessControllerIT's own
        // fixture doing the same.
        user.setPhoneVerified(true);
        user = userRepository.save(user);
    }

    private HttpHeaders headersFor(User user, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(CorrelationIdFilter.HEADER_NAME, requestId);
        return headers;
    }

    @Test
    void exportData_wrongPassword_rejectsWithoutRequestingOrStreamingAnExport() {
        String requestId = "wrong-pw-" + UUID.randomUUID();
        HttpEntity<ExportDataRequest> entity = new HttpEntity<>(
                new ExportDataRequest("definitely-the-wrong-password"), headersFor(user, requestId));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me/data-export", HttpMethod.POST, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        List<AuditLog> logs = auditLogRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        assertThat(logs).extracting(AuditLog::getAction).contains("INVALID_CURRENT_PASSWORD");
        assertThat(logs).extracting(AuditLog::getAction)
                .doesNotContain("DATA_EXPORT_REQUESTED", "DATA_EXPORTED", "DATA_EXPORT_FAILED");
    }

    @Test
    void exportData_correctPassword_streamsAWellFormedZip_withAttachmentHeaders() throws Exception {
        String requestId = "happy-path-" + UUID.randomUUID();
        HttpEntity<ExportDataRequest> entity = new HttpEntity<>(
                new ExportDataRequest(PASSWORD), headersFor(user, requestId));

        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/api/v1/users/me/data-export", HttpMethod.POST, entity, byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("application/zip"));
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).contains("attachment").contains("finora-data-export-").contains(".zip");

        List<String> entryNames = new java.util.ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(response.getBody()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) entryNames.add(entry.getName());
        }
        assertThat(entryNames).contains("manifest.json", "README.txt", "accounts.json", "transactions.json");
    }

    /**
     * Regression test for the review finding this class exists to close: DATA_EXPORT_REQUESTED is
     * written synchronously in exportData() itself, before the response streams; DATA_EXPORTED is
     * written from inside StreamingResponseBody's callback, on a different thread, after
     * CorrelationIdFilter's own finally block has already cleared MDC for the original request
     * thread. Both rows carrying the SAME request ID -- the one this test sent, not a freshly
     * generated one -- is only possible if exportData()'s manual capture/restore actually works.
     */
    @Test
    void exportData_correctPassword_auditsRequestedThenExported_bothCarryingTheSameRequestId() {
        String requestId = "mdc-propagation-" + UUID.randomUUID();
        HttpEntity<ExportDataRequest> entity = new HttpEntity<>(
                new ExportDataRequest(PASSWORD), headersFor(user, requestId));

        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/api/v1/users/me/data-export", HttpMethod.POST, entity, byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(CorrelationIdFilter.HEADER_NAME)).isEqualTo(requestId);

        List<AuditLog> logs = auditLogRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        AuditLog requested = logs.stream().filter(l -> l.getAction().equals("DATA_EXPORT_REQUESTED")).findFirst()
                .orElseThrow(() -> new AssertionError("DATA_EXPORT_REQUESTED was never recorded"));
        AuditLog exported = logs.stream().filter(l -> l.getAction().equals("DATA_EXPORTED")).findFirst()
                .orElseThrow(() -> new AssertionError("DATA_EXPORTED was never recorded"));

        assertThat(requested.getRequestId()).isEqualTo(requestId);
        assertThat(exported.getRequestId()).isEqualTo(requestId);
        // findByUserIdOrderByCreatedAtDesc is newest-first: DATA_EXPORTED (recorded after the
        // stream completes) must sort ahead of DATA_EXPORT_REQUESTED (recorded before it starts).
        assertThat(logs.indexOf(exported)).isLessThan(logs.indexOf(requested));
    }
}
