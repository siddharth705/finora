package com.finora.integrations.google;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The C4 storage guarantees, against a real Postgres — because every one of them is enforced by the
 * database rather than by Java, and none of them can be observed anywhere else.
 *
 * <p>{@code GmailMessageDiscoveryServiceTest} proves the service asks for the right things. This
 * proves the schema actually delivers them: the unique index that makes at-least-once safe, the
 * CHECK constraints that keep the provenance table answerable, and the due-query ordering that
 * decides whose mailbox gets looked at.
 */
class GmailProcessedMessageIT extends AbstractIntegrationTest {

    @Autowired private GmailProcessedMessageRepository processedMessages;
    @Autowired private GmailConnectionRepository connections;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;

    // ---------------------------------------------------------------------------------------
    // Idempotency
    // ---------------------------------------------------------------------------------------

    /**
     * The guarantee the whole resume story rests on. Discovery is at-least-once by design: a run
     * that dies halfway is re-run, and an overlapping window re-lists messages on purpose. Without
     * this index the second pass would write a second row, and the provenance table would report a
     * message decided twice with no way to tell which decision was current.
     */
    @Test
    @DisplayName("one message can be decided at most once per connection")
    void aMessageCannotBeRecordedTwiceForTheSameConnection() {
        UUID connectionId = persistConnection(GmailConnection.Status.CONNECTED, null).getId();
        processedMessages.saveAndFlush(GmailProcessedMessage.trusted(connectionId, "msg-1",
                GmailProcessedMessage.Outcome.DETECTED_NOT_STAGED, "merchant.example"));

        assertThatThrownBy(() -> processedMessages.saveAndFlush(
                GmailProcessedMessage.trusted(connectionId, "msg-1",
                        GmailProcessedMessage.Outcome.DETECTED_NOT_STAGED, "merchant.example")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Two mailboxes can legitimately both receive the same merchant message id -- ids are unique
     *  per mailbox, not globally -- so the constraint must be scoped to the connection. */
    @Test
    void thesameMessageIdInADifferentMailboxIsAllowed() {
        UUID first = persistConnection(GmailConnection.Status.CONNECTED, null).getId();
        UUID second = persistConnection(GmailConnection.Status.CONNECTED, null).getId();

        processedMessages.saveAndFlush(GmailProcessedMessage.trusted(first, "shared-id",
                GmailProcessedMessage.Outcome.DETECTED_NOT_STAGED, "merchant.example"));
        processedMessages.saveAndFlush(GmailProcessedMessage.trusted(second, "shared-id",
                GmailProcessedMessage.Outcome.DETECTED_NOT_STAGED, "merchant.example"));

        assertThat(processedMessages.countByConnectionId(first)).isEqualTo(1);
        assertThat(processedMessages.countByConnectionId(second)).isEqualTo(1);
    }

    /**
     * The subtraction that saves the expensive call. It must be scoped to the connection: returning
     * another mailbox's ids would make discovery skip messages it had never actually examined, and
     * a skipped message leaves no trace to notice.
     */
    @Test
    void alreadyProcessedIdsAreScopedToTheConnection() {
        UUID mine = persistConnection(GmailConnection.Status.CONNECTED, null).getId();
        UUID theirs = persistConnection(GmailConnection.Status.CONNECTED, null).getId();
        processedMessages.saveAndFlush(GmailProcessedMessage.trusted(mine, "mine-1",
                GmailProcessedMessage.Outcome.DETECTED_NOT_STAGED, "merchant.example"));
        processedMessages.saveAndFlush(GmailProcessedMessage.trusted(theirs, "theirs-1",
                GmailProcessedMessage.Outcome.DETECTED_NOT_STAGED, "merchant.example"));

        assertThat(processedMessages.findAlreadyProcessedIds(mine,
                List.of("mine-1", "theirs-1", "never-seen")))
                .containsExactly("mine-1");
    }

    // ---------------------------------------------------------------------------------------
    // The CHECK constraints
    // ---------------------------------------------------------------------------------------

    /**
     * The provenance table is what support reads to answer "why did nothing appear for this
     * receipt?". A typo'd outcome would not break anything loudly — it would quietly produce a row
     * nobody can interpret, which is worse than a rejected write.
     */
    @Test
    void anUnknownOutcomeIsRejectedByTheDatabase() {
        UUID connectionId = persistConnection(GmailConnection.Status.CONNECTED, null).getId();

        assertThatThrownBy(() -> jdbc.update("""
                insert into gmail_processed_messages (id, connection_id, gmail_message_id, outcome)
                values (?, ?, ?, ?)
                """, UUID.randomUUID(), connectionId, "msg-typo", "SKIPPED_UNTRUSTED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** A row cannot claim both that a message was trusted and that it was skipped for a reason --
     *  the two together are unanswerable, and the constraint is what makes that unrepresentable. */
    @Test
    void anUnknownSkipReasonIsRejectedByTheDatabase() {
        UUID connectionId = persistConnection(GmailConnection.Status.CONNECTED, null).getId();

        assertThatThrownBy(() -> jdbc.update("""
                insert into gmail_processed_messages
                    (id, connection_id, gmail_message_id, outcome, skip_reason)
                values (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), connectionId, "msg-bad-reason",
                "SKIPPED_UNTRUSTED_SENDER", "TRUSTED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Every verdict the gate can actually produce must be storable. A constraint that rejected one
     *  of them would turn a routine skip into a failed run. */
    @Test
    void everyRefusalVerdictTheGateProducesIsStorable() {
        UUID connectionId = persistConnection(GmailConnection.Status.CONNECTED, null).getId();
        int stored = 0;
        for (SenderAuthenticationService.Verdict verdict : SenderAuthenticationService.Verdict.values()) {
            if (verdict == SenderAuthenticationService.Verdict.TRUSTED) continue;
            processedMessages.saveAndFlush(GmailProcessedMessage.skipped(connectionId,
                    "msg-" + verdict, new SenderAuthenticationService.Result(verdict, null)));
            stored++;
        }
        assertThat(processedMessages.countByConnectionId(connectionId)).isEqualTo(stored);
    }

    // ---------------------------------------------------------------------------------------
    // Who gets looked at, and in what order
    // ---------------------------------------------------------------------------------------

    /**
     * Never-checked connections sort first, so a mailbox connected moments ago is picked up on the
     * next tick instead of queueing behind every established one. {@code nulls first} is not the
     * default in Postgres for ascending order, so this is exactly the kind of thing that works in
     * HQL and silently does the opposite in SQL.
     */
    @Test
    @DisplayName("a never-checked mailbox is looked at before a recently-checked one")
    void neverCheckedConnectionsComeFirst() {
        GmailConnection checkedRecently = persistConnection(GmailConnection.Status.CONNECTED,
                Instant.now().minus(Duration.ofHours(6)));
        GmailConnection neverChecked = persistConnection(GmailConnection.Status.CONNECTED, null);

        List<GmailConnection> due = connections.findDueForDiscovery(
                Instant.now().minus(Duration.ofHours(1)), PageRequest.of(0, 10));

        assertThat(due).extracting(GmailConnection::getId)
                .containsSubsequence(neverChecked.getId(), checkedRecently.getId());
    }

    /** A dead grant costs a token-refresh request to rediscover. Excluding it at the query is what
     *  keeps REAUTH_REQUIRED from being a standing per-tick tax that nothing ever clears. */
    @Test
    void connectionsNeedingReauthAreNotDue() {
        GmailConnection needsReauth = persistConnection(GmailConnection.Status.REAUTH_REQUIRED, null);

        List<GmailConnection> due = connections.findDueForDiscovery(
                Instant.now().minus(Duration.ofHours(1)), PageRequest.of(0, 10));

        assertThat(due).extracting(GmailConnection::getId).doesNotContain(needsReauth.getId());
    }

    /** The rest interval. A mailbox checked a minute ago is not due, which is what stops one tick's
     *  slice from being the same mailboxes forever while the tail never comes up. */
    @Test
    void aRecentlyCheckedConnectionIsNotDue() {
        GmailConnection justChecked = persistConnection(GmailConnection.Status.CONNECTED,
                Instant.now().minus(Duration.ofMinutes(1)));

        List<GmailConnection> due = connections.findDueForDiscovery(
                Instant.now().minus(Duration.ofHours(1)), PageRequest.of(0, 10));

        assertThat(due).extracting(GmailConnection::getId).doesNotContain(justChecked.getId());
    }

    private GmailConnection persistConnection(GmailConnection.Status status, Instant lastDiscoveryAt) {
        GmailConnection connection = new GmailConnection();
        connection.setUserId(newUser().getId());
        connection.setGoogleUserId("google-sub-" + UUID.randomUUID());
        connection.setGoogleEmail("mailbox-" + UUID.randomUUID() + "@example.test");
        connection.setGrantedScopes(GmailApiClient.GMAIL_READONLY_SCOPE);
        connection.setStatus(status);
        connection.setLastDiscoveryAt(lastDiscoveryAt);
        return connections.saveAndFlush(connection);
    }

    /** gmail_connections.user_id is a real foreign key, so a connection needs a real owner. */
    private User newUser() {
        User user = new User();
        user.setEmail("gmail-c4-it-" + UUID.randomUUID() + "@example.test");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Gmail C4 IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }
}
