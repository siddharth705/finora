package com.finora.integrations.google.merchant;

import com.finora.domain.Money;
import com.finora.integrations.google.GmailAccessTokenService;
import com.finora.integrations.google.GmailApiClient;
import com.finora.integrations.google.GmailConnection;
import com.finora.integrations.google.GmailMessageGoneException;
import com.finora.integrations.google.GmailProcessedMessage;
import com.finora.integrations.google.GmailProcessedMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase C5-B orchestration. {@link GmailStagingBridge} and {@link ParsedReceiptValidator} both have
 * their own focused tests; this one is about the WIRING between fetch, sanitize, routing, and those
 * two — specifically the properties that would be invisible from either side alone.
 */
class GmailReceiptExtractionServiceTest {

    private static final String TOKEN = "an-access-token";

    private GmailApiClient gmail;
    private GmailAccessTokenService accessTokens;
    private MerchantEmailParser parser;
    private GmailStagingBridge stagingBridge;
    private GmailProcessedMessageRepository processedMessages;
    private GmailReceiptExtractionService service;

    private GmailConnection connection;

    @BeforeEach
    void setUp() {
        gmail = mock(GmailApiClient.class);
        accessTokens = mock(GmailAccessTokenService.class);
        parser = mock(MerchantEmailParser.class);
        stagingBridge = mock(GmailStagingBridge.class);
        processedMessages = mock(GmailProcessedMessageRepository.class);

        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, java.util.function.Consumer.class).accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        MerchantEmailSanitizer sanitizer = new MerchantEmailSanitizer();
        ParsedReceiptValidator validator = new ParsedReceiptValidator();

        service = new GmailReceiptExtractionService(gmail, accessTokens, sanitizer, List.of(parser),
                validator, stagingBridge, processedMessages, transactionTemplate);

