import type { PagedResponse } from '../types';

/**
 * Several test files each had their own copy-pasted version of this helper -- Users.test.tsx's
 * took a properly-typed `content: T[]`, but AuditLog.test.tsx's own copy hardcoded
 * `content: unknown[]`, which is why mockResolvedValue(pagedResponse([...AuditLogDto objects]))
 * failed to satisfy `PagedResponse<AuditLogDto>`: TypeScript infers `unknown[]` from the
 * parameter's own declared type, not from what's actually passed in, so the return value's
 * `content` was `unknown[]` no matter what array of real DTOs was given. One shared, actually
 * generic version instead of N duplicated (and, in one case, wrong) ones.
 */
export function pagedResponse<T>(
  content: T[],
  overrides: Partial<Omit<PagedResponse<T>, 'content'>> = {}
): PagedResponse<T> {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: 1,
    ...overrides,
  };
}
