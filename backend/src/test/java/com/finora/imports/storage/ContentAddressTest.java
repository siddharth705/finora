package com.finora.imports.storage;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

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
    void requireMatchesAcceptsContentThatHashesToTheExpectedValue() {
        ContentAddress address = ContentAddress.forContent(bytes("statement bytes"));

        ContentAddress.requireMatches(bytes("statement bytes"), address.hash(), "test");
    }

    @Test
    void requireMatchesRejectsContentThatDoesNot_andNamesBothDigests() {
        // Both hashes belong in the message: equal-length-but-different says damaged object,
        // wholly-different says wrong object, and an operator cannot tell those apart without
        // seeing what was expected against what arrived.
        String expected = ContentAddress.forContent(bytes("the real statement")).hash();
        String actualHash = ContentAddress.hashOf(bytes("something else"));

        assertThatThrownBy(() -> ContentAddress.requireMatches(bytes("something else"), expected, "statement X"))
                .isInstanceOf(StatementIntegrityException.class)
                .hasMessageContaining("statement X")
                .hasMessageContaining(expected)
                .hasMessageContaining(actualHash);
    }

    @Test
    void requireMatchesDetectsASingleFlippedByte() {
        // The realistic corruption, and the one a length check or a null check would miss entirely.
        byte[] original = bytes("%PDF-1.6 a statement of some length");
        byte[] rotted = original.clone();
        rotted[10] ^= 0x01;

        assertThatThrownBy(() -> ContentAddress.requireMatches(rotted, ContentAddress.hashOf(original), "rotted"))
                .isInstanceOf(StatementIntegrityException.class);
    }

    @Test
    void rejectsAnEmptyIdentityOrKey() {
        assertThatThrownBy(() -> new ContentAddress("", "statements/x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContentAddress("abc", " ")).isInstanceOf(IllegalArgumentException.class);
    }

    // --- copyAndAddress (BH-018) -------------------------------------------------------------

    @Test
    void copyAndAddressProducesTheSameAddressAsForContent_forTheSameBytes() throws IOException {
        byte[] content = bytes("a statement, streamed instead of held whole");
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ContentAddress streamed = ContentAddress.copyAndAddress(new ByteArrayInputStream(content), out);

        assertThat(streamed).isEqualTo(ContentAddress.forContent(content));
        assertThat(out.toByteArray()).isEqualTo(content);
    }

    @Test
    void copyAndAddressWritesIncrementallyAsContentArrives_neverBuffersTheWholeThingFirst() throws IOException {
        // The actual property BH-018 is about: not that the numbers come out right (the test
        // above already covers that), but that getting them right never requires the whole
        // upload resident in memory as one array. The write side is what actually distinguishes
        // this from a naive "fix" -- InputStream.readAllBytes() *also* reads in bounded chunks
        // internally (so tracking read() request sizes proves nothing; that mutation was tried
        // and the test below did not catch it), but it always hands the OutputStream one single
        // write of the complete array at the end. Real streaming writes the same small chunks it
        // read, as it reads them. Tracking write() sizes is what actually tells the two apart.
        byte[] large = new byte[5 * 1024 * 1024]; // 5 MB, larger than any single JDK copy buffer
        new Random(42).nextBytes(large);

        var maxSingleWriteSize = new int[]{0};
        var writeCount = new int[]{0};
        var out = new ByteArrayOutputStream() {
            @Override
            public synchronized void write(byte[] b, int off, int len) {
                maxSingleWriteSize[0] = Math.max(maxSingleWriteSize[0], len);
                writeCount[0]++;
                super.write(b, off, len);
            }
        };

        ContentAddress streamed = ContentAddress.copyAndAddress(new ByteArrayInputStream(large), out);

        assertThat(streamed).isEqualTo(ContentAddress.forContent(large));
        assertThat(out.toByteArray()).isEqualTo(large);
        // JDK InputStream.transferTo's own copy buffer is 8 KB (DEFAULT_BUFFER_SIZE) -- generous
        // headroom at 1 MB so this pins the property ("bounded, not file-sized"), not the JDK's
        // private implementation constant, which this class has no business depending on exactly.
        assertThat(maxSingleWriteSize[0])
                .as("a single write() must never carry anywhere near the whole 5 MB content")
                .isLessThan(1024 * 1024);
        assertThat(writeCount[0])
                .as("many small writes, not one write of everything")
                .isGreaterThan(1);
    }
}
