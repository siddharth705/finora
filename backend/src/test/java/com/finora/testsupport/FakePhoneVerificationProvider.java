package com.finora.testsupport;

import com.finora.exception.ApiException;
import com.finora.service.PhoneVerificationProvider;
import org.springframework.http.HttpStatus;

/**
 * Deterministic PhoneVerificationProvider double for integration tests -- @Import'ed (via
 * TestPhoneVerificationConfig) by IT test classes that need to exercise phone-verification-gated
 * flows without a real Firebase project. Encodes the "verified" phone number directly into the
 * fake token string rather than keeping a lookup table, since IT tests run against a real,
 * ephemeral Testcontainers Postgres with nothing to keep such a table in sync with.
 */
public class FakePhoneVerificationProvider implements PhoneVerificationProvider {

    private static final String PREFIX = "fake-firebase-token:";

    /** Any string that doesn't start with the fake-token prefix simulates what a real invalid or
     *  expired Firebase ID token produces -- verifyIdToken() throwing, mapped to the same 401 this
     *  provider's real Firebase implementation maps FirebaseAuthException to. Named for
     *  readability at call sites rather than because it needs special-case handling below. */
    public static final String INVALID_TOKEN = "invalid-firebase-token";

    /** Builds a fake token that verifyAndGetPhoneNumber() resolves back to phoneNumber. */
    public static String tokenFor(String phoneNumber) {
        return PREFIX + phoneNumber;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public String verifyAndGetPhoneNumber(String idToken) {
        if (idToken == null || !idToken.startsWith(PREFIX)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Could not verify phone number — the code may be invalid or expired.");
        }
        return idToken.substring(PREFIX.length());
    }
}
