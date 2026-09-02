package com.finora.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
}
