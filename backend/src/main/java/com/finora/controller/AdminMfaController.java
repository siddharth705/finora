package com.finora.controller;

import com.finora.dto.AdminMfaDtos.*;
import com.finora.dto.ApiResponse;
import com.finora.security.CurrentUser;
import com.finora.service.AdminMfaService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * SEC-03 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Self-service
 * enrollment for the authenticated account only -- every method reads {@code currentUser.id()},
 * never a path/body-supplied id, same discipline every other self-service endpoint in this app
 * follows (see {@code OwnershipGuard}'s own doc comment on why that matters). Not restricted to
 * {@code SCOPE_ADMIN} at this layer: {@code AuthService.login()} only ever checks MFA status for
 * an admin-scoped account, so a consumer account enrolling here would simply have a credential
 * nothing ever asks it to use -- inert, not a privilege-boundary gap.
 */
@RestController
@RequestMapping("/api/v1/admin-mfa")
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
        return ApiResponse.ok(adminMfaService.confirm(currentUser.id(), request.code()),
                "MFA enabled. Save your recovery codes somewhere safe -- they won't be shown again.");
    }

    @PostMapping("/disable")
    public ApiResponse<Void> disable(@RequestBody DisableRequest request) {
        adminMfaService.disable(currentUser.id(), request.currentPassword(), request.googleIdToken());
        return ApiResponse.ok(null, "MFA disabled.");
    }
}
