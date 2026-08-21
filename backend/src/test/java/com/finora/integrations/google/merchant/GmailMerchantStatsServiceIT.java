package com.finora.integrations.google.merchant;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.AdminDtos.GmailMerchantParserStatDto;
import com.finora.entity.User;
import com.finora.integrations.google.GmailApiClient;
import com.finora.integrations.google.GmailConnection;
import com.finora.integrations.google.GmailConnectionRepository;
import com.finora.integrations.google.GmailProcessedMessage;
import com.finora.integrations.google.GmailProcessedMessage.Outcome;
import com.finora.integrations.google.GmailProcessedMessageRepository;
import com.finora.integrations.google.SenderAuthenticationService;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real-Postgres half of C6.2. {@code GmailMerchantStatsServiceTest} proves the aggregation
 * math against fabricated rows; this proves {@code
 * GmailProcessedMessageRepository#merchantOutcomeCounts}'s JPQL actually compiles and groups
 * correctly against the database -- an enum-in-JPQL typo or a wrong GROUP BY key would pass a
 * mocked-repository test and fail only here.
 */
class GmailMerchantStatsServiceIT extends AbstractIntegrationTest {

    @Autowired private GmailMerchantStatsService service;
    @Autowired private GmailProcessedMessageRepository processedMessages;
    @Autowired private GmailConnectionRepository connections;
    @Autowired private UserRepository userRepository;

    // AbstractIntegrationTest runs every test against one shared, non-transactional Postgres --
    // rows from other tests (this class and others) are still there and still inside any wide
    // "since" window. Every test below therefore uses its own random domain and asserts by
    // finding that domain's row rather than the list's total size, the same discipline
    // AdminMerchantStatsControllerIT's real-Postgres tests already apply.

    @Test
    void aggregatesAcrossTwoConnectionsAndExcludesUntrustedSenderTraffic() {
        String domain = "amazon-" + UUID.randomUUID() + ".test";
        UUID connectionA = persistConnection().getId();
        UUID connectionB = persistConnection().getId();
        Instant since = Instant.now().minus(1, ChronoUnit.DAYS);

        // Same merchant domain, two different mailboxes -- the dashboard reports platform-wide,
        // not per-connection, so these two must combine into one row for this domain.
        processedMessages.saveAndFlush(GmailProcessedMessage.trusted(
                connectionA, "msg-1", Outcome.DETECTED_NOT_STAGED, domain));
        processedMessages.saveAndFlush(GmailProcessedMessage.trusted(
                connectionB, "msg-2", Outcome.DETECTED_NOT_STAGED, domain));
        // A refused sender for the same domain must NOT count toward its totals -- it never
        // reached a parser, and merchantOutcomeCounts filters SKIPPED_UNTRUSTED_SENDER out.
        processedMessages.saveAndFlush(GmailProcessedMessage.skipped(connectionA, "msg-3",
                new SenderAuthenticationService.Result(
                        SenderAuthenticationService.Verdict.DOMAIN_NOT_TRUSTED, domain)));

        GmailMerchantParserStatDto row = rowFor(service.parserStats(since), domain);

        assertThat(row.noParserYet()).isEqualTo(2);
        assertThat(row.parsed()).isZero();
        assertThat(row.successRate()).isNull();
    }

    @Test
    void aWindowBeforeAnyMessageExistsReturnsNothingForThatDomain() {
        String domain = "amazon-" + UUID.randomUUID() + ".test";
        UUID connectionId = persistConnection().getId();
        processedMessages.saveAndFlush(GmailProcessedMessage.trusted(
                connectionId, "msg-old", Outcome.PARSED, domain));

        List<GmailMerchantParserStatDto> stats = service.parserStats(Instant.now().plusSeconds(60));

        assertThat(stats).extracting(GmailMerchantParserStatDto::domain).doesNotContain(domain);
    }

    private static GmailMerchantParserStatDto rowFor(List<GmailMerchantParserStatDto> stats, String domain) {
        return stats.stream().filter(s -> s.domain().equals(domain)).findFirst()
                .orElseThrow(() -> new AssertionError("no stats row for domain " + domain));
    }

    private GmailConnection persistConnection() {
        GmailConnection connection = new GmailConnection();
        connection.setUserId(newUser().getId());
        connection.setGoogleUserId("google-sub-" + UUID.randomUUID());
        connection.setGoogleEmail("mailbox-" + UUID.randomUUID() + "@example.test");
        connection.setGrantedScopes(GmailApiClient.GMAIL_READONLY_SCOPE);
        connection.setStatus(GmailConnection.Status.CONNECTED);
        return connections.saveAndFlush(connection);
    }

    private User newUser() {
        User user = new User();
        user.setEmail("gmail-merchant-stats-it-" + UUID.randomUUID() + "@example.test");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Gmail Merchant Stats IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }
}
