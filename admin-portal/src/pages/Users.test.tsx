import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Users from './Users';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminUsersApi } from '../api/endpoints';
import type { UserSummaryDto } from '../types';

// AdminLayout now renders ThemeToggle (dark-mode support), which calls useTheme() --
// same reason adminSearchApi is stubbed below for GlobalSearch: a real ThemeProvider isn't
// mounted in these tests, so without this mock every AdminLayout-wrapped page throws before
// any assertion runs.
vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
// Users.tsx now calls useNotify() (Admin Portal Phase 6) -- without this mock, rendering the page
// outside a real NotificationProvider throws "useNotify must be used within NotificationProvider"
// before any test assertion runs (same class of bug MerchantIntelligence.test.tsx's fix note
// describes for an unmocked hook used inside the rendered tree).
const notifySuccess = vi.fn();
const notifyError = vi.fn();
vi.mock('../context/NotificationContext', () => ({
  useNotify: () => ({ success: notifySuccess, error: notifyError }),
}));
vi.mock('../api/endpoints', () => ({
  adminUsersApi: { list: vi.fn(), get: vi.fn(), suspend: vi.fn(), reactivate: vi.fn(), create: vi.fn(), update: vi.fn() },
  // CreateUserForm fetches this to populate its Role dropdown -- without a mock here,
  // adminRolesApi would be undefined in this module (vi.mock replaces the whole module with
  // only what's listed), and calling adminRolesApi.listRoles() would throw before any test
  // assertion runs, same class of bug the NotificationContext mock above already guards against.
  adminRolesApi: { listRoles: vi.fn(), assignRole: vi.fn() },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Users />
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

function pagedResponse(content: UserSummaryDto[], overrides: Partial<Record<string, unknown>> = {}) {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: 1, ...overrides };
}

const AMY: UserSummaryDto = {
  id: 'user-1', email: 'amy@example.com', fullName: 'Amy Active', phoneNumber: '+911234567890',
  phoneVerified: true, status: 'ACTIVE', roleNames: ['USER'], createdAt: new Date().toISOString(),
};

beforeEach(() => {
  localStorage.clear();
});

describe('Users', () => {
  beforeEach(() => {
    vi.mocked(adminUsersApi.list).mockReset();
    vi.mocked(adminUsersApi.suspend).mockReset();
    vi.mocked(adminUsersApi.reactivate).mockReset();
    vi.mocked(adminUsersApi.create).mockReset();
    notifySuccess.mockReset();
    notifyError.mockReset();
  });

  it('shows an access-denied message when the account lacks USER_VIEW', () => {
    mockAuth([]);
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([]));

    renderPage();

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
  });

  it('renders the user list', async () => {
    mockAuth(['USER_VIEW']);
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([AMY]));

    renderPage();

    await waitFor(() => expect(screen.getByText('Amy Active')).toBeInTheDocument());
    expect(screen.getByText('amy@example.com')).toBeInTheDocument();
  });

  it('applies a search filter via the FilterBar', async () => {
    mockAuth(['USER_VIEW']);
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([AMY]));
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(adminUsersApi.list).toHaveBeenCalledWith(
      { q: undefined, status: undefined, page: 0, size: 20 }
    ));

    await user.type(screen.getByPlaceholderText('Search name, email, phone…'), 'amy');
    await user.click(screen.getByRole('button', { name: 'Search' }));

    await waitFor(() => expect(adminUsersApi.list).toHaveBeenCalledWith(
      { q: 'amy', status: undefined, page: 0, size: 20 }
    ));
  });

  it('applies a status filter immediately on selection', async () => {
    mockAuth(['USER_VIEW']);
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([AMY]));
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(adminUsersApi.list).toHaveBeenCalledWith(
      { q: undefined, status: undefined, page: 0, size: 20 }
    ));

    await user.selectOptions(screen.getByRole('combobox'), 'SUSPENDED');

    await waitFor(() => expect(adminUsersApi.list).toHaveBeenCalledWith(
      { q: undefined, status: 'SUSPENDED', page: 0, size: 20 }
    ));
  });

  it('saves the current filters as a named view and re-applies them after clearing', async () => {
    mockAuth(['USER_VIEW']);
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([AMY]));
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(adminUsersApi.list).toHaveBeenCalledWith(
      { q: undefined, status: undefined, page: 0, size: 20 }
    ));

    const searchInput = screen.getByPlaceholderText('Search name, email, phone…');
    await user.type(searchInput, 'amy');
    await user.selectOptions(screen.getByRole('combobox'), 'ACTIVE');
    await user.click(screen.getByRole('button', { name: /Views/ }));
    await user.click(screen.getByText('+ Save current filters'));
    await user.type(screen.getByPlaceholderText('View name'), 'Active Amys');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await user.clear(searchInput);
    await user.selectOptions(screen.getByRole('combobox'), '');
    await user.click(screen.getByText('Active Amys'));

    await waitFor(() => expect(adminUsersApi.list).toHaveBeenCalledWith(
      { q: 'amy', status: 'ACTIVE', page: 0, size: 20 }
    ));
  });

  it('suspends an active user', async () => {
    mockAuth(['USER_VIEW', 'USER_DELETE']);
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([AMY]));
    vi.mocked(adminUsersApi.suspend).mockResolvedValue({ ...AMY, status: 'SUSPENDED' });
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(screen.getByText('Amy Active')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: /Suspend/ }));
    // Custom in-app confirmation (ConfirmDialog), not the browser's own confirm() -- see this
    // page's own doc comment on confirmSuspend for why. Scoped to the dialog: its confirm button
    // shares the accessible name "Suspend" with the row's own trigger button, still in the DOM.
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Suspend' }));

    await waitFor(() => expect(adminUsersApi.suspend).toHaveBeenCalledWith('user-1'));
    await waitFor(() => expect(notifySuccess).toHaveBeenCalledWith('User suspended.'));
  });

  it('does not suspend when the confirmation is declined', async () => {
    // Suspending signs the user out and blocks their login, from a one-click button in a table
    // row with no undo in the same place -- worth a test of its own that Cancel genuinely stops it.
    mockAuth(['USER_VIEW', 'USER_DELETE']);
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([AMY]));
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(screen.getByText('Amy Active')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: /Suspend/ }));
    await screen.findByText('Suspend Amy Active?');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(adminUsersApi.suspend).not.toHaveBeenCalled();
  });

  it('shows an error notification when suspending fails', async () => {
    mockAuth(['USER_VIEW', 'USER_DELETE']);
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([AMY]));
    vi.mocked(adminUsersApi.suspend).mockRejectedValue({ response: { data: { message: 'Cannot suspend yourself.' } } });
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(screen.getByText('Amy Active')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: /Suspend/ }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Suspend' }));

    await waitFor(() => expect(notifyError).toHaveBeenCalledWith('Cannot suspend yourself.'));
  });

  it('shows a Reactivate action for a self-service deactivated account, not a Suspend action', async () => {
    // AdminUserService.reactivate() was widened to accept DEACTIVATED as well as SUSPENDED -- the
    // admin portal's action column has to offer the same button for both, not just SUSPENDED.
    mockAuth(['USER_VIEW', 'USER_DELETE']);
    const deactivated: UserSummaryDto = { ...AMY, id: 'user-2', fullName: 'Dana Deactivated', status: 'DEACTIVATED' };
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([deactivated]));
    vi.mocked(adminUsersApi.reactivate).mockResolvedValue({ ...deactivated, status: 'ACTIVE' });
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(screen.getByText('Dana Deactivated')).toBeInTheDocument());

    // { selector: 'span' } excludes the status filter dropdown's own "Deactivated" <option>,
    // which otherwise matches the same text.
    expect(screen.getByText('Deactivated', { selector: 'span' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Suspend/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Reactivate/ }));

    // window.prompt is stubbed to return '' (see src/test/setup.ts) -- an empty note trims to
    // undefined, matching a plain reactivation with no note attached.
    await waitFor(() => expect(adminUsersApi.reactivate).toHaveBeenCalledWith('user-2', undefined));
  });

  it('cancels the reactivation when the admin dismisses the reason prompt', async () => {
    mockAuth(['USER_VIEW', 'USER_DELETE']);
    const deactivated: UserSummaryDto = { ...AMY, id: 'user-2', fullName: 'Dana Deactivated', status: 'DEACTIVATED' };
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([deactivated]));
    vi.mocked(window.prompt).mockReturnValueOnce(null);
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(screen.getByText('Dana Deactivated')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: /Reactivate/ }));

    expect(adminUsersApi.reactivate).not.toHaveBeenCalled();
  });

  it('passes a trimmed reason to the reactivate call when the admin provides one', async () => {
    mockAuth(['USER_VIEW', 'USER_DELETE']);
    const deactivated: UserSummaryDto = { ...AMY, id: 'user-2', fullName: 'Dana Deactivated', status: 'DEACTIVATED' };
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([deactivated]));
    vi.mocked(adminUsersApi.reactivate).mockResolvedValue({ ...deactivated, status: 'ACTIVE' });
    vi.mocked(window.prompt).mockReturnValueOnce('  Confirmed over a support call  ');
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(screen.getByText('Dana Deactivated')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: /Reactivate/ }));

    await waitFor(() => expect(adminUsersApi.reactivate)
      .toHaveBeenCalledWith('user-2', 'Confirmed over a support call'));
  });

  it('offers no suspend/reactivate action for an account pending deletion', async () => {
    mockAuth(['USER_VIEW', 'USER_DELETE']);
    const pending: UserSummaryDto = { ...AMY, id: 'user-3', fullName: 'Pat Pending', status: 'PENDING_DELETION' };
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([pending]));

    renderPage();
    await waitFor(() => expect(screen.getByText('Pat Pending')).toBeInTheDocument());

    expect(screen.getByText('Pending Deletion', { selector: 'span' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Suspend/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Reactivate/ })).not.toBeInTheDocument();
  });

  it('creates a new user via the New user form', async () => {
    mockAuth(['USER_VIEW', 'USER_CREATE']);
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([]));
    vi.mocked(adminUsersApi.create).mockResolvedValue(AMY);
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(adminUsersApi.list).toHaveBeenCalled());

    await user.click(screen.getByRole('button', { name: /New user/ }));
    const form = screen.getByText('Create a user').closest('form')!;
    // Bug fix: CreateUserForm's labels used to be unassociated siblings of their inputs (a real
    // axe "label" violation, same class fixed on Login.tsx), so this test used to reach each field
    // positionally via getAllByRole('textbox') destructuring -- fragile, and it couldn't have
    // caught the bug it was working around. Now that each label has a real htmlFor/id pairing,
    // getByLabelText both reads more clearly and fails loudly if that association ever regresses.
    await user.type(within(form).getByLabelText('Full name'), 'New Admin');
    await user.type(within(form).getByLabelText('Email'), 'new-admin@example.com');
    await user.type(within(form).getByLabelText('Phone number'), '+919999999999');
    await user.type(within(form).getByLabelText('Temporary password'), 'TempPass123');
    await user.click(within(form).getByRole('button', { name: 'Create user' }));

    await waitFor(() => expect(adminUsersApi.create).toHaveBeenCalledWith({
      fullName: 'New Admin', email: 'new-admin@example.com', phoneNumber: '+919999999999', password: 'TempPass123',
    }));
  });
});
