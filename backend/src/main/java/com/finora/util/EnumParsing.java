package com.finora.util;

import com.finora.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Bug fix: {@code Transaction.Type.valueOf(rawString)} ran directly on caller-supplied strings in
 * four places (TransactionService's filter/create/update, ImportService.confirm() -- the latter
 * reachable straight from the import-confirm API with no staging-side validation of `type` either)
 * with no try/catch. An unrecognized or blank value threw IllegalArgumentException, which
 * GlobalExceptionHandler has no specific handler for, so it fell through to the generic Exception
 * handler and came back as an opaque 500 instead of a real 400 -- the exact same bug class
 * AccountService.parseAccountType already fixed for Account.Type, just missed for Transaction.Type.
 * Shared here (rather than a fourth copy-pasted private method) since two unrelated service classes
 * both need it for the same enum.
 */
public final class EnumParsing {

    private EnumParsing() {}

    public static <E extends Enum<E>> E parse(Class<E> enumType, String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, fieldName + " is required");
        }
        try {
            return Enum.valueOf(enumType, raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unrecognized " + fieldName + ": " + raw);
        }
    }

    /** For optional fields where {@code null} means "not supplied" / "don't change" -- returns
     *  {@code null} unchanged rather than treating a missing value as an error. */
    public static <E extends Enum<E>> E parseIfPresent(Class<E> enumType, String raw, String fieldName) {
        return raw == null ? null : parse(enumType, raw, fieldName);
    }
}
