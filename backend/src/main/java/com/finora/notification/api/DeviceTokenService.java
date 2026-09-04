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
     *
     * <p>Before touching this user's own row, every OTHER user's row for the identical token is
     * revoked (see {@link #revokeOtherUsersHoldingThisToken}). FCM/APNs tokens are per app-install,
     * not per user: without this, an account switch on a shared or handed-down device (or a
     * reinstall under a different account) leaves the PREVIOUS user's row active forever, and
     * {@link #activeTokensFor} keeps delivering that person's transaction alerts and password-change
     * notices to the new owner's phone. Never rely on the client calling {@link #revoke} first -- an
     * uninstall, a token expiry, or a forced logout never triggers it.
     */
    @Transactional
    public DeviceToken register(UUID userId, String platform, String rawToken) {
        String fingerprint = fingerprint(rawToken);
        Instant now = Instant.now();

        revokeOtherUsersHoldingThisToken(userId, fingerprint, now);

        Optional<DeviceToken> existing =
                repository.findByUserIdAndTokenFingerprint(userId, fingerprint);
        if (existing.isPresent()) {
            DeviceToken token = existing.get();
            token.touch(now);
            token.updatePlatform(platform);
            return repository.save(token);
        }
        return repository.save(DeviceToken.register(userId, platform,
                encryptionService.encrypt(rawToken), fingerprint, now));
    }

    /**
     * Revokes every row -- belonging to a user OTHER than {@code userId} -- that already holds this
     * exact token. Deliberately queries across users; do not let this get confused with
     * {@link #revoke(UUID, String)} below, whose entire correctness rests on being scoped to the
     * caller's own {@code userId}. This method exists specifically to reach past that boundary for
     * one narrow purpose (de-duplicating a shared physical device token) and must never be reused
     * anywhere an authorization check is expected.
     */
    private void revokeOtherUsersHoldingThisToken(UUID userId, String fingerprint, Instant now) {
        for (DeviceToken other : repository
                .findByTokenFingerprintAndUserIdNotAndRevokedAtIsNull(fingerprint, userId)) {
            other.revoke(now);
            repository.save(other);
        }
    }

    /**
     * Decrypted tokens for the push providers, each paired with the platform it was registered
     * under (see {@link ActiveDeviceToken}). One unreadable row must not silence a whole user, so a
     * decryption failure is logged (by id only -- never the ciphertext or key material) and that
     * row is skipped rather than failing the whole batch.
     *
     * <p>Catches both {@link EncryptionException} (a malformed or tampered ciphertext) and
     * {@link IllegalStateException} -- the latter is what {@code KeyProvider.keyById} throws when
     * {@code encryption_key_id} no longer resolves to a configured key (a rotation that dropped the
     * old key too early, or {@code FINORA_ENCRYPTION_KEY} out of sync with what wrote this row).
     * That call happens before {@code EncryptionService}'s own try/catch begins, so it is NOT
     * wrapped as an {@code EncryptionException} -- it is exactly the scenario this method's log
     * message already names, and without this second catch it would propagate out of the loop and
     * silence every OTHER device for this user too, the opposite of the guarantee this method
     * promises.
     */
    @Transactional(readOnly = true)
    public List<ActiveDeviceToken> activeTokensFor(UUID userId) {
        List<ActiveDeviceToken> tokens = new ArrayList<>();
        for (DeviceToken token : repository.findByUserIdAndRevokedAtIsNull(userId)) {
            try {
                String decrypted = encryptionService.decrypt(token.credential());
                tokens.add(new ActiveDeviceToken(decrypted, token.getPlatform()));
            } catch (EncryptionException | IllegalStateException e) {
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
