package com.finora.service;

import com.finora.config.EmailProperties;
import com.finora.dto.AuthDtos.RegisterRequest;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.CategoryRepository;
import com.finora.repository.PasswordResetTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Locks in AuthService.register()'s two pre-save uniqueness checks. Both a duplicate email and a
 * duplicate phone number must be rejected with 409 CONFLICT before any User row is written --
 * this matters beyond simple duplicate-prevention because email-or-phone login
 * (resolveEmailForLogin) assumes a phone number resolves to at most one account. If two accounts
 * could ever share a phone number, login-by-phone would become ambiguous.
 */
class AuthServiceRegisterTest {

    private UserRepository userRepository;
    private PlatformSettingsService platformSettingsService;
    private AuditService auditService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);

        // Bug fix: same root cause as AuthServiceLoginTest -- register()'s success path also
        // calls refreshTokenService.issue(...) and immediately dereferences the result, so an
        // unstubbed mock (which returns null for this plain-record return type) NPEs. Only
        // affected the two tests below that actually reach a successful save(); the two
        // duplicate-rejection tests above throw before getting there.
        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
        when(refreshTokenService.issue(any())).thenReturn(
                new RefreshTokenService.IssuedToken("test-refresh-token", java.time.Instant.now().plusSeconds(3600), java.util.UUID.randomUUID()));

        // register() checks platformSettingsService.getEntity().isRegistrationsEnabled() before
        // doing anything else -- defaults to a real entity with registrationsEnabled=true (the
        // same default V27__platform_settings.sql seeds) so every existing test below, none of
        // which is about this new gate, keeps passing unchanged.
        platformSettingsService = mock(PlatformSettingsService.class);
        when(platformSettingsService.getEntity()).thenReturn(new com.finora.entity.PlatformSettings());

        // Same class of bug as refreshTokenService above: register()'s success path now also
        // calls auditService.record(..., emailResult.provider().name(), ...) right after sending
        // the welcome email, dereferencing whatever sendWelcomeEmail() returns.
        EmailProvider emailProvider = mock(EmailProvider.class);
        when(emailProvider.sendWelcomeEmail(any(), any()))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "test-message-id"));
        // D-23. register() now also sends a verification email the same way, right after the
        // welcome email -- same dereferencing hazard the comment above already covers.
        when(emailProvider.sendEmailVerificationEmail(any(), any()))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "test-message-id"));

        auditService = mock(AuditService.class);
        authService = new AuthService(
                userRepository, mock(CategoryRepository.class), mock(PasswordResetTokenRepository.class),
                mock(com.finora.repository.AccountReactivationTokenRepository.class),
                mock(com.finora.repository.EmailVerificationTokenRepository.class),
                mock(PasswordEncoder.class), mock(JwtService.class), mock(AuthenticationManager.class),
                auditService, refreshTokenService, emailProvider,
                new EmailProperties(), mock(PhoneVerificationProvider.class), platformSettingsService,
                mock(PasswordHistoryService.class), new IdentityLookup(userRepository),
                mock(com.finora.config.RequestMetadata.class),
                mock(com.finora.service.SubscriptionService.class),
                mock(com.finora.service.ReferralService.class),
                // SEC-07: same-thread executor -- runs the dispatched email/audit work
                // synchronously so assertions against it don't race a real background thread.
                Runnable::run,
                // SEC-03: no MFA gate interference for tests unrelated to it -- an
                // unstubbed mock's isEnabled() returns false by default.
                mock(AdminMfaService.class)
        );
    }

    private RegisterRequest request(String email, String phoneNumber) {
        return new RegisterRequest(email, "Password123", "Jane Doe", phoneNumber, null);
    }

    @Test
    void register_withAnEmailAlreadyOnFile_isRejectedBeforeAnyUserIsSaved() {
        when(userRepository.existsByEmailIgnoreCaseAndAccountScope("jane@example.com", "USER")).thenReturn(true);

        try {
            authService.register(request("jane@example.com", "+919876500001"));
            throw new AssertionError("Expected register() to throw for a duplicate email");
        } catch (ApiException e) {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getMessage()).isEqualTo("An account with this email already exists.");
        }

        verify(userRepository, never()).save(any());
        // The email check is deliberately checked first -- a duplicate email should never even
        // reach the phone-number lookup.
        verify(userRepository, never()).existsByPhoneNumberAndAccountScope(anyString(), anyString());
    }

    @Test
    void register_withAPhoneNumberAlreadyOnFile_isRejectedBeforeAnyUserIsSaved() {
        when(userRepository.existsByEmailIgnoreCaseAndAccountScope("newperson@example.com", "USER")).thenReturn(false);
        when(userRepository.existsByPhoneNumberAndAccountScope("+919876500001", "USER")).thenReturn(true);

        try {
            authService.register(request("newperson@example.com", "+919876500001"));
            throw new AssertionError("Expected register() to throw for a duplicate phone number");
        } catch (ApiException e) {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getMessage()).isEqualTo("An account with this mobile number already exists.");
        }

        verify(userRepository, never()).save(any());
    }

    @Test
    void register_withAUniqueEmailAndPhoneNumber_proceedsToSaveTheNewUser() {
        when(userRepository.existsByEmailIgnoreCaseAndAccountScope("newperson@example.com", "USER")).thenReturn(false);
        when(userRepository.existsByPhoneNumberAndAccountScope("+919876500002", "USER")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
            return u;
        });

        authService.register(request("newperson@example.com", "+919876500002"));

        verify(userRepository).save(any(User.class));
        verify(auditService).record(any(), eq("EMAIL_SENT"), eq("User"), any(),
                argThat(metadata -> "welcome".equals(metadata.get("type")) && Boolean.TRUE.equals(metadata.get("success"))));
    }

    /**
     * The masked phone lets VerifyPhone.tsx show which number a code was actually sent to,
     * catching a wrong/missing country code on screen instead of a silent Twilio delivery
     * failure -- see PhoneMasking's own class doc for the incident this was built to prevent a
     * repeat of.
     */
    @Test
    void register_returnsTheMaskedPhoneNumber_forVerifyPhoneToDisplay() {
        when(userRepository.existsByEmailIgnoreCaseAndAccountScope(anyString(), anyString())).thenReturn(false);
        when(userRepository.existsByPhoneNumberAndAccountScope(anyString(), anyString())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
            return u;
        });

        var response = authService.register(request("newperson@example.com", "+919876500002"));

        assertThat(response.maskedPhone()).isEqualTo("+•••••••••002");
    }

    @Test
    void register_withLeadingOrTrailingWhitespaceInEmailAndName_savesTrimmedValues() {
        // RegisterRequest.fullName's @Pattern deliberately tolerates surrounding whitespace (so
        // a stray space typed at either end doesn't get rejected outright at the DTO layer) --
        // which puts the burden of actually trimming on register() itself. This locks in that
        // both the duplicate-email check and the persisted row use the trimmed value, not the
        // raw one, so "  jane@example.com" can't slip past a uniqueness check keyed on
        // "jane@example.com".
        when(userRepository.existsByEmailIgnoreCaseAndAccountScope("jane@example.com", "USER")).thenReturn(false);
        when(userRepository.existsByPhoneNumberAndAccountScope(anyString(), anyString())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
            return u;
        });

        authService.register(new RegisterRequest("  jane@example.com  ", "Password123", "  Jane Doe  ", "+919876500003", null));

        verify(userRepository).existsByEmailIgnoreCaseAndAccountScope("jane@example.com", "USER");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("jane@example.com");
        assertThat(captor.getValue().getFullName()).isEqualTo("Jane Doe");
    }

    /**
     * Locks in the new PlatformSettingsService.registrationsEnabled gate (PlatformSettingsController
     * / V27__platform_settings.sql) -- when an admin turns public sign-up off, register() must
     * reject before it ever reaches the uniqueness checks or writes a row, not just after.
     */
    @Test
    void register_whenRegistrationsAreDisabled_isRejectedBeforeAnyUniquenessCheck() {
        var settings = new com.finora.entity.PlatformSettings();
        settings.setRegistrationsEnabled(false);
        when(platformSettingsService.getEntity()).thenReturn(settings);

        try {
            authService.register(request("newperson@example.com", "+919876500009"));
            throw new AssertionError("Expected register() to throw while registrations are disabled");
        } catch (ApiException e) {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        verify(userRepository, never()).existsByEmailIgnoreCaseAndAccountScope(anyString(), anyString());
        verify(userRepository, never()).save(any());
    }

    /**
     * adminCreateUser() (support-assisted signup, AdminUserController) deliberately does NOT go
     * through the registrationsEnabled gate above -- an admin closing public sign-up shouldn't
     * also block themselves from creating accounts for people they're directly helping.
     */
    @Test
    void adminCreateUser_succeedsEvenWhenPublicRegistrationsAreDisabled() {
        var settings = new com.finora.entity.PlatformSettings();
        settings.setRegistrationsEnabled(false);
        when(platformSettingsService.getEntity()).thenReturn(settings);
        when(userRepository.existsByEmailIgnoreCaseAndAccountScope("supportcreated@example.com", "USER")).thenReturn(false);
        when(userRepository.existsByPhoneNumberAndAccountScope("+919876500010", "USER")).thenReturn(false);  // synthetic-ok: sequential test number, not a real subscriber
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
            return u;
        });

        User created = authService.adminCreateUser(
                request("supportcreated@example.com", "+919876500010"), UUID.randomUUID());  // synthetic-ok: sequential test number, not a real subscriber

        assertThat(created.getEmail()).isEqualTo("supportcreated@example.com");
        verify(userRepository).save(any(User.class));
    }
}
