import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import GlobalRules from './GlobalRules';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminRulesApi } from '../api/endpoints';
import type { RuleDto } from '../types';

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
// GlobalRules.tsx now calls useNotify() (Admin Portal Phase 6) -- without this mock, rendering
// outside a real NotificationProvider throws before any assertion runs (see Users.test.tsx's
// identical fix note for the same class of bug).
const notifySuccess = vi.fn();
const notifyError = vi.fn();
vi.mock('../context/NotificationContext', () => ({
  useNotify: () => ({ success: notifySuccess, error: notifyError }),
}));
vi.mock('../api/endpoints', () => ({
  adminRulesApi: {
    list: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn(), test: vi.fn(),
  },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <GlobalRules />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

// See LearningEngine.test.tsx's mockAuth comment -- AdminLayout always renders Sidebar, which
// reads `permissions` off this same hook, so every mock here must supply it.
function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    fullName: 'Support Admin',
    logout: vi.fn(),
  }));
}

function pageOf(...rows: any[]) {
  return { content: rows, page: 0, size: 20, totalElements: rows.length, totalPages: 1 };
}

describe('GlobalRules', () => {
  beforeEach(() => {
    vi.mocked(useAdminAuth).mockReset();
    vi.mocked(adminRulesApi.list).mockReset();
    vi.mocked(adminRulesApi.test).mockReset();
    notifySuccess.mockReset();
    notifyError.mockReset();
  });

  it('shows an access-denied message when the account lacks RULE_MANAGE', () => {
    mockAuth([]);
    vi.mocked(adminRulesApi.list).mockResolvedValue(pageOf());

    renderPage();

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
  });

  it('renders an existing global rule', async () => {
    mockAuth(['RULE_MANAGE']);
    vi.mocked(adminRulesApi.list).mockResolvedValue(pageOf({
      id: 'rule-1', scope: 'GLOBAL', field: 'DESCRIPTION', operator: 'CONTAINS', comparisonValue: 'Netflix',
      actionType: 'MARK_SUBSCRIPTION', actionValue: null, priority: 100, enabled: true, matchCount: 3, lastMatchedAt: null,
    }));

    renderPage();

    await waitFor(() => expect(screen.getByText(/Netflix/)).toBeInTheDocument());
    expect(screen.getByText('Enabled')).toBeInTheDocument();
  });

  it('shows the empty message when there are no global rules yet', async () => {
    mockAuth(['RULE_MANAGE']);
    vi.mocked(adminRulesApi.list).mockResolvedValue(pageOf());

    renderPage();

    await waitFor(() => expect(screen.getByText('No global rules yet.')).toBeInTheDocument());
  });

  it('the test-match panel reports a match without creating or persisting a rule', async () => {
    mockAuth(['RULE_MANAGE']);
    vi.mocked(adminRulesApi.list).mockResolvedValue(pageOf());
    vi.mocked(adminRulesApi.test).mockResolvedValue({ matches: true });
    const user = userEvent.setup();

    renderPage();

    await user.click(await screen.findByText('New rule'));
    await user.type(screen.getByPlaceholderText('e.g. Swiggy'), 'Netflix');
    await user.click(screen.getByText('Test match'));

    // "Matches" is ambiguous on its own -- the (empty) rules DataTable behind the form also has
    // a "Matches" column header (<th>), always rendered regardless of row count. Scoping to
    // <span> picks out only TestRulePanel's result pill.
    await waitFor(() => expect(screen.getByText('Matches', { selector: 'span' })).toBeInTheDocument());
    expect(adminRulesApi.test).toHaveBeenCalledWith(expect.objectContaining({
      field: 'DESCRIPTION', operator: 'CONTAINS', comparisonValue: 'Netflix',
    }));
    // Dry-run only -- create() must never be called by the test panel itself.
    expect(adminRulesApi.create).not.toHaveBeenCalled();
  });

  it('shows a success notification after creating a rule', async () => {
    mockAuth(['RULE_MANAGE']);
    vi.mocked(adminRulesApi.list).mockResolvedValue(pageOf());
    vi.mocked(adminRulesApi.create).mockResolvedValue({
      id: 'rule-new', scope: 'GLOBAL', field: 'DESCRIPTION', operator: 'CONTAINS', comparisonValue: 'Netflix',
      actionType: 'ASSIGN_CATEGORY', actionValue: 'Entertainment', priority: 100, enabled: true, matchCount: 0, lastMatchedAt: null,
    });
    const user = userEvent.setup();

    renderPage();

    await user.click(await screen.findByText('New rule'));
    await user.type(screen.getByPlaceholderText('e.g. Swiggy'), 'Netflix');
    await user.type(screen.getByPlaceholderText('e.g. Dining'), 'Entertainment');
    await user.click(screen.getByRole('button', { name: 'Create rule' }));

    await waitFor(() => expect(notifySuccess).toHaveBeenCalledWith('Rule created.'));
  });

  it('shows an error notification when creating a rule fails', async () => {
    mockAuth(['RULE_MANAGE']);
    vi.mocked(adminRulesApi.list).mockResolvedValue(pageOf());
    vi.mocked(adminRulesApi.create).mockRejectedValue({ response: { data: { message: 'Duplicate rule.' } } });
    const user = userEvent.setup();

    renderPage();

    await user.click(await screen.findByText('New rule'));
    await user.type(screen.getByPlaceholderText('e.g. Swiggy'), 'Netflix');
    await user.type(screen.getByPlaceholderText('e.g. Dining'), 'Entertainment');
    await user.click(screen.getByRole('button', { name: 'Create rule' }));

    await waitFor(() => expect(notifyError).toHaveBeenCalledWith('Duplicate rule.'));
  });

  /** Seed data alone is already 46 GLOBAL rules (V19), and admins keep adding more -- the whole
   *  reason this page was moved off a fetch-all list. Proves the page state actually drives the
   *  next request, not just that Pagination renders. */
  it('requests the next page of rules when Pagination is clicked', async () => {
    mockAuth(['RULE_MANAGE']);
    vi.mocked(adminRulesApi.list).mockResolvedValue({
      content: [{
        id: 'rule-1', scope: 'GLOBAL', field: 'DESCRIPTION', operator: 'CONTAINS', comparisonValue: 'Netflix',
        actionType: 'MARK_SUBSCRIPTION', actionValue: null, priority: 100, enabled: true, matchCount: 3, lastMatchedAt: null,
      }],
      page: 0, size: 20, totalElements: 25, totalPages: 2,
    });

    renderPage();
    await waitFor(() => expect(screen.getByText(/Netflix/)).toBeInTheDocument());
    expect(screen.getByText('Page 1 of 2')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Next page' }));

    await waitFor(() => expect(adminRulesApi.list).toHaveBeenCalledWith(1, 20));
  });

  /** Bug fix: deleting the only rule on a page beyond the first used to leave the admin
   *  stranded looking at an empty table with no obvious way back -- Pagination still pointed at
   *  the now-nonexistent page. Confirms the page backs off automatically instead. */
  it('backs off to the previous page after deleting the last rule on a later page', async () => {
    mockAuth(['RULE_MANAGE']);
    vi.mocked(adminRulesApi.delete).mockResolvedValue(undefined as any);
    const netflixRule: RuleDto = {
      id: 'rule-1', scope: 'GLOBAL', field: 'DESCRIPTION', operator: 'CONTAINS', comparisonValue: 'Netflix',
      actionType: 'MARK_SUBSCRIPTION', actionValue: null, priority: 100, enabled: true, matchCount: 3, lastMatchedAt: null,
    };
    vi.mocked(adminRulesApi.list).mockResolvedValueOnce({
      content: [netflixRule], page: 0, size: 20, totalElements: 21, totalPages: 2,
    });
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(screen.getByText(/Netflix/)).toBeInTheDocument());

    vi.mocked(adminRulesApi.list).mockResolvedValueOnce({
      content: [{ ...netflixRule, id: 'rule-2', comparisonValue: 'Spotify' }],
      page: 1, size: 20, totalElements: 21, totalPages: 2,
    });
    await user.click(screen.getByRole('button', { name: 'Next page' }));
    await waitFor(() => expect(screen.getByText(/Spotify/)).toBeInTheDocument());

    vi.mocked(adminRulesApi.list).mockResolvedValueOnce(pageOf(netflixRule));
    await user.click(screen.getByTitle('Delete'));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }));

    await waitFor(() => expect(adminRulesApi.list).toHaveBeenLastCalledWith(0, 20));
  });
});
