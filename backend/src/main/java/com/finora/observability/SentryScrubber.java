package com.finora.observability;

import io.sentry.Breadcrumb;
import io.sentry.SentryEvent;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryId;
import io.sentry.protocol.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Removes everything that must never leave the building before an event reaches Sentry.
 *
 * <p>Ported from the rules {@code mobile/src/lib/monitoring.ts} established for the clients, with
 * the additions the backend needs. Read that file for the reasoning the clients share; what is
 * spelled out here is where the server has to go <em>further</em>.
 *
 * <h2>Why the server is the harder case</h2>
 *
 * <p>The clients leak through URLs and request bodies. The server leaks through all of that
 * <em>plus the exception message itself</em>, which is the part with no client equivalent. This
 * backend parses bank statements, and the messages its parsers produce quote the input that failed:
 * an amount, a narration, a merchant name, an account number. A stack trace is safe -- it names
 * classes, methods and line numbers. The message attached to it very often is not.
 *
 * <p>This repository has already had real customer data reach a source comment (see
 * {@code scripts/check-fixture-hygiene.sh}). A crash reporter is a second route to the same
 * failure, except the data leaves the building rather than sitting in git.
 *
 * <h2>Allowlist, never denylist</h2>
 *
 * <p>Structures are rebuilt from scratch rather than having fields deleted from them. Deleting
 * known-bad keys means a field added by a future SDK version ships by default and nobody notices;
 * rebuilding means a new field is absent until someone deliberately adds it. The same reasoning as
 * the mobile breadcrumb scrubber, and it matters more here because the server sees every user.
 *
 * <h2>Everything here is static, pure and tested</h2>
 *
 * <p>Scrubbing that silently stops working looks exactly like scrubbing that works. It cannot be
 * untested logic buried in a config lambda -- see {@code SentryScrubberTest}, which asserts against
 * realistic Finora payloads rather than generic strings.
 */
public final class SentryScrubber {

    private SentryScrubber() {}

