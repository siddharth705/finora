package com.finora.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.notification.domain.DeviceToken;
import com.finora.notification.repository.DeviceTokenRepository;
import com.finora.security.crypto.EncryptedValue;
import com.finora.security.crypto.EncryptionException;
import com.finora.security.crypto.EncryptionService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeviceTokenServiceTest {

    private DeviceTokenRepository repository;
    private EncryptionService encryptionService;
    private DeviceTokenService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(DeviceTokenRepository.class);
        encryptionService = mock(EncryptionService.class);
        service = new DeviceTokenService(repository, encryptionService);

        when(repository.save(any(DeviceToken.class))).thenAnswer(inv -> inv.getArgument(0));
        // Default: nobody else already holds this token. Tests for the cross-user revoke
        // (register_revokesAnotherUsersRowHoldingTheIdenticalToken below) override this.
        when(repository.findByTokenFingerprintAndUserIdNotAndRevokedAtIsNull(anyString(), any()))
                .thenReturn(List.of());
        when(encryptionService.encrypt(anyString()))
                .thenAnswer(inv -> new EncryptedValue("v1", "cipher:" + inv.getArgument(0)));
        // Null-safe: re-stubbing decrypt(any()) inside a test (see the "cannot be decrypted" test
        // below) makes Mockito actually invoke this existing answer once, with a null argument, as
        // part of its own bookkeeping before the new stub takes over -- not an argument any real
        // caller can ever produce, but this lambda has to survive it without throwing.
        when(encryptionService.decrypt(any()))
                .thenAnswer(inv -> {
                    EncryptedValue value = inv.getArgument(0);
                    return value == null ? null : value.ciphertext().replace("cipher:", "");
                });
    }

    @Test
    void register_storesTheTokenEncryptedWithItsKeyId() {
        when(repository.findByUserIdAndTokenFingerprint(any(), anyString()))
                .thenReturn(Optional.empty());

        DeviceToken saved = service.register(userId, "ANDROID", "fcm-token-abc");

        // The raw token must never be what lands in the column.
        assertThat(saved.getEncryptedToken()).isEqualTo("cipher:fcm-token-abc");
        assertThat(saved.getEncryptionKeyId()).isEqualTo("v1");
    }

    @Test
    void register_isIdempotentForARepeatedTokenOnTheSameDevice() {
        DeviceToken existing = DeviceToken.register(userId, "ANDROID",
                new EncryptedValue("v1", "cipher:fcm-token-abc"), "fingerprint", Instant.now());
        when(repository.findByUserIdAndTokenFingerprint(any(), anyString()))
                .thenReturn(Optional.of(existing));

        DeviceToken saved = service.register(userId, "ANDROID", "fcm-token-abc");

        assertThat(saved).isSameAs(existing);
        assertThat(saved.getRevokedAt()).isNull();
    }

    // CORRECTION applied to the brief: activeTokensFor returns List<ActiveDeviceToken> (token +
    // platform), not List<String>. Task 11 must route each token to FCM (ANDROID) or APNs (IOS) by
    // its stored platform; a bare token string carries no platform, which would have forced either
    // a breaking signature change after Task 10 already depends on it, or the push provider
    // re-querying the repository itself. See task-9-report.md for the full rationale.
    @Test
    void activeTokensFor_returnsDecryptedTokensWithPlatformForSending() {
        DeviceToken token = DeviceToken.register(userId, "ANDROID",
                new EncryptedValue("v1", "cipher:fcm-token-abc"), "fingerprint", Instant.now());
        when(repository.findByUserIdAndRevokedAtIsNull(userId)).thenReturn(List.of(token));

        assertThat(service.activeTokensFor(userId))
                .containsExactly(new ActiveDeviceToken("fcm-token-abc", "ANDROID"));
    }

    @Test
    void activeTokensFor_skipsATokenThatCannotBeDecrypted() {
        DeviceToken token = DeviceToken.register(userId, "ANDROID",
                new EncryptedValue("v0", "unreadable"), "fingerprint", Instant.now());
        when(repository.findByUserIdAndRevokedAtIsNull(userId)).thenReturn(List.of(token));
        when(encryptionService.decrypt(any()))
                .thenThrow(new EncryptionException("wrong key", new RuntimeException("cause")));

        // One undecryptable row must not stop every other device from getting the push.
        assertThat(service.activeTokensFor(userId)).isEmpty();
    }

    // Fix round 1, CRITICAL 1: FCM/APNs tokens are per app-install, not per user. Without this,
    // an account switch on a shared/handed-down device leaves the PREVIOUS owner's row active
    // forever and activeTokensFor(previousOwner) keeps returning it -- their transaction alerts
    // and password-change notices would keep landing on the new owner's phone.
    @Test
    void register_revokesAnotherUsersRowHoldingTheIdenticalToken() {
        UUID otherUserId = UUID.randomUUID();
        DeviceToken othersToken = DeviceToken.register(otherUserId, "ANDROID",
                new EncryptedValue("v1", "cipher:shared-device-token"), "shared-fingerprint",
                Instant.now());
        when(repository.findByTokenFingerprintAndUserIdNotAndRevokedAtIsNull(anyString(), eq(userId)))
                .thenReturn(List.of(othersToken));
        when(repository.findByUserIdAndTokenFingerprint(eq(userId), anyString()))
                .thenReturn(Optional.empty());

        service.register(userId, "ANDROID", "shared-device-token");

        assertThat(othersToken.getRevokedAt()).isNotNull();
        verify(repository).save(othersToken);
    }

    // Fix round 1, IMPORTANT 5: Task 11 routes FCM vs APNs by this field. A stale platform on
    // re-registration means a push silently sent to the wrong provider and dropped.
    @Test
    void register_updatesThePlatformOnAnExistingRow() {
        DeviceToken existing = DeviceToken.register(userId, "IOS",
                new EncryptedValue("v1", "cipher:fcm-token-abc"), "fingerprint", Instant.now());
        when(repository.findByUserIdAndTokenFingerprint(any(), anyString()))
                .thenReturn(Optional.of(existing));

        DeviceToken saved = service.register(userId, "ANDROID", "fcm-token-abc");

        assertThat(saved.getPlatform()).isEqualTo("ANDROID");
    }

    // Fix round 1, IMPORTANT 2: KeyProvider.keyById throws IllegalStateException (not
    // EncryptionException) when a key id no longer resolves -- that call sits outside
    // EncryptionService's own try/catch. Before this fix, one such row aborted the whole method
    // and silenced every other device for the user, the opposite of the documented guarantee.
    @Test
    void activeTokensFor_skipsATokenWhoseKeyNoLongerResolves() {
        DeviceToken retiredKeyToken = DeviceToken.register(userId, "ANDROID",
                new EncryptedValue("retired-key", "unreadable"), "fingerprint-1", Instant.now());
        DeviceToken healthyToken = DeviceToken.register(userId, "IOS",
                new EncryptedValue("v1", "cipher:other-token"), "fingerprint-2", Instant.now());
        when(repository.findByUserIdAndRevokedAtIsNull(userId))
                .thenReturn(List.of(retiredKeyToken, healthyToken));
        when(encryptionService.decrypt(eq(retiredKeyToken.credential())))
                .thenThrow(new IllegalStateException("Unknown key id: retired-key"));

        assertThat(service.activeTokensFor(userId))
                .containsExactly(new ActiveDeviceToken("other-token", "IOS"));
    }

    // Fix round 1, IMPORTANT 3(a): revoke's entire authorization guarantee is that it is scoped
    // to the caller's own userId. Verifying the finder is called WITH that userId (not the
    // unscoped cross-user finder Critical-1 just introduced into this same class) guards against
    // that scoping silently regressing.
    @Test
    void revoke_cannotRevokeAnotherUsersTokenEvenWithTheIdenticalTokenString() {
        when(repository.findByUserIdAndTokenFingerprint(eq(userId), anyString()))
                .thenReturn(Optional.empty());

        service.revoke(userId, "someone-elses-token");

        verify(repository).findByUserIdAndTokenFingerprint(eq(userId), anyString());
        verify(repository, never()).save(any(DeviceToken.class));
    }

    // Fix round 1, IMPORTANT 3(b).
    @Test
    void revoke_ofAnUnknownTokenIsANoOpThatDoesNotThrow() {
        when(repository.findByUserIdAndTokenFingerprint(any(), anyString()))
                .thenReturn(Optional.empty());

        assertThatCode(() -> service.revoke(userId, "never-registered-token"))
                .doesNotThrowAnyException();
        verify(repository, never()).save(any(DeviceToken.class));
    }
}
