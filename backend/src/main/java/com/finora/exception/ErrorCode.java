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

    // Accounts
    ACCOUNT_NOT_FOUND("ACC_001", HttpStatus.NOT_FOUND, "Account not found"),

    // Auth / security
    AUTH_INVALID_CREDENTIALS("AUTH_001", HttpStatus.UNAUTHORIZED, "Invalid credentials"),
    AUTH_TOKEN_EXPIRED("AUTH_002", HttpStatus.UNAUTHORIZED, "Session expired, please sign in again"),
    AUTH_FORBIDDEN("AUTH_003", HttpStatus.FORBIDDEN, "You don't have permission to do that"),

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
