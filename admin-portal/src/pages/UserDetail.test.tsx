import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import UserDetail from './UserDetail';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminUsersApi } from '../api/endpoints';
import type { UserDetailDto } from '../types';

/**
 * Characterisation tests for UserDetail, the largest source file in the repository (1770 lines)
 * and, until now, one of the few admin pages with no test at all.
 *
 * Written BEFORE splitting the file into components, and deliberately pinned to the behaviour a
 * split is most likely to break silently: WHICH PERMISSION GATES WHICH SECTION. Eight sections are
 * each rendered behind their own `hasPermission(...)` check. Moving one into a new component and
 * dropping or mistyping its guard would leak another user's financial data to an admin who is not
 * authorised to see it, and nothing in the type system or the existing suite would notice.
 *
 * These assert the gate, not the section internals. Internals are free to change during the
 * refactor; the gates are not.
 */

// AdminLayout now renders ThemeToggle (dark-mode support), which calls useTheme() --
// same reason adminSearchApi is stubbed below for GlobalSearch: a real ThemeProvider isn't
// mounted in these tests, so without this mock every AdminLayout-wrapped page throws before
// any assertion runs.
vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({ useAdminAuth: vi.fn() }));
vi.mock('../context/NotificationContext', () => ({
  useNotify: () => ({ success: vi.fn(), error: vi.fn() }),
}));

