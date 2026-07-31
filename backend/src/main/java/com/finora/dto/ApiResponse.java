package com.finora.dto;

import org.slf4j.MDC;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * Standard envelope for every API response, success or failure, so frontend error handling
 * doesn't have to special-case "sometimes the body is the payload, sometimes it's an error map."
 *
 * errorCode was originally a plain string for any failure message (HTTP status name, or a
 * hand-picked constant like "VALIDATION_ERROR"); it now also carries com.finora.exception.
 * ErrorCode's stable string code (e.g. "TXN_001") when the throwing site used one — see
 * ErrorCode's class doc. Existing callers of error(message, errorCode) are unaffected; details
 * defaults to an empty map for them.
 *
 * requestId is read from MDC (set by CorrelationIdFilter on every request) rather than passed
 * in explicitly by callers — this means every controller's existing ApiResponse.ok(...) call
 * automatically gets a correlation ID with no call-site changes.
 */
public record ApiResponse<T>(boolean success, String message, T data, Instant timestamp, String errorCode,
                              String requestId, Map<String, Object> details) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "OK", data, Instant.now(), null, MDC.get("requestId"), Collections.emptyMap());
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(true, message, data, Instant.now(), null, MDC.get("requestId"), Collections.emptyMap());
    }

    public static <T> ApiResponse<T> error(String message, String errorCode) {
        return new ApiResponse<>(false, message, null, Instant.now(), errorCode, MDC.get("requestId"), Collections.emptyMap());
    }

    /** Structured variant — see com.finora.exception.ErrorCode and GlobalExceptionHandler. */
    public static <T> ApiResponse<T> error(String message, String errorCode, Map<String, Object> details) {
        return new ApiResponse<>(false, message, null, Instant.now(), errorCode, MDC.get("requestId"),
                details == null ? Collections.emptyMap() : details);
    }
}
