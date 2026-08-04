package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the RBAC management endpoints (docs/engineering-directive-phase1.md, Priority 2) are
 * actually gated by the ROLE_MANAGE permission -- seeded onto SUPER_ADMIN only, per
 * V16__rbac_roles_permissions.sql -- and that a plain USER (and even a legacy ADMIN, which does
 * *not* hold ROLE_MANAGE at seed time) can't reach them. Also proves assigning a role through
 * the API actually changes what that user's own token would be granted, by round-tripping
 * through AuthorizationService via a second login-shaped check would require a real login; this
 * test instead asserts on the persisted user_roles state via the same endpoint that manages it,
 * which is what the endpoint contract actually promises.
 */
class RoleAdminControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("rbac-mgmt-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("RBAC Management Test User");
        user.setRole(role);
        user.setPhoneVerified(true); // see AdminRbacIT for why this must be set
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generateToken(user.getId(), user.getEmail()));
        return headers;
    }

    @Test
    void plainUser_isForbiddenFromListingRoles() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/roles", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void legacyAdmin_doesNotAutomaticallyGetRoleManage() {
        // ADMIN's seeded permission set deliberately excludes ROLE_MANAGE/PERMISSION_MANAGE (see
        // the migration's comment on why) -- an operational admin should not be able to grant
        // itself or anyone else more access than the platform intends.
        User admin = createUser("ADMIN");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/roles", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void superAdmin_canListRolesAndAssignOneToAnotherUser() {
        User superAdmin = createUser("SUPER_ADMIN");
        User target = createUser("USER");

        ResponseEntity<String> listResponse = restTemplate.exchange(
                "/api/v1/admin/roles", HttpMethod.GET, new HttpEntity<>(bearerFor(superAdmin)), String.class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).contains("SUPER_ADMIN", "ADMIN", "USER");

        ResponseEntity<String> assignResponse = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/roles/ADMIN",
                HttpMethod.POST, new HttpEntity<>(bearerFor(superAdmin)), String.class);
        assertThat(assignResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        User reloaded = userRepository.findById(target.getId()).orElseThrow();
        assertThat(reloaded.getRoles()).extracting("name").contains("ADMIN");
    }

    @Test
    void assigningRole_toUnknownUser_returns404NotForbidden() {
        User superAdmin = createUser("SUPER_ADMIN");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + UUID.randomUUID() + "/roles/ADMIN",
                HttpMethod.POST, new HttpEntity<>(bearerFor(superAdmin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- Role & Permission CRUD (RoleService.createRole/updateRole/deleteRole, ditto for
    // permissions) -- added alongside the act-on-behalf-of-user / bank registry / global rules
    // admin gaps this pass fills in. The delete-guard tests below are the highest-risk new logic
    // here: deleting a role or permission that's still in use must be rejected, not silently
    // leave a dangling reference. ---

    @Test
    void superAdmin_canCreateUpdateAndDeleteARole() throws Exception {
        User superAdmin = createUser("SUPER_ADMIN");
        HttpHeaders headers = bearerFor(superAdmin);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> createResponse = restTemplate.exchange(
                "/api/v1/admin/roles", HttpMethod.POST,
                new HttpEntity<>("{\"name\":\"SUPPORT_AGENT\",\"description\":\"Support desk\"}", headers), String.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode created = mapper.readTree(createResponse.getBody()).get("data");
        assertThat(created.get("name").asText()).isEqualTo("SUPPORT_AGENT");
        String roleId = created.get("id").asText();

        ResponseEntity<String> updateResponse = restTemplate.exchange(
                "/api/v1/admin/roles/" + roleId, HttpMethod.PUT,
                new HttpEntity<>("{\"description\":\"Support desk, tier 1\"}", headers), String.class);
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(updateResponse.getBody()).get("data").get("description").asText())
                .isEqualTo("Support desk, tier 1");

        ResponseEntity<String> deleteResponse = restTemplate.exchange(
                "/api/v1/admin/roles/" + roleId, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void creatingARole_withADuplicateName_isRejected() {
        User superAdmin = createUser("SUPER_ADMIN");
        HttpHeaders headers = bearerFor(superAdmin);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // SUPER_ADMIN already exists from V16__rbac_roles_permissions.sql's seed data.
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/roles", HttpMethod.POST,
                new HttpEntity<>("{\"name\":\"SUPER_ADMIN\",\"description\":\"duplicate\"}", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void deletingARole_stillAssignedToAUser_isRejected() throws Exception {
        User superAdmin = createUser("SUPER_ADMIN");
        User target = createUser("USER");
        HttpHeaders headers = bearerFor(superAdmin);

        // ADMIN is a seeded role (V16__rbac_roles_permissions.sql) -- assign it to `target` first
        // so it's genuinely in use, then look up its id the same way the admin frontend would.
        restTemplate.exchange("/api/v1/admin/users/" + target.getId() + "/roles/ADMIN",
                HttpMethod.POST, new HttpEntity<>(headers), String.class);

        ResponseEntity<String> listResponse = restTemplate.exchange(
                "/api/v1/admin/roles", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        JsonNode roles = mapper.readTree(listResponse.getBody()).get("data");
        String roleId = null;
        for (JsonNode role : roles) {
            if ("ADMIN".equals(role.get("name").asText())) {
                roleId = role.get("id").asText();
                break;
            }
        }
        assertThat(roleId).as("ADMIN role should exist from V16's seed data").isNotNull();

        ResponseEntity<String> deleteResponse = restTemplate.exchange(
                "/api/v1/admin/roles/" + roleId, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void superAdmin_canCreateAndDeleteAPermission_andCanGrantOrRevokeItOnARole() throws Exception {
        User superAdmin = createUser("SUPER_ADMIN");
        HttpHeaders headers = bearerFor(superAdmin);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Bug fix: this used the literal name REPORT_EXPORT, which V16__rbac_roles_permissions.sql
        // already seeds, so creating it returned 409 CONFLICT and the test failed on its first
        // assertion. Same for the REPORT_VIEWER role below. Unique names per run instead. Never
        // caught because this class had never run: *IT did not match surefire's default includes.
        String permissionName = "REPORT_EXPORT_IT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String roleName = "REPORT_VIEWER_IT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        ResponseEntity<String> createPermission = restTemplate.exchange(
                "/api/v1/admin/permissions", HttpMethod.POST,
                new HttpEntity<>("{\"name\":\"" + permissionName + "\",\"description\":\"Export reports\"}", headers), String.class);
        assertThat(createPermission.getStatusCode()).isEqualTo(HttpStatus.OK);
        String permissionId = mapper.readTree(createPermission.getBody()).get("data").get("id").asText();

        ResponseEntity<String> createRole = restTemplate.exchange(
                "/api/v1/admin/roles", HttpMethod.POST,
                new HttpEntity<>("{\"name\":\"" + roleName + "\",\"description\":\"Reads reports\"}", headers), String.class);
        String roleId = mapper.readTree(createRole.getBody()).get("data").get("id").asText();

        ResponseEntity<String> grantResponse = restTemplate.exchange(
                "/api/v1/admin/roles/" + roleId + "/permissions/" + permissionId,
                HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertThat(grantResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(grantResponse.getBody()).contains(permissionName);

        // Deleting a granted permission must be rejected while it's still attached to the role.
        ResponseEntity<String> deleteWhileGranted = restTemplate.exchange(
                "/api/v1/admin/permissions/" + permissionId, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
        assertThat(deleteWhileGranted.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<String> revokeResponse = restTemplate.exchange(
                "/api/v1/admin/roles/" + roleId + "/permissions/" + permissionId,
                HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
        assertThat(revokeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revokeResponse.getBody()).doesNotContain(permissionName);

        // Now that no role grants it, deletion succeeds.
        ResponseEntity<String> deleteAfterRevoke = restTemplate.exchange(
                "/api/v1/admin/permissions/" + permissionId, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
        assertThat(deleteAfterRevoke.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
