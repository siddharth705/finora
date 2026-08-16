package com.finora.controller;

import com.finora.config.CorrelationIdFilter;
import com.finora.dto.AccountLifecycleDtos.*;
import com.finora.dto.ApiResponse;
import com.finora.dto.MeAccessDto;
import com.finora.dto.PasswordChangeDtos.*;
import com.finora.dto.UserSettingsDto;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.UserRepository;
import com.finora.security.CurrentUser;
import com.finora.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import com.finora.service.AuditService;
import com.finora.service.AuthorizationService;
import com.finora.service.DataExportService;
import com.finora.service.PasswordChangeService;
import com.finora.service.PhoneChangeService;
import com.finora.service.UserAccountLifecycleService;
import com.finora.service.UserSettingsService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/users/me")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserSettingsService userSettingsService;
    private final CurrentUser currentUser;
    private final UserRepository userRepository;
    private final AuthorizationService authorizationService;
    private final PasswordChangeService passwordChangeService;
    private final PhoneChangeService phoneChangeService;
    private final UserAccountLifecycleService accountLifecycleService;
    private final DataExportService dataExportService;
    private final AuditService auditService;

    public UserController(UserSettingsService userSettingsService, CurrentUser currentUser,
                           UserRepository userRepository, AuthorizationService authorizationService,
                           PasswordChangeService passwordChangeService, PhoneChangeService phoneChangeService,
                           UserAccountLifecycleService accountLifecycleService,
                           DataExportService dataExportService, AuditService auditService) {
        this.userSettingsService = userSettingsService;
        this.currentUser = currentUser;
        this.userRepository = userRepository;
        this.authorizationService = authorizationService;
        this.passwordChangeService = passwordChangeService;
        this.phoneChangeService = phoneChangeService;
        this.accountLifecycleService = accountLifecycleService;
        this.dataExportService = dataExportService;
        this.auditService = auditService;
    }

    @GetMapping
    public ApiResponse<UserSettingsDto> get() {
        return ApiResponse.ok(userSettingsService.get(currentUser.id()));
    }

    @PutMapping
    public ApiResponse<UserSettingsDto> update(@Valid @RequestBody UserSettingsDto.UpdateRequest request) {
        return ApiResponse.ok(userSettingsService.update(currentUser.id(), request), "Preferences saved");
    }

    /**
     * The authenticated, OTP-gated Change Password flow -- see PasswordChangeService's own doc
     * comment for the full start -> verify-otp -> complete state machine these three back.
     */
    @PostMapping("/password-change/start")
    public ApiResponse<StartResponse> startPasswordChange(@Valid @RequestBody StartRequest request) {
        return ApiResponse.ok(passwordChangeService.start(currentUser.id(), request));
    }

    @PostMapping("/password-change/verify-otp")
    public ApiResponse<VerifyOtpResponse> verifyPasswordChangeOtp(@Valid @RequestBody VerifyOtpRequest request) {
        return ApiResponse.ok(passwordChangeService.verifyOtp(currentUser.id(), request));
    }

    /**
     * Which session is asking comes from the access token's {@code sid} claim, published as a
     * request attribute by {@code JwtAuthFilter} -- not from a refresh token in the body. BH-012
     * removed the web client's ability to read its own refresh token (it is HttpOnly now), and the
     * cookie is path-scoped to {@code /api/v1/auth} so it never reaches here. The sid was always
     * the more accurate answer anyway: it survives rotation, and a token does not.
     */
    @PostMapping("/password-change/complete")
    public ApiResponse<CompleteResponse> completePasswordChange(
            @Valid @RequestBody CompleteRequest request, HttpServletRequest httpRequest) {
        UUID currentSessionId = (UUID) httpRequest.getAttribute(JwtAuthFilter.SESSION_ID_ATTRIBUTE);
        return ApiResponse.ok(passwordChangeService.complete(currentUser.id(), request, currentSessionId));
    }

    /**
     * The authenticated, OTP-gated Change Phone Number flow -- see PhoneChangeService's own doc
     * comment for the full start -> verify-otp -> complete state machine these three back. Reached
     * from VerifyPhone.tsx when Firebase can't send a code to the number currently on file.
     */
    @PostMapping("/phone-change/start")
    public ApiResponse<com.finora.dto.PhoneChangeDtos.StartResponse> startPhoneChange(
            @Valid @RequestBody com.finora.dto.PhoneChangeDtos.StartRequest request) {
        return ApiResponse.ok(phoneChangeService.start(currentUser.id(), request));
    }

    @PostMapping("/phone-change/verify-otp")
    public ApiResponse<com.finora.dto.PhoneChangeDtos.VerifyOtpResponse> verifyPhoneChangeOtp(
            @Valid @RequestBody com.finora.dto.PhoneChangeDtos.VerifyOtpRequest request) {
        return ApiResponse.ok(phoneChangeService.verifyOtp(currentUser.id(), request));
    }

    @PostMapping("/phone-change/complete")
    public ApiResponse<com.finora.dto.PhoneChangeDtos.CompleteResponse> completePhoneChange(
            @Valid @RequestBody com.finora.dto.PhoneChangeDtos.CompleteRequest request) {
        return ApiResponse.ok(phoneChangeService.complete(currentUser.id(), request));
    }

    /**
     * The caller's own effective roles + permissions. Any authenticated user can read their own
     * access (there's no permission gate here beyond "you have a valid token") -- the sensitive
     * operations those permissions unlock are each gated individually on the endpoints that
     * perform them. This exists specifically so the admin portal (frontend-admin/) can ask "does
     * this account have any admin-relevant access" right after login, before showing the admin
     * shell at all -- see AuthorizationService.meAccess.
     */
    @GetMapping("/access")
    public ApiResponse<MeAccessDto> access() {
        User user = userRepository.findById(currentUser.id())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        return ApiResponse.ok(authorizationService.meAccess(user));
    }

    /** Reversible: blocks login, signs out every device, retains all data -- see
     *  UserAccountLifecycleService.deactivate's own doc comment. The frontend clears its own
     *  local session immediately after this succeeds (there is nothing left to be signed in to). */
    @PostMapping("/account/deactivate")
    public ApiResponse<DeactivateResponse> deactivate(@Valid @RequestBody DeactivateRequest request) {
        accountLifecycleService.deactivate(currentUser.id(), request.currentPassword(), request.reason(), request.note());
        return ApiResponse.ok(new DeactivateResponse(
                "Your account has been deactivated. Sign in again any time to reactivate it."));
    }

    /** Irreversible -- see UserAccountLifecycleService.requestDeletion's own doc comment. Frontend
     *  clears its own local session immediately after this succeeds, same as deactivate() above. */
    @PostMapping("/account/delete")
    public ApiResponse<DeleteAccountResponse> deleteAccount(@Valid @RequestBody DeleteAccountRequest request) {
        accountLifecycleService.requestDeletion(currentUser.id(), request.sessionId());
        return ApiResponse.ok(new DeleteAccountResponse(
                "Your account is scheduled for deletion. You've been signed out everywhere."));
    }

    /**
     * "Download My Data" (Phase C) -- see DataExportService's own doc comment for the full design,
     * in particular why this is split into a synchronous gather ({@code buildBundle}, which also
     * proves the password and can therefore still return a clean error response) followed by a
     * streamed ZIP write ({@code writeZip}, which cannot: headers are already committed by the
     * time it runs).
     *
     * <p>{@code POST}, not {@code GET} -- matches deactivate()/deleteAccount()'s own convention
     * for a password-gated self-service action, and lets the password travel in the body.
     *
     * <p>Bug fix (review): a mid-stream failure used to be caught, logged and audited but then
     * swallowed -- the lambda returned normally, and since HTTP 200 and the response headers were
     * already committed by then, the client received what looked like a complete, successful
     * download of a truncated/corrupt ZIP, with no error surfaced anywhere. {@code writeTo}'s own
     * signature permits {@code IOException}, so the fix re-throws instead: Spring aborts the
     * connection rather than completing it, which is the only way left, once bytes are already
     * flowing, to make the client's request actually fail instead of silently succeeding.
     */
    @PostMapping("/data-export")
    public ResponseEntity<StreamingResponseBody> exportData(@Valid @RequestBody ExportDataRequest request) {
        UUID userId = currentUser.id();
        DataExportService.ExportBundle bundle = dataExportService.buildBundle(userId, request.currentPassword());
        auditService.record(userId, "DATA_EXPORT_REQUESTED", "User", userId, Map.of());

        String fileName = "finora-data-export-" + LocalDate.now() + ".zip";
        // Captured on this (synchronous) request thread, not read again inside the callback below:
        // StreamingResponseBody runs its callback on a separate async-dispatch thread, and MDC is
        // thread-local, so CorrelationIdFilter's own key is already gone (cleared in its finally,
        // once this method returns) by the time that thread runs. Restoring it explicitly is what
        // lets DATA_EXPORTED/DATA_EXPORT_FAILED carry the same requestId DATA_EXPORT_REQUESTED did.
        String requestId = MDC.get(CorrelationIdFilter.MDC_KEY);
        StreamingResponseBody body = out -> {
            if (requestId != null) MDC.put(CorrelationIdFilter.MDC_KEY, requestId);
            try {
                try {
                    dataExportService.writeZip(userId, bundle, out);
                } catch (Exception e) {
                    log.error("Data export failed mid-stream for user {}: {}", userId, e.getMessage(), e);
                    auditService.record(userId, "DATA_EXPORT_FAILED", "User", userId,
                            Map.of("error", e.getClass().getSimpleName()));
                    throw (e instanceof IOException ioe) ? ioe : new IOException("Data export failed mid-stream", e);
                }
                // Bug fix (self-review): this used to sit inside the try block above, so a
                // transient failure recording success (e.g. a momentary DB blip) after writeZip
                // had ALREADY fully delivered the ZIP was caught by the SAME handler as a real
                // mid-stream failure -- misattributing a successful export as DATA_EXPORT_FAILED
                // in the user's own audit trail, then attempting to throw an IOException to
                // "abort" a connection that had already completed successfully. Every byte is on
                // the wire by this point; there is nothing left to abort, so a failure here is
                // logged, not misrecorded as a failure and not rethrown.
                try {
                    auditService.record(userId, "DATA_EXPORTED", "User", userId,
                            Map.of("statementCount", bundle.statementSummaries().size()));
                } catch (Exception e) {
                    log.warn("Data export for user {} succeeded but recording the DATA_EXPORTED audit event failed: {}",
                            userId, e.getMessage(), e);
                }
            } finally {
                if (requestId != null) MDC.remove(CorrelationIdFilter.MDC_KEY);
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .body(body);
    }
}
