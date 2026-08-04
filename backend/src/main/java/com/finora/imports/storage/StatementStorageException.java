package com.finora.imports.storage;

/**
 * A statement's bytes could not be stored or read back.
 *
 * Unchecked on purpose. Every call site reaches storage from a row that asserts the object exists,
 * so there is no sensible local recovery -- the honest responses are "fail this request" or "fail
 * this migration row and report it", both of which propagate. A checked exception would push
 * try/catch into the import pipeline for an outcome none of it can actually handle.
 *
 * Deliberately NOT an ApiException: storage failing is infrastructure, not a user error, and it
 * should surface as a 500 with a correlation id rather than as advice the user cannot act on.
 */
public class StatementStorageException extends RuntimeException {

    public StatementStorageException(String message) {
        super(message);
    }

    public StatementStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
