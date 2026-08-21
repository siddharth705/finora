package com.finora.integrations.google.login;

/**
 * What Finora actually trusts out of a Google ID token, after
 * {@link GoogleIdTokenVerifierService} has already checked the token's signature, issuer, audience
 * and {@code email_verified} claim — by the time a caller holds one of these, "is this really
 * Google's own claim about this address" is already answered.
 *
 * @param email the account email, already confirmed {@code email_verified = true} by the verifier
 *              — {@link com.finora.service.AuthService#loginWithGoogle} never has to check this
 *              itself
 * @param name  Google's {@code name} claim, best-effort — used as the new account's initial
 *              display name if one is present, never required (a Google account can omit it)
 */
public record GoogleIdentity(String email, String name) {}
