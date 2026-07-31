package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.AuthDtos.RegisterRequest;
import com.finora.dto.SetupDtos.SetupStatusDto;
import com.finora.security.CurrentUser;
import com.finora.service.SetupService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * First-run platform setup (V33__bootstrap_admin.sql / BootstrapService / SetupService).
 * /status is public -- SecurityConfig permits it alongside /api/v1/auth/** -- so the frontend
 * login page can decide whether to show a normal sign-in form or redirect to a setup wizard
 * before anyone has a token. /complete requires a valid JWT for the SYSTEM_INITIALIZE
 * permission, which only the one BOOTSTRAP_ADMIN account BootstrapService creates ever holds.
 */
@RestController
@RequestMapping("/api/v1/setup")
public class SetupController {

    private final SetupService setupService;
    private final CurrentUser currentUser;

    public SetupController(SetupService setupService, CurrentUser currentUser) {
        this.setupService = setupService;
        this.currentUser = currentUser;
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<SetupStatusDto>> status() {
        return ResponseEntity.ok(ApiResponse.ok(
                new SetupStatusDto(setupService.isSetupRequired(), setupService.isInstallationKeyAvailable())));
    }

    @PostMapping("/complete")
    @PreAuthorize("hasAuthority('SYSTEM_INITIALIZE')")
    public ResponseEntity<ApiResponse<Void>> complete(@Valid @RequestBody RegisterRequest request) {
        setupService.completeSetup(currentUser.id(), request);
        return ResponseEntity.ok(ApiResponse.ok(null, "Setup complete. The bootstrap account has been locked."));
    }
}
