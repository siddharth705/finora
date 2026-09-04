package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ImportService#dryRunParse} -- the zero-write parser re-run building block Plan 3 of the
 * Held Statement Review System depends on. See that method's own doc for why it exists separately
 * from {@link ImportService#parseAndStageWithSession}/{@link ImportService#parseAndStagePdfWithSession}:
 * those call {@code ImportSessionService.findLiveSessionByContentHash} first, which deletes a live
 * {@code STAGED} session on a parser-version mismatch -- exactly the condition a re-run tests.
 */
class ImportServiceDryRunIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private ImportSessionRepository importSessionRepository;
    @Autowired private UserRepository userRepository;

    private static final byte[] CSV = ("Date,Description,Amount,Balance\n"
            + "01/01/2026,Opening balance,,1000.00\n"
            + "05/01/2026,Coffee shop,-150.00,850.00\n").getBytes(StandardCharsets.UTF_8);

    private User user() {
        User user = new User();
        user.setEmail("dry-run-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Dry Run User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    @Test
    void dryRunParseReturnsVerificationAndPeriodsWithoutWritingAnySession() throws Exception {
        UUID userId = user().getId();
        long sessionsBefore = importSessionRepository.count();

        ImportService.DryRunResult result = importService.dryRunParse(userId, "statement.csv", CSV, "CSV");

        assertThat(result.verificationReports()).isNotNull();
        assertThat(result.statementPeriods()).isNotNull();
        assertThat(importSessionRepository.count()).isEqualTo(sessionsBefore);
    }

    @Test
    void dryRunParseTwiceInARowDoesNotTriggerTheLiveDuplicateGuard() throws Exception {
        // The live path's findLiveSessionByContentHash would short-circuit a second call on the
        // same bytes. A dry run must not: an engineer re-running the same held statement's bytes
        // twice (e.g. checking again after fixing nothing) must get two independent parses, not a
        // duplicate-detection response meant for a different feature.
        UUID userId = user().getId();

        ImportService.DryRunResult first = importService.dryRunParse(userId, "statement.csv", CSV, "CSV");
        ImportService.DryRunResult second = importService.dryRunParse(userId, "statement.csv", CSV, "CSV");

        assertThat(first.verificationReports()).hasSameSizeAs(second.verificationReports());
        assertThat(first.statementPeriods()).hasSameSizeAs(second.statementPeriods());
    }
}
