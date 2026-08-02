package com.finora.service;

import com.finora.config.JwtProperties;
import com.finora.entity.RefreshToken;
import com.finora.exception.ApiException;
import com.finora.repository.RefreshTokenRepository;
import com.finora.util.TokenHasher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Refresh tokens are opaque random strings (not JWTs) stored hashed server-side — unlike the
 * short-lived JWT access token, a refresh token needs to be individually revocable, which a
 * self-contained JWT fundamentally can't be without a server-side blocklist anyway. So we just
 * store it server-side from the start, same pattern as password reset tokens.
 *
 * Rotation: every use of a refresh token immediately revokes it and issues a new one. If a
 * revoked token is presented again, that's a strong signal it was stolen and used by both the
 * legitimate holder and an attacker — the response to that is to revoke every active token for
 * the user, forcing a fresh login on every device rather than trying to guess which session is
 * the attacker's.
 */
@Service
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, JwtProperties jwtProperties) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtProperties = jwtProperties;
    }

    public record IssuedToken(String rawToken, Instant expiresAt) {}
    public record RotationResult(UUID userId, IssuedToken newToken) {}

    public IssuedToken issue(UUID userId) {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        RefreshToken rt = new RefreshToken();
        rt.setUserId(userId);
        rt.setTokenHash(TokenHasher.sha256(rawToken));
        Instant expiresAt = Instant.now().plusMillis(jwtProperties.getRefreshExpirationMs());
        rt.setExpiresAt(expiresAt);
        refreshTokenRepository.save(rt);

        return new IssuedToken(rawToken, expiresAt);
    }

    @Transactional
    public RotationResult rotate(String rawToken) {
        RefreshToken rt = refreshTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));

        if (rt.getRevokedAt() != null) {
            revokeAllForUser(rt.getUserId());
            throw new ApiException(HttpStatus.UNAUTHORIZED,
                    "This refresh token has already been used. All sessions have been signed out as a precaution.");
        }
        if (rt.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Refresh token expired — please sign in again.");
        }

        rt.setRevokedAt(Instant.now());
        refreshTokenRepository.save(rt);

        IssuedToken newToken = issue(rt.getUserId());
        return new RotationResult(rt.getUserId(), newToken);
    }

    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken)).ifPresent(rt -> {
            rt.setRevokedAt(Instant.now());
            refreshTokenRepository.save(rt);
        });
    }

    /** Revokes every active session for the user, including whichever one is calling this --
     *  the same defense-in-depth response rotate() already applies when it detects a
     *  stolen/replayed refresh token (see this class's own doc comment). */
    public void revokeAllForUser(UUID userId) {
        List<RefreshToken> active = refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId);
        Instant now = Instant.now();
        active.forEach(t -> t.setRevokedAt(now));
        refreshTokenRepository.saveAll(active);
    }

    /** Used by PasswordChangeService's "sign out other devices" choice -- unlike
     *  revokeAllForUser(), the device that just completed the change stays signed in rather than
     *  also being forced to re-authenticate, since currentRawToken identifies it and is excluded.
     *  If currentRawToken doesn't match any active token (e.g. it was already rotated by the time
     *  this runs), nothing is excluded and every session -- including, in that edge case, this
     *  one -- ends up revoked; that fails toward the safer outcome, not a silent no-op. */
    public void revokeAllOtherSessionsForUser(UUID userId, String currentRawToken) {
        String currentHash = TokenHasher.sha256(currentRawToken);
        List<RefreshToken> others = refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId).stream()
                .filter(t -> !t.getTokenHash().equals(currentHash))
                .toList();
        Instant now = Instant.now();
        others.forEach(t -> t.setRevokedAt(now));
        refreshTokenRepository.saveAll(others);
    }
}
