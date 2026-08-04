package com.finora.imports.storage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentAddressTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void hashMatchesTheStandardSha256_soItIsReDerivableByAnythingElse() {
        // The published SHA-256 of "abc". Pinned against an external constant rather than against
        // this implementation's own output, or the test would pass for any consistent-but-wrong
        // algorithm -- and a migration or a sweeper written in another language has to be able to
        // compute the same identity.
        assertThat(ContentAddress.hashOf(bytes("abc")))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(ContentAddress.hashOf(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void identicalContentProducesAnIdenticalAddress() {
        assertThat(ContentAddress.forContent(bytes("statement")))
                .isEqualTo(ContentAddress.forContent(bytes("statement")));
    }

    @Test
    void keyShardsOnTheHashPrefix_andCarriesNoUserSuppliedInput() {
        ContentAddress address = ContentAddress.forContent(bytes("abc"));

        assertThat(address.key()).isEqualTo("statements/ba/78/" + address.hash() + ".bin");
        // No original filename or extension in the key: a renamed upload must not change where
        // identical content lands, which is exactly what content-addressing exists to prevent.
        assertThat(address.key()).endsWith(".bin");
    }

    @Test
    void identityAndKeyAreSeparateFields() {
        ContentAddress address = ContentAddress.forContent(bytes("statement"));

        // The hash is what the application knows a document by, forever. The key is a layout
        // decision that must stay changeable -- if the key WERE the identity, re-sharding the
        // bucket would mean rewriting how every row identifies its document.
        assertThat(address.hash()).doesNotContain("/");
        assertThat(address.key()).contains(address.hash());
        assertThat(address.key()).isNotEqualTo(address.hash());
    }

    @Test
    void rejectsAnEmptyIdentityOrKey() {
        assertThatThrownBy(() -> new ContentAddress("", "statements/x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContentAddress("abc", " ")).isInstanceOf(IllegalArgumentException.class);
    }
}
