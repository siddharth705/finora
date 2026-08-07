package com.finora.observability;

import io.sentry.Breadcrumb;
import io.sentry.SentryEvent;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.User;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scrubber is the only thing standing between a stack trace and a customer's bank statement
 * leaving the building, so it is tested against realistic Finora payloads rather than generic
 * strings.
 *
 * <p>Scrubbing that silently stops working looks exactly like scrubbing that works: no error, no
 * failing test, events still flowing. That is why the assertions below are phrased as "this exact
 * value must not appear anywhere in the serialised event" rather than "the field was rewritten" --
 * a rewrite that moved the data somewhere else would pass the weaker check.
 */
class SentryScrubberTest {

    // ---------------------------------------------------------------- message redaction

    @Test
    void redactsTheQuotedInputAParserQuotesBack() {
        // The single highest-risk message shape on this backend: a parse failure that quotes the
        // statement content that failed.
        String message = "Could not parse amount '1,23,456.78' in narration 'UPI/ACME STORES/paid'";

        String scrubbed = SentryScrubber.redactMessage(message);

        assertThat(scrubbed).doesNotContain("1,23,456.78").doesNotContain("ACME STORES");
        assertThat(scrubbed).contains("Could not parse amount").contains("in narration");
    }

    @Test
    void redactsAccountNumbersAndReferenceNumbers() {
        String scrubbed = SentryScrubber.redactMessage(
                "Statement row 42 for account 50100000000000 ref 9000000000 failed validation");

        assertThat(scrubbed).doesNotContain("50100000000000").doesNotContain("9000000000");
        // Three digits or fewer survive, so "row 42" and an HTTP status stay readable.
        assertThat(scrubbed).contains("row 42");
    }

    @Test
    void redactsEmailAndPhone() {
        String scrubbed = SentryScrubber.redactMessage(
                "Registration failed for test.customer@example.com / +919999999999");

        assertThat(scrubbed).doesNotContain("test.customer@example.com").doesNotContain("9999999999");
    }

    @Test
    void redactsUuidsSoOneUsersErrorsStillGroupTogether() {
        String scrubbed = SentryScrubber.redactMessage(
                "Import 3f2504e0-4f89-11d3-9a0c-0305e82c3301 failed");

        assertThat(scrubbed).doesNotContain("3f2504e0").contains("{id}");
    }

    @Test
    void keepsEnoughToIdentifyTheFault() {
        // The trade being made: detail is lost, but the sentence still says what broke and where.
        // If this ever reduces to "{redacted}" the scrubber has become useless rather than safe.
        String scrubbed = SentryScrubber.redactMessage(
                "StatementValidator rejected balance chain at row 7");

        assertThat(scrubbed).contains("StatementValidator").contains("balance chain").contains("row 7");
    }

    @Test
    void aBareEventScrubsWithoutThrowing() {
        // Regression: getExtras() returns null on an event that never had any -- the common case
        // for an uncaught exception -- and an unguarded clear() threw an NPE inside beforeSend.
        // The scrubber crashed while handling a crash, on the majority of real events. The fixture
        // in this class always sets an extra, so nothing here caught it; MonitoringConfigTest did.
        //
        // This asserts the shape that actually reaches production: an event with nothing populated.
        SentryEvent bare = new SentryEvent();

        SentryEvent scrubbed = SentryScrubber.scrubEvent(bare);

        assertThat(scrubbed).isNotNull();
    }

    @Test
    void anEventWithOnlyAnExceptionScrubsWithoutThrowing() {
        SentryEvent event = new SentryEvent();
        SentryException ex = new SentryException();
        ex.setType("IllegalStateException");
        ex.setValue("balance chain broken at row 7");
        event.setExceptions(List.of(ex));

        SentryEvent scrubbed = SentryScrubber.scrubEvent(event);

        assertThat(scrubbed.getExceptions().get(0).getValue()).contains("row 7");
    }

    @Test
    void nullsPassThroughRatherThanThrowing() {
        assertThat(SentryScrubber.redactMessage(null)).isNull();
        assertThat(SentryScrubber.scrubUrl(null)).isNull();
        assertThat(SentryScrubber.redactPath(null)).isNull();
        assertThat(SentryScrubber.scrubBreadcrumb(null)).isNull();
        assertThat(SentryScrubber.scrubEvent(null)).isNull();
    }

