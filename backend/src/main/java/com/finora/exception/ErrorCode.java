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
    // Bug fix (gap review of SEC-06): TransactionService.create()'s idempotency replay check used
    // to return whatever transaction the key mapped to unconditionally, with no check that the
    // REST of the request -- amount, account, type, date, description, category -- matched what
    // was recorded under that key the first time. A client bug that resent a key with a different
    // amount or account silently got back the stale original instead of a rejection, exactly the
    // "resolves quietly to whatever's there" failure V97's own migration comment says an
    // idempotency key must not permit.
    TXN_IDEMPOTENCY_KEY_REUSED("TXN_004", HttpStatus.CONFLICT,
            "This idempotency key was already used for a different request."),

    // Statement import (com.finora.imports)
    IMPORT_NO_HEADER_DETECTED("IMPORT_001", HttpStatus.UNPROCESSABLE_ENTITY, "Could not find a transaction table in this file", true),
    IMPORT_ACCOUNT_REQUIRED("IMPORT_002", HttpStatus.BAD_REQUEST, "Choose an existing account or provide details for a new one"),
    IMPORT_ACCOUNT_NAME_REQUIRED("IMPORT_003", HttpStatus.BAD_REQUEST, "The new account needs a name"),
    IMPORT_ACCOUNT_FORBIDDEN("IMPORT_004", HttpStatus.FORBIDDEN, "This account does not belong to you"),
    IMPORT_ACCOUNT_NOT_FOUND("IMPORT_005", HttpStatus.NOT_FOUND, "Account not found"),
    // BH-043: intentionalRejection=true -- see that field's own doc. ImportConcurrencyLimiter
    // now throws this the instant every permit is taken, rather than after a rare 20s timeout, so
    // it fires on ordinary bursts routinely, not on genuine server trouble.
    IMPORT_SYSTEM_BUSY("IMPORT_006", HttpStatus.SERVICE_UNAVAILABLE,
            "Finora is processing a lot of statement imports right now. Please try again in a moment.",
            RetryPolicy.FAIL_FAST, false, true),
    // Deliberately separate from IMPORT_001 even though both mean "you got nothing". They fail at
    // different stages and need different follow-up: 001 means the document's layout defeated
    // table detection, 007 means the table WAS found and every row inside it was rejected. Folding
    // them into one code is what let a real statement import as a silent, confirmable no-op.
    IMPORT_NO_TRANSACTIONS_FOUND("IMPORT_007", HttpStatus.UNPROCESSABLE_ENTITY,
            "Found a transaction table in this file but could not read any transactions from it", true),
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
            "This PDF has no text in it -- every page is an image", true),

    IMPORT_PDF_PASSWORD_REQUIRED("IMPORT_008", HttpStatus.UNPROCESSABLE_ENTITY,
            "This statement is password protected. Enter the password your bank uses for it.", true),
    IMPORT_PDF_PASSWORD_INVALID("IMPORT_009", HttpStatus.UNPROCESSABLE_ENTITY,
            "That password did not open this statement. Check it and try again.", true),
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
    // SEC-02 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). The multipart
    // max-file-size cap (application.yml, app.import.pdf.max-pages's sibling property) bounds the
    // UPLOADED bytes, not what PDFBox materializes once it decompresses the document's object/page
    // graph -- a small, spec-valid PDF with an extreme page count is not caught by that cap.
    // PdfTextExtractor checks this immediately after Loader.loadPDF, before the expensive
    // full-document stripper.getText() pass. Same treatment as IMPORT_CORRUPT_PDF: userActionRequired
    // because "split it up" is a real, followable instruction, unlike a genuinely corrupt file.
    IMPORT_PDF_TOO_LARGE("IMPORT_013", HttpStatus.UNPROCESSABLE_ENTITY,
            "This PDF has too many pages to process. Split it into smaller files (e.g. by date range) "
                    + "and import each one separately.", true),
    // Deliberately separate from IMPORT_007 even though both are thrown from the exact same
    // zero-staged-rows call site (ExtractionCheck.rejectIfNothingWasExtracted) -- they are not the
    // same event. 007 means the table WAS found and every row inside it was rejected: a real
    // extraction failure. This means ExplicitZeroActivityDetector found a row where the statement
    // ITSELF states, in both directions at once, that nothing happened during the period it
    // covers -- confirmed against a real HSBC composite statement in the corpus, whose savings
    // ledger prints an explicit zero transaction count alongside an unchanged opening/closing
    // balance. Folding this into 007 is the exact failure IMPORT_007's own comment already
    // describes for IMPORT_001/007: a customer whose statement genuinely had no activity was being
    // told Finora could not read their file, which is not what happened.
    //
    // userActionRequired=true here is a deliberate stretch of what the field literally means
    // ("the user can reasonably correct the input") -- there is nothing to correct. It is chosen
    // anyway because it is the only lever the existing contract exposes to keep this off the
    // red/danger banner treatment IMPORT_FAILURE_MESSAGES otherwise gives every IMPORT_* code; see
    // that file's own comment. Still UNPROCESSABLE_ENTITY, and still thrown rather than a 2xx
    // success, because nothing was staged and no account or session is created here -- only the
    // wording and the code change, not what happens next.
    IMPORT_NO_ACTIVITY_IN_PERIOD("IMPORT_014", HttpStatus.UNPROCESSABLE_ENTITY,
            "This statement's own printed summary shows no transactions for the period it covers "
                    + "-- there is nothing to import from this file.", true),

    // Never thrown -- carried on the job by ImportJob.rejectAfterTrustReview so the user is shown a
    // reason instead of a bare failure. holdForTrustReview clears the job's failure code on the way
    // in (a trust hold is not a failure), so without this there would be nothing left to explain
    // the outcome to somebody who had been told we were running additional checks.
    //
    // The message says what happened on our side and claims nothing about the document. The
    // extraction was not trustworthy; that is a statement about our parse, not about their bank or
    // their statement, and this copy must not let the two blur.
    //
    // userActionRequired=true for the same reason IMPORT_014 chose it -- see that code's comment.
    // There is nothing for the user to correct, and it is the only lever that keeps this off the
    // red danger treatment IMPORT_FAILURE_MESSAGES gives every other IMPORT_* code.
    //
    // Both halves of this -- the calm treatment and this wording -- are a product decision taken by
    // the repository owner on 2026-09-04, not an implementer's default. The alternatives considered
    // and rejected were the standard red failure banner (consistent with other import failures, but
    // reads as "something is wrong with your account" when the fault is our parser), naming the
    // human reviewer in the copy (more transparent, but tells a customer staff opened their
    // statement), and inviting them to contact support (a next step instead of a dead end, but
    // support load nobody wants pre-launch). Changing either half is a product call, not a tidy-up.
    IMPORT_TRUST_REVIEW_REJECTED("IMPORT_015", HttpStatus.UNPROCESSABLE_ENTITY,
            "We checked this statement and could not read it accurately enough to import it. "
                    + "Nothing was added to your accounts.", true),

    // Thrown by ImportSessionService.claimForConfirmation when the session's job is
    // HELD_FOR_TRUST_REVIEW -- TrustPredicate found a reason not to trust the extraction, and the
    // whole point of that hold is that it is withheld from this exact step until an operator
    // decides (see HoldDecision's own doc comment). Without this check, confirmSession/
    // confirmMultiSection had no awareness of the hold at all and would write the staged rows to
    // the ledger anyway -- confirmed against a real held session in manual end-to-end testing.
    // CONFLICT rather than UNPROCESSABLE_ENTITY: the statement itself is not the problem (unlike
    // IMPORT_TRUST_REVIEW_REJECTED), the request is just premature -- the same distinction
    // IMPORT_SESSION_ALREADY_CONFIRMED draws for "already confirmed".
    IMPORT_SESSION_HELD_FOR_REVIEW("IMPORT_016", HttpStatus.CONFLICT,
            "This statement is being reviewed for accuracy and can't be confirmed yet. "
                    + "We'll let you know when it's ready.", true),

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
    // Deliberately distinct from a bare 403 "suspended" message: the frontend has to TELL THEM
    // APART, since a deactivated account gets an in-place reactivation prompt (see
    // AuthService.login()'s deactivated branch) where a suspended one is a dead end. The
    // reactivation token itself travels in ApiException's details map, not this message.
    AUTH_ACCOUNT_DEACTIVATED("AUTH_007", HttpStatus.FORBIDDEN,
            "This account is deactivated."),

    // SEC-03 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Same shape as
    // AUTH_ACCOUNT_DEACTIVATED just above: the frontend has to TELL THIS APART from an ordinary
    // login failure, because the response carries a short-lived challenge token (ApiException's
    // details map, "mfaChallengeToken") the login form needs to complete the second step against
    // POST /auth/mfa/verify -- a plain "invalid credentials" would strand the user with a correct
    // password and no way forward. Thrown only after the password has already been verified (see
    // AuthService.login()), so it never becomes an account-existence or MFA-enrollment oracle for
    // an unauthenticated caller.
    AUTH_MFA_REQUIRED("AUTH_008", HttpStatus.FORBIDDEN,
            "Enter the code from your authenticator app to finish signing in."),
    // Deliberately the SAME code+message for "wrong TOTP code" and "wrong/expired/already-used
    // recovery code" and "expired/unknown challenge token" -- MfaController's one entry point for
    // all three, same reasoning AUTH_INVALID_CREDENTIALS already applies to login(): distinguishing
    // them would tell an attacker which guess got closer.
    AUTH_MFA_INVALID_CODE("AUTH_009", HttpStatus.UNAUTHORIZED,
            "That code didn't work. Check your authenticator app and try again."),

    // Follow-up to SEC-03: the backend above is complete and tested, but the admin portal has no
    // enrollment/verification/recovery UI yet -- flipping app.admin-mfa.enabled on without one
    // would risk locking an admin out with no self-service way back in (see
    // AdminMfaService.requireFeatureEnabled's own doc comment). Every AdminMfaService entry point,
    // and AuthService's login()/completeMfaLogin() gate, refuse with this code while the flag is
    // off (default), so the feature is unreachable end to end rather than merely undocumented.
    AUTH_MFA_NOT_AVAILABLE("AUTH_010", HttpStatus.NOT_FOUND,
            "Admin MFA is not available yet."),

    // Billing / entitlements (com.finora.service.EntitlementService)
    //
    // The first ErrorCode ever thrown from an EntitlementService.hasEntitlement() check --
    // ADVANCED_REPORTS (AnalyticsController's self-service views) is the first FeatureEntitlement
    // key any endpoint actually enforces; every other seeded key (BASIC_DASHBOARD, EXTENDED_HISTORY,
    // INVESTMENT_INSIGHTS, FINO_AI, PRIORITY_SUPPORT) still has zero enforcing call sites. Carries
    // its own code rather than a bare AUTH_FORBIDDEN for the same reason AUTH_MFA_REQUIRED does:
    // the frontend has to TELL THEM APART -- a plan-gated 403 should open PremiumFeatureGate's
    // upgrade prompt, not the generic "you don't have permission" dead end a real authorization
    // failure gets.
    ENTITLEMENT_REQUIRED("ENTITLEMENT_001", HttpStatus.FORBIDDEN,
            "This feature isn't included in your current plan."),

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
    private final boolean userActionRequired;
    private final boolean intentionalRejection;

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
        this(code, defaultStatus, defaultMessage, RetryPolicy.FAIL_FAST, false, false);
    }

    ErrorCode(String code, HttpStatus defaultStatus, String defaultMessage, RetryPolicy retryPolicy) {
        this(code, defaultStatus, defaultMessage, retryPolicy, false, false);
    }

    /**
     * The {@code IMPORT_*} codes below pass {@code true} here -- Premium Import Reliability
     * v1, §1's {@code ACTION_REQUIRED} refinement of {@code FAILED}. Everything else defaults to
     * {@code false} through the shorter overloads, matching {@link RetryPolicy}'s own
     * safe-default reasoning above: a code this enum has no opinion about is presented as plain
     * {@code FAILED}, not guessed into {@code ACTION_REQUIRED}.
     */
    ErrorCode(String code, HttpStatus defaultStatus, String defaultMessage, boolean userActionRequired) {
        this(code, defaultStatus, defaultMessage, RetryPolicy.FAIL_FAST, userActionRequired, false);
    }

    /**
     * Full form — every field explicit, no defaulting. {@code IMPORT_SYSTEM_BUSY} is the only
     * entry that calls this directly today, since it is the only code that needs a non-default
     * {@link #intentionalRejection} while everything else about it (retry policy, user-action)
     * stays at the ordinary default -- adding a dedicated shorter overload just for that one
     * combination would collide with the existing {@code (..., boolean userActionRequired)}
     * overload above (same erased signature, different meaning), so this call site is explicit
     * instead.
     */
    ErrorCode(String code, HttpStatus defaultStatus, String defaultMessage,
              RetryPolicy retryPolicy, boolean userActionRequired, boolean intentionalRejection) {
        this.code = code;
        this.defaultStatus = defaultStatus;
        this.defaultMessage = defaultMessage;
        this.retryPolicy = retryPolicy;
        this.userActionRequired = userActionRequired;
        this.intentionalRejection = intentionalRejection;
    }

    public String code() { return code; }
    public HttpStatus defaultStatus() { return defaultStatus; }
    public String defaultMessage() { return defaultMessage; }
    public RetryPolicy retryPolicy() { return retryPolicy; }

    /**
     * Whether the user themselves can reasonably fix what caused this -- Premium Import
     * Reliability v1, §1's governing rule: "{@code ACTION_REQUIRED} = the user can reasonably
     * correct the input. {@code FAILED} = the user cannot fix it without Finora or support." Never
     * branched on directly by a throw site -- this is presentation metadata about a known failure,
     * the same role {@link #defaultMessage} already plays, not retry policy (that's {@link
     * #retryPolicy}). Two readers, one per import path: {@link
     * com.finora.imports.jobs.UserFacingImportStatus#of} folds it into the async path's
     * {@code userStatus}; {@link GlobalExceptionHandler#handleApiException} puts it on the sync
     * path's error envelope directly, as {@code details.userActionRequired}, so a synchronous
     * failure -- which has no {@code ImportJob} to compute a {@code userStatus} on -- carries the
     * same answer without the frontend needing its own copy of which codes qualify.
     */
    public boolean userActionRequired() { return userActionRequired; }

    /**
     * BH-043: whether a {@code 5xx} carrying this code is the server deliberately choosing to
     * reject a request it could have served (backpressure, a capacity limit, a maintenance
     * window), rather than something breaking unexpectedly. {@link GlobalExceptionHandler
     * #handleApiException} reads this to decide how to log a {@code 5xx} response: a genuine
     * failure logs at {@code ERROR} with the full stack trace (how to find and fix a real
     * defect); a deliberate rejection logs at {@code WARN} with no trace (there is nothing to
     * find — the code and message already say everything the throw site knew), so that an
     * ordinary, expected condition firing routinely under normal load doesn't read as a server
     * outage or flood {@code ERROR}-level alerting with what the system is doing correctly.
     *
     * <p>Defaults to {@code false} through every shorter constructor overload, same reasoning as
     * {@link #userActionRequired()}'s own default: a {@code 5xx} this enum has no opinion about
     * is treated as a genuine failure, not guessed into the quieter path.
     */
    public boolean intentionalRejection() { return intentionalRejection; }

    /**
     * {@link #userActionRequired()} for a stored value that is really this enum's NAME, or
     * {@code false} for anything that isn't one (including {@code null}) -- the safe default,
     * since a failure with no curated {@code ErrorCode} at all has no known concrete fix to offer.
     * Mirrors {@link #wireCodeOrNull}'s exact shape and exists for the identical reason: {@code
     * ImportJob.failureCode} stores either an {@code ErrorCode} enum name or a raw exception's
     * simple class name, and only {@link #valueOf} can tell which -- safely, rather than throwing.
     */
    public static boolean userActionRequiredOrDefault(String storedName) {
        if (storedName == null) return false;
        try {
            return valueOf(storedName).userActionRequired();
        } catch (IllegalArgumentException notAnErrorCodeName) {
            return false;
        }
    }

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
