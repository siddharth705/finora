package com.finora.observability;

import io.sentry.Sentry;
import io.sentry.SentryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Backend error monitoring.
 *
 * <p>Disabled unless {@code SENTRY_DSN} is set, mirroring how every other external integration in
 * this codebase behaves ({@code RESEND_API_KEY}, {@code GOOGLE_APPLICATION_CREDENTIALS},
 * {@code EXPO_PUBLIC_SENTRY_DSN}): absent config degrades to a no-op rather than crashing or
 * half-working. That also keeps local development and the 1200-test suite free of network calls
 * with no extra setup.
 *
 * <h2>What this closes</h2>
 *
 * <p>Frontend, admin portal and mobile have reported runtime failures since their own
 * {@code monitoring.ts} landed. The backend -- which runs the import pipeline, the async workers in
 * {@code BackgroundWorkConfig}, the merchant-learning queue and every financial mutation -- had
 * none. A worker that died mid-batch was visible only if someone went looking in application logs.
 *
 * <h2>Every default here is more restrictive than Sentry's</h2>
 *
 * <p>The settings below are stated explicitly rather than inherited, because these are precisely
 * the ones whose accidental flip would be most damaging, and an inherited default is one nobody
 * reviews. {@link SentryScrubber} then removes what configuration alone cannot.
 *
 * <p><b>This is defence in depth, not belt-and-braces.</b> {@code sendDefaultPii=false} stops
 * Sentry attaching identity itself; the scrubber stops the application's own exception messages
 * carrying statement contents. Neither substitutes for the other.
 */
@Configuration
public class MonitoringConfig {

    private static final Logger log = LoggerFactory.getLogger(MonitoringConfig.class);

    private final String dsn;
    private final String environment;
    private final String release;

    public MonitoringConfig(@Value("${sentry.dsn:}") String dsn,
                            @Value("${sentry.environment:development}") String environment,
                            @Value("${sentry.release:}") String release) {
        this.dsn = dsn;
        this.environment = environment;
        this.release = release;
    }

    /**
     * The starter applies every {@code Sentry.OptionsConfiguration} bean it finds, so this is the
     * supported hook rather than calling {@code Sentry.init} ourselves and fighting the
     * auto-configuration.
     */
    @Bean
    public Sentry.OptionsConfiguration<SentryOptions> finoraSentryOptions() {
        return options -> {
            if (dsn == null || dsn.isBlank()) {
                // Explicitly cleared: the starter would otherwise happily initialise with whatever
                // it found in the environment, and "monitoring is off" should be a decision, not an
                // accident of configuration precedence.
                options.setDsn(null);
                options.setEnabled(false);
                log.info("No SENTRY_DSN configured -- backend error monitoring is off "
                        + "(set SENTRY_DSN to enable)");
                return;
            }

            options.setEnvironment(environment);
            if (release != null && !release.isBlank()) {
                // Tied to the commit SHA by the deploy, so an error points at a deploy rather than
                // at "production".
                options.setRelease(release);
            }

            // Never attach IP address, cookies, headers or user identity. Sentry's own default is
            // already false; stated here because this is the flag whose flip would be worst.
            options.setSendDefaultPii(false);

            // Crash reporting only. Performance spans are keyed by URL, which would reintroduce the
            // account and transaction identifiers SentryScrubber strips -- and there is no
            // performance question being asked yet that would justify that trade. Operational
            // metrics (roadmap phase 2) are the right answer to "is it slow", not tracing.
            options.setTracesSampleRate(0.0);

            // The request body is the highest-value payload on this server and must never be
            // attached: a registration body holds an email, a phone number and a plaintext
            // password, and an import body is a bank statement.
            options.setMaxRequestBodySize(SentryOptions.RequestSize.NONE);

            // Stack traces for handled events are useful and carry no customer data -- they name
            // classes, methods and lines. Local variable capture is a different matter and stays
            // off: locals in the import pipeline hold parsed statement rows.
            options.setAttachStacktrace(true);
            options.setAttachThreads(false);

            options.setBeforeSend((event, hint) -> SentryScrubber.scrubEvent(event));
            options.setBeforeBreadcrumb((breadcrumb, hint) -> SentryScrubber.scrubBreadcrumb(breadcrumb));

            log.info("Backend error monitoring enabled (environment={}, release={})",
                    environment, release == null || release.isBlank() ? "unset" : release);
        };
    }
}
