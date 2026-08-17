package com.finora.integrations.apple.login;

/**
 * What Finora actually trusts out of an Apple identity token, after
 * {@link AppleIdTokenVerifierService} has already checked the token's signature, issuer, audience
 * and {@code email_verified} claim.
 *
 * <p>No {@code name} field, unlike {@link com.finora.integrations.google.login.GoogleIdentity}:
 * Apple's identity token never carries one. Apple hands the display name to the client (native
 * {@code AuthenticationServices} UI) only on the user's very first authorization for this app, as
 * a separate, unsigned value the client must capture and forward itself — see
 * {@code AuthController#apple} and {@code AuthDtos.AppleAuthRequest.fullName}.
 *
 * @param email the account email, already confirmed {@code email_verified} by the verifier — may
 *              be one of Apple's private-relay addresses ({@code @privaterelay.appleid.com}) if
 *              the user chose "Hide My Email"; Apple forwards mail sent to it, so it is used as a
 *              normal, deliverable account email like any other
 * @param sub   Apple's stable, per-user-per-app identifier — not used for account matching today
 *              (email is, same as Google), kept here since it is the one piece of this token that
 *              never changes even if the user's email address does
 */
public record AppleIdentity(String email, String sub) {}
