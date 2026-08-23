package com.finora.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * The step-up-gated, session-based Change Email flow -- see EmailChangeService for the full
 * start -> verify -> complete state machine these back. Mirrors PhoneChangeDtos' shape (three
 * separate request/response pairs, not one combined call), with one structural difference: unlike
 * phone-change, this flow DOES have a "prove you still are who you say you are" first step
 * (StartRequest carries the same currentPassword/googleIdToken/appleIdToken triple
 * PasswordChangeDtos.StartRequest does) -- email is the account's password-reset delivery channel,
 * a lower bar to authorize changing it than phone-change accepts would be worse, not better.
 */
public class EmailChangeDtos {

    /** Exactly one of currentPassword/googleIdToken/appleIdToken is actually required -- which one
     *  depends on the account's own User.signInMethod, known only server-side. See
     *  GoogleReauthVerifier and PasswordChangeDtos.StartRequest's identical field, copied here
     *  rather than shared since each DTO class already keeps its own field set self-contained. */
    public record StartRequest(
            String currentPassword, String googleIdToken, String appleIdToken,
            @NotBlank @Email String newEmail
    ) {}

    /** sessionId is what the frontend carries forward to the next two steps -- deliberately
     *  opaque (a random UUID), not something a client could compute or guess. No masked-echo
     *  field the way PhoneChangeDtos.StartResponse has one: EmailMasking exists only for log
     *  lines (see its own doc comment), not display, and the frontend already has the unmasked
     *  new address anyway -- the user just typed it in.
     *
     *  <p>devVerifyLink mirrors AuthDtos.ForgotPasswordResponse.devResetLink -- populated only
     *  when no email provider is configured (this environment has none wired up), so the flow
     *  stays genuinely testable rather than a dead end with no way to reach verify(). Null once
     *  a real provider is configured. */
    public record StartResponse(String sessionId, String devVerifyLink) {}

    /** token proves control of the NEW address -- the raw value from the link this flow emailed
     *  to it, checked against this session's own stored hash (see EmailChangeSession). */
    public record VerifyRequest(@NotBlank String sessionId, @NotBlank String token) {}

    public record VerifyResponse(String message) {}

    public record CompleteRequest(@NotBlank String sessionId) {}

    /** email echoes back the address now on the account, so the frontend can update its own
     *  local state without a second GET /users/me round trip -- same reasoning as
     *  PhoneChangeDtos.CompleteResponse.phoneNumber. */
    public record CompleteResponse(String message, String email) {}
}
