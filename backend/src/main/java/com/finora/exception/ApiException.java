package com.finora.exception;

import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.Map;

/**
 * Existing {@code new ApiException(status, message)} call sites are unaffected: {@code code()}
 * returns null and {@code details()} returns an empty map for those, same as before the
 * {@link ErrorCode}/details fields were added (see ErrorCode's class doc for the migration
 * philosophy — codes get adopted call-site by call-site, not as a blanket rewrite).
 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final ErrorCode code;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String message) {
        this(status, null, message, Collections.emptyMap());
    }

    public ApiException(ErrorCode code) {
        this(code.defaultStatus(), code, code.defaultMessage(), Collections.emptyMap());
    }

    public ApiException(ErrorCode code, String message) {
        this(code.defaultStatus(), code, message, Collections.emptyMap());
    }

    public ApiException(HttpStatus status, ErrorCode code, String message) {
        this(status, code, message, Collections.emptyMap());
    }

    public ApiException(HttpStatus status, ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Collections.emptyMap() : details;
    }

    public HttpStatus getStatus() { return status; }
    public ErrorCode getCode() { return code; }
    public Map<String, Object> getDetails() { return details; }
}
