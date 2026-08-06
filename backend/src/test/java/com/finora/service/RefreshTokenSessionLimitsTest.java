package com.finora.service;

import com.finora.config.ClientIpResolver;
import com.finora.config.JwtProperties;
import com.finora.entity.RefreshToken;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.repository.RefreshTokenRepository;
import com.finora.util.TokenHasher;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two limits that stop a rotating refresh token from being a session that never ends.
 *
 * <p>Rotation alone does not bound anything: each refresh issues a token with a fresh 30-day
 * expiry, so a session stays alive indefinitely as long as it is used once a month. That was found
 * the ordinary way -- a browser tab left for nine hours went straight back into someone's
 * statements without a password. Not a defect in the rotation logic, which is correct and worth
 * keeping; a policy that suited a shopping site and not a product holding bank statements.
 */
class RefreshTokenSessionLimitsTest {

    private static final Duration IDLE = Duration.ofMinutes(30);
    private static final Duration ABSOLUTE = Duration.ofDays(7);
    private static final String RAW_TOKEN = "raw-refresh-token";

    private RefreshTokenRepository repository;
    private RefreshTokenService service;
    private UUID userId;

    @BeforeEach
    void setUp() {
        repository = mock(RefreshTokenRepository.class);
        JwtProperties props = new JwtProperties();
        props.setIdleTimeoutMs(IDLE.toMillis());
        props.setAbsoluteSessionMs(ABSOLUTE.toMillis());
        ReflectionTestUtils.setField(props, "refreshExpirationMs", Duration.ofDays(30).toMillis());
        service = new RefreshTokenService(repository, props,
                mock(HttpServletRequest.class), mock(ClientIpResolver.class));
        userId = UUID.randomUUID();
        when(repository.save(any(RefreshToken.class))).thenAnswer(i -> i.getArgument(0));
    }

    /** A live token created {@code tokenAge} ago, belonging to a session started {@code sessionAge} ago. */
    private void existingToken(Duration tokenAge, Duration sessionAge) {
        RefreshToken rt = new RefreshToken();
        rt.setUserId(userId);
        rt.setTokenHash(TokenHasher.sha256(RAW_TOKEN));
        rt.setExpiresAt(Instant.now().plus(Duration.ofDays(30)));
        ReflectionTestUtils.setField(rt, "createdAt", Instant.now().minus(tokenAge));
        rt.setSessionStartedAt(Instant.now().minus(sessionAge));
        when(repository.findByTokenHash(TokenHasher.sha256(RAW_TOKEN))).thenReturn(Optional.of(rt));
    }

    @Test
    void anActiveSessionInsideBothWindowsRotatesNormally() {
        existingToken(Duration.ofMinutes(14), Duration.ofDays(2));

        assertThatCode(() -> service.rotate(RAW_TOKEN)).doesNotThrowAnyException();
    }

