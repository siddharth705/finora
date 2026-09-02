package com.finora.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.notification.api.DeviceTokenService;
import com.finora.notification.domain.DeviceToken;
import com.finora.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exercises behavior only a real Postgres -- and the real {@link DeviceTokenService} /
 * {@link DeviceTokenRepository} wiring, not mocks -- can validate:
 *
 * <ul>
 *   <li>The whole argument behind V127's plain {@code UNIQUE (user_id, token_fingerprint)}
 *       (rather than a partial index): re-registering a revoked token reactivates the SAME row via
 *       UPDATE, so it never collides with the constraint.</li>
 *   <li>Fix round 1, CRITICAL 1: registering a token already held by a different user actually
 *       revokes that other user's row in the database, not just in an in-memory mock -- and that
 *       Postgres allows the shared {@code token_fingerprint} to exist under two different
 *       {@code user_id}s at once, since the constraint is scoped per user.</li>
 *   <li>{@code device_tokens.user_id ... ON DELETE CASCADE}, previously untested.</li>
 * </ul>
 */
class DeviceTokenRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private DeviceTokenRepository repository;

    @Autowired
    private DeviceTokenService service;

    @Autowired
    private UserRepository userRepository;

    private UUID newUser() {
        // Construction copied from NotificationLogRepositoryIT.setUp() -- there is no shared
        // newTestUser() helper.
        User user = new User();
        user.setEmail("test-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Test User");
        return userRepository.save(user).getId();
    }

    @Test
    @Transactional
    void reregisteringARevokedToken_reactivatesTheSameRowInsteadOfColliding() {
        UUID userId = newUser();
        DeviceToken first = service.register(userId, "ANDROID", "device-token-alpha");
        service.revoke(userId, "device-token-alpha");

        DeviceToken reregistered = service.register(userId, "ANDROID", "device-token-alpha");

        assertThat(reregistered.getId()).isEqualTo(first.getId());
        assertThat(reregistered.getRevokedAt()).isNull();
        // Exactly one row for this user -- a partial-index scheme (rejected in the migration's own
        // comment) would have let the revoked row and a fresh insert coexist as two rows instead.
        assertThat(repository.findByUserIdAndRevokedAtIsNull(userId)).hasSize(1);
    }

    @Test
    @Transactional
    void registeringATokenAlreadyHeldByAnotherUser_revokesTheOtherUsersRowInTheDatabase() {
        UUID userA = newUser();
        UUID userB = newUser();
        DeviceToken tokenA = service.register(userA, "ANDROID", "shared-device-token");

        DeviceToken tokenB = service.register(userB, "ANDROID", "shared-device-token");

        DeviceToken reloadedA = repository.findById(tokenA.getId()).orElseThrow();
        assertThat(reloadedA.getRevokedAt()).isNotNull();
        assertThat(tokenB.getRevokedAt()).isNull();
        assertThat(repository.findByUserIdAndRevokedAtIsNull(userA)).isEmpty();
        assertThat(repository.findByUserIdAndRevokedAtIsNull(userB))
                .extracting(DeviceToken::getId)
                .containsExactly(tokenB.getId());
    }

    @Test
    @Transactional
    void deletingAUser_cascadesToTheirDeviceTokens() {
        UUID userId = newUser();
        service.register(userId, "ANDROID", "cascade-token");

        userRepository.deleteById(userId);
        userRepository.flush();

        assertThat(repository.findByUserIdAndRevokedAtIsNull(userId)).isEmpty();
    }
}
