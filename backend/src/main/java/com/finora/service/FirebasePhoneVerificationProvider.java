package com.finora.service;

import com.finora.exception.ApiException;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Verifies a Firebase ID token server-side via the Firebase Admin SDK -- the one thing the
 * backend needs to trust the frontend's own client-side Firebase Phone Authentication instead of
 * taking its word for it (see FirebaseConfig's own doc comment for the full architecture this is
 * part of). "The backend should never trust the frontend directly" -- every OTP-gated flow
 * (registration, password reset, authenticated password change) calls this before treating a
 * phone number as verified.
 *
 * The only {@link PhoneVerificationProvider} implementation today -- callers depend on that
 * interface, not this class directly, so a future identity provider swap only ever means adding a
 * new implementation, not touching AuthService/PasswordChangeService/ProductionConfigValidator.
 */
@Service
public class FirebasePhoneVerificationProvider implements PhoneVerificationProvider {

    private final Optional<FirebaseApp> firebaseApp;

    public FirebasePhoneVerificationProvider(Optional<FirebaseApp> firebaseApp) {
        this.firebaseApp = firebaseApp;
    }

    @Override
    public boolean isConfigured() {
        return firebaseApp.isPresent();
    }

    /**
     * @return the E.164 phone number (e.g. {@code "+919876543210"}) Firebase attests this token's
     *         holder proved control of, just now. Callers MUST still compare this against the
     *         account's own stored phone number themselves -- a valid token only proves "someone
     *         verified this specific phone number with Firebase," never "this is the right
     *         account's phone number." Throws ApiException (401) for an invalid/expired token, or
     *         a token that doesn't carry a phone number claim at all (e.g. an email/password
     *         Firebase token from a different auth method); (503) if Firebase isn't configured on
     *         this server at all.
     */
    @Override
    public String verifyAndGetPhoneNumber(String idToken) {
        if (firebaseApp.isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Phone verification is not configured on this server.");
        }
        FirebaseToken decoded;
        try {
            decoded = FirebaseAuth.getInstance(firebaseApp.get()).verifyIdToken(idToken);
        } catch (FirebaseAuthException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Could not verify phone number — the code may be invalid or expired.");
        }
        Object phoneNumber = decoded.getClaims().get("phone_number");
        if (!(phoneNumber instanceof String) || ((String) phoneNumber).isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "This verification token doesn't prove a phone number.");
        }
        return (String) phoneNumber;
    }
}