        connection = connection();
        when(accessTokens.accessTokenFor(connection)).thenReturn(TOKEN);
        when(parser.canParse("amazon.in")).thenReturn(true);
    }

    @Test
    @DisplayName("a parsed, valid receipt is staged and the message is marked PARSED")
    void aValidReceiptIsStagedAndMarkedParsed() {
        GmailProcessedMessage message = pendingMessage("m1", "amazon.in");
        when(processedMessages.findByConnectionIdAndOutcomeOrderByProcessedAtAsc(any(), any(), any()))
                .thenReturn(List.of(message));
        when(gmail.getMessageBody(TOKEN, "m1"))
                .thenReturn(new GmailApiClient.MessageBody("<p>Order Total: Rs. 500.00</p>", null));
        ParsedReceipt receipt = new ParsedReceipt("m1", "amazon.in",
                Money.of(new BigDecimal("500.00")), LocalDate.of(2026, 8, 10), 0.9);
        when(parser.parse(any())).thenReturn(ParserResult.parsed(receipt));

        GmailReceiptExtractionService.ExtractionResult result = service.extractFor(connection, 50);

        assertThat(result.staged()).isEqualTo(1);
        verify(stagingBridge).stage(connection.getUserId(), receipt);
        assertThat(message.getOutcome()).isEqualTo(GmailProcessedMessage.Outcome.PARSED);
    }

    @Test
    void aNonReceiptIsMarkedSkippedNotReceipt() {
        GmailProcessedMessage message = pendingMessage("m1", "amazon.in");
        when(processedMessages.findByConnectionIdAndOutcomeOrderByProcessedAtAsc(any(), any(), any()))
                .thenReturn(List.of(message));
        when(gmail.getMessageBody(TOKEN, "m1"))
                .thenReturn(new GmailApiClient.MessageBody("<p>marketing</p>", null));
        when(parser.parse(any())).thenReturn(ParserResult.notAReceipt("no order marker"));

        GmailReceiptExtractionService.ExtractionResult result = service.extractFor(connection, 50);

        assertThat(result.notAReceipt()).isEqualTo(1);
        verifyNoInteractions(stagingBridge);
        assertThat(message.getOutcome()).isEqualTo(GmailProcessedMessage.Outcome.SKIPPED_NOT_RECEIPT);
    }

    @Test
    void aMalformedExtractionIsMarkedParseFailed() {
        GmailProcessedMessage message = pendingMessage("m1", "amazon.in");
        when(processedMessages.findByConnectionIdAndOutcomeOrderByProcessedAtAsc(any(), any(), any()))
                .thenReturn(List.of(message));
        when(gmail.getMessageBody(TOKEN, "m1"))
                .thenReturn(new GmailApiClient.MessageBody("<p>Order # 1</p>", null));
        when(parser.parse(any())).thenReturn(ParserResult.malformed("total not found"));

        GmailReceiptExtractionService.ExtractionResult result = service.extractFor(connection, 50);

        assertThat(result.malformed()).isEqualTo(1);
        verifyNoInteractions(stagingBridge);
        assertThat(message.getOutcome()).isEqualTo(GmailProcessedMessage.Outcome.PARSE_FAILED);
    }

    /**
     * The property that justifies a separate validation step existing at all: a parser can return
     * {@code PARSED} with a receipt that is nonetheless implausible (the real
     * {@link ParsedReceiptValidator} is used in this test, not a stub, specifically so this proves
     * the validator is actually consulted, not merely present on the constructor).
     */
    @Test
    @DisplayName("a PARSED receipt that fails validation is treated as malformed, not staged")
    void aParsedButInvalidReceiptFailsValidationRatherThanBeingStaged() {
        GmailProcessedMessage message = pendingMessage("m1", "amazon.in");
        when(processedMessages.findByConnectionIdAndOutcomeOrderByProcessedAtAsc(any(), any(), any()))
                .thenReturn(List.of(message));
        when(gmail.getMessageBody(TOKEN, "m1"))
                .thenReturn(new GmailApiClient.MessageBody("<p>Order Total: Rs. 0.00</p>", null));
        // Zero amount: extractable, but implausible -- ParsedReceiptValidator's own job.
        ParsedReceipt zeroReceipt = new ParsedReceipt("m1", "amazon.in", Money.ZERO,
                LocalDate.of(2026, 8, 10), 0.9);
        when(parser.parse(any())).thenReturn(ParserResult.parsed(zeroReceipt));

        GmailReceiptExtractionService.ExtractionResult result = service.extractFor(connection, 50);

        assertThat(result.malformed()).isEqualTo(1);
        assertThat(result.staged()).isZero();
        verifyNoInteractions(stagingBridge);
        assertThat(message.getOutcome()).isEqualTo(GmailProcessedMessage.Outcome.PARSE_FAILED);
    }

    @Test
    @DisplayName("a message vanishing between discovery and extraction is skipped, not left to retry forever")
    void aVanishedMessageIsSkippedNotRetried() {
        GmailProcessedMessage message = pendingMessage("m1", "amazon.in");
        when(processedMessages.findByConnectionIdAndOutcomeOrderByProcessedAtAsc(any(), any(), any()))
                .thenReturn(List.of(message));
        when(gmail.getMessageBody(TOKEN, "m1"))
                .thenThrow(new GmailMessageGoneException("gone"));

        GmailReceiptExtractionService.ExtractionResult result = service.extractFor(connection, 50);

        assertThat(result.notAReceipt()).isEqualTo(1);
        assertThat(message.getOutcome()).isEqualTo(GmailProcessedMessage.Outcome.SKIPPED_NOT_RECEIPT);
    }

    /**
     * A trusted domain with no registered parser must not be advanced at all -- there is no parser
     * to have decided anything, so the row stays exactly where discovery left it, ready for the day
     * a parser for this domain ships.
     */
    @Test
    @DisplayName("a domain with no matching parser is left untouched, not marked as any kind of failure")
    void aDomainWithNoParserIsLeftUntouched() {
        when(parser.canParse("amazon.in")).thenReturn(false);
        GmailProcessedMessage message = pendingMessage("m1", "amazon.in");
        when(processedMessages.findByConnectionIdAndOutcomeOrderByProcessedAtAsc(any(), any(), any()))
                .thenReturn(List.of(message));

        GmailReceiptExtractionService.ExtractionResult result = service.extractFor(connection, 50);

        assertThat(result.noParser()).isEqualTo(1);
        verifyNoInteractions(gmail);
        assertThat(message.getOutcome()).isEqualTo(GmailProcessedMessage.Outcome.DETECTED_NOT_STAGED);
    }

    /**
     * A transient Gmail failure fetching one message's body must not stop the batch, mirroring
     * discovery's own per-connection isolation one level down (per-message here).
     */
    @Test
    @DisplayName("a transient failure on one message does not stop the rest of the batch")
    void aTransientFailureOnOneMessageDoesNotStopTheBatch() {
        GmailProcessedMessage failing = pendingMessage("m1", "amazon.in");
        GmailProcessedMessage healthy = pendingMessage("m2", "amazon.in");
        when(processedMessages.findByConnectionIdAndOutcomeOrderByProcessedAtAsc(any(), any(), any()))
                .thenReturn(List.of(failing, healthy));
        when(gmail.getMessageBody(TOKEN, "m1")).thenThrow(new RuntimeException("timeout"));
        when(gmail.getMessageBody(TOKEN, "m2"))
                .thenReturn(new GmailApiClient.MessageBody("<p>Order Total: Rs. 500.00</p>", null));
        when(parser.parse(any())).thenReturn(ParserResult.parsed(new ParsedReceipt(
                "m2", "amazon.in", Money.of(new BigDecimal("500.00")), LocalDate.of(2026, 8, 10), 0.9)));

        GmailReceiptExtractionService.ExtractionResult result = service.extractFor(connection, 50);

        assertThat(result.staged()).isEqualTo(1);
        // The failing message is untouched -- still DETECTED_NOT_STAGED, ready to retry next tick.
        assertThat(failing.getOutcome()).isEqualTo(GmailProcessedMessage.Outcome.DETECTED_NOT_STAGED);
    }

    @Test
    void nothingPendingReturnsAnEmptyResultWithoutTouchingGmail() {
        when(processedMessages.findByConnectionIdAndOutcomeOrderByProcessedAtAsc(any(), any(), any()))
                .thenReturn(List.of());

        GmailReceiptExtractionService.ExtractionResult result = service.extractFor(connection, 50);

        assertThat(result.staged() + result.notAReceipt() + result.malformed() + result.noParser())
                .isZero();
        verifyNoInteractions(gmail);
        verify(accessTokens, never()).accessTokenFor(any());
    }

    private static GmailConnection connection() {
        GmailConnection connection = new GmailConnection();
        connection.setUserId(UUID.randomUUID());
        connection.setGoogleUserId("google-sub-" + UUID.randomUUID());
        connection.setGoogleEmail("mailbox@example.test");
        connection.setGrantedScopes(GmailApiClient.GMAIL_READONLY_SCOPE);
        try {
            var field = GmailConnection.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(connection, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return connection;
    }

    private static GmailProcessedMessage pendingMessage(String gmailMessageId, String domain) {
        return GmailProcessedMessage.trusted(UUID.randomUUID(), gmailMessageId,
                GmailProcessedMessage.Outcome.DETECTED_NOT_STAGED, domain);
    }
}
