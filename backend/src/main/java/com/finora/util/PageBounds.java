package com.finora.util;

/**
 * Clamps raw page/size query params into values {@code PageRequest.of} will actually accept.
 * Extracted after the third copy of this exact `Math.max(0, page)` / `Math.max(1, Math.min(size,
 * N))` pair showed up (AdminController.globalAuditLogs, AdminUserService.list) -- a negative page
 * or zero/negative size otherwise reaches {@code PageRequest.of} directly and throws
 * IllegalArgumentException, which has no handler in GlobalExceptionHandler and surfaces as an
 * opaque 500 instead of just being clamped to something sane (mirrors how most paginated admin
 * UIs behave: an oversized page size doesn't error, it just gets capped).
 */
public final class PageBounds {

    private PageBounds() {}

    public static final int DEFAULT_MAX_SIZE = 100;

    public static int safePage(int page) {
        return Math.max(0, page);
    }

    public static int safeSize(int size, int maxSize) {
        return Math.max(1, Math.min(size, maxSize));
    }

    public static int safeSize(int size) {
        return safeSize(size, DEFAULT_MAX_SIZE);
    }
}
