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
    // Named to match DocumentClassification.SCANNED_OCR_REQUIRED, so one condition has one name
    // across the codebase rather than an analysis vocabulary and a product vocabulary that drift.
    //
    // The MESSAGE is held to a stricter standard than the identifier, and deliberately so: zero
    // extractable text proves no usable native text was acquired, and does not prove the file is a
    // bank statement, a scan rather than a photo or an export, or that recognition would succeed on
    // it. So the user is told only what was observed, and a test requires the words "scanned" and
    // "OCR" to be absent from what they read.
    IMPORT_SCANNED_OCR_REQUIRED("IMPORT_010", HttpStatus.UNPROCESSABLE_ENTITY,
            "This PDF has no text in it -- every page is an image"),

    IMPORT_PDF_PASSWORD_REQUIRED("IMPORT_008", HttpStatus.UNPROCESSABLE_ENTITY,
            "This statement is password protected. Enter the password your bank uses for it."),
    IMPORT_PDF_PASSWORD_INVALID("IMPORT_009", HttpStatus.UNPROCESSABLE_ENTITY,
            "That password did not open this statement. Check it and try again."),
    // A structurally broken PDF -- truncated by a failed download, corrupted in transit, or saved
    // by something that produced not-quite-valid output. Previously thrown as a codeless
    // ApiException (PdfTextExtractor.loadOrExplain's IOException branch), which meant
    // StatementAnalysisRecorder recorded failureCode = null for it -- indistinguishable from any
    // other codeless failure in the failure_code histogram, the customer-facing failures list, and
    // any future retry classification. The user-facing message is unchanged by adding this code;
    // the throw site's own message stays richer than this default (see that method's doc comment
    // for why a codeless response was actively wrong, not just imprecise).
    IMPORT_CORRUPT_PDF("IMPORT_011", HttpStatus.UNPROCESSABLE_ENTITY,
            "This PDF could not be read -- the file appears to be damaged or incomplete"),
    // Distinct from a genuinely expired/missing session (still a codeless ApiException, since
    // "upload again" really is the right instruction there) because the frontend has to TELL THEM
    // APART, not just print a message: reaching a completed job's "Review this import" action
    // after the same session was already reviewed and confirmed through the normal flow used to
    // surface the generic expired-session message ("please upload the statement again"), which is
    // actively wrong -- the import already succeeded, nothing needs re-uploading.
    IMPORT_SESSION_ALREADY_CONFIRMED("IMPORT_012", HttpStatus.BAD_REQUEST,
            "This import has already been reviewed and confirmed."),

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

    /**
     * How {@code ImportJobWorker}'s retry loop should treat an exception carrying this code --
     * Premium Import Reliability v1, §5 ("Retry classification and failure handling"). Lives on
     * {@code ErrorCode} because it is exactly the same kind of per-code metadata {@code
     * defaultStatus}/{@code defaultMessage} already are, scoped deliberately to what {@code
     * ErrorCode} already owns: KNOWN, named failures. Infrastructure exceptions
     * ({@code StatementStorageException}, a {@code DataAccessException}) and unclassified
     * application exceptions are not {@code ErrorCode}s at all and are not this enum's concern --
     * that dispatch belongs to a separate {@code ExceptionClassifier} (not yet built), which defers
     * to this field only for the one exception type that has an opinion of its own,
     * {@code ApiException}.
     */
    public enum RetryPolicy {
        /** A known, permanent failure -- retrying cannot succeed. Dead-letter on the first
         *  attempt rather than spending the existing 5-attempt/31-minute backoff on something
         *  that will fail identically every time. */
        FAIL_FAST,
        /** A transient condition worth the existing backoff schedule. Not assigned to any
         *  {@code ErrorCode} today -- infrastructure exceptions aren't {@code ErrorCode}s, they're
         *  classified by type, not by code (see the class doc above). Exists so a future code that
         *  genuinely is retryable (should one ever need to be) has somewhere to say so without a
         *  new field. */
        RETRY,
        /** Retry once, then dead-letter and alert -- the honest answer to "is this transient or
         *  permanent" when neither is known yet. Not assigned to any {@code ErrorCode} today for
         *  the same reason as {@code RETRY}; a code this deliberate is unlikely to exist, since a
         *  named {@code ErrorCode} is by definition already a KNOWN failure. */
        RETRY_ONCE_THEN_ALERT,
    }

    private final String code;
    private final HttpStatus defaultStatus;
    private final String defaultMessage;
    private final RetryPolicy retryPolicy;

    /**
     * Every existing call site uses this overload, and every one of them defaults to
     * {@link RetryPolicy#FAIL_FAST}. For the ~15 {@code IMPORT_*} codes that is a real, reasoned
     * default: each one is a known, permanent, user-input failure (a locked PDF, an unreadable
     * layout, damaged bytes) that retrying cannot fix -- see the reliability plan's §5 for the
     * full three-tier model this codifies. For every other code (TXN/ACC/AUTH/the generic
     * fallbacks) it is a safe default rather than a reasoned one: nothing reads this field on
     * those paths today, and {@code ExceptionClassifier} (not yet built) is only ever planned to
     * consult it from {@code ImportJobWorker}'s catch site -- but "don't retry an exception this
     * enum has no opinion about" is the conservative choice regardless, since retrying an
     * unclassified failure five times is worse than dead-lettering it once.
     */
    ErrorCode(String code, HttpStatus defaultStatus, String defaultMessage) {
        this(code, defaultStatus, defaultMessage, RetryPolicy.FAIL_FAST);
    }

    ErrorCode(String code, HttpStatus defaultStatus, String defaultMessage, RetryPolicy retryPolicy) {
        this.code = code;
        this.defaultStatus = defaultStatus;
        this.defaultMessage = defaultMessage;
        this.retryPolicy = retryPolicy;
    }

    public String code() { return code; }
    public HttpStatus defaultStatus() { return defaultStatus; }
    public String defaultMessage() { return defaultMessage; }
    public RetryPolicy retryPolicy() { return retryPolicy; }

    /**
     * The wire code ({@code "IMPORT_001"}) for a stored value that is really this enum's NAME
     * ({@code "IMPORT_NO_HEADER_DETECTED"}) -- or {@code null} for any stored value that isn't one,
     * safely, rather than throwing.
     *
     * <p>Extracted here because two independent tables now store a value in exactly this shape and
     * both need the identical translation: {@code StatementAnalysisSession.failureCode} (the
     * original case, see {@code StatementAnalysisRecorder.recentCustomerFailures}'s doc comment for
     * the bug this translation exists to prevent -- handing a customer response the raw enum name
     * instead of the wire code silently defeated the frontend's failure-UX contract for every row,
     * caught by a post-merge review, commit {@code c44f417}) and {@code ImportJob.failureCode}
     * (Premium Import Reliability v1, §3.1, the import timeline).
     *
     * <p>Not every stored value is a valid enum name: both write sites fall back to {@code
     * failure.getClass().getSimpleName()} (e.g. {@code "NullPointerException"}) when the failure
     * never carried an {@code ApiException} with a code. {@link #valueOf} would throw on that
     * input, and a raw Java exception class name is not something a customer response should carry
     * regardless -- both are handled by returning {@code null} here, which every known consumer
     * already treats as "no curated copy for this one" and falls back to a generic message for.
     */
    public static String wireCodeOrNull(String storedName) {
        if (storedName == null) return null;
        try {
            return valueOf(storedName).code();
        } catch (IllegalArgumentException notAnErrorCodeName) {
            return null;
        }
    }

    /**
     * What to store as a curated failure identifier for {@code cause} -- this enum's own NAME when
     * {@code cause} is an {@link ApiException} carrying a code, else the exception's simple class
     * name (e.g. {@code "NullPointerException"}) as the honest answer for a failure this vocabulary
     * has no opinion about. {@link #wireCodeOrNull} is this method's read-side counterpart: together
     * they are the write-then-translate pair every failure-recording call site needs.
     *
     * <p>Extracted here for the identical reason {@link #wireCodeOrNull} was: two independent write
     * sites ({@code ImportService.recordParseFailure} for {@code
     * StatementAnalysisSession.failureCode}, and {@code ImportJobWorker.recordFailure} for {@code
     * ImportJob.failureCode}, Premium Import Reliability v1, §3.1) need the identical rule, and a
     * rule this specific left duplicated is a rule that drifts the first time only one of its two
     * copies is changed.
     */
    public static String failureCodeOf(Throwable cause) {
        return cause instanceof ApiException api
                ? (api.getCode() == null ? null : api.getCode().name())
                : cause.getClass().getSimpleName();
    }
}
