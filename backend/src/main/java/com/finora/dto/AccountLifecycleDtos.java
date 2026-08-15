package com.finora.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The self-service account lifecycle -- see UserAccountLifecycleService for deactivate (today)
 *  and delete-request/purge (Phase B, to follow). */
public class AccountLifecycleDtos {

    /** currentPassword is the re-auth gate -- deactivation is reversible and the caller already
     *  holds a valid session, so this is the same bar as any other "re-enter your password"
     *  confirmation, not the OTP-gated PasswordChangeService flow (that's reserved for the
     *  irreversible delete request in Phase B).
     *
     *  reason is required (product decision: churn-analysis data is worth the small extra step on
     *  an otherwise-reversible action) and validated against User.DEACTIVATION_REASONS by
     *  UserAccountLifecycleService, not here -- @Pattern against a Java constant list would drift
     *  the moment one side changed without the other. note is optional and deliberately unbounded
     *  by content (only by length, matching the column) -- it is never re-parsed, only ever read
     *  by a human doing churn analysis. */
    public record DeactivateRequest(
            @NotBlank String currentPassword,
            @NotBlank String reason,
            @Size(max = 500) String note
    ) {}

    public record DeactivateResponse(String message) {}
}
