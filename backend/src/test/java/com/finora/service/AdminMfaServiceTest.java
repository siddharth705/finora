package com.finora.service;

import com.finora.dto.AdminMfaDtos.ConfirmResponse;
import com.finora.dto.AdminMfaDtos.EnrollResponse;
import com.finora.entity.AdminMfaChallenge;
import com.finora.entity.AdminMfaRecoveryCode;
import com.finora.entity.AdminTotpCredential;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.repository.AdminMfaChallengeRepository;
import com.finora.repository.AdminMfaRecoveryCodeRepository;
import com.finora.repository.AdminTotpCredentialRepository;
import com.finora.repository.UserRepository;
import com.finora.security.crypto.EncryptedValue;
import com.finora.security.crypto.EncryptionService;
import com.finora.security.mfa.TotpGenerator;
import com.finora.util.TokenHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** SEC-03 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). */
class AdminMfaServiceTest {

    private AdminTotpCredentialRepository credentialRepository;
    private AdminMfaRecoveryCodeRepository recoveryCodeRepository;
    private AdminMfaChallengeRepository challengeRepository;
    private UserRepository userRepository;
    private EncryptionService encryptionService;
    private GoogleReauthVerifier googleReauthVerifier;
    private AuditService auditService;
    private AdminMfaService service;
    private final UUID userId = UUID.randomUUID();
    private static final String SECRET = TotpGenerator.generateSecret();
    private static final EncryptedValue ENCRYPTED = new EncryptedValue("key-1", "ciphertext");

