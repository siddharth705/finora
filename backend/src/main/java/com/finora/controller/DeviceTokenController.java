package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.notification.api.DeviceTokenService;
import com.finora.notification.domain.DeviceToken;
import com.finora.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service registration for the current user's mobile push tokens (storage half of mobile
 * push -- Task 9). Sits alongside DeviceController's own /users/me/devices family conceptually
 * (both are "this user's own devices"), but lives as its own resource under /device-tokens rather
 * than nested there: a push token and a login session are different lifecycles with different
 * identifiers, and DeviceController's DELETE-by-id shape doesn't fit a client that only ever knows
 * its own raw token string, never a server-side row id.
 *
 * <p>The user id comes from {@link CurrentUser#id()} -- resolved from the caller's own JWT, exactly
 * like every other authenticated (non-admin) controller in this codebase (see DeviceController,
 * PhoneController). Never trust a userId the client could put in the request body: a device token
 * registered against the wrong user id would send that user's notifications to someone else's
 * phone.
 *
 * <p>Revoke is a POST to a sub-path, not a DELETE with a body: the client identifies the token to
 * revoke by its own raw token string (it was never given the server-generated row id), and this
 * codebase has no precedent for a DELETE carrying a request body -- some intermediary proxies strip
 * DELETE bodies, and it reads as unusual REST. POST .../revoke matches the same shape
 * PhoneController already uses for POST /phone/verify.
 */
@RestController
@RequestMapping("/api/v1/device-tokens")
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;
    private final CurrentUser currentUser;

    public DeviceTokenController(DeviceTokenService deviceTokenService, CurrentUser currentUser) {
        this.deviceTokenService = deviceTokenService;
        this.currentUser = currentUser;
    }

    public record RegisterDeviceTokenRequest(
            @NotBlank @Pattern(regexp = "ANDROID|IOS", message = "platform must be ANDROID or IOS")
            String platform,
            @NotBlank String token) {
    }

    public record RevokeDeviceTokenRequest(@NotBlank String token) {
    }

    /**
     * Deliberately carries no token material, encrypted or otherwise -- see
     * {@link DeviceTokenService}'s class doc. The raw token the client just sent must never be
     * echoed back in this response either; there is no field here it could occupy.
     */
    public record DeviceTokenRegisteredDto(UUID id, String platform, Instant registeredAt) {
        static DeviceTokenRegisteredDto from(DeviceToken token) {
            return new DeviceTokenRegisteredDto(token.getId(), token.getPlatform(),
                    token.getLastSeenAt());
        }
    }

    @PostMapping
    public ApiResponse<DeviceTokenRegisteredDto> register(
            @Valid @RequestBody RegisterDeviceTokenRequest request) {
        DeviceToken saved =
                deviceTokenService.register(currentUser.id(), request.platform(), request.token());
        return ApiResponse.ok(DeviceTokenRegisteredDto.from(saved), "Device registered");
    }

    @PostMapping("/revoke")
    public ApiResponse<Void> revoke(@Valid @RequestBody RevokeDeviceTokenRequest request) {
        deviceTokenService.revoke(currentUser.id(), request.token());
        return ApiResponse.ok(null, "Device token revoked");
    }
}
