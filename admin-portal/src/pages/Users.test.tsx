import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Users from './Users';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminUsersApi, adminRolesApi } from '../api/endpoints';
import type { UserSummaryDto } from '../types';

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

    await waitFor(() => expect(adminUsersApi.suspend).toHaveBeenCalledWith('user-1'));
    await waitFor(() => expect(notifySuccess).toHaveBeenCalledWith('User suspended.'));
  });

  it('shows an error notification when suspending fails', async () => {
    mockAuth(['USER_VIEW', 'USER_DELETE']);
    vi.mocked(adminUsersApi.list).mockResolvedValue(pagedResponse([AMY]));
    vi.mocked(adminUsersApi.suspend).mockRejectedValue({ response: { data: { message: 'Cannot suspend yourself.' } } });
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(screen.getByText('Amy Active')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: /Suspend/ }));

    await waitFor(() => expect(notifyError).toHaveBeenCalledWith('Cannot suspend yourself.'));
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
    // CreateUserForm's labels aren't id/htmlFor-associated with their inputs (a pre-existing gap
    // in this form, unrelated to Phase 5) -- fullName/email/phoneNumber/password are the only
    // four textbox-role inputs in this form, in that DOM order, so this is a reliable way to
    // reach each one without relying on that association.
    const [fullName, email, phoneNumber, password] = within(form).getAllByRole('textbox');
    await user.type(fullName, 'New Admin');
    await user.type(email, 'new-admin@example.com');
    await user.type(phoneNumber, '+919999999999');
    await user.type(password, 'TempPass123');
    await user.click(within(form).getByRole('button', { name: 'Create user' }));

    await waitFor(() => expect(adminUsersApi.create).toHaveBeenCalledWith({
      fullName: 'New Admin', email: 'new-admin@example.com', phoneNumber: '+919999999999', password: 'TempPass123',
    }));
  });
});
