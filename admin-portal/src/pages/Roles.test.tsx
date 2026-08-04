import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Roles from './Roles';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminRolesApi } from '../api/endpoints';
import type { PermissionDto, RoleDto } from '../types';

/**
 * Bug fix: Roles.tsx -- the page that grants and revokes every OTHER admin permission on the
 * platform, including its own (ROLE_MANAGE/PERMISSION_MANAGE) -- had no test file at all, unlike
 * every sibling permission-gated page (Banks, GlobalRules, LearningEngine, ...). A regression here
 * (e.g. a dropped RequirePermission guard, or a grant/revoke button rendered for an account that
 * shouldn't see it) would have gone completely uncaught. Mirrors the established pattern from
 * those sibling test files: access-denied without the gating permission, a render of real data,
 * and the create/grant/revoke mutations RoleCard and RolesContent expose.
 */

vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminRolesApi: {
    listRoles: vi.fn(),
    listPermissions: vi.fn(),
    createRole: vi.fn(),
    updateRole: vi.fn(),
    deleteRole: vi.fn(),
    createPermission: vi.fn(),
    deletePermission: vi.fn(),
    grantPermission: vi.fn(),
    revokePermission: vi.fn(),
  },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Roles />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

// AdminLayout always renders Sidebar, which reads `permissions` off this same hook.
function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    fullName: 'Support Admin',
    logout: vi.fn(),
  }));
}

const AUDIT_VIEW: PermissionDto = { id: 'perm-1', name: 'AUDIT_VIEW', description: 'View the audit log.' };
const USER_VIEW: PermissionDto = { id: 'perm-2', name: 'USER_VIEW', description: 'View user accounts.' };

const SUPPORT_ROLE: RoleDto = {
  id: 'role-1', name: 'SUPPORT_AGENT', description: 'Front-line support.', permissions: [AUDIT_VIEW],
};

beforeEach(() => {
  vi.mocked(useAdminAuth).mockReset();
  vi.mocked(adminRolesApi.listRoles).mockReset();
  vi.mocked(adminRolesApi.listPermissions).mockReset();
  vi.mocked(adminRolesApi.createRole).mockReset();
  vi.mocked(adminRolesApi.grantPermission).mockReset();
  vi.mocked(adminRolesApi.revokePermission).mockReset();
});

