import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import GlobalRules from './GlobalRules';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminRulesApi } from '../api/endpoints';

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
    vi.mocked(adminRulesApi.list).mockResolvedValue([]);

    renderPage();

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
  });

  it('renders an existing global rule', async () => {
    mockAuth(['RULE_MANAGE']);
    vi.mocked(adminRulesApi.list).mockResolvedValue([{
      id: 'rule-1', scope: 'GLOBAL', field: 'DESCRIPTION', operator: 'CONTAINS', comparisonValue: 'Netflix',
      actionType: 'MARK_SUBSCRIPTION', actionValue: null, priority: 100, enabled: true, matchCount: 3, lastMatchedAt: null,
    }]);

    renderPage();

    await waitFor(() => expect(screen.getByText(/Netflix/)).toBeInTheDocument());
    expect(screen.getByText('Enabled')).toBeInTheDocument();
  });

  it('shows the empty message when there are no global rules yet', async () => {
    mockAuth(['RULE_MANAGE']);
    vi.mocked(adminRulesApi.list).mockResolvedValue([]);

    renderPage();

    await waitFor(() => expect(screen.getByText('No global rules yet.')).toBeInTheDocument());
  });

  it('the test-match panel reports a match without creating or persisting a rule', async () => {
    mockAuth(['RULE_MANAGE']);
    vi.mocked(adminRulesApi.list).mockResolvedValue([]);
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
    vi.mocked(adminRulesApi.list).mockResolvedValue([]);
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
    vi.mocked(adminRulesApi.list).mockResolvedValue([]);
    vi.mocked(adminRulesApi.create).mockRejectedValue({ response: { data: { message: 'Duplicate rule.' } } });
    const user = userEvent.setup();

    renderPage();

    await user.click(await screen.findByText('New rule'));
    await user.type(screen.getByPlaceholderText('e.g. Swiggy'), 'Netflix');
    await user.type(screen.getByPlaceholderText('e.g. Dining'), 'Entertainment');
    await user.click(screen.getByRole('button', { name: 'Create rule' }));

    await waitFor(() => expect(notifyError).toHaveBeenCalledWith('Duplicate rule.'));
  });
});
