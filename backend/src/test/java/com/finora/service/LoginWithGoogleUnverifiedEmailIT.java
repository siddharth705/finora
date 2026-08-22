package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.AuthDtos.RegisterRequest;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.integrations.google.login.GoogleIdentity;
import com.finora.repository.EmailVerificationTokenRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * D-23 self-review fix, proven against a REAL Spring transaction rather than a mocked one.
 *
 * <p>{@code AuthService.loginWithGoogle}'s existing-but-unverified-email branch mints and saves an
 * {@link com.finora.entity.EmailVerificationToken}, registers an {@code AfterCommit} email send,
 * and then throws {@code ApiException}. {@code ApiException} is an unchecked
 * {@code RuntimeException}, so without {@code @Transactional(noRollbackFor = ApiException.class)}
 * on that method, Spring's default rule would silently roll back the very token row this whole
 * response promises the caller was created -- leaving the real account owner with no actual way
 * to verify and try again, despite the error message saying otherwise. A plain Mockito unit test
 * ({@code AuthServiceGoogleLoginTest}) mocks the repository, so {@code save()} being called looks
 * identical whether or not a real transaction would have kept it -- this is exactly the class of
 * bug that requires a real transactional boundary to catch, the same reason
 * {@code LoginExistenceOracleIT} exists for {@code login()}'s own {@code noRollbackFor}.
 */
class LoginWithGoogleUnverifiedEmailIT extends AbstractIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Test
    @DisplayName("the freshly minted verification token survives the ApiException it's thrown alongside, not rolled back with it")
    void unverifiedAutoLinkAttempt_stillPersistsTheFreshVerificationToken() {
        String email = "google-unverified-" + UUID.randomUUID() + "@example.test";
        authService.register(new RegisterRequest(email, "SecurePass123", "Jane Doe", "+919876500001" /* synthetic-ok */, null));
        User registered = userRepository.findByEmailIgnoreCaseAndAccountScope(email, User.SCOPE_USER).orElseThrow();
        assertThat(registered.isEmailVerified())
                .as("register() must leave a self-service account unverified until the link is clicked")
                .isFalse();

        long tokensBefore = emailVerificationTokenRepository.count();

        ApiException thrown = catchThrowableOfType(
                () -> authService.loginWithGoogle(new GoogleIdentity(email, "Jane Doe")),
                ApiException.class);

        assertThat(thrown).as("Google sign-in must refuse to auto-link into an unverified account").isNotNull();
        assertThat(thrown.getMessage()).contains("verified");

        // The real assertion: a NEW token row exists after the throw, not just before it -- proving
        // the mint+save inside the branch that throws actually committed.
        assertThat(emailVerificationTokenRepository.count())
                .as("a fresh verification token must be durably persisted, not rolled back with the exception")
                .isEqualTo(tokensBefore + 1);
    }
}