    @Test
    void aSessionIdleLongerThanTheIdleWindowIsRefused() {
        existingToken(Duration.ofMinutes(31), Duration.ofHours(1));

        assertThatThrownBy(() -> service.rotate(RAW_TOKEN))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.AUTH_SESSION_IDLE);
    }

    @Test
    void anIdleSessionsTokenIsRevokedSoItCannotBeRetried() {
        existingToken(Duration.ofMinutes(31), Duration.ofHours(1));

        assertThatThrownBy(() -> service.rotate(RAW_TOKEN)).isInstanceOf(ApiException.class);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getRevokedAt())
                .as("refusing the rotation without revoking would leave a token that is rejected "
                    + "now and accepted again by any change to the idle window")
                .isNotNull();
    }

    @Test
    void aSessionOlderThanTheAbsoluteCapIsRefusedEvenWhenActivelyUsed() {
        // One minute since the last refresh -- the user is mid-session, not idle at all. The cap
        // is the point: activity must not be able to extend a session forever.
        existingToken(Duration.ofMinutes(1), Duration.ofDays(8));

        assertThatThrownBy(() -> service.rotate(RAW_TOKEN))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.AUTH_SESSION_MAX_AGE);
    }

    @Test
    void hittingTheAbsoluteCapEndsOnlyThisDevicesSession() {
        existingToken(Duration.ofMinutes(1), Duration.ofDays(8));

        assertThatThrownBy(() -> service.rotate(RAW_TOKEN)).isInstanceOf(ApiException.class);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getRevokedAt()).isNotNull();

        // Every device carries its OWN session_started_at, stamped when that device signed in, so
        // a phone that signed in later reaches its cap later and needs no help from here. Signing
        // everything out would take down sessions that are nowhere near their limit, and would put
        // an ordinary seven-day expiry in the same bucket as refresh-token reuse -- which really
        // does mean theft, and whose all-sessions revocation is worth keeping distinct.
        verify(repository, never()).findByUserIdAndRevokedAtIsNull(any(UUID.class));
        verify(repository, never()).saveAll(any());
    }

    @Test
    void refreshTokenReuseStillSignsOutEverything() {
        // The contrast that makes the change above safe: reuse of an already-rotated token is a
        // genuine theft signal, and that path must keep its blast radius.
        RefreshToken revoked = new RefreshToken();
        revoked.setUserId(userId);
        revoked.setTokenHash(TokenHasher.sha256(RAW_TOKEN));
        revoked.setExpiresAt(Instant.now().plus(Duration.ofDays(30)));
        revoked.setSessionStartedAt(Instant.now().minus(Duration.ofHours(1)));
        revoked.setRevokedAt(Instant.now().minus(Duration.ofMinutes(1)));
        when(repository.findByTokenHash(TokenHasher.sha256(RAW_TOKEN)))
                .thenReturn(Optional.of(revoked));
        when(repository.findByUserIdAndRevokedAtIsNull(any(UUID.class))).thenReturn(List.of());

        assertThatThrownBy(() -> service.rotate(RAW_TOKEN))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.AUTH_SESSION_REVOKED);

        verify(repository).findByUserIdAndRevokedAtIsNull(userId);
        verify(repository).saveAll(any());
    }

    @Test
    void rotationCarriesTheORIGINALSessionStartForward() {
        Instant sessionStart = Instant.now().minus(Duration.ofDays(3));
        RefreshToken rt = new RefreshToken();
        rt.setUserId(userId);
        rt.setTokenHash(TokenHasher.sha256(RAW_TOKEN));
        rt.setExpiresAt(Instant.now().plus(Duration.ofDays(30)));
        ReflectionTestUtils.setField(rt, "createdAt", Instant.now().minus(Duration.ofMinutes(5)));
        rt.setSessionStartedAt(sessionStart);
        when(repository.findByTokenHash(TokenHasher.sha256(RAW_TOKEN))).thenReturn(Optional.of(rt));

        service.rotate(RAW_TOKEN);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository, times(2)).save(saved.capture());
        RefreshToken issued = saved.getAllValues().get(1);

        // THE test for the absolute cap. If issue() stamped `now` here instead of forwarding the
        // original, every check above would still pass and the cap would silently never fire --
        // the session clock would reset every fifteen minutes, which is precisely the perpetual
        // session this whole change exists to end.
        assertThat(issued.getSessionStartedAt()).isEqualTo(sessionStart);
    }

    @Test
    void aFreshSignInStartsTheSessionClockNow() {
        service.issue(userId);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getSessionStartedAt())
                .isCloseTo(Instant.now(), org.assertj.core.api.Assertions.within(
                        5, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void zeroDisablesEitherLimitForATestOrADeliberateDecision() {
        JwtProperties off = new JwtProperties();
        off.setIdleTimeoutMs(0);
        off.setAbsoluteSessionMs(0);
        ReflectionTestUtils.setField(off, "refreshExpirationMs", Duration.ofDays(30).toMillis());
        RefreshTokenService unlimited = new RefreshTokenService(repository, off,
                mock(HttpServletRequest.class), mock(ClientIpResolver.class));
        existingToken(Duration.ofDays(40), Duration.ofDays(400));

        assertThatCode(() -> unlimited.rotate(RAW_TOKEN)).doesNotThrowAnyException();
        verify(repository, never()).saveAll(any());
    }
}