    // ---------------------------------------------------------------- urls

    @Test
    void dropsTheQueryStringEntirely_itCarriesTheLedgerSearchTerm() {
        String scrubbed = SentryScrubber.scrubUrl(
                "https://api.finoratech.info/api/v1/transactions?q=landlord%20rent&page=1");

        assertThat(scrubbed).doesNotContain("landlord").doesNotContain("?");
        assertThat(scrubbed).isEqualTo("https://api.finoratech.info/api/v1/transactions");
    }

    @Test
    void redactsIdentifiersInThePathSoGroupingStillWorks() {
        String a = SentryScrubber.scrubUrl("/api/v1/statements/3f2504e0-4f89-11d3-9a0c-0305e82c3301");
        String b = SentryScrubber.scrubUrl("/api/v1/statements/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        // Two users hitting the same broken endpoint must produce one issue, not two.
        assertThat(a).isEqualTo(b).isEqualTo("/api/v1/statements/{id}");
    }

    // ---------------------------------------------------------------- breadcrumbs

    @Test
    void dropsLogBreadcrumbsWholesale() {
        // What this application logs while importing includes narrations and amounts, by design.
        for (String category : List.of("console", "log", "logging")) {
            Breadcrumb crumb = new Breadcrumb();
            crumb.setCategory(category);
            crumb.setMessage("Parsed 412 rows, first narration 'UPI/ACME STORES/paid'");

            assertThat(SentryScrubber.scrubBreadcrumb(crumb))
                    .as("%s breadcrumbs must be dropped, not filtered", category)
                    .isNull();
        }
    }

    @Test
    void rebuildsHttpBreadcrumbsFromScratch_soAFutureSdkFieldIsNotIncludedByDefault() {
        Breadcrumb crumb = new Breadcrumb();
        crumb.setCategory("http");
        crumb.setData("method", "POST");
        crumb.setData("status_code", 500);
        crumb.setData("url", "/api/v1/imports/9000000000?token=secret");
        crumb.setData("request_body", "{\"password\":\"hunter2\"}");
        crumb.setData("some_future_field", "whatever the SDK adds next");

        Breadcrumb scrubbed = SentryScrubber.scrubBreadcrumb(crumb);

        assertThat(scrubbed).isNotNull();
        assertThat(scrubbed.getData("method")).isEqualTo("POST");
        assertThat(scrubbed.getData("status_code")).isEqualTo(500);
        assertThat(scrubbed.getData("url")).isEqualTo("/api/v1/imports/{n}");
        assertThat(scrubbed.getData("request_body")).isNull();
        assertThat(scrubbed.getData("some_future_field"))
                .as("rebuilt from an allowlist, so unknown keys are absent by construction")
                .isNull();
    }

    // ---------------------------------------------------------------- whole event

    /** Everything a realistic Finora crash could carry, in one event. */
    private SentryEvent eventWithEverything() {
        SentryEvent event = new SentryEvent();

        Request request = new Request();
        request.setMethod("POST");
        request.setUrl("/api/v1/imports/3f2504e0-4f89-11d3-9a0c-0305e82c3301/confirm");
        request.setQueryString("q=landlord");
        request.setData("{\"password\":\"hunter2\",\"phone\":\"+919999999999\"}");
        request.setHeaders(Map.of("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.secret"));
        request.setCookies("session=abc123");
        event.setRequest(request);

        User user = new User();
        user.setEmail("test.customer@example.com");
        user.setIpAddress("203.0.113.7");
        event.setUser(user);
        event.setServerName("finora-prod-worker-1");

        SentryException ex = new SentryException();
        ex.setType("StatementParseException");
        ex.setValue("Could not parse amount '1,23,456.78' for account 50100000000000");
        event.setExceptions(List.of(ex));

        Message message = new Message();
        message.setMessage("Import failed for 'ACME STORES'");
        message.setFormatted("Import failed for 'ACME STORES'");
        event.setMessage(message);

        event.setExtra("statementRow", "01/04/2026 UPI/ACME STORES/paid 1,23,456.78 DR");

        Breadcrumb logCrumb = new Breadcrumb();
        logCrumb.setCategory("log");
        logCrumb.setMessage("row 1 narration 'UPI/ACME STORES/paid'");
        event.setBreadcrumbs(List.of(logCrumb));

        return event;
    }

    @Test
    void noSensitiveValueSurvivesAnywhereInTheEvent() {
        SentryEvent scrubbed = SentryScrubber.scrubEvent(eventWithEverything());

        // Asserted against the whole serialised event rather than field by field: a scrub that
        // merely relocated the data would pass a per-field check.
        String dump = dump(scrubbed);

        assertThat(dump)
                .doesNotContain("hunter2")
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9")
                .doesNotContain("test.customer@example.com")
                .doesNotContain("203.0.113.7")
                .doesNotContain("9999999999")
                .doesNotContain("50100000000000")
                .doesNotContain("1,23,456.78")
                .doesNotContain("ACME STORES")
                .doesNotContain("landlord")
                .doesNotContain("session=abc123")
                .doesNotContain("finora-prod-worker-1");
    }

    @Test
    void theEventIsStillWorthReceivingAfterScrubbing() {
        // A scrubber that emptied the event would pass every assertion above and be useless.
        SentryEvent scrubbed = SentryScrubber.scrubEvent(eventWithEverything());

        assertThat(scrubbed.getExceptions()).hasSize(1);
        assertThat(scrubbed.getExceptions().get(0).getType()).isEqualTo("StatementParseException");
        assertThat(scrubbed.getRequest().getMethod()).isEqualTo("POST");
        assertThat(scrubbed.getRequest().getUrl()).isEqualTo("/api/v1/imports/{id}/confirm");
    }

    @Test
    void requestIsRebuilt_soHeadersCookiesBodyAndEnvAreAbsentByConstruction() {
        SentryEvent scrubbed = SentryScrubber.scrubEvent(eventWithEverything());
        Request request = scrubbed.getRequest();

        assertThat(request.getHeaders()).isNull();
        assertThat(request.getCookies()).isNull();
        assertThat(request.getData()).isNull();
        assertThat(request.getQueryString()).isNull();
        assertThat(request.getEnvs()).isNull();
    }

    @Test
    void identityIsRemovedEvenThoughSendDefaultPiiIsAlreadyFalse() {
        SentryEvent scrubbed = SentryScrubber.scrubEvent(eventWithEverything());

        assertThat(scrubbed.getUser()).isNull();
        assertThat(scrubbed.getServerName()).isNull();
    }

    @Test
    void extrasAreCleared_soNoFutureCallerCanAttachAStatementRow() {
        SentryEvent scrubbed = SentryScrubber.scrubEvent(eventWithEverything());

        assertThat(scrubbed.getExtras()).isEmpty();
    }

    /** Serialises every field the assertions care about into one string. Deliberately hand-rolled
     *  rather than using Sentry's JSON serializer, so the test does not silently stop covering a
     *  field the serializer chooses to omit. */
    private String dump(SentryEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append(event.getServerName()).append('\n');
        sb.append(event.getUser()).append('\n');
        if (event.getUser() != null) {
            sb.append(event.getUser().getEmail()).append(event.getUser().getIpAddress()).append('\n');
        }
        Request r = event.getRequest();
        if (r != null) {
            sb.append(r.getMethod()).append(r.getUrl()).append(r.getQueryString())
              .append(r.getData()).append(r.getHeaders()).append(r.getCookies()).append(r.getEnvs());
        }
        sb.append('\n').append(event.getExtras()).append('\n');
        if (event.getExceptions() != null) {
            event.getExceptions().forEach(e -> sb.append(e.getType()).append(e.getValue()).append('\n'));
        }
        Message m = event.getMessage();
        if (m != null) sb.append(m.getMessage()).append(m.getFormatted()).append(m.getParams());
        sb.append('\n');
        if (event.getBreadcrumbs() != null) {
            event.getBreadcrumbs().forEach(b ->
                    sb.append(b.getCategory()).append(b.getMessage()).append(b.getData()).append('\n'));
        }
        return sb.toString();
    }
}
