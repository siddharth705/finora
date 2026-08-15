package com.finora.integrations.google;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The outcome-transition guard added in C5-B. This row's whole purpose is to record a message's
 * fate exactly once (see the class doc), so the property worth pinning is not "does the outcome
 * change" but "can it be changed a SECOND time" — it must not, or an overlapping extraction run
 * could silently overwrite what actually happened the first time.
 */
class GmailProcessedMessageTest {

    private static GmailProcessedMessage detectedNotStaged() {
        return GmailProcessedMessage.trusted(UUID.randomUUID(), "m1",
                GmailProcessedMessage.Outcome.DETECTED_NOT_STAGED, "amazon.in");
    }

    @Test
    void markParsedTransitionsFromDetectedNotStaged() {
        GmailProcessedMessage message = detectedNotStaged();

        message.markParsed();

        assertThat(message.getOutcome()).isEqualTo(GmailProcessedMessage.Outcome.PARSED);
    }

    @Test
    void markParseFailedTransitionsFromDetectedNotStaged() {
        GmailProcessedMessage message = detectedNotStaged();

        message.markParseFailed();

        assertThat(message.getOutcome()).isEqualTo(GmailProcessedMessage.Outcome.PARSE_FAILED);
    }

    @Test
    void markSkippedNotReceiptTransitionsFromDetectedNotStaged() {
        GmailProcessedMessage message = detectedNotStaged();

        message.markSkippedNotReceipt();

        assertThat(message.getOutcome()).isEqualTo(GmailProcessedMessage.Outcome.SKIPPED_NOT_RECEIPT);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("a message cannot be decided about twice")
    void aSecondTransitionIsRejected() {
        GmailProcessedMessage message = detectedNotStaged();
        message.markParsed();

        assertThatThrownBy(message::markParseFailed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("m1")
                .hasMessageContaining("PARSED");
    }

    @Test
    void aMessageThatWasNeverDetectedNotStagedCannotBeTransitioned() {
        GmailProcessedMessage skipped = GmailProcessedMessage.skipped(UUID.randomUUID(), "m1",
                new SenderAuthenticationService.Result(
                        SenderAuthenticationService.Verdict.NOT_AUTHENTICATED, null));

        assertThatThrownBy(skipped::markParsed).isInstanceOf(IllegalStateException.class);
    }
}
