import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { GlobalSearch } from './GlobalSearch';
import { adminSearchApi } from '../api/endpoints';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';

const mockNavigate = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => mockNavigate };
});
vi.mock('../api/endpoints', () => ({
  adminSearchApi: { search: vi.fn() },
}));
// GlobalSearch reads hasPermission('USER_VIEW') off this hook to decide whether it renders at
// all -- mocking it lets most tests below drive "an admin who can search" directly, and one test
// drive "an admin who can't" (see AdminSearchController.search's own doc comment on why USER_VIEW
// is the real backend gate this component now has to mirror).
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));

function renderSearch() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <GlobalSearch />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('GlobalSearch', () => {
  beforeEach(() => {
    vi.mocked(adminSearchApi.search).mockReset();
    mockNavigate.mockReset();
    // Default: an admin who holds USER_VIEW, same as every test below expected before this
    // permission gate existed. The one test that needs the opposite overrides this explicitly.
    vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
      permissions: ['USER_VIEW'],
      hasPermission: (p) => p === 'USER_VIEW',
    }));
  });

  /**
   * Bug fix: AdminSearchController.search() requires USER_VIEW (it used to have no @PreAuthorize
   * at all -- a real PII leak, see that controller's doc comment), but this component rendered
   * and queried unconditionally for any signed-in admin. An admin without USER_VIEW (e.g. a
   * support role scoped to just AUDIT_VIEW) got a search box that always 403'd, silently
   * misrepresented here as "no matches" since neither `data` nor `isFetching` distinguish the two.
   * Now hidden entirely for such an account, matching Sidebar.tsx's own pattern.
   */
  it('renders nothing for an admin without USER_VIEW', () => {
    vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({ permissions: [], hasPermission: () => false }));
    renderSearch();

    expect(screen.queryByPlaceholderText(/search users, merchants, banks, rules/i)).not.toBeInTheDocument();
  });

  it('never calls the search API for an admin without USER_VIEW, even if a query is somehow set', async () => {
    vi.mocked(adminSearchApi.search).mockResolvedValue([]);
    vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({ permissions: [], hasPermission: () => false }));
    renderSearch();

    await new Promise((resolve) => setTimeout(resolve, 400));
    expect(adminSearchApi.search).not.toHaveBeenCalled();
  });

  it('does not query the backend until at least 2 characters are typed', async () => {
    vi.mocked(adminSearchApi.search).mockResolvedValue([]);
    const user = userEvent.setup();
    renderSearch();

    await user.type(screen.getByPlaceholderText(/search users, merchants, banks, rules/i), 'a');

    // Give the 300ms debounce a chance to fire if it were going to (it shouldn't at 1 char).
    await new Promise((resolve) => setTimeout(resolve, 400));
    expect(adminSearchApi.search).not.toHaveBeenCalled();
  });

  it('shows grouped results once the debounced query resolves', async () => {
    vi.mocked(adminSearchApi.search).mockResolvedValue([
      { type: 'user', id: 'u1', title: 'Amazon Shopper', subtitle: 'amazon.shopper@example.com', link: '/users/u1' },
      { type: 'bank', id: 'hdfc', title: 'HDFC Bank Ltd', subtitle: 'HDFC', link: '/banks' },
    ]);
    const user = userEvent.setup();
    renderSearch();

    await user.type(screen.getByPlaceholderText(/search users, merchants, banks, rules/i), 'amazon');

    await waitFor(() => expect(screen.getByText('Amazon Shopper')).toBeInTheDocument(), { timeout: 2000 });
    expect(screen.getByText('HDFC Bank Ltd')).toBeInTheDocument();
    // Grouped by type -- one section header per type present in the results.
    expect(screen.getByText('Users')).toBeInTheDocument();
    expect(screen.getByText('Banks')).toBeInTheDocument();
  });

  it('shows a no-matches message when the search returns nothing', async () => {
    vi.mocked(adminSearchApi.search).mockResolvedValue([]);
    const user = userEvent.setup();
    renderSearch();

    await user.type(screen.getByPlaceholderText(/search users, merchants, banks, rules/i), 'zzz');

    await waitFor(() => expect(screen.getByText(/no matches for/i)).toBeInTheDocument(), { timeout: 2000 });
  });

  it('navigates to the result link and clears the query on selection', async () => {
    vi.mocked(adminSearchApi.search).mockResolvedValue([
      { type: 'user', id: 'u1', title: 'Amazon Shopper', subtitle: 'amazon.shopper@example.com', link: '/users/u1' },
    ]);
    const user = userEvent.setup();
    renderSearch();

    const input = screen.getByPlaceholderText(/search users, merchants, banks, rules/i);
    await user.type(input, 'amazon');
    await waitFor(() => expect(screen.getByText('Amazon Shopper')).toBeInTheDocument(), { timeout: 2000 });

    await user.click(screen.getByText('Amazon Shopper'));

    expect(mockNavigate).toHaveBeenCalledWith('/users/u1');
    expect(input).toHaveValue('');
  });

  it('clears the query when the clear button is clicked', async () => {
    vi.mocked(adminSearchApi.search).mockResolvedValue([]);
    const user = userEvent.setup();
    renderSearch();

    const input = screen.getByPlaceholderText(/search users, merchants, banks, rules/i);
    await user.type(input, 'test');
    expect(input).toHaveValue('test');

    await user.click(screen.getByLabelText('Clear search'));

    expect(input).toHaveValue('');
  });
});
