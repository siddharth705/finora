package com.finora.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * SHA-256 hashing for opaque tokens (password reset, refresh tokens) that are stored server-side.
 * The raw token only ever exists in the API response and the client's storage — hashing what's
 * persisted means a database leak alone doesn't hand out working tokens.
 */
public final class TokenHasher {

    private TokenHasher() {}

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
