package com.finora.controller;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.AuditLog;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.Permission;
import com.finora.entity.Role;
import com.finora.entity.User;
import com.finora.imports.storage.ContentAddress;
import com.finora.imports.storage.StatementStorage;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.RoleRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /{heldId}/document} -- the one endpoint in the product that hands a customer's bank
 * statement to a member of staff. Its own file, split from {@code AdminHeldStatementControllerIT},
 * because this endpoint carries more risk than the rest of the controller combined and deserves
 * tests that read that way rather than being mixed in with the queue's.
 *
 * <p>{@code ImportJob} never carries database-held bytes -- {@code getFileContent()} always
 * returns null by design (BH-045: "a job carries an address, never the bytes") -- so a real read
 * needs a real, configured {@link StatementStorage}, the same reason {@code ImportJobSourceFormatIT}
 * enables the filesystem provider rather than relying on the legacy database fallback.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-held-statement-download-it"
})
class AdminHeldStatementDownloadIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private HeldStatementRepository heldStatementRepository;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private EntityManager entityManager;
    @Autowired private StatementStorage storage;

    private static final byte[] PDF_BYTES = "%PDF-1.4 fixture bytes".getBytes(StandardCharsets.UTF_8);

    private User createUser(String role) {
        User user = new User();
        user.setEmail("held-dl-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Held Download IT User");
        user.setRole(role);
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    /**
     * A support-portal account: holds {@code TRUST_REVIEW_MANAGE} through a real, attached RBAC
     * role -- so it can reach the queue and open a hold's evidence -- but its legacy {@code role}
     * column is deliberately NOT "ADMIN" or "SUPER_ADMIN", so it carries neither {@code ROLE_ADMIN}
     * nor {@code ROLE_SUPER_ADMIN}. This is exactly the future support role the repository owner's
     * decision names: permitted to work the queue, never permitted to take the document.
     */
    private User createSupportUserWithTrustReviewPermissionButNoAdminRole() {
        Permission trustReviewManage = entityManager
                .createQuery("SELECT p FROM Permission p WHERE p.name = :name", Permission.class)
                .setParameter("name", "TRUST_REVIEW_MANAGE")
                .getSingleResult();
        Role supportRole = new Role();
        supportRole.setName("TRUST_REVIEW_SUPPORT_" + UUID.randomUUID().toString().substring(0, 8));
        supportRole.setDescription("AdminHeldStatementDownloadIT fixture role");
        supportRole.getPermissions().add(trustReviewManage);
        roleRepository.save(supportRole);

        User user = createUser("SUPPORT");
        user.getRoles().add(supportRole);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** A job in exactly the state the worker's trust gate leaves a held import in, with real PDF
     *  bytes actually written to the (filesystem-backed) object store -- {@code
     *  anAdminGetsThePdfBytes} needs something to actually retrieve, and {@code getFileContent()}
     *  cannot supply it (see this class's own doc). */
    private HeldStatement seedHold(String heldId) {
        User owner = createUser("USER");
        ContentAddress address = storage.store(PDF_BYTES);
        ImportJob job = new ImportJob(owner.getId(), "hdfc-june.pdf",
                address.hash(), address.key(), "PDF");
        job.markClaimed("worker", Instant.now());
        UUID sessionId = UUID.randomUUID();
        job.holdForTrustReview(sessionId, null, Instant.now());
        importJobRepository.save(job);

        HeldStatement held = heldStatementRepository.save(new HeldStatement(
                heldId, job.getId(), owner.getId(), job.getObjectKey(),
                "Printed and parsed transaction count disagree (ROW_GROUPING)"));
        job.holdForTrustReview(sessionId, held.getId(), Instant.now());
        importJobRepository.save(job);
        return held;
    }

    private ResponseEntity<byte[]> download(String heldId, User admin) {
        return restTemplate.exchange("/api/v1/admin/held-statements/" + heldId + "/document",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), byte[].class);
    }

    @Test
    void downloadRefusesAnUnauthenticatedCaller() {
        seedHold("HLD-2026-200001");

        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/api/v1/admin/held-statements/HLD-2026-200001/document",
                HttpMethod.GET, HttpEntity.EMPTY, byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * {@code TRUST_REVIEW_MANAGE} alone is not enough for this one endpoint -- a role that can work
     * the queue must still not be able to take the document.
     */
    @Test
    void downloadRefusesAnyRoleBelowAdmin() {
        seedHold("HLD-2026-200002");
        User support = createSupportUserWithTrustReviewPermissionButNoAdminRole();

        ResponseEntity<byte[]> response = download("HLD-2026-200002", support);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * Proves the AND is genuinely enforced, not merely present -- the case the test above cannot
     * distinguish. An earlier version of this endpoint used two separate {@code @PreAuthorize}
     * annotations (class-level {@code TRUST_REVIEW_MANAGE}, method-level {@code hasAnyRole}), which
     * Spring Security resolves by having the method-level one REPLACE the class-level one rather
     * than AND with it -- silently dropping the permission check for this endpoint alone, exactly
     * as {@code AdminStatementAnalysisController}'s own doc already documents for that mechanism.
     *
     * <p>Mutating back to that two-annotation form and re-running this suite is how this was
     * caught: {@code downloadRefusesAnyRoleBelowAdmin} above still passed under the bug, because
     * its support user already lacks {@code ROLE_ADMIN} regardless of which permission check ran.
     * This account is the one shape that tells the two implementations apart -- {@code ROLE_ADMIN}
     * present (the legacy role string, unconditionally granted), {@code TRUST_REVIEW_MANAGE} absent
     * (withheld because the account carries no admin-portal scope, which is what actually grants a
     * role's permissions -- see {@code AuthorizationService.addRole}). Under the broken form this
     * user would be let straight through on the role check alone; under the fix, both are required.
     */
    @Test
    void downloadRefusesAnAdminRoleAccountWithoutTheTrustReviewPermission() {
        seedHold("HLD-2026-200007");
        User user = new User();
        user.setEmail("held-dl-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Held Download IT User");
        user.setRole("ADMIN");
        user.setPhoneVerified(true);
        // accountScope left at its default (User.SCOPE_USER) -- deliberately not ADMIN, which is
        // what withholds the ADMIN role's permissions even though the legacy role string still
        // grants ROLE_ADMIN.
        userRepository.save(user);

        ResponseEntity<byte[]> response = download("HLD-2026-200007", user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** Every download writes an audit row naming who took it and whose statement it was. A
     *  download nobody can attribute is the failure mode this endpoint's whole risk profile rests
     *  on. */
    @Test
    void everyDownloadIsAudited() {
        HeldStatement held = seedHold("HLD-2026-200003");
        User admin = createUser("ADMIN");

        download("HLD-2026-200003", admin);

        List<AuditLog> entries = auditLogRepository.findByEntityIdOrderByCreatedAtAsc(held.getId());
        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry.getAction()).isEqualTo("TRUST_REVIEW_DOCUMENT_DOWNLOADED");
            assertThat(entry.getUserId()).isEqualTo(admin.getId());
        });
    }

    @Test
    void anAdminGetsThePdfBytes() {
        seedHold("HLD-2026-200004");
        User admin = createUser("ADMIN");

        ResponseEntity<byte[]> response = download("HLD-2026-200004", admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(PDF_BYTES);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
    }

    /** A Super Admin is the other role the decision names -- proves the gate is "either", not
     *  "ADMIN only". */
    @Test
    void aSuperAdminGetsThePdfBytesToo() {
        seedHold("HLD-2026-200005");
        User superAdmin = createUser("SUPER_ADMIN");

        ResponseEntity<byte[]> response = download("HLD-2026-200005", superAdmin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(PDF_BYTES);
    }

    @Test
    void anUnknownHeldIdIs404NotAnAttributeError() {
        User admin = createUser("ADMIN");

        ResponseEntity<byte[]> response = download("HLD-2026-999999", admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