    private static final Pattern UUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                    Pattern.CASE_INSENSITIVE);

    /** Four or more digits: account numbers, card fragments, reference numbers, and amounts once
     *  separators are stripped. Three or fewer stays, so "row 42" and "HTTP 500" survive. */
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{4,}");

    /** Quoted runs. Parser errors quote the input that failed -- this is the single highest-risk
     *  pattern in a backend that reads statements. */
    private static final Pattern SINGLE_QUOTED = Pattern.compile("'[^']{0,200}'");
    private static final Pattern DOUBLE_QUOTED = Pattern.compile("\"[^\"]{0,200}\"");

    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** Indian mobile numbers, with or without country code -- the shape the PII guard already
     *  screens for in fixtures. */
    private static final Pattern PHONE = Pattern.compile("(\\+?91[-. ]?)?[6-9]\\d{9}");

    /**
     * Replaces identifiers in a path so error grouping still works but the specific account,
     * transaction or statement is not identifiable. Same two rules as the clients.
     */
    public static String redactPath(String path) {
        if (path == null) return null;
        String out = UUID.matcher(path).replaceAll("{id}");
        return LONG_DIGITS.matcher(out).replaceAll("{n}");
    }

    /**
     * Drops the query string entirely and redacts identifiers from what is left.
     *
     * <p>The query string carries the ledger's free-text search term -- whatever the user typed,
     * which on this product is a merchant, a landlord, or their own name. Dropped wholesale rather
     * than filtered per-parameter, because a filter has to be right every time and dropping only
     * has to be right once.
     */
    public static String scrubUrl(String raw) {
        if (raw == null) return null;
        int q = raw.indexOf('?');
        return redactPath(q >= 0 ? raw.substring(0, q) : raw);
    }

    /**
     * Redacts an exception or log message.
     *
     * <p>Deliberately aggressive, and the trade is stated plainly: redacting quoted runs costs some
     * diagnostic detail, because plenty of quoted content is a field name or an enum constant
     * rather than customer data. That cost is accepted because the alternative is deciding, per
     * message, whether a quoted value came from a statement -- which is exactly the "filter has to
     * be right every time" position this codebase avoids.
     *
     * <p>What survives is what actually identifies the fault: the exception type, the class, the
     * method, and the line. Those are carried elsewhere on the event and are not touched.
     */
    public static String redactMessage(String message) {
        if (message == null) return null;
        String out = EMAIL.matcher(message).replaceAll("{email}");
        out = PHONE.matcher(out).replaceAll("{phone}");
        out = UUID.matcher(out).replaceAll("{id}");
        out = SINGLE_QUOTED.matcher(out).replaceAll("'{redacted}'");
        out = DOUBLE_QUOTED.matcher(out).replaceAll("\"{redacted}\"");
        return LONG_DIGITS.matcher(out).replaceAll("{n}");
    }

    /**
     * Returning null drops the breadcrumb entirely.
     *
     * <p>Sentry's Spring integration turns log statements into breadcrumbs. What this application
     * logs while handling a statement includes narrations and amounts -- {@code ImportService} and
     * the parsers log liberally by design, because that is how import problems get diagnosed
     * locally. Those are dropped wholesale rather than redacted: a log breadcrumb's value is low
     * and its risk is high, which is the opposite of the trade worth making.
     *
     * <p>HTTP breadcrumbs are kept but rebuilt: method, status and a scrubbed URL are what make
     * them useful; everything else is what makes them dangerous.
     */
    public static Breadcrumb scrubBreadcrumb(Breadcrumb breadcrumb) {
        if (breadcrumb == null) return null;

        String category = breadcrumb.getCategory();
        if ("console".equals(category) || "log".equals(category) || "logging".equals(category)) {
            return null;
        }

        Breadcrumb rebuilt = new Breadcrumb(breadcrumb.getTimestamp());
        rebuilt.setCategory(category);
        rebuilt.setType(breadcrumb.getType());
        rebuilt.setLevel(breadcrumb.getLevel());
        rebuilt.setMessage(redactMessage(breadcrumb.getMessage()));

        if ("http".equals(category)) {
            copyIfPresent(breadcrumb, rebuilt, "method");
            copyIfPresent(breadcrumb, rebuilt, "status_code");
            Object url = breadcrumb.getData("url");
            if (url instanceof String s) rebuilt.setData("url", scrubUrl(s));
        }
        return rebuilt;
    }

    private static void copyIfPresent(Breadcrumb from, Breadcrumb to, String key) {
        Object value = from.getData(key);
        if (value != null) to.setData(key, value);
    }

    /**
     * The single entry point wired into {@code SentryOptions.setBeforeSend}.
     *
     * <p>Order matters only in that every branch below must run: an event carries the same data in
     * several places, and scrubbing the exception message while leaving the request body would
     * simply move the leak.
     */
    public static SentryEvent scrubEvent(SentryEvent event) {
        if (event == null) return null;

        // Request: rebuilt from scratch. The original carries headers (Authorization: Bearer ...),
        // cookies, the request body (a registration body holds email, phone and a plaintext
        // password), the query string and the server environment.
        Request original = event.getRequest();
        if (original != null) {
            Request safe = new Request();
            safe.setMethod(original.getMethod());
            safe.setUrl(scrubUrl(original.getUrl()));
            event.setRequest(safe);
        }

        // Identity. sendDefaultPii=false already prevents most of this; setting it to null as well
        // means a future default flip cannot quietly reintroduce it.
        event.setUser(null);
        event.setServerName(null);

        // Exception messages -- the backend-specific risk this whole class exists for.
        if (event.getExceptions() != null) {
            for (SentryException ex : event.getExceptions()) {
                ex.setValue(redactMessage(ex.getValue()));
            }
        }

        Message message = event.getMessage();
        if (message != null) {
            message.setMessage(redactMessage(message.getMessage()));
            message.setFormatted(redactMessage(message.getFormatted()));
            if (message.getParams() != null) {
                List<String> params = new ArrayList<>();
                for (String p : message.getParams()) params.add(redactMessage(p));
                message.setParams(params);
            }
        }

        // Extras are free-form and set by whatever called captureException. Cleared rather than
        // inspected: nothing in this codebase relies on them today, so an empty map is lossless,
        // and leaving the channel open invites a future caller to attach a statement row to it.
        //
        // Null-checked because getExtras() returns null on an event that never had any, which is
        // the common case for an uncaught exception. An unguarded clear() here threw an NPE inside
        // beforeSend -- i.e. the scrubber crashed while handling a crash, on the majority of real
        // events. Every accessor below and above is guarded for the same reason: this code runs in
        // the error path, where throwing is the one thing it must never do.
        if (event.getExtras() != null) {
            event.getExtras().clear();
        }

        if (event.getBreadcrumbs() != null) {
            List<Breadcrumb> safe = new ArrayList<>();
            for (Breadcrumb b : event.getBreadcrumbs()) {
                Breadcrumb scrubbed = scrubBreadcrumb(b);
                if (scrubbed != null) safe.add(scrubbed);
            }
            event.setBreadcrumbs(safe);
        }

        return event;
    }

    /** Kept so a caller can assert an event id round-trips unchanged; the id is generated by the
     *  SDK and carries no customer data. */
    public static SentryId idOf(SentryEvent event) {
        return event.getEventId();
    }
}
