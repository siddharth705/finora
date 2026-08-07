package com.finora.security;

import com.finora.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import java.util.function.Function;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final JwtProperties jwtProperties;
    private final SecretKey signingKey;

    public JwtService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        // HS256 requires a key >= 256 bits; application.yml enforces a 32+ char secret.
        this.signingKey = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param accountScope which portal this account belongs to ({@link com.finora.entity.User#SCOPE_USER}
     *        or {@code SCOPE_ADMIN}). Carried as a claim so the token says which portal it was
     *        minted for instead of that being derivable only by reloading the row. Additive to the
     *        wire format and therefore safe for an installed mobile build: no client parses the
     *        access token, and an unknown claim is ignored by every JWT library regardless.
     */
    public String generateToken(UUID userId, String email, UUID sessionId, String accountScope) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtProperties.getExpirationMs());
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                // Standard claim name for a session identifier. It lets the server answer
                // "which session is this request from" without the client storing or sending
                // anything extra, and without a second identifier to keep in sync.
                .claim("sid", sessionId.toString())
                .claim("scope", accountScope)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(extractClaim(token, Claims::getSubject));
    }

    /** The session this token was minted for, or null for a token issued before sid existed. */
    public UUID extractSessionId(String token) {
        String sid = extractClaim(token, c -> c.get("sid", String.class));
        try {
            return sid == null ? null : UUID.fromString(sid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String extractEmail(String token) {
        return extractClaim(token, c -> c.get("email", String.class));
    }

    /**
     * The portal this token was minted for, or null for a token issued before the claim existed.
     *
     * <p>Null is safe here in a way a null {@code sid} is not, and the difference is worth stating
     * because the two claims are treated differently a few lines apart in {@link JwtAuthFilter}. A
     * missing {@code sid} makes the revocation check impossible to perform, so it has to fail
     * closed. A missing {@code scope} costs nothing: the account's row is the authority on its own
     * scope and is loaded on every request anyway, so the filter simply falls back to it. The claim
     * exists to be cross-checked against that row, not to replace it.
     */
    public String extractAccountScope(String token) {
        return extractClaim(token, c -> c.get("scope", String.class));
    }

    public boolean isTokenValid(String token) {
        try {
            return !extractClaim(token, Claims::getExpiration).before(new Date());
        } catch (Exception e) {
            // Bug fix: this silently returned false for every failure reason alike (expired,
            // malformed, tampered signature, or a real bug in extractClaim itself) with no log
            // line and no comment explaining why -- indistinguishable from an ordinary expired
            // token, with zero trace for a user reporting unexpected logouts or repeated
            // malformed/tampered-token presentations against protected endpoints. debug, not
            // warn/error -- an expired token is the overwhelmingly common, entirely routine case
            // here, not something worth alarming on.
            log.debug("Rejecting invalid token: {}", e.getMessage());
            return false;
        }
    }

    private <T> T extractClaim(String token, Function<Claims, T> resolver) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return resolver.apply(claims);
    }
}
