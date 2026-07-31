import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { GlobalSearch } from './GlobalSearch';
import { adminSearchApi } from '../api/endpoints';

const mockNavigate = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => mockNavigate };
});
vi.mock('../api/endpoints', () => ({
  adminSearchApi: { search: vi.fn() },
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