describe('Roles', () => {
  it('shows an access-denied message when the account lacks ROLE_MANAGE', () => {
    mockAuth([]);
    vi.mocked(adminRolesApi.listRoles).mockResolvedValue([]);

    renderPage();

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
  });

  it('renders an existing role and its granted permissions', async () => {
    mockAuth(['ROLE_MANAGE']);
    vi.mocked(adminRolesApi.listRoles).mockResolvedValue([SUPPORT_ROLE]);

    renderPage();

    await waitFor(() => expect(screen.getByText('SUPPORT_AGENT')).toBeInTheDocument());
    expect(screen.getByText('AUDIT_VIEW')).toBeInTheDocument();
  });

  // PERMISSION_MANAGE is a separate, narrower permission from ROLE_MANAGE (see
  // AdminRoleController) -- an account holding ROLE_MANAGE alone can grant/revoke permissions a
  // role already lists, but the "All permissions" registry (create/delete a permission outright)
  // stays hidden without PERMISSION_MANAGE specifically.
  it('hides the "All permissions" registry from an account with ROLE_MANAGE but not PERMISSION_MANAGE', async () => {
    mockAuth(['ROLE_MANAGE']);
    vi.mocked(adminRolesApi.listRoles).mockResolvedValue([SUPPORT_ROLE]);

    renderPage();

    await waitFor(() => expect(screen.getByText('SUPPORT_AGENT')).toBeInTheDocument());
    expect(screen.queryByText('All permissions')).not.toBeInTheDocument();
    expect(adminRolesApi.listPermissions).not.toHaveBeenCalled();
  });

  it('shows the "All permissions" registry once the account also holds PERMISSION_MANAGE', async () => {
    mockAuth(['ROLE_MANAGE', 'PERMISSION_MANAGE']);
    vi.mocked(adminRolesApi.listRoles).mockResolvedValue([SUPPORT_ROLE]);
    vi.mocked(adminRolesApi.listPermissions).mockResolvedValue([AUDIT_VIEW, USER_VIEW]);

    renderPage();

    await waitFor(() => expect(screen.getByText('All permissions')).toBeInTheDocument());
    expect(screen.getByText(/View user accounts/)).toBeInTheDocument();
  });

  it('creates a new role via the New role form', async () => {
    mockAuth(['ROLE_MANAGE']);
    vi.mocked(adminRolesApi.listRoles).mockResolvedValue([]);
    vi.mocked(adminRolesApi.createRole).mockResolvedValue({ id: 'role-new', name: 'BILLING_AGENT', description: 'Handles billing.', permissions: [] });
    const user = userEvent.setup();

    renderPage();

    // findByText (not getByText) -- RolesContent shows a "Loading…" placeholder until both
    // admin-roles and admin-permissions settle, same reasoning as GlobalRules.test.tsx's
    // identical findByText('New rule').
    await user.click(await screen.findByText('New role'));
    const form = screen.getByText('New role', { selector: 'h3' }).closest('form')!;
    await user.type(within(form).getByPlaceholderText('e.g. SUPPORT_AGENT'), 'billing_agent');
    await user.type(within(form).getByLabelText('Description'), 'Handles billing.');
    await user.click(within(form).getByRole('button', { name: 'Create' }));

    // CreateEntityForm upper-cases the name before submitting (both roles and permissions follow
    // the same ^[A-Z][A-Z0-9_]{1,49}$ pattern the backend validates).
    await waitFor(() => expect(adminRolesApi.createRole).toHaveBeenCalledWith('BILLING_AGENT', 'Handles billing.'));
  });

  it('grants a permission to a role from the role card', async () => {
    mockAuth(['ROLE_MANAGE', 'PERMISSION_MANAGE']);
    vi.mocked(adminRolesApi.listRoles).mockResolvedValue([SUPPORT_ROLE]);
    vi.mocked(adminRolesApi.listPermissions).mockResolvedValue([AUDIT_VIEW, USER_VIEW]);
    vi.mocked(adminRolesApi.grantPermission).mockResolvedValue({ ...SUPPORT_ROLE, permissions: [AUDIT_VIEW, USER_VIEW] });
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(screen.getByText('SUPPORT_AGENT')).toBeInTheDocument());

    // USER_VIEW is the only grantable permission shown (AUDIT_VIEW is already held) -- see
    // RoleCard's grantableePermissions filter.
    await user.selectOptions(screen.getByDisplayValue('Grant a permission…'), 'perm-2');
    await user.click(screen.getByRole('button', { name: 'Grant' }));

    await waitFor(() => expect(adminRolesApi.grantPermission).toHaveBeenCalledWith('role-1', 'perm-2'));
  });

  it('revokes a permission from a role', async () => {
    mockAuth(['ROLE_MANAGE']);
    vi.mocked(adminRolesApi.listRoles).mockResolvedValue([SUPPORT_ROLE]);
    vi.mocked(adminRolesApi.revokePermission).mockResolvedValue({ ...SUPPORT_ROLE, permissions: [] });
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(screen.getByText('SUPPORT_AGENT')).toBeInTheDocument());

    // The revoke control is a small "×" glyph button; "Revoke" lives in its `title` attribute
    // (a tooltip), not its accessible name (computed from the visible "×" text content), so this
    // targets it via getByTitle rather than getByRole's name matcher.
    await user.click(screen.getByTitle('Revoke'));

    await waitFor(() => expect(adminRolesApi.revokePermission).toHaveBeenCalledWith('role-1', 'perm-1'));
  });
});
