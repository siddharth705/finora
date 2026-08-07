package com.finora.observability;

import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.SentryException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the safety settings rather than trusting them.
 *
 * <p>Every option here is one whose accidental flip ships customer data to a third party, and none
 * of them fail loudly when wrong: a build with {@code sendDefaultPii=true} passes every other test
 * in this repository. These assertions are the only thing that would notice.
 *
 * <p>The same reasoning as {@code SentryScrubberTest}: configuration that silently stops protecting
 * looks exactly like configuration that protects.
 */
class MonitoringConfigTest {

    private SentryOptions optionsFrom(String dsn, String environment, String release) {
        SentryOptions options = new SentryOptions();
        new MonitoringConfig(dsn, environment, release).finoraSentryOptions().configure(options);
        return options;
    }

    @Test
    void withNoDsnMonitoringIsOff_theSafeDefault() {
        // Absent config degrades to a no-op rather than half-working -- the same posture as
        // RESEND_API_KEY and GOOGLE_APPLICATION_CREDENTIALS. This also keeps the 1400-test suite
        // and local development free of network calls with no extra setup.
        SentryOptions options = optionsFrom("", "development", "");

        assertThat(options.isEnabled()).isFalse();
        assertThat(options.getDsn()).isNull();
    }

    @Test
    void aBlankOrNullDsnIsTreatedTheSameAsUnset() {
        assertThat(optionsFrom(null, "development", "").isEnabled()).isFalse();
        assertThat(optionsFrom("   ", "development", "").isEnabled()).isFalse();
    }

    @Test
    void withADsnThePiiProtectionsAreAllOn() {
        SentryOptions options = optionsFrom("https://key@example.invalid/1", "production", "abc123");

        assertThat(options.isSendDefaultPii())
                .as("would attach IP address, cookies and headers")
                .isFalse();
        assertThat(options.getMaxRequestBodySize())
                .as("a registration body holds email, phone and a plaintext password; "
                        + "an import body is a bank statement")
                .isEqualTo(SentryOptions.RequestSize.NONE);
        assertThat(options.getTracesSampleRate())
                .as("performance spans are keyed by URL, which reintroduces the identifiers "
                        + "SentryScrubber strips")
                .isEqualTo(0.0);
        assertThat(options.isAttachThreads())
                .as("thread dumps can carry parsed statement rows in locals")
                .isFalse();
    }

    @Test
    void withADsnBothScrubbersAreActuallyWired() {
        // The failure this catches: options configured correctly but the callbacks never attached,
        // which looks identical to a working setup until an event carrying a statement is sent.
        SentryOptions options = optionsFrom("https://key@example.invalid/1", "production", "abc123");

        assertThat(options.getBeforeSend()).isNotNull();
        assertThat(options.getBeforeBreadcrumb()).isNotNull();

        SentryEvent event = new SentryEvent();
        SentryException ex = new SentryException();
        ex.setValue("Could not parse amount '1,23,456.78' for account 50100000000000");
        event.setExceptions(List.of(ex));

        SentryEvent sent = options.getBeforeSend().execute(event, null);

        assertThat(sent).isNotNull();
        assertThat(sent.getExceptions().get(0).getValue())
                .doesNotContain("1,23,456.78")
                .doesNotContain("50100000000000");
    }

    @Test
    void environmentAndReleaseArePassedThrough_soAnErrorPointsAtADeploy() {
        SentryOptions options = optionsFrom("https://key@example.invalid/1", "production", "abc123");

        assertThat(options.getEnvironment()).isEqualTo("production");
        assertThat(options.getRelease()).isEqualTo("abc123");
    }

    @Test
    void anUnsetReleaseIsLeftAloneRatherThanSetToEmptyString() {
        // An empty release would group every deploy's errors under "", which is worse than having
        // no release at all because it looks deliberate.
        SentryOptions options = optionsFrom("https://key@example.invalid/1", "production", "");

        assertThat(options.getRelease()).isNull();
    }
}
