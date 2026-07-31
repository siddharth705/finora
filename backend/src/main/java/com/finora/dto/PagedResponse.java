package com.finora.dto;

import java.util.List;

/**
 * Generic paginated envelope for any list endpoint that needs the caller to know a real total
 * (for "Showing 1-10 of 4,213" and computing total page count up front), not just infer "more
 * pages might exist" heuristically from result count vs. page size.
 *
 * Originally introduced admin-only (nested in AdminDtos) for the Users directory, on the
 * assumption that regular user-facing list endpoints could get away with a bare array. That
 * assumption didn't hold: Ledger's transaction search hit the same "no way to paginate past page
 * one" gap the admin Users directory was built to avoid, for the same reason -- Spring Data's
 * Page<T> already computes totalElements/totalPages on every paged repository query, so this was
 * always available and just being discarded before it left the service layer. Extracted here,
 * un-admin-scoped, rather than duplicating an equivalent record a second time.
 */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static <T> PagedResponse<T> of(org.springframework.data.domain.Page<T> page) {
        return new PagedResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
