package com.finora.support;

import com.finora.entity.ClientPlatform;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Which client is calling, and which build of it.
 *
 * <p>Support tickets and feedback both record where they came from, and until this existed nothing
 * in the codebase could answer that: there was no header contract, no user-agent parsing, nothing.
 * The columns would have been permanently null.
 *
 * <p>Reads the current request the same way {@link com.finora.security.CurrentUser} reads the
 * current principal — from the thread-bound context rather than by being passed down through every
 * signature. That keeps it out of service method parameters, which is what stops it becoming
 * something a caller can spoof by passing whatever it likes.
 *
 * <h2>Both headers are advisory, and that is a deliberate limit</h2>
 *
 * <p>A client asserts its own platform and version; neither is verified and neither could be. This
 * is fine for what the fields are for — aggregation and support context — and would not be fine
 * for anything security-bearing. Nothing may authorise on these values.
 *
 * <h2>Absent or unrecognised means {@link ClientPlatform#WEB}</h2>
 *
 * <p>So {@code WEB} really means "web, or a client that did not say". That is the honest reading
 * and it matters when interpreting the counts: a spike in {@code WEB} could be a real spike, or a
 * mobile release that shipped without the header. The alternative — rejecting a request over a
 * cosmetic header — would turn a typo in a client into an outage, which is a far worse trade for a
 * field used to group support requests.
 */
@Component
public class ClientIdentity {

    /** The client's own claim about which app it is. */
    public static final String PLATFORM_HEADER = "X-Client-Platform";

    /** The client's own claim about which build it is. */
    public static final String VERSION_HEADER = "X-App-Version";

    /**
     * Matches {@code app_version VARCHAR(32)} in V145 and V148.
     *
     * <p>A longer value is discarded rather than truncated. Truncation would store something that
     * looks like a version and identifies no build — worse than null, because null is visibly
     * absent while a clipped commit SHA is quietly wrong. Note the web client deliberately sends a
     * short commit SHA for this reason: its {@code __APP_RELEASE__} is the full 40-character hash
     * and would not fit.
     */
    static final int MAX_VERSION_LENGTH = 32;

    /** Never null: an unrecognised or missing platform reads as {@link ClientPlatform#WEB}. */
    public ClientPlatform platform() {
        String raw = header(PLATFORM_HEADER);
        if (raw == null) {
            return ClientPlatform.WEB;
        }
        try {
            return ClientPlatform.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            // A client sending a platform this build does not know about. Not an error worth
            // failing a support request over -- see this class's own doc.
            return ClientPlatform.WEB;
        }
    }

    /** The client's build identifier, or null when absent, blank, or implausibly long. */
    public String appVersion() {
        String raw = header(VERSION_HEADER);
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_VERSION_LENGTH) {
            return null;
        }
        return trimmed;
    }

    /**
     * The header value, or null.
     *
     * <p>Returns null rather than throwing when there is no bound request. This bean is a
     * singleton and nothing stops a background worker or a scheduled sweep from reaching it; those
     * threads have no request, and the correct answer there is "unknown", not an exception thrown
     * from inside a job that has nothing to do with HTTP.
     */
    private String header(String name) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return null;
        }
        HttpServletRequest request = servletAttributes.getRequest();
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? null : value;
    }
}
