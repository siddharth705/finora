package com.finora.controller;

import com.finora.dto.AdminDtos.PlatformSettingsDto;
import com.finora.dto.AdminDtos.UpdatePlatformSettingsRequest;
import com.finora.dto.ApiResponse;
import com.finora.security.CurrentUser;
import com.finora.service.PlatformSettingsService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Real platform-wide configuration for the admin portal's System page -- gated by
 *  SYSTEM_SETTINGS, the same permission AdminSystemController's health check already uses. See
 *  PlatformSettingsService for what's actually configurable and why. */
@RestController
@RequestMapping("/api/v1/admin/settings")
@PreAuthorize("hasAuthority('SYSTEM_SETTINGS')")
public class PlatformSettingsController {

    private final PlatformSettingsService platformSettingsService;
    private final CurrentUser currentUser;

    public PlatformSettingsController(PlatformSettingsService platformSettingsService, CurrentUser currentUser) {
        this.platformSettingsService = platformSettingsService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<PlatformSettingsDto> get() {
        return ApiResponse.ok(platformSettingsService.get());
    }

    @PutMapping
    public ApiResponse<PlatformSettingsDto> update(@Valid @RequestBody UpdatePlatformSettingsRequest request) {
        return ApiResponse.ok(platformSettingsService.update(currentUser.id(), request), "Settings updated");
    }
}
