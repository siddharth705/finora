package com.finora.integrations.google;

import com.finora.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Answers one question for one connection: <b>what is worth looking at in this mailbox?</b> — Phase
 * C4.
 *
 * <p>This is the first thing in Finora that reads a user's mail. Everything it does is shaped by
 * that, so the boundaries are worth stating outright.
 *
 * <h2>What C4 ends at</h2>
 *
 * <i>"These messages exist, from these authenticated domains, and here is what we decided about
 * each."</i> Nothing here parses, stages, or creates a transaction, and nothing here fetches a
 * message body — {@link GmailApiClient} is structurally incapable of it. Merchant parsers and the
 * bridge into {@code ImportSessionService} are C5.
 *
 * <h2>The order of operations is the security property</h2>
 *
 * <pre>
 *   list message ids   (no content)
 *     -&gt; subtract ids already decided about
 *     -&gt; fetch HEADERS only
 *     -&gt; {@link SenderAuthenticationService} gate
 *     -&gt; record the outcome
 * </pre>
 *
 * The gate reads headers, so headers are all that is fetched. Mail from a sender about to be
 * rejected never has its content downloaded at all — which is a stronger guarantee than sanitising
 * untrusted content after the fact, because the untrusted bytes never enter the process.
 *
 * <h2>At-least-once, and why that is the right guarantee</h2>
 *
 * A message may be examined twice; the unique index on
 * {@code (connection_id, gmail_message_id)} makes the second write a no-op. Exactly-once across an
 * external API and a database needs more machinery than this earns. The failure modes are not
 * symmetric: a re-examined message costs one Gmail call, while a missed one is invisible — nothing
 * downstream can tell a receipt that was never seen from a mailbox that never received one.
 *
 * <p>So the ordering rule is: <b>record the outcome, then advance the window — and only if the run
 * reached the end of that window.</b> {@code last_discovery_at} moves after a completed run, and the
 * next run re-asks with an overlap. Advancing first would lose messages on a crash; advancing after
 * a run that stopped at its per-run cap would lose every message the cap cut off, which on a first
 * run is most of a ninety-day backlog.
 *
 * <h2>Not transactional</h2>
 *
 * Deliberately, and for the reason BH-016 and BH-047 already established: a pooled database
 * connection must not be held across an outbound HTTP call. A run makes one Gmail request per
 * candidate message, so a run-scoped transaction would hold one of ten pooled connections for the
 * length of a mailbox scan. Each write takes its own short transaction instead.
 */
