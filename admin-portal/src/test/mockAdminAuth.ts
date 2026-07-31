import { vi } from 'vitest';
import type { AdminAuthState } from '../context/AdminAuthContext';

/**
 * Every test that mocks useAdminAuth() was building a PARTIAL AdminAuthState (just whichever
 * fields that particular test cared about -- permissions, hasPermission, fullName, logout) and
 * force-casting it with `as ReturnType<typeof useAdminAuth>`. TypeScript correctly flags that as
 * an unsound cast (TS2352) once the partial object's shape stops overlapping enough with the real
 * type to be considered a plausible narrowing -- which is exactly what should happen here, since
 * these mocks are missing most of the real interface's fields entirely, not narrowing a real
 * union.
 *
 * This provides sane defaults for every field so each test can override only what it actually
 * exercises, with the compiler checking the whole object against the real AdminAuthState shape
 * instead of trusting an unchecked cast -- a typo'd field name here fails to compile instead of
 * silently mocking nothing.
 */
export function mockAdminAuthState(overrides: Partial<AdminAuthState> = {}): AdminAuthState {
  return {
    token: 'test-token',
    email: 'admin@test.finora.local',
    fullName: 'Test Admin',
    phoneVerified: true,
    permissions: [],
    roles: [],
    loading: false,
    login: vi.fn(),
    completePhoneVerification: vi.fn(),
    logout: vi.fn(),
    hasPermission: () => false,
    ...overrides,
  };
}
