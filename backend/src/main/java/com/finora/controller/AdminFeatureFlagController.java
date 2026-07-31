package com.finora.controller;

import com.finora.dto.AdminDtos.FeatureFlagDto;
import com.finora.dto.AdminDtos.UpdateFeatureFlagRequest;
import com.finora.dto.ApiResponse;
import com.finora.security.CurrentUser;
import com.finora.service.FeatureFlagService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Admin Portal Phase 8 -- feature flag toggle surface. Reuses SYSTEM_SETTINGS (V16) rather than
 *  minting a new permission: flipping a platform-wide flag is the same class of "internal
 *  operational configuration" capability PlatformSettingsController already gates with it. */
@RestController
@RequestMapping("/api/v1/admin/feature-flags")
@PreAuthorize("hasAuthority('SYSTEM_SETTINGS')")
public class AdminFeatureFlagController {

    private final FeatureFlagService featureFlagService;
    private final CurrentUser currentUser;

    public AdminFeatureFlagController(FeatureFlagService featureFlagService, CurrentUser currentUser) {
        this.featureFlagService = featureFlagService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<List<FeatureFlagDto>> list() {
        return ApiResponse.ok(featureFlagService.list());
    }

    @PutMapping("/{id}")
    public ApiResponse<FeatureFlagDto> update(@PathVariable UUID id, @Valid @RequestBody UpdateFeatureFlagRequest request) {
        return ApiResponse.ok(featureFlagService.setEnabled(currentUser.id(), id, request.enabled()));
    }
}
