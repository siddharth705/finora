package com.finora.security;

import com.finora.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Bug 50 (docs/quality/bug-reports/BUG_REVIEW_REPORT.md). id() used to cast the principal to
 * UserDetails unconditionally, with no null check on getAuthentication() and no instanceof check
 * on the principal -- an anonymous request's principal is the literal String "anonymousUser" (or
 * Authentication itself is null), and either used to throw a bare ClassCastException/
 * NullPointerException that resolved to a 500 rather than a clean 401.
 */
class CurrentUserTest {

    private final CurrentUser currentUser = new CurrentUser(mock(UserRepository.class));

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void id_returnsTheUuid_forARealAuthenticatedPrincipal() {
        UUID userId = UUID.randomUUID();
        var principal = org.springframework.security.core.userdetails.User
                .withUsername(userId.toString()).password("irrelevant").authorities("ROLE_USER").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        assertThat(currentUser.id()).isEqualTo(userId);
    }

    @Test
    void id_throwsBadCredentials_whenThereIsNoAuthenticationAtAll() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(currentUser::id).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void id_throwsBadCredentials_forAnAnonymousPrincipal() {
        // AnonymousAuthenticationToken's principal is the literal String "anonymousUser" -- not a
        // UserDetails -- exactly the shape SecurityConfig's own permitAll matchers populate the
        // context with for an unauthenticated caller.
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThatThrownBy(currentUser::id).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void id_stillThrowsIllegalState_whenThePrincipalIsAUserDetailsWithANonUuidUsername() {
        // Distinct from the anonymous/missing-authentication case above -- a real UserDetails
        // principal whose username still isn't a parseable UUID is the "something is genuinely
        // wrong with this token" case this method already handled, and must keep doing so.
        var principal = org.springframework.security.core.userdetails.User
                .withUsername("not-a-uuid").password("irrelevant").authorities("ROLE_USER").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        assertThatThrownBy(currentUser::id).isInstanceOf(IllegalStateException.class);
    }
}