@Service
public class GmailMessageDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(GmailMessageDiscoveryService.class);

    /** Gmail's {@code after:} takes a date, not an instant, in the mailbox's own terms. */
    private static final DateTimeFormatter GMAIL_DATE =
            DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);

    /**
     * How far back a first run looks.
     *
     * <p>Bounded rather than "the whole mailbox" because an unbounded first scan of a ten-year
     * mailbox is the worst possible first impression of this feature: minutes of quota, thousands of
     * requests, and a queue of receipts for orders the user has long forgotten. Ninety days is the
     * design proposal's figure.
     */
    static final Duration INITIAL_WINDOW = Duration.ofDays(90);

    /**
     * How far before {@code last_discovery_at} a subsequent run re-asks.
     *
     * <p>Not zero, because {@code after:} is a whole-day filter in the mailbox's timezone and mail
     * can be delivered with a header date slightly behind its arrival. The overlap costs nothing —
     * re-listed ids are subtracted against the processed table before any header is fetched — and
     * without it a message can fall between two runs and never be seen again.
     */
    static final Duration WINDOW_OVERLAP = Duration.ofDays(2);

    private final GmailApiClient gmail;
    private final GmailAccessTokenService accessTokens;
    private final SenderAuthenticationService senderAuthentication;
    private final GmailProcessedMessageRepository processedMessages;
    private final GmailConnectionRepository connections;
    private final TransactionTemplate transactionTemplate;

    public GmailMessageDiscoveryService(GmailApiClient gmail,
                                        GmailAccessTokenService accessTokens,
                                        SenderAuthenticationService senderAuthentication,
                                        GmailProcessedMessageRepository processedMessages,
                                        GmailConnectionRepository connections,
                                        TransactionTemplate transactionTemplate) {
        this.gmail = gmail;
        this.accessTokens = accessTokens;
        this.senderAuthentication = senderAuthentication;
        this.processedMessages = processedMessages;
        this.connections = connections;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * What one run did. Returned rather than logged so the worker can report it and a test can
     * assert on it without reading log output.
     *
     * @param examined  messages whose headers were fetched
     * @param trusted   messages that cleared the gate
     * @param skipped   messages the gate refused
     * @param alreadySeen ids listed but already decided about — the saving the processed table buys
     * @param vanished  ids Gmail no longer had by the time they were fetched
     * @param hitLimit  whether the run stopped on its own cap rather than running out of mail
     */
    public record DiscoveryResult(int examined, int trusted, int skipped, int alreadySeen,
                                  int vanished, boolean hitLimit) {

        static DiscoveryResult nothingToDo() {
            return new DiscoveryResult(0, 0, 0, 0, 0, false);
        }
    }

    /** What happened to one message. */
    private enum Examination { TRUSTED, REFUSED, GONE }

    /**
     * Runs discovery for one connection.
     *
     * @param maxMessages the per-run cap. Hitting it is a <b>pause, not a failure</b> — the
     *                    processed table means the next run resumes rather than restarts, and one
     *                    large mailbox must not be able to monopolise a tick.
     * @throws ApiException on a transient Gmail failure. Not caught here on purpose: the run is the
     *         unit of retry, and the next scheduled tick resumes from what was already recorded.
     */
    public DiscoveryResult discoverFor(GmailConnection connection, int maxMessages) {
        if (connection.getStatus() != GmailConnection.Status.CONNECTED) {
            // REAUTH_REQUIRED is the interesting one: the grant is dead and only the user can fix
            // it, so retrying costs a request and produces the same answer every tick.
            return DiscoveryResult.nothingToDo();
        }
        if (!connection.hasGmailReadScope()) {
            // A connection that completed consent without the scope. C2 makes this visible on
            // verify; here it would be one 403 per message, so it is checked before any call.
            log.debug("Skipping discovery for connection {}: gmail.readonly was never granted.",
                    connection.getId());
            return DiscoveryResult.nothingToDo();
        }

        String accessToken = accessTokens.accessTokenFor(connection);
        String query = queryFor(connection);

        int examined = 0;
        int trusted = 0;
        int skipped = 0;
        int alreadySeen = 0;
        int vanished = 0;
        boolean hitLimit = false;
        String pageToken = null;

        do {
            int remaining = maxMessages - examined;
            GmailApiClient.MessagePage page = gmail.listMessages(
                    accessToken, query, pageToken, Math.min(remaining, 100));

            List<String> ids = page.messages().stream().map(GmailApiClient.MessageId::id).toList();
            if (ids.isEmpty()) break;

            // One query per page rather than one per message: the page is already in hand, and the
            // point of this lookup is to avoid the expensive per-message header fetch.
            Set<String> alreadyDecided = new HashSet<>(
                    processedMessages.findAlreadyProcessedIds(connection.getId(), ids));

            for (String messageId : ids) {
                if (alreadyDecided.contains(messageId)) {
                    alreadySeen++;
                    continue;
                }
                if (examined >= maxMessages) {
                    hitLimit = true;
                    break;
                }
                switch (examine(connection, accessToken, messageId)) {
                    case TRUSTED -> { trusted++; examined++; }
                    case REFUSED -> { skipped++; examined++; }
                    // Deleted between the list and the fetch. Not counted as examined, because
                    // nothing was: there is no message left to have an opinion about.
                    case GONE -> vanished++;
                }
            }

            pageToken = page.nextPageToken();
        } while (pageToken != null && !hitLimit && examined < maxMessages);

        // Only after every outcome above is committed, and only if the run actually reached the end
        // of the window.
        //
        // THE CAP AND THE WINDOW INTERACT, and getting this wrong loses mail silently. A first run
        // looks back ninety days; if it stops at its per-run cap having examined five hundred
        // messages, the rest of that window is still unexamined. Advancing the checkpoint here would
        // make the next run ask for "the last two days" -- and the other eighty-eight days would
        // never be listed again. Nothing would fail; the receipts would simply never exist as far as
        // Finora is concerned.
        //
        // So a capped run leaves the checkpoint where it was. The next run re-lists the same window,
        // subtracts what this run already recorded, and continues where this one stopped. Re-listing
        // is ids-only and cheap; that is the whole reason the expensive call is the header fetch.
        if (!hitLimit) {
            markDiscovered(connection);
        }

        DiscoveryResult result =
                new DiscoveryResult(examined, trusted, skipped, alreadySeen, vanished, hitLimit);
        if (examined > 0 || hitLimit) {
            log.info("Gmail discovery for connection {}: {} examined, {} trusted, {} skipped, "
                            + "{} already seen{}.", connection.getId(), examined, trusted, skipped,
                    alreadySeen, hitLimit ? ", stopped at the per-run cap" : "");
        }
        return result;
    }

    /**
     * Fetches one message's headers and decides about it.
     *
     * <p>A message that vanished between the list and the fetch is skipped rather than fatal, and
     * nothing is recorded for it. Letting it escape would abort the run on an id that can never
     * succeed, and the next run would reach the same id and abort again -- see
     * {@link GmailMessageGoneException}.
     */
    private Examination examine(GmailConnection connection, String accessToken, String messageId) {
        GmailApiClient.MessageHeaders headers;
        try {
            headers = gmail.getMessageHeaders(accessToken, messageId);
        } catch (GmailMessageGoneException e) {
            return Examination.GONE;
        }
        SenderAuthenticationService.Result gate =
                senderAuthentication.evaluate(headers.header("Authentication-Results"));

        if (!gate.isTrusted()) {
            record(GmailProcessedMessage.skipped(connection.getId(), messageId, gate));
            return Examination.REFUSED;
        }

        // Trusted, and that is as far as C4 goes. DETECTED_NOT_STAGED is the honest outcome: the
        // message is from a merchant Finora reads, and no parser exists to read it -- which is
        // exactly the "write a parser for this domain next" signal (design proposal 16.1).
        record(GmailProcessedMessage.trusted(connection.getId(), messageId,
                GmailProcessedMessage.Outcome.DETECTED_NOT_STAGED, gate.authenticatedDomain()));
        return Examination.TRUSTED;
    }

    /**
     * Writes one outcome in its own transaction.
     *
     * <p>A unique-constraint violation is swallowed, not retried and not raised. It means a
     * concurrent or overlapping run already recorded this message — which is precisely what the
     * index exists to make safe, and the at-least-once guarantee working as designed rather than a
     * fault. The alternative, checking first and then inserting, is the TOCTOU race the index
     * removes.
     */
    private void record(GmailProcessedMessage message) {
        try {
            // saveAndFlush, not save: a plain save defers the INSERT to commit, where a constraint
            // violation surfaces as a TransactionSystemException from the template rather than the
            // DataIntegrityViolationException caught below -- so the duplicate would escape as a
            // run-ending failure instead of the no-op it is meant to be.
            transactionTemplate.executeWithoutResult(tx -> processedMessages.saveAndFlush(message));
        } catch (DataIntegrityViolationException e) {
            log.debug("Gmail message {} was already recorded for connection {}.",
                    message.getGmailMessageId(), message.getConnectionId());
        }
    }

    /**
     * The Gmail search this run asks for.
     *
     * <p>Anchored on {@code last_discovery_at} so a mailbox that has been checked recently is asked
     * about a couple of days rather than ninety. There is no {@code category:purchases} filter: it
     * is Gmail's own classification, it is not applied to every account, and a receipt it
     * misclassifies would become permanently invisible to Finora with nothing to indicate why.
     * Filtering happens at the gate, on evidence Finora can see.
     */
    private String queryFor(GmailConnection connection) {
        Instant since = connection.getLastDiscoveryAt() == null
                ? Instant.now().minus(INITIAL_WINDOW)
                : connection.getLastDiscoveryAt().minus(WINDOW_OVERLAP);
        return "after:" + GMAIL_DATE.format(since);
    }

    /** Records that this connection was checked, in its own transaction, re-reading first so a
     *  connection disconnected mid-run is not resurrected. */
    private void markDiscovered(GmailConnection connection) {
        UUID connectionId = connection.getId();
        Instant now = Instant.now();
        transactionTemplate.executeWithoutResult(tx ->
                connections.findById(connectionId).ifPresent(fresh -> {
                    if (fresh.getStatus() != GmailConnection.Status.CONNECTED) return;
                    fresh.setLastDiscoveryAt(now);
                    connections.save(fresh);
                }));
        connection.setLastDiscoveryAt(now);
    }
}
