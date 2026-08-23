package com.finora.controller;

import com.finora.dto.AdminMfaDtos.*;
import com.finora.dto.ApiResponse;
import com.finora.security.CurrentUser;
import com.finora.service.AdminMfaService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * SEC-03 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Self-service
 * enrollment for the authenticated account only -- every method reads {@code currentUser.id()},
 * never a path/body-supplied id, same discipline every other self-service endpoint in this app
 * follows (see {@code OwnershipGuard}'s own doc comment on why that matters).
 *
 * <p>Gated on {@code PORTAL_ADMIN} rather than a fine-grained permission like the rest of the
 * {@code /api/v1/admin/**} surface (see {@code AuthorizationService}'s own doc comment on that
 * convention): this is "manage your own account's second factor," not an action against another
 * user's data, so it should be available to every admin-scoped account regardless of which
 * specific permissions they hold, not something a permission grant has to be seeded for first.
 * {@code AuthService.login()} today only ever checks MFA status for an admin-scoped account, so a
 * consumer account reaching this would have had a credential nothing asks it to use -- but that
 * is a fact about {@code login()}'s current logic, not a boundary this controller should rely on
 * staying true. {@code AdminEndpointAuthorizationTest} exists precisely so authorization is
 * enforced at the layer that owns it, not inferred from what a caller happens not to do with it.
 */
@RestController
@RequestMapping("/api/v1/admin-mfa")
@PreAuthorize("hasAuthority('PORTAL_ADMIN')")
public class AdminMfaController {

    private final AdminMfaService adminMfaService;
    private final CurrentUser currentUser;

    public AdminMfaController(AdminMfaService adminMfaService, CurrentUser currentUser) {
        this.adminMfaService = adminMfaService;
        this.currentUser = currentUser;
    }

    @GetMapping("/status")
    public ApiResponse<StatusResponse> status() {
        return ApiResponse.ok(new StatusResponse(adminMfaService.isEnabled(currentUser.id())));
    }

    @PostMapping("/enroll")
    public ApiResponse<EnrollResponse> enroll() {
        return ApiResponse.ok(adminMfaService.beginEnrollment(currentUser.id()));
    }

    @PostMapping("/confirm")
    public ApiResponse<ConfirmResponse> confirm(@Valid @RequestBody ConfirmRequest request) {
        // actingAdminId == currentUser.id(): this is self-service only, there is no admin-proxy
        // path onto another account's MFA (see AdminMfaService.confirm's own doc comment).
        UUID userId = currentUser.id();
        return ApiResponse.ok(adminMfaService.confirm(userId, request.code(), userId),
                "MFA enabled. Save your recovery codes somewhere safe -- they won't be shown again.");
    }

    @PostMapping("/disable")
    public ApiResponse<Void> disable(@RequestBody DisableRequest request) {
        UUID userId = currentUser.id();
        adminMfaService.disable(userId, request.currentPassword(), request.googleIdToken(), request.appleIdToken(), userId);
        return ApiResponse.ok(null, "MFA disabled.");
    }
}
