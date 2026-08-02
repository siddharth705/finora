package com.finora.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserAgentParserTest {

    private static final String CHROME_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String EDGE_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0";
    private static final String SAFARI_MAC =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15";
    private static final String IPHONE_SAFARI =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
    private static final String ANDROID_CHROME =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    @Test
    void browser_identifiesEdgeBeforeChrome_sinceEdgesOwnUaAlsoContainsChrome() {
        assertThat(UserAgentParser.browser(EDGE_WINDOWS)).isEqualTo("Edge");
    }

    @Test
    void browser_identifiesChrome() {
        assertThat(UserAgentParser.browser(CHROME_WINDOWS)).isEqualTo("Chrome");
    }

    @Test
    void browser_identifiesSafariBeforeGenericFallback_onMac() {
        assertThat(UserAgentParser.browser(SAFARI_MAC)).isEqualTo("Safari");
    }

    @Test
    void browser_returnsUnknown_forNullOrBlank() {
        assertThat(UserAgentParser.browser(null)).isEqualTo("Unknown");
        assertThat(UserAgentParser.browser("  ")).isEqualTo("Unknown");
    }

    @Test
    void device_identifiesWindows() {
        assertThat(UserAgentParser.device(CHROME_WINDOWS)).isEqualTo("Windows");
    }

    @Test
    void device_identifiesMac() {
        assertThat(UserAgentParser.device(SAFARI_MAC)).isEqualTo("Mac");
    }

    @Test
    void device_identifiesIphone() {
        assertThat(UserAgentParser.device(IPHONE_SAFARI)).isEqualTo("iPhone");
    }

    @Test
    void device_identifiesAndroidPhoneDistinctFromTablet() {
        assertThat(UserAgentParser.device(ANDROID_CHROME)).isEqualTo("Android phone");
    }

    @Test
    void device_returnsUnknown_forNullOrBlank() {
        assertThat(UserAgentParser.device(null)).isEqualTo("Unknown");
        assertThat(UserAgentParser.device("")).isEqualTo("Unknown");
    }
}
