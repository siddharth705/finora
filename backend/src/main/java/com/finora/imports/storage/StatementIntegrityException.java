package com.finora.imports.storage;

/**
 * Bytes came back from storage, but they are not the bytes that address names.
 *
 * <p>Separate from a plain {@link StatementStorageException} because the two demand opposite
 * responses. "Missing or unreadable" is an availability problem: the object may come back when a
 * provider recovers, and the row is still correct. This is a correctness problem: something is
 * present and readable and <em>wrong</em>, so retrying reads the same wrong bytes forever. One is
 * waited out; the other is investigated, and until it is, that statement must not be parsed into
 * anyone's financial records.
 *
 * <p>Collapsing them into one type would put a corruption event in the same log bucket as a
 * transient outage, which is where it would stay unnoticed.
 */
public class StatementIntegrityException extends StatementStorageException {

    public StatementIntegrityException(String message) {
        super(message);
    }
}
