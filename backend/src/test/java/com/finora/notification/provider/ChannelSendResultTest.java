package com.finora.notification.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChannelSendResult}'s compact constructor invariant. Mockito-free -- this
 * is a pure record test, matching the style of {@code NotificationTest}.
 */
class ChannelSendResultTest {

    @Test
    void successFactoryProducesANonPermanentResult() {
        ChannelSendResult result = ChannelSendResult.success("resend", "ok");

        assertThat(result.success()).isTrue();
        assertThat(result.permanent()).isFalse();
    }

    @Test
    void failureFactoryProducesANonPermanentResult() {
        ChannelSendResult result = ChannelSendResult.failure("resend", "502 from provider");

        assertThat(result.success()).isFalse();
        assertThat(result.permanent()).isFalse();
    }

    @Test
    void permanentFailureFactoryProducesAPermanentResult() {
        ChannelSendResult result =
                ChannelSendResult.permanentFailure("resend", "no email address on file");

        assertThat(result.success()).isFalse();
        assertThat(result.permanent()).isTrue();
    }

    /**
     * The compact constructor's own invariant, documented on the record itself: a result can never
     * claim both success and permanent failure at once -- "permanent" only has meaning for a
     * failure. Neither factory method can produce this combination, so this goes straight at the
     * canonical constructor to pin the guard itself, not just its two safe callers.
     */
    @Test
    void compactConstructorRejectsSuccessCombinedWithPermanent() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChannelSendResult(true, "resend", "ok", true))
                .withMessageContaining("cannot be marked permanent");
    }

    @Test
    void compactConstructorAllowsSuccessWithoutPermanent() {
        ChannelSendResult result = new ChannelSendResult(true, "resend", "ok", false);

        assertThat(result.success()).isTrue();
        assertThat(result.permanent()).isFalse();
    }
}
