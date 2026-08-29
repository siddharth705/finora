import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Banks from './Banks';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminBanksApi } from '../api/endpoints';
import type { BankDto } from '../types';

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
vi.mock('../api/endpoints', () => ({
  adminBanksApi: { list: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn(), audit: vi.fn() },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Banks />
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

const HDFC: BankDto = {
  id: 'hdfc-custom', officialName: 'HDFC Bank Custom Ltd.', shortName: 'HDFC Custom',
  colorHex: '#004C8F', initials: 'HC', logoPath: '/assets/banks/generic.svg',
  category: 'PRIVATE', websiteUrl: 'https://hdfcbank.com', ifscPrefix: 'HDFC',
  supportedAccountTypes: ['SAVINGS', 'CREDIT_CARD'],
};

function pageOf(...rows: BankDto[]) {
  return { content: rows, page: 0, size: 20, totalElements: rows.length, totalPages: 1 };
}

describe('Banks', () => {
  beforeEach(() => {
    vi.mocked(adminBanksApi.list).mockReset();
    vi.mocked(adminBanksApi.create).mockReset();
    vi.mocked(adminBanksApi.update).mockReset();
    vi.mocked(adminBanksApi.delete).mockReset();
    vi.mocked(adminBanksApi.audit).mockReset();
  });

  it('shows an access-denied message when the account lacks BANK_MANAGE', () => {
    mockAuth([]);
    vi.mocked(adminBanksApi.list).mockResolvedValue(pageOf());

    renderPage();

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
  });

  it('renders the bank list', async () => {
    mockAuth(['BANK_MANAGE']);
    vi.mocked(adminBanksApi.list).mockResolvedValue(pageOf(HDFC));

    renderPage();

    await waitFor(() => expect(screen.getByText('HDFC Custom')).toBeInTheDocument());
    expect(screen.getByText('HDFC Bank Custom Ltd.')).toBeInTheDocument();
  });

  it('opens the EntityDrawer with Summary details when a bank is clicked', async () => {
    mockAuth(['BANK_MANAGE']);
    vi.mocked(adminBanksApi.list).mockResolvedValue(pageOf(HDFC));
    const user = userEvent.setup();

    renderPage();

    await waitFor(() => expect(screen.getByText('HDFC Custom')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: /HDFC Custom/ }));

    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('hdfcbank.com', { exact: false })).toBeInTheDocument();
    expect(within(dialog).getByText('PRIVATE')).toBeInTheDocument();
  });

  /**
   * Bug fix / security hardening: Bank.websiteUrl has no scheme validation on the backend -- any
   * BANK_MANAGE admin could set it to a `javascript:` URL, and this used to render it as a real,
   * clickable <a href> to every OTHER admin who opens this bank's Summary tab. Clicking it would
   * execute the value as a script in the admin portal's own origin. See lib/safeUrl.test.ts for
   * unit coverage of the guard itself; this pins that Banks.tsx actually applies it.
   */
  it('shows an unsafe websiteUrl as plain text, never as a clickable link', async () => {
    mockAuth(['BANK_MANAGE']);
    vi.mocked(adminBanksApi.list).mockResolvedValue(pageOf(
      { ...HDFC, websiteUrl: 'javascript:alert(document.cookie)' },
    ));
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(screen.getByText('HDFC Custom')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: /HDFC Custom/ }));

    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('javascript:alert(document.cookie)')).toBeInTheDocument();
    expect(within(dialog).queryByRole('link')).not.toBeInTheDocument();
  });

  it('shows structural fields on the Metadata tab', async () => {
    mockAuth(['BANK_MANAGE']);
    vi.mocked(adminBanksApi.list).mockResolvedValue(pageOf(HDFC));
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(screen.getByText('HDFC Custom')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: /HDFC Custom/ }));
    await user.click(screen.getByRole('button', { name: 'Metadata' }));

    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('hdfc-custom')).toBeInTheDocument();
    expect(within(dialog).getByText('SAVINGS')).toBeInTheDocument();
    expect(within(dialog).getByText('CREDIT_CARD')).toBeInTheDocument();
  });

  it('loads and shows real audit history on the Audit tab', async () => {
    mockAuth(['BANK_MANAGE']);
    vi.mocked(adminBanksApi.list).mockResolvedValue(pageOf(HDFC));
    vi.mocked(adminBanksApi.audit).mockResolvedValue([
      {
        id: 'log-1', userId: 'admin-1', action: 'BANK_UPDATED', entityType: 'Bank', entityId: null,
        metadata: { bankId: 'hdfc-custom' }, requestId: null, createdAt: new Date().toISOString(),
      },
    ]);
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(screen.getByText('HDFC Custom')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: /HDFC Custom/ }));
    await user.click(screen.getByRole('button', { name: 'Audit' }));

    await waitFor(() => expect(screen.getByText('BANK_UPDATED')).toBeInTheDocument());
    expect(adminBanksApi.audit).toHaveBeenCalledWith('hdfc-custom');
  });

  it('shows an empty-state message on the Audit tab when there is no recorded history', async () => {
    mockAuth(['BANK_MANAGE']);
    vi.mocked(adminBanksApi.list).mockResolvedValue(pageOf(HDFC));
    vi.mocked(adminBanksApi.audit).mockResolvedValue([]);
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(screen.getByText('HDFC Custom')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: /HDFC Custom/ }));
    await user.click(screen.getByRole('button', { name: 'Audit' }));

    await waitFor(() => expect(screen.getByText('No recorded history for this bank.')).toBeInTheDocument());
  });

  it('edits a bank via the Summary tab edit toggle and saves', async () => {
    mockAuth(['BANK_MANAGE']);
    vi.mocked(adminBanksApi.list).mockResolvedValue(pageOf(HDFC));
    const updated = { ...HDFC, shortName: 'HDFC Renamed' };
    vi.mocked(adminBanksApi.update).mockResolvedValue(updated);
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(screen.getByText('HDFC Custom')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: /HDFC Custom/ }));
    await user.click(screen.getByRole('button', { name: /Edit details/ }));

    const dialog = screen.getByRole('dialog');
    const shortNameInput = within(dialog).getByDisplayValue('HDFC Custom');
    await user.clear(shortNameInput);
    await user.type(shortNameInput, 'HDFC Renamed');
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(adminBanksApi.update).toHaveBeenCalledWith(
      'hdfc-custom', expect.objectContaining({ shortName: 'HDFC Renamed' })
    ));
  });

  it('deletes a bank after confirmation', async () => {
    mockAuth(['BANK_MANAGE']);
    vi.mocked(adminBanksApi.list).mockResolvedValue(pageOf(HDFC));
    vi.mocked(adminBanksApi.delete).mockResolvedValue(undefined as any);
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(screen.getByText('HDFC Custom')).toBeInTheDocument());
    await user.click(screen.getByTitle('Delete'));

    // Custom in-app confirmation (ConfirmDialog), not the browser's own confirm() -- see this
    // page's own doc comment on confirmDeleteBank for why.
    expect(await screen.findByText('Remove HDFC Custom?')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Remove' }));

    await waitFor(() => expect(adminBanksApi.delete).toHaveBeenCalledWith('hdfc-custom'));
  });

  it('creates a new bank via the Add bank form', async () => {
    mockAuth(['BANK_MANAGE']);
    vi.mocked(adminBanksApi.list).mockResolvedValue(pageOf());
    vi.mocked(adminBanksApi.create).mockResolvedValue(HDFC);
    const user = userEvent.setup();

    renderPage();
    await waitFor(() => expect(screen.getByText('No custom banks added yet.')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: /Add bank/ }));
    await user.type(screen.getByPlaceholderText('e.g. IOB'), 'IOB');
    await user.type(screen.getByPlaceholderText('e.g. Indian Overseas Bank'), 'Indian Overseas Bank');
    await user.type(screen.getByPlaceholderText('e.g. Indian Overseas Bank Ltd.'), 'Indian Overseas Bank Ltd.');
    await user.click(screen.getByRole('button', { name: 'Add bank' }));

    await waitFor(() => expect(adminBanksApi.create).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'IOB', shortName: 'Indian Overseas Bank', officialName: 'Indian Overseas Bank Ltd.' })
    ));
  });

  /** The custom-bank catalog is small (dozens to low hundreds, see
   *  BankManagementService.listCustom's own doc comment) -- pagination here is UI consistency
   *  with every other admin list page, not a scale fix. Still worth proving the page state
   *  actually drives the next request. */
  it('requests the next page of banks when Pagination is clicked', async () => {
    mockAuth(['BANK_MANAGE']);
    vi.mocked(adminBanksApi.list).mockResolvedValue(
      { content: [HDFC], page: 0, size: 20, totalElements: 25, totalPages: 2 }
    );

    renderPage();
    await waitFor(() => expect(screen.getByText('HDFC Custom')).toBeInTheDocument());
    expect(screen.getByText('Page 1 of 2')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Next page' }));

    await waitFor(() => expect(adminBanksApi.list).toHaveBeenCalledWith(1, 20));
  });
});
