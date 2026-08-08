package com.finora.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The OTP-gated, session-based authenticated Change Password flow -- see PasswordChangeService
 * for the full state machine these back. Deliberately three separate request/response shapes
 * (not one combined "change password" call): each step is its own round trip so the frontend can
 * show progress and the backend can enforce that step N+1 never runs before step N actually
 * succeeded server-side.
 *
 * OTP verification itself now happens via Firebase Phone Authentication (see
 * PhoneVerificationProvider) -- the frontend's own Firebase client SDK sends and confirms
 * the code directly against Firebase; the backend only ever sees the resulting ID token.
 */
public class PasswordChangeDtos {

    /** currentPassword is the proof of ownership that starts the flow -- no token/OTP yet at
     *  this point, the same way it is for any "re-enter your password" confirmation elsewhere. */
    public record StartRequest(@NotBlank String currentPassword) {}

    /** sessionId is what the frontend carries forward to the next two steps -- deliberately
     *  opaque (a random UUID), not something a client could compute or guess. phoneNumber (the
     *  real, unmasked number) is what the frontend hands to Firebase's signInWithPhoneNumber();
     *  maskedPhone is what it shows on screen while doing so. */
    public record StartResponse(String sessionId, String phoneNumber, String maskedPhone) {}

    /** firebaseIdToken proves phone ownership -- see PhoneVerificationProvider's own doc
     *  comment. By the time a token exists at all, Firebase has already confirmed the code
     *  client-side; a wrong code never produces a token to send here in the first place. */
    public record VerifyOtpRequest(@NotBlank String sessionId, @NotBlank String firebaseIdToken) {}

    public record VerifyOtpResponse(String message) {}

    /** signOutOtherDevices controls whether every OTHER active session gets revoked once the
     *  password is updated -- the device completing this flow always stays signed in either way,
     *  unlike the old single-step flow this replaces, which unconditionally logged out every
     *  device including the one making the change.
     *
     *  <p>currentRefreshToken is DEPRECATED AND IGNORED. Its comment used to say "an access token
     *  alone doesn't carry enough information to know which refresh token belongs to this browser
     *  tab" -- that stopped being true when the access token gained its {@code sid} claim, which
     *  names the session directly and, unlike a token, survives rotation. The server now reads
     *  that claim (see UserController.completePasswordChange) and this field decides nothing.
     *
     *  <p>Kept on the record, and no longer {@code @NotBlank}, purely for the mobile support
     *  window: installed builds still send it and must not start failing validation. Relaxing a
     *  required request field is non-breaking under
     *  docs/engineering/api-compatibility-policy.md; removing the field would not be. Delete it
     *  once no supported client sends it. */
    public record CompleteRequest(
            @NotBlank String sessionId,
            @NotBlank @Size(min = 8, max = 72, message = AuthDtos.PASSWORD_SIZE_MESSAGE) String newPassword,
            boolean signOutOtherDevices,
            String currentRefreshToken
    ) {}

    public record CompleteResponse(String message, boolean otherDevicesSignedOut) {}
}
