import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import MerchantTemplates from './MerchantTemplates';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminMerchantTemplatesApi } from '../api/endpoints';
import type { TestMerchantTemplateResult } from '../types';

vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
// Same fix note as GlobalRules.test.tsx's identical mock: without this, rendering outside a real
// NotificationProvider throws before any assertion runs.
const notifySuccess = vi.fn();
const notifyError = vi.fn();
vi.mock('../context/NotificationContext', () => ({
  useNotify: () => ({ success: notifySuccess, error: notifyError }),
}));
vi.mock('../api/endpoints', () => ({
  adminMerchantTemplatesApi: {
    list: vi.fn(), create: vi.fn(), update: vi.fn(), activate: vi.fn(), deactivate: vi.fn(), test: vi.fn(),
  },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <MerchantTemplates />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

// See GlobalRules.test.tsx's mockAuth comment -- AdminLayout always renders Sidebar, which reads
// `permissions` off this same hook, so every mock here must supply it.
function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    fullName: 'Support Admin',
    logout: vi.fn(),
  }));
}

const EXISTING_TEMPLATE = {
  id: 'tmpl-1', merchantDomain: 'uber.com', merchantName: 'Uber', receiptMarker: 'Trip Fare',
  amountPattern: 'Total: Rs. {amount}', datePattern: 'Trip Date: {date}', enabled: true,
  createdByUserId: null, createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z',
  domainIsTrusted: true,
};

