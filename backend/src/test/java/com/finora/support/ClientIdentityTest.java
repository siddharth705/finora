package com.finora.support;

import com.finora.entity.ClientPlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The header contract, including every way a client can get it wrong.
 *
 * <p>These values reach two database columns that exist to be counted, so the cases that matter
 * most are the malformed ones: what a typo, a missing header, an over-long version or a background
 * thread with no request at all resolves to.
 */
class ClientIdentityTest {

    private final ClientIdentity identity = new ClientIdentity();

    private void request(String platform, String version) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (platform != null) {
            request.addHeader(ClientIdentity.PLATFORM_HEADER, platform);
        }
        if (version != null) {
            request.addHeader(ClientIdentity.VERSION_HEADER, version);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void readsEachKnownPlatform() {
        for (ClientPlatform expected : ClientPlatform.values()) {
            request(expected.name(), null);
            assertThat(identity.platform()).isEqualTo(expected);
        }
    }

    @Test
    void acceptsLowercaseAndSurroundingWhitespace() {
        request("  mobile_android  ", null);
        assertThat(identity.platform()).isEqualTo(ClientPlatform.MOBILE_ANDROID);
    }

    /**
     * A platform this build has never heard of must not fail the request. The field groups support
     * requests; it authorises nothing. Rejecting here would turn a client-side typo into an outage.
     */
    @Test
    void anUnrecognisedPlatformFallsBackToWebRatherThanThrowing() {
        request("MOBILE_WINDOWS_PHONE", null);
        assertThat(identity.platform()).isEqualTo(ClientPlatform.WEB);
    }

    @Test
    void aMissingPlatformReadsAsWeb() {
        request(null, null);
        assertThat(identity.platform()).isEqualTo(ClientPlatform.WEB);
    }

    @Test
    void readsTheAppVersion() {
        request("WEB", "a1b2c3d4e5f6");
        assertThat(identity.appVersion()).isEqualTo("a1b2c3d4e5f6");
    }

    @Test
    void aMissingOrBlankVersionIsNull() {
        request("WEB", null);
        assertThat(identity.appVersion()).isNull();

        request("WEB", "   ");
        assertThat(identity.appVersion()).isNull();
    }

    /**
     * The column is {@code VARCHAR(32)}. Discarding beats truncating: a clipped commit SHA looks
     * like a build identifier and identifies nothing, whereas null is visibly absent. Without this
     * the insert would fail outright — the web client's own {@code __APP_RELEASE__} is a full
     * 40-character hash, which is why it sends a short SHA.
     */
    @Test
    void anOverLongVersionIsDiscardedNotTruncated() {
        request("WEB", "x".repeat(ClientIdentity.MAX_VERSION_LENGTH + 1));
        assertThat(identity.appVersion()).isNull();

        request("WEB", "x".repeat(ClientIdentity.MAX_VERSION_LENGTH));
        assertThat(identity.appVersion()).hasSize(ClientIdentity.MAX_VERSION_LENGTH);
    }

    /**
     * This is a singleton bean and nothing stops a scheduled sweep or a worker thread from reaching
     * it. Those threads have no bound request, and the right answer there is "unknown" rather than
     * an exception thrown from inside a job that has nothing to do with HTTP.
     */
    @Test
    void resolvesOffAnyThreadWithNoBoundRequest() {
        RequestContextHolder.resetRequestAttributes();

        assertThat(identity.platform()).isEqualTo(ClientPlatform.WEB);
        assertThat(identity.appVersion()).isNull();
    }
}
