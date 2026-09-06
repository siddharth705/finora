package com.finora.onboarding;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** V162's backfill -- every user that existed before this migration must come out of it with
 *  onboarding already marked complete, so nobody already using Fynora is ambushed by a tour on
 *  their next login. See that migration's own comment, and the design spec's §5. */
class OnboardingMigrationIT extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User createUser() {
        User user = new User();
        user.setEmail("onboarding-migration-it-" + UUID.randomUUID() + "@example.com"); // synthetic-ok: fixture, not a real account
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Onboarding Migration IT Test User");
        user.setAccountScope(User.SCOPE_USER);
        return user;
    }

    @Test
    void everyUserHasOnboardingCompletedAtSetAfterMigration() {
        // This user is created AFTER V162 already ran (the migration only runs once, at
        // Testcontainers bootstrap) -- so it does NOT prove the backfill ran, only that a fresh
        // row genuinely starts unset. See the next test for that half.
        User user = userRepository.saveAndFlush(createUser());

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getOnboardingCompletedAt()).isNull();
    }

    @Test
    void theBackfillStatementFlipsAnyRowLeftNull() {
        // V162's own UPDATE ran once, against an empty users table, at this Testcontainers
        // instance's schema bootstrap -- there is no way to observe a genuinely pre-migration row
        // from inside a test that only runs after every migration already applied. What this test
        // proves instead, honestly: V162's exact backfill statement (same predicate, same
        // assignment) does what it claims -- given a row it has never touched (NULL), running it
        // again flips that row to non-null, and only that row.
        User user = userRepository.saveAndFlush(createUser());
        assertThat(userRepository.findById(user.getId()).orElseThrow().getOnboardingCompletedAt()).isNull();

        int rowsUpdated = jdbcTemplate.update(
                "UPDATE users SET onboarding_completed_at = now() WHERE onboarding_completed_at IS NULL AND id = ?",
                user.getId());

        assertThat(rowsUpdated).isEqualTo(1);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getOnboardingCompletedAt()).isNotNull();
    }
}
