package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.config.JwtProperties;
import com.finora.dto.DeviceSessionDto;
import com.finora.security.CurrentUser;
import com.finora.service.RefreshTokenService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Self-service view of a user's own active sessions (refresh tokens), and the ability to sign a
 * single one of them out remotely — e.g. "I left myself logged in on a friend's phone" or "I lost
 * this device." Sits alongside UserController's own /users/me family rather than inside it, since
 * it's a distinct resource with its own list/delete shape, not another field of the user's
 * profile/settings.
 */
@RestController
@RequestMapping("/api/v1/users/me/devices")
public class DeviceController {

    private final RefreshTokenService refreshTokenService;
    private final CurrentUser currentUser;
    private final JwtProperties jwtProperties;

    public DeviceController(RefreshTokenService refreshTokenService, CurrentUser currentUser,
                            JwtProperties jwtProperties) {
        this.refreshTokenService = refreshTokenService;
        this.currentUser = currentUser;
        this.jwtProperties = jwtProperties;
    }

    @GetMapping
    public ApiResponse<List<DeviceSessionDto>> list() {
        List<DeviceSessionDto> sessions = refreshTokenService.listActiveSessions(currentUser.id()).stream()
                .map(rt -> DeviceSessionDto.from(rt, jwtProperties.getAbsoluteSessionMs()))
                .toList();
        return ApiResponse.ok(sessions);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> revoke(@PathVariable UUID id) {
        refreshTokenService.revokeSession(currentUser.id(), id);
        return ApiResponse.ok(null, "Signed out of that device");
    }
}
