package com.finora.service;

import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.integrations.apple.login.AppleIdTokenVerifierService;
import com.finora.integrations.apple.login.AppleIdentity;
import com.finora.integrations.google.login.GoogleIdTokenVerifierService;
import com.finora.integrations.google.login.GoogleIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoogleReauthVerifierTest {

    private PasswordEncoder passwordEncoder;
    private GoogleIdTokenVerifierService googleIdTokenVerifierService;
    private AppleIdTokenVerifierService appleIdTokenVerifierService;
    private GoogleReauthVerifier verifier;

    @BeforeEach
    void setUp() {
        passwordEncoder = mock(PasswordEncoder.class);
        googleIdTokenVerifierService = mock(GoogleIdTokenVerifierService.class);
        appleIdTokenVerifierService = mock(AppleIdTokenVerifierService.class);
        verifier = new GoogleReauthVerifier(passwordEncoder, googleIdTokenVerifierService, appleIdTokenVerifierService);
    }

    private User passwordUser() {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setEmail("jane@example.com");
        u.setPasswordHash("hashed");
        u.setSignInMethod(User.SIGN_IN_METHOD_PASSWORD);
        return u;
    }

    private User googleUser() {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setEmail("jane@example.com");
        u.setPasswordHash("some-random-unguessable-value");
        u.setSignInMethod(User.SIGN_IN_METHOD_GOOGLE);
        return u;
    }

    private User appleUser() {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setEmail("jane@example.com");
        u.setPasswordHash("some-random-unguessable-value");
        u.setSignInMethod(User.SIGN_IN_METHOD_APPLE);
        return u;
    }

    @Test
    void verify_onAPasswordAccount_delegatesToThePasswordEncoder() {
        User user = passwordUser();
        when(passwordEncoder.matches("correct", "hashed")).thenReturn(true);

        assertThat(verifier.verify(user, "correct", null, null)).isTrue();
        verify(googleIdTokenVerifierService, never()).verify(any());
        verify(appleIdTokenVerifierService, never()).verify(any());
    }

    @Test
    void verify_onAPasswordAccount_rejectsAWrongPassword() {
        User user = passwordUser();
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThat(verifier.verify(user, "wrong", null, null)).isFalse();
    }

    @Test
    void verify_onAPasswordAccount_rejectsANullPasswordWithoutCallingTheEncoder() {
        User user = passwordUser();

        assertThat(verifier.verify(user, null, null, null)).isFalse();
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void verify_onAPasswordAccount_rejectsABlankPasswordWithoutCallingTheEncoder() {
        User user = passwordUser();

        assertThat(verifier.verify(user, "   ", null, null)).isFalse();
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void verify_onAGoogleAccount_acceptsATokenThatVerifiesToTheSameEmail() {
        User user = googleUser();
        when(googleIdTokenVerifierService.verify("fresh-token"))
                .thenReturn(new GoogleIdentity("jane@example.com", "Jane"));

        assertThat(verifier.verify(user, null, "fresh-token", null)).isTrue();
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void verify_onAGoogleAccount_matchesEmailCaseInsensitivelyAndTrimmed() {
        User user = googleUser();
        when(googleIdTokenVerifierService.verify("fresh-token"))
                .thenReturn(new GoogleIdentity(" Jane@Example.com ", "Jane"));

        assertThat(verifier.verify(user, null, "fresh-token", null)).isTrue();
    }

    @Test
    void verify_onAGoogleAccount_rejectsATokenThatVerifiesToADifferentEmail() {
        User user = googleUser();
        when(googleIdTokenVerifierService.verify("someone-elses-token"))
                .thenReturn(new GoogleIdentity("someone-else@example.com", "Someone Else"));

        assertThat(verifier.verify(user, null, "someone-elses-token", null)).isFalse();
    }

    @Test
    void verify_onAGoogleAccount_rejectsANullTokenWithoutCallingTheVerifierService() {
        User user = googleUser();

        assertThat(verifier.verify(user, null, null, null)).isFalse();
        verify(googleIdTokenVerifierService, never()).verify(any());
    }

    @Test
    void verify_onAGoogleAccount_ignoresACurrentPasswordEvenIfOneWereSomehowSupplied() {
        User user = googleUser();
        when(googleIdTokenVerifierService.verify("fresh-token"))
                .thenReturn(new GoogleIdentity("jane@example.com", "Jane"));

        assertThat(verifier.verify(user, "some-password", "fresh-token", null)).isTrue();
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void verify_onAGoogleAccount_returnsFalseWhenTheTokenFailsVerification() {
        User user = googleUser();
        when(googleIdTokenVerifierService.verify("bad-token"))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "Invalid Google sign-in token."));

        assertThat(verifier.verify(user, null, "bad-token", null)).isFalse();
    }

    @Test
    void verify_onAGoogleAccount_propagatesAServiceUnavailableRatherThanSwallowingIt() {
        User user = googleUser();
        when(googleIdTokenVerifierService.verify("any-token"))
                .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Sign in with Google is not configured on this server."));

        assertThatThrownBy(() -> verifier.verify(user, null, "any-token", null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.SERVICE_UNAVAILABLE);
    }

    // D-26 gap closed. Mirrors every Google test above -- same behavior, same edge cases, now
    // proven for the sign-in method Google's own re-auth branch could never cover.

    @Test
    void verify_onAnAppleAccount_acceptsATokenThatVerifiesToTheSameEmail() {
        User user = appleUser();
        when(appleIdTokenVerifierService.verify("fresh-token"))
                .thenReturn(new AppleIdentity("jane@example.com", "apple-subject"));

        assertThat(verifier.verify(user, null, null, "fresh-token")).isTrue();
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void verify_onAnAppleAccount_matchesEmailCaseInsensitivelyAndTrimmed() {
        User user = appleUser();
        when(appleIdTokenVerifierService.verify("fresh-token"))
                .thenReturn(new AppleIdentity(" Jane@Example.com ", "apple-subject"));

        assertThat(verifier.verify(user, null, null, "fresh-token")).isTrue();
    }

    @Test
    void verify_onAnAppleAccount_rejectsATokenThatVerifiesToADifferentEmail() {
        User user = appleUser();
        when(appleIdTokenVerifierService.verify("someone-elses-token"))
                .thenReturn(new AppleIdentity("someone-else@example.com", "someone-elses-subject"));

        assertThat(verifier.verify(user, null, null, "someone-elses-token")).isFalse();
    }

    @Test
    void verify_onAnAppleAccount_rejectsANullTokenWithoutCallingTheVerifierService() {
        User user = appleUser();

        assertThat(verifier.verify(user, null, null, null)).isFalse();
        verify(appleIdTokenVerifierService, never()).verify(any());
    }

    @Test
    void verify_onAnAppleAccount_ignoresACurrentPasswordEvenIfOneWereSomehowSupplied() {
        User user = appleUser();
        when(appleIdTokenVerifierService.verify("fresh-token"))
                .thenReturn(new AppleIdentity("jane@example.com", "apple-subject"));

        assertThat(verifier.verify(user, "some-password", null, "fresh-token")).isTrue();
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void verify_onAnAppleAccount_returnsFalseWhenTheTokenFailsVerification() {
        User user = appleUser();
        when(appleIdTokenVerifierService.verify("bad-token"))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "Invalid Apple sign-in token."));

        assertThat(verifier.verify(user, null, null, "bad-token")).isFalse();
    }

    @Test
    void verify_onAnAppleAccount_propagatesAServiceUnavailableRatherThanSwallowingIt() {
        User user = appleUser();
        when(appleIdTokenVerifierService.verify("any-token"))
                .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Sign in with Apple is not configured on this server."));

        assertThatThrownBy(() -> verifier.verify(user, null, null, "any-token"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