// Every section fetches on mount. Only adminUsersApi.get needs real shape; the rest just have to
// exist and never resolve, since these tests assert on gating rather than on section data.
// Everything is inlined because vi.mock factories are hoisted above any top-level declaration.
vi.mock('../api/endpoints', () => {
  const pending = () => new Promise(() => {});
  return {
    adminUsersApi: { get: vi.fn(), suspend: vi.fn(), reactivate: vi.fn(), update: vi.fn() },
    adminAuditApi: { forUser: vi.fn(pending) },
    adminRolesApi: { listRoles: vi.fn(pending), assignRole: vi.fn(), revokeRole: vi.fn() },
    adminAccountsApi: { forUser: vi.fn(pending), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
    adminTransactionsApi: { forUser: vi.fn(pending), delete: vi.fn() },
    adminUserMerchantsApi: { list: vi.fn(pending), rename: vi.fn(), merge: vi.fn(), confirmCategory: vi.fn() },
    adminUserRulesApi: { list: vi.fn(pending), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
    adminUserRelationshipsApi: { list: vi.fn(pending), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
    adminUserLearningApi: { summary: vi.fn(pending), timeline: vi.fn(pending) },
    adminUserWorkspaceApi: { dashboard: vi.fn(pending), activity: vi.fn(pending) },
    adminUserAnalyticsApi: {
      topMerchants: vi.fn(pending), topCategories: vi.fn(pending), trend: vi.fn(pending),
      categoryConfidence: vi.fn(pending), learningGrowth: vi.fn(pending),
    },
    banksApi: { list: vi.fn(pending) },
    // AdminLayout renders GlobalSearch, which imports this. vi.mock replaces the whole module, so
    // omitting it makes GlobalSearch throw before any assertion runs.
    adminSearchApi: { search: vi.fn(pending) },
  };
});

// Matches UserDetailDto exactly. Getting this wrong is not a soft failure: the page renders
// `user.roleNames.join(', ')` unguarded, so a fixture missing that field throws during render and
// every assertion in the file fails with a misleading "element not found".
const USER: UserDetailDto = {
  id: 'user-1',
  email: 'someone@example.com',
  fullName: 'Target User',
  phoneNumber: '+919876543210', // synthetic-ok: invented, matches the number the backend ITs use
  phoneVerified: true,
  status: 'ACTIVE',
  roleNames: ['USER'],
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-02T00:00:00Z',
  accountCount: 2,
  transactionCount: 17,
};

/**
 * Every section's heading, paired with the single permission that reveals it.
 *
 * Queried by heading ROLE, not by text: "Accounts" is also the label of a stat tile in the profile
 * header, so a plain text query matches two elements and throws.
 */
const SECTION_GATES: ReadonlyArray<readonly [string, string]> = [
  ['Accounts', 'USER_VIEW'],
  ['Recent transactions', 'TRANSACTION_DELETE'],
  ['Merchants', 'MERCHANT_MANAGE'],
  ['Rules', 'RULE_MANAGE'],
  ['Relationships', 'RELATIONSHIP_MANAGE'],
  ['Learning Engine', 'MERCHANT_MANAGE'],
  ['Analytics', 'PLATFORM_ANALYTICS_VIEW'],
  ['Intelligence Workspace', 'RECONCILIATION_VIEW'],
];

function renderWith(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(
    mockAdminAuthState({
      permissions,
      hasPermission: (p: string) => permissions.includes(p),
    })
  );
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/users/user-1']}>
        <Routes>
          <Route path="/users/:id" element={<UserDetail />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(adminUsersApi.get).mockResolvedValue(USER);
});

describe('UserDetail — page-level access', () => {
  it('renders nothing but the permission notice without USER_VIEW', async () => {
    renderWith([]);
    expect(await screen.findByText(/don't have access to this section/i)).toBeInTheDocument();
    expect(screen.queryByText('Target User')).not.toBeInTheDocument();
  });

  it('shows the profile once USER_VIEW is present', async () => {
    renderWith(['USER_VIEW']);
    expect(await screen.findByText('Target User')).toBeInTheDocument();
  });
});

describe('UserDetail — section gating', () => {
  /*
   * The load-bearing assertions for the refactor. Each section must appear for exactly the
   * permission that guards it, and must NOT appear for an admin holding only USER_VIEW.
   */
  it.each(SECTION_GATES)('shows "%s" when the admin holds %s', async (heading, permission) => {
    renderWith(['USER_VIEW', permission]);
    expect(await screen.findByRole('heading', { name: heading })).toBeInTheDocument();
  });

  // Accounts is excluded: it is gated on USER_VIEW itself, the same permission that grants the
  // page, so there is no "has the page but not this section" state to assert for it.
  it.each(SECTION_GATES.filter(([, permission]) => permission !== 'USER_VIEW'))(
    'hides "%s" from an admin with only USER_VIEW',
    async (heading) => {
      renderWith(['USER_VIEW']);
      await screen.findByText('Target User'); // page has rendered
      expect(screen.queryByRole('heading', { name: heading })).not.toBeInTheDocument();
    }
  );

  /** MERCHANT_MANAGE gates two different sections. A split that gives one of them the wrong guard
   *  would still pass a test that only checked the other. */
  it('reveals both MERCHANT_MANAGE sections together', async () => {
    renderWith(['USER_VIEW', 'MERCHANT_MANAGE']);
    expect(await screen.findByRole('heading', { name: 'Merchants' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Learning Engine' })).toBeInTheDocument();
  });

  it('shows every section to an admin holding all of the permissions', async () => {
    renderWith(['USER_VIEW', ...SECTION_GATES.map(([, p]) => p)]);
    for (const [heading] of SECTION_GATES) {
      expect(await screen.findByRole('heading', { name: heading })).toBeInTheDocument();
    }
  });
});

describe('UserDetail — profile actions', () => {
  it('offers suspend only with USER_DELETE', async () => {
    renderWith(['USER_VIEW']);
    await screen.findByText('Target User');
    expect(screen.queryByRole('button', { name: /suspend/i })).not.toBeInTheDocument();

    renderWith(['USER_VIEW', 'USER_DELETE']);
    await waitFor(() => expect(screen.getAllByRole('button', { name: /suspend/i }).length).toBeGreaterThan(0));
  });

  it('offers profile editing only with USER_UPDATE', async () => {
    renderWith(['USER_VIEW']);
    await screen.findByText('Target User');
    expect(screen.queryByRole('button', { name: /edit profile/i })).not.toBeInTheDocument();

    renderWith(['USER_VIEW', 'USER_UPDATE']);
    await waitFor(() => expect(screen.getAllByRole('button', { name: /edit profile/i }).length).toBeGreaterThan(0));
  });

  it('shows the Roles panel only with ROLE_MANAGE', async () => {
    renderWith(['USER_VIEW']);
    await screen.findByText('Target User');
    expect(screen.queryByRole('heading', { name: 'Roles' })).not.toBeInTheDocument();

    renderWith(['USER_VIEW', 'ROLE_MANAGE']);
    expect(await screen.findByRole('heading', { name: 'Roles' })).toBeInTheDocument();
  });
});
