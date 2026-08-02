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
                new RefreshTokenService.IssuedToken("test-refresh-token", java.time.Instant.now().plusSeconds(3600)));

        // Bug fix: same shape of bug, one line later in register() -- otpService.issueOtp(...)
        // is dereferenced immediately too (.delivered()), and this mock was never stubbed either.
        // Fixing the refreshTokenService NPE above was necessary but not sufficient; this is what
        // was still failing right after it.
        OtpService otpService = mock(OtpService.class);
        when(otpService.issueOtp(any(), any(), any())).thenReturn(new OtpService.OtpIssueResult("123456", true));

        // register() checks platformSettingsService.getEntity().isRegistrationsEnabled() before
        // doing anything else -- defaults to a real entity with registrationsEnabled=true (the
        // same default V27__platform_settings.sql seeds) so every existing test below, none of
        // which is about this new gate, keeps passing unchanged.
        platformSettingsService = mock(PlatformSettingsService.class);
        when(platformSettingsService.getEntity()).thenReturn(new com.finora.entity.PlatformSettings());

        authService = new AuthService(
                userRepository, mock(CategoryRepository.class), mock(PasswordResetTokenRepository.class),
                mock(PasswordEncoder.class), mock(JwtService.class), mock(AuthenticationManager.class),
                mock(AuditService.class), refreshTokenService, mock(EmailService.class),
                new EmailProperties(), otpService, platformSettingsService
        );
    }

    private RegisterRequest request(String email, String phoneNumber) {
        return new RegisterRequest(email, "Password123", "Jane Doe", phoneNumber);
    }

    @Test
    void register_withAnEmailAlreadyOnFile_isRejectedBeforeAnyUserIsSaved() {
        when(userRepository.existsByEmail("jane@example.com")).thenReturn(true);

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
        verify(userRepository, never()).existsByPhoneNumber(anyString());
    }

    @Test
    void register_withAPhoneNumberAlreadyOnFile_isRejectedBeforeAnyUserIsSaved() {
        when(userRepository.existsByEmail("newperson@example.com")).thenReturn(false);
        when(userRepository.existsByPhoneNumber("+919876500001")).thenReturn(true);

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
        when(userRepository.existsByEmail("newperson@example.com")).thenReturn(false);
        when(userRepository.existsByPhoneNumber("+919876500002")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
            return u;
        });

        authService.register(request("newperson@example.com", "+919876500002"));

        verify(userRepository).save(any(User.class));
    }

    /**
     * The masked phone lets VerifyPhone.tsx show which number a code was actually sent to,
     * catching a wrong/missing country code on screen instead of a silent Twilio delivery
     * failure -- see PhoneMasking's own class doc for the incident this was built to prevent a
     * repeat of.
     */
    @Test
    void register_returnsTheMaskedPhoneNumber_forVerifyPhoneToDisplay() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.existsByPhoneNumber(anyString())).thenReturn(false);
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
        when(userRepository.existsByEmail("jane@example.com")).thenReturn(false);
        when(userRepository.existsByPhoneNumber(anyString())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
            return u;
        });

        authService.register(new RegisterRequest("  jane@example.com  ", "Password123", "  Jane Doe  ", "+919876500003"));

        verify(userRepository).existsByEmail("jane@example.com");
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

        verify(userRepository, never()).existsByEmail(anyString());
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
        when(userRepository.existsByEmail("supportcreated@example.com")).thenReturn(false);
        when(userRepository.existsByPhoneNumber("+919876500010")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
            return u;
        });

        User created = authService.adminCreateUser(
                request("supportcreated@example.com", "+919876500010"), UUID.randomUUID());

        assertThat(created.getEmail()).isEqualTo("supportcreated@example.com");
        verify(userRepository).save(any(User.class));
    }
}
