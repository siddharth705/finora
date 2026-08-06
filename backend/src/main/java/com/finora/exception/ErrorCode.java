package com.finora.exception;

import org.springframework.http.HttpStatus;

/**
 * Structured error codes for API responses, per the v56 roadmap's "Global Error Codes" goal:
 * {@code {"code": "TXN_001", "message": "...", "details": {}}} instead of just an HTTP status
 * and a free-text message. Deliberately starts small — one enum, grown as real call sites adopt
 * it — rather than pre-populating a code for every conceivable failure up front; an unused code
 * taxonomy rots faster than a small one that's actually wired to real `throw` sites.
 *
 * Naming: <MODULE>_<sequence>, module prefix matches the feature package it originates from
 * (TXN = transactions, IMPORT = com.finora.imports, ACC = accounts, AUTH = auth/security, ...).
 * Prefixes are added as modules migrate to the feature-based package structure (see
 * docs/engineering/CODING_STANDARDS.md); this is not meant to be exhaustive on day one.
 *
 * Existing call sites using {@code new ApiException(HttpStatus, message)} are unaffected — that
 * constructor still works and results in a null error code, same as before this was introduced.
 * Migrate a throw site to a code when it's actually useful for the frontend or support tooling
 * to branch on (see ApiException's class doc), not as a blanket rewrite.
 */
public enum ErrorCode {
    // Transactions
    TXN_DUPLICATE("TXN_001", HttpStatus.CONFLICT, "Duplicate transaction detected"),
    TXN_NOT_FOUND("TXN_002", HttpStatus.NOT_FOUND, "Transaction not found"),
    TXN_FORBIDDEN("TXN_003", HttpStatus.FORBIDDEN, "This transaction does not belong to you"),

    // Statement import (com.finora.imports)
    IMPORT_NO_HEADER_DETECTED("IMPORT_001", HttpStatus.UNPROCESSABLE_ENTITY, "Could not find a transaction table in this file"),
    IMPORT_ACCOUNT_REQUIRED("IMPORT_002", HttpStatus.BAD_REQUEST, "Choose an existing account or provide details for a new one"),
    IMPORT_ACCOUNT_NAME_REQUIRED("IMPORT_003", HttpStatus.BAD_REQUEST, "The new account needs a name"),
    IMPORT_ACCOUNT_FORBIDDEN("IMPORT_004", HttpStatus.FORBIDDEN, "This account does not belong to you"),
    IMPORT_ACCOUNT_NOT_FOUND("IMPORT_005", HttpStatus.NOT_FOUND, "Account not found"),
    IMPORT_SYSTEM_BUSY("IMPORT_006", HttpStatus.SERVICE_UNAVAILABLE,
            "Finora is processing a lot of statement imports right now. Please try again in a moment."),
    // Deliberately separate from IMPORT_001 even though both mean "you got nothing". They fail at
    // different stages and need different follow-up: 001 means the document's layout defeated
    // table detection, 007 means the table WAS found and every row inside it was rejected. Folding
    // them into one code is what let a real statement import as a silent, confirmable no-op.
    IMPORT_NO_TRANSACTIONS_FOUND("IMPORT_007", HttpStatus.UNPROCESSABLE_ENTITY,
            "Found a transaction table in this file but could not read any transactions from it"),
    // Two codes, not one, and for the same reason IMPORT_001 and IMPORT_007 are separate: the
    // follow-up differs. 008 means "we have not asked you for the password yet" -- the UI opens a
    // prompt. 009 means "you gave us one and the document rejected it" -- the UI keeps the prompt
    // open with an inline error, because re-prompting from scratch reads as though the app lost
    // the file.
    //
    // PDFBox cannot tell these apart on its own: an encrypted PDF opened with NO password and one
    // opened with the WRONG password both throw InvalidPasswordException with the identical
    // message ("Cannot decrypt PDF, the password is incorrect"). The only thing that distinguishes
    // them is whether the request carried a password, which is why the distinction is drawn at the
    // call site rather than from the exception.
    IMPORT_PDF_PASSWORD_REQUIRED("IMPORT_008", HttpStatus.UNPROCESSABLE_ENTITY,
            "This statement is password protected. Enter the password your bank uses for it."),
    IMPORT_PDF_PASSWORD_INVALID("IMPORT_009", HttpStatus.UNPROCESSABLE_ENTITY,
            "That password did not open this statement. Check it and try again."),

    // Accounts
    ACCOUNT_NOT_FOUND("ACC_001", HttpStatus.NOT_FOUND, "Account not found"),

    // Auth / security
    //
    // These carry codes rather than only messages because the frontend has to TELL THEM APART, not
    // just print them. A 401 saying "wrong password" must leave the user on the login form with an
    // inline error; a 401 saying "your session ended" must clear stored credentials and explain the
    // sign-out. Branching on message text to decide that would break the moment anyone reworded a
    // string -- which is exactly what is planned.
    AUTH_INVALID_CREDENTIALS("AUTH_001", HttpStatus.UNAUTHORIZED, "Invalid credentials"),
    AUTH_TOKEN_EXPIRED("AUTH_002", HttpStatus.UNAUTHORIZED, "Session expired, please sign in again"),
    AUTH_FORBIDDEN("AUTH_003", HttpStatus.FORBIDDEN, "You don't have permission to do that"),
    // Deliberately distinct from AUTH_002 even though both end in "sign in again". A refresh token
    // presented twice is treated as evidence of theft (RefreshTokenService.rotate()) and revokes
    // EVERY session for that user, not just this one. Folding it into "session expired" would hide
    // a security event behind routine copy -- the user should know their other devices were signed
    // out, and support should be able to tell the two apart in logs.
    AUTH_SESSION_REVOKED("AUTH_004", HttpStatus.UNAUTHORIZED,
            "For your security, all sessions were signed out. Please sign in again."),

    // Separate from AUTH_002 for the same reason AUTH_004 is: all three end the session, but a user
    // deciding whether something is wrong needs to know WHY. "Signed out after a period of
    // inactivity" is reassuring and self-explanatory; "session expired" for a session the user was
    // actively using reads like a fault. Support needs to tell them apart in logs too -- a spike in
    // AUTH_005 means the idle window is too aggressive for how people actually work, which is
    // invisible if it is bucketed with ordinary expiry.
    AUTH_SESSION_IDLE("AUTH_005", HttpStatus.UNAUTHORIZED,
            "Signed out after a period of inactivity. Please sign in again."),
    AUTH_SESSION_MAX_AGE("AUTH_006", HttpStatus.UNAUTHORIZED,
            "Your session reached its maximum length. Please sign in again."),

    // Generic fallbacks — used by GlobalExceptionHandler when no more specific code applies
    VALIDATION_ERROR("VAL_001", HttpStatus.BAD_REQUEST, "Validation failed"),
    NOT_FOUND("GEN_001", HttpStatus.NOT_FOUND, "No such endpoint"),
    INTERNAL_ERROR("GEN_002", HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");

    private final String code;
    private final HttpStatus defaultStatus;
    private final String defaultMessage;

    ErrorCode(String code, HttpStatus defaultStatus, String defaultMessage) {
        this.code = code;
        this.defaultStatus = defaultStatus;
        this.defaultMessage = defaultMessage;
    }

    public String code() { return code; }
    public HttpStatus defaultStatus() { return defaultStatus; }
    public String defaultMessage() { return defaultMessage; }
}
