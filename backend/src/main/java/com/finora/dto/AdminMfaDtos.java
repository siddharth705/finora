package com.finora.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** SEC-03 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). */
public class AdminMfaDtos {

    private AdminMfaDtos() {}

    public record StatusResponse(boolean enabled) {}

    /** {@code provisioningUri} is what a QR-code renderer turns into a scannable image
     *  client-side -- this backend never generates the image itself. {@code secret} is the same
     *  value in typeable form, for an authenticator app's manual-entry fallback. */
    public record EnrollResponse(String secret, String provisioningUri) {}

    public record ConfirmRequest(@NotBlank String code) {}

    /** {@code recoveryCodes} is the ONLY time these are ever returned in the clear -- only
     *  hashes are persisted (see AdminMfaRecoveryCode). The frontend's job is to make the user
     *  actually look at these before dismissing this response, not to let them be re-fetched. */
    public record ConfirmResponse(List<String> recoveryCodes) {}

    public record DisableRequest(String currentPassword, String googleIdToken) {}
}
