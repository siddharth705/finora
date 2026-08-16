package com.finora.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The OTP-gated, session-based Change Phone Number flow -- see PhoneChangeService for the full
 * start -> verify-otp -> complete state machine these back. Mirrors PasswordChangeDtos' shape
 * (three separate request/response pairs, not one combined call) for the same reason: each step
 * is its own round trip so the backend can enforce that step N+1 never runs before step N actually
 * succeeded server-side.
 *
 * Unlike password change, there is no "prove you still are who you say you are" first step here --
 * the caller is already authenticated, and the OTP itself is the whole proof this flow needs: it
 * proves control of the NEW number, which is the only fact that matters before committing it.
 */
public class PhoneChangeDtos {

    /** newPhoneNumber uses the same pattern registration validates against (RegisterRequest's own
     *  field) -- accepts either a leading "+" or a bare 10-15 digit string. */
    public record StartRequest(
            @NotBlank @Pattern(regexp = AuthDtos.PHONE_REGEXP, message = AuthDtos.PHONE_MESSAGE)
            String newPhoneNumber
    ) {}

    /** sessionId is what the frontend carries forward to the next two steps. maskedPhone echoes
     *  back the new number (masked) for on-screen confirmation -- unlike PasswordChangeDtos'
     *  StartResponse there's no unmasked phoneNumber field to return: the frontend already has the
     *  unmasked value itself, since the user just typed it in. */
    public record StartResponse(String sessionId, String maskedPhone) {}

    /** firebaseIdToken proves control of the number this session is trying to move to -- by the
     *  time a token exists at all, Firebase has already confirmed the code client-side against
     *  THAT number. */
    public record VerifyOtpRequest(@NotBlank String sessionId, @NotBlank String firebaseIdToken) {}

    public record VerifyOtpResponse(String message) {}

    public record CompleteRequest(@NotBlank String sessionId) {}

    /** phoneNumber echoes back the number now on the account, so the frontend can update its own
     *  local state without a second GET /users/me round trip. */
    public record CompleteResponse(String message, String phoneNumber) {}
}
