package com.finora.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The OTP-gated, session-based authenticated Change Password flow -- see PasswordChangeService
 * for the full state machine these back. Deliberately three separate request/response shapes
 * (not one combined "change password" call): each step is its own round trip so the frontend can
 * show progress and the backend can enforce that step N+1 never runs before step N actually
 * succeeded server-side.
 */
public class PasswordChangeDtos {

    /** currentPassword is the proof of ownership that starts the flow -- no token/OTP yet at
     *  this point, the same way it is for any "re-enter your password" confirmation elsewhere. */
    public record StartRequest(@NotBlank String currentPassword) {}

    /** sessionId is the only thing the frontend needs to carry forward to the next two steps --
     *  deliberately opaque (a random UUID), not something a client could compute or guess.
     *  devOtp mirrors every other OTP-issuing endpoint's own field: only populated when no SMS
     *  provider is configured. */
    public record StartResponse(String sessionId, String maskedPhone, String devOtp) {}

    public record VerifyOtpRequest(@NotBlank String sessionId, @NotBlank String otp) {}

    public record VerifyOtpResponse(boolean verified, String message) {}

    /** signOutOtherDevices controls whether every OTHER active session gets revoked once the
     *  password is updated -- the device completing this flow always stays signed in either way
     *  (see currentRefreshToken below), unlike the old single-step flow this replaces, which
     *  unconditionally logged out every device including the one making the change.
     *  currentRefreshToken is this device's own stored refresh token, sent so the backend can
     *  positively identify (and exclude) it from revocation -- an access token alone doesn't
     *  carry enough information to know which refresh token belongs to this browser tab. */
    public record CompleteRequest(
            @NotBlank String sessionId,
            @NotBlank @Size(min = 8, max = 72, message = AuthDtos.PASSWORD_SIZE_MESSAGE) String newPassword,
            boolean signOutOtherDevices,
            @NotBlank String currentRefreshToken
    ) {}

    public record CompleteResponse(String message, boolean otherDevicesSignedOut) {}
}
