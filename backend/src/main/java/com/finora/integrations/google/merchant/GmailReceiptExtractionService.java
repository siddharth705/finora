package com.finora.integrations.google.merchant;

import com.finora.integrations.google.GmailAccessTokenService;
import com.finora.integrations.google.GmailApiClient;
import com.finora.integrations.google.GmailConnection;
import com.finora.integrations.google.GmailMessageGoneException;
import com.finora.integrations.google.GmailProcessedMessage;
import com.finora.integrations.google.GmailProcessedMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The step between "we know this message is trusted" and "a user can review it" — Phase C5-B.
 *
 * <pre>
 *   DETECTED_NOT_STAGED row (C4)
 *     -&gt; fetch body (only now — see {@link GmailApiClient#getMessageBody})
 *     -&gt; {@link MerchantEmailSanitizer}
 *     -&gt; find a {@link MerchantEmailParser} that claims this domain
 *     -&gt; parse
 *     -&gt; {@link ParsedReceiptValidator}
 *     -&gt; {@link GmailStagingBridge}
 *     -&gt; the row's outcome is updated to its final fate
 * </pre>
 *
 * <p>Deliberately a separate class from {@code GmailMessageDiscoveryService}, whose own doc
 * comment states it ends at {@code DETECTED_NOT_STAGED} — discovery decides what is trusted;
 * this decides what a trusted message actually contains. Keeping them separate is also what let
 * C5-A's parser framework be built and reviewed with zero risk to C4's already-shipped discovery
 * path.
 *
 * <h2>Failures are per-message, like discovery's are per-connection</h2>
 *
 * One message's parser throwing, or Gmail returning a transient failure fetching its body, must
 * not stop the batch — the same reasoning {@link com.finora.integrations.google.GmailDiscoveryWorker}
 * applies to per-connection failures, one level down. A message that fails transiently is simply
 * still {@code DETECTED_NOT_STAGED} afterward, and the next run picks it up again.
 */
@Service
public class GmailReceiptExtractionService {

    private static final Logger log = LoggerFactory.getLogger(GmailReceiptExtractionService.class);

    private final GmailApiClient gmail;
    private final GmailAccessTokenService accessTokens;
    private final MerchantEmailSanitizer sanitizer;
    private final List<MerchantEmailParser> parsers;
    private final ParsedReceiptValidator validator;
    private final GmailStagingBridge stagingBridge;
    private final GmailProcessedMessageRepository processedMessages;
    private final TransactionTemplate transactionTemplate;

    public GmailReceiptExtractionService(GmailApiClient gmail,
                                         GmailAccessTokenService accessTokens,
                                         MerchantEmailSanitizer sanitizer,
                                         List<MerchantEmailParser> parsers,
                                         ParsedReceiptValidator validator,
                                         GmailStagingBridge stagingBridge,
                                         GmailProcessedMessageRepository processedMessages,
                                         TransactionTemplate transactionTemplate) {
        this.gmail = gmail;
        this.accessTokens = accessTokens;
        this.sanitizer = sanitizer;
        this.parsers = parsers;
        this.validator = validator;
        this.stagingBridge = stagingBridge;
        this.processedMessages = processedMessages;
        this.transactionTemplate = transactionTemplate;
    }

    /** What one run did — mirrors {@code GmailMessageDiscoveryService.DiscoveryResult}'s reasoning
     *  for existing: returned rather than only logged, so a worker can report it and a test can
     *  assert on it. */
    public record ExtractionResult(int staged, int notAReceipt, int malformed, int noParser) {

        static ExtractionResult nothingToDo() {
            return new ExtractionResult(0, 0, 0, 0);
        }
    }

    /**
     * Extracts and stages up to {@code maxMessages} pending receipts for one connection.
     *
     * @throws com.finora.exception.ApiException on a transient Gmail failure fetching an access
     *         token — that fails the whole batch rather than per-message, since a dead token means
     *         every subsequent body fetch would fail identically; the caller (the worker) is
     *         expected to catch this the same way it catches a discovery failure.
     */
    public ExtractionResult extractFor(GmailConnection connection, int maxMessages) {
        List<GmailProcessedMessage> pending = processedMessages.findByConnectionIdAndOutcomeOrderByProcessedAtAsc(
                connection.getId(), GmailProcessedMessage.Outcome.DETECTED_NOT_STAGED,
                PageRequest.of(0, maxMessages));
        if (pending.isEmpty()) {
            return ExtractionResult.nothingToDo();
        }

        String accessToken = accessTokens.accessTokenFor(connection);

        int staged = 0;
        int notAReceipt = 0;
        int malformed = 0;
        int noParser = 0;

        for (GmailProcessedMessage message : pending) {
            Optional<MerchantEmailParser> parser = parsers.stream()
                    .filter(p -> p.canParse(message.getAuthenticatedDomain()))
                    .findFirst();

            if (parser.isEmpty()) {
                // Left as DETECTED_NOT_STAGED, not advanced -- there is no parser to have decided
                // anything, so nothing has actually happened to this message yet. It becomes
                // PARSED/PARSE_FAILED/SKIPPED_NOT_RECEIPT retroactively the day a parser for this
                // domain ships and a future run reaches it again.
                noParser++;
                continue;
            }

            try {
                switch (processOne(connection, accessToken, message, parser.get())) {
                    case STAGED -> staged++;
                    case NOT_A_RECEIPT -> notAReceipt++;
                    case MALFORMED -> malformed++;
                }
            } catch (RuntimeException e) {
                // Transient (a Gmail timeout, a rate limit) by elimination -- GmailMessageGoneException
                // is handled inside processOne, not here. Left DETECTED_NOT_STAGED; the next run
                // retries this specific message for free, the same guarantee discovery's checkpoint
                // gives a message it never got to examine.
                log.warn("Gmail receipt extraction failed for message {} (connection {}): {}",
                        message.getGmailMessageId(), connection.getId(), e.getClass().getSimpleName());
            }
        }

        if (staged + notAReceipt + malformed + noParser > 0) {
            log.info("Gmail extraction for connection {}: {} staged, {} not receipts, {} malformed, "
                            + "{} with no parser yet.", connection.getId(), staged, notAReceipt,
                    malformed, noParser);
        }
        return new ExtractionResult(staged, notAReceipt, malformed, noParser);
    }

    private enum Outcome { STAGED, NOT_A_RECEIPT, MALFORMED }

    private Outcome processOne(GmailConnection connection, String accessToken,
                               GmailProcessedMessage message, MerchantEmailParser parser) {
        GmailApiClient.MessageBody body;
        try {
            body = gmail.getMessageBody(accessToken, message.getGmailMessageId());
        } catch (GmailMessageGoneException e) {
            // Deleted between discovery and extraction. Same reasoning as discovery's own handling
            // of this: nothing to decide about a message that no longer exists, recorded as such
            // rather than left to retry forever against an id that can never succeed.
            recordOutcome(message, GmailProcessedMessage::markSkippedNotReceipt);
            return Outcome.NOT_A_RECEIPT;
        }

        SanitizedGmailMessage sanitized = sanitizer.sanitize(
                message.getGmailMessageId(), message.getAuthenticatedDomain(),
                body.html() != null ? body.html() : body.plainText());

        ParserResult result = parser.parse(sanitized);

        return switch (result.status()) {
            case NOT_A_RECEIPT -> {
                recordOutcome(message, GmailProcessedMessage::markSkippedNotReceipt);
                yield Outcome.NOT_A_RECEIPT;
            }
            case MALFORMED -> {
                recordOutcome(message, GmailProcessedMessage::markParseFailed);
                yield Outcome.MALFORMED;
            }
            case PARSED -> {
                List<ParsedReceiptValidator.Violation> violations = validator.validate(result.receipt());
                if (!violations.isEmpty()) {
                    log.debug("Gmail receipt for message {} failed validation: {}",
                            message.getGmailMessageId(), violations);
                    recordOutcome(message, GmailProcessedMessage::markParseFailed);
                    yield Outcome.MALFORMED;
                }
                stagingBridge.stage(connection.getUserId(), result.receipt());
                recordOutcome(message, GmailProcessedMessage::markParsed);
                yield Outcome.STAGED;
            }
        };
    }

    /** One short transaction per message, same reasoning as discovery's own per-message writes:
     *  a run makes one Gmail call and one staging call per message, so a run-scoped transaction
     *  would hold a pooled connection for the length of the whole batch (BH-016, BH-047). */
    private void recordOutcome(GmailProcessedMessage message, Consumer<GmailProcessedMessage> transition) {
        transactionTemplate.executeWithoutResult(tx -> {
            transition.accept(message);
            processedMessages.save(message);
        });
    }
}
