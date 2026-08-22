package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.RefreshToken;
import com.finora.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug 28. findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastSeenAtDesc backs the
 * device-management "your active sessions" list, documented (see RefreshTokenRepository) as
 * most-recently-active first. It used to be a derived-name query with no explicit null ordering,
 * which compiled to a plain {@code ORDER BY last_seen_at DESC} -- and Postgres's default null
 * ordering for DESC is NULLS FIRST, so a session with no captured {@code lastSeenAt} (best-effort;
 * skipped whenever there's no live request context, see RefreshTokenService#captureDeviceMetadata)
 * sorted ahead of every genuinely-recent session instead of behind them.
 *
 * <p>An H2/mock-based test can't catch this at all -- the defect is specifically in what SQL
 * Postgres's default null ordering produces, not in any Java comparator.
 */
class RefreshTokenRepositoryIT extends AbstractIntegrationTest {

    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private UserRepository userRepository;

    private RefreshToken tokenFor(UUID userId, Instant lastSeenAt) {
        RefreshToken rt = new RefreshToken();
        rt.setUserId(userId);
        rt.setTokenHash(UUID.randomUUID().toString());
        rt.setExpiresAt(Instant.now().plusSeconds(3600));
        rt.setLastSeenAt(lastSeenAt);
        return rt;
    }

    @Test
    void activeSessions_sortsANeverSeenSessionLast_notFirst() {
        User user = new User();
        user.setEmail("refresh-token-ordering-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Ordering Test");
        user = userRepository.save(user);
        UUID userId = user.getId();

        Instant now = Instant.now();
        RefreshToken neverSeen = refreshTokenRepository.save(tokenFor(userId, null));
        RefreshToken seenAWhileAgo = refreshTokenRepository.save(tokenFor(userId, now.minusSeconds(3600)));
        RefreshToken seenJustNow = refreshTokenRepository.save(tokenFor(userId, now));

        List<RefreshToken> active = refreshTokenRepository
                .findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastSeenAtDesc(userId, now.minusSeconds(7200));

        assertThat(active).extracting(RefreshToken::getId)
                .as("most-recently-active first, and a session with no lastSeenAt at all last -- "
                        + "not ahead of every real session, which is what Postgres's default NULLS "
                        + "FIRST for DESC would otherwise produce")
                .containsExactly(seenJustNow.getId(), seenAWhileAgo.getId(), neverSeen.getId());
    }
}