describe('MerchantTemplates', () => {
  beforeEach(() => {
    vi.mocked(useAdminAuth).mockReset();
    vi.mocked(adminMerchantTemplatesApi.list).mockReset();
    vi.mocked(adminMerchantTemplatesApi.create).mockReset();
    vi.mocked(adminMerchantTemplatesApi.test).mockReset();
    vi.mocked(adminMerchantTemplatesApi.activate).mockReset();
    notifySuccess.mockReset();
    notifyError.mockReset();
  });

  it('shows an access-denied message when the account lacks MERCHANT_MANAGE', () => {
    mockAuth([]);
    vi.mocked(adminMerchantTemplatesApi.list).mockResolvedValue([]);

    renderPage();

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
  });

  it('renders an existing template', async () => {
    mockAuth(['MERCHANT_MANAGE']);
    vi.mocked(adminMerchantTemplatesApi.list).mockResolvedValue([EXISTING_TEMPLATE]);

    renderPage();

    await waitFor(() => expect(screen.getByText(/Uber/)).toBeInTheDocument());
    expect(screen.getByText('uber.com', { exact: false })).toBeInTheDocument();
    expect(screen.getByText('Active')).toBeInTheDocument();
  });

  it('shows the empty message when there are no templates yet', async () => {
    mockAuth(['MERCHANT_MANAGE']);
    vi.mocked(adminMerchantTemplatesApi.list).mockResolvedValue([]);

    renderPage();

    await waitFor(() => expect(screen.getByText('No merchant templates yet.')).toBeInTheDocument());
  });

  it('the test panel reports a parsed result without creating or persisting a template', async () => {
    mockAuth(['MERCHANT_MANAGE']);
    vi.mocked(adminMerchantTemplatesApi.list).mockResolvedValue([]);
    vi.mocked(adminMerchantTemplatesApi.test).mockResolvedValue({
      status: 'PARSED', reason: null, amount: 499, transactionDate: '2026-08-12',
      confidence: 0.9, violations: [],
    });
    const user = userEvent.setup();

    renderPage();

    await user.click(await screen.findByText('New template'));
    await user.type(screen.getByPlaceholderText('e.g. swiggy.com'), 'swiggy.com');
    await user.type(screen.getByPlaceholderText('e.g. Swiggy'), 'Swiggy');
    await user.type(
      screen.getByPlaceholderText('A literal phrase every receipt from this merchant contains, e.g. Order Summary'),
      'Order Summary'
    );
    await user.type(screen.getByPlaceholderText('e.g. Grand Total: Rs. {amount}'), 'Grand Total: Rs. {amount}');
    await user.type(screen.getByPlaceholderText('e.g. Order Date: {date}'), 'Order Date: {date}');
    await user.type(
      screen.getByPlaceholderText("Paste the sample email's HTML (or plain text) here"),
      'Order Summary Grand Total: Rs. 499.00 Order Date: August 12, 2026'
    );
    await user.click(screen.getByText('Test template'));

    await waitFor(() => expect(screen.getByText(/Parsed -- amount 499/)).toBeInTheDocument());
    expect(adminMerchantTemplatesApi.test).toHaveBeenCalledWith(expect.objectContaining({
      merchantDomain: 'swiggy.com', receiptMarker: 'Order Summary',
    }));
    // Dry-run only -- create() must never be called by the test panel itself.
    expect(adminMerchantTemplatesApi.create).not.toHaveBeenCalled();
  });

  it('shows a success notification after creating a template, disabled', async () => {
    mockAuth(['MERCHANT_MANAGE']);
    vi.mocked(adminMerchantTemplatesApi.list).mockResolvedValue([]);
    vi.mocked(adminMerchantTemplatesApi.create).mockResolvedValue({ ...EXISTING_TEMPLATE, enabled: false });
    const user = userEvent.setup();

    renderPage();

    await user.click(await screen.findByText('New template'));
    await user.type(screen.getByPlaceholderText('e.g. swiggy.com'), 'swiggy.com');
    await user.type(screen.getByPlaceholderText('e.g. Swiggy'), 'Swiggy');
    await user.type(
      screen.getByPlaceholderText('A literal phrase every receipt from this merchant contains, e.g. Order Summary'),
      'Order Summary'
    );
    await user.type(screen.getByPlaceholderText('e.g. Grand Total: Rs. {amount}'), 'Grand Total: Rs. {amount}');
    await user.type(screen.getByPlaceholderText('e.g. Order Date: {date}'), 'Order Date: {date}');
    await user.click(screen.getByRole('button', { name: 'Create template' }));

    await waitFor(() => expect(notifySuccess).toHaveBeenCalledWith(
      'Template created, disabled pending a successful test.'
    ));
  });

  it('shows an error notification when creating a template fails', async () => {
    mockAuth(['MERCHANT_MANAGE']);
    vi.mocked(adminMerchantTemplatesApi.list).mockResolvedValue([]);
    vi.mocked(adminMerchantTemplatesApi.create).mockRejectedValue({
      response: { data: { message: 'amazon.in is already handled by a hand-written parser.' } },
    });
    const user = userEvent.setup();

    renderPage();

    await user.click(await screen.findByText('New template'));
    await user.type(screen.getByPlaceholderText('e.g. swiggy.com'), 'amazon.in');
    await user.type(screen.getByPlaceholderText('e.g. Swiggy'), 'Amazon');
    await user.type(
      screen.getByPlaceholderText('A literal phrase every receipt from this merchant contains, e.g. Order Summary'),
      'Order #'
    );
    await user.type(screen.getByPlaceholderText('e.g. Grand Total: Rs. {amount}'), 'Total: Rs. {amount}');
    await user.type(screen.getByPlaceholderText('e.g. Order Date: {date}'), 'Date: {date}');
    await user.click(screen.getByRole('button', { name: 'Create template' }));

    await waitFor(() => expect(notifyError).toHaveBeenCalledWith(
      'amazon.in is already handled by a hand-written parser.'
    ));
  });

  /** The core safety rail this whole feature exists for: an admin must not be able to activate a
   *  disabled template without a passing test against its CURRENT field values. */
  it('Activate stays disabled for a disabled template until a test passes', async () => {
    mockAuth(['MERCHANT_MANAGE']);
    const disabled = { ...EXISTING_TEMPLATE, enabled: false };
    vi.mocked(adminMerchantTemplatesApi.list).mockResolvedValue([disabled]);
    vi.mocked(adminMerchantTemplatesApi.test).mockResolvedValue({
      status: 'PARSED', reason: null, amount: 255, transactionDate: '2026-08-12',
      confidence: 0.9, violations: [],
    });
    const user = userEvent.setup();

    renderPage();
    await user.click(await screen.findByTitle('Edit / test'));

    const activateButton = await screen.findByRole('button', { name: 'Activate' });
    expect(activateButton).toBeDisabled();

    await user.type(
      screen.getByPlaceholderText("Paste the sample email's HTML (or plain text) here"),
      'Trip Fare Total: Rs. 255.00 Trip Date: August 12, 2026'
    );
    await user.click(screen.getByText('Test template'));

    await waitFor(() => expect(activateButton).toBeEnabled());
  });

  it('activating calls the API and shows a success notification', async () => {
    mockAuth(['MERCHANT_MANAGE']);
    const disabled = { ...EXISTING_TEMPLATE, enabled: false };
    vi.mocked(adminMerchantTemplatesApi.list).mockResolvedValue([disabled]);
    vi.mocked(adminMerchantTemplatesApi.test).mockResolvedValue({
      status: 'PARSED', reason: null, amount: 255, transactionDate: '2026-08-12',
      confidence: 0.9, violations: [],
    });
    vi.mocked(adminMerchantTemplatesApi.activate).mockResolvedValue({ ...disabled, enabled: true });
    const user = userEvent.setup();

    renderPage();
    await user.click(await screen.findByTitle('Edit / test'));
    await user.type(
      screen.getByPlaceholderText("Paste the sample email's HTML (or plain text) here"),
      'Trip Fare Total: Rs. 255.00 Trip Date: August 12, 2026'
    );
    await user.click(screen.getByText('Test template'));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Activate' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: 'Activate' }));

    await waitFor(() => expect(adminMerchantTemplatesApi.activate).toHaveBeenCalledWith('tmpl-1'));
    await waitFor(() => expect(notifySuccess).toHaveBeenCalledWith('Template activated.'));
  });

  /** Regression coverage for a real bug: TestTemplatePanel originally read
   *  receiptMarker/amountPattern/datePattern from component props inside its onSuccess callback,
   *  not from what was actually sent when the test request was made. Editing a field while a test
   *  is still in flight would then attribute the pass to whatever is CURRENTLY typed when the
   *  response arrives, not to what was actually tested -- silently enabling Activate for an
   *  untested value. The fix threads the tested values through as the mutation's own variables,
   *  which TanStack Query passes to onSuccess as a fixed argument, immune to later renders. */
  it('does not enable Activate if the receipt marker changes while a test is still in flight', async () => {
    mockAuth(['MERCHANT_MANAGE']);
    const disabled = { ...EXISTING_TEMPLATE, enabled: false };
    vi.mocked(adminMerchantTemplatesApi.list).mockResolvedValue([disabled]);
    let resolveTest: ((result: TestMerchantTemplateResult) => void) | undefined;
    vi.mocked(adminMerchantTemplatesApi.test).mockImplementationOnce(
      () => new Promise((resolve) => { resolveTest = resolve; })
    );
    const user = userEvent.setup();

    renderPage();
    await user.click(await screen.findByTitle('Edit / test'));
    await user.type(
      screen.getByPlaceholderText("Paste the sample email's HTML (or plain text) here"),
      'Trip Fare Total: Rs. 255.00 Trip Date: August 12, 2026'
    );
    // Tests against the marker as it stands right now ("Trip Fare", from EXISTING_TEMPLATE) --
    // the request is in flight and NOT yet resolved.
    await user.click(screen.getByText('Test template'));

    // The admin edits the marker before the response comes back -- the exact race the bug
    // depended on. The marker input has no accessible label of its own in this form; it's
    // identified by its current value instead.
    const markerInput = screen.getByDisplayValue('Trip Fare');
    await user.clear(markerInput);
    await user.type(markerInput, 'Changed Marker');

    // NOW the test call resolves, reporting PARSED -- but against "Trip Fare", not the
    // currently-typed "Changed Marker".
    await act(async () => resolveTest?.({
      status: 'PARSED', reason: null, amount: 255, transactionDate: '2026-08-12',
      confidence: 0.9, violations: [],
    }));

    // The bug: this used to pass, because onSuccess read the (by-then-changed) marker straight
    // out of props instead of the mutation's own variables.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Activate' })).toBeDisabled());
  });
});
