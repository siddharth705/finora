package com.finora.controller;

import com.finora.dto.AdminDtos.AdminUpdateUserRequest;
import com.finora.dto.AdminDtos.PagedResponse;
import com.finora.dto.AdminDtos.UserDetailDto;
import com.finora.dto.AdminDtos.UserSummaryDto;
import com.finora.dto.ApiResponse;
import com.finora.dto.AuthDtos.RegisterRequest;
import com.finora.security.CurrentUser;
import com.finora.service.AdminUserService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * The admin Users directory (frontend-admin/): search/browse every account on the platform, view
 * a single account's detail, and suspend/reactivate. Gated by USER_VIEW for reads and USER_DELETE
 * for the state-changing suspend/reactivate actions -- USER_DELETE's seeded description
 * ("Delete or deactivate another user's account") already covers this; there's no separate
 * USER_SUSPEND permission because suspending is exactly the "deactivate" half of that grant.
 *
 * Thin by design (Priority 4 of the engineering directive) -- all logic lives in AdminUserService.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final CurrentUser currentUser;

    public AdminUserController(AdminUserService adminUserService, CurrentUser currentUser) {
        this.adminUserService = adminUserService;
        this.currentUser = currentUser;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('USER_VIEW')")
    public ApiResponse<PagedResponse<UserSummaryDto>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(adminUserService.list(q, status, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('USER_VIEW')")
    public ApiResponse<UserDetailDto> get(@PathVariable UUID id) {
        return ApiResponse.ok(adminUserService.getUser(id));
    }

    /** Support-assisted signup -- see AdminUserService.createUser. */
    @PostMapping
    @PreAuthorize("hasAuthority('USER_CREATE')")
    public ApiResponse<UserSummaryDto> create(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(adminUserService.createUser(currentUser.id(), request), "User created");
    }

    /** Support-assisted profile edit -- see AdminUserService.updateProfile. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    public ApiResponse<UserSummaryDto> update(@PathVariable UUID id, @RequestBody AdminUpdateUserRequest request) {
        return ApiResponse.ok(adminUserService.updateProfile(currentUser.id(), id, request), "Profile updated");
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasAuthority('USER_DELETE')")
    public ApiResponse<UserSummaryDto> suspend(@PathVariable UUID id) {
        return ApiResponse.ok(adminUserService.suspend(id, currentUser.id()), "Account suspended");
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAuthority('USER_DELETE')")
    public ApiResponse<UserSummaryDto> reactivate(@PathVariable UUID id) {
        return ApiResponse.ok(adminUserService.reactivate(id, currentUser.id()), "Account reactivated");
    }
}
