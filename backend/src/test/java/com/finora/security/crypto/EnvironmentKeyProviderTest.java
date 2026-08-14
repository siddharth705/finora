package com.finora.security.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-007. The point of these tests is that misconfiguration fails AT STARTUP with a message naming
 * the problem — not hours later, on the first user's first integration connect, as a 500.
 */
class EnvironmentKeyProviderTest {

    private static String validKey() {
        byte[] raw = new byte[32];
        java.util.Arrays.fill(raw, (byte) 7);
        return Base64.getEncoder().encodeToString(raw);
    }

    private static CryptoProperties properties(String activeKeyId, Map<String, String> keys) {
        CryptoProperties props = new CryptoProperties();
        props.setActiveKeyId(activeKeyId);
        props.setKeys(new LinkedHashMap<>(keys));
        return props;
    }

    @Test
    void aValidConfigurationResolvesTheActiveKey() {
        EnvironmentKeyProvider provider = new EnvironmentKeyProvider(
                properties("v1", Map.of("v1", validKey())));

        assertThat(provider.activeKeyId()).isEqualTo("v1");
        assertThat(provider.activeKey()).isNotNull();
        assertThat(provider.activeKey().getAlgorithm()).isEqualTo("AES");
    }

    @Test
    @DisplayName("a 16-byte key is rejected rather than silently used as AES-128")
    void aKeyOfTheWrongLengthIsRejected() {
        // The failure mode this guards: 16 bytes is a perfectly valid AES key, so without an
        // explicit length check the application would start and encrypt everything at half the
        // intended strength, with nothing anywhere reporting a problem.
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new EnvironmentKeyProvider(properties("v1", Map.of("v1", shortKey))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes")
                .hasMessageContaining("openssl rand -base64 32");
    }

    @Test
    void aKeyThatIsNotBase64IsRejected() {
        assertThatThrownBy(() -> new EnvironmentKeyProvider(
                properties("v1", Map.of("v1", "this is not base64 !!!"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid base64");
    }

    @Test
    void anEmptyKeyIsRejected() {
        assertThatThrownBy(() -> new EnvironmentKeyProvider(properties("v1", Map.of("v1", "  "))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void anActiveKeyIdWithNoMatchingKeyIsRejected() {
        // The likeliest real rotation mistake: point active-key-id at v2 and forget to supply v2.
        assertThatThrownBy(() -> new EnvironmentKeyProvider(
                properties("v2", Map.of("v1", validKey()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("v2");
    }

    @Test
    void aMissingActiveKeyIdIsRejected() {
        assertThatThrownBy(() -> new EnvironmentKeyProvider(properties(null, Map.of("v1", validKey()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active-key-id");
    }

    @Test
    @DisplayName("a retired key stays resolvable so old ciphertext remains readable")
    void anInactiveKeyIsStillResolvableById() {
        EnvironmentKeyProvider provider = new EnvironmentKeyProvider(
                properties("v2", Map.of("v1", validKey(), "v2", validKey())));

        assertThat(provider.activeKeyId()).isEqualTo("v2");
        assertThatCode(() -> provider.keyById("v1"))
                .as("v1 is no longer active but data written under it must still decrypt")
                .doesNotThrowAnyException();
    }

    @Test
    void anUnknownKeyIdFailsLoudlyRatherThanReturningNothing() {
        EnvironmentKeyProvider provider = new EnvironmentKeyProvider(
                properties("v1", Map.of("v1", validKey())));

        // Deliberately a hard failure: an unresolvable id means unreadable data, which is an
        // operational emergency, not a condition to paper over with a null.
        assertThatThrownBy(() -> provider.keyById("v9"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("v9");
    }
}
