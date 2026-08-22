package com.finora.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The self-service account lifecycle -- see UserAccountLifecycleService for deactivate and
 *  delete-request/purge. */
public class AccountLifecycleDtos {

    /** currentPassword/googleIdToken/appleIdToken is the re-auth gate -- deactivation is
     *  reversible and the caller already holds a valid session, so this is the same bar as any
     *  other "re-enter your credential" confirmation, not the OTP-gated PasswordChangeService
     *  flow (that's reserved for the irreversible delete request in Phase B). Exactly one of the
     *  three is required, none @NotBlank -- see PasswordChangeDtos.StartRequest's identical
     *  shape and GoogleReauthVerifier, which is what actually enforces that.
     *
     *  reason is required (product decision: churn-analysis data is worth the small extra step on
     *  an otherwise-reversible action) and validated against User.DEACTIVATION_REASONS by
     *  UserAccountLifecycleService, not here -- @Pattern against a Java constant list would drift
     *  the moment one side changed without the other. note is optional and deliberately unbounded
     *  by content (only by length, matching the column) -- it is never re-parsed, only ever read
     *  by a human doing churn analysis. */
    public record DeactivateRequest(
            String currentPassword,
            String googleIdToken,
            String appleIdToken,
            @NotBlank String reason,
            @Size(max = 500) String note
    ) {}

    public record DeactivateResponse(String message) {}

    /** sessionId is a PasswordChangeSession id already at OTP_VERIFIED -- the frontend drives the
     *  exact same start()/verifyOtp() calls ChangePasswordModal uses. See
     *  UserAccountLifecycleService.requestDeletion and PasswordChangeService.
     *  consumeForAccountDeletion. No currentPassword field: the session itself is that proof, same
     *  as CompleteRequest never re-asks for it either -- this is deliberately a higher bar than
     *  DeactivateRequest's password-only re-auth, since this action is irreversible. */
    public record DeleteAccountRequest(@NotBlank String sessionId) {}

    public record DeleteAccountResponse(String message) {}

    /** currentPassword/googleIdToken/appleIdToken is the re-auth gate for Phase C's data export --
     *  same bar as DeactivateRequest's, not the OTP tier: the export is a pure read (reversible,
     *  changes nothing), but it bundles unmasked original bank statement files into one
     *  downloadable artifact, so a plain re-auth step is worth the friction even though every
     *  table in it is already individually readable through existing endpoints with just a JWT.
     *  See DataExportService's own doc comment. Exactly one of the three fields is required, same
     *  as DeactivateRequest above. */
    public record ExportDataRequest(String currentPassword, String googleIdToken, String appleIdToken) {}
}
