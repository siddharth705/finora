package com.finora.notification.api;

import com.finora.notification.domain.DeviceToken;
import com.finora.notification.repository.DeviceTokenRepository;
import com.finora.security.crypto.EncryptionException;
import com.finora.security.crypto.EncryptionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registers, resolves, and revokes device push tokens. Never logs a raw token. */
@Service
public class DeviceTokenService {

    private static final Logger log = LoggerFactory.getLogger(DeviceTokenService.class);

    private final DeviceTokenRepository repository;
    private final EncryptionService encryptionService;

    public DeviceTokenService(DeviceTokenRepository repository,
            EncryptionService encryptionService) {
        this.repository = repository;
        this.encryptionService = encryptionService;
    }

    /**
     * Registers a device's push token, or -- if this exact token is already on file for this user
     * -- reactivates and refreshes the existing row instead of inserting a duplicate. That reuse is
     * what keeps {@code UNIQUE (user_id, token_fingerprint)} in the migration safe even though a
     * revoked row and a freshly re-registered one are the same physical token: there is always at
     * most one row per (user, token), and revoke/re-register mutate it rather than replacing it.
     */
    @Transactional
    public DeviceToken register(UUID userId, String platform, String rawToken) {
        String fingerprint = fingerprint(rawToken);
        Instant now = Instant.now();
        Optional<DeviceToken> existing =
                repository.findByUserIdAndTokenFingerprint(userId, fingerprint);
        if (existing.isPresent()) {
            existing.get().touch(now);
            return repository.save(existing.get());
        }
        return repository.save(DeviceToken.register(userId, platform,
                encryptionService.encrypt(rawToken), fingerprint, now));
    }

    /**
     * Decrypted tokens for the push providers, each paired with the platform it was registered
     * under (see {@link ActiveDeviceToken}). One unreadable row must not silence a whole user, so a
     * decryption failure is logged (by id only -- never the ciphertext or key material) and that
     * row is skipped rather than failing the whole batch.
     */
    @Transactional(readOnly = true)
    public List<ActiveDeviceToken> activeTokensFor(UUID userId) {
        List<ActiveDeviceToken> tokens = new ArrayList<>();
        for (DeviceToken token : repository.findByUserIdAndRevokedAtIsNull(userId)) {
            try {
                String decrypted = encryptionService.decrypt(token.credential());
                tokens.add(new ActiveDeviceToken(decrypted, token.getPlatform()));
            } catch (EncryptionException e) {
                log.error("Cannot decrypt device token {} -- skipping it. Check "
                        + "FINORA_ENCRYPTION_KEY against the runbook.", token.getId());
            }
        }
        return tokens;
    }

    @Transactional
    public void revoke(UUID userId, String rawToken) {
        repository.findByUserIdAndTokenFingerprint(userId, fingerprint(rawToken))
                .ifPresent(token -> {
                    token.revoke(Instant.now());
                    repository.save(token);
                });
    }

    /**
     * SHA-256 of the raw token. Needed because AES-GCM's fresh per-call IV means the same token
     * never encrypts to the same ciphertext, so the encrypted column cannot be matched on.
     */
    private String fingerprint(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