    @BeforeEach
    void setUp() {
        credentialRepository = mock(AdminTotpCredentialRepository.class);
        recoveryCodeRepository = mock(AdminMfaRecoveryCodeRepository.class);
        challengeRepository = mock(AdminMfaChallengeRepository.class);
        userRepository = mock(UserRepository.class);
        encryptionService = mock(EncryptionService.class);
        googleReauthVerifier = mock(GoogleReauthVerifier.class);
        auditService = mock(AuditService.class);
        service = new AdminMfaService(credentialRepository, recoveryCodeRepository, challengeRepository,
                userRepository, encryptionService, googleReauthVerifier, auditService);
        // @Value never runs outside a Spring context -- every test in this file exercises the
        // underlying MFA logic itself, so the feature flag is on here. See the "feature flag"
        // section below for flag-off behavior, which sets this back to false per test.
        ReflectionTestUtils.setField(service, "featureEnabled", true);

        when(encryptionService.encrypt(any())).thenReturn(ENCRYPTED);
        when(encryptionService.decrypt(any())).thenReturn(SECRET);
        when(credentialRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recoveryCodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(challengeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setEmail("admin@example.com");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    // --- isEnabled ---

    @Test
    void isEnabled_falseWhenNoCredentialRowExists() {
        when(credentialRepository.findByUserId(userId)).thenReturn(Optional.empty());
        assertThat(service.isEnabled(userId)).isFalse();
    }

    @Test
    void isEnabled_falseForAPendingUnconfirmedEnrollment() {
        AdminTotpCredential credential = new AdminTotpCredential();
        when(credentialRepository.findByUserId(userId)).thenReturn(Optional.of(credential));
        assertThat(service.isEnabled(userId)).isFalse(); // enabled defaults to false
    }

    // --- beginEnrollment ---

    @Test
    void beginEnrollment_storesAnEncryptedSecret_andReturnsTheProvisioningUri() {
        when(credentialRepository.findByUserId(userId)).thenReturn(Optional.empty());

        EnrollResponse response = service.beginEnrollment(userId);

        assertThat(response.provisioningUri()).startsWith("otpauth://totp/Finora%20Admin:admin%40example.com");
        assertThat(response.provisioningUri()).contains("secret=" + response.secret());
        ArgumentCaptor<AdminTotpCredential> captor = ArgumentCaptor.forClass(AdminTotpCredential.class);
        verify(credentialRepository).save(captor.capture());
        assertThat(captor.getValue().isEnabled()).isFalse();
    }

    @Test
    void beginEnrollment_refusesToRestartWhileAlreadyEnabled() {
        AdminTotpCredential existing = new AdminTotpCredential();
        existing.markEnabled();
        when(credentialRepository.findByUserId(userId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.beginEnrollment(userId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already enabled");
        verify(credentialRepository, never()).delete(any());
    }

    @Test
    void beginEnrollment_replacesAnAbandonedUnconfirmedAttempt() {
        AdminTotpCredential existing = new AdminTotpCredential(); // enabled defaults false
        when(credentialRepository.findByUserId(userId)).thenReturn(Optional.of(existing));

        service.beginEnrollment(userId);

        verify(credentialRepository).delete(existing);
        verify(credentialRepository).save(any());
    }

    // --- confirm ---

    @Test
    void confirm_withACorrectCode_enablesAndReturnsTenRecoveryCodes() {
        AdminTotpCredential pending = new AdminTotpCredential();
        pending.storeSecret(ENCRYPTED);
        when(credentialRepository.findByUserId(userId)).thenReturn(Optional.of(pending));

        String code = TotpGenerator.currentCode(SECRET);
        ConfirmResponse response = service.confirm(userId, code);

        assertThat(pending.isEnabled()).isTrue();
        assertThat(response.recoveryCodes()).hasSize(10);
        assertThat(response.recoveryCodes()).allMatch(c -> c.matches("[0-9A-F]{5}-[0-9A-F]{5}"));
        assertThat(response.recoveryCodes()).doesNotHaveDuplicates();
        verify(recoveryCodeRepository, times(10)).save(any());
        verify(auditService).record(eq(userId), eq("ADMIN_MFA_ENABLED"), eq("User"), eq(userId), any());
    }

    @Test
    void confirm_withAWrongCode_throwsAndLeavesTheCredentialUnconfirmed() {
        AdminTotpCredential pending = new AdminTotpCredential();
        pending.storeSecret(ENCRYPTED);
        when(credentialRepository.findByUserId(userId)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.confirm(userId, "000000"))
                .isInstanceOf(ApiException.class);
        assertThat(pending.isEnabled()).isFalse();
        verify(recoveryCodeRepository, never()).save(any());
    }

    @Test
    void confirm_withNoPendingEnrollment_throws() {
        when(credentialRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(userId, "123456"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Start enrollment");
    }

    @Test
    void confirm_whenAlreadyEnabled_treatsItAsNoPendingEnrollment() {
        AdminTotpCredential already = new AdminTotpCredential();
        already.storeSecret(ENCRYPTED);
        already.markEnabled();
        when(credentialRepository.findByUserId(userId)).thenReturn(Optional.of(already));

        assertThatThrownBy(() -> service.confirm(userId, "123456"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Start enrollment");
    }

    // --- disable ---

    @Test
    void disable_withAVerifiedCredential_deletesEverything() {
        when(googleReauthVerifier.verify(any(), any(), any())).thenReturn(true);

        service.disable(userId, "correct-password", null);

        verify(credentialRepository).deleteByUserId(userId);
        verify(recoveryCodeRepository).deleteByUserId(userId);
        verify(challengeRepository).deleteByUserId(userId);
        verify(auditService).record(eq(userId), eq("ADMIN_MFA_DISABLED"), eq("User"), eq(userId), any());
    }

    @Test
    void disable_withAnUnverifiedCredential_throwsAndDeletesNothing() {
        when(googleReauthVerifier.verify(any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.disable(userId, "wrong-password", null))
                .isInstanceOf(ApiException.class);
        verify(credentialRepository, never()).deleteByUserId(any());
        verify(recoveryCodeRepository, never()).deleteByUserId(any());
    }

    // --- issueChallenge / verifyChallenge ---

    @Test
    void issueChallenge_storesOnlyTheHash_andReturnsTheRawToken() {
        String raw = service.issueChallenge(userId);

        ArgumentCaptor<AdminMfaChallenge> captor = ArgumentCaptor.forClass(AdminMfaChallenge.class);
        verify(challengeRepository).save(captor.capture());
        assertThat(captor.getValue().getTokenHash()).isEqualTo(TokenHasher.sha256(raw));
        assertThat(captor.getValue().getTokenHash()).isNotEqualTo(raw);
        assertThat(captor.getValue().getExpiresAt()).isAfter(Instant.now());
    }

    private AdminMfaChallenge liveChallenge(String rawToken) {
        AdminMfaChallenge challenge = new AdminMfaChallenge();
        challenge.setUserId(userId);
        challenge.setTokenHash(TokenHasher.sha256(rawToken));
        challenge.setExpiresAt(Instant.now().plusSeconds(300));
        return challenge;
    }

    @Test
    void verifyChallenge_withACorrectTotpCode_succeedsAndConsumesTheChallenge() {
        AdminMfaChallenge challenge = liveChallenge("raw-challenge-token");
        when(challengeRepository.findByTokenHash(TokenHasher.sha256("raw-challenge-token")))
                .thenReturn(Optional.of(challenge));
        AdminTotpCredential enabled = new AdminTotpCredential();
        enabled.storeSecret(ENCRYPTED);
        enabled.markEnabled();
        when(credentialRepository.findByUserId(userId)).thenReturn(Optional.of(enabled));

        String code = TotpGenerator.currentCode(SECRET);
        UUID resolved = service.verifyChallenge("raw-challenge-token", code);

        assertThat(resolved).isEqualTo(userId);
        assertThat(challenge.getUsedAt()).isNotNull();
    }

    @Test
    void verifyChallenge_withAValidUnusedRecoveryCode_succeeds() {
        AdminMfaChallenge challenge = liveChallenge("raw-challenge-token");
        when(challengeRepository.findByTokenHash(TokenHasher.sha256("raw-challenge-token")))
                .thenReturn(Optional.of(challenge));
        AdminTotpCredential enabled = new AdminTotpCredential();
        enabled.storeSecret(ENCRYPTED);
        enabled.markEnabled();
        when(credentialRepository.findByUserId(userId)).thenReturn(Optional.of(enabled));

        AdminMfaRecoveryCode recoveryCode = new AdminMfaRecoveryCode();
        recoveryCode.setUserId(userId);
        recoveryCode.setCodeHash(TokenHasher.sha256("ABCDE-12345"));
        when(recoveryCodeRepository.findByUserIdAndCodeHashAndUsedAtIsNull(userId, TokenHasher.sha256("ABCDE-12345")))
                .thenReturn(Optional.of(recoveryCode));

        // Wrong TOTP guess first (proves the fallback actually runs, not just the happy path).
        UUID resolved = service.verifyChallenge("raw-challenge-token", "abcde-12345"); // lowercase, as a user might type it

        assertThat(resolved).isEqualTo(userId);
        assertThat(recoveryCode.getUsedAt()).isNotNull();
        verify(auditService).record(eq(userId), eq("ADMIN_MFA_RECOVERY_CODE_USED"), eq("User"), eq(userId), any());
    }

    @Test
    void verifyChallenge_withAnExpiredChallenge_throws() {
        AdminMfaChallenge challenge = liveChallenge("raw-challenge-token");
        challenge.setExpiresAt(Instant.now().minusSeconds(1));
        when(challengeRepository.findByTokenHash(TokenHasher.sha256("raw-challenge-token")))
                .thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> service.verifyChallenge("raw-challenge-token", "123456"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void verifyChallenge_withAnAlreadyUsedChallenge_throws() {
        AdminMfaChallenge challenge = liveChallenge("raw-challenge-token");
        challenge.markUsed();
        when(challengeRepository.findByTokenHash(TokenHasher.sha256("raw-challenge-token")))
                .thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> service.verifyChallenge("raw-challenge-token", "123456"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void verifyChallenge_withAnUnknownToken_throws() {
        when(challengeRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyChallenge("never-issued", "123456"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void verifyChallenge_withAWrongCodeAndNoMatchingRecoveryCode_throwsWithoutConsumingTheChallenge() {
        AdminMfaChallenge challenge = liveChallenge("raw-challenge-token");
        when(challengeRepository.findByTokenHash(TokenHasher.sha256("raw-challenge-token")))
                .thenReturn(Optional.of(challenge));
        AdminTotpCredential enabled = new AdminTotpCredential();
        enabled.storeSecret(ENCRYPTED);
        enabled.markEnabled();
        when(credentialRepository.findByUserId(userId)).thenReturn(Optional.of(enabled));
        when(recoveryCodeRepository.findByUserIdAndCodeHashAndUsedAtIsNull(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyChallenge("raw-challenge-token", "000000"))
                .isInstanceOf(ApiException.class);
        assertThat(challenge.getUsedAt()).isNull();
    }

    // --- feature flag (app.admin-mfa.enabled) ---
    //
    // Sid's decision: keep this off until the admin portal has an MFA UI (enrollment,
    // verification, recovery) -- calling enroll/confirm directly today would require MFA on the
    // next login with no way to complete it through the web app. Every entry point below must
    // refuse outright, not just decline to change state, so a direct caller gets an unambiguous
    // "not available" rather than a status page that quietly looks usable.

    @Test
    void isFeatureEnabled_reflectsTheFlag() {
        assertThat(service.isFeatureEnabled()).isTrue(); // set true in setUp() for this file's other tests

        ReflectionTestUtils.setField(service, "featureEnabled", false);
        assertThat(service.isFeatureEnabled()).isFalse();
    }

    @Test
    void isEnabled_whenFeatureDisabled_throwsNotAvailable_insteadOfCheckingTheCredentialTable() {
        ReflectionTestUtils.setField(service, "featureEnabled", false);

        assertThatThrownBy(() -> service.isEnabled(userId))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.AUTH_MFA_NOT_AVAILABLE));
        verifyNoInteractions(credentialRepository);
    }

    @Test
    void beginEnrollment_whenFeatureDisabled_throwsNotAvailable_andStartsNothing() {
        ReflectionTestUtils.setField(service, "featureEnabled", false);

        assertThatThrownBy(() -> service.beginEnrollment(userId))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.AUTH_MFA_NOT_AVAILABLE));
        verify(credentialRepository, never()).save(any());
    }

    @Test
    void confirm_whenFeatureDisabled_throwsNotAvailable_andEnablesNothing() {
        ReflectionTestUtils.setField(service, "featureEnabled", false);

        assertThatThrownBy(() -> service.confirm(userId, "123456"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.AUTH_MFA_NOT_AVAILABLE));
        verifyNoInteractions(credentialRepository);
    }

    @Test
    void disable_whenFeatureDisabled_throwsNotAvailable_andDeletesNothing() {
        ReflectionTestUtils.setField(service, "featureEnabled", false);

        assertThatThrownBy(() -> service.disable(userId, "correct-password", null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.AUTH_MFA_NOT_AVAILABLE));
        verify(credentialRepository, never()).deleteByUserId(any());
        verify(recoveryCodeRepository, never()).deleteByUserId(any());
        verifyNoInteractions(googleReauthVerifier);
    }
}
