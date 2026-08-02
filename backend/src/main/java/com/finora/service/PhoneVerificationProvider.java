package com.finora.service;

/**
 * Abstraction over "prove this phone number is really controlled by whoever's calling" -- callers
 * (AuthService, PasswordChangeService, ProductionConfigValidator) depend on this interface, never
 * on which real identity provider is behind it. Firebase Phone Authentication is the only
 * implementation today ({@link FirebasePhoneVerificationProvider}), but nothing about the callers
 * assumes that: if Firebase's pricing, policy, or capabilities ever change, a replacement
 * implementation is the only thing that needs to change, not the authentication flows that depend
 * on this.
 */
public interface PhoneVerificationProvider {

    boolean isConfigured();

    /**
     * @return the E.164 phone number (e.g. {@code "+919876543210"}) the provider attests this
     *         token's holder proved control of, just now. Callers MUST still compare this against
     *         the account's own stored phone number themselves -- a valid token only proves
     *         "someone verified this specific phone number," never "this is the right account's
     *         phone number." Throws for an invalid/expired token, or one that doesn't carry a
     *         verifiable phone number at all.
     */
    String verifyAndGetPhoneNumber(String idToken);
}
